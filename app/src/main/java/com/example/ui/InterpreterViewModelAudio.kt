package com.example.ui

import android.app.Application
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "InterpreterVMAudio"

internal suspend fun InterpreterViewModel.playAudioMp3(bytes: ByteArray) = withContext(Dispatchers.IO) {
    try {
        val cacheFile = File(getApplication<Application>().cacheDir, "ai_speech.mp3")
        FileOutputStream(cacheFile).use { it.write(bytes) }
        
        withContext(Dispatchers.Main) {
            stopPlayback()
            _isSpeaking.value = true
            Log.d(TAG, "playAudioMp3: MediaPlayer start requested at ${System.currentTimeMillis()} bytes=${bytes.size}")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(cacheFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isSpeaking.value = false
                    it.release()
                    mediaPlayer = null
                }
                start()
            }
            Log.d(TAG, "playAudioMp3: MediaPlayer.start() completed at ${System.currentTimeMillis()}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "playAudioMp3 failed", e)
        _isSpeaking.value = false
    }
}

internal fun InterpreterViewModel.addWavHeaderToPcm(pcmBytes: ByteArray, sampleRate: Int = 24000): ByteArray {
    val headerSize = 44
    val totalDataLen = pcmBytes.size
    val totalAudioLen = totalDataLen + headerSize - 8
    val byteRate = sampleRate * 2 * 1 // sampleRate * bytesPerSample * channels

    val header = ByteArray(headerSize)
    header[0] = 'R'.toByte() // RIFF
    header[1] = 'I'.toByte()
    header[2] = 'F'.toByte()
    header[3] = 'F'.toByte()
    header[4] = (totalAudioLen and 0xff).toByte()
    header[5] = ((totalAudioLen shr 8) and 0xff).toByte()
    header[6] = ((totalAudioLen shr 16) and 0xff).toByte()
    header[7] = ((totalAudioLen shr 24) and 0xff).toByte()
    header[8] = 'W'.toByte() // WAVE
    header[9] = 'A'.toByte()
    header[10] = 'V'.toByte()
    header[11] = 'E'.toByte()
    header[12] = 'f'.toByte() // fmt 
    header[13] = 'm'.toByte()
    header[14] = 't'.toByte()
    header[15] = ' '.toByte()
    header[16] = 16 // Subchunk1Size = 16 for PCM
    header[17] = 0
    header[18] = 0
    header[19] = 0
    header[20] = 1 // AudioFormat = 1 (PCM)
    header[21] = 0
    header[22] = 1 // NumChannels = 1 (Mono)
    header[23] = 0
    header[24] = (sampleRate and 0xff).toByte()
    header[25] = ((sampleRate shr 8) and 0xff).toByte()
    header[26] = ((sampleRate shr 16) and 0xff).toByte()
    header[27] = ((sampleRate shr 24) and 0xff).toByte()
    header[28] = (byteRate and 0xff).toByte()
    header[29] = ((byteRate shr 8) and 0xff).toByte()
    header[30] = ((byteRate shr 16) and 0xff).toByte()
    header[31] = ((byteRate shr 24) and 0xff).toByte()
    header[32] = 2 // BlockAlign = NumChannels * BitsPerSample/8 = 2
    header[33] = 0
    header[34] = 16 // BitsPerSample = 16
    header[35] = 0
    header[36] = 'd'.toByte() // data
    header[37] = 'a'.toByte()
    header[38] = 't'.toByte()
    header[39] = 'a'.toByte()
    header[40] = (totalDataLen and 0xff).toByte()
    header[41] = ((totalDataLen shr 8) and 0xff).toByte()
    header[42] = ((totalDataLen shr 16) and 0xff).toByte()
    header[43] = ((totalDataLen shr 24) and 0xff).toByte()

    val wavBytes = ByteArray(headerSize + pcmBytes.size)
    System.arraycopy(header, 0, wavBytes, 0, headerSize)
    System.arraycopy(pcmBytes, 0, wavBytes, headerSize, pcmBytes.size)
    return wavBytes
}

internal suspend fun InterpreterViewModel.playAudioFromBase64(base64: String) = withContext(Dispatchers.IO) {
    try {
        val rawDecoded = Base64.decode(base64, Base64.DEFAULT)
        Log.d(TAG, "playAudioFromBase64: decoded size = ${rawDecoded.size} bytes")
        if (rawDecoded.isNotEmpty()) {
            val limit = minOf(rawDecoded.size, 100)
            val sampleText = String(rawDecoded.take(limit).toByteArray(), Charsets.UTF_8)
            Log.d(TAG, "playAudioFromBase64: first $limit bytes (text) = $sampleText")
            val hexLimit = minOf(rawDecoded.size, 20)
            Log.d(TAG, "playAudioFromBase64: first $hexLimit bytes (hex) = ${rawDecoded.take(hexLimit).joinToString("") { String.format("%02X ", it) }}")
        }
        
        var decoded = rawDecoded
        val isRiff = decoded.size >= 12 &&
                     decoded[0] == 'R'.toByte() && decoded[1] == 'I'.toByte() && 
                     decoded[2] == 'F'.toByte() && decoded[3] == 'F'.toByte() &&
                     decoded[8] == 'W'.toByte() && decoded[9] == 'A'.toByte() && 
                     decoded[10] == 'V'.toByte() && decoded[11] == 'E'.toByte()
                     
        if (!isRiff) {
            Log.d(TAG, "playAudioFromBase64: Detected raw PCM, adding WAV header (24kHz Mono 16-bit)")
            decoded = addWavHeaderToPcm(decoded, 24000)
        } else {
            // Fix the WAV header size for streamed PCM/WAV payloads with bad metadata
            decoded = fixWavHeaderSize(decoded)
        }
        
        val cacheFile = File(getApplication<Application>().cacheDir, "ai_speech.wav")
        FileOutputStream(cacheFile).use { it.write(decoded) }
        
        withContext(Dispatchers.Main) {
            stopPlayback()
            _isSpeaking.value = true
            Log.d(TAG, "playAudioFromBase64: MediaPlayer start requested at ${System.currentTimeMillis()} decodedBytes=${decoded.size}")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(cacheFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isSpeaking.value = false
                    it.release()
                    mediaPlayer = null
                }
                start()
            }
            Log.d(TAG, "playAudioFromBase64: MediaPlayer.start() completed at ${System.currentTimeMillis()}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "playAudioFromBase64 failed", e)
        _isSpeaking.value = false
    }
}

/**
 * FIX FOR QWEN TTS WAV PLAYBACK ISSUE
 */
internal fun InterpreterViewModel.fixWavHeaderSize(wavBytes: ByteArray): ByteArray {
    if (wavBytes.size < 44) return wavBytes
    
    // Verify WAV "RIFF" and "WAVE" signature
    val isRiff = wavBytes[0] == 'R'.toByte() && wavBytes[1] == 'I'.toByte() && 
                 wavBytes[2] == 'F'.toByte() && wavBytes[3] == 'F'.toByte()
    val isWave = wavBytes[8] == 'W'.toByte() && wavBytes[9] == 'A'.toByte() && 
                 wavBytes[10] == 'V'.toByte() && wavBytes[11] == 'E'.toByte()
    
    if (!isRiff || !isWave) return wavBytes
    
    val fixed = wavBytes.clone()
    val fileSizeMinus8 = fixed.size - 8
    val dataSize = fixed.size - 44
    
    // Write actual file size minus 8 to byte index 4-7 (Little Endian)
    fixed[4] = (fileSizeMinus8 and 0xFF).toByte()
    fixed[5] = ((fileSizeMinus8 shr 8) and 0xFF).toByte()
    fixed[6] = ((fileSizeMinus8 shr 16) and 0xFF).toByte()
    fixed[7] = ((fileSizeMinus8 shr 24) and 0xFF).toByte()
    
    // Write actual raw audio data size to byte index 40-43 (Little Endian)
    fixed[40] = (dataSize and 0xFF).toByte()
    fixed[41] = ((dataSize shr 8) and 0xFF).toByte()
    fixed[42] = ((dataSize shr 16) and 0xFF).toByte()
    fixed[43] = ((dataSize shr 24) and 0xFF).toByte()
    
    Log.d(TAG, "fixWavHeaderSize: fixed WAV file size field to $fileSizeMinus8 and data size field to $dataSize")
    return fixed
}

internal fun InterpreterViewModel.stopPlayback() {
    runCatching {
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
        textToSpeechSystem?.stop()
    }
    clearSpeechQueue()
    _isSpeaking.value = false
}
