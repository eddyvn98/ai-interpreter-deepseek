package com.example.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RealtimeAsr"

/**
 * Streams raw PCM-16 mono 16kHz audio frames to ASR WebSocket API (local)
 * and delivers real-time word-by-word transcription updates.
 */
class RealtimeAsrClient(private val apiKey: String, private val baseUrl: String) {

    private var webSocket: WebSocket? = null
    private val isTaskStarted = AtomicBoolean(false)
    private val isFinished = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Keep open for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var onPartialCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String) -> Unit)? = null
    /** Called on final event with (enText, viText). viText may be empty if server did not translate. */
    private var onFinalWithViTextCallback: ((String, String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onReadyCallback: () -> Unit = {}

    fun connect(
        model: String = "medium",
        language: String = "auto",
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onFinalWithViText: ((String, String) -> Unit)? = null,
        onError: (String) -> Unit,
        onReady: () -> Unit
    ) {
        onPartialCallback = onPartial
        onFinalCallback = onFinal
        onFinalWithViTextCallback = onFinalWithViText
        onErrorCallback = onError
        onReadyCallback = onReady

        // Format baseUrl to WebSocket protocols: http -> ws, https -> wss
        var wsUrl = baseUrl.trim()
        if (wsUrl.startsWith("http://")) {
            wsUrl = wsUrl.replaceFirst("http://", "ws://")
        } else if (wsUrl.startsWith("https://")) {
            wsUrl = wsUrl.replaceFirst("https://", "wss://")
        }
        
        // Append ASR endpoint if not present
        if (!wsUrl.contains("/audio/stream")) {
            wsUrl = if (wsUrl.endsWith("/")) "${wsUrl}audio/stream" else "$wsUrl/audio/stream"
        }

        // Add query parameters for model and language
        wsUrl = if (wsUrl.contains("?")) {
            "$wsUrl&model=$model&language=$language"
        } else {
            "$wsUrl?model=$model&language=$language"
        }

        Log.d(TAG, "Connecting to ASR WebSocket URL: $wsUrl")

        val requestBuilder = Request.Builder().url(wsUrl)
        if (apiKey.isNotEmpty() && apiKey != "MY_SILICONFLOW_API_KEY") {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = requestBuilder.build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "ASR WebSocket connection opened")
                isTaskStarted.set(true)
                onReadyCallback()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ASR WebSocket failure: ${t.message}", t)
                if (!isFinished.get()) {
                    onErrorCallback?.invoke("Lỗi kết nối ASR: ${t.localizedMessage ?: "Unknown"}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ASR WebSocket closed: code=$code, reason=$reason")
                isTaskStarted.set(false)
            }
        })
    }

    private fun handleServerMessage(text: String) {
        runCatching {
            val json = JSONObject(text)
            val event = json.optString("event")
            if (event == "result") {
                val words = json.optString("text").trim()
                val isFinal = json.optBoolean("is_final", false)
                val viText = json.optString("vi_text", "").trim()
                if (words.isNotEmpty()) {
                    if (isFinal) {
                        Log.d(TAG, "Final EN: $words | VI: $viText")
                        // Prefer the combined callback if registered, fallback to simple onFinal
                        if (onFinalWithViTextCallback != null) {
                            onFinalWithViTextCallback?.invoke(words, viText)
                        } else {
                            onFinalCallback?.invoke(words)
                        }
                    } else {
                        Log.d(TAG, "Partial result: $words")
                        onPartialCallback?.invoke(words)
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Error parsing ASR WebSocket message: $text", it)
        }
    }

    /**
     * Send raw short array PCM frames directly over WebSocket binary channel.
     */
    fun sendAudioFrame(pcm: ShortArray, length: Int) {
        if (!isTaskStarted.get() || isFinished.get()) return
        val ws = webSocket ?: return

        // Convert ShortArray → ByteArray little-endian
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        
        ws.send(bytes.toByteString())
    }

    /**
     * Terminate ASR session.
     */
    fun finish() {
        if (!isFinished.compareAndSet(false, true)) return
        isTaskStarted.set(false)
        webSocket?.close(1000, "done")
        webSocket = null
    }

    fun isConnected() = isTaskStarted.get() && !isFinished.get()
}
