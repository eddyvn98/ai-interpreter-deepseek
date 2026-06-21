package com.example.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SiliconFlowApiClient {
    private const val BASE_URL = "https://api.siliconflow.com/v1/audio/transcriptions"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun transcribeAudioFile(
        apiKey: String,
        audioBytes: ByteArray,
        filename: String = "audio.m4a",
        mimeType: String = "audio/m4a",
        modelName: String = "openai/whisper-large-v3-turbo"
    ): String {
        val fileBody = audioBytes.toRequestBody(mimeType.toMediaType())
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", modelName)
            .addFormDataPart("file", filename, fileBody)
            .build()

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val responseString = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("SiliconFlow ASR error ${resp.code}: $responseString")
            }
            val obj = JSONObject(responseString)
            return obj.optString("text", "").trim()
        }
    }
}
