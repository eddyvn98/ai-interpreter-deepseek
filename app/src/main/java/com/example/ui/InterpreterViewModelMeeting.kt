package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.LocalAsrClient
import com.example.data.database.MeetingAudioSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG = "InterpreterVMMeeting"

internal fun InterpreterViewModel.startMeetingRecording() {
    _errorMessage.value = null
    _meetingTranscript.value = ""
    _meetingRawTranscript.value = ""
    val app = getApplication<Application>()
    MeetingRecordService.startService(app)
}

internal fun InterpreterViewModel.pauseMeetingRecording() {
    val app = getApplication<Application>()
    MeetingRecordService.pauseService(app)
}

internal fun InterpreterViewModel.resumeMeetingRecording() {
    val app = getApplication<Application>()
    MeetingRecordService.resumeService(app)
}

internal fun InterpreterViewModel.stopMeetingRecording() {
    val sessionId = MeetingRecordService.meetingSessionId.value
    val app = getApplication<Application>()
    MeetingRecordService.stopService(app)

    // Save finalized meeting transcript to history in database after stops
    viewModelScope.launch(Dispatchers.IO) {
        // Wait 2.0s for the last segment to be finalized and transcribed
        delay(2000)
        if (sessionId != null) {
            saveMeetingToHistory(sessionId)
        }
    }
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
                Log.w(TAG, "Audio segment file does not exist or empty: $filePath")
                return@launch
            }

            // Find matching segment record in SQLite Room DB
            val segments = repository.getSegmentsBySessionIdDirect(segmentSessionId)
            val segment = segments.find { it.filePath == filePath } ?: run {
                Log.w(TAG, "No matching SQLite record found for segment file: $filePath")
                return@launch
            }

            val bytes = file.readBytes()

            withContext(Dispatchers.Main) {
                _micStatus.value = "Đang nhận diện đoạn ${segment.segmentIndex + 1}..."
            }

            // Call server to transcribe and identify speaker
            val response = try {
                LocalAsrClient.transcribeSegment(
                    baseUrl = asrBaseUrl,
                    audioBytes = bytes,
                    filename = file.name,
                    mimeType = "audio/wav",
                    model = "medium",
                    language = null, // Auto detect
                    sessionId = segmentSessionId,
                    segmentIndex = segment.segmentIndex
                )
            } catch (e: Exception) {
                Log.e(TAG, "Network request failed for segment ${segment.segmentIndex}", e)
                // Mark segment as FAILED in DB
                repository.updateSegmentStatus(segment.id, "FAILED", null, null)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Lỗi kết nối server phân đoạn ${segment.segmentIndex + 1}. Bạn có thể thử lại sau."
                }
                return@launch
            }

            // Update SQLite record with COMPLETED state
            val speakerText = response.speaker ?: "Speaker 1"
            repository.updateSegmentStatus(
                id = segment.id,
                status = "COMPLETED",
                speakerLabel = speakerText,
                transcribedText = response.text
            )

            // Update ViewModel transcripts for active UI display
            updateActiveMeetingTranscript(segmentSessionId)

            withContext(Dispatchers.Main) {
                _micStatus.value = "Hoàn thành xử lý đoạn cuộc họp!"
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

internal suspend fun InterpreterViewModel.updateActiveMeetingTranscript(sessionId: String) {
    val completedSegments = repository.getSegmentsBySessionIdDirect(sessionId)
        .filter { it.status == "COMPLETED" }
        .sortedBy { it.segmentIndex }

    val sb = StringBuilder()
    for (seg in completedSegments) {
        val timeString = formatStartTime(seg.startTimeMs)
        val speaker = seg.speakerLabel ?: "Speaker 1"
        val text = seg.transcribedText.orEmpty()
        if (text.isNotBlank()) {
            sb.append("$timeString $speaker: $text\n\n")
        }
    }

    val finalText = sb.toString().trim()
    withContext(Dispatchers.Main) {
        _meetingRawTranscript.value = finalText
        _meetingTranscript.value = finalText
    }
}

internal fun formatStartTime(ms: Long): String {
    val totalSecs = ms / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return String.format(Locale.getDefault(), "[%02d:%02d]", minutes, seconds)
}

internal fun InterpreterViewModel.saveMeetingToHistory(sessionId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val segments = repository.getSegmentsBySessionIdDirect(sessionId)
                .filter { it.status == "COMPLETED" }
                .sortedBy { it.segmentIndex }

            if (segments.isEmpty()) {
                Log.d(TAG, "No completed segments found for session $sessionId, skipping history save.")
                return@launch
            }

            val sb = StringBuilder()
            for (seg in segments) {
                val timeString = formatStartTime(seg.startTimeMs)
                val speaker = seg.speakerLabel ?: "Speaker 1"
                val text = seg.transcribedText.orEmpty()
                if (text.isNotBlank()) {
                    sb.append("$timeString $speaker: $text\n\n")
                }
            }

            val fullTranscript = sb.toString().trim()
            if (fullTranscript.isNotBlank()) {
                repository.insertRecord(
                    originalText = fullTranscript,
                    translatedText = fullTranscript,
                    sourceLang = "AUTO",
                    targetLang = "AUTO",
                    sessionId = sessionId
                )
                Log.d(TAG, "Saved full meeting transcript to database for session $sessionId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save meeting history", e)
        }
    }
}

// Retry failed segments function
internal fun InterpreterViewModel.retryFailedSegments(sessionId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        _isProcessingMeeting.value = true
        try {
            val failedSegments = repository.getSegmentsBySessionIdDirect(sessionId)
                .filter { it.status == "FAILED" || it.status == "PENDING" }
                .sortedBy { it.segmentIndex }

            if (failedSegments.isEmpty()) {
                return@launch
            }

            val asrBaseUrl = BuildConfig.ASR_BASE_URL.trim()
            if (asrBaseUrl.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Thiếu cấu hình ASR_BASE_URL để xử lý lại."
                }
                return@launch
            }

            for (segment in failedSegments) {
                val file = File(segment.filePath)
                if (!file.exists() || file.length() == 0L) {
                    continue
                }

                withContext(Dispatchers.Main) {
                    _micStatus.value = "Đang nhận diện lại đoạn ${segment.segmentIndex + 1}..."
                }

                try {
                    val response = LocalAsrClient.transcribeSegment(
                        baseUrl = asrBaseUrl,
                        audioBytes = file.readBytes(),
                        filename = file.name,
                        mimeType = "audio/wav",
                        model = "medium",
                        language = null,
                        sessionId = sessionId,
                        segmentIndex = segment.segmentIndex
                    )

                    val speakerText = response.speaker ?: "Speaker 1"
                    repository.updateSegmentStatus(
                        id = segment.id,
                        status = "COMPLETED",
                        speakerLabel = speakerText,
                        transcribedText = response.text
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Retry failed for segment ${segment.segmentIndex}", e)
                }
            }

            updateActiveMeetingTranscript(sessionId)
            
            withContext(Dispatchers.Main) {
                _micStatus.value = "Đã dịch xong các phân đoạn bị lỗi!"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error retrying segments", e)
        } finally {
            _isProcessingMeeting.value = false
        }
    }
}
