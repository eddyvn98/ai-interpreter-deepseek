package com.example.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GoogleTranslateApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return ""
        val src = sourceLang.uppercase()
        val tgt = targetLang.uppercase()

        val gtxSource = when (src) {
            "ZH" -> "zh-CN"
            "YUE" -> "zh-HK"
            else -> src.lowercase()
        }
        val gtxTarget = tgt.lowercase()

        return translateDirect(text, gtxSource, gtxTarget)
    }

    private fun translateDirect(text: String, sl: String, tl: String): String {
        if (text.isBlank()) return ""
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encodedText"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Google Translate API error: ${response.code}")
                val body = response.body?.string() ?: return ""
                
                val jsonArray = JSONArray(body)
                val firstArray = jsonArray.optJSONArray(0) ?: return ""
                val sb = StringBuilder()
                for (i in 0 until firstArray.length()) {
                    val part = firstArray.optJSONArray(i)
                    val translatedPart = part?.optString(0)
                    if (!translatedPart.isNullOrEmpty()) {
                        sb.append(translatedPart)
                    }
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
