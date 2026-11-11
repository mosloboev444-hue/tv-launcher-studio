package com.gathin.tvhome.universal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gathin.tvhome.R
import com.gathin.tvhome.universal.data.AppItem
import com.gathin.tvhome.universal.data.IconCache
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Dock(
    apps: List<AppItem>,
    favorites: List<AppItem>,
    onMenu: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Home should open with a tile focused, like the stock launchers do — not on a header button.
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(favorites, apps) {
        if (favorites.isEmpty() && apps.isEmpty()) return@LaunchedEffect
        // The window may not have focus yet when the first composition lands: keep trying for a moment.
        repeat(12) {
            delay(180)
            try {
                firstTile.requestFocus()
                return@LaunchedEffect
            } catch (_: Exception) {
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AppRow(stringResource(R.string.uni_favorites), favorites, stringResource(R.string.uni_favorites_hint), onMenu, onOpen, firstTile)
        AppRow(stringResource(R.string.uni_all_apps), apps, "", onMenu, onOpen, if (favorites.isEmpty()) firstTile else null)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppRow(title: String, apps: List<AppItem>, emptyHint: String, onMenu: (String) -> Unit, onOpen: (String) -> Unit, firstTile: FocusRequester? = null) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontSize = 20.sp,
            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
        if (apps.isEmpty() && emptyHint.isNotEmpty()) {
            Text(emptyHint, color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(vertical = 30.dp))
        }
        // Content padding gives the focused card's white ring and 1.09x scale room at both ends of the row;
        // without it the first and last cards are clipped by the row's viewport.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            items(apps, key = { "$title/${it.packageName}" }) { app ->
                Surface(
                    onClick = { SoundFx.play(Sound.SELECT); onOpen(app.packageName) },   // pointer only
                    onLongClick = { onMenu(app.packageName) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    border = ClickableSurfaceDefaults.border(
                        border = Border.None,
                        focusedBorder = Border(
                            border = BorderStroke(3.dp, Color.White),
                            inset = 0.dp,
                            shape = RoundedCornerShape(10.dp),
                        ),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.09f),
                    modifier = Modifier
                        .size(width = 160.dp, height = 90.dp)
                        .rememberTvOkGestures(
                            onTap = { SoundFx.play(Sound.SELECT); onOpen(app.packageName) },
                            onLongPress = { onMenu(app.packageName) },
                        )
                        .then(if (firstTile != null && app == apps.firstOrNull()) Modifier.focusRequester(firstTile) else Modifier)
                        .onFocusChanged { if (it.hasFocus) SoundFx.play(Sound.FOCUS) },
                ) {
                    AppImage(app)
                }
            }
        }
    }
}

/** Иконка грузится в фоне, только когда карточка на экране; пока грузится, видно название. */
@Composable
private fun AppImage(app: AppItem) {
    val context = LocalContext.current
    // The karaoke tile keeps its own animated fire banner (the same drawable as the cinema carousel).
    if (app.packageName == context.getString(R.string.fire_app)) {
        AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    setImageDrawable(com.gathin.tvhome.FireBannerDrawable())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    // размер картинки подстраивается под плотность экрана (HD / Full HD / 4K)
    val w = (160f * LocalDensity.current.density).coerceIn(224f, 320f).toInt()
    val h = w * 9 / 16
    val bitmap by produceState<ImageBitmap?>(IconCache.get(app.packageName), app.packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { IconCache.load(context, app.packageName, app.label, w, h) }
    }
    val b = bitmap
    if (b != null) {
        Image(bitmap = b, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF2A2F3A)), contentAlignment = Alignment.Center) {
            Text(app.label, color = Color.White, fontSize = 13.sp, maxLines = 2)
        }
    }
}
