package com.example.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.database.AppDatabase
import com.example.data.database.MeetingAudioSegment
import com.example.data.repository.TranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

private const val TAG = "MeetingRecordService"
private const val CHANNEL_ID = "meeting_record_channel"
private const val NOTIFICATION_ID = 2026

class MeetingRecordService : Service() {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecordingAudio = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val chunkDurationSec = 30 // Tự động chia nhỏ file mỗi 30 giây

    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

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
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeetingRecord::WakeLock").apply {
                acquire()
            }

            segmentIndex = 0
            segmentDurationSec = 0
            val sessionId = "MEETING_" + System.currentTimeMillis()
            _meetingSessionId.value = sessionId
            _outputFilePath.value = null

            _isRecording.value = true
            _isPaused.value = false
            _durationSec.value = 0
            InterpreterWidgetProvider.updateWidgetState(this)

            // Start AudioRecord Background Thread
            startAudioRecordingThread()

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
            Log.d(TAG, "Recording started sessionId: $sessionId")

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

    private fun startAudioRecordingThread() {
        isRecordingAudio = true
        recordingThread = Thread({
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
            val audioData = ShortArray(bufferSize / 2)

            val meetingsDir = File(filesDir, "meetings")
            if (!meetingsDir.exists()) {
                meetingsDir.mkdirs()
            }

            var fos: FileOutputStream? = null
            var totalWrittenBytes = 0L
            var currentSegmentWrittenBytes = 0L
            val bytesPerSecond = sampleRate * 2 // 16000 * 2 = 32000 bytes/s
            val chunkBytesLimit = chunkDurationSec * bytesPerSecond

            val db = AppDatabase.getDatabase(applicationContext)
            val repository = TranslationRepository(db.translationDao())

            try {
                if (android.content.pm.PackageManager.PERMISSION_GRANTED == androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    )) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                } else {
                    Log.e(TAG, "RECORD_AUDIO permission not granted")
                    _isRecording.value = false
                    stopSelf()
                    return@Thread
                }

                audioRecord?.startRecording()

                fun openNewSegmentFile() {
                    val sessionId = _meetingSessionId.value ?: return
                    val segmentFile = File(meetingsDir, "${sessionId}_part_${segmentIndex}.wav")
                    currentSegmentFile = segmentFile
                    currentSegmentWrittenBytes = 0

                    fos = FileOutputStream(segmentFile)
                    // Write dummy WAV header (44 bytes) to reserve space
                    fos?.write(ByteArray(44))
                    Log.d(TAG, "Opened new segment file: ${segmentFile.absolutePath}")
                }

                openNewSegmentFile()
                var wasPausedLastFrame = false

                while (isRecordingAudio) {
                    val isPausedNow = _isPaused.value
                    if (isPausedNow != wasPausedLastFrame) {
                        if (isPausedNow) {
                            // Finalize current segment WAV file when paused
                            fos?.close()
                            fos = null
                            val finalizedFile = currentSegmentFile
                            if (finalizedFile != null && finalizedFile.exists() && currentSegmentWrittenBytes > 0) {
                                writeWavHeader(finalizedFile, currentSegmentWrittenBytes)
                                val currentDuration = (currentSegmentWrittenBytes / bytesPerSecond).toInt().coerceAtLeast(1)
                                val sessId = _meetingSessionId.value.orEmpty()
                                val currentSegIndex = segmentIndex
                                val segmentStartTime = (totalWrittenBytes - currentSegmentWrittenBytes) * 1000 / bytesPerSecond

                                serviceScope.launch(Dispatchers.IO) {
                                    repository.insertSegment(
                                        MeetingAudioSegment(
                                            sessionId = sessId,
                                            segmentIndex = currentSegIndex,
                                            filePath = finalizedFile.absolutePath,
                                            startTimeMs = segmentStartTime,
                                            durationSec = currentDuration,
                                            status = "PENDING"
                                        )
                                    )
                                    _latestSegment.value = SegmentInfo(sessId, finalizedFile.absolutePath, currentDuration)
                                }
                                segmentIndex++
                            }
                        } else {
                            // Open new segment file when resumed
                            openNewSegmentFile()
                        }
                        wasPausedLastFrame = isPausedNow
                    }

                    val readSize = audioRecord?.read(audioData, 0, audioData.size) ?: -1
                    if (readSize > 0) {
                        if (!isPausedNow) {
                            // Convert ShortArray to ByteArray (Little-Endian)
                            val byteBuffer = ByteArray(readSize * 2)
                            for (i in 0 until readSize) {
                                val value = audioData[i]
                                byteBuffer[i * 2] = (value.toInt() and 0xff).toByte()
                                byteBuffer[i * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
                            }

                            fos?.write(byteBuffer)
                            currentSegmentWrittenBytes += byteBuffer.size
                            totalWrittenBytes += byteBuffer.size

                            // Check chunk limit
                            if (currentSegmentWrittenBytes >= chunkBytesLimit) {
                                fos?.close()
                                fos = null
                                val finalizedFile = currentSegmentFile
                                val currentDuration = (currentSegmentWrittenBytes / bytesPerSecond).toInt()
                                val sessId = _meetingSessionId.value.orEmpty()
                                val currentSegIndex = segmentIndex

                                if (finalizedFile != null && finalizedFile.exists()) {
                                    writeWavHeader(finalizedFile, currentSegmentWrittenBytes)
                                    val segmentStartTime = (totalWrittenBytes - currentSegmentWrittenBytes) * 1000 / bytesPerSecond
                                    serviceScope.launch(Dispatchers.IO) {
                                        repository.insertSegment(
                                            MeetingAudioSegment(
                                                sessionId = sessId,
                                                segmentIndex = currentSegIndex,
                                                filePath = finalizedFile.absolutePath,
                                                startTimeMs = segmentStartTime,
                                                durationSec = currentDuration,
                                                status = "PENDING"
                                            )
                                        )
                                        _latestSegment.value = SegmentInfo(sessId, finalizedFile.absolutePath, currentDuration)
                                    }
                                }

                                segmentIndex++
                                openNewSegmentFile()
                            }
                        }
                    } else {
                        Thread.sleep(10)
                    }
                }

                // Finalize last segment on stop
                fos?.close()
                fos = null
                val finalizedFile = currentSegmentFile
                if (finalizedFile != null && finalizedFile.exists() && currentSegmentWrittenBytes > 0) {
                    writeWavHeader(finalizedFile, currentSegmentWrittenBytes)
                    val currentDuration = (currentSegmentWrittenBytes / bytesPerSecond).toInt().coerceAtLeast(1)
                    val sessId = _meetingSessionId.value.orEmpty()
                    val currentSegIndex = segmentIndex
                    val segmentStartTime = (totalWrittenBytes - currentSegmentWrittenBytes) * 1000 / bytesPerSecond

                    serviceScope.launch(Dispatchers.IO) {
                        repository.insertSegment(
                            MeetingAudioSegment(
                                sessionId = sessId,
                                segmentIndex = currentSegIndex,
                                filePath = finalizedFile.absolutePath,
                                startTimeMs = segmentStartTime,
                                durationSec = currentDuration,
                                status = "PENDING"
                            )
                        )
                        _latestSegment.value = SegmentInfo(sessId, finalizedFile.absolutePath, currentDuration)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in recording thread", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (ex: Exception) {
                    Log.e(TAG, "Error releasing audioRecord", ex)
                }
                audioRecord = null
                fos?.close()
            }
        }, "MeetingRecordThread")
        recordingThread?.start()
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long) {
        val totalDataLen = pcmDataLength + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2L

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                val header = ByteArray(44)
                header[0] = 'R'.code.toByte() // RIFF
                header[1] = 'I'.code.toByte()
                header[2] = 'F'.code.toByte()
                header[3] = 'F'.code.toByte()
                header[4] = (totalDataLen and 0xff).toByte()
                header[5] = ((totalDataLen shr 8) and 0xff).toByte()
                header[6] = ((totalDataLen shr 16) and 0xff).toByte()
                header[7] = ((totalDataLen shr 24) and 0xff).toByte()
                header[8] = 'W'.code.toByte() // WAVE
                header[9] = 'A'.code.toByte()
                header[10] = 'V'.code.toByte()
                header[11] = 'E'.code.toByte()
                header[12] = 'f'.code.toByte() // fmt 
                header[13] = 'm'.code.toByte()
                header[14] = 't'.code.toByte()
                header[15] = ' '.code.toByte()
                header[16] = 16 // Subchunk1Size (16 for PCM)
                header[17] = 0
                header[18] = 0
                header[19] = 0
                header[20] = 1 // AudioFormat (1 for PCM)
                header[21] = 0
                header[22] = channels.toByte() // NumChannels (1 for mono)
                header[23] = 0
                header[24] = (sampleRate and 0xff).toByte() // SampleRate
                header[25] = ((sampleRate shr 8) and 0xff).toByte()
                header[26] = ((sampleRate shr 16) and 0xff).toByte()
                header[27] = ((sampleRate shr 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte() // ByteRate
                header[29] = ((byteRate shr 8) and 0xff).toByte()
                header[30] = ((byteRate shr 16) and 0xff).toByte()
                header[31] = ((byteRate shr 24) and 0xff).toByte()
                header[32] = (channels * 2).toByte() // BlockAlign
                header[33] = 0
                header[34] = 16 // BitsPerSample
                header[35] = 0
                header[36] = 'd'.code.toByte() // data
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                header[40] = (pcmDataLength and 0xff).toByte() // Subchunk2Size
                header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
                header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
                header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

                raf.write(header)
                Log.d(TAG, "Wrote WAV header to ${file.name}, data size: $pcmDataLength")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV header", e)
        }
    }

    private fun pauseRecording() {
        if (!_isRecording.value || _isPaused.value) return
        _isPaused.value = true
        updateNotification(_durationSec.value)
        InterpreterWidgetProvider.updateWidgetState(this)
        Log.d(TAG, "Recording paused.")
    }

    private fun resumeRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        _isPaused.value = false
        segmentDurationSec = 0
        updateNotification(_durationSec.value)
        InterpreterWidgetProvider.updateWidgetState(this)
        Log.d(TAG, "Recording resumed.")
    }

    private fun stopRecording() {
        if (!_isRecording.value) return
        Log.d(TAG, "Stopping recording")
        _isRecording.value = false
        _isPaused.value = false
        isRecordingAudio = false
        serviceJob?.cancel()
        InterpreterWidgetProvider.updateWidgetState(this)

        try {
            recordingThread?.join(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining recording thread", e)
        }

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
