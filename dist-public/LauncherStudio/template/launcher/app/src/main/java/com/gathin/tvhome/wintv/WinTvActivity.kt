package com.gathin.tvhome.wintv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.gathin.tvhome.ScreenBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WinTvActivity : Activity() {
    private val bg = Color.rgb(13, 18, 31)
    private val panel = Color.rgb(25, 34, 52)
    private val accent = Color.rgb(65, 135, 245)
    private val muted = 0xffaab7ca.toInt()
    private lateinit var appStrip: LinearLayout
    private lateinit var clock: TextView
    private var apps: List<ResolveInfo> = emptyList()
    private var soundsEnabled = true
    private var brandTaps = 0
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var startId = 0
    private var firstTileId = 0
    private var startView: TextView? = null
    private var switchView: TextView? = null
    private var settingsView: TextView? = null

    private val clockTick = object : Runnable {
        override fun run() {
            if (::clock.isInitialized) clock.text = SimpleDateFormat("EEE, d MMM   HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soundsEnabled = getPreferences(MODE_PRIVATE).getBoolean("sounds", true)
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        buildScreen()
        handler.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        if (::appStrip.isInitialized) loadApps()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(24), dp(42), dp(20))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = text("WinTV", 30f, Color.WHITE, true).apply {
            isFocusable = true
            setOnClickListener {
                brandTaps++
                if (brandTaps >= 5) { brandTaps = 0; easterEgg() } else sound(ToneGenerator.TONE_PROP_BEEP, 35)
            }
            setOnLongClickListener { showAbout(); true }
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        clock = text("", 17f, 0xffc5cede.toInt(), false)
        top.addView(clock)
        root.addView(top)

        root.addView(text("Твой телевизор. Твой Windows.", 32f, Color.WHITE, true).apply { setPadding(0, dp(28), 0, dp(5)) })
        root.addView(text("Приложения всегда под рукой", 17f, muted, false))
        root.addView(text("ПРИЛОЖЕНИЯ", 13f, 0xff91a4c2.toInt(), true).apply { setPadding(0, dp(28), 0, dp(12)) })

        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; descendantFocusability = ViewGroupCompat.AFTER_DESCENDANTS }
        appStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scroller.addView(appStrip)
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(150)))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val taskbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 22)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val start = text("⊞   Пуск", 17f, Color.WHITE, true).apply {
            background = rounded(accent, 16); setPadding(dp(22), dp(11), dp(22), dp(11)); isFocusable = true
            setOnClickListener { sound(ToneGenerator.TONE_PROP_ACK); showStartMenu() }
        }
        startId = View.generateViewId()
        start.id = startId
        startView = start
        taskbar.addView(start)
        taskbar.addView(text("WinTV Launcher", 15f, 0xffc3cede.toInt(), false).apply { setPadding(dp(18), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        val settings = text("⚙", 23f, Color.WHITE, true).apply {
            isFocusable = true; setPadding(dp(14), dp(5), dp(14), dp(5))
            setOnClickListener { sound(ToneGenerator.TONE_PROP_ACK); showSettings() }
            contentDescription = "Настройки WinTV"
        }
        val switch = text("⇄  Экран", 17f, Color.WHITE, true).apply {
            background = rounded(panel, 16)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            setOnClickListener { sound(ToneGenerator.TONE_PROP_ACK); ScreenBridge.cycle(this@WinTvActivity) }
        }
        switchView = switch
        taskbar.addView(switch, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(10) })
        taskbar.addView(settings)
        root.addView(taskbar)
        setContentView(root)
        loadApps()
    }

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvApps = packageManager.queryIntentActivities(intent, 0)
        val fallback = if (tvApps.isEmpty()) packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) else tvApps
        apps = fallback.distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        appStrip.removeAllViews()
        for ((index, info) in apps.withIndex()) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(panel, 18)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) accent else panel, 18)
                    v.scaleX = if (focused) 1.06f else 1f
                    v.scaleY = if (focused) 1.06f else 1f
                    if (focused) sound(ToneGenerator.TONE_PROP_BEEP, 20)
                }
                setOnClickListener { sound(ToneGenerator.TONE_PROP_ACK); launch(info) }
            }
            val icon = ImageView(this).apply { setImageDrawable(info.loadIcon(packageManager)); scaleType = ImageView.ScaleType.FIT_CENTER }
            tile.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
            tile.addView(text(info.loadLabel(packageManager).toString(), 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(108), dp(28)))
            val lp = LinearLayout.LayoutParams(dp(124), dp(124)); lp.marginEnd = dp(14)
            tile.id = View.generateViewId()
            tile.nextFocusDownId = startId
            if (index == 0) firstTileId = tile.id
            appStrip.addView(tile, lp)
            if (index == 0) tile.requestFocus()
        }
        if (apps.isEmpty()) appStrip.addView(text("Приложения не найдены", 18f, Color.WHITE, false))
        // The remote's Up from the taskbar returns to the app strip instead of losing focus.
        if (firstTileId != 0) {
            startView?.nextFocusUpId = firstTileId
            switchView?.nextFocusUpId = firstTileId
            settingsView?.nextFocusUpId = firstTileId
        }
    }

    private fun showStartMenu() {
        val items = arrayOf("Все приложения", "Настройки звука", "О WinTV Launcher")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("⊞  Пуск")
            .setItems(items) { _, which ->
                sound(ToneGenerator.TONE_PROP_ACK)
                when (which) {
                    0 -> appStrip.getChildAt(0)?.requestFocus()
                    1 -> showSettings()
                    2 -> showAbout()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showSettings() {
        val state = if (soundsEnabled) "Включены" else "Выключены"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Настройки WinTV")
            .setMessage("Звуки интерфейса: $state\n\nЗвуки создаются самим лаунчером и не используют системные файлы Windows.")
            .setPositiveButton(if (soundsEnabled) "Выключить звуки" else "Включить звуки") { _, _ ->
                soundsEnabled = !soundsEnabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("sounds", soundsEnabled).apply()
                if (soundsEnabled) sound(ToneGenerator.TONE_PROP_ACK)
                Toast.makeText(this, if (soundsEnabled) "Звуки включены" else "Звуки выключены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Готово", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("WinTV Launcher")
            .setMessage("Версия 0.2.0\n\nЛёгкий домашний экран для Philips Android TV.\n\nСделано с душой. Powered by ChatGPT.")
            .setPositiveButton("Класс!", null)
            .show()
    }

    private fun easterEgg() {
        sound(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("✦ Секрет найден!")
            .setMessage("Ты нашёл пасхалку.\n\nWIN TV\nСделано с душой.\nPowered by ChatGPT ♥")
            .setPositiveButton("Назад к телевизору", null)
            .show()
    }

    private fun launch(info: ResolveInfo) {
        val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName) ?: Intent(Intent.ACTION_MAIN).apply {
            component = android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        try { startActivity(launch) } catch (_: Exception) { Toast.makeText(this, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show() }
    }

    private fun sound(toneType: Int, duration: Int = 65) {
        if (soundsEnabled) tone?.startTone(toneType, duration)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        if (keyCode == KeyEvent.KEYCODE_MENU) { showStartMenu(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private object ViewGroupCompat { const val AFTER_DESCENDANTS = 0x40000 }
}
