package com.gathin.tvhome.universal.ui

import android.app.Activity
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.gathin.tvhome.universal.NotificationState
import com.gathin.tvhome.R
import com.gathin.tvhome.ScreenBridge
import com.gathin.tvhome.universal.data.AppsCache
import com.gathin.tvhome.universal.data.LauncherState
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import com.gathin.tvhome.universal.data.WALLPAPER_AUTO
import com.gathin.tvhome.universal.data.findVpnPackages
import com.gathin.tvhome.universal.data.isVideoMix
import com.gathin.tvhome.universal.data.launchApp
import com.gathin.tvhome.universal.data.loadApps
import com.gathin.tvhome.universal.data.loadFavorites
import com.gathin.tvhome.universal.data.loadSettings
import com.gathin.tvhome.universal.data.rememberWeather
import com.gathin.tvhome.universal.data.saveFavorites
import com.gathin.tvhome.universal.data.saveSettings
import com.gathin.tvhome.universal.data.toast
import com.gathin.tvhome.universal.data.tryStart
import com.gathin.tvhome.vpn.VpnController
import com.gathin.tvhome.vpn.VpnProfiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LauncherScreen() {
    // Лаунчер не на экране (запущено другое приложение): интерфейс выгружен, память и процессор свободны
    if (!LauncherState.visible) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    // Интерфейс рассчитан на экран шириной 960 dp. На HD, Full HD и 4K телевизорах плотность разная,
    // поэтому масштабируем её так, чтобы вид был одинаковым на любом экране.
    val base = LocalDensity.current
    val scale = (LocalConfiguration.current.screenWidthDp / 960f).coerceIn(0.5f, 4f)
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
        LauncherContent()
    }
}

