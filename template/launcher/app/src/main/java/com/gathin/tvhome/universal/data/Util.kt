package com.gathin.tvhome.universal.data

import android.content.Context
import android.content.Intent
import android.widget.Toast

// Строками, чтобы не зависеть от версии Android (на старых системах пункт может быть недоступен)
const val ACTION_VPN_SETTINGS = "android.settings.VPN_SETTINGS"
const val ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

/** Пробует запустить интенты по очереди, пока один не сработает. */
fun tryStart(context: Context, vararg intents: Intent): Boolean {
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) {
        }
    }
    return false
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
