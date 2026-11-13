package com.vera.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(
    val sender: String,
    val text: String,
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class GeminiChatModel(
    val id: String,
    val displayName: String,
    val shortName: String,
    val subtitle: String,
    val fallbackIds: List<String>
) {
    FLASH(
        id = "gemini-3.8-flash",
        displayName = "Gemini 2.5 Flash",
        shortName = "2.5 Flash",
        subtitle = "Fast, responsive & balanced (Default)",
        fallbackIds = listOf("gemini-3.5-flash", "gemini-flash-latest", "gemini-3.1-pro-preview")
    ),
    PRO(
        id = "gemini-3.1-pro-preview",
        displayName = "Gemini 2.5 Pro",
        shortName = "2.5 Pro",
        subtitle = "Deep mechanical reasoning & Mode 06 analysis",
        fallbackIds = listOf("gemini-pro-latest", "gemini-3.8-flash", "gemini-3.5-flash")
    ),
    THINKING(
        id = "gemini-3.1-pro-preview",
        displayName = "Gemini 2.0 Thinking",
        shortName = "Thinking",
        subtitle = "Step-by-step diagnostic reasoning tree",
        fallbackIds = listOf("gemini-3.8-flash", "gemini-3.5-flash")
    )
}

data class ChatAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val isImage: Boolean,
    val uri: Uri? = null,
    val base64Image: String? = null,
    val textContent: String? = null
)

data class RecordedDataSample(
    val timestampMs: Long,
    val elapsedSeconds: Double,
    val metricsSnapshot: BluetoothObdManager.VehicleMetrics,
    val streamSnapshots: List<LiveStreamData>,
    val mode22Snapshots: List<Mode22StreamData>,
    val roadContextSnapshot: RoadContext? = null
)

data class RecordedCsvResult(
    val csvContent: String,
    val defaultFilename: String,
    val sampleCount: Int,
    val durationSeconds: Double
)

data class FlightSnapshotResult(
    val success: Boolean,
    val sampleCount: Int,
    val durationSeconds: Double,
    val filename: String
)

class GeminiAgentViewModel : ViewModel() {

