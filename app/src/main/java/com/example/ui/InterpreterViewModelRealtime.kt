package com.example.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.DeepSeekApiClient
import com.example.data.api.RealtimeAsrClient
import com.example.overlay.OverlayCaptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InterpreterVMRealtime"

/**
 * Schedules a debounced translation of the current partial ASR text (EN→VI only).
 * If a new partial arrives before the debounce fires, the previous job is cancelled
 * and a new one starts — so only the latest, most complete partial gets translated.
 * Triggers only when the partial has >= 4 words (avoids wasteful calls for very short phrases).
 */
internal fun InterpreterViewModel.schedulePartialTranslation(text: String) {
    if (text.isBlank()) {
        lastTranslatedWordCount = 0
        return
    }

    val words = text.trim().split("\\s+".toRegex())
    val wordCount = words.size

    // Progressive step-translation: translate if first words, or word count increased by >= 3, or ends with sentence boundary
    val shouldTranslate = lastTranslatedWordCount == 0 || 
                          (wordCount - lastTranslatedWordCount >= 3) || 
                          text.endsWith(".") || text.endsWith("?")

    if (!shouldTranslate) return

    val debounceMs = 200L

    partialTranslationJob?.cancel()
    partialTranslationJob = processingScope.launch {
        delay(debounceMs)
        if (!isActive || _isGeneratingVoice.value) return@launch
        try {
            val sourceLang = sourceLang.value
            val targetLang = targetLang.value
            val translated = withContext(Dispatchers.IO) {
                com.example.data.api.GoogleTranslateApiClient.translate(text, sourceLang, targetLang)
            }
            if (isActive) {
                val isSame = translated.trim().equals(text.trim(), ignoreCase = true)
                _currentTranslatedText.value = if (isSame) "" else translated
                lastTranslatedOriginalText = text
                lastTranslatedWordCount = wordCount
                Log.d(TAG, "Partial translation complete: \"${text.take(60)}...\" -> \"${translated.take(60)}\" (isSame=$isSame)")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Partial translation error: ${e.message}")
            }
        }
    }
}

/**
 * Starts Google SpeechRecognizer capture. Partial results update the
 * displayed original text; each final sentence triggers translation.
 */
internal fun InterpreterViewModel.startRealtimeStreaming() {
    val apiKey = BuildConfig.ASR_API_KEY.trim()
    val baseUrl = BuildConfig.ASR_BASE_URL.trim()
    if (baseUrl.isEmpty()) {
        _errorMessage.value = "Thiếu cấu hình ASR (Base URL)."
        _micStatus.value = "Lỗi ASR"
        return
    }

    // Close any previous session first
    realtimeAsrClient?.finish()

    val client = RealtimeAsrClient(apiKey, baseUrl)
    realtimeAsrClient = client

    _micStatus.value = "Đang kết nối ASR..."

    val model = "openai/whisper-medium"
    val language = sourceLang.value.lowercase()

    client.connect(
        model = model,
        language = language,
        onReady = {
            // WebSocket handshake done and task started – now open mic
            Log.d(TAG, "ASR ready, starting AudioRecord")
            recordingStartTime = System.currentTimeMillis()
            viewModelScope.launch(Dispatchers.Main) {
                _isListening.value = true
                _micStatus.value = "Đang nghe..."
            }
            audioRecorder.startStreaming(
                scope = viewModelScope,
                onFrame = { pcm, length ->
                    // Forward raw PCM frames directly over WebSocket
                    if (_isListening.value) client.sendAudioFrame(pcm, length)
                },
                onRmsChanged = { level ->
                    _micLevel.value = level
                }
            )
        },
        onPartial = { text ->
            viewModelScope.launch(Dispatchers.Main) {
                _currentOriginalText.value = text
                // Trigger progressive partial translation
                schedulePartialTranslation(text)
                if (_isOverlayEnabled.value) {
                    OverlayCaptionService.update(
                        getApplication(), text, _currentTranslatedText.value
                    )
                }
            }
        },
        onFinalWithViText = { text, viText ->
            // Final sentence with local argostranslate VI translation from server
            Log.d(TAG, "Final ASR: $text | VI: $viText")
            viewModelScope.launch(Dispatchers.Main) {
                partialTranslationJob?.cancel()
                partialTranslationJob = null

                val sourceLang = sourceLang.value
                val targetLang = targetLang.value
                val sessionId = _currentSessionId.value ?: "${sourceLang}_TO_${targetLang}_DEFAULT"
                val currentLiveTranslation = _currentTranslatedText.value.trim()
                val isTranslationComplete = (text == lastTranslatedOriginalText) && currentLiveTranslation.isNotEmpty()

                val pendingId = getNextPendingId()
                addPendingRecord(pendingId, text, currentLiveTranslation, sourceLang, targetLang, sessionId)

                _currentOriginalText.value = ""
                _currentTranslatedText.value = ""
                lastTranslatedOriginalText = ""
                lastTranslatedWordCount = 0

                // Pass viText as existingTranslation so executeGoogleTranslation skips
                // Google Translate network call AND plays TTS exactly once if source language is English.
                // Do NOT call generateAndPlayVietnameseSpeech() here — that would cause double TTS.
                val seedTranslation = if (sourceLang == "EN" && viText.isNotBlank()) viText
                    else if (isTranslationComplete) currentLiveTranslation
                    else null

                if (seedTranslation != null) {
                    executeGoogleTranslation(text, sourceLang, pendingId, existingTranslation = seedTranslation)
                } else {
                    executeGoogleTranslation(text, sourceLang, pendingId)
                }
            }
        },
        onFinal = { text ->
            // Fallback when server sends no vi_text (older server or vi_text was empty)
            Log.d(TAG, "Final ASR sentence: $text")
            viewModelScope.launch(Dispatchers.Main) {
                partialTranslationJob?.cancel()
                partialTranslationJob = null

                val sourceLang = sourceLang.value
                val targetLang = targetLang.value
                val sessionId = _currentSessionId.value ?: "${sourceLang}_TO_${targetLang}_DEFAULT"
                val currentLiveTranslation = _currentTranslatedText.value.trim()
                val isTranslationComplete = (text == lastTranslatedOriginalText) && currentLiveTranslation.isNotEmpty()

                val pendingId = getNextPendingId()
                addPendingRecord(pendingId, text, currentLiveTranslation, sourceLang, targetLang, sessionId)

                _currentOriginalText.value = ""
                _currentTranslatedText.value = ""
                lastTranslatedOriginalText = ""
                lastTranslatedWordCount = 0

                if (isTranslationComplete) {
                    executeGoogleTranslation(text, sourceLang, pendingId, existingTranslation = currentLiveTranslation)
                } else {
                    executeGoogleTranslation(text, sourceLang, pendingId)
                }
            }
        },
        onError = { msg ->
            Log.e(TAG, "ASR error: $msg")
            viewModelScope.launch(Dispatchers.Main) {
                _isPaused.value = true
                _errorMessage.value = msg
                _micStatus.value = "Lỗi ASR"
                _isListening.value = false
                audioRecorder.stopRecording()

                if (recordingStartTime > 0L) {
                    val elapsedMs = System.currentTimeMillis() - recordingStartTime
                    recordingStartTime = 0L
                    val elapsedSec = elapsedMs / 1000.0
                    val cost = elapsedSec * 0.00009
                    addCost(cost)
                }
            }
        }
    )
}
