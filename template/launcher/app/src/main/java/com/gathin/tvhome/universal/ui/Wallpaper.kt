package com.gathin.tvhome.universal.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.gathin.tvhome.R
import com.gathin.tvhome.universal.data.WallpaperCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val WALLPAPERS = listOf(R.drawable.wp_lake, R.drawable.wp_sunset, R.drawable.wp_night)

@Composable
fun Wallpaper(index: Int) {
    val context = LocalContext.current
    val i = index.coerceIn(0, WALLPAPERS.lastIndex)
    // декодирование в фоне; пока грузится новая картинка, остаётся предыдущая
    val bitmap by produceState<ImageBitmap?>(WallpaperCache.get(i), i) {
        value = withContext(Dispatchers.IO) { WallpaperCache.load(context, WALLPAPERS[i], i) }
    }
    Crossfade(targetState = bitmap, animationSpec = tween(800), label = "wallpaper") { bmp ->
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B1620)))
        }
    }
}
