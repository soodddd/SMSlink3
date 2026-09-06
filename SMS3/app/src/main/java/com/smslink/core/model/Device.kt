package com.smslink.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Device entity.
 */
@Entity(
    tableName = "devices",
    indices = [
        Index(value = ["isPaired"]),
        Index(value = ["lastSeen"])
    ]
)
data class Device(
    @PrimaryKey val id: String,
    val name: String,
    val type: DeviceType,
    val role: DeviceRole,
    val publicKey: String? = null,
    val lastSeen: Long,
    val isPaired: Boolean,
    val isConnected: Boolean = false,
    /** Last discovered LAN/hotspot endpoint. Never use a guessed address. */
    val ipAddress: String? = null,
    val port: Int = 1716,
    /** Bonded Bluetooth Classic address used only for RFCOMM fallback. */
    val bluetoothAddress: String? = null
)

/**
 * Device type.
 */
enum class DeviceType {
    ANDROID,
    PHONE,
    TABLET,
    FOLDABLE
}

/**
 * Device role.
 */
enum class DeviceRole {
    MAIN,
    SECONDARY,
    CELLULAR_SOURCE
}

/**
 * Pairing result.
 */
data class PairResult(
    val success: Boolean,
    val message: String,
    val device: Device? = null
)
