package com.gathin.tvhome.universal.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gathin.tvhome.ScreenBridge
import kotlinx.coroutines.delay

/**
 * Погода для универсального экрана: готовая строка из основного лаунчера («+4° Облачно») и иконка по типу
 * погоды. Сеть, кэш на 30 минут и резервные источники — там же, в CleanYourTV (ScreenBridge), поэтому
 * оба экрана показывают одно и то же и не ходят в сеть дважды.
 */
data class Weather(val text: String, val emoji: String)

private fun emojiFor(kind: Int): String = when (kind) {
    0 -> "☀️"   // ясно
    1 -> "🌤"   // малооблачно
    2 -> "☁️"   // облачно
    3 -> "🌫"   // туман
    4 -> "🌧"   // дождь
    5 -> "❄️"   // снег
    6 -> "⛈"   // гроза
    else -> "🌡"
}

@Composable
fun rememberWeather(): State<Weather?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Weather?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            ScreenBridge.refreshWeather(context)
            val info = ScreenBridge.cachedWeather(context)
            if (info != null) state.value = Weather(info.text, emojiFor(info.kind))
            delay(60_000L)
        }
    }
    return state
}
