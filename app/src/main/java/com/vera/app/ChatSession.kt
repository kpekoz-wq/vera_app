package com.vera.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persisted Drive / Diagnostic Chat Session stored in app internal storage (`filesDir/saved_chats`).
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val vehicleDetails: String? = null,
    val vehicleVin: String? = null,
    val odometerFormatted: String? = null,
    val dtcs: List<String>? = emptyList(),
    val messages: List<ChatMessage>? = emptyList(),
    val recordedSamples: List<RecordedDataSample>? = emptyList(),
    val mode06Results: List<Mode06TestResult>? = emptyList()
) {
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val safeRecordedSamples: List<RecordedDataSample>
        get() = recordedSamples ?: emptyList()

    val safeMessages: List<ChatMessage>
        get() = messages ?: emptyList()

    val safeDtcs: List<String>
        get() = dtcs ?: emptyList()

    val hasReplayData: Boolean
        get() = !recordedSamples.isNullOrEmpty()
}

/**
 * Manages saving, loading, listing, and deleting diagnostic chat sessions.
 * Stored inside its own folder `saved_chats/` in the app's internal files directory.
 */
class ChatSessionManager(private val context: android.content.Context) {

    private val gson = Gson()
    private val chatsDir: File by lazy {
        File(context.filesDir, "saved_chats").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    private val prefs by lazy {
        context.getSharedPreferences("vera_chat_session_prefs", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAllSessions(): List<ChatSession> {
        val files = chatsDir.listFiles { file -> file.extension.lowercase(Locale.US) == "json" }
        val sessions = mutableListOf<ChatSession>()
        if (files != null) {
            for (file in files) {
                try {
                    // Stream JSON using JsonReader to read only session metadata without loading millions of recordedSamples into RAM
                    java.io.FileReader(file).use { fileReader ->
                        com.google.gson.stream.JsonReader(fileReader).use { reader ->
                            var id = ""
                            var title = ""
                            var timestamp = 0L
                            var vehicleDetails: String? = null
                            var vehicleVin: String? = null
                            var odometerFormatted: String? = null
                            var dtcs: MutableList<String>? = null
                            var messages: MutableList<ChatMessage>? = null
                            var hasReplaySamples = false

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "id" -> id = reader.nextString()
                                    "title" -> title = reader.nextString()
                                    "timestamp" -> timestamp = reader.nextLong()
                                    "vehicleDetails" -> vehicleDetails = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                    "vehicleVin" -> vehicleVin = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                    "odometerFormatted" -> odometerFormatted = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                    "dtcs" -> {
                                        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                            reader.nextNull()
                                        } else {
                                            dtcs = mutableListOf()
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                dtcs.add(reader.nextString())
                                            }
                                            reader.endArray()
                                        }
                                    }
                                    "messages" -> {
                                        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                            reader.nextNull()
                                        } else {
                                            messages = mutableListOf()
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                messages.add(gson.fromJson(reader, ChatMessage::class.java))
                                            }
                                            reader.endArray()
                                        }
                                    }
                                    "recordedSamples" -> {
                                        // Skip deep sample objects; just check if array is non-empty
                                        if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                            reader.nextNull()
                                        } else {
                                            reader.beginArray()
                                            if (reader.hasNext()) {
                                                hasReplaySamples = true
                                            }
                                            while (reader.hasNext()) {
                                                reader.skipValue()
                                            }
                                            reader.endArray()
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            val session = ChatSession(
                                id = id.ifBlank { file.nameWithoutExtension },
                                title = title,
                                timestamp = timestamp,
                                vehicleDetails = vehicleDetails,
                                vehicleVin = vehicleVin,
                                odometerFormatted = odometerFormatted,
                                dtcs = dtcs ?: emptyList(),
                                messages = messages ?: emptyList(),
                                recordedSamples = if (hasReplaySamples) listOf(RecordedDataSample(0L, 0.0, BluetoothObdManager.VehicleMetrics(), emptyList(), emptyList(), RoadContext())) else emptyList()
                            )
                            sessions.add(session)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatSessionManager", "Error parsing session from ${file.name}", e)
                }
            }
        }

        val hasSeededDemo = prefs.getBoolean("demo_flight_seeded_v1", false)
        if (!hasSeededDemo) {
            if (sessions.none { it.hasReplayData }) {
                val demoSession = createDemoFlightSession()
                saveSession(demoSession)
                sessions.add(demoSession)
            }
            prefs.edit().putBoolean("demo_flight_seeded_v1", true).apply()
        }

        return sessions.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun saveSession(session: ChatSession) {
        try {
            val file = File(chatsDir, "${session.id}.json")
            val json = gson.toJson(session)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Failed to save chat session ${session.id}", e)
        }
    }

    @Synchronized
    fun loadSession(id: String): ChatSession? {
        val file = File(chatsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, ChatSession::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Failed to load chat session $id", e)
            null
        }
    }

    @Synchronized
    fun getSessionJson(id: String): String? {
        val file = File(chatsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Failed to read chat session json $id", e)
            null
        }
    }

    @Synchronized
    fun deleteSession(id: String): Boolean {
        val file = File(chatsDir, "$id.json")
        return if (file.exists()) file.delete() else false
    }

    private fun createDemoFlightSession(): ChatSession {
        val now = System.currentTimeMillis()
        val samples = mutableListOf<RecordedDataSample>()
        val totalPoints = 90 // 45 seconds of flight telemetry at 2Hz
        for (i in 0 until totalPoints) {
            val sec = i * 0.5
            val progressFrac = i.toDouble() / totalPoints.toDouble()
            // Simulating an uphill climb on Jones St in San Francisco
            val spd = (20.0 + 8.0 * kotlin.math.sin(progressFrac * Math.PI)).coerceIn(15.0, 32.0)
            val rpm = (1800.0 + 850.0 * progressFrac + 80.0 * kotlin.math.sin(sec)).toInt()
            val coolant = (192.0 + 13.0 * progressFrac).toInt()
            val load = (45.0 + 35.0 * progressFrac).toFloat()
            val throttle = (28.0 + 22.0 * progressFrac).toFloat()
            val trim = if (progressFrac > 0.4) (+3.5 + 2.5 * progressFrac).toFloat() else (+1.2).toFloat()
            val grade = 7.2 // 7.2% steep hill climb
            val alt = 310.0 + (progressFrac * 120.0) // climbing from 310ft to 430ft

            val metrics = BluetoothObdManager.VehicleMetrics(
                rpm = rpm,
                speed = (spd / 0.621371).toInt(), // store as km/h
                coolantTemp = ((coolant - 32) * 5 / 9), // store as C
                intakeAirTemp = 28,
                engineLoad = load.toInt(),
                throttlePosition = throttle.toInt(),
                fuelTrim = trim.toInt(),
                maf = 18.5 + (progressFrac * 12.0)
            )

            val voltPid = ObdPidRegistry.KNOWN_PIDS["42"] ?: ObdPidDefinition("42", "0142", "Control Module Voltage", "V", "ECU Voltage", 2) { a -> ((a[0] * 256) + a[1]) / 1000.0 }
            val loadPid = ObdPidRegistry.KNOWN_PIDS["04"] ?: ObdPidDefinition("04", "0104", "Engine Load", "%", "Calculated Engine Load", 1) { a -> (a[0] * 100) / 255.0 }

            val streams = listOf(
                LiveStreamData(
                    pid = voltPid,
                    rawValue = 14.2 - (0.2 * progressFrac),
                    formattedValue = String.format(Locale.US, "%.1fV", 14.2 - (0.2 * progressFrac))
                ),
                LiveStreamData(
                    pid = loadPid,
                    rawValue = load.toDouble(),
                    formattedValue = String.format(Locale.US, "%.0f%%", load)
                )
            )

            val transPid = Mode22Registry.KNOWN_PIDS.firstOrNull() ?: Mode22PidDefinition("1E1C", "Transmission Temp", "Transmission", "°C", "Ford Transmission Fluid Temp", "Ford") { a -> if (a.size >= 2) ((a[0] * 256) + a[1]) / 16.0 - 40.0 else 0.0 }

            val mode22 = listOf(
                Mode22StreamData(
                    pid = transPid,
                    rawValue = 185.0 + (15.0 * progressFrac),
                    formattedValue = String.format(Locale.US, "%.0f°F", 185.0 + (15.0 * progressFrac))
                )
            )

            val road = RoadContext(
                streetName = "Jones St",
                neighborhoodOrCity = "San Francisco, CA",
                fullAddress = "Jones St & Sacramento St, San Francisco, CA",
                latitude = 37.7925 + (progressFrac * 0.003),
                longitude = -122.4132,
                altitudeMeters = alt * 0.3048,
                altitudeFeet = alt,
                roadGradePercent = grade,
                inclineStatus = InclineStatus.STEEP_CLIMB,
                headingCompass = "North",
                speedMph = spd,
                speedKmh = spd * 1.60934,
                isDemoSimulated = true
            )

            samples.add(
                RecordedDataSample(
                    timestampMs = now - ((totalPoints - i) * 500L),
                    elapsedSeconds = sec,
                    metricsSnapshot = metrics,
                    streamSnapshots = streams,
                    mode22Snapshots = mode22,
                    roadContextSnapshot = road
                )
            )
        }

        val demoMsgs = listOf(
            ChatMessage("VERA", "Connected to 2021 Ford F-150 (VIN: 1FTFW1ED5MFB12345). Diagnostics active."),
            ChatMessage("User", "Hey VERA, let's see how the truck does climbing Jones St."),
            ChatMessage("VERA", "I'll keep an eye on your trims and coolant as you head up the grade."),
            ChatMessage("VERA", "Trims are creeping slightly positive at +4.8% as you pull up this 7.2% hill climb. Coolant is holding steady at 198°F.")
        )

        return ChatSession(
            id = "demo_jones_st_climb",
            title = "Demo Drive: Jones St Hill Climb",
            timestamp = now - 3600000L,
            vehicleDetails = "2021 Ford F-150 • 3.5L EcoBoost",
            vehicleVin = "1FTFW1ED5MFB12345",
            odometerFormatted = "48,210 mi",
            dtcs = emptyList(),
            messages = demoMsgs,
            recordedSamples = samples
        )
    }

    companion object {
        fun generateDefaultTitle(
            messages: List<ChatMessage>,
            vehicleDetails: String?,
            odometerFormatted: String?,
            dtcs: List<String>
        ): String {
            val vehShort = vehicleDetails?.split("•")?.firstOrNull()?.trim() ?: "Drive Session"
            val firstUserQuery = messages.firstOrNull { it.sender == "User" }?.text?.trim()

            return when {
                dtcs.isNotEmpty() -> "$vehShort • DTC: ${dtcs.first()}"
                !firstUserQuery.isNullOrBlank() -> {
                    val preview = if (firstUserQuery.length > 28) firstUserQuery.take(25) + "..." else firstUserQuery
                    "$vehShort: \"$preview\""
                }
                !odometerFormatted.isNullOrBlank() -> "$vehShort • $odometerFormatted"
                else -> {
                    val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
                    "$vehShort ($timeStr)"
                }
            }
        }
    }
}
