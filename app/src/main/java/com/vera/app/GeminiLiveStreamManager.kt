package com.vera.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val SUPPORTED_LIVE_VOICES = setOf("Aoede", "Charon", "Fenrir", "Kore", "Puck")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite streaming connection
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // Send WebSocket PING every 15s to keep cellular NAT & towers alive
        .retryOnConnectionFailure(true)
        .build()

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

    private val _lastTranscriptText = MutableStateFlow("")
    val lastTranscriptText: StateFlow<String> = _lastTranscriptText.asStateFlow()

    private val _voiceName = MutableStateFlow("Aoede")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    fun setVoice(name: String) {
        val trimmed = name.trim()
        val normalized = when (trimmed.lowercase()) {
            "vega" -> "Aoede" // Maps consumer Gemini "Vega" (bright female voice) to Gemini Live API female voice Aoede
            "aoede" -> "Aoede"
            "charon" -> "Charon"
            "fenrir" -> "Fenrir"
            "kore" -> "Kore"
            "puck" -> "Puck"
            else -> {
                Log.w(TAG, "Voice '$name' not recognized. Defaulting to 'Aoede'.")
                "Aoede"
            }
        }
        _voiceName.value = normalized
        Log.i(TAG, "Selected Gemini Live Voice: $normalized (requested: '$name')")
    }

    private var currentHasPriorHistory = false
    private var lastApiKey: String = ""
    private var lastSystemInstruction: String = ""
    private var lastTelemetrySummary: String = ""
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

    @SuppressLint("MissingPermission")
    fun startLiveSession(
        apiKey: String,
        systemInstruction: String,
        telemetrySummary: String,
        conversationHistory: String = "",
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

        lastApiKey = apiKey
        lastSystemInstruction = systemInstruction
        lastTelemetrySummary = telemetrySummary
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
        _isGeminiSpeaking.value = true // Initial state: suppress mic while connecting and greeting

        initAudioPlayer()
        connectWebSocket(isReconnect = false)
    }

    private fun connectWebSocket(isReconnect: Boolean) {
        if (isExplicitlyStopped || !_isLiveSessionActive.value) return

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$lastApiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket opened successfully (isReconnect=$isReconnect).")
                reconnectAttempts = 0

                // 1. Send Setup Frame FIRST with Voice, Tools, & Vehicle System Instructions
                sendSetupFrame(ws, lastSystemInstruction, lastTelemetrySummary, lastConversationHistory, _voiceName.value)

                // 2. Start Microphone PCM Audio Streaming AFTER setup frame is sent
                startMicrophoneStreaming(ws)

                // If reconnecting silently while driving, release mic immediately without waiting for speech greeting
                if (isReconnect) {
                    _isGeminiSpeaking.value = false
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(ws, text, lastOnMessageReceived ?: {}, lastOnUserTranscriptReceived, lastOnTurnComplete, lastOnToolCall)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(ws, bytes.utf8(), lastOnMessageReceived ?: {}, lastOnUserTranscriptReceived, lastOnTurnComplete, lastOnToolCall)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val err = "Gemini Live WebSocket Error: ${t.message}"
                Log.e(TAG, err, t)
                if (!isExplicitlyStopped && _isLiveSessionActive.value) {
                    attemptReconnect("Connection failure: ${t.message}")
                } else {
                    lastOnError?.invoke(t.message ?: "Connection failure")
                    stopLiveSession()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closing: $reason ($code)")
                if (code != 1000 && !isExplicitlyStopped && _isLiveSessionActive.value) {
                    attemptReconnect("Server closing ($code: $reason)")
                } else {
                    stopLiveSession()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed ($code).")
                if (code != 1000 && !isExplicitlyStopped && _isLiveSessionActive.value) {
                    attemptReconnect("Socket closed ($code)")
                } else {
                    _isLiveSessionActive.value = false
                }
            }
        })
    }

    private fun sendSetupFrame(
        ws: WebSocket,
        systemInstruction: String,
        telemetrySummary: String,
        conversationHistory: String,
        selectedVoice: String
    ) {
        val historySection = if (conversationHistory.isNotBlank()) {
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

        val toolsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("functionDeclarations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "get_live_data")
                        put("description", "Returns current live vehicle engine telemetry snapshot from ECU (RPM, speed in mph/kmh, coolant temp in F/C, battery voltage, throttle, fuel trims, MAP/MAF, etc.). Invoke whenever driver asks about speed, engine status, temperatures, battery, or sensors.")
                        put("parameters", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("sensor_focus", stringParam("Specific sensor requested, e.g. 'coolant', 'rpm', 'speed', 'battery', 'all'"))
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
            })
        }

        ws.send(setupObj.toString())
        Log.i(TAG, "Sent Gemini Live Setup frame using model $LIVE_MODEL (Voice: $effectiveVoice, transcription enabled).")
    }

    private fun sendInitialGreetingTrigger(ws: WebSocket) {
        _isGeminiSpeaking.value = true
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

            while (_isLiveSessionActive.value) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(50)
                    continue
                }

                val readBytes = record.read(readBuffer, 0, readBuffer.size)
                if (readBytes > 0) {
                    // Suppress microphone transmission while Gemini is speaking to eliminate acoustic feedback and self-interruption
                    if (_isGeminiSpeaking.value) {
                        accumStream.reset()
                        continue
                    }

                    accumStream.write(readBuffer, 0, readBytes)
                    if (accumStream.size() >= pcmChunkSize) {
                        val chunkBytes = accumStream.toByteArray()
                        accumStream.reset()
                        val base64Pcm = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                        val audioChunkObj = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("mediaChunks", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", base64Pcm)
                                    })
                                })
                            })
                        }
                        ws.send(audioChunkObj.toString())
                    }
                } else if (readBytes < 0) {
                    delay(30)
                }
            }
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
                Log.i(TAG, "Gemini Live setup confirmed by server (isReconnecting=$isReconnecting).")
                if (!isReconnecting) {
                    sendInitialGreetingTrigger(ws)
                } else {
                    _isGeminiSpeaking.value = false
                    isReconnecting = false
                    Log.i(TAG, "Silently reconnected to Gemini Live while driving. Resuming listening for driver.")
                }
                return
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
                    val responsesArray = JSONArray()
                    for (i in 0 until functionCalls.length()) {
                        val fc = functionCalls.optJSONObject(i) ?: continue
                        val name = fc.optString("name", "")
                        val callId = fc.optString("id", "")
                        val args = fc.optJSONObject("args") ?: JSONObject()

                        Log.i(TAG, "Gemini Live requested tool execution: $name ($callId)")
                        val toolResult = onToolCall(name, callId, args)

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
                    ws.send(responseMsg.toString())
                    Log.i(TAG, "Sent toolResponse for ${responsesArray.length()} tool(s).")
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
        val toolResult = onToolCall(name, callId, args)

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
        ws.send(responseObj.toString())
    }

    private fun attemptReconnect(reason: String) {
        if (isExplicitlyStopped || !_isLiveSessionActive.value) return

        if (reconnectAttempts >= 5) {
            Log.e(TAG, "Max reconnect attempts (5) reached while driving. Stopping live session.")
            lastOnError?.invoke("Network connection lost while driving: $reason")
            stopLiveSession()
            return
        }

        reconnectAttempts++
        isReconnecting = true
        val backoffMs = (reconnectAttempts * 1500L).coerceAtMost(6000L)
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
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
    }

    fun stopLiveSession() {
        isExplicitlyStopped = true
        isReconnecting = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null

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

        webSocket?.close(1000, "User stopped live session")
        webSocket = null
        Log.i(TAG, "Stopped Gemini Live Session.")
    }
}
