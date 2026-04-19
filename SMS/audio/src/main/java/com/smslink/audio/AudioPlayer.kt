package com.smslink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音频播放器
 * 负责播放接收到的音频数据
 */
class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    companion object {
        const val SAMPLE_RATE = 16000 // 16kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_MULTIPLIER = 2
    }

    /**
     * 初始化播放器
     */
    fun initialize() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack initialization failed")
        }
    }

    /**
     * 开始播放
     */
    fun startPlaying() {
        if (!isPlaying) {
            audioTrack?.play()
            isPlaying = true
        }
    }

    /**
     * 播放音频数据
     */
    suspend fun playAudio(audioData: ByteArray) = withContext(Dispatchers.IO) {
        if (isPlaying) {
            audioTrack?.write(audioData, 0, audioData.size)
        }
    }

    /**
     * 停止播放
     */
    fun stopPlaying() {
        isPlaying = false
        audioTrack?.apply {
            if (state == AudioTrack.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioTrack = null
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }
}
