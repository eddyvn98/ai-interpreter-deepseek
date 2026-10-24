package com.example.ui

import android.util.Log
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.data.api.LocalAsrClient
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "InterpreterVMTts"

internal fun InterpreterViewModel.playRecordedTranslation(text: String, useAiTts: Boolean = true) {
    if (useAiTts) generateAndPlayAiSpeech(text) else speakWithAndroidTts(text)
}

internal fun InterpreterViewModel.generateAndPlayAiSpeech(text: String) {
    if (!_isTtsEnabled.value) return
    processingScope.launch {
        try {
            _isGeneratingVoice.value = true
            _isSpeaking.value = true
            speakWithAndroidTts(text)
        } catch (e: Exception) {
            Log.e(TAG, "Local English TTS failed", e)
            speakWithAndroidTts(text)
        } finally {
            _isGeneratingVoice.value = false
        }
    }
}

internal fun InterpreterViewModel.generateAndPlayVietnameseSpeech(text: String) {
    if (!_isTtsEnabled.value) return
    processingScope.launch {
        try {
            _isGeneratingVoice.value = true
            _isSpeaking.value = true
            Log.d(TAG, "generateAndPlayVietnameseSpeech: using local Android TTS at ${System.currentTimeMillis()} textLen=${text.length}")
            speakWithAndroidTtsVi(text)
        } catch (e: Exception) {
            Log.e(TAG, "Local Vietnamese TTS failed", e)
            speakWithAndroidTtsVi(text)
        } finally {
            _isGeneratingVoice.value = false
        }
    }
}

internal fun InterpreterViewModel.speakWithAndroidTts(text: String) {
    Log.d(TAG, "speakWithAndroidTts: enqueue at ${System.currentTimeMillis()} textLen=${text.length}")
    enqueueSpeech(text, Locale.US, "tts")
}

internal fun InterpreterViewModel.speakWithAndroidTtsVi(text: String) {
    Log.d(TAG, "speakWithAndroidTtsVi: enqueue at ${System.currentTimeMillis()} textLen=${text.length}")
    enqueueSpeech(text, Locale("vi", "VN"), "tts_vi")
}

internal fun InterpreterViewModel.playCloneTtsForLastTranslation() {
    val text = currentTranslatedText.value.ifBlank {
        val currentId = currentSessionId.value
        translationRecords.value
            .filter { it.sessionId == currentId }
            .sortedBy { it.timestamp }
            .lastOrNull()?.translatedText
    }.orEmpty().trim()
    
    if (text.isBlank()) return
    generateAndPlayCloneSpeech(text)
}

internal fun InterpreterViewModel.generateAndPlayCloneSpeech(text: String) {
    processingScope.launch {
        try {
            _isGeneratingCloneVoice.value = true
            _isSpeaking.value = true
            
            val asrBaseUrl = BuildConfig.ASR_BASE_URL.trim()
            val base64Audio = withContext(Dispatchers.IO) {
                LocalAsrClient.synthesizeCloneVoice(asrBaseUrl, text)
            }
            
            if (base64Audio.isNotEmpty()) {
                playAudioFromBase64(base64Audio)
            } else {
                throw IllegalStateException("Empty audio returned from server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clone TTS synthesis failed, falling back to local TTS", e)
            _errorMessage.value = "Lỗi phát giọng clone: ${e.localizedMessage}"
            speakWithAndroidTts(text)
        } finally {
            _isGeneratingCloneVoice.value = false
        }
    }
}
