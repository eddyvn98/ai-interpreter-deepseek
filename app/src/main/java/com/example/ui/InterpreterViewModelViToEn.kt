package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.LocalAsrClient
import com.example.overlay.OverlayCaptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "InterpreterVMViToEn"

internal fun InterpreterViewModel.startBatchRecording() {
    val context = getApplication<Application>()
    val cacheDir = context.cacheDir
    val file = File(cacheDir, "ptt_vi_to_en.m4a")
    batchRecordFile = file
    if (file.exists()) {
        file.delete()
    }

    try {
        batchMediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.media.MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }.apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recordingStartTime = System.currentTimeMillis()
        _isListening.value = true
        _micStatus.value = "Đang nghe..."

        // Periodically check amplitude to update visualizer wave
        batchRecordJob?.cancel()
        batchRecordJob = viewModelScope.launch(Dispatchers.Main) {
            while (_isListening.value) {
                delay(100)
                batchMediaRecorder?.let { mr ->
                    runCatching {
                        val maxAmp = mr.maxAmplitude
                        val normalized = minOf(1f, (maxAmp.toFloat() / 32768f) * 10f)
                        _micLevel.value = normalized
                    }
                }
            }
        }
        Log.d(TAG, "Batch recording started: ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start batch recording", e)
        _errorMessage.value = "Lỗi khởi động ghi âm: ${e.localizedMessage}"
        _micStatus.value = "Lỗi ghi âm"
        _isListening.value = false
        batchMediaRecorder = null
    }
}

