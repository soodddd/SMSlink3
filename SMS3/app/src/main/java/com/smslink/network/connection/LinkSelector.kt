package com.smslink.network.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 链路选择器
 * 负责网络环境检测、链路质量评估和自动选择最佳链路
 */
@Singleton
class LinkSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothAdapter: BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    /**
     * 选择最佳链路类型
     * 根据网络环境和设备状态自动选择
     */
    suspend fun selectBestLink(deviceId: String, targetIp: String?, bluetoothAddress: String?): LinkType? = withContext(Dispatchers.IO) {
        logger.i(TAG, "Selecting best link for device: $deviceId")

        if (connectivityManager == null || wifiManager == null) {
            return@withContext when {
                !targetIp.isNullOrEmpty() -> LinkType.WIFI_LAN
                !bluetoothAddress.isNullOrEmpty() -> LinkType.BLUETOOTH
                else -> null
            }
        }

        // 按优先级尝试每种链路
        for (linkType in LinkType.getAllByPriority()) {
            if (isLinkAvailable(linkType, targetIp, bluetoothAddress)) {
                logger.i(TAG, "Selected link: ${linkType.description} for device: $deviceId")
                return@withContext linkType
            }
        }

        logger.w(TAG, "No available link found for device: $deviceId")
        return@withContext null
    }

    /**
     * 检查链路是否可用
     */
    suspend fun isLinkAvailable(linkType: LinkType, targetIp: String?, bluetoothAddress: String?): Boolean = withContext(Dispatchers.IO) {
        return@withContext when (linkType) {
            LinkType.WIFI_LAN -> isWifiLanAvailable(targetIp)
            LinkType.WIFI_HOTSPOT -> isWifiHotspotAvailable()
            LinkType.BLUETOOTH -> isBluetoothAvailable(bluetoothAddress)
        }
    }

    /**
     * 检查 WiFi 局域网是否可用
     */
    private suspend fun isWifiLanAvailable(targetIp: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查 WiFi 是否连接
            if (!isWifiConnected()) {
                logger.d(TAG, "WiFi not connected")
                return@withContext false
            }

            // 如果没有目标 IP，只检查 WiFi 连接状态
            if (targetIp.isNullOrEmpty()) {
                logger.d(TAG, "No target IP provided, assuming WiFi LAN available")
                return@withContext true
            }

            // 检查是否在同一局域网
            if (!isInSameSubnet(targetIp)) {
                logger.d(TAG, "Target IP $targetIp not in same subnet")
                return@withContext false
            }

            // 尝试 ping 目标设备
            val reachable = isHostReachable(targetIp, PING_TIMEOUT)
            logger.d(TAG, "WiFi LAN available: $reachable for IP: $targetIp")
            return@withContext reachable

        } catch (e: Exception) {
            logger.e(TAG, "Error checking WiFi LAN availability", e)
            return@withContext false
        }
    }

    /**
     * 检查 WiFi 热点是否可用
     */
    private fun isWifiHotspotAvailable(): Boolean {
        try {
            // 检查 WiFi 是否连接
            if (!isWifiConnected()) {
                logger.d(TAG, "WiFi not connected for hotspot")
                return false
            }

            // 检查是否是热点模式
            // 注意：Android 没有直接的 API 检测是否连接到热点
            // 这里简化处理，可以通过 SSID 或 IP 段判断
            val wifi = wifiManager ?: return false
            val wifiInfo = wifi.connectionInfo
            val ipAddress = wifiInfo.ipAddress

            // 热点模式通常使用 192.168.43.x 或 192.168.49.x 网段
            val isHotspotNetwork = isHotspotIpRange(ipAddress)
            logger.d(TAG, "WiFi Hotspot available: $isHotspotNetwork")
            return isHotspotNetwork

        } catch (e: Exception) {
            logger.e(TAG, "Error checking WiFi Hotspot availability", e)
            return false
        }
    }

    /**
     * 检查蓝牙是否可用
     */
    private fun isBluetoothAvailable(bluetoothAddress: String?): Boolean {
        try {
            // 检查蓝牙适配器
            if (bluetoothAdapter == null) {
                logger.d(TAG, "Bluetooth adapter not available")
                return false
            }

            // 检查蓝牙是否开启
            if (!bluetoothAdapter.isEnabled) {
                logger.d(TAG, "Bluetooth not enabled")
                return false
            }

            // 如果没有蓝牙地址，只检查蓝牙状态
            if (bluetoothAddress.isNullOrEmpty()) {
                logger.d(TAG, "No Bluetooth address provided, assuming Bluetooth available")
                return true
            }

            // 检查设备是否已配对
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
            val isPaired = pairedDevices?.any { it.address == bluetoothAddress } ?: false

            logger.d(TAG, "Bluetooth available: $isPaired for address: $bluetoothAddress")
            return isPaired

        } catch (e: Exception) {
            logger.e(TAG, "Error checking Bluetooth availability", e)
            return false
        }
    }

    /**
     * 检查 WiFi 是否连接
     */
    private fun isWifiConnected(): Boolean {
        val connectivity = connectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 检查是否在同一子网
     */
    private fun isInSameSubnet(targetIp: String): Boolean {
        try {
            val wifi = wifiManager ?: return false
            val wifiInfo = wifi.connectionInfo
            val localIp = intToIp(wifiInfo.ipAddress)
            val netmask = intToIp(wifi.dhcpInfo.netmask)

            logger.d(TAG, "Local IP: $localIp, Target IP: $targetIp, Netmask: $netmask")

            val localAddr = InetAddress.getByName(localIp).address
            val targetAddr = InetAddress.getByName(targetIp).address
            val maskAddr = InetAddress.getByName(netmask).address

            // 比较网络地址
            for (i in localAddr.indices) {
                if ((localAddr[i].toInt() and maskAddr[i].toInt()) != (targetAddr[i].toInt() and maskAddr[i].toInt())) {
                    return false
                }
            }

            return true

        } catch (e: Exception) {
            logger.e(TAG, "Error checking subnet", e)
            return false
        }
    }

    /**
     * 检查主机是否可达
     */
    private suspend fun isHostReachable(host: String, timeout: Int): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val address = InetAddress.getByName(host)
            address.isReachable(timeout)
        } catch (e: Exception) {
            logger.e(TAG, "Error checking host reachability: $host", e)
            false
        }
    }

    /**
     * 检查是否是热点 IP 范围
     */
    private fun isHotspotIpRange(ipAddress: Int): Boolean {
        val ip = intToIp(ipAddress)
        // 常见热点网段：192.168.43.x, 192.168.49.x
        return ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
    }

    /**
     * 将整数 IP 转换为字符串
     */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /**
     * 评估链路质量
     */
    suspend fun evaluateLinkQuality(linkType: LinkType, targetIp: String?): LinkQuality = withContext(Dispatchers.IO) {
        return@withContext when (linkType) {
            LinkType.WIFI_LAN -> evaluateWifiLanQuality(targetIp)
            LinkType.WIFI_HOTSPOT -> evaluateWifiHotspotQuality()
            LinkType.BLUETOOTH -> evaluateBluetoothQuality()
        }
    }

    /**
     * 评估 WiFi 局域网质量
     */
    private suspend fun evaluateWifiLanQuality(targetIp: String?): LinkQuality = withContext(Dispatchers.IO) {
        try {
            if (targetIp.isNullOrEmpty()) {
                return@withContext LinkQuality.GOOD
            }

            // 测量延迟
            val startTime = System.currentTimeMillis()
            val reachable = isHostReachable(targetIp, PING_TIMEOUT)
            val latency = System.currentTimeMillis() - startTime

            return@withContext when {
                !reachable -> LinkQuality.POOR
                latency < 50 -> LinkQuality.EXCELLENT
                latency < 100 -> LinkQuality.GOOD
                latency < 200 -> LinkQuality.FAIR
                else -> LinkQuality.POOR
            }

        } catch (e: Exception) {
            logger.e(TAG, "Error evaluating WiFi LAN quality", e)
            return@withContext LinkQuality.POOR
        }
    }

    /**
     * 评估 WiFi 热点质量
     */
    private fun evaluateWifiHotspotQuality(): LinkQuality {
        // 热点连接通常质量中等
        return LinkQuality.GOOD
    }

    /**
     * 评估蓝牙质量
     */
    private fun evaluateBluetoothQuality(): LinkQuality {
        // 蓝牙连接速度较慢，质量一般
        return LinkQuality.FAIR
    }

    companion object {
        private const val TAG = "LinkSelector"
        private const val PING_TIMEOUT = 2000 // 2秒
    }
}

/**
 * 链路质量
 */
enum class LinkQuality {
    EXCELLENT,  // 优秀
    GOOD,       // 良好
    FAIR,       // 一般
    POOR        // 差
}
