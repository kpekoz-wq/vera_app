package com.vera.app

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
    val dtcs: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList()
) {
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
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

    @Synchronized
    fun getAllSessions(): List<ChatSession> {
        val files = chatsDir.listFiles { file -> file.extension.lowercase(Locale.US) == "json" } ?: return emptyList()
        val sessions = mutableListOf<ChatSession>()
        for (file in files) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val session = gson.fromJson(json, ChatSession::class.java)
                if (session != null) {
                    sessions.add(session)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatSessionManager", "Error parsing session from ${file.name}", e)
            }
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
    fun deleteSession(id: String): Boolean {
        val file = File(chatsDir, "$id.json")
        return if (file.exists()) file.delete() else false
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
