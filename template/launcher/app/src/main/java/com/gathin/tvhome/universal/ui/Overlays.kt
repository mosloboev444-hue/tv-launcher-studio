package com.gathin.tvhome.universal.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gathin.tvhome.universal.NotifItem
import com.gathin.tvhome.universal.NotificationService
import com.gathin.tvhome.R
import com.gathin.tvhome.ScreenBridge
import com.gathin.tvhome.universal.data.DeepSeek
import com.gathin.tvhome.universal.data.ACTION_NOTIFICATION_LISTENER_SETTINGS
import com.gathin.tvhome.universal.data.ACTION_VPN_SETTINGS
import com.gathin.tvhome.universal.data.AppItem
import com.gathin.tvhome.universal.data.LauncherSettings
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import com.gathin.tvhome.universal.data.Weather
import com.gathin.tvhome.universal.data.launchApp
import com.gathin.tvhome.universal.data.toast
import com.gathin.tvhome.universal.data.tryStart
import kotlinx.coroutines.delay

@Composable
private fun rememberInitialFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A dialog opened from a held OK cannot take focus while the key is still down (the launcher's own
        // long-press on the VPN pill is exactly that case), so retry until the key is released.
        repeat(14) {
            delay(140)
            try {
                fr.requestFocus()
                return@LaunchedEffect
            } catch (_: Exception) {
            }
        }
    }
    return fr
}

@Composable
private fun DialogPanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(520.dp).background(Color(0xF01B2430), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

@Composable
private fun MenuButton(
    text: String,
    value: String? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { SoundFx.play(Sound.SELECT); onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x33FFFFFF),
            focusedContainerColor = Color(0xFF3D7BD9),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.hasFocus) SoundFx.play(Sound.FOCUS) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, fontSize = 17.sp)
            if (value != null) Text(value, fontSize = 17.sp, color = Color(0xFFBBD6FF))
        }
    }
}

/** Меню приложения (долгое нажатие OK): открыть, избранное, порядок, сведения. */
@Composable
fun AppMenuDialog(
    packageName: String,
    apps: List<AppItem>,
    favorites: List<String>,           // только установленные, по порядку
    onFavorites: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = apps.find { it.packageName == packageName }
    val idx = favorites.indexOf(packageName)
    val focus = rememberInitialFocus()

    fun move(from: Int, to: Int) {
        val l = favorites.toMutableList()
        val x = l.removeAt(from)
        l.add(to, x)
        onFavorites(l)
    }

    DialogPanel(app?.label ?: packageName, onDismiss) {
        MenuButton(stringResource(R.string.uni_menu_open), focusRequester = focus) { launchApp(context, packageName); onDismiss() }
        if (idx < 0) {
            MenuButton(stringResource(R.string.uni_menu_add_fav)) {
                SoundFx.play(Sound.FAVORITE); onFavorites(favorites + packageName); onDismiss()
            }
        } else {
            MenuButton(stringResource(R.string.uni_menu_remove_fav)) { onFavorites(favorites - packageName); onDismiss() }
            if (idx > 0) MenuButton(stringResource(R.string.uni_menu_move_left)) { move(idx, idx - 1) }
            if (idx < favorites.lastIndex) MenuButton(stringResource(R.string.uni_menu_move_right)) { move(idx, idx + 1) }
        }
        MenuButton(stringResource(R.string.uni_menu_app_info)) {
            tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            onDismiss()
        }
    }
}

private val saverMinutes = listOf(0, 2, 5, 10)

