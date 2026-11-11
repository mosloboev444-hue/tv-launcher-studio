"""Ports PollyTV and WinTV sources into our launcher as two extra Home screens.

Run from D:/Projects/tv/work/cyt-fork/launcher:  python patch_five_screens.py
"""
import os
import re
import shutil

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(BASE, "app", "src", "main")
SRC = r"D:/Projects/tv/work/launchers4"


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("written:", os.path.relpath(path, BASE))


# ---------------------------------------------------------------- PollyTV
polly = read(os.path.join(SRC, "PollyTV/app/src/main/java/com/pollytv/launcher/MainActivity.kt"))
polly = polly.replace("package com.pollytv.launcher", "package com.gathin.tvhome.polly")
polly = polly.replace("class MainActivity : ComponentActivity()", "class PollyActivity : ComponentActivity()")

# lifecycle observer imports are unused in this source and moved packages in Compose 1.7: drop them
for unused in (
    "import androidx.lifecycle.Lifecycle\n",
    "import androidx.lifecycle.LifecycleEventObserver\n",
    "import androidx.compose.ui.platform.LocalLifecycleOwner\n",
):
    polly = polly.replace(unused, "")

# imports for the screen switch
polly = polly.replace(
    "import android.content.Intent\n",
    "import android.app.Activity\nimport android.content.Intent\n",
)
polly = polly.replace(
    "import kotlinx.coroutines.delay\n",
    "import com.gathin.tvhome.ScreenBridge\nimport kotlinx.coroutines.delay\n",
)

# back = stay on this Home screen (the HOME key routes back here anyway)
polly = polly.replace(
    "    override fun onResume() {\n        super.onResume()\n        refreshApps()\n    }",
    "    override fun onResume() {\n        super.onResume()\n        refreshApps()\n    }\n\n"
    "    @Suppress(\"DEPRECATION\")\n"
    "    override fun onBackPressed() {\n        // The launcher owns the screen: Back should not drop the user into the system Home.\n"
    "    }",
)

# pass the screen-switch callback down
polly = polly.replace(
    "                    onSystemSettings = { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }\n",
    "                    onSystemSettings = { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) },\n"
    "                    onSwitchScreen = { ScreenBridge.cycle(this) }\n",
)
polly = polly.replace(
    "private fun PollyTvApp(\n    apps: List<TvApp>, onRefreshApps: () -> Unit, onLaunch: (String) -> Unit, onSystemSettings: () -> Unit\n) {",
    "private fun PollyTvApp(\n    apps: List<TvApp>, onRefreshApps: () -> Unit, onLaunch: (String) -> Unit, onSystemSettings: () -> Unit,\n"
    "    onSwitchScreen: () -> Unit\n) {",
)
polly = polly.replace(
    "                    onLaunch = onLaunch, onSettings = { sound(); page = \"settings\" },\n"
    "                    animations = animations\n",
    "                    onLaunch = onLaunch, onSettings = { sound(); page = \"settings\" },\n"
    "                    animations = animations, onSwitchScreen = onSwitchScreen\n",
)
polly = polly.replace(
    "                    onBack = { sound(); page = \"home\" }, onSystemSettings = onSystemSettings\n",
    "                    onBack = { sound(); page = \"home\" }, onSystemSettings = onSystemSettings,\n"
    "                    onSwitchScreen = onSwitchScreen\n",
)
polly = polly.replace(
    "private fun HomeScreen(\n    apps: List<TvApp>, glass: Boolean, iconScale: Float, showClock: Boolean, showDate: Boolean,\n"
    "    onLaunch: (String) -> Unit, onSettings: () -> Unit, animations: Boolean\n) {",
    "private fun HomeScreen(\n    apps: List<TvApp>, glass: Boolean, iconScale: Float, showClock: Boolean, showDate: Boolean,\n"
    "    onLaunch: (String) -> Unit, onSettings: () -> Unit, animations: Boolean,\n"
    "    onSwitchScreen: () -> Unit\n) {",
)
polly = polly.replace(
    "        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {\n"
    "            TvAction(\"⚙  Настройки\", onSettings)\n"
    "        }",
    "        Row(\n"
    "            Modifier.fillMaxWidth(),\n"
    "            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),\n"
    "            verticalAlignment = Alignment.CenterVertically,\n"
    "        ) {\n"
    "            CompactAction(\"⇄  Экран\", onSwitchScreen)\n"
    "            CompactAction(\"⚙  Настройки\", onSettings)\n"
    "        }",
)
polly = polly.replace(
    "    selectedTab: String, onTab: (String) -> Unit,\n",
    "    selectedTab: String, onTab: (String) -> Unit,\n",
)
polly = polly.replace(
    "    onBack: () -> Unit, onSystemSettings: () -> Unit\n) {",
    "    onBack: () -> Unit, onSystemSettings: () -> Unit,\n"
    "    onSwitchScreen: () -> Unit\n) {",
)
polly = polly.replace(
    "            TvAction(\"←  На главный экран\", onBack)\n",
    "            TvAction(\"←  На главный экран\", onBack)\n"
    "            TvAction(\"⇄  Сменить экран\", onSwitchScreen)\n",
)

# a compact (wrap-content) action for the header row, and a neutral signature line
marker = "@Composable\nprivate fun TvAction("
compact = '''@Composable
private fun CompactAction(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Ice.copy(alpha = .20f) else Color.White.copy(alpha = .045f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Ice else Color.White.copy(alpha = .08f))
    ) {
        androidx.compose.material3.Text(
            text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = White, fontSize = 15.sp
        )
    }
}

''' + marker
assert marker in polly
polly = polly.replace(marker, compact, 1)
polly = polly.replace('"POLLY TV  •  MADE WITH CHATGPT"', '"POLLY TV  •  ПОЛНЫЙ ЭКРАН"')

write(os.path.join(APP, "java/com/gathin/tvhome/polly/PollyActivity.kt"), polly)

# ---------------------------------------------------------------- WinTV
win = read(os.path.join(SRC, "WinTVLauncher/app/src/main/java/com/example/wintvlauncher/MainActivity.kt"))
win = win.replace("package com.example.wintvlauncher", "package com.gathin.tvhome.wintv")
win = win.replace("class MainActivity : Activity()", "class WinTvActivity : Activity()")
win = win.replace(
    "import android.widget.Toast\n",
    "import android.widget.Toast\nimport com.gathin.tvhome.ScreenBridge\n",
)

# refresh the app strip when coming back (apps may have been installed/uninstalled meanwhile)
win = win.replace(
    "    override fun onDestroy() {",
    "    override fun onResume() {\n"
    "        super.onResume()\n"
    "        if (::appStrip.isInitialized) loadApps()\n"
    "    }\n\n"
    "    override fun onDestroy() {",
)

# the taskbar gets a screen-switch button next to the gear
win = win.replace(
    "        taskbar.addView(settings)\n",
    "        val switch = text(\"⇄  Экран\", 17f, Color.WHITE, true).apply {\n"
    "            background = rounded(panel, 16)\n"
    "            setPadding(dp(16), dp(9), dp(16), dp(9))\n"
    "            isFocusable = true\n"
    "            setOnClickListener { sound(ToneGenerator.TONE_PROP_ACK); ScreenBridge.cycle(this@WinTvActivity) }\n"
    "        }\n"
    "        taskbar.addView(switch, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(10) })\n"
    "        taskbar.addView(settings)\n",
)

write(os.path.join(APP, "java/com/gathin/tvhome/wintv/WinTvActivity.kt"), win)
print("screens ported")
