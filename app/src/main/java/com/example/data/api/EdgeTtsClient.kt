package com.example.data.api

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object EdgeTtsClient {
    private const val TAG = "EdgeTtsClient"
    private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    private fun getServerTime(): Long {
        try {
            val request = Request.Builder()
                .url("https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1")
                .head()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val dateHeader = response.header("Date")
                if (!dateHeader.isNullOrBlank()) {
                    val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
                    val date = sdf.parse(dateHeader)
                    if (date != null) {
                        val serverUnix = date.time / 1000L
                        Log.d(TAG, "Synchronized server time: $serverUnix (GMT)")
                        return serverUnix
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get server time from Microsoft, using local clock fallback", e)
        }
        return System.currentTimeMillis() / 1000L
    }

    private fun generateSecMSGec(): String {
        val winEpoch = 11644473600L
        val trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val currentUnix = getServerTime()
        val ticks = (currentUnix + winEpoch) * 10000000L
        
        // Round down to the nearest 3,000,000,000 ticks (5 minutes in 100-ns units)
        val timeWindow = ticks - (ticks % 3000000000L)
        
        val input = "$timeWindow$trustedClientToken"
        
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun generateSpeech(text: String, voice: String = "en-US-JennyNeural"): ByteArray {
        val secGec = generateSecMSGec()
        val secGecVersion = "1-130.0.2849.68"
        val urlWithParams = "$WSS_URL&Sec-MS-GEC=$secGec&Sec-MS-GEC-Version=$secGecVersion"

        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(urlWithParams)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .addHeader("Origin", "chrome-extension://jdiccldimpdaibdccjnbbopoifgfbmhh")
                .build()

            val audioStream = ByteArrayOutputStream()
            val uuid = UUID.randomUUID().toString().replace("-", "")

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Edge TTS WebSocket opened")
                    // Send Config Message
                    val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"system\":{\"name\":\"SpeechSDK\",\"version\":\"1.12.1-rc.1\",\"build\":\"JavaScript\",\"lang\":\"JavaScript\",\"os\":{\"platform\":\"Browser/Win32\",\"name\":\"Chrome\",\"version\":\"120.0.0.0\"}}}}"
                    webSocket.send(configMsg)

                    // Send SSML Message
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                            "<voice name='$voice'>$text</voice>" +
                            "</speak>"
                    val ssmlMsg = "X-RequestId:$uuid\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-OutputFormat:audio-24khz-48kbitrate-mono-mp3\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, textMessage: String) {
                    if (textMessage.contains("Path:turn.end")) {
                        Log.d(TAG, "Edge TTS synthesis finished")
                        webSocket.close(1000, "Done")
                        if (continuation.isActive) {
                            continuation.resume(audioStream.toByteArray())
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val buffer = ByteBuffer.wrap(bytes.toByteArray())
                    if (buffer.remaining() < 2) return
                    val headerLength = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() < headerLength) return
                    val headerBytes = ByteArray(headerLength)
                    buffer.get(headerBytes)
                    val headerText = String(headerBytes, Charsets.UTF_8)
                    
                    if (headerText.contains("Path:audio")) {
                        val audioBytes = ByteArray(buffer.remaining())
                        buffer.get(audioBytes)
                        audioStream.write(audioBytes)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Edge TTS WebSocket failure", t)
                    webSocket.close(1001, "Failure")
                    if (continuation.isActive) {
                        continuation.resumeWithException(t)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, "Normal closure")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) {
                        continuation.resume(audioStream.toByteArray())
                    }
                }
            }

            val webSocket = httpClient.newWebSocket(request, listener)
            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }
    }
}
