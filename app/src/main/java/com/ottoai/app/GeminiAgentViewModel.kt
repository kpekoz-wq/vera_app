package com.ottoai.app

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
                    sender = "OttoAI",
                    text = "Hello! I am OttoAI, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
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
                        Log.i("OttoAI_TTS", "Selected Google US High-Definition Voice: ${selectedVoice.name}")
                    }
                } catch (e: Exception) {
                    Log.w("OttoAI_TTS", "Error selecting voice", e)
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

        viewModelScope.launch(Dispatchers.IO) {
            var audioPlaySuccess = false
            val cacheDir = appContext?.cacheDir

            if (geminiApiKey.isNotBlank() && cacheDir != null) {
                val voicesToTry = listOf("en-US-Journey-D", "en-US-Neural2-J", "en-US-Studio-O", "en-US-Neural2-D")
                for (voiceName in voicesToTry) {
                    try {
                        val url = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$geminiApiKey")
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 5000
                            readTimeout = 8000
                            doOutput = true
                        }

                        val requestJson = JSONObject().apply {
                            put("input", JSONObject().put("text", cleanText))
                            put("voice", JSONObject().apply {
                                put("languageCode", "en-US")
                                put("name", voiceName)
                            })
                            put("audioConfig", JSONObject().apply {
                                put("audioEncoding", "MP3")
                                put("speakingRate", 1.02)
                            })
                        }

                        connection.outputStream.use { os ->
                            os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                        }

                        if (connection.responseCode in 200..299) {
                            val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                            val audioB64 = JSONObject(responseStr).optString("audioContent", "")
                            if (audioB64.isNotBlank()) {
                                val mp3Bytes = Base64.decode(audioB64, Base64.DEFAULT)
                                val tempFile = java.io.File.createTempFile("otto_voice_", ".mp3", cacheDir)
                                tempFile.writeBytes(mp3Bytes)

                                withContext(Dispatchers.Main) {
                                    try {
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
                                Log.i("OttoAI_Voice", "Played Google Cloud Human Voice ($voiceName)")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("OttoAI_Voice", "Cloud TTS Voice $voiceName failed: ${e.message}")
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

    private fun speakWithAndroidTts(cleanText: String) {
        if (tts == null) return
        _isSpeaking.value = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) { _isSpeaking.value = false }
        })
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "OttoAiUtterance_${System.currentTimeMillis()}")
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
                sender = "OttoAI",
                text = "Hello! I am OttoAI, your autonomous automotive mechanic assistant. Connect your OBD2 adapter, scan DTCs, or ask me any diagnostic questions."
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
                    sender = "OttoAI",
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
            .filter { it.sender == "User" || it.sender == "OttoAI" || it.sender == "OttoAI (Live)" }
            .takeLast(maxMessages)
        if (relevant.isEmpty()) return ""
        return relevant.joinToString("\n") { msg ->
            val label = if (msg.sender == "User") "DRIVER" else "OTTOAI"
            "$label: ${msg.text.trim()}"
        }
    }

    fun initLiveStreamManager(context: Context) {
        appContext = context.applicationContext
        initSessionManager(context)
        OttoVoiceService.onStopRequested = {
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
            OttoVoiceService.startService(it)
        }

        val voice = mgr.voiceName.value
        val summary = buildTelemetrySnapshotForLive()
        val historyContext = buildRecentConversationContextForLive(maxMessages = 20)
        isNewLiveTurn = true
        isNewUserTurn = true

        messages.add(
            ChatMessage(
                "System",
                "🎙️ Hands-Free Gemini Live Active (Voice: $voice).\nReal-time 2-way conversation running. Speak naturally—OttoAI has full live read-only telemetry access to your vehicle."
            )
        )

        mgr.startLiveSession(
            apiKey = geminiApiKey,
            systemInstruction = liveSystemPromptText,
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
                        OttoVoiceService.stopService(it)
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
            OttoVoiceService.stopService(it)
        }
        messages.add(ChatMessage("System", "⚡ Hands-Free Gemini Live Mode Ended."))
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
            messages.add(ChatMessage("OttoAI (Live)", text.trim()))
            isNewLiveTurn = false
        } else {
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null && lastMsg.sender == "OttoAI (Live)") {
                val updated = if (lastMsg.text.endsWith(" ") || text.startsWith(" ") || text.startsWith(",") || text.startsWith(".") || text.startsWith("!") || text.startsWith("?")) {
                    lastMsg.text + text
                } else {
                    lastMsg.text + " " + text
                }
                val idx = messages.size - 1
                messages[idx] = ChatMessage("OttoAI (Live)", updated)
            } else {
                messages.add(ChatMessage("OttoAI (Live)", text.trim()))
            }
        }
    }

    private val liveSystemPromptText = """
        You are OttoAI, an elite automotive master technician and seasoned mechanic riding shotgun with the driver.

        You are interacting with the driver in REAL-TIME 2-WAY HANDS-FREE VOICE MODE over bidirectional audio while they are operating their vehicle.

        CRITICAL DRIVING & VOICE RULES:
        1. NATURAL HUMAN MECHANIC VOICE & CONVERSATION:
           - Speak naturally and conversationally, like a knowledgeable friend and master mechanic sitting in the passenger seat.
           - Keep answers concise (1 to 3 sentences) so the driver can remain focused on the road.
           - NEVER recite raw PIDs, sensor codes, raw voltages, or test IDs out loud unless the driver explicitly asks for them.
           - Give clear plain-English verdicts:
             * Healthy vehicle: "Everything is purring smoothly and your temperatures are right where they should be. Let me know if you feel or hear anything unusual."
             * Issue detected: "I'm catching a few misfires on cylinder three. Nothing catastrophic right now, but once we're parked we should inspect the spark plug and ignition coil."
        2. MANDATORY REAL-TIME TELEMETRY RETRIEVAL:
           - Vehicle telemetry (RPM, coolant temperature, speed, fuel trims, error codes, odometer mileage) changes dynamically while driving.
           - Whenever the driver asks about their engine, mileage, odometer, temperatures, speed, RPM, battery, check engine light, trouble codes, or vehicle health, YOU MUST FIRST EXECUTE THE CORRESPONDING TOOL (`get_vehicle_info`, `get_live_data`, `get_dtcs`, `get_mode06_results`) BEFORE GIVING YOUR ANSWER.
           - When asked about mileage or odometer, speak the reading naturally in miles (e.g. "You're at one hundred fourteen thousand miles") unless the driver specifies kilometers.
           - NEVER state that you cannot access vehicle data or guess numbers without first executing `get_live_data` or `get_vehicle_info`.
        3. SAFETY FIRST:
           - The driver is operating a vehicle. Never give instructions that require them to look away from the road while driving.
    """.trimIndent()

    private fun buildTelemetrySnapshotForLive(): String {
        val isConnected = obdManager.connectionState.value is BluetoothObdManager.ConnectionState.Connected || obdManager.isDemoMode
        val metrics = obdManager.metrics.value
        val dtcs = troubleCodes.value
        val vin = obdManager.vehicleVin.value ?: "Scanning/Pending"
        val vehicle = obdManager.vehicleDetails.value ?: "Vehicle"
        val m06 = obdManager.mode06Results.value
        val streams = obdManager.supportedStreams.value
        val odoKm = obdManager.vehicleOdometerKm.value

        val streamsText = if (streams.isNotEmpty()) {
            streams.take(15).joinToString(", ") { "${it.pid.name}: ${it.formattedValue}" }
        } else "RPM: ${metrics.rpm}, Speed: ${metrics.speed} km/h, Coolant: ${metrics.coolantTemp}°C"

        val odoText = if (odoKm != null && odoKm > 0) {
            String.format(java.util.Locale.US, "%,.0f mi (%,.0f km)", odoKm * 0.621371, odoKm)
        } else "Not detected"
        val dtcText = if (dtcs.isEmpty()) "None (Clean)" else dtcs.joinToString(", ")
        val m06Text = if (m06.isNotEmpty()) "${m06.size} monitors evaluated" else "Unscanned"
        val connStatus = if (isConnected) "Connected & Polling ECU" else "Adapter in negotiation (invoke get_live_data or get_vehicle_info for live status)"

        return """
            Vehicle: $vehicle (VIN: $vin)
            Odometer: $odoText
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
                    JSONObject().apply {
                        put("connected", isConn)
                        put("rpm", metrics.rpm)
                        put("is_engine_running", metrics.rpm > 300)
                        put("speed_mph", (metrics.speed * 0.621371).toInt())
                        put("speed_kmh", metrics.speed)
                        put("coolant_temp_f", (metrics.coolantTemp * 9 / 5) + 32)
                        put("coolant_temp_c", metrics.coolantTemp)
                        put("intake_air_temp_f", (metrics.intakeAirTemp * 9 / 5) + 32)
                        put("intake_air_temp_c", metrics.intakeAirTemp)
                        put("battery_voltage", voltage)
                        put("engine_load_percent", metrics.engineLoad)
                        put("throttle_position_percent", metrics.throttlePosition)
                        put("fuel_trim_percent", metrics.fuelTrim)
                        put("mass_air_flow_gs", metrics.maf)
                        put("vehicle", obdManager.vehicleDetails.value ?: "Vehicle")
                        val odoKm = obdManager.vehicleOdometerKm.value
                        if (odoKm != null && odoKm > 0) {
                            put("odometer_miles", (odoKm * 0.621371).toInt())
                            put("odometer_km", odoKm.toInt())
                        }
                        if (streams.isNotEmpty()) {
                            val streamsArr = JSONArray()
                            for (s in streams) {
                                streamsArr.put(JSONObject().apply {
                                    put("name", s.pid.name)
                                    put("value", s.formattedValue)
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
                    JSONObject().apply {
                        put("vin", obdManager.vehicleVin.value ?: "Not available")
                        put("vehicle_details", obdManager.vehicleDetails.value ?: "Not available")
                        if (odoKm != null && odoKm > 0) {
                            put("odometer_miles", (odoKm * 0.621371).toInt())
                            put("odometer_km", odoKm.toInt())
                            put("odometer_formatted", String.format(java.util.Locale.US, "%,.0f miles (%,.0f km)", odoKm * 0.621371, odoKm))
                        } else {
                            put("odometer", "Unavailable")
                        }
                        put("connection_status", connectionStatus.value)
                    }
                }
                "get_obdb_signals" -> {
                    val m22 = obdManager.mode22Streams.value
                    JSONObject().apply {
                        val arr = JSONArray()
                        for (p in m22) {
                            arr.put(JSONObject().apply {
                                put("name", p.pid.name)
                                put("category", p.pid.category)
                                put("value", p.formattedValue)
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
            OttoVoiceService.stopService(it)
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

    private val recordedSamples = mutableListOf<RecordedDataSample>()
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var recordingStartMs = 0L

    var lastRecordedCsvResult: RecordedCsvResult? = null
        private set

    fun startRecording() {
        if (_isRecording.value) return
        synchronized(recordedSamples) { recordedSamples.clear() }
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
                synchronized(recordedSamples) {
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
        val durationSec = if (samplesCopy.isNotEmpty()) (samplesCopy.last().timestampMs - samplesCopy.first().timestampMs) / 1000.0 else 0.0

        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val defaultFilename = "OttoAI_DataLog_$timeStr.csv"

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
        val summary = "💾 Recorded Live Data Log Attached (${result.sampleCount} samples, ${String.format(java.util.Locale.US, "%.1f", result.durationSeconds)}s duration).\nFile: ${result.defaultFilename}\n\nAsk OttoAI any question about this data log!"
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

        sb.append("# OttoAI Vehicle Live Data Log\n")
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
        You are OttoAI, a master automotive diagnostic expert and seasoned, practical shade-tree mechanic riding shotgun with the driver.
        
        Persona & Knowledge Base:
        - Master Diagnostic Skills: Deep understanding of engine thermodynamics, electrical systems, PID sensors, fuel trim math, ignition timing, and ECU logic.
        - Practical Wrenching Wisdom: Experienced in real-world failure patterns, common vehicle quirks, quick driveway/garage tests, and practical repair advice.

        Blue-Collar Garage Humor & Shop Personality:
        - Infuse a subtle, friendly blue-collar mechanic sense of humor (e.g., light garage banter, missing 10mm socket jokes, engineers overcomplicating simple parts, or mild "just send it" shop humor).
        - Keep it light, natural, and occasional—not every single response needs a joke, but present enough to make riding shotgun with OttoAI pleasant, warm, and engaging to deal with.

        Core Mechanics & Communication Rules:
        1. SPEAK LIKE A REAL HUMAN MECHANIC IN PLAIN ENGLISH: Speak naturally as an expert mechanic sitting in the passenger seat. NEVER recite raw sensor numbers, PID values, RPM figures, voltage levels, or test ID numbers out loud unless the user specifically asks for exact numbers.
        2. CONCISE PLAIN ENGLISH VERDICTS: Summarize overall vehicle/engine status in 1 to 3 natural, conversational sentences.
           - Healthy vehicle example: "Let me know if there's a specific problem you're experiencing, but from all the indicators I'm watching, everything looks great."
           - Issue/Misfire example: "I'm seeing some misfires on Cylinder 3—maybe dirty spark plugs, fuel injectors, or a dirty throttle body. Try turning on the AC and see if it stops under load."
        3. NO DATA DUMPS OR RECITING NUMBERS: Never list out telemetry lists, PID dumps, raw CSV lines, or trace log text. Summarize diagnostic conclusions and actionable "shade-tree" tests directly.
        4. DISCONNECTED STATE WITH ATTACHMENTS/LOGS: If disconnected from the vehicle BUT user provides attached photos, CSV data logs, files, or diagnostic descriptions, FULLY analyze them and explain findings in plain English.
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
            val models = listOf(
                "gemini-3.8-flash",
                "gemini-3.1-pro-preview",
                "gemini-3.5-flash-lite"
            )
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
                                    val aiText = parts.getJSONObject(0).optString("text", "No response text")
                                    Log.i("GeminiApi", "Successfully received AI response from model $modelName")
                                    return@withContext aiText
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

                val summaryList = mutableListOf<String>()
                for (m22 in m22Results) {
                    summaryList.add("• ${m22.pid.name}: ${m22.formattedValue}")
                }
                val loadedCmds = (obdbLoadedResult.value as? ObdbRepository.LoadResult.Loaded)?.commands ?: emptyList()
                val allSignals = loadedCmds.flatMap { it.signals }.associateBy { it.id }
                for ((sigId, valObj) in obdbResults) {
                    val sigName = allSignals[sigId]?.name ?: sigId
                    val unit = allSignals[sigId]?.unit ?: ""
                    summaryList.add("• $sigName: $valObj $unit".trim())
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

                    val streamsSummary = if (streams.isNotEmpty()) {
                        streams.joinToString("\n") { "- ${it.pid.name} (${it.pid.description}): ${it.formattedValue}" }
                    } else {
                        val metrics = obdManager.metrics.value
                        "- Engine RPM: ${metrics.rpm} RPM\n- Coolant Temp: ${metrics.coolantTemp}°C\n- Vehicle Speed: ${metrics.speed} km/h"
                    }

                    val m06Summary = if (m06Data.isNotEmpty()) {
                        m06Data.joinToString("\n") { "  • ${it.name}: ${it.testValue.toInt()} misfires (${it.status})" }
                    } else {
                        "  • Mode 06 On-Board Monitors: Unscanned / Not queried yet"
                    }

                    val m22Summary = if (m22Data.isNotEmpty()) {
                        m22Data.joinToString("\n") { "  • ${it.pid.name} (${it.pid.category}): ${it.formattedValue}" }
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
                messages.add(ChatMessage("OttoAI", aiResponseText))
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