/** Настройки самого лаунчера: обои, звуки, заставка. Каждое нажатие переключает на следующий вариант. */
@Composable
fun LauncherSettingsDialog(
    settings: LauncherSettings,
    onChange: (LauncherSettings) -> Unit,
    onAssistantKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    val wallpaperNames = listOf(R.string.uni_wp_lake, R.string.uni_wp_sunset, R.string.uni_wp_night, R.string.uni_wp_auto).map { stringResource(it) }
    val soundThemeNames = listOf(R.string.uni_sound_bells, R.string.uni_sound_soft, R.string.uni_sound_crystal).map { stringResource(it) }
    val volumeNames = listOf(R.string.uni_vol_off, R.string.uni_vol_low, R.string.uni_vol_medium, R.string.uni_vol_high).map { stringResource(it) }
    val saverIdx = saverMinutes.indexOf(settings.screensaverMinutes).coerceAtLeast(0)
    val saverValue = if (saverIdx == 0) stringResource(R.string.uni_off) else stringResource(R.string.uni_minutes_fmt, saverMinutes[saverIdx])

    DialogPanel(stringResource(R.string.uni_settings_title), onDismiss) {
        MenuButton(stringResource(R.string.uni_setting_wallpaper), wallpaperNames[settings.wallpaper.coerceIn(0, 3)], focus) {
            onChange(settings.copy(wallpaper = (settings.wallpaper + 1) % wallpaperNames.size))
        }
        MenuButton(stringResource(R.string.uni_setting_sound_theme), soundThemeNames[settings.soundTheme.coerceIn(0, 2)]) {
            onChange(settings.copy(soundTheme = (settings.soundTheme + 1) % soundThemeNames.size))
        }
        MenuButton(stringResource(R.string.uni_setting_volume), volumeNames[settings.volume.coerceIn(0, 3)]) {
            onChange(settings.copy(volume = (settings.volume + 1) % volumeNames.size))
        }
        MenuButton(stringResource(R.string.uni_setting_screensaver), saverValue) {
            onChange(settings.copy(screensaverMinutes = saverMinutes[(saverIdx + 1) % saverMinutes.size]))
        }
        val keySet = DeepSeek.loadKey(LocalContext.current) != null
        MenuButton("DeepSeek: ключ", value = if (keySet) "задан" else "нет") { onAssistantKey() }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }
    }
}

@Composable
fun NotificationsDialog(items: List<NotifItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val component = remember { ComponentName(context, NotificationService::class.java).flattenToString() }
    val focus = rememberInitialFocus()
    DialogPanel(stringResource(R.string.uni_notif_title), onDismiss) {
        if (!enabled) {
            Text(stringResource(R.string.uni_notif_access_help, component), color = Color(0xCCFFFFFF), fontSize = 14.sp)
            MenuButton(stringResource(R.string.uni_notif_open_access), focusRequester = focus) {
                if (!tryStart(context, Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                    toast(context, context.getString(R.string.uni_notif_no_screen))
                }
            }
        } else if (items.isEmpty()) {
            Text(stringResource(R.string.uni_notif_empty), color = Color(0xCCFFFFFF), fontSize = 16.sp)
        } else {
            items.take(8).forEach {
                Column {
                    Text(it.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (it.text.isNotBlank()) Text(it.text, color = Color(0xCCFFFFFF), fontSize = 14.sp, maxLines = 2)
                }
            }
        }
        MenuButton(stringResource(R.string.uni_close), focusRequester = if (enabled) focus else null) { onDismiss() }
    }
}

/** Выбор домашнего экрана: «Кино», «Классика» или «Универсальный» (этот). */
@Composable
fun ScreenPickerDialog(current: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focus = rememberInitialFocus()
    val tick = "✓"
    DialogPanel(stringResource(R.string.uni_screen_title), onDismiss) {
        MenuButton(
            text = stringResource(R.string.uni_screen_cinema),
            value = if (current == "cinema") tick else null,
            focusRequester = focus,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "cinema") else onDismiss() }
        MenuButton(
            text = stringResource(R.string.uni_screen_classic),
            value = if (current == "classic") tick else null,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "classic") else onDismiss() }
        MenuButton(
            text = stringResource(R.string.uni_screen_universal),
            value = if (current == "universal") tick else null,
        ) {
            if (current == "universal") onDismiss()
            else if (activity != null) ScreenBridge.switchTo(activity, "universal") else onDismiss()
        }
        MenuButton(
            text = stringResource(R.string.uni_screen_polly),
            value = if (current == "polly") tick else null,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "polly") else onDismiss() }
        MenuButton(
            text = stringResource(R.string.uni_screen_wintv),
            value = if (current == "wintv") tick else null,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "wintv") else onDismiss() }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }
    }
}

