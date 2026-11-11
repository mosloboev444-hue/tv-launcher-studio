package com.gathin.tvhome.universal.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class NetKind { NONE, WIFI, ETHERNET, OTHER }

/** vpn — в системе поднято VPN-соединение; kind — тип рабочего подключения к интернету. */
data class NetStatus(val vpn: Boolean, val kind: NetKind)

@Suppress("DEPRECATION")
private fun compute(cm: ConnectivityManager): NetStatus {
    var vpn = false
    var kind = NetKind.NONE
    for (n in cm.allNetworks) {
        val c = cm.getNetworkCapabilities(n) ?: continue
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = true; continue }
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (Build.VERSION.SDK_INT < 23 || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        ) {
            val k = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
                else -> NetKind.OTHER
            }
            if (kind == NetKind.NONE || k == NetKind.ETHERNET) kind = k
        }
    }
    return NetStatus(vpn, kind)
}

@Composable
fun rememberNetStatus(): State<NetStatus> {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val state = remember { mutableStateOf(compute(cm)) }
    DisposableEffect(Unit) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = compute(cm) }
            override fun onLost(network: Network) { state.value = compute(cm) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = compute(cm)
            }
        }
        // Запрос по умолчанию исключает VPN-сети, поэтому снимаем это ограничение
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return state
}
