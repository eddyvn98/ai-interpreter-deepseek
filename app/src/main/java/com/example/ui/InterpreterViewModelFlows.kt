package com.example.ui

import android.util.Log
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.DeepSeekApiClient
import com.example.overlay.OverlayCaptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InterpreterVMFlows"

internal fun InterpreterViewModel.translateManualText(text: String) {
    if (text.isBlank() || isPaused.value) return
    _currentOriginalText.value = text
    executeTranslation(text, sourceLang.value)
}

internal fun InterpreterViewModel.executeTranslation(text: String, sourceLang: String, pendingId: Long? = null) {
    processingScope.launch {
        try {
            val deepSeekKey = BuildConfig.DEEPSEEK_API_KEY.trim()
            if (deepSeekKey.isEmpty() || deepSeekKey == "MY_DEEPSEEK_API_KEY") {
                _errorMessage.value = "Thiếu API Key."
                return@launch
            }

            _isGeneratingVoice.value = true

            var isFirstDelta = true
            var tempTranslation = ""
            val translated = withContext(Dispatchers.IO) {
                DeepSeekApiClient.translateStream(
                    apiKey = deepSeekKey,
                    baseUrl = null,
                    inputText = text,
                    sourceLang = sourceLang,
                    onDelta = { delta ->
                        if (isFirstDelta) {
                            tempTranslation = delta
                            isFirstDelta = false
                        } else {
                            tempTranslation += delta
                        }
                        if (pendingId != null) {
                            updatePendingRecordTranslation(pendingId, tempTranslation)
                        } else {
                            _currentTranslatedText.value = tempTranslation
                        }
                    }
                )
            }.ifBlank {
                if (pendingId != null) {
                    val record = pendingRecords.value.firstOrNull { it.id == pendingId }
                    record?.translatedText?.ifBlank { "Không có kết quả dịch." } ?: "Không có kết quả dịch."
                } else {
                    _currentTranslatedText.value.ifBlank { "Không có kết quả dịch." }
                }
            }

            if (pendingId != null) {
                updatePendingRecordTranslation(pendingId, translated)
            } else {
                _currentTranslatedText.value = translated
            }
            Log.d(TAG, "executeTranslation: translation committed at ${System.currentTimeMillis()} sourceLang=$sourceLang len=${translated.length}")
            if (_isOverlayEnabled.value) {
                OverlayCaptionService.update(
                    getApplication(), text, translated
                )
            }

            // Speak the translation immediately (before database sync)
            if (targetLang.value == "VI") {
                Log.d(TAG, "executeTranslation: dispatching Vietnamese TTS at ${System.currentTimeMillis()}")
                generateAndPlayVietnameseSpeech(translated)
            } else if (targetLang.value == "EN") {
                Log.d(TAG, "executeTranslation: dispatching AI TTS at ${System.currentTimeMillis()}")
                generateAndPlayAiSpeech(translated)
            }

            val insertedId = _currentSessionId.value?.let { sessionId ->
                repository.insertRecord(
                    originalText = text,
                    translatedText = translated,
                    sourceLang = sourceLang,
                    targetLang = targetLang.value,
                    sessionId = sessionId
                )
            } ?: -1L

            // Wait until the database Flow emits the new record to UI state flow before clearing preview states
            if (insertedId != -1L) {
                translationRecords.first { records ->
                    records.any { it.id == insertedId }
                }
            } else {
                kotlinx.coroutines.delay(200)
            }

            if (pendingId != null) {
                removePendingRecord(pendingId)
            }

            if (sourceLang == "EN") {
                if (pendingId == null) {
                    _currentOriginalText.value = ""
                    _currentTranslatedText.value = ""
                }
            }

            // Add translation cost: input tokens approx = 15 + inputCharCount/3; output tokens = outputCharCount/3
            val inputTokens = 15 + text.length / 3.0
            val outputTokens = translated.length / 3.0
            val transCost = inputTokens * 0.00000016 + outputTokens * 0.00000049
            addCost(transCost)

            if (!_isSummaryPaused.value && sourceLang != "VI") refreshLiveSummary()

        } catch (e: Exception) {
            _errorMessage.value = "Lỗi dịch: ${e.localizedMessage}"
        } finally {
            _isGeneratingVoice.value = false
        }
    }
}

