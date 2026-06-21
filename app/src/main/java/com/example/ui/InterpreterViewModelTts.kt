package com.example.ui

import android.util.Log
import kotlinx.coroutines.launch
import java.util.Locale

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