/** Заставка: обои, большие часы, дата и погода. Закрывается любой кнопкой пульта. */
@Composable
fun Screensaver(wallpaperIndex: Int, weather: Weather?) {
    val context = LocalContext.current
    val now by rememberNow()
    Box(Modifier.fillMaxSize()) {
        Wallpaper(wallpaperIndex)
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatTime(context, now), color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light)
            Text(formatDate(now, "EEEEdMMMM"), color = Color(0xE6FFFFFF), fontSize = 30.sp)
            if (weather != null) {
                Text("${weather.emoji} ${weather.text}", color = Color(0xE6FFFFFF), fontSize = 30.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The VPN menu: either the launcher's own AmneziaWG tunnel (profiles, connect/stop) or, when the user picked
 * another app as the VPN source, a shortcut to open that app. A single source setting keeps the pill's short
 * press unambiguous: it always does what "Источник VPN" says.
 */
@Composable
fun VpnMenuDialog(
    connected: Boolean,
    working: Boolean,
    builtin: Boolean,
    sourceLabel: String,
    profileName: String?,
    profiles: List<java.io.File>,
    selected: java.io.File?,
    onToggleBuiltin: () -> Unit,
    onOpenExternal: () -> Unit,
    onSelectProfile: (java.io.File) -> Unit,
    onRescan: () -> Unit,
    onSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    val context = LocalContext.current
    DialogPanel("VPN", onDismiss) {
        Text(
            when {
                working -> "Подключаюсь…"
                builtin && connected -> "Мой VPN (AmneziaWG) · включён"
                builtin -> "Мой VPN (AmneziaWG)" + (profileName?.let { " · профиль $it" } ?: "")
                else -> "Источник: $sourceLabel"
            },
            color = Color(0xCCFFFFFF),
            fontSize = 14.sp,
        )
        if (builtin) {
            MenuButton(
                text = if (connected) "Отключить VPN" else "Подключить VPN",
                value = if (connected) "включён" else null,
                focusRequester = focus,
            ) { onToggleBuiltin() }
            profiles.take(6).forEach { file ->
                MenuButton(
                    text = file.name,
                    value = if (file == selected) "✓" else null,
                ) { onSelectProfile(file) }
            }
            MenuButton("Обновить профили") { onRescan() }
        } else {
            MenuButton("Открыть $sourceLabel", focusRequester = focus) { onOpenExternal() }
        }
        MenuButton("Источник VPN: $sourceLabel") { onSource() }
        MenuButton("Системные настройки VPN") {
            tryStart(context, Intent(ACTION_VPN_SETTINGS))
        }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }
    }
}

/** Picks what the VPN pill uses: the built-in AmneziaWG tunnel or an installed VPN app. */
@Composable
fun VpnSourceDialog(
    current: String,
    builtinName: String,
    vpnApps: List<AppItem>,
    onPick: (String) -> Unit,
    onSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = rememberInitialFocus()
    DialogPanel("Источник VPN", onDismiss) {
        Text(
            "Кнопка VPN будет включать выбранный источник.",
            color = Color(0xCCFFFFFF),
            fontSize = 14.sp,
        )
        MenuButton(
            text = builtinName,
            value = if (current == "builtin") "✓" else null,
            focusRequester = focus,
        ) { onPick("builtin") }
        vpnApps.forEach { app ->
            MenuButton(
                text = app.label,
                value = if (current == app.packageName) "✓" else null,
            ) { onPick(app.packageName) }
        }
        MenuButton("Системные настройки VPN") { onSystemSettings() }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }
    }
}
