package com.gathin.tvhome.universal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gathin.tvhome.universal.data.AppItem
import com.gathin.tvhome.universal.data.AssistantCommands
import com.gathin.tvhome.universal.data.DeepSeek
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * DeepSeek as the launcher's voice assistant.
 *
 * The mic pill opens this overlay: it listens (the TV's own recogniser), handles launcher commands locally,
 * and sends everything else to DeepSeek, showing and speaking the answer. Without a speech service or
 * microphone permission it falls back to typing a question with the remote.
 */
@Composable
fun AssistantDialog(apps: List<AppItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var textMode by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val speaker = remember { Speaker(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    val textFocus = remember { FocusRequester() }
    val key = remember { DeepSeek.loadKey(context) }

    fun say(text: String) {
        answer = text
        phase = Phase.ANSWER
        speaker.say(text)
    }

    fun process(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        question = text
        answer = ""
        val local = AssistantCommands.handle(context, apps, text)
        if (local != null) {
            say(local)
            if (local.startsWith("Открываю")) {
                scope.launch {
                    delay(900)
                    onDismiss()
                }
            }
            return
        }
        if (key.isNullOrEmpty()) {
            phase = Phase.ERROR
            answer = "Нужен ключ DeepSeek. Откройте настройки лаунчера (кнопка с палитрой) → «DeepSeek: ключ»."
            return
        }
        phase = Phase.THINKING
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { DeepSeek.ask(text, key) } }
            result.onSuccess { say(it) }
                .onFailure {
                    phase = Phase.ERROR
                    answer = "Не получилось: ${it.message}"
                }
        }
    }

    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var listening by remember { mutableStateOf(false) }

    fun listen() {
        val r = recognizer ?: return
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите вопрос")
        }
        phase = Phase.LISTENING
        answer = ""
        runCatching { r.startListening(intent) }
        listening = true
        SoundFx.play(Sound.FOCUS)
    }

    DisposableEffect(Unit) {
        val r = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        recognizer = r
        r?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                if (phase == Phase.LISTENING) {
                    phase = Phase.ERROR
                    answer = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал. Попробуйте ещё раз."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не дождался речи. Попробуйте ещё раз."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону. Разрешите его в настройках TV."
                        else -> "Распознавание недоступно. Можно ввести вопрос текстом."
                    }
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) process(text)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) question = text
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        onDispose {
            runCatching { r?.cancel(); r?.destroy() }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else {
            phase = Phase.ERROR
            answer = "Нет доступа к микрофону. Разрешите его в настройках TV или введите вопрос текстом."
        }
    }

    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            phase = Phase.ERROR
            answer = "На этом телевизоре нет распознавания речи. Введите вопрос текстом."
            textMode = true
        } else if (!hasMic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            delay(250)
            listen()
        }
    }

    LaunchedEffect(textMode) {
        if (textMode) {
            delay(200)
            runCatching { textFocus.requestFocus() }
            keyboard?.show()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(680.dp)
                    .background(Color(0xF01B2430), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).background(Color(0xFF3D7BD9), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("DeepSeek-помощник", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when (phase) {
                                Phase.LISTENING -> "Слушаю…"
                                Phase.THINKING -> "Думаю…"
                                Phase.ANSWER -> "Готово"
                                Phase.ERROR -> "Ошибка"
                            },
                            color = if (phase == Phase.LISTENING) Color(0xFF9BD1FF) else Color(0xCCFFFFFF),
                            fontSize = 15.sp,
                        )
                    }
                }

                if (question.isNotBlank()) {
                    Text("Вы: $question", color = Color(0xFFBBD6FF), fontSize = 17.sp, maxLines = 3)
                }

                if (textMode) {
                    BasicTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = false,
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { process(typed) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(textFocus)
                            .submitOnOk { process(typed) }
                            .background(Color(0xFF101722), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    )
                    if (typed.isBlank()) {
                        Text("Напишите вопрос и нажмите «Спросить»", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    }
                }

                if (answer.isNotBlank()) {
                    Text(
                        answer,
                        color = if (phase == Phase.ERROR) Color(0xFFFFB4A8) else Color.White,
                        fontSize = 21.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistantButton("Слушать снова", primary = true) {
                        textMode = false
                        listen()
                    }
                    AssistantButton(if (textMode) "Сказать голосом" else "Вопрос текстом") {
                        textMode = !textMode
                    }
                    if (textMode) AssistantButton("Спросить") { process(typed) }
                    AssistantButton("Закрыть") { onDismiss() }
                }
            }
        }
    }
}

private enum class Phase { LISTENING, THINKING, ANSWER, ERROR }

/** On a TV the remote's OK is the natural "send" for a text field; the IME's send key also works. */
private fun Modifier.submitOnOk(onSubmit: () -> Unit): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
        onSubmit()
        true
    } else {
        false
    }
}

@Composable
private fun AssistantButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = { SoundFx.play(Sound.SELECT); onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFF3D7BD9) else Color(0x33FFFFFF),
            focusedContainerColor = if (primary) Color(0xFF3D7BD9) else Color(0x33FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                inset = 0.dp,
                shape = RoundedCornerShape(10.dp),
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** The TV's own text-to-speech, speaking Russian when the engine has it. */
private class Speaker(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) runCatching {
                tts?.language = Locale("ru", "RU")
            }
        }
    }

    fun say(text: String) {
        if (!ready) return
        runCatching {
            tts?.language = Locale("ru", "RU")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "deepseek-assistant")
        }
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }
}

/** The API key lives only on the TV: paste it here, or push a file (see README). */
@Composable
fun DeepSeekKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(DeepSeek.loadKey(context).orEmpty()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(180)
        runCatching { focus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(640.dp)
                    .background(Color(0xF01B2430), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("DeepSeek: ключ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Ключ с platform.deepseek.com (sk-…). Он хранится только на этом телевизоре и не попадает в APK.",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp,
                )
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .submitOnOk {
                            if (value.trim().isNotEmpty()) DeepSeek.saveKey(context, value) else DeepSeek.clearKey(context)
                            onDismiss()
                        }
                        .background(Color(0xFF101722), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistantButton("Сохранить", primary = true) {
                        if (value.trim().isNotEmpty()) DeepSeek.saveKey(context, value) else DeepSeek.clearKey(context)
                        onDismiss()
                    }
                    AssistantButton("Удалить") {
                        DeepSeek.clearKey(context)
                        onDismiss()
                    }
                    AssistantButton("Закрыть") { onDismiss() }
                }
            }
        }
    }
}
