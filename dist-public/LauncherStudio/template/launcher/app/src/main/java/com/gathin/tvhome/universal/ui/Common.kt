package com.gathin.tvhome.universal.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun minuteNow() = System.currentTimeMillis() / 60_000L * 60_000L

/** Текущая минута (мс). Состояние меняется раз в минуту, поэтому интерфейс не перерисовывается зря. */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableStateOf(minuteNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
            now.value = minuteNow()
        }
    }
    return now
}

/** Время в формате системы (12 или 24 часа — как настроено на телевизоре). */
fun formatTime(context: Context, millis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))

/** Дата на языке системы; skeleton, например "EEEdMMM" или "EEEEdMMMM". */
fun formatDate(millis: Long, skeleton: String): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PillButton(
    icon: ImageVector,
    color: Color = Color(0x66000000),
    badge: Int = 0,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box {
        Surface(
            onClick = { SoundFx.play(Sound.SELECT); onClick() },
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = color,
                focusedContainerColor = color,
                contentColor = Color.White,
                focusedContentColor = Color.White,
            ),
            // The launcher look: the focused pill is the same colour, but gets a clear white ring.
            border = ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedBorder = Border(
                    border = BorderStroke(3.dp, Color.White),
                    inset = 0.dp,
                    shape = RoundedCornerShape(50),
                ),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.16f),
            modifier = Modifier
                .size(44.dp)
                .onFocusChanged { if (it.hasFocus) SoundFx.play(Sound.FOCUS) },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).background(Color(0xFFE53935), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge.coerceAtMost(99).toString(), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun InfoPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(Color(0x66000000), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}


/**
 * OK on the remote for cards and pills that have a second action.
 *
 * The long action runs when the key is *released*, not while it is held: on a real remote a held OK keeps
 * repeating, and once the menu was already open those repeats (and the release) landed in the new dialog and
 * clicked its first row — the menu seemed to close by itself. Handling the key here, before the Surface's own
 * click handling, keeps every key event of the press away from both the pill and the dialog.
 */
@Composable
fun Modifier.rememberTvOkGestures(onTap: () -> Unit, onLongPress: () -> Unit): Modifier {
    val downAt = remember { mutableStateOf(0L) }
    val sawLongSignal = remember { mutableStateOf(false) }
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    return this.onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter) {
            false
        } else {
            val native = event.nativeKeyEvent
            val longFlag = native != null &&
                (native.flags and android.view.KeyEvent.FLAG_LONG_PRESS) != 0
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (native == null || native.repeatCount == 0) {
                        downAt.value = SystemClock.uptimeMillis()
                        sawLongSignal.value = longFlag
                    } else {
                        // Held keys repeat; some remotes only signal a long press this way.
                        sawLongSignal.value = true
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val held = SystemClock.uptimeMillis() - downAt.value
                    if (held >= 400L || sawLongSignal.value || longFlag) longPress.value() else tap.value()
                    true
                }
                else -> false
            }
        }
    }
}
