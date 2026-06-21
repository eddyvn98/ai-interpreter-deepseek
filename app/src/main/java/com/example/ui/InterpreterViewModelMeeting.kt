package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.LocalAsrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "InterpreterVMMeeting"

internal fun InterpreterViewModel.startMeetingRecording() {
    _errorMessage.value = null
    _meetingTranscript.value = ""
    _meetingRawTranscript.value = ""
    val app = getApplication<Application>()
    MeetingRecordService.startService(app)
}

internal fun InterpreterViewModel.pauseMeetingRecording() {
    _isProcessingMeeting.value = true
    val app = getApplication<Application>()
    MeetingRecordService.pauseService(app)
}

internal fun InterpreterViewModel.resumeMeetingRecording() {
    val app = getApplication<Application>()
    MeetingRecordService.resumeService(app)
}

internal fun InterpreterViewModel.stopMeetingRecording() {
    val wasPaused = MeetingRecordService.isPaused.value
    if (!wasPaused) {
        _isProcessingMeeting.value = true
    }
    val app = getApplication<Application>()
    MeetingRecordService.stopService(app)
}

internal fun InterpreterViewModel.processMeetingSegment(segmentSessionId: String, filePath: String, durationSec: Int) {
    viewModelScope.launch(Dispatchers.IO) {
        _isProcessingMeeting.value = true
        try {
            val asrBaseUrl = BuildConfig.ASR_BASE_URL.trim()
            if (asrBaseUrl.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Thiếu cấu hình ASR_BASE_URL để xử lý cuộc họp."
                }
                return@launch
            }

            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                return@launch
            }

            val bytes = file.readBytes()
            val actualDurationSec = durationSec.coerceAtLeast(1)

            // Step 1: Transcribe via local on-device Whisper server (no cloud cost).
            // language=null → server auto-detects EN/VI/mixed per segment.
            withContext(Dispatchers.Main) {
                _micStatus.value = "Đang nhận diện đoạn vừa ghi..."
            }
            val rawTranscript = withContext(Dispatchers.IO) {
                LocalAsrClient.transcribeAudioFile(
                    baseUrl = asrBaseUrl,
                    audioBytes = bytes,
                    filename = file.name,
                    mimeType = "audio/m4a",
                    model = "medium",
                    language = null // auto-detect EN+VI mixed
                )
            }

            if (rawTranscript.isBlank()) {
                return@launch
            }

            // Get existing record to construct accumulated transcript
            val existingRecord = repository.getRecordBySessionId(segmentSessionId)
            val currentRaw = existingRecord?.originalText ?: ""

            val newRaw = if (currentRaw.isBlank()) rawTranscript else "$currentRaw\n\n$rawTranscript"

            // Only update active UI states if this segment belongs to the currently active session
            val currentActiveSessionId = MeetingRecordService.meetingSessionId.value
            if (segmentSessionId == currentActiveSessionId) {
                _meetingRawTranscript.value = newRaw
                _meetingTranscript.value = newRaw
            }

            // Save/update in database — store transcript as both original and translated
            // (no translation needed; the raw STT output is what the user wants)
            if (existingRecord != null) {
                val updatedRecord = existingRecord.copy(
                    originalText = newRaw,
                    translatedText = newRaw,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertRecordDirectly(updatedRecord)
            } else {
                repository.insertRecord(
                    originalText = newRaw,
                    translatedText = newRaw,
                    sourceLang = "AUTO",
                    targetLang = "AUTO",
                    sessionId = segmentSessionId
                )
            }

            withContext(Dispatchers.Main) {
                _micStatus.value = "Hoàn thành xử lý đoạn cuộc họp!"
                _meetingCompletionMessage.value = "Đã tạo xong bản ghi âm cuộc họp."
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing meeting segment", e)
            withContext(Dispatchers.Main) {
                _errorMessage.value = "Lỗi xử lý đoạn cuộc họp: ${e.localizedMessage}"
            }
        } finally {
            _isProcessingMeeting.value = false
        }
    }
}
