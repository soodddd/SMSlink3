package com.smslink.audio

/**
 * 音频编解码器
 * 负责音频数据的编码和解码
 *
 * 当前使用 PCM 16-bit 格式，未来可以扩展支持 Opus 等编解码器
 */
class AudioCodec {

    /**
     * 编码音频数据
     * 当前直接返回原始 PCM 数据
     */
    fun encode(pcmData: ByteArray): ByteArray {
        // TODO: 未来可以添加 Opus 编码以减少带宽
        return pcmData
    }

    /**
     * 解码音频数据
     * 当前直接返回原始 PCM 数据
     */
    fun decode(encodedData: ByteArray): ByteArray {
        // TODO: 未来可以添加 Opus 解码
        return encodedData
    }

    /**
     * 获取编码后的数据大小估算
     */
    fun getEncodedSize(pcmSize: Int): Int {
        // 当前未压缩，大小相同
        return pcmSize
    }

    /**
     * 获取解码后的数据大小估算
     */
    fun getDecodedSize(encodedSize: Int): Int {
        // 当前未压缩，大小相同
        return encodedSize
    }
}
