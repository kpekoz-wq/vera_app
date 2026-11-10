package com.vera.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * VeraKillSwitch
 *
 * Protects developer API quota and cloud spend by allowing remote disabling of test copies,
 * enforcing maximum build expiration dates, and displaying custom cutoff notices.
 *
 * Check Strategy:
 * 1. Hard Expiration Date (offline fallback): Builds are automatically deactivated after a
 *    set date (e.g. 60 days from release) even if the device is offline.
 * 2. Remote Kill Switch Endpoint: Queries a remote JSON config (raw GitHub / Gist / Cloudflare).
 *    Config Schema:
 *    {
 *       "kill_switch_active": false,
 *       "message": "This VERA test build has been decommissioned by the developer.",
 *       "min_version_code": 1
 *    }
 */
object VeraKillSwitch {

    private const val TAG = "VeraKillSwitch"

    /**
     * Remote URL containing kill switch JSON status.
     * Developers can toggle "kill_switch_active": true at any time to instantly revoke all active test copies.
     * Uses GitHub raw content from the repo or public gist.
     */
    const val REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/pekoz/OttoAI/master/vera_remote_config.json"

    // Hard fallback expiration date (May 15, 2027 UTC - plenty of runway for current development)
    const val HARD_EXPIRATION_TIMESTAMP_MS: Long = 1810339200000L

    private val _isDeactivated = MutableStateFlow(false)
    val isDeactivated: StateFlow<Boolean> = _isDeactivated.asStateFlow()

    private val _deactivationMessage = MutableStateFlow<String?>(null)
    val deactivationMessage: StateFlow<String?> = _deactivationMessage.asStateFlow()

    fun checkHardcodedExpiration(): Boolean {
        val now = System.currentTimeMillis()
        if (now > HARD_EXPIRATION_TIMESTAMP_MS) {
            val msg = "This test build of VERA expired on May 15, 2027. Please contact the developer for an updated build."
            _isDeactivated.value = true
            _deactivationMessage.value = msg
            Log.w(TAG, "Hard build expiration reached: $msg")
            return true
        }
        return false
    }

    suspend fun checkRemoteKillSwitch(context: Context): Boolean {
        // First check offline hard expiration
        if (checkHardcodedExpiration()) return true

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(REMOTE_CONFIG_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    useCaches = false
                }

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)

                    val isKilled = json.optBoolean("kill_switch_active", false)
                    val customMsg = json.optString("message", "This test build of VERA has been disabled by the developer.")
                    val minVersionCode = json.optInt("min_version_code", 1)

                    val currentVersionCode = try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                    } catch (_: Exception) { 1 }

                    if (isKilled || currentVersionCode < minVersionCode) {
                        withContext(Dispatchers.Main) {
                            _isDeactivated.value = true
                            _deactivationMessage.value = customMsg
                        }
                        Log.w(TAG, "Remote kill switch triggered! Deactivating AI cloud features.")
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                // If offline or network unavailable, don't crash; rely on hard expiration check
                Log.d(TAG, "Remote kill switch check bypassed or offline: ${e.message}")
            }
            false
        }
    }
}
