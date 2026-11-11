package com.gathin.tvhome.universal.data

import android.content.Context

const val WALLPAPER_AUTO = 3

data class LauncherSettings(
    val wallpaper: Int = 0,                // 0 — горы и озеро (как в референсе), 1..2 — другие, 3 — смена каждые 10 минут
    val soundTheme: Int = 0,               // 0 колокольчики, 1 мягкие, 2 кристалл
    val volume: Int = 2,                   // 0 выкл, 1 тихо, 2 средне, 3 громко
    val screensaverMinutes: Int = 5,       // 0 — выкл
)

private const val PREFS = "launcher"

fun loadSettings(context: Context): LauncherSettings {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val d = LauncherSettings()
    // Values baked in by Launcher Studio apply only until the user changes them on the TV.
    fun value(key: String, fallback: Int) =
        if (p.contains(key)) p.getInt(key, fallback)
        else com.gathin.tvhome.StudioDefaults.integer(context, key, fallback)
    return LauncherSettings(
        wallpaper = value("wallpaper", d.wallpaper),
        soundTheme = value("soundTheme", d.soundTheme),
        volume = value("volume", d.volume),
        screensaverMinutes = value("screensaverMinutes", d.screensaverMinutes),
    )
}

fun saveSettings(context: Context, s: LauncherSettings) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putInt("wallpaper", s.wallpaper)
        .putInt("soundTheme", s.soundTheme)
        .putInt("volume", s.volume)
        .putInt("screensaverMinutes", s.screensaverMinutes)
        .apply()
}
