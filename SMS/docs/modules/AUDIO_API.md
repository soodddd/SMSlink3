# Audio API 文档

## 状态说明

**本文件中的接口定义均为"阶段规划中的目标 API 草案"，当前仓库未提交对应 Native 实现。**

以下内容仅用于约束后续开发方向，不能视为当前可调用接口。

---

## OpusCodec (Planned API)

```kotlin
class OpusCodec {
    external fun initEncoder(
        sampleRate: Int = 48000,
        channels: Int = 1,
        bitrate: Int = 24000
    ): Long
    
    external fun encode(
        encoderHandle: Long,
        pcmData: ShortArray
    ): ByteArray?
    
    external fun initDecoder(
        sampleRate: Int = 48000,
        channels: Int = 1
    ): Long
    
    external fun decode(
        decoderHandle: Long,
        opusData: ByteArray
    ): ShortArray?
    
    external fun destroyEncoder(encoderHandle: Long)
    external fun destroyDecoder(decoderHandle: Long)
}
```

**状态**: 未实现 (需要 Native C++ 实现和 Opus 库)

---

## AudioProcessor (Planned API)

```kotlin
class AudioProcessor {
    external fun init(
        sampleRate: Int = 48000,
        enableAec: Boolean = true,
        enableNs: Boolean = true,
        enableAgc: Boolean = true
    ): Long
    
    external fun process(
        handle: Long,
        audioData: ShortArray
    ): ShortArray?
    
    external fun destroy(handle: Long)
}
```

**状态**: 未实现 (需要 Native C++ 实现和 WebRTC 库)

---

## AudioCapture (Planned API)

```kotlin
class AudioCapture(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    fun start(): Flow<ShortArray>
    fun stop()
    fun isRecording(): Boolean
}
```

**状态**: 未实现

---

## AudioPlayer (Planned API)

```kotlin
class AudioPlayer(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    suspend fun play(audioData: ShortArray)
    fun stop()
    fun isPlaying(): Boolean
}
```

**状态**: 未实现
