package com.vera.app

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * VeraDiagnosticTelemetrySync
 *
 * Automatically and silently synchronizes tester diagnostic session error logs back to the developer
 * cloud bucket (Firebase Storage REST / Cloud Endpoint) without requiring the tester to manually
 * attach files to an email.
 *
 * Features:
 * 1. GZIP compression of raw ELM327 / OBD2 communication logs before transmission.
 * 2. Automatic metadata tagging: device manufacturer, model, Android OS, app build version,
 *    vehicle make/model/year, VIN, timestamp, and connection mode.
 * 3. Safe, non-blocking asynchronous transmission with rate-limiting and zero UI freeze.
 */
object VeraDiagnosticTelemetrySync {

    private const val TAG = "VeraTelemetrySync"

    // Firebase Storage REST API endpoint for OttoAI / VERA project
    private const val FIREBASE_STORAGE_BUCKET = "ottoai-8a61f.firebasestorage.app"
    private const val FIREBASE_API_KEY = "AIzaSyCyedaF3B1MNogD9AzL4WsSZjhWyDPl-f4"

    private var lastUploadedTimestampMs: Long = 0L

    /**
     * Uploads the latest session diagnostic log in the background.
     * Rate-limited to at most once per 60 seconds to avoid spamming.
     */
    suspend fun syncSessionLogAsync(
        context: Context,
        vehicleDetails: String?,
        vehicleVin: String?,
        triggerReason: String = "session_disconnect"
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastUploadedTimestampMs < 60_000L) {
            Log.d(TAG, "Sync skipped: uploaded within the last 60 seconds.")
            return@withContext false
        }

        val logFile = File(context.getExternalFilesDir(null), "vera_elm327_debug.log")
        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d(TAG, "No diagnostic log file available to sync.")
            return@withContext false
        }

        try {
            // 1. Read the most recent 1MB / ~10,000 lines of diagnostic activity to keep payload fast and concise
            val maxBytesToRead = 1_000_000L
            val fileLength = logFile.length()
            val startOffset = if (fileLength > maxBytesToRead) fileLength - maxBytesToRead else 0L

            val rawBytes = ByteArrayOutputStream()
            FileInputStream(logFile).use { fis ->
                if (startOffset > 0) {
                    fis.skip(startOffset)
                }
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    rawBytes.write(buffer, 0, bytesRead)
                }
            }

            // 2. Prepend Session Diagnostic Metadata Header
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val headerText = buildString {
                appendLine("=======================================================")
                appendLine("VERA AUTOMATED TESTER TELEMETRY LOG")
                appendLine("Timestamp: $dateStr (UTC $now)")
                appendLine("Trigger: $triggerReason")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                appendLine("Vehicle: ${vehicleDetails ?: "Unknown"}")
                appendLine("VIN: ${vehicleVin ?: "Unknown"}")
                appendLine("=======================================================\n")
            }

            // 3. Compress using GZIP
            val gzipStream = ByteArrayOutputStream()
            GZIPOutputStream(gzipStream).use { gzos ->
                gzos.write(headerText.toByteArray(Charsets.UTF_8))
                gzos.write(rawBytes.toByteArray())
            }
            val compressedBytes = gzipStream.toByteArray()

            // 4. Construct Destination Object Path
            val cleanVin = (vehicleVin ?: "UNKNOWN_VIN").replace(Regex("[^a-zA-Z0-9]"), "_").take(17)
            val cleanModel = (vehicleDetails ?: "Vehicle").replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
            val fileName = "telemetry_${cleanModel}_${cleanVin}_${dateStr}.log.gz"
            val objectPath = "diagnostic_logs/$fileName"
            val encodedObjectPath = java.net.URLEncoder.encode(objectPath, "UTF-8")

            // 5. Upload to Firebase Cloud Storage via REST API
            val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$FIREBASE_STORAGE_BUCKET/o?name=$encodedObjectPath&key=$FIREBASE_API_KEY"
            val url = URL(uploadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/gzip")
                setRequestProperty("x-goog-meta-trigger", triggerReason)
                setRequestProperty("x-goog-meta-app-version", BuildConfig.VERSION_NAME)
                setRequestProperty("x-goog-meta-vehicle", vehicleDetails ?: "Unknown")
                setRequestProperty("x-goog-meta-vin", vehicleVin ?: "Unknown")
            }

            conn.outputStream.use { os ->
                os.write(compressedBytes)
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                lastUploadedTimestampMs = now
                Log.i(TAG, "Diagnostic telemetry log automatically synced to cloud: $fileName (${compressedBytes.size} bytes).")
                return@withContext true
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "Telemetry upload responded with HTTP $responseCode: $err")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing diagnostic telemetry log: ${e.message}", e)
            return@withContext false
        }
    }
}