internal suspend fun InterpreterViewModel.executeTranslationWithLatency(text: String, sourceLang: String, asrLatency: Long) {
        try {
            val deepSeekKey = BuildConfig.DEEPSEEK_API_KEY.trim()
            if (deepSeekKey.isEmpty() || deepSeekKey == "MY_DEEPSEEK_API_KEY") {
                _errorMessage.value = "Thiếu API Key."
                return
            }

        _isGeneratingVoice.value = true

        val mtStartTime = System.currentTimeMillis()
        var isFirstDelta = true
        val translated = withContext(Dispatchers.IO) {
            DeepSeekApiClient.translateStream(
                apiKey = deepSeekKey,
                baseUrl = null,
                inputText = text,
                sourceLang = sourceLang,
                onDelta = { delta ->
                    if (isFirstDelta) {
                        _currentTranslatedText.value = delta
                        isFirstDelta = false
                    } else {
                        _currentTranslatedText.value += delta
                    }
                }
            )
        }.ifBlank { _currentTranslatedText.value.ifBlank { "Không có kết quả dịch." } }

        _currentTranslatedText.value = translated
        Log.d(TAG, "executeTranslationWithLatency: translation committed at ${System.currentTimeMillis()} sourceLang=$sourceLang len=${translated.length}")
        if (_isOverlayEnabled.value) {
            OverlayCaptionService.update(
                getApplication(), _currentOriginalText.value, translated
            )
        }

        // Trigger TTS and record latency immediately
        val mtLatency = System.currentTimeMillis() - mtStartTime
        val ttsStartTime = System.currentTimeMillis()
        if (targetLang.value == "VI") {
            speakWithAndroidTtsVi(translated)
        } else {
            speakWithAndroidTts(translated)
        }
        val ttsLatency = System.currentTimeMillis() - ttsStartTime
        val totalLatency = asrLatency + mtLatency + ttsLatency
        _executionLatencyInfo.value = "ASR: ${asrLatency}ms | MT: ${mtLatency}ms | TTS: ${ttsLatency}ms | Tổng: ${totalLatency}ms"

        val insertedId = _currentSessionId.value?.let { sessionId ->
            repository.insertRecord(
                originalText = text,
                translatedText = translated,
                sourceLang = sourceLang,
                targetLang = targetLang.value,
                sessionId = sessionId
            )
        } ?: -1L

        // Wait until the database Flow emits the new record to UI state flow before clearing preview states
        if (insertedId != -1L) {
            translationRecords.first { records ->
                records.any { it.id == insertedId }
            }
        } else {
            kotlinx.coroutines.delay(200)
        }
        if (sourceLang == "EN") {
            _currentOriginalText.value = ""
            _currentTranslatedText.value = ""
        }

        val inputTokens = 15 + text.length / 3.0
        val outputTokens = translated.length / 3.0
        val transCost = inputTokens * 0.00000016 + outputTokens * 0.00000049
        addCost(transCost)

    } catch (e: Exception) {
        _errorMessage.value = "Lỗi dịch: ${e.localizedMessage}"
    } finally {
        _isGeneratingVoice.value = false
    }
}

internal fun InterpreterViewModel.executeGoogleTranslation(
    text: String,
    sourceLang: String,
    pendingId: Long,
    existingTranslation: String? = null
) {
    processingScope.launch {
        try {
            _isGeneratingVoice.value = true

            val targetLang = targetLang.value

            val translated = if (!existingTranslation.isNullOrBlank()) {
                existingTranslation
            } else {
                withContext(Dispatchers.IO) {
                    com.example.data.api.GoogleTranslateApiClient.translate(
                        text = text,
                        sourceLang = sourceLang,
                        targetLang = targetLang
                    )
                }
            }.ifBlank { "Không có kết quả dịch." }

            // Update pending record immediately on RAM
            updatePendingRecordTranslation(pendingId, translated)

            Log.d(TAG, "executeGoogleTranslation: committed at ${System.currentTimeMillis()} len=${translated.length}")
            if (_isOverlayEnabled.value) {
                OverlayCaptionService.update(
                    getApplication(), text, translated
                )
            }

            // Speak immediately
            if (targetLang == "VI") {
                generateAndPlayVietnameseSpeech(translated)
            } else if (targetLang == "EN") {
                generateAndPlayAiSpeech(translated)
            }

            // Write to database
            val insertedId = _currentSessionId.value?.let { sessionId ->
                repository.insertRecord(
                    originalText = text,
                    translatedText = translated,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    sessionId = sessionId
                )
            } ?: -1L

            // Wait for DB flow to emit
            if (insertedId != -1L) {
                translationRecords.first { records ->
                    records.any { it.id == insertedId }
                }
            } else {
                kotlinx.coroutines.delay(200)
            }

            // Clear pending record
            removePendingRecord(pendingId)

        } catch (e: Exception) {
            _errorMessage.value = "Lỗi dịch Google: ${e.localizedMessage}"
            removePendingRecord(pendingId)
        } finally {
            _isGeneratingVoice.value = false
        }
    }
}

