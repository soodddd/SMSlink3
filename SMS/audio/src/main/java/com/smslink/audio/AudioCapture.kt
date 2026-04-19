package com.smslink.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * 音频捕获器
 * 负责从麦克风捕获音频数据
 */
class AudioCapture {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    companion object {
        const val SAMPLE_RATE = 16000 // 16kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_MULTIPLIER = 2
    }

    /**
     * 开始捕获音频
     * @return Flow<ByteArray> 音频数据流
     */
    fun startCapture(): Flow<ByteArray> = flow {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        audioRecord?.startRecording()
        isRecording = true

        val buffer = ByteArray(bufferSize)

        while (coroutineContext.isActive && isRecording) {
            val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (readBytes > 0) {
                emit(buffer.copyOf(readBytes))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 停止捕获
     */
    fun stopCapture() {
        isRecording = false
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
    }
}
