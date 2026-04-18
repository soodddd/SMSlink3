package com.smslink.network.monitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络监控器
 * 监控网络状态变化、蓝牙状态和连接质量
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothAdapter: BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    /**
     * 观察网络状态变化
     */
    fun observeNetworkState(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger.d(TAG, "Network available: $network")
                trySend(NetworkState.Available(getNetworkType(network)))
            }

            override fun onLost(network: Network) {
                logger.d(TAG, "Network lost: $network")
                trySend(NetworkState.Lost)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                logger.d(TAG, "Network capabilities changed: $network")
                trySend(NetworkState.Available(getNetworkType(network)))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, callback)

        // 发送当前状态
        val currentState = getCurrentNetworkState()
        trySend(currentState)

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * 获取当前网络状态
     */
    fun getCurrentNetworkState(): NetworkState {
        val connectivity = connectivityManager ?: return NetworkState.Lost
        val network = connectivity.activeNetwork ?: return NetworkState.Lost
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkState.Lost

        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            NetworkState.Available(getNetworkType(network))
        } else {
            NetworkState.Lost
        }
    }

    /**
     * 检查是否连接到 WiFi
     */
    fun isWifiConnected(): Boolean {
        val connectivity = connectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 检查是否连接到移动网络
     */
    fun isCellularConnected(): Boolean {
        val connectivity = connectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * 观察蓝牙状态变化
     */
    fun observeBluetoothState(): Flow<BluetoothState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR
                        )
                        when (state) {
                            BluetoothAdapter.STATE_ON -> {
                                logger.d(TAG, "Bluetooth enabled")
                                trySend(BluetoothState.Enabled)
                            }
                            BluetoothAdapter.STATE_OFF -> {
                                logger.d(TAG, "Bluetooth disabled")
                                trySend(BluetoothState.Disabled)
                            }
                            BluetoothAdapter.STATE_TURNING_ON -> {
                                logger.d(TAG, "Bluetooth turning on")
                                trySend(BluetoothState.TurningOn)
                            }
                            BluetoothAdapter.STATE_TURNING_OFF -> {
                                logger.d(TAG, "Bluetooth turning off")
                                trySend(BluetoothState.TurningOff)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        // 发送当前状态
        val currentState = if (isBluetoothAvailable()) {
            BluetoothState.Enabled
        } else {
            BluetoothState.Disabled
        }
        trySend(currentState)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * 检查是否是热点网络
     */
    fun isHotspotNetwork(): Boolean {
        try {
            val wifi = wifiManager ?: return false
            val wifiInfo = wifi.connectionInfo
            val ipAddress = wifiInfo.ipAddress

            // 热点模式通常使用 192.168.43.x 或 192.168.49.x 网段
            val ip = intToIp(ipAddress)
            return ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
        } catch (e: Exception) {
            logger.e(TAG, "Error checking hotspot network", e)
            return false
        }
    }

    /**
     * 获取本地 IP 地址
     */
    fun getLocalIpAddress(): String? {
        try {
            val wifi = wifiManager ?: return null
            if (!isWifiConnected()) return null
            val wifiInfo = wifi.connectionInfo
            return intToIp(wifiInfo.ipAddress)
        } catch (e: Exception) {
            logger.e(TAG, "Error getting local IP address", e)
            return null
        }
    }

    /**
     * 将整数 IP 转换为字符串
     */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /**
     * 获取网络类型
     */
    private fun getNetworkType(network: Network): NetworkType {
        val connectivity = connectivityManager ?: return NetworkType.UNKNOWN
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}

/**
 * 网络状态
 */
sealed class NetworkState {
    data class Available(val type: NetworkType) : NetworkState()
    object Lost : NetworkState()
}

/**
 * 网络类型
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    BLUETOOTH,
    ETHERNET,
    UNKNOWN
}

/**
 * 蓝牙状态
 */
sealed class BluetoothState {
    object Enabled : BluetoothState()
    object Disabled : BluetoothState()
    object TurningOn : BluetoothState()
    object TurningOff : BluetoothState()
}
