package com.example.data.api

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object QwenApiClient {
    private const val BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun translateStream(
        apiKey: String,
        inputText: String,
        sourceLang: String,
        onDelta: (String) -> Unit
    ): String {
        val prompt = if (sourceLang == "EN") {
            "Translate English to Vietnamese. Return translation only.\nText: $inputText"
        } else {
            "Translate Vietnamese to English. Return translation only.\nText: $inputText"
        }

        val payload = JSONObject().apply {
            put("model", "qwen-mt-flash")
            put("stream", true)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val sb = StringBuilder()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Qwen API error ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
            }
            val body = resp.body ?: throw IllegalStateException("Qwen response body is null")
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content", "")
                    .orEmpty()
                if (delta.isNotEmpty()) {
                    sb.append(delta)
                    onDelta(delta)
                }
            }
        }
        return sb.toString().trim()
    }

    fun summarize(apiKey: String, prompt: String): String {
        val payload = JSONObject().apply {
            put("model", "qwen-omni-turbo")
            put("stream", false)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
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
                throw IllegalStateException("Qwen API error ${resp.code}")
            }
            val text = resp.body?.string().orEmpty()
            val obj = JSONObject(text)
            return obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
        }
    }

    fun transcribeAudio(
        apiKey: String,
        wavBytes: ByteArray,
        modelName: String = "qwen3-asr-flash-realtime"
    ): String {
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        
        // Build JSON Payload for Qwen ASR
        val payload = JSONObject().apply {
            put("model", modelName)
            put("stream", false)
            put("modalities", JSONArray().apply { put("text") })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", "data:audio/wav;base64,$b64")
                                put("format", "wav")
                            })
                        })
                    })
                })
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
                val errorBody = resp.body?.string().orEmpty()
                // Fallback to non-realtime model if realtime fails
                if (resp.code == 500 && modelName == "qwen3-asr-flash-realtime") {
                    return transcribeAudio(apiKey, wavBytes, "qwen3-asr-flash")
                }
                throw IllegalStateException("Qwen STT error ${resp.code}: $errorBody")
            }
            val text = resp.body?.string().orEmpty()
            val obj = JSONObject(text)
            return obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
        }
    }

    fun generateTtsAudio(
        apiKey: String,
        text: String,
        voiceName: String = "Nofish",
        languageType: String = "English",
        modelName: String = "qwen3-tts-flash"
    ): String? {
        val url = "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        val payload = JSONObject().apply {
            put("model", modelName)
            put("input", JSONObject().apply {
                put("text", text)
            })
            put("parameters", JSONObject().apply {
                put("voice", voiceName)
                put("format", "wav")
                put("sample_rate", 16000)
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Qwen TTS error ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
            }
            val responseString = resp.body?.string().orEmpty()
            val obj = JSONObject(responseString)
            val output = obj.optJSONObject("output") ?: return null
            val audio = output.optJSONObject("audio") ?: return null
            
            // Return base64 or download the audio URL if provided
            val base64Data = audio.optString("data", "")
            if (base64Data.isNotEmpty()) {
                return base64Data
            }
            val rawUrl = audio.optString("url", "")
            if (rawUrl.isNotEmpty()) {
                val downloadUrl = if (rawUrl.startsWith("http://")) rawUrl.replace("http://", "https://") else rawUrl
                val downloadRequest = Request.Builder().url(downloadUrl).build()
                httpClient.newCall(downloadRequest).execute().use { downloadResp ->
                    if (downloadResp.isSuccessful) {
                        val bytes = downloadResp.body?.bytes()
                        if (bytes != null) {
                            return Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    }
                }
            }
            return null
        }
    }

    fun transcribeAudioFile(
        apiKey: String,
        audioBytes: ByteArray,
        format: String = "m4a",
        modelName: String = "qwen3-asr-flash"
    ): String {
        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("model", modelName)
            put("stream", false)
            put("modalities", JSONArray().apply { put("text") })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", "data:audio/$format;base64,$b64")
                                put("format", format)
                            })
                        })
                    })
                })
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
                val errorBody = resp.body?.string().orEmpty()
                throw IllegalStateException("Qwen ASR error ${resp.code}: $errorBody")
            }
            val text = resp.body?.string().orEmpty()
            val obj = JSONObject(text)
            return obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
        }
    }

    fun refineAndTranslateTranscript(
        apiKey: String,
        rawTranscript: String,
        durationSec: Int
    ): QwenApiResult {
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        val durationStr = String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        val prompt = """
            Bạn là một trợ lý ghi biên bản cuộc họp chuyên nghiệp.
            Dưới đây là bản ghi nhận diện giọng nói thô (chứa cả tiếng Việt và tiếng Anh) của một cuộc họp kéo dài khoảng $durationStr (tổng cộng $durationSec giây).
            
            Nhiệm vụ của bạn:
            1. Giữ nguyên ngôn ngữ gốc được nói (tiếng Việt giữ nguyên tiếng Việt, tiếng Anh giữ nguyên tiếng Anh, tuyệt đối không dịch sang tiếng khác).
            2. Chia nhỏ văn bản thành các đoạn văn ngắn hợp lý và tự động ước lượng mốc thời gian bắt đầu của từng đoạn dưới dạng [phút:giây] (ví dụ: [00:00], [01:30], [03:15],...) dựa vào cấu trúc cuộc họp và tổng thời lượng $durationStr.
            3. Định dạng kết quả sạch sẽ, chuyên nghiệp, tự động xuống dòng giữa các đoạn để người dùng dễ đọc và cuộn theo dõi.
            
            Bản ghi thô:
            $rawTranscript
            
            Kết quả biên bản cuộc họp và chia mốc thời gian:
        """.trimIndent()
        val payload = JSONObject().apply {
            put("model", "qwen-turbo")
            put("stream", false)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
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
                throw IllegalStateException("Qwen refinement error ${resp.code}")
            }
            val text = resp.body?.string().orEmpty()
            val obj = JSONObject(text)
            val content = obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                .orEmpty()
                .trim()
            val usage = obj.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            return QwenApiResult(content, promptTokens, completionTokens)
        }
    }

    fun executeOmniFlow(
        apiKey: String,
        audioBytes: ByteArray,
        format: String = "m4a",
        voiceName: String = "Ethan",
        modelName: String = "qwen3-omni-flash"
    ): OmniResult {
        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        
        val payload = JSONObject().apply {
            put("model", modelName)
            put("stream", true) // MUST BE TRUE to retrieve audio data from Qwen Omni API!
            put("modalities", JSONArray().apply {
                put("text")
                put("audio")
            })
            put("audio", JSONObject().apply {
                put("voice", voiceName)
                put("format", "wav")
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", "data:audio/$format;base64,$b64")
                                put("format", format)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Translate my spoken Vietnamese speech into English. Reply directly in spoken English. Do not add any conversational filler or meta-text, just speak the translated English sentence.")
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val textBuilder = StringBuilder()
        val audioBuilder = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val responseString = resp.body?.string().orEmpty()
                throw IllegalStateException("Qwen Omni error ${resp.code}: $responseString")
            }
            val body = resp.body ?: throw IllegalStateException("Qwen response body is null")
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                
                // Parse choices delta
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                if (delta != null) {
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        textBuilder.append(content)
                    }
                    val audioObj = delta.optJSONObject("audio")
                    if (audioObj != null) {
                        val audioData = audioObj.optString("data", "")
                        if (audioData.isNotEmpty()) {
                            audioBuilder.append(audioData)
                        }
                    }
                }
                
                // Parse token usage if present (typically in the final chunks of the stream)
                val usage = json.optJSONObject("usage")
                if (usage != null) {
                    promptTokens = usage.optInt("prompt_tokens", 0)
                    completionTokens = usage.optInt("completion_tokens", 0)
                }
            }
        }

        return OmniResult(textBuilder.toString().trim(), audioBuilder.toString(), promptTokens, completionTokens)
    }
}

data class QwenApiResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int
)

data class OmniResult(
    val text: String,
    val audioBase64: String,
    val promptTokens: Int,
    val completionTokens: Int
)



