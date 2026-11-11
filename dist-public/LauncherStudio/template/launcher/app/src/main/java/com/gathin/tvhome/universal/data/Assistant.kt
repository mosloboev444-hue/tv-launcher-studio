package com.gathin.tvhome.universal.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.gathin.tvhome.ScreenBridge
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * DeepSeek as the launcher's voice assistant.
 *
 * The mic button opens a small overlay: speech is recognised on the TV, launcher commands ("открой YouTube",
 * "погода", "переключи на классику", "громче") are handled locally and instantly, and anything else is sent to
 * DeepSeek's chat API. The answer is shown and spoken by the TV's TTS engine.
 *
 * The API key lives only on the TV: in the "assistant" SharedPreferences (set in the launcher settings) or in
 * a deepseek_key.txt file in the app's external files dir, so it never goes into the APK or the repository.
 */
object DeepSeek {
    private const val PREFS = "assistant"
    private const val KEY = "deepseek_key"
    private const val KEY_FILE = "deepseek_key.txt"
    private const val ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    private const val MODEL = "deepseek-chat"

    private const val SYSTEM_PROMPT =
        "Ты — голосовой помощник прямо в телевизоре. Отвечай кратко: 1–2 предложения, без markdown и listов, " +
            "так, чтобы текст хорошо звучал вслух. Если тебя просят сделать что-то с телевизором, а такое действие " +
            "тебе недоступно, коротко скажи, что можешь открыть приложения, переключить экран, показать погоду " +
            "и изменить громкость."

    private fun externalFiles(context: Context) = listOfNotNull(
        context.getExternalFilesDir(null),
        File(context.filesDir, "external"),
    )

    fun loadKey(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.trim()
        if (!saved.isNullOrEmpty()) return saved
        for (dir in externalFiles(context)) {
            val text = runCatching { File(dir, KEY_FILE).readText() }.getOrNull()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key.trim()).apply()
    }

    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** Blocking request: call it on an IO coroutine/thread. Throws on network or API errors. */
    fun ask(question: String, key: String): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("temperature", 0.6)
            .put("max_tokens", 400)
            .put(
                "messages",
                org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", question)),
            )
            .toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 40000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(if (message.isBlank()) "DeepSeek: HTTP $status" else message)
            }
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .trim()
            if (content.isEmpty()) throw IllegalStateException("DeepSeek: пустой ответ")
            return content
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Commands the launcher can do itself, without the network: opening apps and screens, the weather chip,
 * the volume and the system settings. Returns a spoken reply, or null when DeepSeek should answer.
 */
object AssistantCommands {

    fun handle(context: Context, apps: List<AppItem>, raw: String): String? {
        val text = raw.lowercase(Locale("ru")).trim().trimEnd('.', '!', '?')

        // «открой youtube», «запусти кинопоиск», «open netflix»
        for (prefix in listOf("открой ", "открыть ", "запусти ", "запустить ", "включи приложение ", "open ", "launch ", "start ")) {
            if (text.startsWith(prefix)) {
                val query = text.removePrefix(prefix).trim()
                val app = findApp(apps, query) ?: return "Не нашёл приложение «$query»."
                launchApp(context, app.packageName)
                return "Открываю ${app.label}."
            }
        }

        // «включи впн», «открой впн»
        if (text.contains("впн") || text.contains("vpn")) {
            if (text.contains("выключ") || text.contains("отключ")) {
                openSettings(context, "android.settings.VPN_SETTINGS")
                return "Открываю настройки VPN."
            }
            val vpn = ScreenBridge.defaultVpn(context)
            if (vpn != null) {
                launchApp(context, vpn)
                return "Открываю VPN."
            }
            openSettings(context, "android.settings.VPN_SETTINGS")
            return "Открываю настройки VPN."
        }

        // «погода», «какая погода»
        if (text.contains("погод")) {
            val weather = ScreenBridge.cachedWeather(context)
            return if (weather != null) "Сейчас ${weather.text.lowercase(Locale("ru"))}."
            else "Погода ещё не загрузилась."
        }

        // «переключи на классику / кино», «классический экран»
        if (text.contains("экран") || text.contains("классик") || text.contains("кино") || text == "кино") {
            when {
                text.contains("классик") -> {
                    val activity = context as? android.app.Activity
                    if (activity != null) ScreenBridge.switchTo(activity, "classic")
                    return "Включаю классический экран."
                }
                text.contains("кино") -> {
                    val activity = context as? android.app.Activity
                    if (activity != null) ScreenBridge.switchTo(activity, "cinema")
                    return "Включаю экран «Кино»."
                }
            }
        }

        // «громче», «тише», «выключи звук»
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (text.contains("громче") || text.contains("громкость выше") || text.contains("louder")) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return "Громче."
        }
        if (text.contains("тише") || text.contains("громкость ниже") || text.contains("quieter")) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return "Тише."
        }
        if (text.contains("выключи звук") || text.contains("без звука") || text.contains("mute")) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            return "Звук выключен."
        }

        // «который час», «сколько времени»
        if (text.contains("время") || text.contains("час")) {
            val now = java.text.SimpleDateFormat("HH:mm", Locale("ru")).format(java.util.Date())
            return "Сейчас $now."
        }

        // «настройки»
        if (text.contains("настройки") || text == "настройки" || text.contains("settings")) {
            openSettings(context, "android.settings.SETTINGS")
            return "Открываю настройки."
        }
        return null
    }

    private fun findApp(apps: List<AppItem>, query: String): AppItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return apps.firstOrNull { it.label.lowercase(Locale.getDefault()).contains(q) }
            ?: apps.firstOrNull { it.packageName.lowercase(Locale.ROOT).contains(q.replace(' ', '.')) }
            ?: apps.firstOrNull { q.contains(it.label.lowercase(Locale.getDefault())) }
    }

    private fun openSettings(context: Context, action: String) {
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
