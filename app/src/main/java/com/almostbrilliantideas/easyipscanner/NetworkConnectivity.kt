package com.almostbrilliantideas.easyipscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

sealed class NetworkState {
    data object Connected : NetworkState()
    data object NoNetwork : NetworkState()
    data object MobileDataOnly : NetworkState()
}

data class CurrentNetwork(
    val ssid: String?,
    val gatewayIp: String?
)

data class NetworkChangeResult(
    val hasChanged: Boolean,
    val currentNetwork: CurrentNetwork,
    val previousSsid: String?,
    val previousGateway: String?
)

object NetworkConnectivity {

    fun getNetworkState(context: Context): NetworkState {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkState.NoNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState.NoNetwork

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.Connected
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Connected
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.MobileDataOnly
            else -> NetworkState.NoNetwork
        }
    }

    fun hasValidLanConnection(context: Context): Boolean {
        val state = getNetworkState(context)
        if (state != NetworkState.Connected) return false

        // Additional check: verify we can get a valid local IP
        val cidr = detectBestCidr(context)
        return cidr != null
    }

    fun getWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid
            if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotBlank()) {
                ssid.removeSurrounding("\"")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getGatewayIp(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo ?: return null
            val gatewayInt = dhcpInfo.gateway
            if (gatewayInt == 0) return null

            // Convert little-endian int to IP string
            val ip = Integer.reverseBytes(gatewayInt)
            "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
        } catch (_: Exception) {
            null
        }
    }

    fun getCurrentNetwork(context: Context): CurrentNetwork {
        return CurrentNetwork(
            ssid = getWifiSsid(context),
            gatewayIp = getGatewayIp(context)
        )
    }

    fun checkNetworkChanged(
        context: Context,
        lastScannedNetwork: LastScannedNetwork?
    ): NetworkChangeResult {
        val current = getCurrentNetwork(context)

        // If no previous scan, no change to report
        if (lastScannedNetwork == null) {
            return NetworkChangeResult(
                hasChanged = false,
                currentNetwork = current,
                previousSsid = null,
                previousGateway = null
            )
        }

        // Check if network has changed
        // Primary check: Gateway IP (most reliable identifier)
        // Secondary check: SSID (in case gateway detection fails)
        val gatewayChanged = current.gatewayIp != null &&
                lastScannedNetwork.gatewayIp != null &&
                current.gatewayIp != lastScannedNetwork.gatewayIp

        val ssidChanged = current.ssid != null &&
                lastScannedNetwork.ssid != null &&
                current.ssid != lastScannedNetwork.ssid

        val hasChanged = gatewayChanged || ssidChanged

        return NetworkChangeResult(
            hasChanged = hasChanged,
            currentNetwork = current,
            previousSsid = lastScannedNetwork.ssid,
            previousGateway = lastScannedNetwork.gatewayIp
        )
    }
}
