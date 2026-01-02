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
}
