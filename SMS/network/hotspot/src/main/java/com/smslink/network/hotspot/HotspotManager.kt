package com.smslink.network.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class HotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Disabled)
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    suspend fun startHotspot(): Result<HotspotInfo> {
        hotspotReservation?.let {
            return getHotspotInfo()?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Hotspot already active but info missing"))
        }

        _hotspotState.value = HotspotState.Enabling

        return suspendCancellableCoroutine { continuation ->
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        val config = reservation.softApConfiguration
                        val info = HotspotInfo(
                            ssid = config?.ssid ?: error("Missing SSID"),
                            passphrase = config?.passphrase ?: error("Missing passphrase"),
                            hostAddress = getWifiApIpAddress(),
                            isEnabled = true
                        )
                        _hotspotState.value = HotspotState.Enabled(info)
                        continuation.resume(Result.success(info))
                    }

                    override fun onFailed(reason: Int) {
                        hotspotReservation = null
                        val error = IllegalStateException("LocalOnlyHotspot 启动失败: $reason")
                        _hotspotState.value = HotspotState.Error(error)
                        continuation.resume(Result.failure(error))
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                        _hotspotState.value = HotspotState.Disabled
                    }
                },
                null
            )
        }
    }

    fun stopHotspot(): Result<Unit> = runCatching {
        _hotspotState.value = HotspotState.Disabling
        hotspotReservation?.close()
        hotspotReservation = null
        _hotspotState.value = HotspotState.Disabled
    }

    fun getHotspotInfo(): HotspotInfo? {
        return when (val state = _hotspotState.value) {
            is HotspotState.Enabled -> state.info
            else -> null
        }
    }

    private fun getWifiApIpAddress(): String {
        return "192.168.49.1" // LocalOnlyHotspot 默认网关地址
    }

    sealed class HotspotState {
        object Disabled : HotspotState()
        object Enabling : HotspotState()
        data class Enabled(val info: HotspotInfo) : HotspotState()
        object Disabling : HotspotState()
        data class Error(val exception: Exception) : HotspotState()
    }

    data class HotspotInfo(
        val ssid: String,
        val passphrase: String,
        val hostAddress: String,
        val isEnabled: Boolean
    )
}