@Composable
private fun LauncherContent() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(AppsCache.apps) }
    val savedFavs = remember { loadFavorites(context) }
    var favs by remember { mutableStateOf(savedFavs ?: emptyList()) }
    var settings by remember { mutableStateOf(loadSettings(context)) }
    var menuPkg by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNotifs by remember { mutableStateOf(false) }
    var showVpnMenu by remember { mutableStateOf(false) }
    var showVpnSource by remember { mutableStateOf(false) }
    var showScreens by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    // The launcher's own AmneziaWG tunnel.
    var vpnConnected by remember { mutableStateOf(VpnController.isConnected()) }
    var vpnWorking by remember { mutableStateOf(false) }
    var vpnProfiles by remember { mutableStateOf(VpnProfiles.list(context)) }
    var vpnSelected by remember { mutableStateOf(VpnProfiles.selected(context)) }
    // What the VPN pill uses: the built-in AmneziaWG tunnel ("builtin") or an installed VPN app's package.
    var vpnSource by remember { mutableStateOf(VpnProfiles.source(context)) }
    val vpnPackages = remember { findVpnPackages(context) }
    val vpnSourceLabel = if (vpnSource == VpnProfiles.SOURCE_BUILTIN) "Мой VPN (AmneziaWG)"
        else apps.find { it.packageName == vpnSource }?.label ?: vpnSource
    // Short name inside the VPN capsule: the tunnel's own name or the selected app.
    val vpnPillLabel = if (vpnSource == VpnProfiles.SOURCE_BUILTIN) "Мой VPN"
        else (apps.find { it.packageName == vpnSource }?.label ?: vpnSource).let {
            if (it.length > 12) it.take(12) + "…" else it
        }
    var autoIdx by remember { mutableIntStateOf(0) }

    val weather by rememberWeather()
    val notifs by NotificationState.items.collectAsState()
    val dialogOpen = rememberUpdatedState(menuPkg != null || showSettings || showNotifs || showVpnMenu || showVpnSource || showScreens || showAssistant || showKey)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        VpnController.setListener { up -> mainHandler.post { vpnConnected = up } }
        onDispose { VpnController.setListener(null) }
    }

    fun connectBuiltInVpn() {
        if (vpnWorking) return
        val config = VpnProfiles.load(context)
        if (config == null) {
            toast(context, "Положите .conf в папку vpn приложения")
            return
        }
        vpnWorking = true
        VpnController.connect(context, config) { ok, error ->
            mainHandler.post {
                vpnWorking = false
                toast(context, if (ok) "VPN включён" else "VPN: $error")
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectBuiltInVpn()
        else toast(context, "Без разрешения VPN не включится")
    }

    fun toggleBuiltInVpn() {
        if (vpnWorking) return
        if (vpnConnected) {
            vpnWorking = true
            VpnController.disconnect { ok, error ->
                mainHandler.post {
                    vpnWorking = false
                    toast(context, if (ok) "VPN выключен" else "VPN: $error")
                }
            }
        } else {
            val prepare = VpnService.prepare(context)
            if (prepare != null) vpnPermission.launch(prepare) else connectBuiltInVpn()
        }
    }

    /** The pill does exactly what the chosen VPN source says: built-in tunnel or the selected app. */
    fun toggleVpn() {
        if (vpnSource == VpnProfiles.SOURCE_BUILTIN) {
            toggleBuiltInVpn()
            return
        }
        val pkg = vpnSource
        if (apps.any { it.packageName == pkg }) {
            launchApp(context, pkg)
        } else {
            toast(context, "VPN-приложение не найдено — выберите источник")
            showVpnMenu = true
        }
    }

    // «Назад» не закрывает лаунчер (диалоги закрываются сами)
    BackHandler(true) {}

    LaunchedEffect(Unit) {
        SoundFx.configure(settings.soundTheme, settings.volume)
        val loaded = withContext(Dispatchers.IO) { loadApps(context) }
        apps = loaded
        // Первый запуск: «Видео MIX» сразу в избранном, если установлено
        if (savedFavs == null) {
            val preset = ScreenBridge.defaultFavorites(context).toList()
            favs = if (preset.isNotEmpty()) preset else loaded.filter { isVideoMix(it) }.map { it.packageName }
        }
        SoundFx.playStartup()
    }

    // автосмена обоев
    LaunchedEffect(settings.wallpaper) {
        if (settings.wallpaper == WALLPAPER_AUTO) {
            while (true) { delay(10 * 60_000L); autoIdx = (autoIdx + 1) % WALLPAPERS.size }
        }
    }
    val wallpaperIndex = if (settings.wallpaper == WALLPAPER_AUTO) autoIdx else settings.wallpaper

    // заставка после периода бездействия
    LaunchedEffect(settings.screensaverMinutes) {
        while (true) {
            delay(5_000)
            val m = settings.screensaverMinutes
            if (m > 0 && !LauncherState.screensaver && !dialogOpen.value &&
                System.currentTimeMillis() - LauncherState.lastInput > m * 60_000L
            ) LauncherState.screensaver = true
        }
    }

    val installedFavs = remember(apps, favs) { favs.filter { p -> apps.any { it.packageName == p } } }
    val favItems = remember(apps, installedFavs) { installedFavs.mapNotNull { p -> apps.find { it.packageName == p } } }
    fun updateFavs(list: List<String>) { favs = list; saveFavorites(context, list) }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            Wallpaper(wallpaperIndex)
            // затемнение снизу для читаемости; без alpha-слоя, чтобы не рисовать лишний буфер
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000))))
            )
            TopBar(
                weather = weather,
                notifCount = notifs.size,
                onNotifications = { showNotifs = true },
                onLauncherSettings = { showSettings = true },
                onScreens = { showScreens = true },
                onAssistant = { showAssistant = true },
                vpnOn = vpnConnected,
                vpnBuiltin = vpnSource == VpnProfiles.SOURCE_BUILTIN,
                vpnLabel = vpnPillLabel,
                onVpn = { toggleVpn() },
                onVpnLong = {
                    vpnProfiles = VpnProfiles.list(context)
                    vpnSelected = VpnProfiles.selected(context)
                    showVpnMenu = true
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 32.dp, vertical = 28.dp),
            )
            Dock(
                apps = apps,
                favorites = favItems,
                onMenu = { menuPkg = it },
                onOpen = { pkg -> if (menuPkg == null) launchApp(context, pkg) },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 24.dp, bottom = 32.dp),
            )
            if (LauncherState.screensaver) Screensaver(wallpaperIndex, weather)
        }

        menuPkg?.let { pkg ->
            AppMenuDialog(pkg, apps, installedFavs, onFavorites = { updateFavs(it) }, onDismiss = { menuPkg = null })
        }
        if (showSettings) {
            LauncherSettingsDialog(
                settings = settings,
                onChange = {
                    settings = it
                    saveSettings(context, it)
                    SoundFx.configure(it.soundTheme, it.volume)
                    SoundFx.play(Sound.SELECT)
                },
                onAssistantKey = { showSettings = false; showKey = true },
                onDismiss = { showSettings = false },
            )
        }
        if (showNotifs) NotificationsDialog(notifs) { showNotifs = false }
        if (showVpnMenu) VpnMenuDialog(
            connected = vpnConnected,
            working = vpnWorking,
            builtin = vpnSource == VpnProfiles.SOURCE_BUILTIN,
            sourceLabel = vpnSourceLabel,
            profileName = vpnSelected?.name,
            profiles = vpnProfiles,
            selected = vpnSelected,
            onToggleBuiltin = { showVpnMenu = false; toggleBuiltInVpn() },
            onOpenExternal = { showVpnMenu = false; toggleVpn() },
            onSelectProfile = { file ->
                VpnProfiles.select(context, file)
                vpnSelected = file
                vpnProfiles = VpnProfiles.list(context)
            },
            onRescan = {
                vpnProfiles = VpnProfiles.list(context)
                vpnSelected = VpnProfiles.selected(context)
                toast(context, if (vpnProfiles.isEmpty()) "Профили не найдены" else "Профили обновлены")
            },
            onSource = { showVpnMenu = false; showVpnSource = true },
            onDismiss = { showVpnMenu = false },
        )
        if (showVpnSource) VpnSourceDialog(
            current = vpnSource,
            builtinName = "Мой VPN (AmneziaWG)",
            vpnApps = apps.filter { it.packageName in vpnPackages }.sortedBy { it.label },
            onPick = { picked ->
                VpnProfiles.setSource(context, picked)
                vpnSource = picked
                showVpnSource = false
                // Switching away from the built-in tunnel must stop it: two VPNs cannot run at once, and the
                // picker would otherwise leave our tunnel up while opening another app.
                if (picked != VpnProfiles.SOURCE_BUILTIN && vpnConnected) {
                    VpnController.disconnect { _, _ -> mainHandler.post { vpnConnected = false } }
                }
                toast(context, "Источник VPN: " + (if (picked == VpnProfiles.SOURCE_BUILTIN) "Мой VPN" else
                    apps.find { it.packageName == picked }?.label ?: picked))
            },
            onSystemSettings = { tryStart(context, android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) },
            onDismiss = { showVpnSource = false },
        )
    }
}
