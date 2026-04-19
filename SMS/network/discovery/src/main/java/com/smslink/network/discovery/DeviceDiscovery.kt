package com.smslink.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.smslink.core.model.DeviceCapability
import com.smslink.core.model.DeviceInfo
import com.smslink.core.model.DeviceType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

class DeviceDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredDevices = ConcurrentHashMap<String, DeviceInfo>()
    private val serviceNameToDeviceId = ConcurrentHashMap<String, String>()

    private val _devicesFlow = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devicesFlow: StateFlow<List<DeviceInfo>> = _devicesFlow.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun register(deviceInfo: DeviceInfo) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceInfo.deviceName
            serviceType = SERVICE_TYPE
            port = deviceInfo.port

            setAttribute("deviceId", deviceInfo.deviceId)
            setAttribute("deviceType", deviceInfo.deviceType.name)
            setAttribute("capabilities", deviceInfo.capabilities.joinToString(",") { it.name })
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // Handle registration failure
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // Handle unregistration failure
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                // Service registered successfully
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                // Service unregistered
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun startDiscovery(): Flow<List<DeviceInfo>> {
        if (discoveryListener != null) return devicesFlow

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let(::resolveService)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                val deviceId = serviceNameToDeviceId.remove(serviceInfo.serviceName)
                    ?: discoveredDevices.values.firstOrNull { it.deviceName == serviceInfo.serviceName }?.deviceId
                    ?: return

                discoveredDevices.remove(deviceId)
                publishDevices()
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discoveredDevices.clear()
                serviceNameToDeviceId.clear()
                publishDevices()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        return devicesFlow
    }

    private fun publishDevices() {
        _devicesFlow.value = discoveredDevices.values.sortedBy { it.deviceId }
    }

    private fun parseDeviceInfo(serviceInfo: NsdServiceInfo): DeviceInfo? {
        val deviceId = serviceInfo.attributes["deviceId"]?.decodeToString()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val deviceType = serviceInfo.attributes["deviceType"]
            ?.decodeToString()
            ?.let { raw -> runCatching { DeviceType.valueOf(raw) }.getOrDefault(DeviceType.PHONE) }
            ?: DeviceType.PHONE

        val capabilities = serviceInfo.attributes["capabilities"]
            ?.decodeToString()
            ?.split(',')
            ?.mapNotNull { raw -> runCatching { DeviceCapability.valueOf(raw) }.getOrNull() }
            ?.toSet()
            ?: emptySet()

        return DeviceInfo(
            deviceId = deviceId,
            deviceName = serviceInfo.serviceName,
            deviceType = deviceType,
            capabilities = capabilities,
            ipAddress = serviceInfo.host?.hostAddress,
            port = serviceInfo.port
        )
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                val deviceInfo = parseDeviceInfo(serviceInfo) ?: return

                serviceNameToDeviceId[serviceInfo.serviceName] = deviceInfo.deviceId
                discoveredDevices[deviceInfo.deviceId] = deviceInfo
                publishDevices()
            }
        }

        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        discoveryListener = null
    }

    fun unregister() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        registrationListener = null
    }

    companion object {
        private const val SERVICE_TYPE = "_smslink._tcp."
    }
}
