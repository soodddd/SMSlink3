package com.smslink.network.protocol

enum class ErrorCode(val value: Int) {
    UNKNOWN_ERROR(0),
    INVALID_MESSAGE(1),
    UNSUPPORTED_VERSION(2),
    AUTHENTICATION_FAILED(3),
    DEVICE_NOT_PAIRED(4),
    PERMISSION_DENIED(5),
    RESOURCE_NOT_FOUND(6),
    TRANSFER_FAILED(7);

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromValue(value: Int): ErrorCode? = map[value]
    }
}
