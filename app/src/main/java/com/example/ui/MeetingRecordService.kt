package com.example.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MeetingRecordService"
private const val CHANNEL_ID = "meeting_record_channel"
private const val NOTIFICATION_ID = 2026

class MeetingRecordService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var segmentIndex = 0
    private var segmentDurationSec = 0
    private var currentSegmentFile: File? = null

    data class SegmentInfo(val sessionId: String, val filePath: String, val durationSec: Int)

    companion object {
        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused = _isPaused.asStateFlow()

        private val _durationSec = MutableStateFlow(0)
        val durationSec = _durationSec.asStateFlow()

        private val _outputFilePath = MutableStateFlow<String?>(null)
        val outputFilePath = _outputFilePath.asStateFlow()

        private val _meetingSessionId = MutableStateFlow<String?>(null)
        val meetingSessionId = _meetingSessionId.asStateFlow()

        private val _latestSegment = MutableStateFlow<SegmentInfo?>(null)
        val latestSegment = _latestSegment.asStateFlow()

        fun clearLatestSegment() {
            _latestSegment.value = null
        }

        fun startService(context: Context) {
            val intent = Intent(context, MeetingRecordService::class.java).apply {
                action = "START"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MeetingRecordService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }

        fun pauseService(context: Context) {
            val intent = Intent(context, MeetingRecordService::class.java).apply {
                action = "PAUSE"
            }
            context.startService(intent)
        }

        fun resumeService(context: Context) {
            val intent = Intent(context, MeetingRecordService::class.java).apply {
                action = "RESUME"
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startRecording()
            "STOP" -> stopRecording()
            "PAUSE" -> pauseRecording()
            "RESUME" -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (_isRecording.value) return

        try {
            // Acquire WakeLock to keep CPU alive during screen off
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MeetingRecord::WakeLock").apply {
                acquire()
            }

            segmentIndex = 0
            segmentDurationSec = 0
            val sessionId = "MEETING_" + System.currentTimeMillis()
            _meetingSessionId.value = sessionId
            _outputFilePath.value = null

            val cacheDir = externalCacheDir ?: cacheDir
            val outputFile = File(cacheDir, "${sessionId}_part_${segmentIndex}.m4a")
            currentSegmentFile = outputFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _isPaused.value = false
            _durationSec.value = 0
            InterpreterWidgetProvider.updateWidgetState(this)

            // Start Foreground Service with type to comply with API 29+ / Android 14+
            val notification = createNotification(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Start coroutine to track duration second by second
            serviceJob?.cancel()
            serviceJob = serviceScope.launch {
                while (_isRecording.value) {
                    delay(1000)
                    if (!_isPaused.value) {
                        _durationSec.value += 1
                        segmentDurationSec += 1
                        updateNotification(_durationSec.value)
                        InterpreterWidgetProvider.updateWidgetState(this@MeetingRecordService)
                    }
                }
            }
            Log.d(TAG, "Recording started segment $segmentIndex: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _isRecording.value = false
            _isPaused.value = false
            _outputFilePath.value = null
            _meetingSessionId.value = null
            wakeLock?.apply { if (isHeld) release() }
            wakeLock = null
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (!_isRecording.value || _isPaused.value) return
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            _isPaused.value = true
            updateNotification(_durationSec.value)
            InterpreterWidgetProvider.updateWidgetState(this)
            Log.d(TAG, "Recording paused, segment finalized: ${currentSegmentFile?.absolutePath}")

            // Publish segment info
            currentSegmentFile?.let { file ->
                val sessId = _meetingSessionId.value.orEmpty()
                _latestSegment.value = SegmentInfo(sessId, file.absolutePath, segmentDurationSec)
            }

            segmentDurationSec = 0
            segmentIndex++
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
        }
    }

    private fun resumeRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        try {
            val sessionId = _meetingSessionId.value ?: return
            val cacheDir = externalCacheDir ?: cacheDir
            val outputFile = File(cacheDir, "${sessionId}_part_${segmentIndex}.m4a")
            currentSegmentFile = outputFile
            segmentDurationSec = 0

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            _isPaused.value = false
            updateNotification(_durationSec.value)
            InterpreterWidgetProvider.updateWidgetState(this)
            Log.d(TAG, "Recording resumed segment $segmentIndex: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
        }
    }

    private fun stopRecording() {
        if (!_isRecording.value) return
        Log.d(TAG, "Stopping recording")
        val wasPaused = _isPaused.value
        _isRecording.value = false
        _isPaused.value = false
        serviceJob?.cancel()
        InterpreterWidgetProvider.updateWidgetState(this)

        try {
            if (!wasPaused) {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                
                // Publish final segment
                currentSegmentFile?.let { file ->
                    val sessId = _meetingSessionId.value.orEmpty()
                    _latestSegment.value = SegmentInfo(sessId, file.absolutePath, segmentDurationSec)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }

        // Release WakeLock
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Meeting Recorder Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(durationSec: Int): Notification {
        val minutes = durationSec / 60
        val seconds = durationSec % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (_isPaused.value) "Đang tạm dừng cuộc họp..." else "Đang ghi âm cuộc họp..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Thời lượng: $timeString")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(durationSec: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(durationSec))
    }
}
