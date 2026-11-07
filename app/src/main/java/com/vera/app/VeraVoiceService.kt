package com.vera.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground Service that keeps CPU, AudioRecord, and Bluetooth OBD2 communication active
 * when the phone screen turns dark or sleeps while in hands-free Gemini Live Voice mode.
 */
class VeraVoiceService : Service() {

    companion object {
        private const val TAG = "VeraVoiceService"
        private const val CHANNEL_ID = "vera_voice_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START_VOICE = "com.vera.app.ACTION_START_VOICE"
        const val ACTION_STOP_VOICE = "com.vera.app.ACTION_STOP_VOICE"

        var onStopRequested: (() -> Unit)? = null

        fun startService(context: Context) {
            val intent = Intent(context, VeraVoiceService::class.java).apply {
                action = ACTION_START_VOICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VeraVoiceService::class.java).apply {
                action = ACTION_STOP_VOICE
            }
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire a PARTIAL_WAKE_LOCK to prevent CPU sleep when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "vera:live_voice_wakelock"
        )?.apply {
            setReferenceCounted(false)
            acquire()
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for hands-free driving voice session.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VOICE) {
            Log.i(TAG, "Stop voice action received in VeraVoiceService.")
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            type
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
            Log.i(TAG, "VeraVoiceService started in foreground successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground in VeraVoiceService", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Released PARTIAL_WAKE_LOCK.")
            }
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "VeraVoiceService destroyed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VERA Voice Assistant"
            val descriptionText = "Keeps voice conversation active while phone screen is off or asleep"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VeraVoiceService::class.java).apply {
            action = ACTION_STOP_VOICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VERA Live Mechanic Active")
            .setContentText("Monitoring vehicle & listening hands-free in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Voice", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
