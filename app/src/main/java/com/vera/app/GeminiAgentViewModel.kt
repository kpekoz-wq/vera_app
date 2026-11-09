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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val mode22Snapshots: List<Mode22StreamData>
)

data class RecordedCsvResult(
    val csvContent: String,
    val defaultFilename: String,
    val sampleCount: Int,
    val durationSeconds: Double
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

    val messages = mutableStateListOf<ChatMessage>()

    init {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    sender = "VERA",
                    text = "Hello! I am VERA, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
                )
            )
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

    private val _isScanningObdb = MutableStateFlow(false)
    val isScanningObdb: StateFlow<Boolean> = _isScanningObdb.asStateFlow()

    fun initObdbRepository(context: android.content.Context) {
        obdManager.initObdbRepository(context)
    }

    fun scanObdbSignals() {
        viewModelScope.launch {
            if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected) {
                messages.add(ChatMessage("System", "Cannot scan OBDb parameters: OBD2 adapter is not connected."))
                return@launch
            }
            _isScanningObdb.value = true
            messages.add(ChatMessage("System", "Scanning vehicle-specific OBDb community parameters..."))
            val results = obdManager.readObdbSignals()
            val count = results.size
            messages.add(ChatMessage("System", "OBDb Parameter Scan Complete: $count signals decoded."))
            _isScanningObdb.value = false
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
                "🧠 Diagnostic Model Changed: ${model.displayName}\n${model.subtitle}"
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

    fun speak(text: String) {
        if (!_isTtsEnabled.value) return
        stopSpeaking()

        val cleanText = text
            .replace(Regex("[#*`•_~]"), "")
            .replace(Regex("\\[.*?]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isBlank()) return

        val activeVoice = liveStreamManager?.voiceName?.value ?: "Aoede"

        viewModelScope.launch(Dispatchers.IO) {
            var audioPlaySuccess = false
            val cacheDir = appContext?.cacheDir

            if (geminiApiKey.isNotBlank() && cacheDir != null) {
                // Use Gemini Native Voice models to output smooth human voice (Aoede) matching live voice chat
                val ttsModels = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-2.5-flash-preview-tts")
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
                                                }
                                                player.setOnErrorListener { _, _, _ ->
                                                    _isSpeaking.value = false
                                                    tempFile.delete()
                                                    false
                                                }
                                                player.start()
                                            } catch (e: Exception) {
                                                _isSpeaking.value = false
                                                tempFile.delete()
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
                    speakWithAndroidTts(cleanText)
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

    private fun speakWithAndroidTts(cleanText: String) {
        if (tts == null) return
        _isSpeaking.value = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) { _isSpeaking.value = false }
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

    private var liveStreamManager: GeminiLiveStreamManager? = null
    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _isGeminiSpeaking = MutableStateFlow(false)
    val isGeminiSpeaking: StateFlow<Boolean> = _isGeminiSpeaking.asStateFlow()

    private val _isHandsFreeVoiceActive = MutableStateFlow(false)
    val isHandsFreeVoiceActive: StateFlow<Boolean> = _isHandsFreeVoiceActive.asStateFlow()

    private var sessionManager: ChatSessionManager? = null
    private val _currentSessionId = MutableStateFlow(java.util.UUID.randomUUID().toString())
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _savedSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val savedSessions: StateFlow<List<ChatSession>> = _savedSessions.asStateFlow()

    fun initSessionManager(context: Context) {
        if (sessionManager == null) {
            val sm = ChatSessionManager(context.applicationContext)
            sessionManager = sm
            _savedSessions.value = sm.getAllSessions()
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

        val session = ChatSession(
            id = _currentSessionId.value,
            title = title,
            timestamp = System.currentTimeMillis(),
            vehicleDetails = obdManager.vehicleDetails.value,
            vehicleVin = obdManager.vehicleVin.value,
            odometerFormatted = odoStr,
            dtcs = dtcList,
            messages = messages.toList()
        )
        sm.saveSession(session)
        _savedSessions.value = sm.getAllSessions()
    }

    fun createNewChatSession() {
        autoSaveCurrentSession()
        _currentSessionId.value = java.util.UUID.randomUUID().toString()
        messages.clear()
        messages.add(
            ChatMessage(
                sender = "VERA",
                text = "Hello! I am VERA, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
            )
        )
    }

    fun loadChatSession(sessionId: String) {
        autoSaveCurrentSession()
        val sm = sessionManager ?: return
        val session = sm.loadSession(sessionId) ?: return
        _currentSessionId.value = session.id
        messages.clear()
        if (session.messages.isNotEmpty()) {
            messages.addAll(session.messages)
        } else {
            messages.add(
                ChatMessage(
                    sender = "VERA",
                    text = "Loaded diagnostic session: ${session.title}"
                )
            )
        }
    }

    fun deleteChatSession(sessionId: String) {
        val sm = sessionManager ?: return
        sm.deleteSession(sessionId)
        _savedSessions.value = sm.getAllSessions()
        if (_currentSessionId.value == sessionId) {
            createNewChatSession()
        }
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
        if (liveStreamManager != null) return
        val mgr = GeminiLiveStreamManager(context.applicationContext)
        liveStreamManager = mgr
        viewModelScope.launch {
            mgr.isLiveSessionActive.collect { active ->
                _isLiveSessionActive.value = active
                _isHandsFreeVoiceActive.value = active
            }
        }
        viewModelScope.launch {
            mgr.isGeminiSpeaking.collect { speaking ->
                _isGeminiSpeaking.value = speaking
            }
        }
    }

    fun toggleHandsFreeVoiceMode() {
        if (_isLiveSessionActive.value) {
            stopGeminiLiveSession()
        } else {
            startGeminiLiveSession()
        }
    }

    fun startGeminiLiveSession() {
        if (geminiApiKey.isBlank()) {
            messages.add(ChatMessage("Error", "GEMINI_API_KEY is not set in local.properties."))
            return
        }
        val mgr = liveStreamManager
        if (mgr == null) {
            messages.add(ChatMessage("Error", "Gemini Live stream manager is not initialized yet."))
            return
        }

        stopSpeaking()
        _isHandsFreeVoiceActive.value = true

        appContext?.let {
            VeraVoiceService.startService(it)
        }

        val summary = buildTelemetrySnapshotForLive()
        val historyContext = buildRecentConversationContextForLive(maxMessages = 20)
        isNewLiveTurn = true
        isNewUserTurn = true

        messages.add(
            ChatMessage(
                "System",
                "Hands-free conversation is live.  Ask anything! Vera has full, live, read-only access to your vehicle's data.  You can chat while you drive. Vera will keep your phone awake."
            )
        )

        mgr.startLiveSession(
            apiKey = geminiApiKey,
            systemInstruction = buildLiveSystemInstruction(),
            telemetrySummary = summary,
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
                    onUserTranscriptReceived(text)
                }
            },
            onTurnComplete = {
                viewModelScope.launch(Dispatchers.Main) {
                    isNewLiveTurn = true
                    isNewUserTurn = true
                    autoSaveCurrentSession()
                }
            },
            onToolCall = { toolName, callId, args ->
                executeLiveTool(toolName, callId, args)
            },
            onError = { errMsg ->
                viewModelScope.launch(Dispatchers.Main) {
                    messages.add(ChatMessage("Error", "Gemini Live Error: $errMsg"))
                    _isHandsFreeVoiceActive.value = false
                    appContext?.let {
                        VeraVoiceService.stopService(it)
                    }
                }
            }
        )
    }

    fun stopGeminiLiveSession() {
        _isHandsFreeVoiceActive.value = false
        isNewLiveTurn = true
        isNewUserTurn = true
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
        liveStreamManager?.interruptPlayback()
    }

    private var isNewUserTurn = true
    private var isNewLiveTurn = true

    fun onUserTranscriptReceived(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val lastMsg = messages.lastOrNull()
            if (!isNewUserTurn && lastMsg != null && lastMsg.sender == "User") {
                val updated = if (lastMsg.text.endsWith(" ") || text.startsWith(" ")) {
                    lastMsg.text + text
                } else {
                    lastMsg.text + " " + text
                }
                val idx = messages.size - 1
                messages[idx] = ChatMessage("User", updated.trim())
            } else {
                messages.add(ChatMessage("User", text.trim()))
                isNewUserTurn = false
            }
        }
    }

    private fun appendOrUpdateLiveTranscript(text: String) {
        isNewUserTurn = true

        if (isNewLiveTurn) {
            messages.add(ChatMessage("VERA (Live)", text.trim()))
            isNewLiveTurn = false
        } else {
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null && (lastMsg.sender == "VERA (Live)" || lastMsg.sender == "OttoAI (Live)")) {
                val updated = if (lastMsg.text.endsWith(" ") || text.startsWith(" ") || text.startsWith(",") || text.startsWith(".") || text.startsWith("!") || text.startsWith("?")) {
                    lastMsg.text + text
                } else {
                    lastMsg.text + " " + text
                }
                val idx = messages.size - 1
                messages[idx] = ChatMessage("VERA (Live)", updated)
            } else {
                messages.add(ChatMessage("VERA (Live)", text.trim()))
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
            You are VERA, an elite automotive master diagnostic technician and veteran shade-tree mechanic riding shotgun with the driver.
            You combine 30 years of hands-on garage wisdom, dealership master technician certification, and brilliant diagnostic deduction.

            You are interacting with the driver in REAL-TIME 2-WAY HANDS-FREE VOICE MODE over bidirectional audio while they are operating their vehicle.

            $unitRules

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

            CRITICAL DRIVING & VOICE RULES:
            1. NATURAL HUMAN MECHANIC VOICE & CONVERSATION:
               - Speak naturally, warmly, and conversationally, like a seasoned, sharp mechanic sitting in the passenger seat.
               - Keep spoken answers concise (1 to 3 sentences) so the driver stays focused on the road.
               - NEVER recite raw PIDs, sensor codes, raw voltages, or test IDs out loud unless the driver explicitly asks for exact numbers.
               - Give clear plain-English verdicts:
                 * Healthy vehicle: "Everything is purring smoothly—coolant, fuel trims, and battery voltage are right in the sweet spot."
                 * Issue detected: "I'm catching a misfire on cylinder three under load. Before buying new parts, once we're parked we'll swap the coil with cylinder one to see if the misfire follows it."
            2. MANDATORY REAL-TIME TELEMETRY RETRIEVAL:
               - Vehicle telemetry (RPM, coolant temperature, speed, fuel trims, error codes, odometer mileage) changes dynamically while driving.
               - Whenever the driver asks about their engine, mileage, odometer, temperatures, speed, RPM, battery, check engine light, trouble codes, or vehicle health, YOU MUST FIRST EXECUTE THE CORRESPONDING TOOL (`get_vehicle_info`, `get_live_data`, `get_dtcs`, `get_mode06_results`, `get_obdb_signals`) BEFORE GIVING YOUR ANSWER.
               - ALWAYS obey the active unit system ($unitRules) when speaking numbers out loud!
               - NEVER state that you cannot access vehicle data or guess numbers without first executing `get_live_data` or `get_vehicle_info`.
            3. SAFETY FIRST:
               - The driver is operating a vehicle. Never give instructions that require them to look away from the road or manipulate parts while driving.
        """.trimIndent()
    }

    private fun buildTelemetrySnapshotForLive(): String {
        val isConnected = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
        val metrics = obdManager.metrics.value
        val dtcs = troubleCodes.value
        val vin = obdManager.vehicleVin.value ?: "Scanning/Pending"
        val vehicle = obdManager.vehicleDetails.value ?: "Vehicle"
        val m06 = obdManager.mode06Results.value
        val streams = obdManager.supportedStreams.value
        val odoKm = obdManager.vehicleOdometerKm.value
        val currentUnit = unitSystem.value
        val isMetric = currentUnit == UnitSystem.METRIC

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

        val odoText = if (odoKm != null && odoKm > 0) {
            if (isMetric) String.format(java.util.Locale.US, "%,.0f km", odoKm)
            else String.format(java.util.Locale.US, "%,.0f miles", odoKm * 0.621371)
        } else "Not detected"
        val dtcText = if (dtcs.isEmpty()) "None (Clean)" else dtcs.joinToString(", ")
        val m06Text = if (m06.isNotEmpty()) "${m06.size} monitors evaluated" else "Unscanned"
        val connStatus = if (isConnected) "Connected & Polling ECU" else "Adapter in negotiation (invoke get_live_data or get_vehicle_info for live status)"

        return """
            Vehicle: $vehicle (VIN: $vin)
            Odometer: $odoText
            Unit System Preference: ${if (isMetric) "METRIC (km/h, °C, kPa, km)" else "SAE (mph, °F, psi, miles)"}
            OBD2 Connection: $connStatus
            Active DTCs: $dtcText
            Latest Telemetry: $streamsText
            Mode 06 Monitors: $m06Text
        """.trimIndent()
    }

    private fun executeLiveTool(name: String, callId: String, @Suppress("UNUSED_PARAMETER") args: JSONObject): JSONObject {
        Log.i("GeminiAgentViewModel", "Executing live tool: $name ($callId)")
        return try {
            when (name) {
                "get_live_data" -> {
                    val metrics = obdManager.metrics.value
                    val streams = obdManager.supportedStreams.value
                    val isConn = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
                    val voltage = streams.find { it.pid.pidHex == "42" }?.formattedValue ?: "14.1V"
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    JSONObject().apply {
                        put("connected", isConn)
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
                        if (streams.isNotEmpty()) {
                            val streamsArr = JSONArray()
                            for (s in streams) {
                                val (fmtVal, fmtUnit) = formatValueWithUnit(s.rawValue, s.pid, currentUnit)
                                streamsArr.put(JSONObject().apply {
                                    put("name", s.pid.name)
                                    put("value", "$fmtVal $fmtUnit".trim())
                                })
                            }
                            put("supported_streams", streamsArr)
                        }
                    }
                }
                "get_dtcs" -> {
                    val dtcs = dtcDetails.value
                    val codes = troubleCodes.value
                    JSONObject().apply {
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
                    val m06 = obdManager.mode06Results.value
                    JSONObject().apply {
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
                    val odoKm = obdManager.vehicleOdometerKm.value
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    JSONObject().apply {
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
                    val m22 = obdManager.mode22Streams.value
                    val currentUnit = unitSystem.value
                    val isMetric = currentUnit == UnitSystem.METRIC
                    JSONObject().apply {
                        put("driver_unit_system", if (isMetric) "METRIC (State temps in °C, pressures in kPa, distances in km)" else "SAE (State temps in °F, pressures in psi, distances in miles)")
                        val arr = JSONArray()
                        for (p in m22) {
                            val (fmtVal, fmtUnit) = formatMode22ValueWithUnit(p.rawValue, p.pid, currentUnit)
                            arr.put(JSONObject().apply {
                                put("name", p.pid.name)
                                put("category", p.pid.category)
                                put("value", "$fmtVal $fmtUnit".trim())
                            })
                        }
                        put("custom_pids", arr)
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

    val liveVoiceName: StateFlow<String>
        get() = liveStreamManager?.voiceName ?: MutableStateFlow("Aoede").asStateFlow()

    val vehicleOdometerKm: StateFlow<Double?> = obdManager.vehicleOdometerKm

    fun setLiveVoiceName(voiceName: String) {
        liveStreamManager?.setVoice(voiceName)
    }

    override fun onCleared() {
        super.onCleared()
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

        messages.add(ChatMessage("System", "🔴 Live Data Recording Started. Telemetry data is being logged continuously..."))

        recordingJob = viewModelScope.launch(Dispatchers.Default) {
            while (_isRecording.value) {
                val now = System.currentTimeMillis()
                val elapsedSec = (now - recordingStartMs) / 1000.0
                val metricsSnap = obdManager.metrics.value
                val streamsSnap = obdManager.supportedStreams.value.toList()
                val mode22Snap = obdManager.mode22Streams.value.toList()

                val sample = RecordedDataSample(
                    timestampMs = now,
                    elapsedSeconds = elapsedSec,
                    metricsSnapshot = metricsSnap,
                    streamSnapshots = streamsSnap,
                    mode22Snapshots = mode22Snap
                )

                var count = 0
                val currentCopy: List<RecordedDataSample>
                synchronized(recordedSamples) {
                    recordedSamples.add(sample)
                    count = recordedSamples.size
                    currentCopy = recordedSamples.toList()
                }

                _recordingDurationSeconds.value = elapsedSec.toInt()
                _recordingSampleCount.value = count
                _recordedSamplesFlow.value = currentCopy

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
        return result
    }

    fun onCsvSavedSuccessfully(result: RecordedCsvResult) {
        val summary = "💾 Recorded Live Data Log Saved (${result.sampleCount} samples, ${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\nCSV file generated and available for AI diagnostic analysis."
        messages.add(ChatMessage("System", summary))
    }

    fun onCsvAttachedForAnalysis(result: RecordedCsvResult) {
        val summary = "💾 Recorded Live Data Log Attached (${result.sampleCount} samples, ${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\nFile: ${result.defaultFilename}\n\nAsk VERA any question about this data log!"
        messages.add(ChatMessage("System", summary))
    }

    fun onDiscardRecordedData() {
        lastRecordedCsvResult = null
        messages.add(ChatMessage("System", "🗑️ Live Data Recording Discarded."))
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

        val dynamicPidNames = samples.flatMap { s -> s.streamSnapshots.map { it.pid.name } }.distinct()
        val mode22PidNames = samples.flatMap { s -> s.mode22Snapshots.map { it.pid.name } }.distinct()

        val headers = mutableListOf(
            "Time_ISO",
            "Elapsed_Sec",
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
            val speedVal = if (isSae) m.speed * 0.621371 else m.speed.toDouble()
            val tempVal = if (isSae) (m.coolantTemp * 9.0 / 5.0) + 32.0 else m.coolantTemp.toDouble()
            val iatVal = if (isSae) (m.intakeAirTemp * 9.0 / 5.0) + 32.0 else m.intakeAirTemp.toDouble()

            val rowMap = mutableMapOf<String, String>()
            rowMap["Time_ISO"] = isoFormat.format(java.util.Date(sample.timestampMs))
            rowMap["Elapsed_Sec"] = String.format(java.util.Locale.US, "%.2f", sample.elapsedSeconds)
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

        return sb.toString()
    }

    init {
        viewModelScope.launch {
            obdManager.connectionState.collect { state ->
                _connectionStatus.value = when (state) {
                    is BluetoothObdManager.ConnectionState.Disconnected -> "Disconnected"
                    is BluetoothObdManager.ConnectionState.Connecting -> "Connecting..."
                    is BluetoothObdManager.ConnectionState.Connected -> "Connected"
                    is BluetoothObdManager.ConnectionState.Error -> "Disconnected"
                }
            }
        }

        viewModelScope.launch {
            var lastVehicleDetail: String? = null
            obdManager.vehicleDetails.collect { details ->
                if (!details.isNullOrBlank() && details != lastVehicleDetail) {
                    lastVehicleDetail = details
                    messages.add(ChatMessage("System", "🚗 Vehicle Connected: $details"))
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
                            messages.add(
                                ChatMessage(
                                    "System",
                                    "🌐 OBDb Community Parameter Signalset Loaded: ${result.repo}/${result.file} (${result.commands.size} custom commands). Scanning signals in background..."
                                )
                            )
                            scanObdbSignals()
                        }
                    }
                    is ObdbRepository.LoadResult.NoData -> {
                        messages.add(
                            ChatMessage(
                                "System",
                                "ℹ️ No OBDb community parameter repository found for vehicle (tried: ${result.tried.joinToString(", ")})."
                            )
                        )
                    }
                    is ObdbRepository.LoadResult.Unavailable -> {
                        messages.add(
                            ChatMessage(
                                "System",
                                "⚠️ OBDb Parameter Signalset Unavailable: ${result.reason}"
                            )
                        )
                    }
                    null -> {}
                }
            }
        }
    }

    private val systemPromptText = """
        You are VERA, an elite automotive master diagnostic technician and veteran shade-tree mechanic riding shotgun with the driver.
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

    fun connectToCar(macOrUsbAddress: String, context: android.content.Context? = null) {
        viewModelScope.launch {
            _isConnecting.value = true
            val isUsb = macOrUsbAddress.startsWith("USB:")
            val connTypeLabel = if (isUsb) "Wired USB OBD2 adapter (vLinker FS)" else "OBD2 adapter"
            messages.add(ChatMessage("System", "Connecting to $connTypeLabel..."))
            val result = obdManager.connectToDevice(macOrUsbAddress, context)
            if (result.isSuccess) {
                messages.add(ChatMessage("System", "Successfully connected to $connTypeLabel."))
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Connection failed"
                messages.add(ChatMessage("System", "Failed to connect: $errorMsg"))
            }
            _isConnecting.value = false
        }
    }

    fun disconnectFromCar() {
        viewModelScope.launch {
            obdManager.disconnect()
            messages.add(ChatMessage("System", "Disconnected from OBD2 adapter."))
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
                messages.add(ChatMessage("System", "Cannot scan Mode 06: OBD2 adapter is not connected."))
                return@launch
            }
            _isScanningMode06.value = true
            messages.add(ChatMessage("System", "Scanning Full Mode 06 On-Board Diagnostic Component Monitors..."))
            val m06Results = obdManager.scanAllMode06Monitors()

            val summaryText = if (m06Results.isNotEmpty()) {
                m06Results.joinToString("\n") { "• [${it.category}] ${it.name}: ${it.testValue.toInt()} ${it.unit} [${it.status}]" }
            } else {
                "No Mode 06 data retrieved."
            }

            messages.add(ChatMessage("System", "Mode 06 Scan Complete (${m06Results.size} Monitors Evaluated):\n$summaryText"))
            _isScanningMode06.value = false
        }
    }

    fun scanMode22Parameters() {
        viewModelScope.launch {
            try {
                if (obdManager.connectionState.value !is BluetoothObdManager.ConnectionState.Connected && !obdManager.isDemoMode) {
                    messages.add(ChatMessage("System", "Cannot scan Custom PIDs: OBD2 adapter is not connected."))
                    return@launch
                }
                _isScanningMode22.value = true
                val details = obdManager.vehicleDetails.value ?: "Vehicle"
                messages.add(ChatMessage("System", "Scanning Enhanced Custom PIDs & OBDb Signals for $details..."))

                // Scan Mode 22 PIDs and OBDb Custom PIDs
                val m22Results = obdManager.scanMode22Pids()
                val obdbResults = obdManager.readObdbSignals()

                val currentUnit = unitSystem.value
                val summaryList = mutableListOf<String>()
                for (m22 in m22Results) {
                    val (fmtVal, fmtUnit) = formatMode22ValueWithUnit(m22.rawValue, m22.pid, currentUnit)
                    summaryList.add("• ${m22.pid.name}: $fmtVal $fmtUnit".trim())
                }
                val loadedCmds = (obdbLoadedResult.value as? ObdbRepository.LoadResult.Loaded)?.commands ?: emptyList()
                val allSignals = loadedCmds.flatMap { it.signals }.associateBy { it.id }
                for ((sigId, valObj) in obdbResults) {
                    val sig = allSignals[sigId]
                    val sigName = sig?.name ?: sigId
                    val (formattedVal, formattedUnit) = formatObdbValueWithUnit(valObj, sig, sigId, currentUnit)
                    summaryList.add("• $sigName: $formattedVal $formattedUnit".trim())
                }

                val summary = if (summaryList.isNotEmpty()) summaryList.joinToString("\n") else "No Custom PID responses received from vehicle."
                messages.add(ChatMessage("System", "Custom PIDs Scan Complete:\n$summary"))
            } catch (e: Exception) {
                Log.e("GeminiAgentViewModel", "Error scanning Mode 22 parameters", e)
                messages.add(ChatMessage("System", "Custom PIDs scan encountered an error: ${e.localizedMessage ?: e.message}"))
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
            val names = allAttachments.joinToString(", ") { if (it.isImage) "📷 ${it.fileName}" else "📄 ${it.fileName}" }
            "\n[Attached ($attachmentCount): $names]"
        } else ""

        val displayMsg = (userText + attachmentLabel).trim()
        messages.add(ChatMessage("User", displayMsg))

        viewModelScope.launch {
            try {
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
                        m06Data.joinToString("\n") { "  • ${it.name}: ${it.testValue.toInt()} misfires (${it.status})" }
                    } else {
                        "  • Mode 06 On-Board Monitors: Unscanned / Not queried yet"
                    }

                    val m22Summary = if (m22Data.isNotEmpty()) {
                        m22Data.joinToString("\n") { m ->
                            val (fmtVal, fmtUnit) = formatMode22ValueWithUnit(m.rawValue, m.pid, currentUnit)
                            "  • ${m.pid.name} (${m.pid.category}): $fmtVal $fmtUnit"
                        }
                    } else {
                        "  • Mode 22 Proprietary Parameters: Unscanned / Not queried yet"
                    }

                    """
                        [Vehicle Telemetry Context - FOR INTERNAL DIAGNOSTIC REASONING ONLY]
                        VIN: $vin
                        Active DTCs: ${if (dtcs.isEmpty()) "None" else dtcs.joinToString(", ")}
                        Telemetry Snapshot: $streamsSummary
                        Mode 06 Monitors: $m06Summary
                        Mode 22 PIDs: $m22Summary
                        $elmTraceSection
                        $fileAttachmentContext

                        CRITICAL INSTRUCTION: Speak naturally in plain English like a real human mechanic riding shotgun. Do NOT recite raw sensor numbers, PIDs, temperatures, voltages, or test IDs out loud unless specifically requested.
                        Healthy verdict example: "Let me know if there's a specific problem you're experiencing, but from all the indicators I'm watching, everything looks great."
                        Issue verdict example: "I'm seeing some misfires on cylinder 3—maybe dirty spark plugs, fuel injectors, or a dirty throttle body. Try turning on the AC and see if it stops under load."

                        Driver Question: $userText
                    """.trimIndent()
                } else {
                    """
                        [Vehicle OBD2 Status: DISCONNECTED]
                        $fileAttachmentContext

                        CRITICAL INSTRUCTION: Speak naturally in plain English like a real human mechanic riding shotgun. Do NOT recite raw sensor numbers, PIDs, temperatures, voltages, or test IDs out loud unless specifically requested.
                        Healthy verdict example: "Let me know if there's a specific problem you're experiencing, but from all the indicators I'm watching, everything looks great."
                        Issue verdict example: "I'm seeing some misfires on cylinder 3—maybe dirty spark plugs, fuel injectors, or a dirty throttle body. Try turning on the AC and see if it stops under load."

                        Driver Question: $userText
                    """.trimIndent()
                }

                _isSendingMessage.value = true
                val aiResponseText = callGeminiApi(contextualPrompt, imagesBase64)
                messages.add(ChatMessage("VERA", aiResponseText))
                _isSendingMessage.value = false
                speak(aiResponseText)
                autoSaveCurrentSession()

            } catch (e: Throwable) {
                messages.add(ChatMessage("Error", "Gemini Request Error: ${e.message ?: e.toString()}"))
                _isSendingMessage.value = false
            }
        }
    }
}