    val obdManager = BluetoothObdManager()

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return obdManager.getBondedDevices()
    }

    fun getAvailableDevices(context: android.content.Context): List<BluetoothObdManager.ObdDeviceItem> {
        return obdManager.getAvailableDevices(context)
    }

    private val geminiApiKey = BuildConfig.GEMINI_API_KEY

    val isKillSwitchDeactivated: StateFlow<Boolean> = VeraKillSwitch.isDeactivated
    val killSwitchMessage: StateFlow<String?> = VeraKillSwitch.deactivationMessage

    fun checkKillSwitch(context: Context) {
        viewModelScope.launch {
            VeraKillSwitch.checkRemoteKillSwitch(context)
        }
    }

    val messages = mutableStateListOf<ChatMessage>()

    val sentinelManager: VeraSentinelManager by lazy {
        VeraSentinelManager(viewModelScope) { alert ->
            onSentinelAlertTriggered(alert)
        }
    }
    val sentinelAlerts: StateFlow<List<SentinelAlert>>
        get() = sentinelManager.alerts

    private fun onSentinelAlertTriggered(alert: SentinelAlert) {
        val chatContent = if (alert.severity == SentinelSeverity.ADVISORY) {
            // For periodic telemetry advisory reports, chat naturally like an AI assistant in conversation
            alert.spokenText
        } else {
            val badge = when (alert.severity) {
                SentinelSeverity.CRITICAL -> "[CRITICAL ALERT]"
                SentinelSeverity.WARNING -> "[WARNING]"
                else -> "[INFO]"
            }
            "$badge ${alert.title}\n${alert.message}"
        }
        viewModelScope.launch(Dispatchers.Main) {
            messages.add(ChatMessage("VERA", chatContent))
        }

        // Automatic Black-Box Flight Snapshot: On mechanical/diagnostic alerts (misfire, overheating, lean spike),
        // capture the 5-minute flight recorder window so the driver has all sensor data leading up to the failure!
        if (alert.severity == SentinelSeverity.CRITICAL || alert.severity == SentinelSeverity.WARNING) {
            saveFlightRecorderSnapshot("Sentinel anomaly: ${alert.title}")
        } else {
            autoSaveCurrentSession()
        }

        // STRICT VOICE RULE: Only vocalize if live voice mode is active
        if (isVoiceModeActive) {
            val isAdvisory = alert.severity == SentinelSeverity.ADVISORY || alert.severity == SentinelSeverity.INFO
            // For periodic scheduled reports and advisories, ensure they never get lost or delayed:
            // If live WebSocket is connected AND idle, inject into Gemini Live.
            // If Gemini Live is currently speaking, busy in a turn, or reconnecting, fallback to immediate local TTS
            // so the driver gets their scheduled report on-time, every time!
            val liveSpeaking = liveStreamManager?.isGeminiSpeaking?.value == true
            val liveInjected = if (_isHandsFreeVoiceActive.value && _isLiveSessionActive.value && !liveSpeaking) {
                liveStreamManager?.injectDriverAlert(alert.spokenText, isAdvisory) ?: false
            } else false

            if (!liveInjected) {
                speak(alert.spokenText) {
                    // Alert finished speaking: If hands-free voice mode is active, open 15-second follow-up window!
                    if (_isHandsFreeVoiceActive.value) {
                        viewModelScope.launch(Dispatchers.Main) {
                            if (!_isLiveSessionActive.value) {
                                startGeminiLiveSession()
                            }
                            startFollowUpWindow(FOLLOW_UP_WINDOW_SECONDS)
                        }
                    }
                }
            }
        }
    }

    fun buildTelemetrySnapshotMap(): Map<String, String> {
        val metrics = obdManager.metrics.value
        val streams = obdManager.supportedStreams.value
        val isMetric = unitSystem.value == UnitSystem.METRIC
        val volt = streams.find { it.pid.pidHex == "42" }?.formattedValue?.takeIf { it != "--" }
        val trans = obdManager.mode22Streams.value.find { it.pid.name.contains("Transmission", ignoreCase = true) }?.formattedValue
        val spd = if (isMetric) "${metrics.speed} km/h" else "${(metrics.speed * 0.621371).toInt()} mph"
        val temp = if (isMetric) "${metrics.coolantTemp}°C" else "${(metrics.coolantTemp * 1.8 + 32).toInt()}°F"
        val trim = if (metrics.fuelTrim >= 0) "+${metrics.fuelTrim}%" else "${metrics.fuelTrim}%"
        // Only report values the car really sent. Missing ones are omitted (never invented).
        val customPidItems = buildUnifiedCustomPidList(
            mode22Streams = obdManager.mode22Streams.value,
            obdbLoadedResult = obdManager.obdbLoadedResult.value,
            obdbSignalResults = obdManager.obdbSignalResults.value,
            unitSystem = unitSystem.value,
            vehicleDetails = obdManager.vehicleDetails.value,
            isMoving = metrics.speed > 2
        )
        return buildMap {
            put("rpm", "${metrics.rpm} RPM")
            put("speed", spd)
            put("coolant", temp)
            put("fuel_trim", trim)
            if (volt != null) put("voltage", volt)
            if (trans != null) put("trans_temp", trans)
            for ((k, sig) in obdManager.opendbcSignals.value) {
                put("can_${k.lowercase().replace(" ", "_")}", sig.formattedValue)
            }
            for (item in customPidItems.filter { !it.isBooleanStatus }.take(20)) {
                put(item.name, "${item.formattedValue} ${item.unit}".trim())
            }
        }
    }

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> get() = _connectionStatus

    val supportedStreams = obdManager.supportedStreams
    val vehicleVin = obdManager.vehicleVin
    val vehicleDetails = obdManager.vehicleDetails
    val mode06Results = obdManager.mode06Results
    val mode22Streams = obdManager.mode22Streams
    val elmCommandLog = obdManager.elmCommandLog
    val obdbLoadedResult = obdManager.obdbLoadedResult
    val obdbSignalResults = obdManager.obdbSignalResults

    val isHighSpeedCanCapable: StateFlow<Boolean> = obdManager.isHighSpeedCanCapable
    val adapterHardwareName: StateFlow<String> = obdManager.adapterHardwareName
    val activeOpendbcProfile: StateFlow<OpendbcManager.VehicleDbcProfile?> = obdManager.activeOpendbcProfile
    val opendbcSignals: StateFlow<Map<String, OpendbcManager.DecodedOpendbcSignal>> = obdManager.opendbcSignals

    val canSniffReport: StateFlow<BluetoothObdManager.CanSniffReport?> = obdManager.canSniffReport
    val isSniffingCan: StateFlow<Boolean> = obdManager.isSniffingCan

    /** Passive, receive-only scan of which CAN IDs this vehicle broadcasts on the OBD-II port. */
    fun scanCanBus() {
        viewModelScope.launch(Dispatchers.IO) {
            obdManager.sniffCanBus()
        }
    }

    private val _isScanningObdb = MutableStateFlow(false)
    val isScanningObdb: StateFlow<Boolean> = _isScanningObdb.asStateFlow()

    fun initObdbRepository(context: android.content.Context) {
        obdManager.initObdbRepository(context)
    }

    fun scanObdbSignals() {
        viewModelScope.launch {
            if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected) {
                return@launch
            }
            _isScanningObdb.value = true
            try {
                obdManager.readObdbSignals()
            } catch (e: Exception) {
                Log.e("GeminiAgentViewModel", "Error scanning OBDb signals", e)
            } finally {
                _isScanningObdb.value = false
            }
        }
    }

    fun getRawElmTraceText(): String {
        val log = obdManager.elmCommandLog.value
        if (log.isEmpty()) return "No ELM327 command exchanges recorded yet."
        return log.takeLast(40).joinToString("\n")
    }

    private val _troubleCodes = MutableStateFlow<List<String>>(emptyList())
    val troubleCodes: StateFlow<List<String>> = _troubleCodes.asStateFlow()

    private val _dtcDetails = MutableStateFlow<List<DtcDetails>>(emptyList())
    val dtcDetails: StateFlow<List<DtcDetails>> = _dtcDetails.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    var locationManager: VeraLocationManager? = null
        private set

    private val _roadContext = MutableStateFlow(RoadContext())
    val roadContext: StateFlow<RoadContext> = _roadContext.asStateFlow()

    // --- VIRTUAL CO-PILOT INSTANT REPLAY / FLIGHT RECORDER ENGINE ---
    private val _isReplaying = MutableStateFlow(false)
    val isReplaying: StateFlow<Boolean> = _isReplaying.asStateFlow()

    private val _isReplayPaused = MutableStateFlow(false)
    val isReplayPaused: StateFlow<Boolean> = _isReplayPaused.asStateFlow()

    private val _replayProgress = MutableStateFlow(0f)
    val replayProgress: StateFlow<Float> = _replayProgress.asStateFlow()

    private val _replayCurrentMs = MutableStateFlow(0L)
    val replayCurrentMs: StateFlow<Long> = _replayCurrentMs.asStateFlow()

    private val _replayTotalDurationMs = MutableStateFlow(0L)
    val replayTotalDurationMs: StateFlow<Long> = _replayTotalDurationMs.asStateFlow()

    private val _replaySessionTitle = MutableStateFlow("")
    val replaySessionTitle: StateFlow<String> = _replaySessionTitle.asStateFlow()

    private val _replaySample = MutableStateFlow<RecordedDataSample?>(null)
    val replaySample: StateFlow<RecordedDataSample?> = _replaySample.asStateFlow()

    private var replayJob: kotlinx.coroutines.Job? = null
    private var activeReplaySamples: List<RecordedDataSample> = emptyList()

    val effectiveMetrics: StateFlow<BluetoothObdManager.VehicleMetrics> = combine(
        obdManager.metrics,
        _replaySample,
        _isReplaying
    ) { live, replay, replaying ->
        if (replaying && replay != null) replay.metricsSnapshot else live
    }.stateIn(viewModelScope, SharingStarted.Eagerly, obdManager.metrics.value)

    val effectiveSupportedStreams: StateFlow<List<LiveStreamData>> = combine(
        supportedStreams,
        _replaySample,
        _isReplaying
    ) { live, replay, replaying ->
        if (replaying && replay != null) replay.streamSnapshots else live
    }.stateIn(viewModelScope, SharingStarted.Eagerly, supportedStreams.value)

    val effectiveMode22Streams: StateFlow<List<Mode22StreamData>> = combine(
        mode22Streams,
        _replaySample,
        _isReplaying
    ) { live, replay, replaying ->
        if (replaying && replay != null) replay.mode22Snapshots else live
    }.stateIn(viewModelScope, SharingStarted.Eagerly, mode22Streams.value)

    val effectiveRoadContext: StateFlow<RoadContext> = combine(
        _roadContext,
        _replaySample,
        _isReplaying
    ) { live, replay, replaying ->
        if (replaying && replay?.roadContextSnapshot != null) replay.roadContextSnapshot else live
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _roadContext.value)

    fun initLocationManager(context: Context) {
        if (locationManager != null) return
        val mgr = VeraLocationManager(
            context = context.applicationContext,
            scope = viewModelScope,
            isObdConnected = { obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode },
            getVehicleSpeedKmh = { obdManager.metrics.value.speed }
        )
        locationManager = mgr
        viewModelScope.launch {
            mgr.roadContext.collect { rc ->
                _roadContext.value = rc
            }
        }
        if (obdManager.isDemoMode) {
            mgr.setDemoMode(true, dirtyDirty = obdManager.vehicleVin.value?.contains("DIRTY") == true)
        } else {
            mgr.startTracking()
        }
        Log.i("GeminiAgentViewModel", "VeraLocationManager initialized with vehicle speed sensor link and tracking started.")
    }

    private val _isScanningDtcs = MutableStateFlow(false)
    val isScanningDtcs: StateFlow<Boolean> = _isScanningDtcs.asStateFlow()

    private val _hasScannedDtcs = MutableStateFlow(false)
    val hasScannedDtcs: StateFlow<Boolean> = _hasScannedDtcs.asStateFlow()

    private val _isScanningMode06 = MutableStateFlow(false)
    val isScanningMode06: StateFlow<Boolean> = _isScanningMode06.asStateFlow()

    private val _isScanningMode22 = MutableStateFlow(false)
    val isScanningMode22: StateFlow<Boolean> = _isScanningMode22.asStateFlow()

    private val _isTestingActuator = MutableStateFlow(false)
    val isTestingActuator: StateFlow<Boolean> = _isTestingActuator.asStateFlow()

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    private val _selectedChatModel = MutableStateFlow(GeminiChatModel.FLASH)
    val selectedChatModel: StateFlow<GeminiChatModel> = _selectedChatModel.asStateFlow()

    fun setChatModel(model: GeminiChatModel) {
        _selectedChatModel.value = model
        Log.i("GeminiApi", "Switched diagnostic chat model to: ${model.displayName} (${model.id})")
        messages.add(
            ChatMessage(
                "System",
                "Diagnostic Model Changed: ${model.displayName}\n${model.subtitle}"
            )
        )
    }

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun initTts(context: Context) {
        appContext = context.applicationContext
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.02f)
                try {
                    val voices = tts?.voices ?: emptySet()
                    val usVoices = voices.filter { 
                        (it.locale.country == "US" || it.name.lowercase().contains("en-us")) &&
                        !it.name.lowercase().contains("embedded")
                    }

                    val preferredVoiceNames = listOf(
                        "en-us-x-iom-network", "en-us-x-sfg-network", "en-us-x-tpc-network", "en-us-x-tpf-network",
                        "en-us-x-iom-local", "en-us-x-sfg-local", "en-us-x-tpc-local", "en-us-x-tpf-local",
                        "en-us-x-iom", "en-us-x-sfg", "en-us-x-tpc", "en-us-x-tpf", "en-us-x-wb", "en-us-x-network"
                    )

                    var selectedVoice: android.speech.tts.Voice? = null
                    for (prefName in preferredVoiceNames) {
                        selectedVoice = usVoices.firstOrNull { it.name.lowercase().contains(prefName) }
                        if (selectedVoice != null) break
                    }

                    if (selectedVoice == null) {
                        selectedVoice = usVoices.firstOrNull { it.quality >= android.speech.tts.Voice.QUALITY_HIGH }
                            ?: usVoices.firstOrNull()
                    }

                    if (selectedVoice != null) {
                        tts?.voice = selectedVoice
                        Log.i("VERA_TTS", "Selected Google US High-Definition Voice: ${selectedVoice.name}")
                    }
                } catch (e: Exception) {
                    Log.w("VERA_TTS", "Error selecting voice", e)
                }
            }
        }
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
        if (!_isTtsEnabled.value) {
            stopSpeaking()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        // STRICT VOICE RULE: Never vocalize unless live voice mode is active
        if (!isVoiceModeActive) {
            onDone?.invoke()
            return
        }
        if (!_isTtsEnabled.value) {
            onDone?.invoke()
            return
        }
        stopSpeaking()

        val cleanText = text
            .replace(Regex("[#*`•_~]"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        val activeVoice = _liveVoiceName.value

        viewModelScope.launch(Dispatchers.IO) {
            var audioPlaySuccess = false
            val cacheDir = appContext?.cacheDir

            if (!VeraKillSwitch.isDeactivated.value && geminiApiKey.isNotBlank() && cacheDir != null) {
                // Use Gemini Native Voice models to output smooth human voice matching selected voice
                val ttsModels = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
                for (modelName in ttsModels) {
                    try {
                        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$geminiApiKey")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 8000
                            readTimeout = 15000
                            doOutput = true
                        }

                        val requestJson = JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", cleanText))
                                    })
                                })
                            })
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", JSONArray().apply {
                                    put("AUDIO")
                                })
                                put("speechConfig", JSONObject().apply {
                                    put("voiceConfig", JSONObject().apply {
                                        put("prebuiltVoiceConfig", JSONObject().apply {
                                            put("voiceName", activeVoice)
                                        })
                                    })
                                })
                            })
                        }

                        connection.outputStream.use { os ->
                            os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                        }

                        if (connection.responseCode in 200..299) {
                            val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonResp = JSONObject(responseStr)
                            val candidates = jsonResp.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val inlineData = parts.getJSONObject(0).optJSONObject("inlineData")
                                    val audioB64 = inlineData?.optString("data", "") ?: ""
                                    val mimeType = inlineData?.optString("mimeType", "") ?: ""

                                    if (audioB64.isNotBlank()) {
                                        val rawAudioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                                        val finalWavBytes = if (mimeType.contains("wav", ignoreCase = true) ||
                                            (rawAudioBytes.size > 4 && rawAudioBytes[0] == 'R'.code.toByte() && rawAudioBytes[1] == 'I'.code.toByte())) {
                                            rawAudioBytes
                                        } else {
                                            addWavHeader(rawAudioBytes, sampleRate = 24000, channels = 1)
                                        }

                                        val tempFile = java.io.File.createTempFile("vera_human_voice_", ".wav", cacheDir)
                                        tempFile.writeBytes(finalWavBytes)

                                        withContext(Dispatchers.Main) {
                                            try {
                                                stopSpeaking()
                                                val player = MediaPlayer()
                                                mediaPlayer = player
                                                player.setDataSource(tempFile.absolutePath)
                                                player.prepare()
                                                _isSpeaking.value = true
                                                player.setOnCompletionListener {
                                                    _isSpeaking.value = false
                                                    tempFile.delete()
                                                    onDone?.invoke()
                                                }
                                                player.setOnErrorListener { _, _, _ ->
                                                    _isSpeaking.value = false
                                                    tempFile.delete()
                                                    onDone?.invoke()
                                                    false
                                                }
                                                player.start()
                                            } catch (e: Exception) {
                                                _isSpeaking.value = false
                                                tempFile.delete()
                                                onDone?.invoke()
                                            }
                                        }
                                        audioPlaySuccess = true
                                        Log.i("VERA_Voice", "Played Gemini Smooth Human Voice ($activeVoice via $modelName)")
                                        break
                                    }
                                }
                            }
                        } else {
                            val errStr = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            Log.w("VERA_Voice", "TTS model $modelName returned error: $errStr")
                        }
                    } catch (e: Exception) {
                        Log.w("VERA_Voice", "TTS model $modelName failed: ${e.message}")
                    }
                }
            }

            if (!audioPlaySuccess) {
                withContext(Dispatchers.Main) {
                    speakWithAndroidTts(cleanText, onDone)
                }
            }
        }
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int = 24000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalDataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16) // Subchunk1Size (16 for PCM)
        bb.putShort(1) // AudioFormat (1 for PCM)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * 2).toShort()) // BlockAlign
        bb.putShort(16) // BitsPerSample
        bb.put("data".toByteArray())
        bb.putInt(pcmData.size)
        return header + pcmData
    }

    private fun speakWithAndroidTts(cleanText: String, onDone: (() -> Unit)? = null) {
        if (tts == null) {
            onDone?.invoke()
            return
        }
        _isSpeaking.value = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                onDone?.invoke()
            }
            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                onDone?.invoke()
            }
        })
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "VeraUtterance_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        tts?.stop()
        _isSpeaking.value = false
    }

    fun previewVoice(voiceName: String, samplePhrase: String = "Hello, I'm Vera.") {
        stopSpeaking()
        val trimmed = voiceName.trim()
        val found = GeminiLiveStreamManager.SUPPORTED_LIVE_VOICES.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: "Aoede"
        setLiveVoiceName(found)
        viewModelScope.launch(Dispatchers.IO) {
            var audioPlaySuccess = false
            val cacheDir = appContext?.cacheDir
            val voiceKey = found.lowercase()
            val cachedPreviewFile = if (cacheDir != null) java.io.File(cacheDir, "vera_voice_sample_${voiceKey}.wav") else null

            if (cachedPreviewFile != null && cachedPreviewFile.exists() && cachedPreviewFile.length() > 1000) {
                withContext(Dispatchers.Main) {
                    try {
                        stopSpeaking()
                        val player = MediaPlayer()
                        mediaPlayer = player
                        player.setDataSource(cachedPreviewFile.absolutePath)
                        player.prepare()
                        _isSpeaking.value = true
                        player.setOnCompletionListener {
                            _isSpeaking.value = false
                        }
                        player.setOnErrorListener { _, _, _ ->
                            _isSpeaking.value = false
                            false
                        }
                        player.start()
                        audioPlaySuccess = true
                        Log.i("VERA_Voice", "Played cached voice preview for '$found'")
                    } catch (e: Exception) {
                        _isSpeaking.value = false
                    }
                }
                if (audioPlaySuccess) return@launch
            }

            if (geminiApiKey.isNotBlank() && cacheDir != null) {
                val ttsModels = listOf(
                    "gemini-3.8-flash-tts",
                    "gemini-3.8-flash-lite-tts",
                    "gemini-3.1-flash-tts-preview",
                    "gemini-2.5-flash-preview-tts"
                )
                for (modelName in ttsModels) {
                    try {
                        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$geminiApiKey")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 6000
                            readTimeout = 10000
                            doOutput = true
                        }

                        val requestJson = JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", samplePhrase))
                                    })
                                })
                            })
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", JSONArray().apply {
                                    put("AUDIO")
                                })
                                put("speechConfig", JSONObject().apply {
                                    put("voiceConfig", JSONObject().apply {
                                        put("prebuiltVoiceConfig", JSONObject().apply {
                                            put("voiceName", voiceName)
                                        })
                                    })
                                })
                            })
                        }

                        connection.outputStream.use { os ->
                            os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                        }

                        if (connection.responseCode in 200..299) {
                            val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonResp = JSONObject(responseStr)
                            val candidates = jsonResp.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val inlineData = parts.getJSONObject(0).optJSONObject("inlineData")
                                    val audioB64 = inlineData?.optString("data", "") ?: ""
                                    val mimeType = inlineData?.optString("mimeType", "") ?: ""

                                    if (audioB64.isNotBlank()) {
                                        val rawAudioBytes = Base64.decode(audioB64, Base64.DEFAULT)
                                        val finalWavBytes = if (mimeType.contains("wav", ignoreCase = true) ||
                                            (rawAudioBytes.size > 4 && rawAudioBytes[0] == 'R'.code.toByte() && rawAudioBytes[1] == 'I'.code.toByte())) {
                                            rawAudioBytes
                                        } else {
                                            addWavHeader(rawAudioBytes, sampleRate = 24000, channels = 1)
                                        }

                                        if (cachedPreviewFile != null) {
                                            try {
                                                cachedPreviewFile.writeBytes(finalWavBytes)
                                            } catch (_: Exception) {}
                                        }

                                        withContext(Dispatchers.Main) {
                                            try {
                                                stopSpeaking()
                                                val player = MediaPlayer()
                                                mediaPlayer = player
                                                if (cachedPreviewFile != null && cachedPreviewFile.exists()) {
                                                    player.setDataSource(cachedPreviewFile.absolutePath)
                                                } else {
                                                    val tempFile = java.io.File.createTempFile("vera_preview_", ".wav", cacheDir)
                                                    tempFile.writeBytes(finalWavBytes)
                                                    player.setDataSource(tempFile.absolutePath)
                                                }
                                                player.prepare()
                                                _isSpeaking.value = true
                                                player.setOnCompletionListener {
                                                    _isSpeaking.value = false
                                                }
                                                player.setOnErrorListener { _, _, _ ->
                                                    _isSpeaking.value = false
                                                    false
                                                }
                                                player.start()
                                            } catch (e: Exception) {
                                                _isSpeaking.value = false
                                            }
                                        }
                                        audioPlaySuccess = true
                                        Log.i("VERA_Voice", "Played voice preview for '$voiceName' via $modelName")
                                        break
                                    }
                                }
                            }
                        } else {
                            val errStr = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            Log.w("VERA_Voice", "Voice preview model $modelName returned error: $errStr")
                        }
                    } catch (e: Exception) {
                        Log.w("VERA_Voice", "Voice preview failed on $modelName: ${e.message}")
                    }
                }
            }

            if (!audioPlaySuccess) {
                withContext(Dispatchers.Main) {
                    val isMale = listOf("charon", "fenrir", "puck", "orus", "enceladus", "iapetus", "algieba", "algenib", "rasalgethi", "alnilam", "schedar", "achird")
                        .any { it.equals(voiceKey, ignoreCase = true) }
                    try {
                        tts?.setPitch(if (isMale) 0.85f else 1.15f)
                    } catch (_: Exception) {}
                    speakWithAndroidTts(samplePhrase)
                }
            }
        }
    }

    private var liveStreamManager: GeminiLiveStreamManager? = null
    private var wakeWordManager: VeraWakeWordManager? = null

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _isGeminiSpeaking = MutableStateFlow(false)
    val isGeminiSpeaking: StateFlow<Boolean> = _isGeminiSpeaking.asStateFlow()

    private val _isHandsFreeVoiceActive = MutableStateFlow(false)
    val isHandsFreeVoiceActive: StateFlow<Boolean> = _isHandsFreeVoiceActive.asStateFlow()

    private val _isWakeWordListening = MutableStateFlow(false)
    val isWakeWordListening: StateFlow<Boolean> = _isWakeWordListening.asStateFlow()

    private val _followUpSecondsRemaining = MutableStateFlow(0)
    val followUpSecondsRemaining: StateFlow<Int> = _followUpSecondsRemaining.asStateFlow()
    private var followUpTimerJob: Job? = null

    val isVoiceModeActive: Boolean
        get() = _isHandsFreeVoiceActive.value || _isLiveSessionActive.value

    private var sessionManager: ChatSessionManager? = null
    private val _currentSessionId = MutableStateFlow(java.util.UUID.randomUUID().toString())
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _savedSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val savedSessions: StateFlow<List<ChatSession>> = _savedSessions.asStateFlow()

    fun initSessionManager(context: Context) {
        appContext = context.applicationContext
        val savedVoice = context.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
            .getString("selected_live_voice", "Aoede") ?: "Aoede"
        _liveVoiceName.value = savedVoice

        val savedAccent = context.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
            .getString("selected_voice_accent", "Standard American") ?: "Standard American"
        _liveVoiceAccent.value = savedAccent

        if (sessionManager == null) {
            val sm = ChatSessionManager(context.applicationContext)
            sessionManager = sm
            _savedSessions.value = sm.getAllSessions()
        }
        sanitizeExistingMessages()
    }

    fun sanitizeExistingMessages() {
        for (i in 0 until messages.size) {
            val msg = messages[i]
            val cleaned = cleanTranscriptText(msg.text)
            if (cleaned != msg.text) {
                messages[i] = msg.copy(text = cleaned)
            }
        }
    }

    fun autoSaveCurrentSession() {
        val sm = sessionManager ?: return
        if (messages.isEmpty() || (messages.size == 1 && messages[0].sender == "OttoAI")) {
            return
        }

        val odoKm = obdManager.vehicleOdometerKm.value
        val odoStr = odoKm?.let { km ->
            if (unitSystem.value == UnitSystem.SAE) {
                String.format(java.util.Locale.US, "%,d mi", (km * 0.621371).toInt())
            } else {
                String.format(java.util.Locale.US, "%,d km", km.toInt())
            }
        }
        val dtcList = troubleCodes.value
        val title = ChatSessionManager.generateDefaultTitle(
            messages = messages.toList(),
            vehicleDetails = obdManager.vehicleDetails.value,
            odometerFormatted = odoStr,
            dtcs = dtcList
        )

        // Attach up to 300 samples (~5 minutes at 1Hz) from the black-box flight recorder.
        // Keeps drive chat session JSON lightweight (~70-100 KB) while ensuring instant flight replay works!
        val recordedCopy = synchronized(blackBoxBuffer) {
            if (blackBoxBuffer.isNotEmpty()) {
                blackBoxBuffer.toList()
            } else {
                synchronized(recordedSamples) { recordedSamples.takeLast(300) }
            }
        }

        val session = ChatSession(
            id = _currentSessionId.value,
            title = title,
            timestamp = System.currentTimeMillis(),
            vehicleDetails = obdManager.vehicleDetails.value,
            vehicleVin = obdManager.vehicleVin.value,
            odometerFormatted = odoStr,
            dtcs = dtcList,
            messages = messages.toList(),
            recordedSamples = recordedCopy,
            mode06Results = obdManager.mode06Results.value
        )

        // Offload disk I/O, heavy JSON serialization and loading all sessions to background thread
        // to prevent freezing the UI main thread and causing ForegroundServiceDidNotStartInTimeException ANRs
        viewModelScope.launch(Dispatchers.IO) {
            sm.saveSession(session)
            val all = sm.getAllSessions()
            _savedSessions.value = all
        }
    }

    fun createNewChatSession(autoSave: Boolean = true) {
        stopReplay()
        if (autoSave) {
            autoSaveCurrentSession()
        }
        synchronized(recordedSamples) { recordedSamples.clear() }
        _recordedSamplesFlow.value = emptyList()
        _currentSessionId.value = java.util.UUID.randomUUID().toString()
        messages.clear()
        messages.add(
            ChatMessage(
                sender = "VERA",
                text = "Hello! I am VERA, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
            )
        )
        sessionManager?.let { sm ->
            _savedSessions.value = sm.getAllSessions()
        }
    }

    fun loadChatSession(sessionId: String) {
        stopReplay()
        autoSaveCurrentSession()
        val sm = sessionManager ?: return
        val session = sm.loadSession(sessionId) ?: return
        _currentSessionId.value = session.id
        messages.clear()
        if (session.safeMessages.isNotEmpty()) {
            messages.addAll(session.safeMessages.map { it.copy(text = cleanTranscriptText(it.text)) })
        } else {
            messages.add(
                ChatMessage(
                    sender = "VERA",
                    text = "Loaded diagnostic session: ${session.title}"
                )
            )
        }
        val samples = session.safeRecordedSamples
        synchronized(recordedSamples) {
            recordedSamples.clear()
            recordedSamples.addAll(samples)
        }
        _recordedSamplesFlow.value = samples
    }

    fun deleteChatSession(sessionId: String) {
        val sm = sessionManager ?: return
        if (_isReplaying.value && _currentSessionId.value == sessionId) {
            stopReplay()
        }
        val isDeletingCurrent = _currentSessionId.value == sessionId
        sm.deleteSession(sessionId)
        if (isDeletingCurrent) {
            createNewChatSession(autoSave = false)
        } else {
            _savedSessions.value = sm.getAllSessions()
        }
    }

    /**
     * Export a saved drive session to formatted diagnostic text optimized for external AI analysis.
     */
    fun exportSessionToText(sessionId: String): String {
        val sm = sessionManager ?: return ""
        val session = sm.loadSession(sessionId) ?: return ""
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("       VERA VEHICLE DIAGNOSTIC & DRIVE REPORT     ")
        sb.appendLine("==================================================")
        sb.appendLine("Title: ${session.title}")
        sb.appendLine("Date: ${session.formattedDate}")
        sb.appendLine("Vehicle: ${session.vehicleDetails ?: "Unknown Vehicle"}")
        sb.appendLine("VIN: ${session.vehicleVin ?: "N/A"}")
        if (!session.odometerFormatted.isNullOrBlank()) {
            sb.appendLine("Odometer: ${session.odometerFormatted}")
        }
        val dtcs = session.safeDtcs
        if (dtcs.isNotEmpty()) {
            sb.appendLine("Diagnostic Trouble Codes (DTCs): ${dtcs.joinToString(", ")}")
        } else {
            sb.appendLine("Diagnostic Trouble Codes (DTCs): None detected / Clean")
        }
        val samples = session.safeRecordedSamples
        if (samples.isNotEmpty()) {
            val durationSec = (samples.last().timestampMs - samples.first().timestampMs) / 1000.0
            sb.appendLine("Recorded Telemetry Samples: ${samples.size} data points (~${String.format(java.util.Locale.US, "%.1f", durationSec)}s duration)")
        }
        sb.appendLine("==================================================")
        sb.appendLine()
        sb.appendLine("--- CONVERSATION TRANSCRIPT & MECHANIC INTERACTION ---")
        val msgs = session.safeMessages
        if (msgs.isEmpty()) {
            sb.appendLine("(No chat messages logged in this session.)")
        } else {
            for ((idx, msg) in msgs.withIndex()) {
                val senderLabel = when (msg.sender) {
                    "User" -> "USER / DRIVER"
                    "VERA", "OttoAI" -> "VERA (AI AUTOMOTIVE MECHANIC)"
                    "VERA (Live)", "OttoAI (Live)" -> "VERA (LIVE VOICE SESSION)"
                    else -> msg.sender.uppercase()
                }
                val timeStr = dateFormat.format(java.util.Date(msg.timestamp))
                sb.appendLine("[$timeStr] [${idx + 1}] $senderLabel:")
                sb.appendLine(msg.text.trim())
                sb.appendLine("--------------------------------------------------")
            }
        }

        if (samples.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- TELEMETRY FLIGHT DATA SUMMARY (HIGH-SPEED OBD2 LOG) ---")
            val isSae = _unitSystem.value == UnitSystem.SAE
            val spdUnit = if (isSae) "mph" else "km/h"
            val tempUnit = if (isSae) "°F" else "°C"

            val maxRpm = samples.maxOfOrNull { it.metricsSnapshot.rpm } ?: 0
            val avgRpm = samples.map { it.metricsSnapshot.rpm }.average().toInt()
            val maxSpeed = samples.maxOfOrNull { if (isSae) it.metricsSnapshot.speed * 0.621371 else it.metricsSnapshot.speed.toDouble() } ?: 0.0
            val maxCoolantRaw = samples.maxOfOrNull { it.metricsSnapshot.coolantTemp } ?: 0
            val maxCoolant = if (isSae) (maxCoolantRaw * 9.0 / 5.0) + 32.0 else maxCoolantRaw.toDouble()
            val maxLoad = samples.maxOfOrNull { it.metricsSnapshot.engineLoad } ?: 0
            val maxTrim = samples.maxOfOrNull { it.metricsSnapshot.fuelTrim } ?: 0
            val minTrim = samples.minOfOrNull { it.metricsSnapshot.fuelTrim } ?: 0

            sb.appendLine("Max RPM: $maxRpm | Avg RPM: $avgRpm")
            sb.appendLine("Max Speed: ${String.format(java.util.Locale.US, "%.1f", maxSpeed)} $spdUnit")
            sb.appendLine("Max Coolant Temp: ${String.format(java.util.Locale.US, "%.1f", maxCoolant)} $tempUnit")
            sb.appendLine("Max Engine Load: $maxLoad%")
            sb.appendLine("Fuel Trim Range: ${minTrim}% to ${maxTrim}%")
            val roadSamples = samples.mapNotNull { it.roadContextSnapshot }
            if (roadSamples.isNotEmpty()) {
                val maxGrade = roadSamples.maxOfOrNull { it.roadGradePercent } ?: 0.0
                val minGrade = roadSamples.minOfOrNull { it.roadGradePercent } ?: 0.0
                val locations = roadSamples.map { it.streetName }.filter { it.isNotBlank() && it != "Locating..." }.distinct()
                sb.appendLine("Road Grade Range: ${String.format(java.util.Locale.US, "%+.1f%%", minGrade)} to ${String.format(java.util.Locale.US, "%+.1f%%", maxGrade)}")
                if (locations.isNotEmpty()) {
                    sb.appendLine("Key Traversed Locations: ${locations.take(5).joinToString(", ")}")
                }
            }
            sb.appendLine("==================================================")
        }

        sb.appendLine()
        sb.appendLine("[AI Prompt Hint: This diagnostic log was produced by VERA Automotive Assistant. You can analyze the trouble codes, mechanical dialogue, and telemetry metrics above to provide a second opinion or deeper mechanical triage.]")
        return sb.toString()
    }

    /**
     * Export a saved drive session's full high-speed telemetry log into CSV format.
     */
    fun exportSessionToCsv(sessionId: String): String {
        val sm = sessionManager ?: return ""
        val session = sm.loadSession(sessionId) ?: return ""
        val samples = session.safeRecordedSamples
        if (samples.isEmpty()) return ""
        return generateCsvData(
            samples = samples,
            vehicleDetails = session.vehicleDetails,
            vehicleVin = session.vehicleVin,
            unitSystem = _unitSystem.value
        )
    }

    /**
     * Export the raw JSON file content of a saved drive session.
     */
    fun exportSessionToJson(sessionId: String): String {
        val sm = sessionManager ?: return ""
        val session = sm.loadSession(sessionId) ?: return ""
        return sm.getSessionJson(sessionId) ?: ""
    }

    // --- REPLAY PLAYBACK CONTROLS ---
    fun startReplay(sessionId: String) {
        val sm = sessionManager ?: return
        val session = sm.loadSession(sessionId) ?: return
        val samples = session.safeRecordedSamples
        if (samples.isEmpty()) {
            messages.add(ChatMessage("System", "No flight telemetry data recorded for this session."))
            return
        }

        // Load chat session if not already loaded
        if (_currentSessionId.value != sessionId) {
            loadChatSession(sessionId)
        }

        replayJob?.cancel()
        activeReplaySamples = samples
        _isReplaying.value = true
        _isReplayPaused.value = false
        val firstMs = samples.first().timestampMs
        val lastMs = samples.last().timestampMs
        val durationMs = maxOf(1000L, lastMs - firstMs)
        _replayTotalDurationMs.value = durationMs
        _replayCurrentMs.value = 0L
        _replayProgress.value = 0f
        _replaySessionTitle.value = session.title
        _replaySample.value = samples.first()

        replayJob = viewModelScope.launch(Dispatchers.Default) {
            while (_isReplaying.value) {
                if (!_isReplayPaused.value) {
                    var currentOffset = _replayCurrentMs.value + 100L
                    if (currentOffset >= durationMs) {
                        currentOffset = durationMs
                        _isReplayPaused.value = true
                    }
                    _replayCurrentMs.value = currentOffset
                    _replayProgress.value = (currentOffset.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

                    val targetMs = firstMs + currentOffset
                    val closest = samples.minByOrNull { kotlin.math.abs(it.timestampMs - targetMs) }
                    _replaySample.value = closest
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun toggleReplayPause() {
        if (_replayCurrentMs.value >= _replayTotalDurationMs.value) {
            seekReplay(0f)
            _isReplayPaused.value = false
        } else {
            _isReplayPaused.value = !_isReplayPaused.value
        }
    }

    fun pauseReplay() {
        _isReplayPaused.value = true
    }

    fun resumeReplay() {
        if (_replayCurrentMs.value >= _replayTotalDurationMs.value) {
            seekReplay(0f)
        }
        _isReplayPaused.value = false
    }

    fun seekReplay(fraction: Float) {
        val totalDuration = _replayTotalDurationMs.value
        if (totalDuration <= 0L) return
        val targetOffset = (fraction.coerceIn(0f, 1f) * totalDuration).toLong()
        _replayCurrentMs.value = targetOffset
        _replayProgress.value = fraction.coerceIn(0f, 1f)

        val samples = if (activeReplaySamples.isNotEmpty()) {
            activeReplaySamples
        } else {
            sessionManager?.loadSession(_currentSessionId.value)?.safeRecordedSamples ?: emptyList()
        }
        if (samples.isNotEmpty()) {
            val firstMs = samples.first().timestampMs
            val targetMs = firstMs + targetOffset
            val closest = samples.minByOrNull { kotlin.math.abs(it.timestampMs - targetMs) }
            _replaySample.value = closest
        }
    }

    fun seekReplayBy(deltaMs: Long) {
        val totalDuration = _replayTotalDurationMs.value
        if (totalDuration <= 0L) return
        val newOffset = (_replayCurrentMs.value + deltaMs).coerceIn(0L, totalDuration)
        val fraction = newOffset.toFloat() / totalDuration.toFloat()
        seekReplay(fraction)
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        activeReplaySamples = emptyList()
        _isReplaying.value = false
        _isReplayPaused.value = false
        _replaySample.value = null
        _replayProgress.value = 0f
        _replayCurrentMs.value = 0L
    }

    private fun buildRecentConversationContextForLive(maxMessages: Int = 20): String {
        val relevant = messages
            .filter { it.sender == "User" || it.sender == "VERA" || it.sender == "VERA (Live)" || it.sender == "OttoAI" || it.sender == "OttoAI (Live)" }
            .takeLast(maxMessages)
        if (relevant.isEmpty()) return ""
        return relevant.joinToString("\n") { msg ->
            val label = if (msg.sender == "User") "DRIVER" else "VERA"
            "$label: ${msg.text.trim()}"
        }
    }

    fun initLiveStreamManager(context: Context) {
        appContext = context.applicationContext
        initSessionManager(context)
        VeraVoiceService.onStopRequested = {
            stopGeminiLiveSession()
        }

        if (wakeWordManager == null) {
            val wwm = VeraWakeWordManager(context.applicationContext) {
                onWakeWordTriggered()
            }
            wakeWordManager = wwm
            viewModelScope.launch {
                wwm.isListeningForWakeWord.collect { listening ->
                    _isWakeWordListening.value = listening
                }
            }
        }

        if (liveStreamManager != null) return
        val mgr = GeminiLiveStreamManager(context.applicationContext)
        liveStreamManager = mgr
        mgr.setVoice(_liveVoiceName.value)
        viewModelScope.launch {
            mgr.isLiveSessionActive.collect { active ->
                _isLiveSessionActive.value = active
            }
        }
        viewModelScope.launch {
            mgr.isGeminiSpeaking.collect { speaking ->
                _isGeminiSpeaking.value = speaking
                if (!speaking && _isLiveSessionActive.value && _isHandsFreeVoiceActive.value) {
                    // Vera just finished speaking: open a follow-up window (30s so slow replies aren't cut off)
                    startFollowUpWindow(FOLLOW_UP_WINDOW_SECONDS)
                } else if (speaking) {
                    cancelFollowUpTimer()
                }
            }
        }
        viewModelScope.launch {
            mgr.isUserSpeaking.collect { userSpeaking ->
                if (userSpeaking) {
                    // Driver is speaking: cancel follow-up timer so session doesn't suspend mid-sentence
                    cancelFollowUpTimer()
                }
            }
        }
        viewModelScope.launch {
            mgr.voiceName.collect { name ->
                _liveVoiceName.value = name
            }
        }
    }

    private fun onWakeWordTriggered() {
        viewModelScope.launch(Dispatchers.Main) {
            if (_isHandsFreeVoiceActive.value && !_isLiveSessionActive.value) {
                Log.i("GeminiAgentViewModel", "Wake word 'Hey Vera' detected: connecting live session")
                startGeminiLiveSession()
                startFollowUpWindow(FOLLOW_UP_WINDOW_SECONDS)
            }
        }
    }

    fun startFollowUpWindow(seconds: Int = FOLLOW_UP_WINDOW_SECONDS) {
        followUpTimerJob?.cancel()
        followUpTimerJob = viewModelScope.launch {
            for (s in seconds downTo 1) {
                _followUpSecondsRemaining.value = s
                delay(1000L)
            }
            _followUpSecondsRemaining.value = 0
            // Do NOT suspend if driver is currently speaking
            if (liveStreamManager?.isUserSpeaking?.value == true) {
                Log.i("GeminiAgentViewModel", "Follow-up timer reached 0 but driver is actively speaking. Keeping live session active.")
                return@launch
            }
            if (_isLiveSessionActive.value && _isHandsFreeVoiceActive.value) {
                Log.i("GeminiAgentViewModel", "${seconds}-second follow-up window expired. Suspending live cloud stream.")
                liveStreamManager?.stopLiveSession()
                wakeWordManager?.startListening()
            }
        }
    }

    fun cancelFollowUpTimer() {
        followUpTimerJob?.cancel()
        followUpTimerJob = null
        _followUpSecondsRemaining.value = 0
    }

    fun toggleHandsFreeVoiceMode() {
        if (_isHandsFreeVoiceActive.value) {
            stopGeminiLiveSession()
        } else {
            _isHandsFreeVoiceActive.value = true
            startGeminiLiveSession()
        }
    }

    fun startGeminiLiveSession() {
        if (VeraKillSwitch.isDeactivated.value) {
            val msg = VeraKillSwitch.deactivationMessage.value ?: "This test build of VERA has expired or been disabled."
            messages.add(ChatMessage("Error", msg))
            stopGeminiLiveSession()
            return
        }
        if (geminiApiKey.isBlank()) {
            messages.add(ChatMessage("Error", "GEMINI_API_KEY is not set in local.properties."))
            return
        }
        val mgr = liveStreamManager
        if (mgr == null) {
            messages.add(ChatMessage("Error", "Gemini Live stream manager is not initialized yet."))
            return
        }

        cancelFollowUpTimer()
        wakeWordManager?.stopListening()
        stopSpeaking()
        _isHandsFreeVoiceActive.value = true

        appContext?.let {
            VeraVoiceService.startService(it)
        }

        val summary = buildTelemetrySnapshotForLive()
        val historyContext = buildRecentConversationContextForLive(maxMessages = 20)
        isNewLiveTurn = true
        isNewUserTurn = true
        currentUserTurnRaw.clear()
        currentLiveTurnRaw.clear()

        messages.add(
            ChatMessage(
                "System",
                "Hands-free 2-way conversation is live.  Ask anything. Vera has full, live, read-only access to your vehicle's data."
            )
        )

        mgr.startLiveSession(
            apiKey = geminiApiKey,
            systemInstruction = buildLiveSystemInstruction(),
            telemetrySummary = summary,
            telemetryProvider = { buildTelemetrySnapshotForLive() },
            conversationHistory = historyContext,
            onMessageReceived = { text ->
                if (text.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.Main) {
                        appendOrUpdateLiveTranscript(text)
                    }
                }
            },
            onUserTranscriptReceived = { text ->
                if (text.isNotBlank()) {
                    cancelFollowUpTimer()
                    onUserTranscriptReceived(text)
                }
            },
            onTurnComplete = {
                viewModelScope.launch(Dispatchers.Main) {
                    isNewLiveTurn = true
                    isNewUserTurn = true
                    currentUserTurnRaw.clear()
                    currentLiveTurnRaw.clear()
                    autoSaveCurrentSession()
                    val lastUserMsg = messages.lastOrNull { it.sender == "User" }?.text
                    if (!lastUserMsg.isNullOrBlank()) {
                        checkAndExecuteLocalMonitoringIntent(lastUserMsg)
                    }
                }
            },
            onToolCall = { toolName, callId, args ->
                executeLiveTool(toolName, callId, args)
            },
            onError = { errMsg ->
                viewModelScope.launch(Dispatchers.Main) {
                    messages.add(ChatMessage("Error", "Gemini Live Error: $errMsg"))
                    _isHandsFreeVoiceActive.value = false
                    cancelFollowUpTimer()
                    wakeWordManager?.stopListening()
                    appContext?.let {
                        VeraVoiceService.stopService(it)
                    }
                }
            }
        )
    }

    fun stopGeminiLiveSession() {
        cancelFollowUpTimer()
        wakeWordManager?.stopListening()
        stopSpeaking()
        _isHandsFreeVoiceActive.value = false
        isNewLiveTurn = true
        isNewUserTurn = true
        currentUserTurnRaw.clear()
        currentLiveTurnRaw.clear()
        liveStreamManager?.stopLiveSession()
        appContext?.let {
            VeraVoiceService.stopService(it)
        }
        messages.add(ChatMessage("System", "Hands-free mode stopped"))
        autoSaveCurrentSession()
    }

    fun interruptLivePlayback() {
        isNewLiveTurn = true
        isNewUserTurn = true
        currentUserTurnRaw.clear()
        currentLiveTurnRaw.clear()
        liveStreamManager?.interruptPlayback()
    }

    private var isNewUserTurn = true
    private var isNewLiveTurn = true
    private val currentUserTurnRaw = StringBuilder()
    private val currentLiveTurnRaw = StringBuilder()

    fun onUserTranscriptReceived(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (isNewUserTurn) {
                currentUserTurnRaw.clear()
                currentUserTurnRaw.append(text)
                val cleaned = cleanTranscriptText(currentUserTurnRaw.toString())
                messages.add(ChatMessage("User", cleaned))
                isNewUserTurn = false
            } else {
                currentUserTurnRaw.append(text)
                val cleaned = cleanTranscriptText(currentUserTurnRaw.toString())
                val lastMsg = messages.lastOrNull()
                if (lastMsg != null && lastMsg.sender == "User") {
                    val idx = messages.size - 1
                    messages[idx] = lastMsg.copy(text = cleaned)
                } else {
                    messages.add(ChatMessage("User", cleaned))
                }
            }
        }
    }

    private fun appendOrUpdateLiveTranscript(text: String) {
        isNewUserTurn = true
        currentUserTurnRaw.clear()

        if (isNewLiveTurn) {
            currentLiveTurnRaw.clear()
            currentLiveTurnRaw.append(text)
            val cleaned = cleanTranscriptText(currentLiveTurnRaw.toString())
            messages.add(ChatMessage("VERA (Live)", cleaned))
            isNewLiveTurn = false
        } else {
            currentLiveTurnRaw.append(text)
            val cleaned = cleanTranscriptText(currentLiveTurnRaw.toString())
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null && (lastMsg.sender == "VERA (Live)" || lastMsg.sender == "OttoAI (Live)")) {
                val idx = messages.size - 1
                messages[idx] = lastMsg.copy(text = cleaned)
            } else {
                messages.add(ChatMessage("VERA (Live)", cleaned))
            }
        }
    }

    private fun buildLiveSystemInstruction(): String {
        val isMetric = unitSystem.value == UnitSystem.METRIC
        val unitRules = if (isMetric) {
            """
            UNITS SYSTEM IN EFFECT: METRIC (SI).
            The driver has explicitly selected METRIC units.
            - You MUST speak all speeds in km/h (kilometers per hour).
            - You MUST speak all temperatures in degrees Celsius (°C).
            - You MUST speak all pressures in kPa (kilopascals).
            - You MUST speak all distances and odometer readings in kilometers (km).
            - DO NOT speak in miles, Fahrenheit, or psi unless the driver explicitly asks for an imperial conversion.
            """.trimIndent()
        } else {
            """
            UNITS SYSTEM IN EFFECT: SAE (US IMPERIAL).
            The driver has explicitly selected SAE units.
            - You MUST speak all speeds in mph (miles per hour).
            - You MUST speak all temperatures in degrees Fahrenheit (°F).
            - You MUST speak all pressures in psi (pounds per square inch).
            - You MUST speak all distances and odometer readings in miles.
            - DO NOT speak in kilometers, Celsius, or kPa unless the driver explicitly asks for a metric conversion.
            """.trimIndent()
        }

        return """
            You are VERA (Vehicle Evaluation & Repair Assistant), an elite automotive master diagnostic technician and veteran shade-tree mechanic riding shotgun with the driver.
            Your name VERA stands for "Vehicle Evaluation & Repair Assistant". Whenever asked what VERA stands for, your full name, or who you are, state clearly and proudly: "VERA stands for Vehicle Evaluation and Repair Assistant."
            You combine 30 years of hands-on garage wisdom, dealership master technician certification, and brilliant diagnostic deduction.

            You are interacting with the driver in REAL-TIME 2-WAY HANDS-FREE VOICE MODE over bidirectional audio while they are operating their vehicle.

            $unitRules

            CORE MISSION & ANTI-SYCOPHANCY PHILOSOPHY (DIAGNOSTIC TRUTH COMES FIRST):
            - YOUR HIGHEST OBJECTIVE IS THE DIAGNOSTIC TRUTH AND THE MECHANICAL HEALTH OF THE VEHICLE.
            - NEVER BE A "YES-MAN": Reflexive agreement, appeasement, or people-pleasing is dangerous in automotive diagnosis. App success depends entirely upon arriving at the unvarnished diagnostic truth and protecting the driver and vehicle.
            - REMAIN OBJECTIVE AND NEUTRAL: You must be completely neutral to whether an answer is pleasant or what the user hoped to hear. If sensor data or mechanical principles contradict what the driver suspects, respectfully set the record straight with evidence. Never agree just to sound agreeable.
            - BLUE-COLLAR MECHANIC PERSONA: Be pleasant, engaging, and witty in a seasoned blue-collar mechanic's typical style—warm, plainspoken, and practical, with grease-under-the-fingernails common sense. But never sacrifice diagnostic accuracy for charm.
            - HONESTY ABOUT CAPABILITIES: If asked to perform an action outside your capabilities (actuation, physical vehicle control, monitoring sensors not on OBD2), immediately and clearly state your limits. Never say you will do something or promise future action unless you actually execute the tool.

            ELITE MECHANIC KNOWLEDGE & MASTER DIAGNOSTIC CAPABILITIES:
            - DEALERSHIP LEVEL WISDOM: Deep knowledge of OEM service manuals, factory technical service bulletins (TSBs), known pattern failures, engine architecture quirks (e.g. AFM/MDS lifters, VVT/cam phaser oil starvation, direct-injection carbon buildup, timing chain stretch, ground strap corrosion, heater core bypass tricks).
            - SHADE-TREE MECHANIC PLAYBOOK & DRIVEWAY FIELD TRIAGE:
              * Never throw expensive parts at a problem. Always prioritize zero-cost, quick-triage diagnostic tests first:
                - Coil/Plug swap test (swap ignition coil or spark plug to another cylinder to see if a misfire follows the part or stays on the cylinder).
                - Driveway vacuum leak test (carb cleaner, unlit propane, or water mist around intake gaskets, PCV hoses, and brake booster).
                - Voltage drop testing across battery posts, starter terminals, and chassis/engine ground straps instead of guessing.
                - Fuel pump relay swap test (swap with horn/AC relay) and tapping the fuel tank or starter solenoid with a mallet/wood block to limp home.
                - Stethoscope / long screwdriver test against engine blocks, alternators, and water pumps to isolate mechanical ticking vs. pulley bearing whine.
              * Realistic wrenching warnings: Warn about brittle plastic cooling connectors, aluminum head spark plug cross-threading, stuck exhaust studs, or bleeding air bubbles from tricky cooling systems.

            OBD2 DATA QUIRKS (READ THIS BEFORE BLAMING THE CAR):
            - The adapter reads the car's computers, not the dashboard. A reading of 0, "--", a flat-lined value, or an impossible jump (0 volts, -40 degrees, 255, 65535) is almost always a data dropout, a sensor this model doesn't have, or a PID this car doesn't support. It is NOT evidence of a failed part.
            - BATTERY / ALTERNATOR VOLTAGE: PID 0142 is the computer's supply voltage. It reads low (9-11 V) while cranking, can sit near 12 V for the first several seconds after the engine catches, and hybrids and start-stop cars legitimately shut the engine off. NEVER suspect the alternator from a single reading, a reading at startup, or a 0 V reading. Only raise it if voltage stays under about 12.5 V for 30+ seconds with the engine steadily running AND the driver reports a dash battery light or symptoms. Healthy running voltage is roughly 13.5 to 14.8 V.
            - A temperature of exactly -40 means the raw byte was zero (no data). Never report it as a real temperature.
            - The first 30 seconds after connecting, the adapter is still probing protocols and the stream is warming up. Do not diagnose anything from that window.
            - Different cars answer different PIDs. If a value is not in your telemetry snapshot, say it isn't available from this car. NEVER invent or assume a value.
            - Enhanced (Mode 21/22) manufacturer PIDs are model-specific. If one looks implausible (negative oil temperature on a warm day), say the reading looks unreliable instead of diagnosing from it.
            - If the dashboard shows no warning lights and the car drives normally, trust that over one odd OBD value. A one-off glitch that has cleared is not worth bringing up again.

            CRITICAL DRIVING & VOICE RULES:
            1. NATURAL HUMAN MECHANIC VOICE & CONVERSATION:
               - Speak naturally, warmly, and conversationally, like a seasoned, sharp mechanic sitting in the passenger seat.
               - Keep spoken answers concise (1 to 3 sentences) so the driver stays focused on the road.
               - NEVER recite raw PIDs, sensor codes, raw voltages, or test IDs out loud unless the driver explicitly asks for exact numbers.
            2. REAL-TIME TELEMETRY GROUNDING & ULTRA-LOW LATENCY:
               - Vehicle telemetry changes dynamically while driving.
               - LIVE PROMPT SNAPSHOT & REAL-TIME TOOL REFRESH:
                 * You receive a live telemetry snapshot in your prompt, and live context updates are pushed to you automatically when the vehicle connects.
                 * CRITICAL CONNECTION & LIVE DATA RULE: Whenever the driver asks whether you are connected, asks to check again, or asks for current live readings (steering angle, wheel speeds, RPM, speed, temperatures, fuel trims, battery voltage, or trouble codes), ALWAYS invoke `get_live_data` (or `get_vehicle_info`) if your initial snapshot showed Disconnected/Connecting or if the driver asks for the latest live numbers! Never assume you are still disconnected without calling `get_live_data` first!
               - TRANSMISSION COMMANDED GEAR & PARK POSITION:
                 * On Ford and GM automatic transmissions, Mode 22 PID 11B3 (Commanded Gear) measures the PCM hydraulic shift solenoid commanded circuit.
                 * When the vehicle is STATIONARY / PARKED (speed 0 mph), the PCM commands hydraulic state 2 as the baseline for the Park/Neutral interlock. If the driver is parked and asks what gear they are in, tell them the transmission is in Park (or Neutral) and explain that state 2 is the stationary hydraulic solenoid baseline, NOT forward 2nd gear!
               - INITIALIZING STREAM & NO HALLUCINATION DIRECTIVE:
                 * When OBD2 is connected but the live stream is INITIALIZING (first frames still arriving from ECU):
                 * If asked for live speed, gear, RPM, or temperatures right at connection time, state clearly: "I just connected to your OBD adapter and am establishing the live data stream with your vehicle; give me just a few moments to get the first readings." Never guess, fabricate, or hallucinate sensor values!
               - BLACK-BOX FLIGHT RECORDER & MOMENT SNAPSHOTS:
                 * When the driver asks you to save data, record this moment, snapshot an incident, or log a spike (e.g. "Vera, save that data", "record the last 5 minutes", "log that knock", "save a snapshot of that spike"):
                 * You MUST invoke the `save_flight_recorder_snapshot` tool with a brief description or reason.
                 * Confirm in one short natural sentence that you saved a 5-minute flight data black-box snapshot containing all vehicle readings around that moment.
               - If OBD2 is DISCONNECTED:
                 * You DO NOT have access to live vehicle telemetry, engine RPM, coolant temperature, speed, battery voltage, or trouble codes.
                 * You MUST NEVER state "everything looks fine", "all indicators look great", or guess sensor values when disconnected.
                 * If the driver asks about their vehicle's health, data, speed, RPM, codes, or engine while disconnected, you MUST explicitly tell them: "We're not connected to your vehicle's OBD2 port right now, so I cannot see your live data or trouble codes. Please connect your OBD2 adapter or select Demo Mode in the app."
               - ALWAYS obey the active unit system ($unitRules) when speaking numbers out loud!
            3. AUTONOMOUS SENTINEL & SCHEDULING DIRECTIVES:
               - When the driver asks you to check, read, or report a telemetry parameter repeatedly or periodically (e.g. "report my fuel trims every 20 seconds for a minute", "tell me my speed every 10 seconds", "watch coolant every 30s"):
                 * You MUST invoke the `schedule_periodic_telemetry_report` tool with the requested parameter, interval_seconds, and duration_seconds.
                 * CRITICAL BURST PREVENTION: In your spoken answer acknowledging the schedule, YOU MUST ONLY CONFIRM ONCE IN A SINGLE BRIEF SENTENCE (e.g., "Got it, I'll keep an eye on your fuel trims and report every 10 seconds."). ABSOLUTELY DO NOT simulate, loop, or recite multiple updates in this turn! The background watchdog service will independently speak each reading at each timed interval.
               - When delivering periodic sensor readings or gauge updates to the driver:
                 * Speak naturally and casually, like a seasoned mechanic sitting in the passenger seat watching the dashboard gauges (e.g. "Fuel trims are sitting at +2% right now", "Coolant's at 195", "Speed is 55").
                 * NEVER say "Periodic update:" or sound like an automated robot. Give the data in a natural conversational style.
               - When the driver asks you to watch or alert them if a sensor crosses a threshold (e.g. "let me know if my RPMs go over 2000", "warn me if coolant temp crosses 220", "alert me if speed goes over 80"):
                 * You MUST invoke the `set_telemetry_threshold_alert` tool with the parameter, comparison condition ('>', '<', '>=', '<='), and threshold_value.
                 * Confirm briefly in one natural sentence that you are monitoring that threshold.
               - When the driver asks to cancel or stop active timers, watchers, or alerts (e.g. "stop reporting", "cancel alerts", "stop watching"):
                 * You MUST invoke the `cancel_telemetry_alerts` tool.
            4. STRICT CAPABILITIES & HARD BOUNDARY ENFORCEMENT:
               - HONESTY OVER AGREEABLENESS:
                 * Never agree to perform an action or promise future checks unless you actively execute a corresponding function tool in that turn (`schedule_periodic_telemetry_report`, `set_telemetry_threshold_alert`, `cancel_telemetry_alerts`, `get_live_data`, `get_dtcs`).
                 * If the driver asks you to do something outside your technical capabilities (e.g. adjust the radio, control the air conditioning, roll down windows, unlock doors, clear transmission adaptations, flash the ECU), DO NOT say "Sure, doing that now".
                 * You MUST immediately refuse honestly and state your limits: "I cannot do that. I have read-only diagnostic access to your vehicle's engine and sensors; I cannot control vehicle physical hardware or actuators."
               - SUPPORTED SENSORS FOR PERIODIC REPORTS & ALERTS:
                 * You CAN schedule periodic reports or threshold alerts for: Engine RPM, Vehicle Speed, Coolant Temperature, Short & Long Term Fuel Trims, Mass Air Flow (MAF), Engine Load, Throttle Position, Battery Voltage, Mode 06 Cylinder Misfires, Mode 22 Transmission Temp, Mode 03 Confirmed DTCs, and Mode 07 Pending DTCs.
                 * If the driver asks to monitor or alert on a sensor that is NOT broadcast over standard OBD2 (e.g. tire pressure on cars without OBD2 TPMS, oil quality percentage, blind spot radar), state honestly: "I cannot monitor that parameter because your vehicle's OBD2 stream does not broadcast it."
            5. GEOGRAPHIC, NAVIGATION & ROAD TOPOGRAPHY AWARENESS:
               - You have real-time awareness of the driver's geographic location, current street name, elevation, road grade (% incline / slope), and MOTION STATE (Stationary/Parked vs Driving in Motion).
               - CRITICAL VEHICLE SPEED & MOTION AWARENESS BEFORE SPEAKING:
                 * ALWAYS check vehicle speed, wheel speed, and phone acceleration before discussing driving conditions, hills, climbs, or descents!
                 * If the car is parked, idling, or stationary (speed is 0 or under 2 mph): DO NOT say or assume the driver is actively driving, climbing, or descending a hill! Even if they are parked on a hillside road or in a steep driveway, acknowledge they are parked or sitting stationary (e.g. "You're parked on an incline in your driveway" or "Sitting stationary on a slope with the engine idling at 0 mph").
                 * NEVER say things like "as you climb this grade", "pulling up this hill", "descending the road", or "under hill climb load" unless the car is ACTUALLY MOVING in drive (speed > 4 mph).
               - When ACTIVELY DRIVING up a grade (speed > 4 mph, 5%+ incline):
                 * Engine load, intake air flow, and exhaust heat are expected to rise naturally under hill climb load.
                 * High combustion chamber pressure stresses spark plugs and ignition coils—watch for cylinder misfires.
               - When ACTIVELY DRIVING on a downhill descent (speed > 4 mph):
                 * Engine braking engages and deceleration fuel cut-off (DFCO) activates.
               - If the driver asks where they are, what street they are on, what their elevation is, or whether they are on an incline, answer accurately using your live road context or the `get_road_topography` tool, explicitly stating whether the car is parked or in motion.
            6. SAFETY FIRST:
               - The driver is operating a vehicle. Never give instructions that require them to look away from the road or manipulate parts while driving.
        """.trimIndent()
    }

    private fun buildTelemetrySnapshotForLive(): String {
        val connState = obdManager.connectionState.value
        val isConnected = connState is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
        val isConnecting = connState is BluetoothObdManager.ConnectionState.Connecting
        val currentUnit = unitSystem.value
        val isMetric = currentUnit == UnitSystem.METRIC

        if (isConnecting) {
            return """
                OBD2 CONNECTION STATUS: CONNECTING / HANDSHAKING WITH ADAPTER
                Vehicle: Handshaking with OBD2 adapter and querying VIN/PIDs...
                DIRECTIVE: The OBD2 adapter is actively connecting right now (takes ~2-4 seconds). If the driver asks if you are connected or asks for live data, ALWAYS call the `get_live_data` tool first to check if the connection handshake has completed!
            """.trimIndent()
        }

        if (!isConnected) {
            return """
                OBD2 CONNECTION STATUS: DISCONNECTED (NOT CONNECTED TO VEHICLE)
                Vehicle: None detected (Disconnected)
                VIN: None (Disconnected)
                Odometer: Unavailable (Disconnected)
                Active DTCs: UNAVAILABLE - Disconnected from vehicle ECU
                Live Telemetry: UNAVAILABLE - Disconnected from vehicle ECU
                Mode 06 Monitors: UNAVAILABLE - Disconnected from vehicle ECU
                
                DIRECTIVE: At the moment this snapshot was taken, the OBD2 adapter was disconnected. However, the driver may connect at any moment during our conversation! Therefore, if the driver asks if you are connected, says they are connected, or asks for live vehicle data, you MUST call `get_live_data` to check the live connection state before saying you are disconnected.
            """.trimIndent()
        }

        val vin = obdManager.vehicleVin.value ?: "Scanning/Pending"
        val vehicle = obdManager.vehicleDetails.value ?: "Vehicle"
        val isStreaming = obdManager.isStreamingLiveData.value
        val streams = obdManager.supportedStreams.value
        val opendbcSigs = obdManager.opendbcSignals.value

        if (!isStreaming && streams.isEmpty() && opendbcSigs.isEmpty()) {
            val road = locationManager?.roadContext?.value
            val roadText = if (road != null &&
                (!road.isDemoSimulated || obdManager.isDemoMode) &&
                road.streetName.isNotBlank() &&
                road.streetName != "Locating road..." &&
                road.streetName != "Locating..."
            ) {
                road.formattedTopography
            } else "Determining GPS road position..."

            return """
                Vehicle: $vehicle (VIN: $vin)
                Road & Topography: $roadText
                OBD2 Connection: Connected (Live Stream Initializing)
                Live Telemetry: INITIALIZING - Polling loop has started; awaiting first live ECU sensor frames.
                
                DIRECTIVE: The OBD2 adapter is connected! If the driver asks for live speed, gear, steering angle, RPM, or temperatures right now, call `get_live_data` to fetch the latest readings.
            """.trimIndent()
        }

        val metrics = obdManager.metrics.value
        val dtcs = troubleCodes.value
        val m06 = obdManager.mode06Results.value
        val m22Streams = obdManager.mode22Streams.value
        val odoKm = obdManager.vehicleOdometerKm.value

        val road = locationManager?.roadContext?.value
        val obdSpeedMph = (metrics.speed * 0.621371).toInt()
        val isVehicleMoving = (road?.isMoving == true) || (obdSpeedMph >= 3)

        val streamsText = if (streams.isNotEmpty()) {
            streams.take(15).joinToString(", ") { s ->
                val (fmtVal, fmtUnit) = formatValueWithUnit(s.rawValue, s.pid, currentUnit)
                "${s.pid.name}: $fmtVal $fmtUnit"
            }
        } else {
            val spd = if (isMetric) "${metrics.speed} km/h" else "${(metrics.speed * 0.621371).toInt()} mph"
            val temp = if (isMetric) "${metrics.coolantTemp}°C" else "${(metrics.coolantTemp * 1.8 + 32).toInt()}°F"
            "RPM: ${metrics.rpm}, Speed: $spd, Coolant: $temp"
        }

        val allCustomPids = buildUnifiedCustomPidList(
            mode22Streams = m22Streams,
            obdbLoadedResult = obdManager.obdbLoadedResult.value,
            obdbSignalResults = obdManager.obdbSignalResults.value,
            unitSystem = currentUnit,
            vehicleDetails = vehicle,
            isMoving = isVehicleMoving
        )

        val m22Text = if (allCustomPids.isNotEmpty()) {
            val gauges = allCustomPids.filter { !it.isBooleanStatus }
            val switches = allCustomPids.filter { it.isBooleanStatus }
            val gaugeStr = gauges.joinToString(", ") { "${it.name} [${it.category.displayName}]: ${it.formattedValue} ${it.unit}".trim() }
            val switchStr = if (switches.isNotEmpty()) {
                " | Status Switches (${switches.size}): " + switches.take(25).joinToString(", ") { "${it.name}=${it.formattedValue}" }
            } else ""
            gaugeStr + switchStr
        } else "None polled yet"

        val canDynamicsText = if (opendbcSigs.isNotEmpty()) {
            opendbcSigs.values.joinToString(", ") { sig ->
                "${sig.name}: ${sig.formattedValue}"
            }
        } else "Awaiting CAN broadcast sample"

        val odoText = if (odoKm != null && odoKm > 0) {
            if (isMetric) String.format(java.util.Locale.US, "%,.0f km", odoKm)
            else String.format(java.util.Locale.US, "%,.0f miles", odoKm * 0.621371)
        } else "Not detected"
        val dtcText = if (dtcs.isEmpty()) "None (Clean)" else dtcs.joinToString(", ")
        val m06Text = if (m06.isNotEmpty()) {
            "${m06.size} monitors evaluated (" + m06.joinToString(", ") { "${it.name}: ${it.testValue.toInt()} ${it.unit} [${it.status}]" } + ")"
        } else "Unscanned"

        val motionStateText = if (isVehicleMoving) {
            val spdText = if (isMetric) "${metrics.speed} km/h" else "$obdSpeedMph mph"
            "DRIVING / IN MOTION ($spdText)"
        } else {
            "STATIONARY / PARKED (0 mph - sitting in driveway or idling, NOT driving)"
        }

        val roadText = if (road != null &&
            (!road.isDemoSimulated || obdManager.isDemoMode) &&
            road.streetName.isNotBlank() &&
            road.streetName != "Locating road..." &&
            road.streetName != "Locating..."
        ) {
            road.formattedTopography
        } else "Determining GPS road position & elevation..."

        return """
            OBD2 CONNECTION STATUS: CONNECTED TO VEHICLE ($vehicle, VIN: $vin)
            Vehicle: $vehicle (VIN: $vin)
            Odometer: $odoText
            Motion State: $motionStateText
            Unit System Preference: ${if (isMetric) "METRIC (km/h, °C, kPa, km)" else "SAE (mph, °F, psi, miles)"}
            Road & Topography: $roadText
            OBD2 Connection: Connected & Actively Streaming ECU + CAN Bus Telemetry
            Active DTCs: $dtcText
            Latest OBD2 Telemetry (Mode 01): $streamsText
            High-Speed CAN Bus Dynamics (opendbc - Steering, Wheels, Brake, Pedal): $canDynamicsText
            Vehicle Custom PIDs & OBDb Signals (${allCustomPids.size} decoded): $m22Text
            Mode 06 Monitors: $m06Text
        """.trimIndent()
    }

    private fun executeLiveTool(name: String, callId: String, @Suppress("UNUSED_PARAMETER") args: JSONObject): JSONObject {
        Log.i("GeminiAgentViewModel", "Executing live tool: $name ($callId)")
        return try {
            val isConn = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
            when (name) {
                "get_live_data" -> {
                    if (!isConn) {
                        val isConnecting = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connecting
                        if (isConnecting) {
                            return JSONObject().apply {
                                put("connected", true)
                                put("status", "CONNECTING_HANDSHAKE")
                                put("message", "OBD2 adapter is actively finishing its connection handshake right now. Tell the driver you are connecting to the vehicle and readings will appear in a couple seconds.")
                            }
                        }
                        return JSONObject().apply {
                            put("connected", false)
                            put("status", "DISCONNECTED")
                            put("error", "OBD2 adapter is not connected to the vehicle. No live telemetry available. Instruct user to connect to the vehicle OBD2 adapter or launch Demo Mode.")
                        }
                    }
                    val streams = obdManager.supportedStreams.value
                    val opendbcSigs = obdManager.opendbcSignals.value
                    if (!obdManager.isStreamingLiveData.value && streams.isEmpty() && opendbcSigs.isEmpty()) {
                        return JSONObject().apply {
                            put("connected", true)
                            put("status", "STREAM_INITIALIZING")
                            put("vehicle", obdManager.vehicleDetails.value ?: "Vehicle")
                            put("vin", obdManager.vehicleVin.value ?: "Scanning")
                            put("message", "The OBD2 adapter is connected to ${obdManager.vehicleDetails.value ?: "the vehicle"}, and the live data stream is initializing.")
                        }
                    }
                    val metrics = obdManager.metrics.value
                    val voltage = streams.find { it.pid.pidHex == "42" }?.formattedValue?.takeIf { it != "--" } ?: "not available yet"
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    JSONObject().apply {
                        put("connected", true)
                        put("status", "CONNECTED_STREAMING")
                        put("driver_unit_system", if (isMetric) "METRIC (State speeds in km/h, temps in °C, pressures in kPa, distances in km)" else "SAE (State speeds in mph, temps in °F, pressures in psi, distances in miles)")
                        put("rpm", metrics.rpm)
                        put("is_engine_running", metrics.rpm > 300)
                        if (isMetric) {
                            put("speed", "${metrics.speed} km/h")
                            put("coolant_temp", "${metrics.coolantTemp} °C")
                            put("intake_air_temp", "${metrics.intakeAirTemp} °C")
                        } else {
                            put("speed", "${(metrics.speed * 0.621371).toInt()} mph")
                            put("coolant_temp", "${(metrics.coolantTemp * 1.8 + 32).toInt()} °F")
                            put("intake_air_temp", "${(metrics.intakeAirTemp * 1.8 + 32).toInt()} °F")
                        }
                        put("speed_mph", (metrics.speed * 0.621371).toInt())
                        put("speed_kmh", metrics.speed)
                        put("coolant_temp_f", (metrics.coolantTemp * 1.8) + 32)
                        put("coolant_temp_c", metrics.coolantTemp)
                        put("intake_air_temp_f", (metrics.intakeAirTemp * 1.8) + 32)
                        put("intake_air_temp_c", metrics.intakeAirTemp)
                        put("battery_voltage", voltage)
                        put("engine_load_percent", metrics.engineLoad)
                        put("throttle_position_percent", metrics.throttlePosition)
                        put("fuel_trim_percent", metrics.fuelTrim)
                        put("mass_air_flow_gs", metrics.maf)
                        put("vehicle", obdManager.vehicleDetails.value ?: "Vehicle")
                        put("vin", obdManager.vehicleVin.value ?: "Unknown")
                        val road = locationManager?.roadContext?.value
                        if (road != null && road.streetName.isNotBlank() && road.streetName != "Locating...") {
                            put("current_road", road.streetName)
                            put("city", road.neighborhoodOrCity)
                            put("road_grade_percent", road.roadGradePercent)
                            put("incline_status", road.inclineStatus.description)
                            put("elevation_feet", road.altitudeFeet.toInt())
                            put("road_topography", road.formattedTopography)
                        }
                        val odoKm = obdManager.vehicleOdometerKm.value
                        if (odoKm != null && odoKm > 0) {
                            if (isMetric) {
                                put("odometer", String.format(java.util.Locale.US, "%,.0f km", odoKm))
                                put("odometer_km", odoKm.toInt())
                            } else {
                                put("odometer", String.format(java.util.Locale.US, "%,.0f miles", odoKm * 0.621371))
                                put("odometer_miles", (odoKm * 0.621371).toInt())
                            }
                        }
                        // 1. Comprehensive Vehicle Health & Anomaly Summary (Zero-Lag Awareness):
                        // We summarize DTCs and Misfires directly at the top so Vera is 100% aware of all defects
                        // without requiring a 3,000-word payload dump that stalls voice turnaround.
                        val activeDtcs = troubleCodes.value
                        val m06List = obdManager.mode06Results.value
                        val failedM06 = m06List.filter { it.status == Mode06Status.FAIL }

                        val healthObj = JSONObject().apply {
                            put("active_dtcs_count", activeDtcs.size)
                            if (activeDtcs.isNotEmpty()) {
                                put("dtc_codes", JSONArray(activeDtcs))
                            }
                            put("failed_monitors_count", failedM06.size)
                            if (failedM06.isNotEmpty()) {
                                val failArr = JSONArray()
                                for (fm in failedM06) {
                                    failArr.put("${fm.name} (${fm.category}): testValue=${fm.testValue} vs max=${fm.maxLimit}")
                                }
                                put("failed_monitors", failArr)
                            }
                            put("overall_health_verdict", when {
                                activeDtcs.isNotEmpty() || failedM06.isNotEmpty() -> "ATTENTION REQUIRED (DTCs or failing component monitors detected)"
                                metrics.coolantTemp > 105 -> "WARNING: Engine coolant running hot"
                                kotlin.math.abs(metrics.fuelTrim) > 15 -> "WARNING: High fuel trim variance"
                                else -> "NOMINAL / HEALTHY (All monitored systems within spec)"
                            })
                        }
                        put("vehicle_health_summary", healthObj)

                        // 2. Focused Stream Inclusion:
                        // Only include secondary custom PIDs or high-speed CAN streams if the driver asked for something specific
                        // or if an anomaly is present, preserving sub-second turnaround times for conversation!
                        val requestedSensor = args.optString("sensor_focus", "").lowercase().trim()
                        if (requestedSensor.isNotBlank() && requestedSensor != "all") {
                            val roadSnap = locationManager?.roadContext?.value
                            val isMoving = (roadSnap?.isMoving == true) || ((metrics.speed * 0.621371) >= 3)
                            val allCustomPids = buildUnifiedCustomPidList(
                                mode22Streams = obdManager.mode22Streams.value,
                                obdbLoadedResult = obdManager.obdbLoadedResult.value,
                                obdbSignalResults = obdManager.obdbSignalResults.value,
                                unitSystem = currentUnit,
                                vehicleDetails = obdManager.vehicleDetails.value,
                                isMoving = isMoving
                            )
                            val matchedCustom = allCustomPids.filter { it.name.lowercase().contains(requestedSensor) || it.category.displayName.lowercase().contains(requestedSensor) }
                            if (matchedCustom.isNotEmpty()) {
                                val customArr = JSONArray()
                                for (c in matchedCustom) {
                                    customArr.put(JSONObject().apply {
                                        put("name", c.name)
                                        put("value", "${c.formattedValue} ${c.unit}".trim())
                                    })
                                }
                                put("requested_custom_pids", customArr)
                            }

                            val matchedCan = opendbcSigs.filter { it.key.lowercase().contains(requestedSensor) }
                            if (matchedCan.isNotEmpty()) {
                                val canArr = JSONObject()
                                for ((k, sig) in matchedCan) {
                                    canArr.put(k, sig.formattedValue)
                                }
                                put("requested_can_dynamics", canArr)
                            }
                        }
                    }
                }
                "get_dtcs" -> {
                    if (!isConn) {
                        return JSONObject().apply {
                            put("connected", false)
                            put("status", "DISCONNECTED")
                            put("error", "OBD2 adapter is not connected. Diagnostic trouble codes cannot be read.")
                        }
                    }
                    val dtcs = dtcDetails.value
                    val codes = troubleCodes.value
                    JSONObject().apply {
                        put("connected", true)
                        put("active_trouble_codes_count", codes.size)
                        put("codes", JSONArray(codes))
                        val detailsArr = JSONArray()
                        for (d in dtcs) {
                            detailsArr.put(JSONObject().apply {
                                put("code", d.code)
                                put("category", d.category)
                                put("description", d.description)
                            })
                        }
                        put("details", detailsArr)
                    }
                }
                "get_mode06_results" -> {
                    if (!isConn) {
                        return JSONObject().apply {
                            put("connected", false)
                            put("status", "DISCONNECTED")
                            put("error", "OBD2 adapter is not connected. Mode 06 component monitors cannot be read.")
                        }
                    }
                    val m06 = obdManager.mode06Results.value
                    JSONObject().apply {
                        put("connected", true)
                        put("monitors_count", m06.size)
                        val monArr = JSONArray()
                        for (m in m06) {
                            monArr.put(JSONObject().apply {
                                put("name", m.name)
                                put("category", m.category)
                                put("value", m.testValue)
                                put("min_limit", m.minLimit)
                                put("max_limit", m.maxLimit)
                                put("unit", m.unit)
                                put("status", m.status.name)
                            })
                        }
                        put("monitors", monArr)
                    }
                }
                "get_vehicle_info" -> {
                    if (!isConn) {
                        return JSONObject().apply {
                            put("connected", false)
                            put("status", "DISCONNECTED")
                            put("vin", "None (Disconnected)")
                            put("vehicle_details", "No vehicle connected")
                            put("odometer", "Unavailable")
                            put("error", "OBD2 adapter is not connected to vehicle.")
                        }
                    }
                    val odoKm = obdManager.vehicleOdometerKm.value
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    JSONObject().apply {
                        put("connected", true)
                        put("vin", obdManager.vehicleVin.value ?: "Not available")
                        put("vehicle_details", obdManager.vehicleDetails.value ?: "Not available")
                        put("driver_unit_system", if (isMetric) "METRIC (State distance in km)" else "SAE (State distance in miles)")
                        if (odoKm != null && odoKm > 0) {
                            if (isMetric) {
                                put("odometer", String.format(java.util.Locale.US, "%,.0f km", odoKm))
                                put("odometer_km", odoKm.toInt())
                            } else {
                                put("odometer", String.format(java.util.Locale.US, "%,.0f miles", odoKm * 0.621371))
                                put("odometer_miles", (odoKm * 0.621371).toInt())
                            }
                            put("odometer_formatted", String.format(java.util.Locale.US, "%,.0f miles (%,.0f km)", odoKm * 0.621371, odoKm))
                        } else {
                            put("odometer", "Unavailable")
                        }
                        put("connection_status", connectionStatus.value)
                    }
                }
                "get_obdb_signals" -> {
                    if (!isConn) {
                        return JSONObject().apply {
                            put("connected", false)
                            put("status", "DISCONNECTED")
                            put("error", "OBD2 adapter is not connected. Custom Mode 22 & OBDb signals unavailable.")
                        }
                    }
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    val allCustomPids = buildUnifiedCustomPidList(
                        mode22Streams = obdManager.mode22Streams.value,
                        obdbLoadedResult = obdManager.obdbLoadedResult.value,
                        obdbSignalResults = obdManager.obdbSignalResults.value,
                        unitSystem = currentUnit,
                        vehicleDetails = obdManager.vehicleDetails.value,
                        isMoving = obdManager.metrics.value.speed > 2
                    )
                    JSONObject().apply {
                        put("connected", true)
                        put("driver_unit_system", if (isMetric) "METRIC (State temps in °C, pressures in kPa, distances in km)" else "SAE (State temps in °F, pressures in psi, distances in miles)")
                        put("total_custom_pids_count", allCustomPids.size)
                        val arr = JSONArray()
                        for (item in allCustomPids) {
                            arr.put(JSONObject().apply {
                                put("name", item.name)
                                put("category", item.category.displayName)
                                put("value", "${item.formattedValue} ${item.unit}".trim())
                                put("is_switch", item.isBooleanStatus)
                            })
                        }
                        put("custom_pids", arr)
                    }
                }
                "schedule_periodic_telemetry_report" -> {
                    val param = args.optString("parameter", "fuel_trim")
                    val interval = args.optInt("interval_seconds", 20)
                    val duration = args.optInt("duration_seconds", 60)
                    val repeatCount = args.optInt("repeat_count", 0)
                    sentinelManager.schedulePeriodicReport(
                        parameter = param,
                        intervalSeconds = interval,
                        durationSeconds = duration,
                        repeatCount = repeatCount,
                        currentUnit = unitSystem.value,
                        getTelemetrySnapshot = { buildTelemetrySnapshotMap() }
                    )
                }
                "set_telemetry_threshold_alert" -> {
                    val param = args.optString("parameter", "rpm")
                    val condition = args.optString("condition", ">")
                    val threshold = args.optDouble("threshold_value", 2000.0)
                    val customMsg = if (args.has("custom_message")) args.getString("custom_message") else null
                    sentinelManager.setThresholdAlert(
                        parameter = param,
                        condition = condition,
                        threshold = threshold,
                        customMessage = customMsg,
                        currentUnit = unitSystem.value
                    )
                }
                "cancel_telemetry_alerts" -> {
                    val param = if (args.has("parameter")) args.getString("parameter") else "all"
                    sentinelManager.cancelAlerts(param)
                }
                "get_road_topography" -> {
                    if (locationManager?.inDemoMode != true) {
                        val current = locationManager?.roadContext?.value
                        if (current == null || current.isDemoSimulated || current.streetName == "Locating road..." || current.streetName == "Locating...") {
                            try {
                                runBlocking {
                                    withTimeoutOrNull(2500L) {
                                        locationManager?.awaitFreshLocation()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("GeminiAgentViewModel", "Location await timed out or failed: ${e.message}")
                            }
                        } else {
                            locationManager?.forceRefreshLocation()
                        }
                    }
                    val road = locationManager?.roadContext?.value ?: RoadContext()
                    val obdSpeedMph = (obdManager.metrics.value.speed * 0.621371).toInt()
                    val isMoving = road.isMoving || obdSpeedMph >= 3
                    JSONObject().apply {
                        put("status", "success")
                        put("street_name", road.streetName)
                        put("city", road.neighborhoodOrCity)
                        put("full_address", road.fullAddress)
                        put("latitude", road.latitude)
                        put("longitude", road.longitude)
                        put("road_grade_percent", road.roadGradePercent)
                        put("incline_status", if (isMoving) road.inclineStatus.description else road.inclineStatus.stationaryDescription)
                        put("is_vehicle_moving", isMoving)
                        put("motion_state", if (isMoving) "Driving (${maxOf(obdSpeedMph, road.speedMph.roundToInt())} mph)" else "Stationary / Parked (0 mph)")
                        put("altitude_feet", road.altitudeFeet.roundToInt())
                        put("altitude_meters", road.altitudeMeters.roundToInt())
                        put("heading", road.headingCompass)
                        put("speed_mph", if (isMoving) maxOf(obdSpeedMph, road.speedMph.roundToInt()) else 0)
                        put("speed_kmh", if (isMoving) maxOf(obdManager.metrics.value.speed, road.speedKmh.roundToInt()) else 0)
                        put("phone_accel_mps2", String.format(java.util.Locale.US, "%.2f", road.phoneAccelMps2))
                        put("formatted_summary", road.formattedTopography)
                    }
                }
                "evaluate_topographical_performance" -> {
                    if (locationManager?.inDemoMode != true) {
                        locationManager?.forceRefreshLocation()
                    }
                    val road = locationManager?.roadContext?.value ?: RoadContext()
                    val metrics = obdManager.metrics.value
                    val streams = obdManager.supportedStreams.value
                    val m06 = obdManager.mode06Results.value
                    val m22 = obdManager.mode22Streams.value
                    val isMetric = unitSystem.value == UnitSystem.METRIC

                    val transStream = m22.find { it.pid.name.contains("Transmission", ignoreCase = true) }
                    val transTemp = transStream?.formattedValue ?: "not available"
                    val volt = streams.find { it.pid.pidHex == "42" }?.formattedValue?.takeIf { it != "--" } ?: "not available yet"

                    val misfireSummary = m06.filter { it.category.contains("Misfire", ignoreCase = true) }
                    val failingMisfires = misfireSummary.filter { it.status == Mode06Status.FAIL || it.testValue > 5.0 }

                    val speedMph = (metrics.speed * 0.621371).toInt()
                    val isMoving = speedMph >= 3 || road.isMoving || (road.speedMph >= 3.0)
                    val isUphill = road.roadGradePercent >= 3.5 || road.inclineStatus == InclineStatus.STEEP_CLIMB || road.inclineStatus == InclineStatus.MODERATE_CLIMB
                    val isDownhill = road.roadGradePercent <= -3.0 || road.inclineStatus == InclineStatus.STEEP_DESCENT || road.inclineStatus == InclineStatus.MODERATE_DESCENT

                    val mechanicVerdict = when {
                        !isMoving -> {
                            if (kotlin.math.abs(road.roadGradePercent) >= 1.5) {
                                val slopeType = if (road.roadGradePercent > 0) "incline" else "downhill slope"
                                "Stationary on ${road.streetName} (${String.format(java.util.Locale.US, "%+.1f%%", road.roadGradePercent)} grade). Vehicle is currently parked or idling on an $slopeType (0 mph). Engine is at idle (${metrics.rpm} RPM, ${metrics.engineLoad}% load) with zero driving stress."
                            } else {
                                "Vehicle is stationary on ${road.streetName} (0 mph, level ground). Engine idling normally at ${metrics.rpm} RPM."
                            }
                        }
                        isUphill && failingMisfires.isNotEmpty() -> {
                            val cyl = failingMisfires.first().name.replace("Misfires", "").trim()
                            "Catching misfires on $cyl under ${metrics.engineLoad}% load climbing ${road.streetName} (+${road.roadGradePercent}% grade). Ignition breakdown under high combustion chamber pressure."
                        }
                        isUphill && metrics.fuelTrim >= 15 -> {
                            "Fuel trims spiking lean (+${metrics.fuelTrim}%) under uphill climb on ${road.streetName}. Computer is dumping maximum fuel; check for fuel pump starvation or clogged filter under load."
                        }
                        isUphill -> {
                            "Pulling smoothly up ${road.streetName} (+${road.roadGradePercent}% grade) at ${metrics.engineLoad}% engine load. Zero cylinder misfires, fuel trims holding steady at ${if (metrics.fuelTrim >= 0) "+" else ""}${metrics.fuelTrim}%, and catalytic/exhaust temps are stable."
                        }
                        isDownhill -> {
                            "Descending ${road.streetName} (${road.roadGradePercent}% grade). Engine braking engaged, throttle closed (${metrics.throttlePosition}%), and fuel trims are stable."
                        }
                        else -> {
                            "Cruising level on ${road.streetName} at ${if (isMetric) "${metrics.speed} km/h" else "$speedMph mph"}. Engine load is moderate (${metrics.engineLoad}%), fuel trims normal (${metrics.fuelTrim}%), and combustion is clean."
                        }
                    }

                    JSONObject().apply {
                        put("status", "success")
                        put("road_location", road.formattedTopography)
                        put("street_name", road.streetName)
                        put("road_grade_percent", road.roadGradePercent)
                        put("incline_status", if (isMoving) road.inclineStatus.description else road.inclineStatus.stationaryDescription)
                        put("is_vehicle_moving", isMoving)
                        put("motion_state", if (isMoving) "Driving ($speedMph mph)" else "Stationary / Parked (0 mph)")
                        put("engine_load_percent", metrics.engineLoad)
                        put("rpm", metrics.rpm)
                        put("speed", if (isMetric) "${metrics.speed} km/h" else "$speedMph mph")
                        put("coolant_temp", if (isMetric) "${metrics.coolantTemp}°C" else "${(metrics.coolantTemp * 1.8 + 32).toInt()}°F")
                        put("fuel_trim", "${if (metrics.fuelTrim >= 0) "+" else ""}${metrics.fuelTrim}%")
                        put("transmission_temp", transTemp)
                        put("battery_voltage", volt)
                        put("active_misfires_detected", failingMisfires.isNotEmpty())
                        if (failingMisfires.isNotEmpty()) {
                            put("failing_misfire_monitors", JSONArray().apply {
                                failingMisfires.forEach { put("${it.name}: ${it.testValue.toInt()} misfires (FAIL)") }
                            })
                        }
                        put("mechanic_verdict", mechanicVerdict)
                    }
                }
                "save_flight_recorder_snapshot" -> {
                    val reason = args.optString("reason", "Driver requested data snapshot")
                    val res = saveFlightRecorderSnapshot(reason)
                    JSONObject().apply {
                        put("success", res.success)
                        put("sample_count", res.sampleCount)
                        put("duration_seconds", res.durationSeconds)
                        put("filename", res.filename)
                        put("message", if (res.success) {
                            "Successfully saved 5-minute flight data black-box snapshot (${res.sampleCount} points) to ${res.filename}."
                        } else {
                            "No flight data recorded in buffer yet. Drive for a moment and try again."
                        })
                    }
                }
                else -> {
                    JSONObject().put("status", "unknown tool $name")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiAgentViewModel", "Error in live tool $name", e)
            JSONObject().put("error", e.message ?: "Failed to execute tool")
        }
    }

    fun checkAndExecuteLocalMonitoringIntent(userText: String): Boolean {
        val lower = userText.lowercase().trim()

        // 0. Check for flight recorder snapshot intent ("save that data", "log that spike", "record the last 5 minutes")
        if (lower.contains("save that data") || lower.contains("save data") ||
            lower.contains("save snapshot") || lower.contains("snapshot that") ||
            lower.contains("log that spike") || lower.contains("log that knock") ||
            lower.contains("record the last 5 minutes") || lower.contains("record last 5 minutes")) {
            saveFlightRecorderSnapshot("Voice command: '$userText'")
            if (isVoiceModeActive) {
                speak("I've saved a 5-minute flight data black-box snapshot of all sensor readings to your Downloads.")
            }
            return true
        }

        // 1. Check for cancellation
        if (lower.contains("stop monitor") || lower.contains("cancel monitor") ||
            lower.contains("stop watch") || lower.contains("cancel watch") ||
            lower.contains("stop checking") || lower.contains("stop reporting") ||
            lower == "stop" || lower == "cancel") {
            sentinelManager.cancelAlerts("all")
            val msg = "I've stopped the recurring monitoring reports."
            messages.add(ChatMessage("VERA", msg))
            autoSaveCurrentSession()
            if (isVoiceModeActive) {
                val liveInjected = if (_isHandsFreeVoiceActive.value) {
                    liveStreamManager?.injectDriverAlert(msg, isAdvisory = true) ?: false
                } else false
                if (!liveInjected) speak(msg)
            }
            return true
        }

        // 2. Check for scheduling / periodic intent keywords
        val isMonitoringIntent = lower.contains("monitor") || lower.contains("watch") ||
                lower.contains("track") || lower.contains("report") || lower.contains("update") ||
                lower.contains("check") || lower.contains("keep an eye")

        val hasRecurringKeyword = lower.contains("every") || lower.contains("each") ||
                lower.contains("periodic") || lower.contains("regular") || lower.contains("interval") ||
                lower.contains("second") || lower.contains("minute")

        if (!isMonitoringIntent || !hasRecurringKeyword) return false

        // Extract parameter
        val parameter = when {
            lower.contains("fuel") || lower.contains("trim") -> "fuel_trim"
            lower.contains("rpm") || lower.contains("tach") || lower.contains("rev") || lower.contains("engine speed") -> "rpm"
            lower.contains("coolant") || lower.contains("water temp") || lower.contains("engine temp") || (lower.contains("temp") && !lower.contains("trans")) -> "coolant"
            lower.contains("speed") || lower.contains("mph") || lower.contains("kmh") -> "speed"
            lower.contains("trans") || lower.contains("transmission") -> "trans_temp"
            lower.contains("volt") || lower.contains("battery") || lower.contains("alternator") -> "voltage"
            else -> "all"
        }

        if (sentinelManager.hasActiveWatcher(parameter)) {
            // Already actively tracking this parameter from a concurrent call
            return false
        }

        // Extract interval seconds (e.g. "every 20 seconds", "every 15s", "every 10 sec", "every minute")
        var intervalSeconds = 20
        val intervalMatch = Regex("""(?:every|each)\s+(\d+)\s*(?:s|sec|secs|seconds?)?""").find(lower)
        if (intervalMatch != null) {
            val num = intervalMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0) intervalSeconds = num
        } else if (lower.contains("every minute") || lower.contains("each minute")) {
            intervalSeconds = 60
        } else if (lower.contains("every half minute") || lower.contains("every 30")) {
            intervalSeconds = 30
        }

        // Extract duration seconds (e.g. "for a minute", "for 2 minutes", "for 60 seconds")
        var durationSeconds = 60
        val durationMatchSec = Regex("""for\s+(\d+)\s*(?:s|sec|secs|seconds?)""").find(lower)
        val durationMatchMin = Regex("""for\s+(\d+)\s*(?:m|min|mins|minutes?)""").find(lower)
        if (durationMatchSec != null) {
            val num = durationMatchSec.groupValues[1].toIntOrNull()
            if (num != null && num > 0) durationSeconds = num
        } else if (durationMatchMin != null) {
            val num = durationMatchMin.groupValues[1].toIntOrNull()
            if (num != null && num > 0) durationSeconds = num * 60
        } else if (lower.contains("for a minute") || lower.contains("for one minute")) {
            durationSeconds = 60
        } else if (lower.contains("for 2 minutes") || lower.contains("for two minutes")) {
            durationSeconds = 120
        } else if (intervalSeconds >= 30) {
            durationSeconds = intervalSeconds * 3
        }

        val safeDuration = durationSeconds.coerceAtLeast(intervalSeconds)

        val readableParam = when (parameter) {
            "fuel_trim" -> "fuel trims"
            "rpm" -> "engine RPM"
            "coolant" -> "coolant temperature"
            "speed" -> "vehicle speed"
            "trans_temp" -> "transmission temperature"
            "voltage" -> "battery voltage"
            else -> "gauges"
        }

        Log.i("GeminiAgentViewModel", "Local Monitoring Intent Triggered: param=$parameter, interval=${intervalSeconds}s, duration=${safeDuration}s")

        sentinelManager.schedulePeriodicReport(
            parameter = parameter,
            intervalSeconds = intervalSeconds,
            durationSeconds = safeDuration,
            repeatCount = 0,
            currentUnit = unitSystem.value,
            getTelemetrySnapshot = { buildTelemetrySnapshotMap() }
        )

        val confirmMsg = "I'll keep an eye on your $readableParam and give you an update every $intervalSeconds seconds."
        messages.add(ChatMessage("VERA", confirmMsg))
        autoSaveCurrentSession()

        if (isVoiceModeActive) {
            val liveInjected = if (_isHandsFreeVoiceActive.value) {
                liveStreamManager?.injectDriverAlert(confirmMsg, isAdvisory = true) ?: false
            } else false
            if (!liveInjected) {
                speak(confirmMsg)
            }
        }
        return true
    }

    private val _liveVoiceName = MutableStateFlow("Aoede")
    val liveVoiceName: StateFlow<String> = _liveVoiceName.asStateFlow()

    private val _liveVoiceAccent = MutableStateFlow("Standard American")
    val liveVoiceAccent: StateFlow<String> = _liveVoiceAccent.asStateFlow()

    val vehicleOdometerKm: StateFlow<Double?> = obdManager.vehicleOdometerKm

    fun setLiveVoiceName(voiceName: String) {
        val trimmed = voiceName.trim()
        val found = GeminiLiveStreamManager.SUPPORTED_LIVE_VOICES.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: "Aoede"
        _liveVoiceName.value = found
        appContext?.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
            ?.edit()?.putString("selected_live_voice", found)?.apply()
        liveStreamManager?.setVoice(found)
        Log.i("VERA_Voice", "setLiveVoiceName updated to '$found'")
    }

    fun setLiveVoiceAccent(accent: String) {
        val validAccents = listOf(
            "Standard American",
            "British (RP)",
            "Scottish",
            "Australian",
            "Irish",
            "Southern US",
            "New York / Boston",
            "Canadian",
            "German English",
            "Indian English"
        )
        val selected = validAccents.firstOrNull { it.equals(accent.trim(), ignoreCase = true) } ?: "Standard American"
        _liveVoiceAccent.value = selected
        appContext?.getSharedPreferences("vera_voice_settings", Context.MODE_PRIVATE)
            ?.edit()?.putString("selected_voice_accent", selected)?.apply()
        Log.i("VERA_Voice", "setLiveVoiceAccent updated to '$selected'")
    }

    fun previewVoiceAccent(accentName: String) {
        setLiveVoiceAccent(accentName)
        val phrase = when (accentName) {
            "British (RP)" -> "Right then, I'm Vera. Ready to inspect your motor."
            "Scottish" -> "Aye, I'm Vera. Let's see what's under the bonnet."
            "Australian" -> "G'day, I'm Vera. Ready to check out your vehicle."
            "Irish" -> "Hello there, I'm Vera. All set for diagnostics."
            "Southern US" -> "Hey y'all, I'm Vera. Let's take a look under the hood."
            "New York / Boston" -> "Hey, I'm Vera. Let's run the diagnostics."
            "Canadian" -> "Hello, I'm Vera. Ready to check your vehicle today."
            "German English" -> "Hello, I am Vera. Ready for precision diagnostics."
            "Indian English" -> "Hello, I am Vera. Ready to check all vehicle parameters."
            else -> "Hello, I'm Vera. Ready to diagnose your vehicle."
        }
        previewVoice(_liveVoiceName.value, phrase)
    }

    override fun onCleared() {
        super.onCleared()
        cancelFollowUpTimer()
        wakeWordManager?.stopListening()
        appContext?.let {
            VeraVoiceService.stopService(it)
        }
        liveStreamManager?.stopLiveSession()
        tts?.stop()
        tts?.shutdown()
    }

    fun recheckVin() {
        viewModelScope.launch {
            if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected) {
                messages.add(ChatMessage("System", "Cannot read VIN: OBD2 adapter is not connected."))
                return@launch
            }
            messages.add(ChatMessage("System", "Re-querying Mode 09 Vehicle Identification Number (VIN)..."))
            val vin = obdManager.fetchVehicleVin()
            if (!vin.isNullOrBlank() && vin.length == 17) {
                messages.add(ChatMessage("System", "VIN Decoded: ${obdManager.vehicleDetails.value}"))
            } else {
                messages.add(ChatMessage("Error", "Could not read Mode 09 VIN. Ensure ignition key is in READY or IGNITION ON mode."))
            }
        }
    }


    private val _isSendingMessage = MutableStateFlow(false)
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()

    private val _unitSystem = MutableStateFlow(UnitSystem.SAE)
    val unitSystem: StateFlow<UnitSystem> = _unitSystem.asStateFlow()

    fun setUnitSystem(system: UnitSystem) {
        _unitSystem.value = system
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _recordingSampleCount = MutableStateFlow(0)
    val recordingSampleCount: StateFlow<Int> = _recordingSampleCount.asStateFlow()

    private val _recordedSamplesFlow = MutableStateFlow<List<RecordedDataSample>>(emptyList())
    val recordedSamplesFlow: StateFlow<List<RecordedDataSample>> = _recordedSamplesFlow.asStateFlow()

    // 5-minute rolling in-memory black-box flight recorder (300 samples at 1Hz = 5 min).
    // Always maintained in RAM when connected to vehicle; lightweight (~100 KB), zero continuous disk I/O.
    private val blackBoxBuffer = java.util.ArrayDeque<RecordedDataSample>(300)
    private val recordedSamples = mutableListOf<RecordedDataSample>()
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var recordingStartMs = 0L

    var lastRecordedCsvResult: RecordedCsvResult? = null
        private set

    fun startRecording() {
        if (_isRecording.value) return
        synchronized(recordedSamples) { recordedSamples.clear() }
        _recordedSamplesFlow.value = emptyList()
        recordingStartMs = System.currentTimeMillis()
        _recordingDurationSeconds.value = 0
        _recordingSampleCount.value = 0
        _isRecording.value = true

        messages.add(ChatMessage("System", "Live Data Recording Started. Telemetry data is being logged continuously..."))

        recordingJob = viewModelScope.launch(Dispatchers.Default) {
            while (_isRecording.value) {
                val now = System.currentTimeMillis()
                val elapsedSec = (now - recordingStartMs) / 1000.0
                val metricsSnap = obdManager.metrics.value
                // Strip per-stream 300-point sparkline history so saved session JSONs stay compact (<100 KB instead of 20 MB)
                val streamsSnap = obdManager.supportedStreams.value.map { it.copy(history = emptyList()) }
                val mode22Snap = obdManager.mode22Streams.value.toList()
                val roadSnap = _roadContext.value

                val sample = RecordedDataSample(
                    timestampMs = now,
                    elapsedSeconds = elapsedSec,
                    metricsSnapshot = metricsSnap,
                    streamSnapshots = streamsSnap,
                    mode22Snapshots = mode22Snap,
                    roadContextSnapshot = roadSnap
                )

                var count = 0
                synchronized(recordedSamples) {
                    // Cap live memory log at 14,400 samples (1 hour at 4Hz) to protect memory
                    if (recordedSamples.size >= 14400) {
                        recordedSamples.removeAt(0)
                    }
                    recordedSamples.add(sample)
                    count = recordedSamples.size
                }

                _recordingDurationSeconds.value = elapsedSec.toInt()
                _recordingSampleCount.value = count

                kotlinx.coroutines.delay(250)
            }
        }
    }

    fun stopRecording(): RecordedCsvResult {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        val samplesCopy = synchronized(recordedSamples) { recordedSamples.toList() }
        _recordedSamplesFlow.value = samplesCopy
        val durationSec = if (samplesCopy.isNotEmpty()) (samplesCopy.last().timestampMs - samplesCopy.first().timestampMs) / 1000.0 else 0.0

        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val defaultFilename = "VERA_DataLog_$timeStr.csv"

        val csvString = generateCsvData(
            samples = samplesCopy,
            vehicleDetails = obdManager.vehicleDetails.value,
            vehicleVin = obdManager.vehicleVin.value,
            unitSystem = _unitSystem.value
        )

        val result = RecordedCsvResult(
            csvContent = csvString,
            defaultFilename = defaultFilename,
            sampleCount = samplesCopy.size,
            durationSeconds = durationSec
        )
        lastRecordedCsvResult = result
        autoSaveCurrentSession()
        return result
    }

    /**
     * Saves a snapshot of the rolling 5-minute flight data black-box buffer.
     * Invoked when user asks ("Vera, save that data") or when Sentinel triggers an anomaly.
     */
    fun saveFlightRecorderSnapshot(reason: String = "Driver requested snapshot"): FlightSnapshotResult {
        val samplesCopy = synchronized(blackBoxBuffer) { blackBoxBuffer.toList() }
        if (samplesCopy.isEmpty()) {
            return FlightSnapshotResult(success = false, sampleCount = 0, durationSeconds = 0.0, filename = "")
        }
        val durationSec = (samplesCopy.last().timestampMs - samplesCopy.first().timestampMs) / 1000.0
        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val filename = "VERA_BlackBox_$timeStr.csv"
        val csv = generateCsvData(
            samples = samplesCopy,
            vehicleDetails = obdManager.vehicleDetails.value,
            vehicleVin = obdManager.vehicleVin.value,
            unitSystem = _unitSystem.value
        )
        try {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(dir, filename)
            file.writeText(csv)
            Log.i("GeminiAgentViewModel", "Saved black-box flight snapshot to ${file.absolutePath} ($reason)")
        } catch (e: Exception) {
            Log.e("GeminiAgentViewModel", "Failed to write black-box snapshot to Downloads", e)
        }

        // Copy snapshot into recordedSamples so Replay / Graph immediately reflects this 5-minute capture
        synchronized(recordedSamples) {
            recordedSamples.clear()
            recordedSamples.addAll(samplesCopy)
        }
        _recordedSamplesFlow.value = samplesCopy

        viewModelScope.launch(Dispatchers.Main) {
            messages.add(
                ChatMessage(
                    sender = "VERA",
                    text = "Captured 5-minute black-box snapshot (${samplesCopy.size} data points over ${durationSec.toInt()}s) for: $reason.\nSaved to Downloads/$filename"
                )
            )
            autoSaveCurrentSession()
        }

        return FlightSnapshotResult(
            success = true,
            sampleCount = samplesCopy.size,
            durationSeconds = durationSec,
            filename = filename
        )
    }

    fun onCsvSavedSuccessfully(result: RecordedCsvResult) {
        val summary = "Recorded Live Data Log Saved (${result.sampleCount} samples, ${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\nCSV file generated and available for AI diagnostic analysis."
        messages.add(ChatMessage("System", summary))
    }

    fun onCsvAttachedForAnalysis(result: RecordedCsvResult) {
        val summary = "Recorded Live Data Log Attached (${result.sampleCount} samples, ${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\nFile: ${result.defaultFilename}\n\nAsk VERA any question about this data log!"
        messages.add(ChatMessage("System", summary))
    }

    fun onDiscardRecordedData() {
        lastRecordedCsvResult = null
        messages.add(ChatMessage("System", "Live Data Recording Discarded."))
    }

    private fun generateCsvData(
        samples: List<RecordedDataSample>,
        vehicleDetails: String?,
        vehicleVin: String?,
        unitSystem: UnitSystem
    ): String {
        val sb = StringBuilder()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

        val startTimeStr = if (samples.isNotEmpty()) dateFormat.format(java.util.Date(samples.first().timestampMs)) else "N/A"
        val durationSec = if (samples.isNotEmpty()) (samples.last().timestampMs - samples.first().timestampMs) / 1000.0 else 0.0

        sb.append("# VERA Vehicle Live Data Log\n")
        sb.append("# Vehicle: ${vehicleDetails ?: "Unknown Vehicle"}\n")
        sb.append("# VIN: ${vehicleVin ?: "N/A"}\n")
        sb.append("# Start Time: $startTimeStr\n")
        sb.append("# Total Samples: ${samples.size}\n")
        sb.append("# Duration Seconds: ${String.format(java.util.Locale.US, "%.1f", durationSec)}\n")
        sb.append("# Unit System: ${unitSystem.name}\n#\n")

        val isSae = unitSystem == UnitSystem.SAE
        val speedCol = if (isSae) "Speed_mph" else "Speed_kmh"
        val tempCol = if (isSae) "Coolant_Temp_F" else "Coolant_Temp_C"
        val iatCol = if (isSae) "Intake_Air_Temp_F" else "Intake_Air_Temp_C"
        val altCol = if (isSae) "Altitude_ft" else "Altitude_m"
        val gpsSpeedCol = if (isSae) "GPS_Speed_mph" else "GPS_Speed_kmh"

        val dynamicPidNames = samples.flatMap { s -> s.streamSnapshots.map { it.pid.name } }.distinct()
        val mode22PidNames = samples.flatMap { s -> s.mode22Snapshots.map { it.pid.name } }.distinct()

        val headers = mutableListOf(
            "Time_ISO",
            "Elapsed_Sec",
            "Latitude",
            "Longitude",
            altCol,
            "Road_Grade_pct",
            "Incline_Status",
            "Heading",
            gpsSpeedCol,
            "Street_Name",
            "Engine_RPM",
            speedCol,
            tempCol,
            iatCol,
            "Engine_Load_pct",
            "Throttle_Pos_pct",
            "Short_Fuel_Trim_pct",
            "MAF_gps"
        )

        for (name in dynamicPidNames) {
            val safe = name.replace(" ", "_").replace("/", "_")
            if (!headers.contains(safe)) {
                headers.add(safe)
            }
        }

        for (name in mode22PidNames) {
            val safe = "M22_${name.replace(" ", "_").replace("/", "_")}"
            if (!headers.contains(safe)) {
                headers.add(safe)
            }
        }

        sb.append(headers.joinToString(",")).append("\n")

        for (sample in samples) {
            val m = sample.metricsSnapshot
            val rc = sample.roadContextSnapshot
            val speedVal = if (isSae) m.speed * 0.621371 else m.speed.toDouble()
            val tempVal = if (isSae) (m.coolantTemp * 9.0 / 5.0) + 32.0 else m.coolantTemp.toDouble()
            val iatVal = if (isSae) (m.intakeAirTemp * 9.0 / 5.0) + 32.0 else m.intakeAirTemp.toDouble()

            val rowMap = mutableMapOf<String, String>()
            rowMap["Time_ISO"] = isoFormat.format(java.util.Date(sample.timestampMs))
            rowMap["Elapsed_Sec"] = String.format(java.util.Locale.US, "%.2f", sample.elapsedSeconds)

            // Geographic & Road Topography (Elevation, Grade %, Incline Status, Heading)
            if (rc != null && (rc.latitude != 0.0 || rc.altitudeFeet != 0.0 || (rc.streetName.isNotBlank() && rc.streetName != "Locating..."))) {
                rowMap["Latitude"] = if (rc.latitude != 0.0) String.format(java.util.Locale.US, "%.6f", rc.latitude) else ""
                rowMap["Longitude"] = if (rc.longitude != 0.0) String.format(java.util.Locale.US, "%.6f", rc.longitude) else ""
                val altVal = if (isSae) rc.altitudeFeet else rc.altitudeMeters
                rowMap[altCol] = String.format(java.util.Locale.US, "%.1f", altVal)
                rowMap["Road_Grade_pct"] = String.format(java.util.Locale.US, "%+.1f", rc.roadGradePercent)
                rowMap["Incline_Status"] = "\"${rc.inclineStatus.description}\""
                rowMap["Heading"] = rc.headingCompass
                val gpsSpd = if (isSae) rc.speedMph else rc.speedKmh
                rowMap[gpsSpeedCol] = String.format(java.util.Locale.US, "%.1f", gpsSpd)
                val streetDisplay = if (rc.neighborhoodOrCity.isNotBlank()) "${rc.streetName}, ${rc.neighborhoodOrCity}" else rc.streetName
                rowMap["Street_Name"] = "\"${streetDisplay.replace("\"", "\"\"")}\""
            } else {
                rowMap["Latitude"] = ""
                rowMap["Longitude"] = ""
                rowMap[altCol] = ""
                rowMap["Road_Grade_pct"] = ""
                rowMap["Incline_Status"] = ""
                rowMap["Heading"] = ""
                rowMap[gpsSpeedCol] = ""
                rowMap["Street_Name"] = ""
            }

            // Engine & Vehicle Telemetry
            rowMap["Engine_RPM"] = m.rpm.toString()
            rowMap[speedCol] = String.format(java.util.Locale.US, "%.1f", speedVal)
            rowMap[tempCol] = String.format(java.util.Locale.US, "%.1f", tempVal)
            rowMap[iatCol] = String.format(java.util.Locale.US, "%.1f", iatVal)
            rowMap["Engine_Load_pct"] = String.format(java.util.Locale.US, "%.1f", m.engineLoad.toDouble())
            rowMap["Throttle_Pos_pct"] = String.format(java.util.Locale.US, "%.1f", m.throttlePosition.toDouble())
            rowMap["Short_Fuel_Trim_pct"] = String.format(java.util.Locale.US, "%.1f", m.fuelTrim.toDouble())
            rowMap["MAF_gps"] = String.format(java.util.Locale.US, "%.2f", m.maf)

            for (stream in sample.streamSnapshots) {
                val safe = stream.pid.name.replace(" ", "_").replace("/", "_")
                val (formatted, _) = formatValueWithUnit(stream.rawValue, stream.pid, unitSystem)
                rowMap[safe] = formatted
            }

            for (m22 in sample.mode22Snapshots) {
                val safe = "M22_${m22.pid.name.replace(" ", "_").replace("/", "_")}"
                val (formatted, _) = formatMode22ValueWithUnit(m22.rawValue, m22.pid, unitSystem)
                rowMap[safe] = formatted
            }

            val row = headers.map { h -> rowMap[h] ?: "" }
            sb.append(row.joinToString(",")).append("\n")
        }

        val m06 = obdManager.mode06Results.value
        if (m06.isNotEmpty()) {
            sb.append("\n# Mode 06 Component Monitor Results:\n")
            for (item in m06) {
                sb.append("# [${item.category}] ${item.name}: ${item.testValue} ${item.unit} (Min: ${item.minLimit ?: "--"}, Max: ${item.maxLimit ?: "--"}) -> ${item.status}\n")
            }
        }

        return sb.toString()
    }

    init {
        viewModelScope.launch {
            var wasConnected = false
            obdManager.connectionState.collect { state ->
                val isNowConnected = state is BluetoothObdManager.ConnectionState.Connected
                if (wasConnected && !isNowConnected) {
                    // Auto-sync logs whenever the connection drops, vehicle shuts off, or adapter is unplugged
                    appContext?.let { ctx ->
                        VeraDiagnosticTelemetrySync.syncSessionLogAsync(
                            context = ctx,
                            vehicleDetails = vehicleDetails.value,
                            vehicleVin = vehicleVin.value,
                            triggerReason = if (state is BluetoothObdManager.ConnectionState.Error) "connection_error" else "connection_drop"
                        )
                    }
                }
                wasConnected = isNowConnected

                _connectionStatus.value = when (state) {
                    is BluetoothObdManager.ConnectionState.Disconnected -> {
                        resetVehicleDiagnostics()
                        "Disconnected"
                    }
                    is BluetoothObdManager.ConnectionState.Connecting -> "Connecting..."
                    is BluetoothObdManager.ConnectionState.Connected -> "Connected"
                    is BluetoothObdManager.ConnectionState.Error -> {
                        resetVehicleDiagnostics()
                        "Disconnected"
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastVehicleDetail: String? = null
            obdManager.vehicleDetails.collect { details ->
                if (!details.isNullOrBlank() && details != lastVehicleDetail) {
                    lastVehicleDetail = details
                    messages.add(ChatMessage("System", "Vehicle Connected: $details"))
                }
            }
        }

        viewModelScope.launch {
            obdManager.vehicleVin.collect { vin ->
                if (!vin.isNullOrBlank() && vin.length == 17) {
                    val info = fetchAndParseVehicleInfo(vin)
                    if (info != null) {
                        val make = info.make?.trim() ?: ""
                        val model = info.model?.trim() ?: ""
                        val year = info.modelYear?.trim() ?: ""
                        val detailsStr = listOf(make, model, year, "VIN: $vin").filter { it.isNotBlank() }.joinToString(" • ")
                        if (detailsStr.isNotBlank()) {
                            obdManager.updateVehicleDetails(detailsStr)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastLoadedRepo: String? = null
            obdManager.obdbLoadedResult.collect { result ->
                when (result) {
                    is ObdbRepository.LoadResult.Loaded -> {
                        val repoKey = "${result.repo}/${result.file}"
                        if (repoKey != lastLoadedRepo) {
                            lastLoadedRepo = repoKey
                            Log.i("GeminiAgentViewModel", "OBDb Community Parameter Signalset Loaded: ${result.repo}/${result.file} (${result.commands.size} custom commands ready for Scan Mode 22).")
                        }
                    }
                    is ObdbRepository.LoadResult.NoData -> {
                        Log.i("GeminiAgentViewModel", "No OBDb community parameter repository found for vehicle (tried: ${result.tried.joinToString(", ")}).")
                    }
                    is ObdbRepository.LoadResult.Unavailable -> {
                        Log.i("GeminiAgentViewModel", "OBDb Parameter Signalset Unavailable: ${result.reason}")
                    }
                    null -> {}
                }
            }
        }
    }

    private val systemPromptText = """
        You are VERA (Vehicle Evaluation & Repair Assistant), an elite automotive master diagnostic technician and veteran shade-tree mechanic riding shotgun with the driver.
        Your name VERA stands for "Vehicle Evaluation & Repair Assistant". Whenever asked what your name means, what VERA stands for, or who you are, always state clearly: "VERA stands for Vehicle Evaluation and Repair Assistant."
        You combine 30 years of grease-under-the-fingernails garage wisdom, dealership master technician knowledge (ASE Master Certified caliber), and razor-sharp diagnostic deduction.
        
        Persona & Master Knowledge Base:
        - Dealership-Level Diagnostic Mastery:
          * Deep understanding of engine thermodynamics, electrical schematics, PID sensors, fuel trim math (STFT + LTFT analysis at idle vs. 2500 RPM for vacuum vs. fuel delivery), ignition timing, CAN bus / UDS communication, catalytic converter oxygen storage efficiency, Mode 06 misfire counts, and ECU calibration logic.
          * Intimate knowledge of factory Technical Service Bulletins (TSBs), known vehicle pattern failures across makes/models (e.g., Ford 5.4 3V cam phasers & two-piece plugs, GM AFM/DOD lifters, Subaru EJ head gaskets, Honda VTEC spool valve screens, BMW OFHG oil-to-coolant leaks, Toyota oil consumption & charcoal canister vapor valves).
        - Veteran Shade-Tree Field Heuristics:
          * Never throw parts at a car blindly. Always prescribe zero-cost or cheap driveway triage before replacing sensors or modules:
            - Coil/Plug swap test (swap ignition coil or spark plug to another cylinder to confirm if the misfire follows the part or stays on the cylinder).
            - Driveway vacuum leak test (carb cleaner, unlit propane torch, or water spray around intake gaskets, PCV hoses, and vacuum lines).
            - Voltage drop testing across battery terminals, starter cables, and engine ground straps instead of condemning a starter or alternator.
            - Relay swap test (swap fuel pump relay with identical horn or AC relay) to diagnose crank-no-start.
            - Mechanic's stethoscope or long screwdriver to isolate mechanical valvetrain tick from pulley bearing whine.
            - Mallet / wood block tap on a stubborn starter solenoid or fuel pump tank to limp a stalled car home.
          * Practical wrenching warnings: Remind the user about brittle plastic coolant fittings, torque specs on aluminum heads, cleaning mating surfaces, using anti-seize or threadlocker appropriately, and proper cooling system burping procedures.

        Blue-Collar Garage Humor & Shop Personality:
        - Infuse a subtle, warm, blue-collar mechanic sense of humor (e.g., light garage banter, missing 10mm socket jokes, engineers designing parts that require engine removal, or practical shop wisdom).
        - Keep it natural, confident, and warm—helpful and encouraging whether helping a complete novice or a seasoned gearhead.

        Core Mechanics & Communication Rules:
        1. SPEAK LIKE A REAL HUMAN MECHANIC IN PLAIN ENGLISH: Speak naturally as an expert mechanic sitting right next to the driver. NEVER dump raw uninterpreted sensor tables, PID numbers, or voltage readings unless specifically asked for the raw data.
        2. CONCISE PLAIN ENGLISH VERDICTS: First provide a clear 1 to 3 sentence verdict and actionable next steps.
           - Healthy vehicle example: "Everything is purring smoothly—coolant, fuel trims, and battery charging voltage are all right in the sweet spot. No pending codes or misfires."
           - Issue/Misfire example: "I'm seeing misfires on Cylinder 3 under load. Before buying new parts, let's swap the coil with Cylinder 1 to see if the misfire follows the coil, or if we're dealing with a fouled plug or vacuum leak."
        3. MULTIMODAL VISUAL DIAGNOSTICS: When photos of spark plugs, belts, tires, fluid leaks, wiring connectors, or engine bays are attached, analyze them like an experienced inspector checking wear patterns, deposits, corrosion, and physical damage.
        4. DISCONNECTED STATE WITH ATTACHMENTS/LOGS: If disconnected from the vehicle BUT user provides attached photos, CSV data logs, files, or diagnostic descriptions, thoroughly analyze them and explain findings in plain, actionable English.
    """.trimIndent()

    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null

            val maxDim = 1024
            val scale = minOf(1.0f, maxOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height))
            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("GeminiApi", "Error converting image URI to Base64", e)
            null
        }
    }

    private fun generateOfflineDiagnosticAnalysis(): String {
        val dtcs = dtcDetails.value
        val m06 = obdManager.mode06Results.value
        val failedMonitors = m06.filter { it.status == Mode06Status.FAIL }

        return if (dtcs.isNotEmpty() || failedMonitors.isNotEmpty()) {
            val faultList = mutableListOf<String>()
            for (dtc in dtcs) {
                faultList.add("${dtc.code} (${dtc.description})")
            }
            for (m in failedMonitors) {
                faultList.add(m.name)
            }
            val faults = faultList.joinToString(", ")
            "I'm seeing an issue with $faults. Check your spark plugs and ignition coils first—try swapping coils between cylinders to see if the misfire follows the coil."
        } else {
            "Let me know if there's a specific problem you're experiencing, but from all the indicators I'm watching, everything looks great."
        }
    }

    private suspend fun callGeminiApi(prompt: String, imagesBase64: List<String> = emptyList()): String {
        return withContext(Dispatchers.IO) {
            val selected = _selectedChatModel.value
            val models = listOf(selected.id) + selected.fallbackIds
            var lastErrorMessage = ""

            for (modelName in models) {
                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$geminiApiKey"
                for (attempt in 1..2) {
                    try {
                        val url = URL(urlString)
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 10000
                            readTimeout = 15000
                            doOutput = true
                        }

                        val requestJson = JSONObject().apply {
                            if (systemPromptText.isNotBlank()) {
                                put("system_instruction", JSONObject().apply {
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", systemPromptText))
                                    })
                                })
                            }

                            if (selected == GeminiChatModel.THINKING) {
                                put("generationConfig", JSONObject().apply {
                                    put("thinkingConfig", JSONObject().apply {
                                        put("thinkingBudget", 2048)
                                    })
                                })
                            }

                            val contentsArray = JSONArray().apply {
                                val userContentObj = JSONObject().apply {
                                    put("role", "user")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", prompt))
                                        for (img in imagesBase64) {
                                            if (img.isNotBlank()) {
                                                put(
                                                    JSONObject().put(
                                                        "inline_data",
                                                        JSONObject()
                                                            .put("mime_type", "image/jpeg")
                                                            .put("data", img)
                                                    )
                                                )
                                            }
                                        }
                                    })
                                }
                                put(userContentObj)
                            }
                            put("contents", contentsArray)
                        }

                        connection.outputStream.use { os ->
                            val input = requestJson.toString().toByteArray(Charsets.UTF_8)
                            os.write(input, 0, input.size)
                        }

                        val responseCode = connection.responseCode
                        if (responseCode in 200..299) {
                            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonResponse = JSONObject(responseString)
                            val candidates = jsonResponse.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val candidate = candidates.getJSONObject(0)
                                val content = candidate.optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val textBuilder = StringBuilder()
                                    for (i in 0 until parts.length()) {
                                        val part = parts.getJSONObject(i)
                                        if (part.has("text")) {
                                            textBuilder.append(part.getString("text"))
                                        }
                                    }
                                    val aiText = textBuilder.toString().trim()
                                    if (aiText.isNotBlank()) {
                                        Log.i("GeminiApi", "Successfully received AI response from model $modelName")
                                        return@withContext aiText
                                    }
                                }
                            }
                            return@withContext "Received empty response from Gemini."
                        } else {
                            val errStream = connection.errorStream
                            val errString = errStream?.bufferedReader()?.use { it.readText() } ?: ""
                            val parsedMsg = try {
                                JSONObject(errString).optJSONObject("error")?.optString("message") ?: errString
                            } catch (_: Exception) {
                                errString
                            }
                            lastErrorMessage = "($responseCode): $parsedMsg"
                            Log.w("GeminiApi", "Model $modelName attempt $attempt failed -> $lastErrorMessage")

                            if (responseCode == 503 || responseCode == 429) {
                                kotlinx.coroutines.delay(800L * attempt)
                            }
                        }
                    } catch (e: Exception) {
                        lastErrorMessage = e.localizedMessage ?: e.toString()
                        Log.w("GeminiApi", "Model $modelName attempt $attempt exception", e)
                        kotlinx.coroutines.delay(800L * attempt)
                    }
                }
            }

            Log.w("GeminiApi", "Gemini API unavailable ($lastErrorMessage).")
            if (lastErrorMessage.isNotBlank()) {
                "⚠️ Gemini AI Service Error: $lastErrorMessage\n\n(Offline Mechanic Verdict): ${generateOfflineDiagnosticAnalysis()}"
            } else {
                generateOfflineDiagnosticAnalysis()
            }
        }
    }

    fun resetVehicleDiagnostics() {
        _troubleCodes.value = emptyList()
        _dtcDetails.value = emptyList()
        _hasScannedDtcs.value = false
        _isScanningDtcs.value = false
        _isScanningMode06.value = false
        _isScanningMode22.value = false
        _isScanningObdb.value = false
        sentinelManager.resetSession()
    }

    fun connectToCar(macOrUsbAddress: String, context: android.content.Context? = null) {
        viewModelScope.launch {
            resetVehicleDiagnostics()
            _isConnecting.value = true
            val isUsb = macOrUsbAddress.startsWith("USB:")
            val isWifi = macOrUsbAddress.startsWith("WIFI:", ignoreCase = true) ||
                         macOrUsbAddress.startsWith("TCP:", ignoreCase = true) ||
                         macOrUsbAddress.contains(":35000") ||
                         (macOrUsbAddress.count { it == '.' } == 3 && macOrUsbAddress.any { it.isDigit() })
            val connTypeLabel = when {
                isUsb -> "Wired USB OBD2 adapter (vLinker FS)"
                isWifi -> "Wi-Fi OBD2 adapter (${macOrUsbAddress.removePrefix("WIFI:").removePrefix("wifi:")})"
                else -> "OBD2 adapter"
            }
            messages.add(ChatMessage("System", "Connecting to $connTypeLabel..."))
            val result = obdManager.connectToDevice(macOrUsbAddress, context)
            if (result.isSuccess) {
                messages.add(ChatMessage("System", "Successfully connected to $connTypeLabel."))
                if (macOrUsbAddress == "DEMO_SIMULATOR_CLEAN") {
                    locationManager?.setDemoMode(true, dirtyDirty = false)
                } else if (macOrUsbAddress == "DEMO_SIMULATOR_DIRTY") {
                    locationManager?.setDemoMode(true, dirtyDirty = true)
                } else {
                    locationManager?.setDemoMode(false)
                    locationManager?.startTracking()
                    locationManager?.forceRefreshLocation()
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Connection failed"
                messages.add(ChatMessage("System", "Failed to connect: $errorMsg"))
            }
            _isConnecting.value = false
        }
    }

    fun disconnectFromCar() {
        viewModelScope.launch {
            val vDetails = vehicleDetails.value
            val vVin = vehicleVin.value
            resetVehicleDiagnostics()
            locationManager?.setDemoMode(false)
            locationManager?.forceRefreshLocation()
            obdManager.disconnect()
            messages.add(ChatMessage("System", "Disconnected from OBD2 adapter."))

            // Silently upload session diagnostic log to cloud bucket for developer troubleshooting
            appContext?.let { ctx ->
                VeraDiagnosticTelemetrySync.syncSessionLogAsync(
                    context = ctx,
                    vehicleDetails = vDetails,
                    vehicleVin = vVin,
                    triggerReason = "manual_disconnect"
                )
            }
        }
    }

    fun scanTroubleCodes() {
        viewModelScope.launch {
            if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected) {
                messages.add(ChatMessage("System", "Cannot scan trouble codes: OBD2 adapter is not connected."))
                return@launch
            }
            _isScanningDtcs.value = true
            messages.add(ChatMessage("System", "Scanning Stored, Pending, and Permanent Fault Codes..."))
            val scannedList = obdManager.scanAllDtcModes()
            _dtcDetails.value = scannedList
            _troubleCodes.value = scannedList.map { "${it.code} [${it.category}]: ${it.description}" }
            _hasScannedDtcs.value = true

            val dtcSummary = if (scannedList.isEmpty()) {
                "No trouble codes found across Stored, Pending, or Permanent modes. System clean."
            } else {
                scannedList.joinToString("\n") { "• ${it.code} [${it.category}]: ${it.description}" }
            }

            messages.add(ChatMessage("System", "DTC Scan Complete:\n$dtcSummary"))
            _isScanningDtcs.value = false
        }
    }

    fun scanMode06Monitors() {
        viewModelScope.launch {
            if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected) {
                return@launch
            }
            _isScanningMode06.value = true
            try {
                obdManager.scanAllMode06Monitors()
                if (liveStreamManager?.isLiveSessionActive?.value == true) {
                    liveStreamManager?.injectTelemetryContextUpdate(buildTelemetrySnapshotForLive())
                }
            } catch (e: Exception) {
                Log.e("GeminiAgentViewModel", "Error scanning Mode 06 monitors", e)
            } finally {
                _isScanningMode06.value = false
            }
        }
    }

    fun scanMode22Parameters() {
        viewModelScope.launch {
            try {
                if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected && !obdManager.isDemoMode) {
                    return@launch
                }
                _isScanningMode22.value = true

                // Scan Mode 22 PIDs and OBDb Custom PIDs
                obdManager.scanMode22Pids()
                obdManager.readObdbSignals()

                if (liveStreamManager?.isLiveSessionActive?.value == true) {
                    liveStreamManager?.injectTelemetryContextUpdate(buildTelemetrySnapshotForLive())
                }
            } catch (e: Exception) {
                Log.e("GeminiAgentViewModel", "Error scanning Mode 22 parameters", e)
            } finally {
                _isScanningMode22.value = false
            }
        }
    }

    fun refreshLiveData() {
        // Live data is continuously updated via BluetoothObdManager metrics state flow
    }

    fun sendMessageToAgent(
        userText: String,
        imageBase64: String? = null,
        attachments: List<ChatAttachment> = emptyList()
    ) {
        val allAttachments = attachments.toMutableList()
        if (!imageBase64.isNullOrBlank() && allAttachments.none { it.base64Image == imageBase64 }) {
            allAttachments.add(
                ChatAttachment(
                    fileName = "Photo.jpg",
                    isImage = true,
                    base64Image = imageBase64
                )
            )
        }

        val attachmentCount = allAttachments.size
        val attachmentLabel = if (attachmentCount > 0) {
            val names = allAttachments.joinToString(", ") { it.fileName }
            "\n[Attached ($attachmentCount): $names]"
        } else ""

        val displayMsg = (userText + attachmentLabel).trim()
        messages.add(ChatMessage("User", displayMsg))

        if (allAttachments.isEmpty() && checkAndExecuteLocalMonitoringIntent(userText)) {
            // Local monitoring intent satisfied immediately on device with zero latency
            return
        }

        viewModelScope.launch {
            try {
                if (VeraKillSwitch.isDeactivated.value) {
                    val msg = VeraKillSwitch.deactivationMessage.value ?: "This test build of VERA has expired or been disabled."
                    messages.add(ChatMessage("Error", msg))
                    return@launch
                }
                if (geminiApiKey.isBlank()) {
                    messages.add(ChatMessage("Error", "GEMINI_API_KEY is not set in local.properties."))
                    return@launch
                }

                val isConnected = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected
                val lowerQuery = userText.lowercase()
                val wantsRawTrace = lowerQuery.contains("trace") || lowerQuery.contains("elm") || lowerQuery.contains("dongle") || lowerQuery.contains("raw log") || lowerQuery.contains("hex") || lowerQuery.contains("serial")

                // Build attached file text blocks with smart sampling for large CSV logs
                val fileTexts = allAttachments.mapNotNull { att ->
                    val rawText = att.textContent
                    if (!att.isImage && !rawText.isNullOrBlank()) {
                        val cleanContent = if (rawText.length > 6000) {
                            val lines = rawText.lines()
                            if (lines.size > 50) {
                                val header = lines.take(12).joinToString("\n")
                                val midIdx = lines.size / 2
                                val middle = lines.slice(midIdx until minOf(midIdx + 15, lines.size)).joinToString("\n")
                                val tail = lines.takeLast(15).joinToString("\n")
                                "$header\n\n... [${lines.size - 42} intermediate data sample rows omitted for prompt efficiency] ...\n\n$middle\n\n... [intermediate sample rows omitted] ...\n\n$tail"
                            } else rawText.take(6000)
                        } else rawText

                        "[Attached File: ${att.fileName}]\n$cleanContent"
                    } else null
                }.joinToString("\n\n")

                val fileAttachmentContext = if (fileTexts.isNotBlank()) "\n\n[USER ATTACHED FILES / LOGS]:\n$fileTexts" else ""
                val imagesBase64 = allAttachments.mapNotNull { if (it.isImage) it.base64Image else null }

                val elmTraceSection = if (wantsRawTrace) {
                    "\n\n[RAW ELM327 BLUETOOTH DONGLE COMMAND & RESPONSE TRACE LOG]\n${getRawElmTraceText()}"
                } else ""

                val contextualPrompt = if (isConnected) {
                    val streams = obdManager.supportedStreams.value
                    val vin = obdManager.vehicleVin.value ?: "Not Verified"
                    val dtcs = obdManager.getTroubleCodes()
                    val m06Data = obdManager.mode06Results.value
                    val m22Data = obdManager.mode22Streams.value

                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    val streamsSummary = if (streams.isNotEmpty()) {
                        streams.joinToString("\n") { s ->
                            val (fmtVal, fmtUnit) = formatValueWithUnit(s.rawValue, s.pid, currentUnit)
                            "- ${s.pid.name} (${s.pid.description}): $fmtVal $fmtUnit"
                        }
                    } else {
                        val metrics = obdManager.metrics.value
                        val spd = if (isMetric) "${metrics.speed} km/h" else "${(metrics.speed * 0.621371).toInt()} mph"
                        val temp = if (isMetric) "${metrics.coolantTemp}°C" else "${(metrics.coolantTemp * 1.8 + 32).toInt()}°F"
                        "- Engine RPM: ${metrics.rpm} RPM\n- Coolant Temp: $temp\n- Vehicle Speed: $spd"
                    }

                    val m06Summary = if (m06Data.isNotEmpty()) {
                        m06Data.joinToString("\n") { "  • [${it.category}] ${it.name}: ${it.testValue.toInt()} ${it.unit} (${it.status})" }
                    } else {
                        "  • Mode 06 On-Board Monitors: Unscanned / Not queried yet"
                    }

                    val allCustomItems = buildUnifiedCustomPidList(
                        mode22Streams = m22Data,
                        obdbLoadedResult = obdManager.obdbLoadedResult.value,
                        obdbSignalResults = obdManager.obdbSignalResults.value,
                        unitSystem = currentUnit,
                        vehicleDetails = obdManager.vehicleDetails.value,
                        isMoving = obdManager.metrics.value.speed > 2
                    )
                    val m22Summary = if (allCustomItems.isNotEmpty()) {
                        allCustomItems.joinToString("\n") { item ->
                            "  • [${item.category.displayName}] ${item.name}: ${item.formattedValue} ${item.unit}".trimEnd()
                        }
                    } else {
                        "  • Vehicle Custom PIDs (Mode 21/22 & OBDb): Unscanned / Not queried yet"
                    }

                    val opendbcData = opendbcSignals.value
                    val opendbcSummary = if (opendbcData.isNotEmpty()) {
                        val prof = activeOpendbcProfile.value?.platformName ?: "Vehicle CAN"
                        "  • opendbc Dynamic Chassis & Dynamics ($prof):\n" + opendbcData.values.joinToString("\n") { "    - ${it.name}: ${it.formattedValue}" }
                    } else if (isHighSpeedCanCapable.value) {
                        "  • opendbc: High-Speed CAN Ready (${adapterHardwareName.value})"
                    } else {
                        "  • opendbc: Standard OBD2 adapter (${adapterHardwareName.value})"
                    }

                    val currentMetrics = obdManager.metrics.value
                    val obdSpd = (currentMetrics.speed * 0.621371).toInt()
                    val isMoving = roadContext.value.isMoving || obdSpd >= 3
                    val motionDesc = if (isMoving) "Driving ($obdSpd mph)" else "Stationary / Parked (0 mph, sitting in driveway or idling)"

                    """
                        [Vehicle Telemetry Context - FOR INTERNAL DIAGNOSTIC REASONING ONLY]
                        VIN: $vin
                        Motion State: $motionDesc
                        Active DTCs: ${if (dtcs.isEmpty()) "None" else dtcs.joinToString(", ")}
                        Road Topography & Location: ${roadContext.value.formattedTopography}
                        Telemetry Snapshot: $streamsSummary
                        Mode 06 Monitors: $m06Summary
                        Mode 22 PIDs: $m22Summary
                        High-Speed CAN & opendbc Dynamics:
                        $opendbcSummary
                        $elmTraceSection
                        $fileAttachmentContext

                        CRITICAL INSTRUCTION: Speak naturally in plain English like a real human mechanic riding shotgun. Do NOT recite raw sensor numbers, PIDs, temperatures, voltages, or test IDs out loud unless specifically requested.
                        CRITICAL SPEED & TOPOGRAPHY AWARENESS: Always check 'Motion State' before discussing hills, inclines, or driving conditions. If the vehicle is stationary (0 mph), the user is parked or sitting in their driveway. Do NOT assume or say they are actively driving, climbing, or descending a hill.
                        CORE MISSION & ANTI-SYCOPHANCY: Your highest objective is arriving at the unvarnished diagnostic truth and protecting the mechanical health of the vehicle. Do NOT be a "yes-man" or give reflexive affirmative answers. If the driver's hypothesis is contradicted by sensor data, or if an action is outside your capabilities, state the truth plainly and objectively.
                        BLUE-COLLAR MECHANIC PERSONA: Speak naturally in plain English like a seasoned, sharp blue-collar mechanic riding shotgun—warm, engaging, and occasionally witty, but relentlessly focused on finding the root cause without throwing expensive parts at a problem.
                        Healthy verdict example: "Let me know if there's a specific problem you're experiencing, but from all the indicators I'm watching, everything looks great."
                        Issue verdict example: "I'm seeing some misfires on cylinder 3—maybe dirty spark plugs, fuel injectors, or a dirty throttle body. Try turning on the AC and see if it stops under load."
                        STRICT BOUNDARY RULE: You have read-only diagnostic access to vehicle engine and OBD2 data. If the user asks you to control physical vehicle actuators (radio, doors, AC, windows, clearing adaptation tables, flashing ECU), refuse honestly: state that you are a read-only diagnostic assistant and cannot actuate vehicle hardware.

                        Driver Question: $userText
                    """.trimIndent()
                } else {
                    """
                        [Vehicle OBD2 Status: DISCONNECTED]
                        [Live Telemetry: UNAVAILABLE - No connection to vehicle ECU]
                        $fileAttachmentContext

                        CRITICAL INSTRUCTION: You are currently DISCONNECTED from the vehicle's OBD2 port. You have NO access to live vehicle telemetry, engine RPM, sensors, or trouble codes.
                        - CORE MISSION & ANTI-SYCOPHANCY: Your highest objective is the diagnostic truth. Do not agree to actions you cannot perform or assume sensor values.
                        - If the driver asks about their current vehicle data, engine status, trouble codes, or vehicle health, YOU MUST EXPLICITLY TELL THEM that you are not connected to the vehicle's OBD2 adapter yet, and instruct them to connect an adapter or start Demo Mode in the app so you can read their live data.
                        - DO NOT claim, hallucinate, or assume that "everything looks fine", "all indicators look great", or that sensors are normal when disconnected.
                        - You CAN answer general automotive, repair, or mechanic questions, but clarify that you cannot see their specific vehicle's real-time data until connected.

                        Driver Question: $userText
                    """.trimIndent()
                }

                _isSendingMessage.value = true
                val aiResponseText = callGeminiApi(contextualPrompt, imagesBase64)
                messages.add(ChatMessage("VERA", aiResponseText))
                _isSendingMessage.value = false
                if (isVoiceModeActive) {
                    speak(aiResponseText)
                }
                autoSaveCurrentSession()

            } catch (e: Throwable) {
                messages.add(ChatMessage("Error", "Gemini Request Error: ${e.message ?: e.toString()}"))
                _isSendingMessage.value = false
            }
        }
    }

    init {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    sender = "VERA",
                    text = "Hello! I am VERA, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
                )
            )
        }

        // 1. Hook Sentinel Telemetry Evaluator to live metrics stream (all StateFlows guaranteed initialized)
        viewModelScope.launch {
            obdManager.metrics.collect { metrics ->
                val isConn = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
                sentinelManager.evaluateTelemetry(
                    metrics = metrics,
                    streams = obdManager.supportedStreams.value,
                    dtcDetails = _dtcDetails.value,
                    mode06Results = obdManager.mode06Results.value,
                    mode22Streams = obdManager.mode22Streams.value,
                    unitSystem = unitSystem.value,
                    isConnected = isConn,
                    roadContext = locationManager?.roadContext?.value
                )
            }
        }

        // 2. Push real-time connection & telemetry context into active Gemini Live voice session
        // whenever the vehicle finishes connecting or starts streaming after voice mode was already started.
        viewModelScope.launch {
            var lastPushedStateWasConnected = false
            var lastPushedStreaming = false
            var lastPushedVin: String? = null
            var lastCanPushMs = 0L
            kotlinx.coroutines.flow.combine(
                obdManager.connectionState,
                obdManager.isStreamingLiveData,
                obdManager.vehicleVin,
                obdManager.opendbcSignals
            ) { conn, streaming, vin, canSigs ->
                val isConn = conn is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
                Pair(Triple(isConn, streaming, vin), canSigs.size)
            }.collect { (stateTriple, canSigCount) ->
                val (isConn, streaming, vin) = stateTriple
                if (liveStreamManager?.isLiveSessionActive?.value == true) {
                    val now = System.currentTimeMillis()
                    val stateTransitioned = (isConn && !lastPushedStateWasConnected) ||
                            (streaming && !lastPushedStreaming) ||
                            (vin != null && vin != lastPushedVin) ||
                            (canSigCount > 0 && lastCanPushMs == 0L)
                    val periodicRefreshDue = isConn && (now - lastCanPushMs >= 15_000L)
                    if (stateTransitioned || periodicRefreshDue) {
                        delay(250) // allow initial PIDs/VIN to settle
                        val snapshot = buildTelemetrySnapshotForLive()
                        if (liveStreamManager?.injectTelemetryContextUpdate(snapshot) == true) {
                            lastPushedStateWasConnected = isConn
                            lastPushedStreaming = streaming
                            lastPushedVin = vin
                            lastCanPushMs = now
                        }
                    }
                } else {
                    lastPushedStateWasConnected = false
                    lastPushedStreaming = false
                    lastPushedVin = null
                    lastCanPushMs = 0L
                }
            }
        }

        // 3. Continuous Background Flight Data Logging while connected (5-minute rolling black-box buffer)
        // Maintains a lightweight 300-sample in-memory circular window (~100 KB) with zero disk I/O.
        viewModelScope.launch {
            val sessionStartTimeMs = System.currentTimeMillis()
            while (isActive) {
                delay(1000L)
                val isConn = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
                if (isConn && obdManager.isStreamingLiveData.value) {
                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - sessionStartTimeMs) / 1000.0
                    val metricsSnap = obdManager.metrics.value
                    val streamsSnap = obdManager.supportedStreams.value.map { it.copy(history = emptyList()) }
                    val mode22Snap = obdManager.mode22Streams.value.toList()
                    val roadSnap = locationManager?.roadContext?.value ?: RoadContext()

                    val sample = RecordedDataSample(
                        timestampMs = now,
                        elapsedSeconds = elapsedSec,
                        metricsSnapshot = metricsSnap,
                        streamSnapshots = streamsSnap,
                        mode22Snapshots = mode22Snap,
                        roadContextSnapshot = roadSnap
                    )
                    synchronized(blackBoxBuffer) {
                        if (blackBoxBuffer.size >= 300) {
                            blackBoxBuffer.removeFirst()
                        }
                        blackBoxBuffer.addLast(sample)
                    }
                }
            }
        }
    }

    companion object {
        const val FOLLOW_UP_WINDOW_SECONDS = 30

        /**
         * Cleans up streaming speech recognition and LLM text artifacts:
         * 1. Eliminates spaces erroneously inserted across subword token boundaries (e.g. "fu el" -> "fuel").
         * 2. Collapses redundant consecutive whitespace.
         * 3. Fixes spaces separated from standard punctuation (e.g. "fuel trims ?" -> "fuel trims?").
         * 4. Heals bound morphemes / suffixes (e.g. "look ing" -> "looking", "igni tion" -> "ignition").
         */
        fun cleanTranscriptText(text: String): String {
            if (text.isBlank()) return text
            var result = text

            // 1. Normalize multiple consecutive spaces
            result = result.replace(Regex(" {2,}"), " ")

            // 2. Remove space before standard punctuation marks
            result = result.replace(Regex("\\s+([.,!?;:])"), "$1")

            // 3. Heal common speech-to-text tokenization splits (automotive & conversational terms)
            val commonSplits = listOf(
                "fu\\s+el" to "fuel",
                "fu\\s+els" to "fuels",
                "cool\\s+ant" to "coolant",
                "cool\\s+ants" to "coolants",
                "trans\\s+mission" to "transmission",
                "trans\\s+missions" to "transmissions",
                "al\\s+ternator" to "alternator",
                "al\\s+ternators" to "alternators",
                "rad\\s+iator" to "radiator",
                "rad\\s+iators" to "radiators",
                "odo\\s+meter" to "odometer",
                "odo\\s+meters" to "odometers",
                "cata\\s+lytic" to "catalytic",
                "cyl\\s+inder" to "cylinder",
                "cyl\\s+inders" to "cylinders",
                "mani\\s+fold" to "manifold",
                "mani\\s+folds" to "manifolds",
                "igni\\s+tion" to "ignition",
                "igni\\s+tions" to "ignitions",
                "pres\\s+sure" to "pressure",
                "pres\\s+sures" to "pressures",
                "sen\\s+sor" to "sensor",
                "sen\\s+sors" to "sensors",
                "steer\\s+ing" to "steering",
                "throt\\s+tle" to "throttle",
                "throt\\s+tles" to "throttles",
                "bat\\s+tery" to "battery",
                "bat\\s+teries" to "batteries",
                "dia\\s+gnostic" to "diagnostic",
                "dia\\s+gnostics" to "diagnostics",
                "ve\\s+hicle" to "vehicle",
                "ve\\s+hicles" to "vehicles",
                "muf\\s+fler" to "muffler",
                "muf\\s+flers" to "mufflers",
                "ex\\s+haust" to "exhaust",
                "mile\\s+age" to "mileage",
                "volt\\s+age" to "voltage",
                "am\\s+perage" to "amperage",
                "al\\s+ign" to "align",
                "al\\s+igns" to "aligns",
                "al\\s+ignment" to "alignment",
                "dis\\s+connect" to "disconnect",
                "dis\\s+connects" to "disconnects",
                "dis\\s+connected" to "disconnected",
                "dis\\s+connecting" to "disconnecting",
                "moni\\s+tor" to "monitor",
                "moni\\s+tors" to "monitors",
                "moni\\s+toring" to "monitoring",
                "ana\\s+lyze" to "analyze",
                "ana\\s+lyzing" to "analyzing",
                "ana\\s+lysis" to "analysis",
                "look\\s+ing" to "looking",
                "check\\s+ing" to "checking",
                "run\\s+ning" to "running",
                "driv\\s+ing" to "driving",
                "work\\s+ing" to "working",
                "start\\s+ing" to "starting",
                "read\\s+ing" to "reading",
                "read\\s+ings" to "readings",
                "com\\s+puter" to "computer",
                "spe\\s+cific" to "specific",
                "pa\\s+rameter" to "parameter",
                "pa\\s+rameters" to "parameters"
            )

            for ((pattern, replacement) in commonSplits) {
                val regex = Regex("(?i)\\b$pattern\\b")
                result = regex.replace(result) { match ->
                    val matched = match.value
                    when {
                        matched.all { it.isUpperCase() } -> replacement.uppercase()
                        matched.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.uppercase() }
                        else -> replacement.lowercase()
                    }
                }
            }

            // 4. Heal generic suffix splits where bound morphemes were disconnected:
            // e.g. "look ing", "check ing", "igni tion", "move ment"
            val suffixRegex = Regex("(?i)\\b([a-zA-Z]{2,})\\s+(ing|tion|tions|ment|ments|ness)\\b")
            result = suffixRegex.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}"
            }

            return result
        }
    }
}
