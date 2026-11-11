package com.gathin.tvhome.universal.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Общее состояние: последняя кнопка пульта, заставка и видим ли лаунчер сейчас. */
object LauncherState {
    var lastInput by mutableLongStateOf(System.currentTimeMillis())
    var screensaver by mutableStateOf(false)
    /** False until an activity attaches; the UI unloads while another app is in front. */
    var visible by mutableStateOf(false)
        private set

    private var owner: Any? = null

    /**
     * The activity that currently owns the screen. A stale instance (for example after an APK update
     * replaced the running activity) must not be able to hide the new one in its late onStop().
     */
    fun attach(token: Any) {
        owner = token
        visible = true
    }

    fun detach(token: Any) {
        if (owner === token) {
            owner = null
            visible = false
        }
    }
}
