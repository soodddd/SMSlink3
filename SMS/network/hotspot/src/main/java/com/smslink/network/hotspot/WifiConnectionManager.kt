package com.smslink.network.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class WifiConnectionManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    fun connectToNetwork(ssid: String, password: String): Flow<ConnectionState> = callbackFlow {
        disconnect()
        trySend(ConnectionState.Connecting)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                connectivityManager.bindProcessToNetwork(network)
                trySend(ConnectionState.Connected(ssid))
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    activeNetwork = null
                }
                trySend(ConnectionState.Disconnected)
            }

            override fun onUnavailable() {
                trySend(ConnectionState.Failed(IllegalStateException("Network unavailable")))
            }
        }

        activeCallback = callback
        connectivityManager.requestNetwork(request, callback)

        awaitClose { disconnect() }
    }

    fun disconnect() {
        activeCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
            activeCallback = null
        }
        connectivityManager.bindProcessToNetwork(null)
        activeNetwork = null
    }

    fun getCurrentNetwork(): NetworkInfo? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = wifiManager.connectionInfo
            return NetworkInfo(
                ssid = wifiInfo.ssid.removeSurrounding("\""),
                isConnected = true
            )
        }

        return null
    }

    sealed class ConnectionState {
        object Connecting : ConnectionState()
        data class Connected(val ssid: String) : ConnectionState()
        object Disconnected : ConnectionState()
        data class Failed(val exception: Exception) : ConnectionState()
    }

    data class NetworkInfo(
        val ssid: String,
        val isConnected: Boolean
    )
}
