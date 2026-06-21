package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Request Classes ---

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val responseModalities: List<String>? = null,
    val speechConfig: SpeechConfig? = null,
    val temperature: Float? = null,
    val responseMimeType: String? = null
)

@JsonClass(generateAdapter = true)
data class SpeechConfig(
    val voiceConfig: VoiceConfig
)

@JsonClass(generateAdapter = true)
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@JsonClass(generateAdapter = true)
data class PrebuiltVoiceConfig(
    val voiceName: String
)

// --- Response Classes ---

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null
)

// --- Interface definition ---

interface GeminiApiService {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateWithFlash(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse

    @POST("v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
    suspend fun generateWithTts(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiApiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    val service: GeminiApiService by lazy {
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }

    suspend fun generateTtsAudio(
        apiKey: String,
        text: String,
        voiceName: String = "Aoede",
        modelName: String = "gemini-2.5-flash-preview-tts"
    ): String? {
        val prompt = "Đọc đoạn văn bản sau bằng Tiếng Việt. Tuyệt đối không thêm bất kỳ lời thoại, lời dẫn hay bình luận nào khác, chỉ phát ra giọng nói đọc chính xác nội dung văn bản này:\n$text"
        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(Part(text = prompt))
                )
            ),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(
                        prebuiltVoiceConfig = PrebuiltVoiceConfig(
                            voiceName = voiceName
                        )
                    )
                )
            )
        )
        return try {
            val response = if (modelName == "gemini-2.5-flash-preview-tts") {
                service.generateWithTts(apiKey, request)
            } else {
                service.generateWithFlash(apiKey, request)
            }
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it.inlineData != null }?.inlineData?.data
        } catch (e: Exception) {
            try {
                android.util.Log.e("GeminiApiClient", "Gemini TTS Generation failed", e)
            } catch (unused: RuntimeException) {
                println("GeminiApiClient: Gemini TTS Generation failed: ${e.localizedMessage}")
                e.printStackTrace()
            }
            null
        }
    }
}

