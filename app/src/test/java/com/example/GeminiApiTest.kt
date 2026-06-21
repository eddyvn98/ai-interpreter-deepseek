package com.example

import com.example.data.api.GeminiApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeminiApiTest {

    @Test
    fun testGeminiTts() = runBlocking {
        // Attempt to find GEMINI_API_KEY
        var apiKey = System.getenv("GEMINI_API_KEY")?.trim() ?: ""
        
        if (apiKey.isEmpty()) {
            // Cwd of the test JVM runner is project/app/
            val parentEnv = File("../.env") // project root
            val rootEnv = File(".env") // app folder
            val envInSrc = File("src/test/java/.env") // fallback
            
            val targetEnv = when {
                parentEnv.exists() -> parentEnv
                rootEnv.exists() -> rootEnv
                envInSrc.exists() -> envInSrc
                else -> null
            }
            
            if (targetEnv != null) {
                println("Found .env file at absolute path: ${targetEnv.absolutePath}")
                val lines = targetEnv.readLines()
                // Find REAL_GEMINI_API_KEY first, fallback to GEMINI_API_KEY
                var foundReal = ""
                var foundLegacy = ""
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("REAL_GEMINI_API_KEY=")) {
                        foundReal = trimmed.substringAfter("REAL_GEMINI_API_KEY=").trim()
                    } else if (trimmed.startsWith("GEMINI_API_KEY=")) {
                        foundLegacy = trimmed.substringAfter("GEMINI_API_KEY=").trim()
                    }
                }
                apiKey = if (foundReal.isNotEmpty()) foundReal else foundLegacy
            } else {
                println("Could not find .env in CWD: ${File(".").absolutePath}")
            }
        }
        
        // Clean quotes from the key if any
        apiKey = apiKey.removeSurrounding("\"").removeSurrounding("'")

        println("Loaded API Key starting with: ${apiKey.take(8)}...")
        assertTrue("API Key is missing. Please set GEMINI_API_KEY in .env or system environment.", apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY")

        val testText = "Xin chào, đây là bản kiểm tra giọng nói từ hệ thống dịch trực tiếp Gemini."
        println("Generating speech for text: \"$testText\"")

        val audioBase64 = GeminiApiClient.generateTtsAudio(
            apiKey = apiKey,
            text = testText
        )

        assertNotNull("Gemini TTS API returned null audio output", audioBase64)
        assertTrue("Gemini TTS API returned empty audio data", audioBase64!!.isNotEmpty())

        println("Gemini TTS API call successful!")
        println("Received base64 audio data length: ${audioBase64.length} characters")
    }
}
