package com.gathin.tvhome.universal.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gathin.tvhome.R
import com.gathin.tvhome.universal.data.ACTION_VPN_SETTINGS
import com.gathin.tvhome.universal.data.NetKind
import com.gathin.tvhome.universal.data.Sound
import com.gathin.tvhome.universal.data.SoundFx
import com.gathin.tvhome.universal.data.Weather
import com.gathin.tvhome.universal.data.rememberNetStatus
import com.gathin.tvhome.universal.data.toast
import com.gathin.tvhome.universal.data.tryStart
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import com.gathin.tvhome.universal.data.NetStatus

@Composable
fun TopBar(
    weather: Weather?,
    notifCount: Int,
    onNotifications: () -> Unit,
    onLauncherSettings: () -> Unit,
    onAssistant: () -> Unit,
    onScreens: () -> Unit,
    vpnOn: Boolean,
    vpnBuiltin: Boolean,
    vpnLabel: String,
    onVpn: () -> Unit,
    onVpnLong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val net by rememberNetStatus()

    // звук при смене состояния VPN (первое значение пропускаем)
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(net.vpn) {
        if (first) first = false else SoundFx.play(if (net.vpn) Sound.VPN_ON else Sound.VPN_OFF)
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(Icons.Default.Mic) { onAssistant() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(Icons.Default.Home) { onScreens() }
            // A held OK opens the VPN picker; it must open on release, or the held key's repeats end up in
            // the dialog and click its first row (see rememberTvOkGestures).
            VpnPill(
                vpnOn = if (vpnBuiltin) vpnOn else net.vpn,
                label = vpnLabel,
                onTap = onVpn,
                onLongPress = onVpnLong,
            )
            PillButton(
                icon = when (net.kind) {
                    NetKind.NONE -> Icons.Default.WifiOff
                    NetKind.ETHERNET -> Icons.Default.SettingsEthernet
                    else -> Icons.Default.Wifi
                },
                color = if (net.kind == NetKind.NONE) Color(0xFFC62828) else Color(0x66000000),
            ) { tryStart(context, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }
            PillButton(Icons.Default.Notifications, badge = notifCount, onClick = onNotifications)
            PillButton(Icons.Default.Search) {
                if (!tryStart(context, Intent("android.search.action.GLOBAL_SEARCH"), Intent(Intent.ACTION_ASSIST))) {
                    toast(context, context.getString(R.string.uni_search_unavailable))
                }
            }
            PillButton(Icons.Default.Palette, onClick = onLauncherSettings)
            PillButton(Icons.Default.Settings) { tryStart(context, Intent(Settings.ACTION_SETTINGS)) }
            if (weather != null) InfoPill {
                Text("${weather.emoji} ${weather.text}", color = Color.White, fontSize = 20.sp, maxLines = 1, softWrap = false)
            }
            ClockPill()
        }
    }
}

@Composable
private fun ClockPill() {
    val context = LocalContext.current
    val now by rememberNow()
    InfoPill {
        // Time only, single line: the date is on the other two screens, and the bar has no room for it here.
        Text(formatTime(context, now), color = Color.White, fontSize = 24.sp, maxLines = 1, softWrap = false)
    }
}

private fun startVoice(context: Context) {
    val ok = tryStart(
        context,
        Intent(Intent.ACTION_VOICE_COMMAND),
        Intent(Intent.ACTION_ASSIST),
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
    )
    if (!ok) toast(context, context.getString(R.string.uni_voice_not_found))
}

/** Для кнопки VPN: системный экран VPN, если нет выбранного приложения. */
fun vpnSettingsIntent() = Intent(ACTION_VPN_SETTINGS)


@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun VpnPill(vpnOn: Boolean, label: String, onTap: () -> Unit, onLongPress: () -> Unit) {
    // One neutral capsule, same height and palette as the other top-bar buttons: the key icon and the name
    // live inside it, so nothing sticks out under the button. No colour coding — the “· вкл” suffix tells
    // whether the chosen VPN (built-in tunnel or app) is currently up.
    Surface(
        onClick = { SoundFx.play(Sound.SELECT); onTap() },   // pointer/touch only; D-pad OK is handled below
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x66000000),
            focusedContainerColor = Color(0x66000000),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                inset = 0.dp,
                shape = RoundedCornerShape(50),
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.16f),
        modifier = Modifier
            .height(44.dp)
            .rememberTvOkGestures(
                onTap = { SoundFx.play(Sound.SELECT); onTap() },
                onLongPress = onLongPress,
            )
            .onFocusChanged { if (it.hasFocus) SoundFx.play(Sound.FOCUS) },
    ) {
        // Height = the capsule height, so the row is exactly as tall as the pill and its content is
        // centred vertically (without this the Surface placed the content at the top).
        Row(
            Modifier
                .height(44.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                if (vpnOn) "$label · вкл" else label,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
