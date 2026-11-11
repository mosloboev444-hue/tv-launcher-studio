package com.gathin.tvhome.polly

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalView
import com.gathin.tvhome.ScreenBridge
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ice = Color(0xFFB9D9FF)
private val White = Color(0xFFF4F7FC)
private val palettes = mapOf(
    "Полночь" to listOf(Color(0xFF071326), Color(0xFF142D4B), Color(0xFF35516B), Color(0xFF101B31)),
    "Океан" to listOf(Color(0xFF031D2B), Color(0xFF07536A), Color(0xFF147C86), Color(0xFF092B40)),
    "Графит" to listOf(Color(0xFF101318), Color(0xFF252B35), Color(0xFF414957), Color(0xFF171B22)),
    "Аметист" to listOf(Color(0xFF160F2B), Color(0xFF38265B), Color(0xFF654C88), Color(0xFF211638))
)

data class TvApp(val label: String, val packageName: String, val icon: Drawable)

class PollyActivity : ComponentActivity() {
    private var installedApps by mutableStateOf<List<TvApp>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        refreshApps()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ice, background = Color(0xFF071326), surface = Color(0xFF142642))) {
                PollyTvApp(
                    apps = installedApps,
                    onRefreshApps = ::refreshApps,
                    onLaunch = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                    onSystemSettings = { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) },
                    onSwitchScreen = { ScreenBridge.cycle(this) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The launcher owns the screen: Back should not drop the user into the system Home.
    }

    private fun refreshApps() {
        val pm = packageManager
        installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { TvApp(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
}

@Composable
private fun PollyTvApp(
    apps: List<TvApp>, onRefreshApps: () -> Unit, onLaunch: (String) -> Unit, onSystemSettings: () -> Unit,
    onSwitchScreen: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("polly_tv", 0) }
    var page by remember { mutableStateOf("home") }
    var selectedTab by remember { mutableStateOf("Внешний вид") }
    var wallpaper by remember { mutableStateOf(prefs.getString("wallpaper", "Полночь") ?: "Полночь") }
    var animations by remember { mutableStateOf(prefs.getBoolean("animations", true)) }
    var glass by remember { mutableStateOf(prefs.getBoolean("glass", true)) }
    var showClock by remember { mutableStateOf(prefs.getBoolean("clock", true)) }
    var showDate by remember { mutableStateOf(prefs.getBoolean("date", true)) }
    var clickSounds by remember { mutableStateOf(prefs.getBoolean("sounds", true)) }
    var iconScale by remember { mutableFloatStateOf(prefs.getFloat("icon_scale", 1f)) }

    fun save(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
            }
        }.apply()
    }
    fun sound() { if (clickSounds) android.view.View(context).playSoundEffect(SoundEffectConstants.CLICK) }
    val background = Brush.linearGradient(palettes[wallpaper] ?: palettes.getValue("Полночь"))

    Box(Modifier.fillMaxSize().background(background).padding(horizontal = 58.dp, vertical = 34.dp)) {
        AnimatedContent(targetState = page, label = "page") { current ->
            if (current == "home") {
                HomeScreen(
                    apps = apps, glass = glass, iconScale = iconScale,
                    showClock = showClock, showDate = showDate,
                    onLaunch = onLaunch, onSettings = { sound(); page = "settings" },
                    animations = animations, onSwitchScreen = onSwitchScreen
                )
            } else {
                SettingsScreen(
                    selectedTab = selectedTab, onTab = { sound(); selectedTab = it },
                    wallpaper = wallpaper, onWallpaper = { wallpaper = it; save("wallpaper", it); sound() },
                    animations = animations, onAnimations = { animations = it; save("animations", it) },
                    glass = glass, onGlass = { glass = it; save("glass", it) },
                    showClock = showClock, onShowClock = { showClock = it; save("clock", it) },
                    showDate = showDate, onShowDate = { showDate = it; save("date", it) },
                    clickSounds = clickSounds, onClickSounds = { clickSounds = it; save("sounds", it) },
                    iconScale = iconScale, onIconScale = { iconScale = it; save("icon_scale", it) },
                    appCount = apps.size, onRefreshApps = onRefreshApps,
                    onBack = { sound(); page = "home" }, onSystemSettings = onSystemSettings,
                    onSwitchScreen = onSwitchScreen
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    apps: List<TvApp>, glass: Boolean, iconScale: Float, showClock: Boolean, showDate: Boolean,
    onLaunch: (String) -> Unit, onSettings: () -> Unit, animations: Boolean,
    onSwitchScreen: () -> Unit
) {
    var time by remember { mutableStateOf(now("HH:mm")) }
    var date by remember { mutableStateOf(now("EEEE, d MMMM")) }
    LaunchedEffect(Unit) {
        while (true) { time = now("HH:mm"); date = now("EEEE, d MMMM"); delay(30_000) }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactAction("⇄  Экран", onSwitchScreen)
            CompactAction("⚙  Настройки", onSettings)
        }
        Spacer(Modifier.weight(1f))
        if (showClock) androidx.compose.material3.Text(time, color = White, fontSize = 82.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        if (showDate) androidx.compose.material3.Text(date.replaceFirstChar { it.uppercase() }, color = Ice.copy(alpha = .9f), fontSize = 19.sp)
        Spacer(Modifier.weight(1f))
        if (apps.isEmpty()) {
            androidx.compose.material3.Text("Приложения не найдены", color = White)
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth()
                    .background(if (glass) Color(0xAA142642) else Color(0xE6101B2C), RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
            ) { items(apps, key = { it.packageName }) { app -> AppTile(app, iconScale, animations) { onLaunch(app.packageName) } } }
        }
    }
}

@Composable
private fun AppTile(app: TvApp, iconScale: Float, animations: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && animations) 1.12f else 1f, label = "tileFocus")
    Surface(
        onClick = onClick,
        modifier = Modifier.width(104.dp).scale(scale).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(20.dp),
        color = if (focused) Color.White.copy(alpha = .17f) else Color.Transparent,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Ice else Color.White.copy(alpha = .08f))
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The launcher draws the app's icon as a plain bitmap: Compose's DrawablePainter is not in the
            // version this project builds against.
            val icon = remember(app.packageName) {
                app.icon.toBitmap(128, 128, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            Image(bitmap = icon, contentDescription = app.label, modifier = Modifier.size((48 * iconScale).dp))
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text(app.label, color = White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsScreen(
    selectedTab: String, onTab: (String) -> Unit,
    wallpaper: String, onWallpaper: (String) -> Unit,
    animations: Boolean, onAnimations: (Boolean) -> Unit,
    glass: Boolean, onGlass: (Boolean) -> Unit,
    showClock: Boolean, onShowClock: (Boolean) -> Unit,
    showDate: Boolean, onShowDate: (Boolean) -> Unit,
    clickSounds: Boolean, onClickSounds: (Boolean) -> Unit,
    iconScale: Float, onIconScale: (Float) -> Unit,
    appCount: Int, onRefreshApps: () -> Unit,
    onBack: () -> Unit, onSystemSettings: () -> Unit,
    onSwitchScreen: () -> Unit
) {
    val tabs = listOf("Внешний вид", "Главный экран", "Приложения", "Панель", "Звук", "Система")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.width(250.dp).fillMaxHeight().background(Color(0xAA10233D), RoundedCornerShape(26.dp))
                .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(26.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Text("POLLY TV", color = Ice, fontSize = 13.sp, letterSpacing = 2.sp)
            androidx.compose.material3.Text("Настройки", color = White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            tabs.forEach { tab -> TvAction(tab, { onTab(tab) }, selectedTab == tab) }
            Spacer(Modifier.weight(1f))
            TvAction("←  На главный экран", onBack)
            TvAction("⇄  Сменить экран", onSwitchScreen)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xAA10233D), RoundedCornerShape(26.dp))
                .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(26.dp)).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            androidx.compose.material3.Text(selectedTab, color = White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            when (selectedTab) {
                "Внешний вид" -> {
                    androidx.compose.material3.Text("Цветовая атмосфера", color = Ice, fontSize = 16.sp)
                    palettes.keys.forEach { choice -> TvAction(if (wallpaper == choice) "✓   $choice" else choice, { onWallpaper(choice) }, wallpaper == choice) }
                    SettingToggle("Стеклянная панель", glass, onGlass)
                    SettingToggle("Плавные анимации", animations, onAnimations)
                }
                "Главный экран" -> {
                    androidx.compose.material3.Text("Что показывать на главном экране", color = Ice, fontSize = 16.sp)
                    SettingToggle("Большие часы", showClock, onShowClock)
                    SettingToggle("Дату под часами", showDate, onShowDate)
                }
                "Приложения" -> {
                    androidx.compose.material3.Text("Найдено приложений: $appCount", color = White, fontSize = 18.sp)
                    androidx.compose.material3.Text("Список формируется автоматически из приложений, установленных на телевизоре.", color = White.copy(alpha = .7f), fontSize = 15.sp)
                    TvAction("↻  Обновить список", onRefreshApps)
                }
                "Панель" -> {
                    androidx.compose.material3.Text("Размер иконок", color = Ice, fontSize = 16.sp)
                    androidx.compose.material3.Text("${(iconScale * 100).toInt()}%", color = White, fontSize = 18.sp)
                    Slider(value = iconScale, onValueChange = onIconScale, valueRange = .75f..1.35f)
                }
                "Звук" -> {
                    androidx.compose.material3.Text("Звуковая обратная связь интерфейса", color = Ice, fontSize = 16.sp)
                    SettingToggle("Звук нажатий", clickSounds, onClickSounds)
                    androidx.compose.material3.Text("Используются системные звуки Android TV. Громкость регулируется телевизором.", color = White.copy(alpha = .65f), fontSize = 14.sp)
                }
                "Система" -> {
                    androidx.compose.material3.Text("Polly TV • версия 1.1", color = White.copy(alpha = .8f), fontSize = 18.sp)
                    androidx.compose.material3.Text("Самостоятельный интерфейс для Android TV. Не меняет системные настройки без твоего подтверждения.", color = White.copy(alpha = .65f), fontSize = 14.sp)
                    TvAction("Открыть настройки Android", onSystemSettings)
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = .06f), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text(label, color = White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CompactAction(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Ice.copy(alpha = .20f) else Color.White.copy(alpha = .045f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Ice else Color.White.copy(alpha = .08f))
    ) {
        androidx.compose.material3.Text(
            text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = White, fontSize = 15.sp
        )
    }
}

@Composable
private fun TvAction(text: String, onClick: () -> Unit, selected: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || selected
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp),
        color = if (active) Ice.copy(alpha = .20f) else Color.White.copy(alpha = .045f),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Ice else Color.White.copy(alpha = .08f))
    ) {
        androidx.compose.material3.Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = White, fontSize = 15.sp)
    }
}

private fun now(pattern: String): String = SimpleDateFormat(pattern, Locale("ru")).format(Date())
