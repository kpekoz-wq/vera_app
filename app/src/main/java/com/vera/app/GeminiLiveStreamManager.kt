package com.vera.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages real-time bidirectional audio streaming with Google's Gemini Multimodal Live API over WebSockets.
 * Continuously streams 16kHz PCM audio from the vehicle microphone to Gemini Live, and plays back
 * 24kHz PCM native human AI voice speech over car audio or device speakers, with real-time
 * read-only vehicle telemetry function calling, live driver/assistant chat transcription, and acoustic echo isolation.
 */
class GeminiLiveStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveStream"
        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
        private const val LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
        /** If Gemini is flagged "speaking" but nothing has happened for this long, release the mic so she can't go deaf. */
        private const val MODEL_ACTIVITY_WATCHDOG_MS = 5_000L
        /** Give up (and tell the user) after this many failed reconnect attempts (~2 minutes). */
        private const val MAX_RECONNECT_ATTEMPTS = 15
        val SUPPORTED_LIVE_VOICES = setOf(
            "Aoede", "Kore", "Zephyr", "Fenrir", "Puck", "Charon"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite streaming connection
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // Send WebSocket PING every 15s for dead-socket detection
        .retryOnConnectionFailure(true)
        .build()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var playbackJob: Job? = null
    private var sessionTimeoutJob: Job? = null
    private var turnCompletionJob: Job? = null
    private var reconnectJob: Job? = null
    private var audioPlaybackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var totalFramesWritten = 0L
    @Volatile
    private var isPlayingActive = false

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _isGeminiSpeaking = MutableStateFlow(false)
    val isGeminiSpeaking: StateFlow<Boolean> = _isGeminiSpeaking.asStateFlow()

    private val _isUserSpeaking = MutableStateFlow(false)
    val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking.asStateFlow()

    private val _lastTranscriptText = MutableStateFlow("")
    val lastTranscriptText: StateFlow<String> = _lastTranscriptText.asStateFlow()

    private val voicePrefs by lazy {
        context.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
    }

    private val _voiceName = MutableStateFlow(
        context.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
            .getString("selected_live_voice", "Aoede") ?: "Aoede"
    )
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    fun setVoice(name: String) {
        val trimmed = name.trim()
        val found = SUPPORTED_LIVE_VOICES.firstOrNull { it.equals(trimmed, ignoreCase = true) }
        val normalized = when {
            found != null -> found
            trimmed.equals("vega", ignoreCase = true) -> "Aoede"
            else -> {
                Log.w(TAG, "Voice '$name' not recognized. Defaulting to 'Aoede'.")
                "Aoede"
            }
        }
        _voiceName.value = normalized
        voicePrefs.edit().putString("selected_live_voice", normalized).apply()
        Log.i(TAG, "Selected Gemini Live Voice: $normalized (requested: '$name')")

        if (_isLiveSessionActive.value && lastApiKey.isNotBlank()) {
            Log.i(TAG, "Live session active during voice change; reconnecting session with new voice '$normalized'")
            startLiveSession(
                apiKey = lastApiKey,
                systemInstruction = lastSystemInstruction,
                telemetrySummary = lastTelemetrySummary,
                conversationHistory = lastConversationHistory,
                onMessageReceived = lastOnMessageReceived ?: {},
                onUserTranscriptReceived = lastOnUserTranscriptReceived,
                onTurnComplete = lastOnTurnComplete,
                onToolCall = lastOnToolCall,
                onError = lastOnError
            )
        }
    }

    private var currentHasPriorHistory = false
    private var lastApiKey: String = ""
    private var lastSystemInstruction: String = ""
    private var lastTelemetrySummary: String = ""
    private var lastTelemetryProvider: (() -> String)? = null
    private var lastConversationHistory: String = ""
    private var lastOnMessageReceived: ((String) -> Unit)? = null
    private var lastOnUserTranscriptReceived: ((String) -> Unit)? = null
    private var lastOnTurnComplete: (() -> Unit)? = null
    private var lastOnToolCall: ((String, String, JSONObject) -> JSONObject)? = null
    private var lastOnError: ((String) -> Unit)? = null

    @Volatile
    private var isExplicitlyStopped = false
    @Volatile
    private var isReconnecting = false
    private var reconnectAttempts = 0

    /** Incremented every time a socket is created or torn down. Callbacks from older sockets are ignored. */
    private val socketGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    /** Gemini session-resumption handle: lets a reconnect continue the SAME conversation server-side. */
    @Volatile
    private var resumeHandle: String? = null
    /** True for a socket that is a reconnect (suppresses the "I'm back" greeting + mic gating). */
    @Volatile
    private var pendingReconnectSetup = false
    /** True once the server has acknowledged our setup frame on the current socket. */
    @Volatile
    private var setupCompleted = false
    /** Falls back to a plain setup frame if the server rejects resumption / compression fields. */
    @Volatile
    private var useSessionFeatures = true
    @Volatile
    private var lastModelActivityMs = 0L
    private val toolSupervisor = SupervisorJob()
    private var goAwayJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startLiveSession(
        apiKey: String,
        systemInstruction: String,
        telemetrySummary: String,
        conversationHistory: String = "",
        telemetryProvider: (() -> String)? = null,
        onMessageReceived: (String) -> Unit,
        onUserTranscriptReceived: ((String) -> Unit)? = null,
        onTurnComplete: (() -> Unit)? = null,
        onToolCall: ((String, String, JSONObject) -> JSONObject)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (apiKey.isBlank()) {
            val err = "Cannot start Gemini Live: API key is blank."
            Log.e(TAG, err)
            onError?.invoke(err)
            return
        }
        stopLiveSession()

        isExplicitlyStopped = false
        isReconnecting = false
        reconnectAttempts = 0
        resumeHandle = null
        pendingReconnectSetup = false
        setupCompleted = false

        lastApiKey = apiKey
        lastSystemInstruction = systemInstruction
        lastTelemetrySummary = telemetrySummary
        lastTelemetryProvider = telemetryProvider
        lastConversationHistory = conversationHistory
        lastOnMessageReceived = onMessageReceived
        lastOnUserTranscriptReceived = onUserTranscriptReceived
        lastOnTurnComplete = onTurnComplete
        lastOnToolCall = onToolCall
        lastOnError = onError

        currentHasPriorHistory = conversationHistory.isNotBlank()

        // 30-minute maximum driving session length limit
        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = scope.launch {
            delay(30 * 60 * 1000L) // 30 minutes
            Log.i(TAG, "Gemini Live driving session limit reached (30 minutes). Stopping session automatically.")
            stopLiveSession()
        }

        _isLiveSessionActive.value = true
        _isGeminiSpeaking.value = false // Microphone is unsuppressed and ready for driver input
        lastModelActivityMs = System.currentTimeMillis()

        registerNetworkCallbackIfNeeded()
        initAudioPlayer()
        connectWebSocket(isReconnect = false)
    }

    private fun connectWebSocket(isReconnect: Boolean) {
        if (isExplicitlyStopped || !_isLiveSessionActive.value) return

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$lastApiKey"
        val request = Request.Builder().url(wsUrl).build()
        val myGen = socketGeneration.incrementAndGet()
        pendingReconnectSetup = isReconnect
        setupCompleted = false

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun isStale() = myGen != socketGeneration.get()

            override fun onOpen(ws: WebSocket, response: Response) {
                if (isStale()) {
                    ws.cancel()
                    return
                }
                Log.i(TAG, "Gemini Live WebSocket opened successfully (isReconnect=$isReconnect, resuming=${resumeHandle != null}).")

                // 1. Send Setup Frame FIRST with Voice, Tools, & Fresh Vehicle System Instructions
                val freshTelemetry = try { lastTelemetryProvider?.invoke() } catch (_: Exception) { null } ?: lastTelemetrySummary
                lastTelemetrySummary = freshTelemetry
                sendSetupFrame(ws, lastSystemInstruction, freshTelemetry, lastConversationHistory, _voiceName.value, resumeHandle)

                // 2. Start Microphone PCM Audio Streaming AFTER setup frame is sent
                startMicrophoneStreaming(ws)

                // If reconnecting silently while driving, release mic immediately without waiting for speech greeting
                if (isReconnect) {
                    _isGeminiSpeaking.value = false
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isStale()) return
                handleServerMessage(ws, text, lastOnMessageReceived ?: {}, lastOnUserTranscriptReceived, lastOnTurnComplete, lastOnToolCall)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (isStale()) return
                handleServerMessage(ws, bytes.utf8(), lastOnMessageReceived ?: {}, lastOnUserTranscriptReceived, lastOnTurnComplete, lastOnToolCall)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                Log.w(TAG, "Gemini Live WebSocket failure: ${t.message} (http=$httpCode, stale=${isStale()})")

                // Ignore failures from sockets we already replaced, or after an intentional stop
                if (isStale() || isExplicitlyStopped || !_isLiveSessionActive.value) return

                // Bad API key / bad request: retrying can never succeed, so tell the user instead of looping
                if (httpCode != null && httpCode in 400..499 && httpCode != 429) {
                    failSession("Gemini Live rejected the connection (HTTP $httpCode). Check the API key.")
                    return
                }

                // EVERY other failure (including "Socket closed" / timeouts) means the session is dead -> reconnect
                onSocketLost("Connection failure: ${t.message}", null)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closing: $reason ($code, stale=${isStale()})")
                if (isStale() || isExplicitlyStopped || !_isLiveSessionActive.value) return
                // A server-initiated close (even code 1000 when its time limit is reached) must NOT end the
                // hands-free session: reconnect and resume instead.
                onSocketLost("Server closing ($code: $reason)", code)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed ($code, stale=${isStale()}).")
                if (isStale() || isExplicitlyStopped || !_isLiveSessionActive.value) return
                onSocketLost("Socket closed ($code)", code)
            }
        })
    }

    /**
     * Called when the current socket died unexpectedly. Falls back to a plain setup frame if the server
     * rejected our resumption/compression fields before setup completed, then schedules a reconnect.
     */
    private fun onSocketLost(reason: String, closeCode: Int?) {
        if (!setupCompleted && (closeCode == 1007 || closeCode == 1008)) {
            if (useSessionFeatures) {
                Log.w(TAG, "Server rejected setup ($closeCode). Retrying without session-resumption / compression.")
                useSessionFeatures = false
                resumeHandle = null
            } else {
                failSession("Gemini Live rejected the session setup ($closeCode).")
                return
            }
        }
        attemptReconnect(reason)
    }

    /** Stops the session and reports the error to the UI (stopLiveSession clears the callback first). */
    private fun failSession(message: String) {
        val callback = lastOnError
        stopLiveSession()
        callback?.invoke(message)
    }

    private fun sendSetupFrame(
        ws: WebSocket,
        systemInstruction: String,
        telemetrySummary: String,
        conversationHistory: String,
        selectedVoice: String,
        resumeHandle: String? = null
    ) {
        // When resuming, the server already holds the conversation, so do not paste history a second time
        val historySection = if (conversationHistory.isNotBlank() && resumeHandle.isNullOrBlank()) {
            """
            
            PRIOR CONVERSATION HISTORY IN THIS SESSION (MAINTAIN CONTINUITY):
            $conversationHistory
            
            CONTINUITY DIRECTIVE: You have complete context of the previous conversation history above. Continue seamlessly without re-introducing yourself if you were already discussing a topic.
            """.trimIndent()
        } else ""

        val fullInstruction = """
            $systemInstruction
            
            CURRENT LIVE VEHICLE TELEMETRY SUMMARY AT SESSION START:
            $telemetrySummary
            $historySection
            
            VOICE DIRECTIVE: Speak naturally in plain English like a real human mechanic sitting in the passenger seat.
        """.trimIndent()

        val stringParam = { desc: String ->
            JSONObject().apply {
                put("type", "STRING")
                put("description", desc)
            }
        }
        val numberParam = { desc: String ->
            JSONObject().apply {
                put("type", "NUMBER")
                put("description", desc)
            }
        }
        val intParam = { desc: String ->
            JSONObject().apply {
                put("type", "INTEGER")
                put("description", desc)
            }
        }
        val booleanParam = { desc: String ->
            JSONObject().apply {
                put("type", "BOOLEAN")
                put("description", desc)
            }
        }

        val toolsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("functionDeclarations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "get_live_data")
                        put("description", "Returns current live vehicle telemetry, engine status, and high-speed CAN bus dynamics (RPM, speed in mph/kmh, coolant temp, battery voltage, throttle, fuel trims, MAP/MAF, steering angle, wheel speeds, brake pressure, yaw rate, transmission gear). Invoke whenever driver asks about speed, engine status, temperatures, battery, steering, or chassis dynamics.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("sensor_focus", stringParam("Specific sensor requested, e.g. 'steering', 'coolant', 'rpm', 'speed', 'battery', 'all'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_dtcs")
                        put("description", "Scans active, pending, and permanent Diagnostic Trouble Codes (DTCs) logged by the vehicle ECU across all modules.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("scan_scope", stringParam("Scope of DTC scan, e.g. 'all', 'stored', 'pending'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_mode06_results")
                        put("description", "Returns Mode 06 on-board diagnostic monitor test results (misfire counters per cylinder, EVAP limits, O2 sensor monitors).")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("monitor_type", stringParam("Monitor type to inspect, e.g. 'misfires', 'evap', 'o2', 'all'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_vehicle_info")
                        put("description", "Returns vehicle identification number (VIN), decoded make, model, year, total odometer mileage reading (in miles and km), and adapter connection status.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("info_type", stringParam("Information requested, e.g. 'odometer', 'mileage', 'vin', 'specs', 'status'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_obdb_signals")
                        put("description", "Returns community-sourced OBDb parameters and custom Mode 22 enhanced PID telemetry readings (transmission fluid temp, oil temp/pressure).")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("signal_name", stringParam("Signal requested, e.g. 'transmission_temp', 'oil_pressure', 'all'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "scan_custom_pids")
                        put("description", "Scans the vehicle for enhanced OEM custom PIDs (Mode 22) and community OBDb parameters (cylinder head temp, transmission temp/gear, oil pressure, throttle angles). Invoke when the driver asks to scan for PIDs, check custom/enhanced PIDs, or run a Mode 22 scan.")
                    })
                    put(JSONObject().apply {
                        put("name", "schedule_periodic_telemetry_report")
                        put("description", "Schedules a repeating periodic telemetry voice report at a specified interval (e.g. read fuel trims every 20 seconds for 1 minute). Invoke whenever driver asks to check or read a sensor repeatedly or on an interval.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("parameter", stringParam("The sensor or telemetry parameter to report, e.g. 'fuel_trim', 'rpm', 'speed', 'coolant', 'voltage', 'all'"))
                                put("interval_seconds", intParam("Interval between spoken reports in seconds (minimum 5, default 20)"))
                                put("duration_seconds", intParam("Total duration to run in seconds, or 0 for ongoing until driver says stop"))
                                put("repeat_count", intParam("Optional number of times to report, e.g. 3 times"))
                            })
                            put("required", JSONArray().apply { put("parameter"); put("interval_seconds") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "set_telemetry_threshold_alert")
                        put("description", "Sets a real-time threshold alert on a vehicle telemetry parameter (e.g. alert if RPM > 2000, coolant > 220, fuel trims past +/-3%, or tell me when I start moving). Invoke when driver asks to be notified or alerted when a reading reaches, exceeds, or deviates outside a threshold.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("parameter", stringParam("Telemetry parameter to monitor, e.g. 'rpm', 'speed', 'coolant_temp', 'engine_load', 'fuel_trim', 'battery_voltage'"))
                                put("condition", stringParam("Comparison operator: '>', '<', '>=', '<=', or 'abs_gt' (CRITICAL: When driver asks for a +/- band like 'past 3% or -3%' or 'outside 5%', ALWAYS set condition to 'abs_gt' and threshold_value to the positive number)"))
                                put("threshold_value", numberParam("Numeric threshold value (e.g. 2000 for RPM, 220 for coolant temp, 3.0 for fuel trim, 75 for speed, 1.0 for moving)"))
                                put("custom_message", stringParam("Optional custom alert message or context, e.g. 'Vehicle moving'"))
                                put("one_shot", booleanParam("Set to true if this is a one-time check or notification (e.g. 'let me know when I start moving', 'tell me when coolant reaches 200'). True by default for speed/moving checks."))
                            })
                            put("required", JSONArray().apply { put("parameter"); put("condition"); put("threshold_value") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "cancel_telemetry_alerts")
                        put("description", "Cancels active periodic telemetry reports, updates, or custom threshold alerts. Invoke when driver says 'cancel the update', 'stop reporting', 'cancel alert', or 'stop watching'.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("parameter", stringParam("Optional parameter or target name to cancel (e.g. 'update', 'fuel_trim', 'rpm'), or 'all' to cancel all active watchers"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_road_topography")
                        put("description", "Returns the vehicle's current road location, street name, city, elevation above sea level, and real-time road slope / grade percentage (e.g. +7.2% steep uphill climb, level road, or downhill descent). Invoke when driver asks about their current road, hill incline, elevation, or location.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("detail_level", stringParam("Optional detail level, e.g. 'summary', 'full'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "evaluate_topographical_performance")
                        put("description", "Evaluates vehicle combustion, exhaust, ignition coils, and fuel delivery performance specifically under the current road topography and grade (e.g. stress-testing misfires and fuel trims on an uphill climb, or evaluating cooling and deceleration fuel cut-off on a downhill descent). Invoke whenever the driver asks how the vehicle, engine, or exhaust is performing on a hill, grade, or descent.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("focus_area", stringParam("Optional focus area: 'exhaust', 'ignition_misfires', 'fuel_trims', 'cooling', 'all'"))
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "save_flight_recorder_snapshot")
                        put("description", "Saves a rolling 5-minute flight data black-box snapshot of all vehicle sensor readings around this moment (RPM, speed, temps, fuel trims, transmission gear, topography). Invoke whenever driver asks to save, snapshot, record, or log data for this moment or an incident/spike.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("reason", stringParam("Optional reason or description of the snapshot, e.g. 'Driver heard noise', 'RPM spike', 'Transmission slip'"))
                            })
                        })
                    })
                })
            })
        }

        val effectiveVoice = if (selectedVoice in SUPPORTED_LIVE_VOICES) selectedVoice else "Aoede"
        val setupObj = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", LIVE_MODEL)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", fullInstruction)
                        })
                    })
                })
                put("tools", toolsArray)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", effectiveVoice)
                            })
                        })
                    })
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                if (useSessionFeatures) {
                    // Lets a dropped connection continue the SAME conversation instead of starting over
                    put("sessionResumption", JSONObject().apply {
                        if (!resumeHandle.isNullOrBlank()) put("handle", resumeHandle)
                    })
                    // Removes the ~15 minute audio-session cap
                    put("contextWindowCompression", JSONObject().apply {
                        put("slidingWindow", JSONObject())
                    })
                }
            })
        }

        ws.send(setupObj.toString())
        Log.i(TAG, "Sent Gemini Live Setup frame using model $LIVE_MODEL (Voice: $effectiveVoice, transcription enabled).")
    }

    private fun sendInitialGreetingTrigger(ws: WebSocket) {
        lastModelActivityMs = System.currentTimeMillis()
        val greetingPrompt = if (currentHasPriorHistory) {
            "The driver just reactivated hands-free voice mode during an ongoing conversation. Warmly acknowledge that you're back with them in one short, natural sentence (e.g. 'I'm back with you. Where were we?'), referencing the last topic if appropriate."
        } else {
            "The driver just activated hands-free driving voice mode. Briefly greet them out loud in one short, warm sentence as their mechanic riding shotgun and ask how you can help."
        }
        val triggerObj = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", greetingPrompt)
                            })
                        })
                    })
                })
                put("turnComplete", true)
            })
        }
        val sent = ws.send(triggerObj.toString())
        Log.i(TAG, "Sent initial greeting prompt to Gemini Live (hasHistory=$currentHasPriorHistory, success=$sent).")
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(ws: WebSocket) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recordBufSize = maxOf(minBufSize * 4, 16000)

        // Prefer VOICE_RECOGNITION for speech-optimized gain & noise reduction
        var record: AudioRecord? = null
        try {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
            } else {
                candidate.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "VOICE_RECOGNITION audio source failed, falling back to MIC", e)
        }

        if (record == null) {
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_IN,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord with MIC", e)
                return
            }
        }

        audioRecord = record

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord recording", e)
            return
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            // ~100ms chunks of 16kHz 16-bit mono audio = 16000 * 2 * 0.1 = 3200 bytes
            val pcmChunkSize = 3200
            val readBuffer = ByteArray(1600)
            val accumStream = ByteArrayOutputStream()
            var speechEnergyHoldUntil = 0L

            var consecutiveReadErrors = 0

            while (_isLiveSessionActive.value) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(50)
                    continue
                }

                val readBytes = record.read(readBuffer, 0, readBuffer.size)
                if (readBytes > 0) {
                    consecutiveReadErrors = 0
                    // Never stream audio before the server has acknowledged our setup frame
                    if (!setupCompleted) {
                        accumStream.reset()
                        continue
                    }

                    // Suppress microphone transmission ONLY while Gemini is actively outputting audio via speaker to eliminate echo
                    if (_isGeminiSpeaking.value && isPlayingActive) {
                        // Watchdog: if the playback got stuck, release the mic
                        if (System.currentTimeMillis() - lastModelActivityMs > MODEL_ACTIVITY_WATCHDOG_MS) {
                            Log.w(TAG, "Speaking flag stuck with no model activity for ${MODEL_ACTIVITY_WATCHDOG_MS / 1000}s. Releasing microphone.")
                            _isGeminiSpeaking.value = false
                        } else {
                            accumStream.reset()
                            _isUserSpeaking.value = false
                            continue
                        }
                    }

                    // Calculate local audio energy (RMS) to immediately detect user speaking and hold timer open
                    var sumSquares = 0.0
                    val numSamples = readBytes / 2
                    for (i in 0 until numSamples) {
                        val sample = (readBuffer[i * 2 + 1].toInt() shl 8) or (readBuffer[i * 2].toInt() and 0xFF)
                        sumSquares += (sample * sample).toDouble()
                    }
                    val rms = if (numSamples > 0) kotlin.math.sqrt(sumSquares / numSamples) else 0.0
                    val now = System.currentTimeMillis()

                    if (rms > 400.0) { // Clear speech energy threshold
                        speechEnergyHoldUntil = now + 1200L // Hold user speaking state for 1.2s of follow-on speech
                        if (!_isUserSpeaking.value) {
                            _isUserSpeaking.value = true
                        }
                    } else if (now > speechEnergyHoldUntil) {
                        if (_isUserSpeaking.value) {
                            _isUserSpeaking.value = false
                        }
                    }

                    accumStream.write(readBuffer, 0, readBytes)
                    if (accumStream.size() >= pcmChunkSize) {
                        val chunkBytes = accumStream.toByteArray()
                        accumStream.reset()
                        val base64Pcm = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                        val audioChunkObj = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                // `audio` replaces the deprecated `mediaChunks` field
                                put("audio", JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", base64Pcm)
                                })
                            })
                        }
                        ws.send(audioChunkObj.toString())
                    }
                } else if (readBytes < 0) {
                    consecutiveReadErrors++
                    Log.w(TAG, "AudioRecord read error: $readBytes (consecutive: $consecutiveReadErrors)")
                    if (readBytes == AudioRecord.ERROR_INVALID_OPERATION ||
                        readBytes == AudioRecord.ERROR_DEAD_OBJECT ||
                        consecutiveReadErrors >= 10
                    ) {
                        Log.w(TAG, "AudioRecord hardware died or invalid. Recreating AudioRecord...")
                        delay(200)
                        reinitAudioRecord()
                        consecutiveReadErrors = 0
                    } else {
                        delay(30)
                    }
                }
            }
            _isUserSpeaking.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun reinitAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recordBufSize = maxOf(minBufSize * 4, 16000)
        try {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = candidate
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord successfully re-initialized with VOICE_RECOGNITION.")
                return
            } else {
                candidate.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reinit with VOICE_RECOGNITION, falling back to MIC", e)
        }
        try {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = candidate
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord successfully re-initialized with MIC.")
            } else {
                candidate.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reinit with MIC", e)
        }
    }

    private fun initAudioPlayer() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}

        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufSize = maxOf(minBufSize * 4, 48000) // ~1 second buffer to prevent underruns

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        totalFramesWritten = 0L
        isPlayingActive = false

        audioPlaybackChannel.close()
        audioPlaybackChannel = Channel(Channel.UNLIMITED)

        playbackJob?.cancel()
        playbackJob = scope.launch {
            for (pcmBytes in audioPlaybackChannel) {
                if (!_isLiveSessionActive.value) break
                val track = audioTrack ?: continue

                val written = track.write(pcmBytes, 0, pcmBytes.size)
                lastModelActivityMs = System.currentTimeMillis()
                if (written > 0) {
                    totalFramesWritten += (written / 2) // 16-bit mono = 2 bytes per frame
                }

                // Pre-buffer ~150ms (3600 frames = 7200 bytes) before calling play() to prevent underruns
                if (!isPlayingActive && totalFramesWritten >= 3600) {
                    try {
                        track.play()
                        isPlayingActive = true
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack play failed", e)
                    }
                }
            }
        }
    }

    private fun onTurnComplete(onTurnCompleteCallback: (() -> Unit)?) {
        onTurnCompleteCallback?.invoke()
        val track = audioTrack
        if (track != null && !isPlayingActive && totalFramesWritten > 0) {
            try {
                track.play()
                isPlayingActive = true
            } catch (_: Exception) {}
        }

        turnCompletionJob?.cancel()
        turnCompletionJob = scope.launch {
            if (track != null && isPlayingActive) {
                val startTime = System.currentTimeMillis()
                while (_isLiveSessionActive.value && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val head = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                    if (head >= totalFramesWritten) break
                    if (System.currentTimeMillis() - startTime > 8000L) break
                    delay(40)
                }
                delay(250) // Acoustic settling delay
            }
            try {
                track?.pause()
            } catch (_: Exception) {}
            isPlayingActive = false
            totalFramesWritten = 0L
            _isGeminiSpeaking.value = false
        }
    }

    /**
     * Instantly flushes audio playback queue when interrupted.
     */
    fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) {
            // Discard pending buffered audio chunks
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        isPlayingActive = false
        totalFramesWritten = 0L
        turnCompletionJob?.cancel()
        turnCompletionJob = null
        _isGeminiSpeaking.value = false
        Log.i(TAG, "Audio playback queue flushed.")
    }

    fun interruptPlayback() {
        flushPlaybackQueue()
    }

    private fun handleServerMessage(
        ws: WebSocket,
        jsonText: String,
        onMessageReceived: (String) -> Unit,
        onUserTranscriptReceived: ((String) -> Unit)?,
        onTurnCompleteCallback: (() -> Unit)?,
        onToolCall: ((String, String, JSONObject) -> JSONObject)?
    ) {
        try {
            val root = JSONObject(jsonText)

            // 0. Check for setup confirmation
            if (root.has("setupComplete") || root.has("setup_complete")) {
                val wasReconnect = pendingReconnectSetup
                Log.i(TAG, "Gemini Live setup confirmed by server (reconnect=$wasReconnect, resuming=${resumeHandle != null}).")
                setupCompleted = true
                pendingReconnectSetup = false
                isReconnecting = false
                reconnectAttempts = 0
                lastModelActivityMs = System.currentTimeMillis()
                if (!wasReconnect) {
                    sendInitialGreetingTrigger(ws)
                } else {
                    // Silent reconnect: no "I'm back" greeting, just go back to listening
                    _isGeminiSpeaking.value = false
                    Log.i(TAG, "Silently reconnected to Gemini Live. Resuming listening for driver.")
                }
                return
            }

            // 0b. Session resumption handle updates (lets us continue the same conversation after a drop)
            val resumptionUpdate = root.optJSONObject("sessionResumptionUpdate") ?: root.optJSONObject("session_resumption_update")
            if (resumptionUpdate != null) {
                val newHandle = resumptionUpdate.optString("newHandle", resumptionUpdate.optString("new_handle", ""))
                if (resumptionUpdate.optBoolean("resumable", true) && newHandle.isNotBlank()) {
                    resumeHandle = newHandle
                }
            }

            // 0c. Server is about to close the connection: reconnect proactively between turns
            val goAway = root.optJSONObject("goAway") ?: root.optJSONObject("go_away")
            if (goAway != null) {
                val timeLeftSec = goAway.optString("timeLeft", goAway.optString("time_left", ""))
                    .removeSuffix("s").toDoubleOrNull() ?: 10.0
                Log.w(TAG, "Gemini Live goAway received (timeLeft=${timeLeftSec}s). Scheduling seamless reconnect.")
                scheduleGoAwayReconnect(timeLeftSec)
            }

            // 0d. A pending tool call was cancelled (e.g. driver interrupted): drop it and release the mic
            if (root.has("toolCallCancellation") || root.has("tool_call_cancellation")) {
                Log.i(TAG, "Gemini Live cancelled pending tool call(s).")
                toolSupervisor.cancelChildren()
                _isGeminiSpeaking.value = false
            }

            // 1. Check for serverContent (model speech audio, user & model transcripts, interruptions)
            val serverContent = root.optJSONObject("serverContent") ?: root.optJSONObject("server_content")
            if (serverContent != null) {
                val isInterrupted = serverContent.optBoolean("interrupted", false)
                if (isInterrupted) {
                    Log.i(TAG, "Gemini Live interrupted by user speech. Flushing playback.")
                    flushPlaybackQueue()
                }

                // 1a. User speech transcription (from inputAudioTranscription)
                val inputTxObj = serverContent.optJSONObject("inputAudioTranscription")
                    ?: serverContent.optJSONObject("input_audio_transcription")
                    ?: serverContent.optJSONObject("inputTranscription")
                    ?: serverContent.optJSONObject("input_transcription")
                val userText = inputTxObj?.optString("text", "")
                    ?: (serverContent.optString("inputAudioTranscription", "").takeIf { it.isNotBlank() }
                        ?: serverContent.optString("input_audio_transcription", ""))

                if (userText.isNotBlank()) {
                    Log.i(TAG, "User speech transcript: $userText")
                    onUserTranscriptReceived?.invoke(userText)
                }

                // 1b. Model speech transcription (from outputAudioTranscription)
                val outputTxObj = serverContent.optJSONObject("outputAudioTranscription")
                    ?: serverContent.optJSONObject("output_audio_transcription")
                    ?: serverContent.optJSONObject("outputTranscription")
                    ?: serverContent.optJSONObject("output_transcription")
                val modelText = outputTxObj?.optString("text", "")
                    ?: (serverContent.optString("outputAudioTranscription", "").takeIf { it.isNotBlank() }
                        ?: serverContent.optString("output_audio_transcription", ""))

                if (modelText.isNotBlank()) {
                    Log.i(TAG, "Model speech transcript: $modelText")
                    _lastTranscriptText.value = modelText
                    onMessageReceived(modelText)
                }

                val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                if (modelTurn != null) {
                    turnCompletionJob?.cancel()
                    turnCompletionJob = null
                    _isGeminiSpeaking.value = true
                    lastModelActivityMs = System.currentTimeMillis()

                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue

                            // Text transcript fallback (if outputAudioTranscription was absent)
                            val isThought = part.optBoolean("thought", false)
                            val text = part.optString("text", "")
                            if (!isThought && text.isNotBlank() && outputTxObj == null && modelText.isBlank()) {
                                _lastTranscriptText.value = text
                                onMessageReceived(text)
                            }

                            // Native PCM audio bytes (24kHz 16-bit Mono)
                            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                            if (inlineData != null) {
                                val b64Data = inlineData.optString("data", "")
                                if (b64Data.isNotBlank()) {
                                    val pcmBytes = Base64.decode(b64Data, Base64.DEFAULT)
                                    audioPlaybackChannel.trySend(pcmBytes)
                                }
                            }

                            // Embedded function call within modelTurn part
                            val funcCall = part.optJSONObject("functionCall") ?: part.optJSONObject("function_call")
                            if (funcCall != null && onToolCall != null) {
                                handleSingleFunctionCall(ws, funcCall, onToolCall)
                            }
                        }
                    }
                }

                val turnComplete = serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("turn_complete", false)
                if (turnComplete) {
                    onTurnComplete(onTurnCompleteCallback)
                }
            }

            // 2. Check for Top-Level toolCall message (standard Live API function calling)
            val toolCall = root.optJSONObject("toolCall") ?: root.optJSONObject("tool_call")
            if (toolCall != null && onToolCall != null) {
                turnCompletionJob?.cancel()
                turnCompletionJob = null
                _isGeminiSpeaking.value = true

                val functionCalls = toolCall.optJSONArray("functionCalls") ?: toolCall.optJSONArray("function_calls")
                if (functionCalls != null && functionCalls.length() > 0) {
                  // Run tools OFF the socket reader thread so slow OBD scans can't block pongs and drop the connection
                  scope.launch(toolSupervisor) {
                    val heartbeat = launch {
                        while (isActive) {
                            lastModelActivityMs = System.currentTimeMillis()
                            delay(5000)
                        }
                    }
                    try {
                    val responsesArray = JSONArray()
                    for (i in 0 until functionCalls.length()) {
                        val fc = functionCalls.optJSONObject(i) ?: continue
                        val name = fc.optString("name", "")
                        val callId = fc.optString("id", "")
                        val args = fc.optJSONObject("args") ?: JSONObject()

                        Log.i(TAG, "Gemini Live requested tool execution: $name ($callId)")
                        val toolResult = safeToolCall(onToolCall, name, callId, args)

                        responsesArray.put(JSONObject().apply {
                            put("name", name)
                            put("id", callId)
                            put("response", JSONObject().apply {
                                put("output", toolResult)
                            })
                        })
                    }

                    val responseMsg = JSONObject().apply {
                        put("toolResponse", JSONObject().apply {
                            put("functionResponses", responsesArray)
                        })
                    }
                    if (isActive) {
                        ws.send(responseMsg.toString())
                        Log.i(TAG, "Sent toolResponse for ${responsesArray.length()} tool(s).")
                    }
                    } finally {
                        heartbeat.cancel()
                        lastModelActivityMs = System.currentTimeMillis()
                    }
                  }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling Gemini Live server message", e)
        }
    }

    private fun handleSingleFunctionCall(
        ws: WebSocket,
        funcCall: JSONObject,
        onToolCall: (String, String, JSONObject) -> JSONObject
    ) {
        val name = funcCall.optString("name", "")
        val callId = funcCall.optString("id", "")
        val args = funcCall.optJSONObject("args") ?: JSONObject()

        Log.i(TAG, "Gemini Live requested embedded tool execution: $name ($callId)")
        scope.launch(toolSupervisor) {
        val toolResult = safeToolCall(onToolCall, name, callId, args)

        val responseObj = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", name)
                        put("id", callId)
                        put("response", JSONObject().apply {
                            put("output", toolResult)
                        })
                    })
                })
            })
        }
        if (isActive) ws.send(responseObj.toString())
        }
    }

    /** Runs a tool and converts any exception into an error payload so Gemini is never left waiting forever. */
    private fun safeToolCall(
        onToolCall: (String, String, JSONObject) -> JSONObject,
        name: String,
        callId: String,
        args: JSONObject
    ): JSONObject {
        return try {
            onToolCall(name, callId, args)
        } catch (e: Exception) {
            Log.w(TAG, "Tool '$name' failed", e)
            JSONObject().apply { put("error", "Tool '$name' failed: ${e.message}") }
        }
    }

    /**
     * On a server goAway, wait for a gap in the conversation (or until the deadline) and then reconnect
     * immediately using the resumption handle, so the driver never notices.
     */
    private fun scheduleGoAwayReconnect(timeLeftSec: Double) {
        goAwayJob?.cancel()
        goAwayJob = scope.launch {
            val deadline = System.currentTimeMillis() + ((timeLeftSec - 3.0).coerceAtLeast(0.5) * 1000).toLong()
            while (System.currentTimeMillis() < deadline && _isGeminiSpeaking.value) {
                delay(200)
            }
            if (!isExplicitlyStopped && _isLiveSessionActive.value) {
                attemptReconnect("Server goAway", immediate = true)
            }
        }
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallback != null) return
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Cellular/Wi-Fi network connection became available.")
                    if (!isExplicitlyStopped && isReconnecting && _isLiveSessionActive.value) {
                        scope.launch {
                            delay(600) // Brief cellular transport stabilization
                            if (!isExplicitlyStopped && isReconnecting && _isLiveSessionActive.value) {
                                Log.i(TAG, "Network restored while driving. Fast-tracking live auto-reconnect...")
                                reconnectJob?.cancel()
                                tearDownSocketAndMic()
                                connectWebSocket(isReconnect = true)
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Cellular/Wi-Fi network connection lost while driving.")
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Registered network connectivity callback for hands-free live driving.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = connectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun attemptReconnect(reason: String, immediate: Boolean = false) {
        if (isExplicitlyStopped || !_isLiveSessionActive.value) return

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            failSession("Lost connection to Gemini Live. Please restart the voice session.")
            return
        }
        // A stale resumption handle can make the server reject us repeatedly; fall back to a fresh session.
        if (resumeHandle != null && reconnectAttempts >= 3) {
            Log.w(TAG, "Dropping resumption handle after repeated reconnect failures")
            resumeHandle = null
        }
        isReconnecting = true
        // Fast retries for momentary blips (1.5s, 3s, 4.5s), then steady 8s periodic probing
        // while networkCallback also actively listens for signal restoration to fast-track reconnect.
        val backoffMs = when {
            immediate -> 200L
            reconnectAttempts <= 3 -> reconnectAttempts * 1500L
            else -> 8000L
        }
        Log.w(TAG, "Cellular/network disconnect ($reason). Auto-reconnecting attempt #$reconnectAttempts in ${backoffMs}ms...")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            tearDownSocketAndMic()
            delay(backoffMs)
            if (!isExplicitlyStopped && _isLiveSessionActive.value) {
                connectWebSocket(isReconnect = true)
            }
        }
    }

    private fun tearDownSocketAndMic() {
        // Invalidate callbacks from the socket we are about to close so they can't trigger another reconnect.
        socketGeneration.incrementAndGet()
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            webSocket?.close(1000, "User stopped live session")
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
    }

    /**
     * Silently updates the live WebSocket session's vehicle connection & telemetry context
     * (`turnComplete = false`) whenever the OBD2 adapter connects or live telemetry starts streaming
     * after the voice session was already opened.
     */
    fun injectTelemetryContextUpdate(telemetryText: String): Boolean {
        lastTelemetrySummary = telemetryText
        val ws = webSocket ?: return false
        if (!_isLiveSessionActive.value || !setupCompleted) return false
        return try {
            val contextPrompt = "[SYSTEM LIVE TELEMETRY UPDATE — DO NOT SPEAK UNLESS SPOKEN TO]\nThe vehicle OBD2 connection and live telemetry state just updated:\n$telemetryText\nRemember: You ARE now connected to the vehicle and receiving live data. Use `get_live_data` and `get_vehicle_info` whenever the driver asks about their car."
            val updateObj = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", contextPrompt)
                                })
                            })
                        })
                    })
                    put("turnComplete", false)
                })
            }
            val sent = ws.send(updateObj.toString())
            Log.i(TAG, "Injected silent telemetry context update into Gemini Live WebSocket (sent=$sent)")
            sent
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject telemetry context update", e)
            false
        }
    }

    fun injectDriverAlert(alertText: String, isAdvisory: Boolean = false): Boolean {
        val ws = webSocket ?: return false
        if (!_isLiveSessionActive.value) return false
        return try {
            val alertPrompt = if (isAdvisory) {
                "IN-VEHICLE GAUGE & SENSOR READING: $alertText. Announce this reading naturally and casually to the driver in one brief conversational sentence, the way an AI mechanic chats. Do NOT say 'periodic update' or sound robotic."
            } else {
                "SAFETY ALERT: $alertText. Alert the driver clearly and concisely in one sentence as their master technician riding shotgun."
            }
            val alertObj = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", alertPrompt)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            val sent = ws.send(alertObj.toString())
            Log.i(TAG, "Injected driver alert into Gemini Live WebSocket (sent=$sent, isAdvisory=$isAdvisory): $alertText")
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject driver alert into Gemini Live WebSocket", e)
            false
        }
    }

    fun stopLiveSession() {
        isExplicitlyStopped = true
        isReconnecting = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        goAwayJob?.cancel()
        goAwayJob = null
        toolSupervisor.cancelChildren()

        // Suppress any error reporting on intentional stop
        lastOnError = null

        unregisterNetworkCallback()

        _isLiveSessionActive.value = false
        _isGeminiSpeaking.value = false

        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = null

        turnCompletionJob?.cancel()
        turnCompletionJob = null

        tearDownSocketAndMic()

        playbackJob?.cancel()
        playbackJob = null
        audioPlaybackChannel.close()

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isPlayingActive = false
        totalFramesWritten = 0L

        Log.i(TAG, "Stopped Gemini Live Session.")
    }
}
