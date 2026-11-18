package com.superdownloader.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NetState(val connected: Boolean, val unmetered: Boolean, val networkId: String)

class NetworkMonitor(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<NetState> = _state

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _state.value = read() }
                override fun onLost(network: Network) { _state.value = read() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _state.value = read() }
            })
        }
    }

    private fun read(): NetState {
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        return NetState(connected, unmetered, n?.toString() ?: "none")
    }
}
