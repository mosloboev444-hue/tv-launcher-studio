package com.gathin.tvhome.universal

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gathin.tvhome.universal.data.LauncherState
import com.gathin.tvhome.universal.data.SoundFx
import com.gathin.tvhome.universal.data.WallpaperCache
import com.gathin.tvhome.universal.ui.LauncherScreen

class UniversalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherScreen() }
    }

    override fun onStart() {
        super.onStart()
        LauncherState.screensaver = false
        LauncherState.attach(this)
    }

    override fun onResume() {
        super.onResume()
        // Re-assert ownership: a replaced instance may deliver its onStop() after this one resumed.
        LauncherState.attach(this)
    }

    /**
     * Когда пользователь ушёл в другое приложение (YouTube, Netflix...), лаунчер полностью
     * выгружает интерфейс и обои: освобождается память и не тратится процессор.
     */
    override fun onStop() {
        super.onStop()
        LauncherState.detach(this)
        WallpaperCache.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) SoundFx.release()
    }

    /** Любая кнопка пульта сбрасывает таймер заставки; если заставка открыта — только закрывает её. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        LauncherState.lastInput = System.currentTimeMillis()
        if (LauncherState.screensaver) {
            if (event.action == KeyEvent.ACTION_DOWN) LauncherState.screensaver = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
