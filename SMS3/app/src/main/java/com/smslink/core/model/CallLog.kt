package com.smslink.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通话记录实体
 */
@Entity(
    tableName = "call_logs",
    indices = [
        Index(value = ["phoneNumber"]),
        Index(value = ["timestamp"]),
        Index(value = ["isSynced"])
    ]
)
data class CallLog(
    @PrimaryKey
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val type: CallDirection,
    val timestamp: Long,
    val duration: Long,
    val isSynced: Boolean = false
)
