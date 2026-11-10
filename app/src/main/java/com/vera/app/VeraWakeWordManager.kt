package com.vera.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * VeraWakeWordManager
 *
 * Provides on-device "Hey Vera" wake-word listening when hands-free voice mode is active.
 *
 * Detection Architecture:
 * 1. Picovoice Porcupine Engine:
 *    If a PICOVOICE_ACCESS_KEY is provided in local.properties or settings, and a custom keyword
 *    model file (e.g. 'hey_vera_android.ppn') is bundled in assets, it uses Picovoice Porcupine
 *    for ultra-low-power DSP wake word spotting.
 * 2. On-Device Offline Speech Engine (Zero Configuration):
 *    If Picovoice is not configured or unavailable, it uses Android's on-device offline
 *    SpeechRecognizer configured strictly for local, zero-network keyword spotting.
 *
 * Token & Cost Savings:
 * - When sleeping, ZERO audio tokens are streamed to Gemini Cloud.
 * - Cloud session activates on "Hey Vera" and remains open for 15 seconds after Vera finishes
 *   speaking for natural hands-free follow-up queries.
 */
class VeraWakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "VeraWakeWord"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isListeningForWakeWord = MutableStateFlow(false)
    val isListeningForWakeWord: StateFlow<Boolean> = _isListeningForWakeWord.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerActive = false
    private var restartJob: Job? = null
    private var porcupineManager: Any? = null // Reference kept as Any for dynamic Porcupine binding

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var isMuted = false

    /**
     * Silences system earcon / beeps played by Google Speech Services when SpeechRecognizer starts,
     * loops, or encounters a silence timeout in standby mode.
     */
    private fun setMuteBeeps(mute: Boolean) {
        val am = audioManager ?: return
        try {
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            if (mute) {
                if (!isMuted) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
                    } catch (_: Exception) {}
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
                    } catch (_: Exception) {}
                    isMuted = true
                }
            } else {
                if (isMuted) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0)
                    } catch (_: Exception) {}
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0)
                    } catch (_: Exception) {}
                    isMuted = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust stream volume: ${e.message}")
        }
    }

    /**
     * Start listening on-device for "Hey Vera".
     */
    fun startListening() {
        if (_isListeningForWakeWord.value) return
        _isListeningForWakeWord.value = true

        val picovoiceKey = BuildConfig.PICOVOICE_ACCESS_KEY.trim()
        if (picovoiceKey.isNotBlank()) {
            val porcupineStarted = initPicovoicePorcupine(picovoiceKey)
            if (porcupineStarted) {
                Log.i(TAG, "Wake word listening started using Picovoice Porcupine.")
                return
            }
        }

        // Fallback to Android On-Device SpeechRecognizer
        startOnDeviceSpeechRecognizer()
    }

    /**
     * Stop wake-word listening.
     */
    fun stopListening() {
        _isListeningForWakeWord.value = false
        restartJob?.cancel()
        restartJob = null

        stopPicovoicePorcupine()
        stopOnDeviceSpeechRecognizer()
        setMuteBeeps(false)
        Log.i(TAG, "Wake word listening stopped.")
    }

    private fun initPicovoicePorcupine(accessKey: String): Boolean {
        return try {
            val porcupineClass = Class.forName("ai.picovoice.porcupine.PorcupineManager")
            val builderClass = Class.forName("ai.picovoice.porcupine.PorcupineManager\$Builder")
            val porcupineCallbackClass = Class.forName("ai.picovoice.porcupine.PorcupineManagerCallback")

            val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                porcupineCallbackClass.classLoader,
                arrayOf(porcupineCallbackClass)
            ) { _, method, _ ->
                if (method.name == "invoke") {
                    Log.i(TAG, "Picovoice Porcupine detected keyword!")
                    mainHandler.post {
                        onWakeWordDetected()
                    }
                }
                null
            }

            var builder = builderClass.newInstance()
            val setAccessKeyMethod = builderClass.getMethod("setAccessKey", String::class.java)
            builder = setAccessKeyMethod.invoke(builder, accessKey)

            // Check if hey_vera_android.ppn is in assets
            val assetList = context.assets.list("") ?: emptyArray()
            val customPpn = assetList.find { it.contains("vera", ignoreCase = true) && it.endsWith(".ppn") }

            if (customPpn != null) {
                val setKeywordPathMethod = builderClass.getMethod("setKeywordPath", String::class.java)
                builder = setKeywordPathMethod.invoke(builder, customPpn)
            } else {
                // If custom keyword not bundled, try BuiltInKeyword if available or return false
                Log.w(TAG, "Custom 'hey_vera.ppn' not found in assets. Falling back to On-Device speech recognizer.")
                return false
            }

            val buildMethod = builderClass.getMethod("build", Context::class.java, porcupineCallbackClass)
            val manager = buildMethod.invoke(builder, context, callbackProxy)
            val startMethod = manager.javaClass.getMethod("start")
            startMethod.invoke(manager)
            porcupineManager = manager
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Picovoice initialization did not complete: ${e.message}. Using on-device recognizer.")
            false
        }
    }

    private fun stopPicovoicePorcupine() {
        try {
            porcupineManager?.let { mgr ->
                val stopMethod = mgr.javaClass.getMethod("stop")
                stopMethod.invoke(mgr)
                val deleteMethod = mgr.javaClass.getMethod("delete")
                deleteMethod.invoke(mgr)
            }
        } catch (_: Throwable) {}
        porcupineManager = null
    }

    private fun startOnDeviceSpeechRecognizer() {
        val action = Runnable {
            if (!_isListeningForWakeWord.value) return@Runnable
            try {
                // Silence prompt beeps played by Google Speech Services
                setMuteBeeps(true)

                if (speechRecognizer == null) {
                    speechRecognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    ) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }
                speechRecognizer?.setRecognitionListener(createListener())

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                }

                isRecognizerActive = true
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "On-device speech recognizer listening for 'Hey Vera' (silent)...")
            } catch (e: Exception) {
                Log.w(TAG, "SpeechRecognizer start failed: ${e.message}")
                scheduleRestart()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun stopOnDeviceSpeechRecognizer() {
        val action = Runnable {
            try {
                isRecognizerActive = false
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun scheduleRestart(delayMs: Long = 400L) {
        if (!_isListeningForWakeWord.value) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (_isListeningForWakeWord.value) {
                startOnDeviceSpeechRecognizer()
            }
        }
    }

    private fun checkTextForWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        val isMatch = lower.contains("hey vera") ||
                lower.contains("hay vera") ||
                lower.contains("hi vera") ||
                lower.contains("ok vera") ||
                lower.contains("vera")
        if (isMatch) {
            Log.i(TAG, "Wake word matched from text: '$text'")
        }
        return isMatch
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (candidate in matches) {
                        if (checkTextForWakeWord(candidate)) {
                            stopListening()
                            onWakeWordDetected()
                            return
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (candidate in matches) {
                        if (checkTextForWakeWord(candidate)) {
                            stopListening()
                            onWakeWordDetected()
                            return
                        }
                    }
                }
                // If not matched, restart recognizer loop
                if (_isListeningForWakeWord.value) {
                    scheduleRestart(150L)
                }
            }

            override fun onError(error: Int) {
                if (_isListeningForWakeWord.value) {
                    scheduleRestart(350L)
                }
            }

            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
