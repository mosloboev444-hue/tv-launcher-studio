package com.gathin.tvhome.universal.data

import android.content.Context

private const val PREFS = "launcher"
private const val KEY = "favs_order"

/** Точный пакет «Видео MIX», если известен. Иначе приложение ищется по названию. */
const val VIDEO_MIX_PACKAGE = ""

fun isVideoMix(app: AppItem): Boolean =
    (VIDEO_MIX_PACKAGE.isNotEmpty() && app.packageName == VIDEO_MIX_PACKAGE) ||
        app.label.contains("video mix", true) ||
        app.label.contains("видео mix", true) ||
        app.label.contains("видео микс", true)

/** Упорядоченный список избранного; null — пользователь ещё ничего не сохранял. */
fun loadFavorites(context: Context): List<String>? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.split("\n")?.filter { it.isNotBlank() }

fun saveFavorites(context: Context, favs: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY, favs.joinToString("\n")).apply()
}
