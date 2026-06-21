package com.example.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private const val TAG = "AudioRecorder"

/**
 * Low-latency microphone recorder that delivers raw PCM frames in real-time.
 *
 * Two modes:
 *  1. Streaming mode  – onFrame() called per ~40ms chunk while recording.
 *     Used by the WebSocket real-time ASR pipeline.
 *  2. Batch mode (legacy) – onStop() called with full WAV bytes after stopRecording().
 *     Kept as fallback for REST-based ASR if needed.
 */
class AudioRecorder {
    val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Use a small buffer size (roughly 40ms per frame) for low latency streaming
    private val frameSize: Int = sampleRate / 25  // 640 samples ≈ 40ms
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val bufferSize = maxOf(minBufferSize, frameSize * 4)

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingJob: Job? = null

    /**
     * Start streaming mode.
     * [onFrame]     called with (pcmSamples, validLength) for each ~40ms audio chunk.
     * [onRmsChanged] called with a 0..1 amplitude value for UI visualizer.
     */
    @SuppressLint("MissingPermission")
    fun startStreaming(
        scope: CoroutineScope,
        onFrame: (ShortArray, Int) -> Unit,
        onRmsChanged: (Float) -> Unit
    ): Boolean {
        if (isRecording) return true
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                runCatching { audioRecord?.release() }
                audioRecord = null
                return false
            }
            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Streaming started (frameSize=$frameSize bufferSize=$bufferSize)")

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(frameSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
                    if (read > 0) {
                        // Calculate RMS for visualizer
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                        val rms = sqrt(sum / read)
                        val normalized = minOf(1f, (rms / 32768.0).toFloat() * 10f)
                        scope.launch(Dispatchers.Main) { onRmsChanged(normalized) }

                        // Deliver frame to caller (WebSocket sender)
                        onFrame(buffer.copyOf(read), read)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting streaming", e)
            runCatching { audioRecord?.release() }
            audioRecord = null
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        audioRecord = null
        recordingJob?.cancel()
        recordingJob = null
        Log.d(TAG, "Recording stopped")
    }
}
