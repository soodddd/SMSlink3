package com.smslink.network.protocol

enum class MessageType(val value: Byte) {
    // 设备管理 (0x00-0x0F)
    DEVICE_DISCOVERY(0x00),
    DEVICE_PAIR_REQUEST(0x01),
    DEVICE_PAIR_RESPONSE(0x02),
    DEVICE_HEARTBEAT(0x03),
    DEVICE_ROLE_SWITCH(0x04),

    // 通知同步 (0x10-0x1F)
    NOTIFICATION_SYNC(0x10),
    NOTIFICATION_ACTION(0x11),
    NOTIFICATION_DISMISS(0x12),

    // 通话功能 (0x20-0x2F)
    CALL_INCOMING(0x20),
    CALL_ANSWER(0x21),
    CALL_END(0x22),
    CALL_AUDIO_DATA(0x23),

    // 文件传输 (0x30-0x3F)
    FILE_TRANSFER_REQUEST(0x30),
    FILE_TRANSFER_ACCEPT(0x31),
    FILE_TRANSFER_REJECT(0x32),
    FILE_TRANSFER_DATA(0x33),
    FILE_TRANSFER_COMPLETE(0x34),
    FILE_TRANSFER_PROGRESS(0x35),
    FILE_TRANSFER_CANCEL(0x36),

    // 控制消息 (0xF0-0xFF)
    ACK(0xF0.toByte()),
    ERROR(0xF1.toByte());

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromValue(value: Byte): MessageType? = map[value]
    }
}
