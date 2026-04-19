package com.smslink.device.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi网络服务发现（NSD/mDNS）
 * 用于在同一WiFi网络下发现设备
 *
 * 参考：
 * - Android NSD官方文档
 * - KDE Connect的网络发现机制
 * - LocalSend的mDNS实现
 */
@Singleton
class WifiNetworkDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "WifiNetworkDiscovery"
        private const val SERVICE_TYPE = "_smslink._tcp."
        private const val SERVICE_NAME = "SMS-link"
        private const val SERVICE_PORT = 1716
    }

    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _discoveredDevices = MutableStateFlow<List<WifiDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WifiDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()

    /**
     * 检查是否在WiFi网络下
     */
    fun isWifiConnected(): Boolean {
        return try {
            val wifiInfo = wifiManager?.connectionInfo
            wifiInfo != null && wifiInfo.networkId != -1
        } catch (e: Exception) {
            logger.e(TAG, "Failed to check WiFi status", e)
            false
        }
    }

    /**
     * 获取当前WiFi网络的SSID
     */
    fun getCurrentWifiSsid(): String? {
        return try {
            val wifiInfo = wifiManager?.connectionInfo
            wifiInfo?.ssid?.removeSurrounding("\"")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get WiFi SSID", e)
            null
        }
    }

    /**
     * 注册本地服务（让其他设备发现）
     */
    fun registerService(localDevice: Device) {
        if (_isRegistered.value) {
            logger.d(TAG, "Service already registered")
            return
        }

        if (!isWifiConnected()) {
            logger.w(TAG, "Not connected to WiFi, cannot register service")
            return
        }

        if (nsdManager == null) {
            logger.e(TAG, "NsdManager not available")
            return
        }

        logger.i(TAG, "Registering NSD service: ${localDevice.name}")

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME}-${localDevice.name}"
            serviceType = SERVICE_TYPE
            port = SERVICE_PORT

            // 添加设备信息到TXT记录
            setAttribute("deviceId", localDevice.id)
            setAttribute("deviceName", localDevice.name)
            setAttribute("deviceType", localDevice.type.name)
            setAttribute("protocolVersion", "1")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logger.e(TAG, "Service registration failed: $errorCode")
                _isRegistered.value = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logger.e(TAG, "Service unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                logger.i(TAG, "Service registered: ${serviceInfo?.serviceName}")
                _isRegistered.value = true
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                logger.i(TAG, "Service unregistered: ${serviceInfo?.serviceName}")
                _isRegistered.value = false
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to register service", e)
        }
    }

    /**
     * 取消注册服务
     */
    fun unregisterService() {
        if (!_isRegistered.value) {
            return
        }

        logger.i(TAG, "Unregistering NSD service")

        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to unregister service", e)
        } finally {
            _isRegistered.value = false
            registrationListener = null
        }
    }

    /**
     * 开始发现服务
     */
    fun startDiscovery() {
        if (_isDiscovering.value) {
            logger.d(TAG, "Discovery already running")
            return
        }

        if (!isWifiConnected()) {
            logger.w(TAG, "Not connected to WiFi, cannot start discovery")
            return
        }

        if (nsdManager == null) {
            logger.e(TAG, "NsdManager not available")
            return
        }

        logger.i(TAG, "Starting NSD discovery")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                logger.i(TAG, "Discovery started: $serviceType")
                _isDiscovering.value = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                logger.i(TAG, "Discovery stopped: $serviceType")
                _isDiscovering.value = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return

                logger.d(TAG, "Service found: ${serviceInfo.serviceName}")

                // 只处理SMS-link服务
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return

                logger.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                removeDevice(serviceInfo.serviceName)
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                logger.e(TAG, "Discovery start failed: $errorCode")
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                logger.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start discovery", e)
            _isDiscovering.value = false
        }
    }

    /**
     * 停止发现服务
     */
    fun stopDiscovery() {
        if (!_isDiscovering.value) {
            return
        }

        logger.i(TAG, "Stopping NSD discovery")

        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to stop discovery", e)
        } finally {
            _isDiscovering.value = false
            discoveryListener = null
            _discoveredDevices.value = emptyList()
        }
    }

    /**
     * 解析服务详情
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val serviceName = serviceInfo.serviceName

        // 避免重复解析
        if (resolveListeners.containsKey(serviceName)) {
            logger.d(TAG, "Already resolving: $serviceName")
            return
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                logger.w(TAG, "Resolve failed for ${serviceInfo?.serviceName}: $errorCode")
                resolveListeners.remove(serviceName)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) {
                    resolveListeners.remove(serviceName)
                    return
                }

                logger.i(TAG, "Service resolved: ${serviceInfo.serviceName}")

                try {
                    // 从TXT记录中提取设备信息
                    val deviceId = serviceInfo.attributes["deviceId"]?.let { String(it, Charsets.UTF_8) }
                    val deviceName = serviceInfo.attributes["deviceName"]?.let { String(it, Charsets.UTF_8) }
                    val deviceTypeStr = serviceInfo.attributes["deviceType"]?.let { String(it, Charsets.UTF_8) }

                    if (deviceId != null && deviceName != null) {
                        val device = Device(
                            id = deviceId,
                            name = deviceName,
                            type = deviceTypeStr?.let { DeviceType.valueOf(it) } ?: DeviceType.PHONE,
                            role = DeviceRole.SECONDARY,
                            publicKey = null,
                            lastSeen = System.currentTimeMillis(),
                            isPaired = false
                        )

                        val discoveredDevice = WifiDiscoveredDevice(
                            device = device,
                            serviceName = serviceInfo.serviceName,
                            host = serviceInfo.host,
                            port = serviceInfo.port,
                            lastSeen = System.currentTimeMillis()
                        )

                        addOrUpdateDevice(discoveredDevice)
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to parse service info", e)
                }

                resolveListeners.remove(serviceName)
            }
        }

        resolveListeners[serviceName] = resolveListener

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to resolve service", e)
            resolveListeners.remove(serviceName)
        }
    }

    /**
     * 添加或更新设备
     */
    private fun addOrUpdateDevice(discoveredDevice: WifiDiscoveredDevice) {
        val currentDevices = _discoveredDevices.value.toMutableList()
        val existingIndex = currentDevices.indexOfFirst {
            it.device.id == discoveredDevice.device.id
        }

        if (existingIndex >= 0) {
            currentDevices[existingIndex] = discoveredDevice
        } else {
            currentDevices.add(discoveredDevice)
            logger.i(TAG, "New device discovered: ${discoveredDevice.device.name} at ${discoveredDevice.host}")
        }

        _discoveredDevices.value = currentDevices
    }

    /**
     * 移除设备
     */
    private fun removeDevice(serviceName: String) {
        val currentDevices = _discoveredDevices.value.toMutableList()
        val removed = currentDevices.removeAll { it.serviceName == serviceName }

        if (removed) {
            logger.d(TAG, "Device removed: $serviceName")
            _discoveredDevices.value = currentDevices
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopDiscovery()
        unregisterService()
        resolveListeners.clear()
        scope.cancel()
    }
}

/**
 * WiFi发现的设备
 */
data class WifiDiscoveredDevice(
    val device: Device,
    val serviceName: String,
    val host: InetAddress,
    val port: Int,
    val lastSeen: Long
)
