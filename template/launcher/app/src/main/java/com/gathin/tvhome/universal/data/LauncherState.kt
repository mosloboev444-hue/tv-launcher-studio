package com.gathin.tvhome.universal.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Общее состояние: последняя кнопка пульта, заставка и видим ли лаунчер сейчас. */
object LauncherState {
    var lastInput by mutableLongStateOf(System.currentTimeMillis())
    var screensaver by mutableStateOf(false)
    var visible by mutableStateOf(true)
}