internal fun InterpreterViewModel.stopBatchRecordingAndProcess() {
    val recorder = batchMediaRecorder
    val file = batchRecordFile
    batchMediaRecorder = null
    batchRecordFile = null

    if (recorder != null) {
        runCatching {
            recorder.stop()
            recorder.release()
        }.onFailure { Log.e(TAG, "Failed to stop batch recorder", it) }
    }

    val elapsedMs = if (recordingStartTime > 0L) System.currentTimeMillis() - recordingStartTime else 0L
    recordingStartTime = 0L

    if (file == null || !file.exists() || file.length() == 0L) {
        Log.d(TAG, "No audio recorded")
        return
    }

    val durationSec = (elapsedMs / 1000.0).coerceAtLeast(1.0)

    // Cancel any previous processing before starting a new one
    cancelProcessing()
    processingScope.launch {
        _isGeneratingVoice.value = true
        _errorMessage.value = null
        _executionLatencyInfo.value = null

        val flow = selectedViToEnFlow.value
        _micStatus.value = "Đang nhận diện..."
        _currentOriginalText.value = "Đang xử lý..."
        _currentTranslatedText.value = ""

        try {
            val startTime = System.currentTimeMillis()
            val asrBaseUrl = BuildConfig.ASR_BASE_URL.trim()

            // 1. Nhận diện giọng nói bằng Local Whisper Server (task = transcribe)
            val originalText = withContext(Dispatchers.IO) {
                val bytes = file.readBytes()
                LocalAsrClient.transcribeAudioFile(
                    baseUrl = asrBaseUrl,
                    audioBytes = bytes,
                    filename = file.name,
                    mimeType = "audio/m4a",
                    model = "medium",
                    language = sourceLang.value.lowercase(),
                    task = "transcribe"
                )
            }

            if (originalText.isNotBlank()) {
                Log.d(TAG, "Whisper transcribe result: $originalText")
                _currentOriginalText.value = originalText
                _micStatus.value = "Đang dịch..."

                // 2. Dịch bằng Google Translate
                val translated = withContext(Dispatchers.IO) {
                    com.example.data.api.GoogleTranslateApiClient.translate(
                        text = originalText,
                        sourceLang = sourceLang.value,
                        targetLang = targetLang.value
                    )
                }

                _currentTranslatedText.value = translated
                if (_isOverlayEnabled.value) {
                    OverlayCaptionService.update(getApplication(), originalText, translated)
                }

                val totalAsrMtLatency = System.currentTimeMillis() - startTime

                // 3. Phát âm thanh ngay lập tức
                _micStatus.value = "Đang phát..."
                if (targetLang.value == "VI") {
                    speakWithAndroidTtsVi(translated)
                } else {
                    speakWithAndroidTts(translated)
                }

                _executionLatencyInfo.value = "ASR+MT: ${totalAsrMtLatency}ms"

                // 4. Lưu bản ghi đầy đủ vào cơ sở dữ liệu dưới nền
                _currentSessionId.value?.let { sessionId ->
                    repository.insertRecord(
                        originalText = originalText,
                        translatedText = translated,
                        sourceLang = sourceLang.value,
                        targetLang = targetLang.value,
                        sessionId = sessionId
                    )
                }
                _micStatus.value = "Sẵn sàng"
            } else {
                _currentOriginalText.value = "Không nhận diện được giọng nói."
                _micStatus.value = "Sẵn sàng"
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR+MT Flow failed", e)
            _errorMessage.value = "Lỗi dịch: ${e.localizedMessage}"
            _currentOriginalText.value = ""
            _isGeneratingVoice.value = false
            _micStatus.value = "Sẵn sàng"
        }
    }
}

internal fun InterpreterViewModel.onPushToTalkStart() {
    if (currentMode.value != TranslateMode.VI_TO_EN) return
    val expectedPrefix = selectedViToEnFlow.value.name
    if (_currentSessionId.value == null || !_currentSessionId.value!!.startsWith(expectedPrefix)) {
        _currentSessionId.value = "${expectedPrefix}_${UUID.randomUUID()}"
    }
    startListening()
}

internal fun InterpreterViewModel.onPushToTalkEnd() {
    if (currentMode.value != TranslateMode.VI_TO_EN) return
    stopListening()
}

internal fun InterpreterViewModel.getDeepSeekApiKey(): String {
    var key = runCatching { BuildConfig.DEEPSEEK_API_KEY }.getOrNull()?.trim() ?: ""
    if (key.isNotEmpty() && key != "MY_DEEPSEEK_API_KEY" && key != "null") return key

    key = System.getenv("DEEPSEEK_API_KEY")?.trim() ?: ""
    if (key.isNotEmpty()) return key

    try {
        val hermesPaths = listOf(
            File("D:/hermes/.env"),
            File(System.getProperty("user.home"), ".hermes/.env")
        )
        for (hermesEnv in hermesPaths) {
            if (!hermesEnv.exists()) continue
            val lines = hermesEnv.readLines()
            for (line in lines) {
                if (line.trim().startsWith("DEEPSEEK_API_KEY=")) {
                    val parsed = line.substringAfter("DEEPSEEK_API_KEY=").trim()
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read DeepSeek API key from ~/.hermes/.env", e)
    }
    return ""
}

internal fun InterpreterViewModel.getSiliconFlowApiKey(): String {
    var key = runCatching { BuildConfig.SILICONFLOW_API_KEY }.getOrNull()?.trim() ?: ""
    if (key.isNotEmpty() && key != "MY_SILICONFLOW_API_KEY" && key != "null") return key

    key = System.getenv("SILICONFLOW_API_KEY")?.trim() ?: ""
    if (key.isNotEmpty()) return key

    try {
        val hermesPaths = listOf(
            File("D:/hermes/.env"),
            File(System.getProperty("user.home"), ".hermes/.env")
        )
        for (hermesEnv in hermesPaths) {
            if (!hermesEnv.exists()) continue
            val lines = hermesEnv.readLines()
            for (line in lines) {
                if (line.trim().startsWith("SILICONFLOW_API_KEY=")) {
                    val parsed = line.substringAfter("SILICONFLOW_API_KEY=").trim()
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read SiliconFlow API key from Hermes env", e)
    }

    return ""
}

internal fun detectSourceLanguage(text: String): String {
    val normalized = text.lowercase().trim()
    if (normalized.isBlank()) return "VI"

    val vietnameseMarks = "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"
    if (normalized.any { it in vietnameseMarks }) return "VI"

    val viTokens = setOf("và", "là", "của", "không", "được", "một", "những", "cho", "tôi", "anh", "chị", "em", "hôm", "nay", "đang", "đã")
    val enTokens = setOf("the", "and", "you", "that", "this", "with", "for", "from", "have", "what", "when", "where", "hello", "please", "thank")

    val words = normalized.split("\\s+".toRegex())
    val viScore = words.count { it in viTokens }
    val enScore = words.count { it in enTokens }
    return if (viScore > enScore) "VI" else "EN"
}
