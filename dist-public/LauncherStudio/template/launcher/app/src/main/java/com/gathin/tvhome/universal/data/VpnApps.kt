package com.gathin.tvhome.universal.data

import android.content.Context
import android.content.Intent

private const val PREFS = "launcher"
private const val KEY = "default_vpn"

/** Пакеты приложений, которые умеют поднимать VPN (объявляют VpnService). */
fun findVpnPackages(context: Context): Set<String> =
    context.packageManager.queryIntentServices(Intent("android.net.VpnService"), 0)
        .map { it.serviceInfo.packageName }
        .toSet()

fun loadDefaultVpn(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

fun saveDefaultVpn(context: Context, pkg: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pkg).apply()
}
