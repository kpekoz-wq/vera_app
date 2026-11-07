package com.ottoai.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages real-time bidirectional audio streaming with Google's Gemini Live API over WebSockets.
 * Streams 16kHz PCM audio from microphone to Gemini Live, and plays back 24kHz PCM native human
 * AI voice speech (using voice 'Fenrir') over phone or Bluetooth speakers.
 */
class GeminiLiveStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveStream"
        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite streaming
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var playbackJob: Job? = null
    private var sessionTimeoutJob: Job? = null
    private var audioPlaybackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _isGeminiSpeaking = MutableStateFlow(false)
    val isGeminiSpeaking: StateFlow<Boolean> = _isGeminiSpeaking.asStateFlow()

    private val _lastTranscriptText = MutableStateFlow("")
    val lastTranscriptText: StateFlow<String> = _lastTranscriptText.asStateFlow()

    private val _voiceName = MutableStateFlow("Aoede")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    fun setVoice(name: String) {
        _voiceName.value = name
    }

    @SuppressLint("MissingPermission")
    fun startLiveSession(
        apiKey: String,
        systemInstruction: String,
        telemetrySummary: String,
        onMessageReceived: (String) -> Unit,
        onToolCall: ((String, String, JSONObject) -> JSONObject)? = null
    ) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot start Gemini Live: API key is blank.")
            return
        }
        stopLiveSession()

        // 10-minute maximum session length limit to prevent unintended costs
        sessionTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(10 * 60 * 1000L)
            Log.i(TAG, "10-minute Gemini Live session limit reached. Stopping session automatically.")
            stopLiveSession()
        }

        // Multimodal Live API WebSocket URL
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        initAudioPlayer()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket opened successfully.")
                _isLiveSessionActive.value = true

                // Start Streaming Microphone PCM Audio Chunks (Before Setup)
                startMicrophoneStreaming(ws)

                // Send Setup Frame with Voice Configuration, Tools, & Vehicle System Instruction
                sendSetupFrame(ws, systemInstruction, telemetrySummary, _voiceName.value)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(ws, text, onMessageReceived, onToolCall)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket Error: ${t.message}", t)
                stopLiveSession()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closing: $reason ($code)")
                stopLiveSession()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed.")
                _isLiveSessionActive.value = false
            }
        })
    }

    private fun sendSetupFrame(
        ws: WebSocket,
        systemInstruction: String,
        telemetrySummary: String,
        selectedVoice: String
    ) {
        val fullInstruction = """
            $systemInstruction
            
            CURRENT LIVE VEHICLE TELEMETRY SUMMARY:
            $telemetrySummary
            
            VOICE DIRECTIVE: Speak naturally in plain English like a real human mechanic sitting in the passenger seat.
        """.trimIndent()

        val toolsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("functionDeclarations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "get_live_data")
                        put("description", "Returns current live vehicle engine telemetry data snapshot (RPM, speed, coolant temp, etc.).")
                    })
                    put(JSONObject().apply {
                        put("name", "get_dtcs")
                        put("description", "Returns current active Diagnostic Trouble Codes (DTCs) logged by the vehicle ECU.")
                    })
                    put(JSONObject().apply {
                        put("name", "get_mode06_results")
                        put("description", "Returns Mode 06 on-board diagnostic monitor test results (misfire counters, EVAP limits, O2 sensor monitors).")
                    })
                    put(JSONObject().apply {
                        put("name", "get_vehicle_info")
                        put("description", "Returns vehicle identification number (VIN) and decoded make, model, year information.")
                    })
                    put(JSONObject().apply {
                        put("name", "get_obdb_signals")
                        put("description", "Returns community-sourced OBDb parameters and custom PID telemetry readings.")
                    })
                })
            })
        }

        val setupObj = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-2.0-flash-realtime-exp")
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
                        put("TEXT")
                        put("AUDIO") 
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", selectedVoice)
                            })
                        })
                    })
                })
            })
        }

        ws.send(setupObj.toString())
        
        Log.i(TAG, "Sent Gemini Live Setup frame with tools (Voice: $selectedVoice).")
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(ws: WebSocket) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufSize, 6400)
        )

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            return
        }

        recordJob = scope.launch {
            val pcmChunkSize = 6400 // ~200ms of 16kHz 16-bit mono audio
            val readBuffer = ByteArray(2048)
            val accumStream = java.io.ByteArrayOutputStream()

            while (_isLiveSessionActive.value && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readBytes = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                if (readBytes > 0) {
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
                }
            }
        }
    }

    private fun initAudioPlayer() {
        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufSize, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        
        // Recreate the channel for a new session
        audioPlaybackChannel.close()
        audioPlaybackChannel = Channel(Channel.UNLIMITED)
        
        playbackJob?.cancel()
        playbackJob = scope.launch {
            for (pcmBytes in audioPlaybackChannel) {
                if (_isLiveSessionActive.value) {
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                }
            }
        }
    }

    private fun handleServerMessage(
        ws: WebSocket,
        jsonText: String,
        onMessageReceived: (String) -> Unit,
        onToolCall: ((String, String, JSONObject) -> JSONObject)?
    ) {
        try {
            val root = JSONObject(jsonText)
            val serverContent = root.optJSONObject("serverContent") ?: root.optJSONObject("server_content")
            
            if (serverContent != null) {
                val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue

                            // Check for text transcript
                            val text = part.optString("text", "")
                            if (text.isNotBlank()) {
                                _lastTranscriptText.value = text
                                onMessageReceived(text)
                            }

                            // Check for inline PCM audio bytes
                            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                            if (inlineData != null) {
                                val b64Data = inlineData.optString("data", "")
                                if (b64Data.isNotBlank()) {
                                    val pcmBytes = Base64.decode(b64Data, Base64.DEFAULT)
                                    _isGeminiSpeaking.value = true
                                    audioPlaybackChannel.trySend(pcmBytes)
                                }
                            }

                            // Check for tool / function call requests
                            val funcCall = part.optJSONObject("functionCall") ?: part.optJSONObject("function_call")
                            if (funcCall != null && onToolCall != null) {
                                val name = funcCall.optString("name", "")
                                val callId = funcCall.optString("id", "")
                                val args = funcCall.optJSONObject("args") ?: JSONObject()

                                Log.i(TAG, "Gemini Live requested tool execution: $name ($callId)")
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
                        }
                    }
                }
                
                val turnComplete = serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("turn_complete", false)
                if (turnComplete) {
                    _isGeminiSpeaking.value = false
                }
            } else {
                 Log.d(TAG, "Server message received without serverContent: $jsonText")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling Gemini Live server message", e)
        }
    }

    fun stopLiveSession() {
        _isLiveSessionActive.value = false
        _isGeminiSpeaking.value = false

        sessionTimeoutJob?.cancel()
        sessionTimeoutJob = null

        recordJob?.cancel()
        recordJob = null
        
        playbackJob?.cancel()
        playbackJob = null
        audioPlaybackChannel.close()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        webSocket?.close(1000, "User stopped live session")
        webSocket = null
        Log.i(TAG, "Stopped Gemini Live Session.")
    }
}
