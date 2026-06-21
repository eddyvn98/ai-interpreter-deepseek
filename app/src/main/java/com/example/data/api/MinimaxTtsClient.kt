package com.example.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MinimaxTtsClient {
    private const val BASE_URL = "https://api-uw.minimax.io/v1/t2a_v2"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun generateSpeech(
        apiKey: String,
        text: String,
        voiceId: String = "English_magnetic_voiced_man",
        modelName: String = "speech-2.8-hd"
    ): ByteArray {
        val payload = JSONObject().apply {
            put("model", modelName)
            put("text", text)
            put("voice_setting", JSONObject().apply {
                put("voice_id", voiceId)
            })
            put("audio_setting", JSONObject().apply {
                put("audio_format", "mp3")
                put("sample_rate", 32000)
                put("bitrate", 128000)
            })
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorString = resp.body?.string().orEmpty()
                throw IllegalStateException("Minimax TTS error ${resp.code}: $errorString")
            }
            
            val contentType = resp.header("Content-Type") ?: ""
            if (contentType.contains("application/json")) {
                val responseString = resp.body?.string().orEmpty()
                val obj = JSONObject(responseString)
                val status = obj.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
                if (status != 0) {
                    val msg = obj.optJSONObject("base_resp")?.optString("status_msg", "") ?: ""
                    throw IllegalStateException("Minimax TTS API returned status $status: $msg")
                }
            }
            
            return resp.body?.bytes() ?: throw IllegalStateException("Minimax TTS response body is null")
        }
    }
}
