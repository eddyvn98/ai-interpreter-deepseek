package com.example.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeepSeekApiClient {
    private const val DEFAULT_BASE_URL = "https://api.deepseek.com/chat/completions"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun translateStream(
        apiKey: String,
        baseUrl: String?,
        inputText: String,
        sourceLang: String,
        onDelta: (String) -> Unit
    ): String {
        val url = if (!baseUrl.isNullOrBlank()) {
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        } else {
            DEFAULT_BASE_URL
        }

        val prompt = if (sourceLang == "EN") {
            "Translate English to Vietnamese. Return translation only.\nUse standard Vietnamese word spacing with spaces between words. Do not concatenate syllables.\nText: $inputText"
        } else {
            "Translate Vietnamese to English. Return translation only.\nUse standard English word spacing. Do not concatenate words.\nText: $inputText"
        }

        val streamed = requestChatCompletion(
            url = url,
            apiKey = apiKey,
            prompt = prompt,
            stream = true,
            onDelta = onDelta
        )
        if (streamed.isNotBlank() && !needsSpacingFallback(streamed, sourceLang)) {
            return streamed
        }

        // Streaming responses can occasionally arrive with an unexpected chunk shape.
        // Fall back to the non-streaming response so the UI still shows a translation.
        return requestChatCompletion(
            url = url,
            apiKey = apiKey,
            prompt = prompt,
            stream = false,
            onDelta = onDelta
        ).ifBlank { "Không có kết quả dịch." }
    }

    private fun requestChatCompletion(
        url: String,
        apiKey: String,
        prompt: String,
        stream: Boolean,
        onDelta: (String) -> Unit
    ): String {
        val payload = JSONObject().apply {
            put("model", "deepseek-v4-flash")
            put("stream", stream)
            put("temperature", 0.0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("DeepSeek API error ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
            }

            if (stream) {
                parseStreamResponse(resp, onDelta)
            } else {
                parseJsonResponse(resp)
            }
        }
    }

    private fun parseStreamResponse(resp: Response, onDelta: (String) -> Unit): String {
        val body = resp.body ?: throw IllegalStateException("DeepSeek response body is null")
        val sb = StringBuilder()
        val source = body.source()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
            val deltaObj = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
            val delta = deltaObj?.opt("content") as? String
            if (!delta.isNullOrEmpty()) {
                sb.append(delta)
                onDelta(delta)
            }
        }
        return normalizeTranslatedText(sb.toString())
    }

    private fun parseJsonResponse(resp: Response): String {
        val body = resp.body ?: throw IllegalStateException("DeepSeek response body is null")
        val json = JSONObject(body.string())
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            .orEmpty()
        return normalizeTranslatedText(content)
    }

    private fun normalizeTranslatedText(text: String): String {
        return text
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun needsSpacingFallback(text: String, sourceLang: String): Boolean {
        if (sourceLang != "EN") return false
        if (text.length < 18) return false
        val spaceCount = text.count { it == ' ' }
        return spaceCount == 0
    }
}
