package com.gathin.tvhome.universal.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Только название и пакет: список строится быстро и почти не занимает памяти. */
data class AppItem(val label: String, val packageName: String)

/** Последний загруженный список: при возврате на главный экран показывается мгновенно. */
object AppsCache {
    @Volatile var apps: List<AppItem> = emptyList()
}

fun loadApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val regular = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(regular, 0))
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .sortedBy { it.label.lowercase() }
        .also { AppsCache.apps = it }
}

fun launchApp(context: Context, packageName: String) {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
        ?: pm.getLaunchIntentForPackage(packageName)
        ?: return
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Иконки грузятся только для видимых карточек и кэшируются (до ~6 МБ). Баннер берётся, если он реально
 * что-то рисует; пустой/прозрачный баннер (а таких у части приложений полно) заменяется крупной иконкой,
 * а приложение без своей иконки — монограммой, а не системным «роботом».
 */
object IconCache {
    private val cache = object : LruCache<String, ImageBitmap>(6 * 1024) { // размер в КБ
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.asAndroidBitmap().byteCount / 1024).coerceAtLeast(1)
    }

    fun get(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(context: Context, pkg: String, label: String, W: Int, H: Int): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val pm = context.packageManager
        return try {
            val component = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.component
            val banner: Drawable? =
                component?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
                    ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            var painted = false
            if (banner != null) {
                banner.setBounds(0, 0, W, H)
                banner.draw(canvas)
                painted = hasContent(bmp)
            }
            if (!painted) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.drawColor(0xFF232833.toInt())
                val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                val iconEmpty = icon == null || isDefaultIcon(pm, icon)
                if (icon != null && !iconEmpty) {
                    val size = (H * 0.62f).toInt()
                    icon.setBounds((W - size) / 2, (H - size) / 2, (W + size) / 2, (H + size) / 2)
                    icon.draw(canvas)
                } else {
                    drawMonogram(canvas, label, W, H)
                }
            }
            bmp.asImageBitmap().also { cache.put(pkg, it) }
        } catch (_: Exception) {
            null
        }
    }

    /** A banner that is fully transparent or one flat colour draws nothing useful. */
    private fun hasContent(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        var painted = 0
        var total = 0
        var min = 255
        var max = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                total++
                if (Color.alpha(c) > 16) {
                    painted++
                    val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    if (lum < min) min = lum
                    if (lum > max) max = lum
                }
                x += stepX
            }
            y += stepY
        }
        if (total == 0) return false
        if (painted < total * 0.05) return false           // почти прозрачный
        return max - min > 10                              // и не заливка одним цветом
    }

    /** Compares the app icon with the system fallback icon at a small size. */
    private fun isDefaultIcon(pm: PackageManager, icon: Drawable): Boolean = try {
        val a = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(a).also { icon.setBounds(0, 0, 24, 24); icon.draw(it) }
        val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val def = pm.defaultActivityIcon
        Canvas(b).also { def.setBounds(0, 0, 24, 24); def.draw(it) }
        var diff = 0L
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            diff += (kotlin.math.abs(Color.red(p) - Color.red(q)) +
                kotlin.math.abs(Color.green(p) - Color.green(q)) +
                kotlin.math.abs(Color.blue(p) - Color.blue(q))) / 3
        }
        diff < 24 * 24 * 7L   // the system fallback icon in any of its renders
    } catch (_: Exception) {
        false
    }

    /** First letter of the app name on a coloured rounded plate. */
    private fun drawMonogram(canvas: Canvas, label: String, W: Int, H: Int) {
        val hue = kotlin.math.abs(label.hashCode()) % 360
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f))
        }
        val rect = android.graphics.RectF(0f, 0f, W.toFloat(), H.toFloat())
        canvas.drawRoundRect(rect, H * 0.12f, H * 0.12f, plate)
        val letter = label.trim().take(1).uppercase()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = H * 0.42f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val fm = text.fontMetrics
        canvas.drawText(letter, W / 2f, H / 2f - (fm.ascent + fm.descent) / 2f, text)
    }
}
