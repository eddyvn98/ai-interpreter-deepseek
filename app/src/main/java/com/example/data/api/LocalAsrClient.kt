package com.example.data.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "LocalAsrClient"

/**
 * HTTP client for the on-device Whisper ASR server.
 * Calls POST /v1/audio/transcriptions (OpenAI-compatible multipart form).
 *
 * The server auto-detects language (EN+VI mixed) when [language] is null or "auto".
 */
object LocalAsrClient {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Transcribe an audio file using the local ASR server.
     *
     * @param baseUrl   Base URL of the local server, e.g. "http://192.168.1.x:8000/v1"
     * @param audioBytes Raw audio bytes (m4a, wav, etc.)
     * @param filename   Filename hint for the server (e.g. "audio.m4a")
     * @param mimeType   MIME type of the audio (e.g. "audio/m4a")
     * @param model      Whisper model to use, default "medium"
     * @param language   Language code ("en", "vi") or null/"auto" for auto-detect
     * @param task       "transcribe" (default) or "translate" (outputs English from any language)
     * @return Transcribed/translated text, or empty string on failure
     */
    fun transcribeAudioFile(
        baseUrl: String,
        audioBytes: ByteArray,
        filename: String = "audio.m4a",
        mimeType: String = "audio/m4a",
        model: String = "medium",
        language: String? = null,
        task: String = "transcribe"
    ): String {
        // Normalize base URL: strip trailing slash, ensure endpoint path
        val normalizedBase = baseUrl.trimEnd('/')
        val endpoint = if (normalizedBase.endsWith("/v1")) {
            "$normalizedBase/audio/transcriptions"
        } else {
            "$normalizedBase/v1/audio/transcriptions"
        }

        Log.d(TAG, "Transcribing ${audioBytes.size} bytes via local server: $endpoint (model=$model, language=$language)")

        val fileBody = audioBytes.toRequestBody(mimeType.toMediaType())
        val formBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("task", task)
            .addFormDataPart("file", filename, fileBody)

        // Only add language field if explicitly specified — omitting it lets server use auto-detect
        if (!language.isNullOrBlank() && language.lowercase() != "auto") {
            formBuilder.addFormDataPart("language", language)
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(formBuilder.build())
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Local ASR server error ${resp.code}: $body")
                    throw IllegalStateException("Local ASR error ${resp.code}: $body")
                }
                val text = JSONObject(body).optString("text", "").trim()
                Log.d(TAG, "Local ASR result: $text")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local ASR request failed", e)
            throw e
        }
    }

    data class AsrResponse(val text: String, val speaker: String?)

    fun transcribeSegment(
        baseUrl: String,
        audioBytes: ByteArray,
        filename: String = "audio.wav",
        mimeType: String = "audio/wav",
        model: String = "medium",
        language: String? = null,
        task: String = "transcribe",
        sessionId: String? = null,
        segmentIndex: Int? = null
    ): AsrResponse {
        val normalizedBase = baseUrl.trimEnd('/')
        val endpoint = if (normalizedBase.endsWith("/v1")) {
            "$normalizedBase/audio/transcriptions"
        } else {
            "$normalizedBase/v1/audio/transcriptions"
        }

        Log.d(TAG, "Transcribing segment index=$segmentIndex for session=$sessionId via local server: $endpoint")

        val fileBody = audioBytes.toRequestBody(mimeType.toMediaType())
        val formBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("task", task)
            .addFormDataPart("file", filename, fileBody)

        if (!language.isNullOrBlank() && language.lowercase() != "auto") {
            formBuilder.addFormDataPart("language", language)
        }
        if (sessionId != null) {
            formBuilder.addFormDataPart("session_id", sessionId)
        }
        if (segmentIndex != null) {
            formBuilder.addFormDataPart("segment_index", segmentIndex.toString())
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(formBuilder.build())
            .build()

        return httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Local ASR segment error ${resp.code}: $body")
                throw IllegalStateException("Local ASR error ${resp.code}: $body")
            }
            val json = JSONObject(body)
            val text = json.optString("text", "").trim()
            val speaker = if (json.has("speaker") && !json.isNull("speaker")) json.getString("speaker") else null
            Log.d(TAG, "Local ASR segment result: speaker=$speaker, text=$text")
            AsrResponse(text, speaker)
        }
    }

    /**
     * Synthesize English text into voice-cloned WAV audio (base64) using the local server.
     */
    fun synthesizeCloneVoice(
        baseUrl: String,
        text: String
    ): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val endpoint = if (normalizedBase.endsWith("/v1")) {
            "$normalizedBase/audio/synthesize_clone"
        } else {
            "$normalizedBase/v1/audio/synthesize_clone"
        }

        Log.d(TAG, "Synthesizing clone voice for text length=${text.length} via local server: $endpoint")

        val formBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)

        val request = Request.Builder()
            .url(endpoint)
            .post(formBuilder.build())
            .build()

        return httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Local clone synthesis server error ${resp.code}: $body")
                throw IllegalStateException("Local TTS synthesis error ${resp.code}: $body")
            }
            val json = JSONObject(body)
            if (json.optBoolean("success", false) || json.has("audio_base64")) {
                json.getString("audio_base64")
            } else {
                throw IllegalStateException("API returned failure: $body")
            }
        }
    }
}

