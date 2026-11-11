"""Wires the two ported screens into the launcher: profile cycle, routing, picker, manifest, strings."""
import os
import re

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(BASE, "app")
MAIN = os.path.join(APP, "src", "main")
JAVA = os.path.join(MAIN, "java", "com", "gathin", "tvhome")


def patch(path, pairs, label=None):
    path = os.path.join(BASE, path) if not os.path.isabs(path) else path
    with open(path, encoding="utf-8") as f:
        s = f.read()
    for old, new in pairs:
        assert old in s, "pattern missing in " + os.path.relpath(path, BASE) + ": " + old[:80]
        s = s.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)
    print("patched:", label or os.path.relpath(path, BASE))


# ---------------------------------------------------------------- ScreenProfile
patch(os.path.join(JAVA, "ScreenProfile.java"), [
    ('''    /** The third Home screen: the universal Compose launcher (works on any Android TV or box). */
    static final String UNIVERSAL = "universal";''',
     '''    /** The third Home screen: the universal Compose launcher (works on any Android TV or box). */
    static final String UNIVERSAL = "universal";
    /** Fourth Home screen: the dark atmospheric PollyTV dock. */
    static final String POLLY = "polly";
    /** Fifth Home screen: the Windows-styled WinTV launcher. */
    static final String WINTV = "wintv";'''),
    ('''        String value = prefs(ctx).getString(KEY, CINEMA);
        if (CLASSIC.equals(value)) return CLASSIC;
        if (UNIVERSAL.equals(value)) return UNIVERSAL;
        return CINEMA;''',
     '''        String value = prefs(ctx).getString(KEY, CINEMA);
        if (CLASSIC.equals(value)) return CLASSIC;
        if (UNIVERSAL.equals(value)) return UNIVERSAL;
        if (POLLY.equals(value)) return POLLY;
        if (WINTV.equals(value)) return WINTV;
        return CINEMA;'''),
    ('''    /** True when the universal (Compose) Home should be shown instead of this activity. */
    static boolean isUniversal(Context ctx) {
        return UNIVERSAL.equals(current(ctx));
    }''',
     '''    /** True when the universal (Compose) Home should be shown instead of this activity. */
    static boolean isUniversal(Context ctx) {
        return UNIVERSAL.equals(current(ctx));
    }

    static boolean isPolly(Context ctx) {
        return POLLY.equals(current(ctx));
    }

    static boolean isWinTv(Context ctx) {
        return WINTV.equals(current(ctx));
    }'''),
    ('''    /** Stores the screen choice; the caller restarts or recreates Home itself. */
    static void setScreen(Context ctx, String screen) {
        String value = CLASSIC.equals(screen) ? CLASSIC : UNIVERSAL.equals(screen) ? UNIVERSAL : CINEMA;
        prefs(ctx).edit().putString(KEY, value).apply();
    }''',
     '''    /** Stores the screen choice; the caller restarts or recreates Home itself. */
    static void setScreen(Context ctx, String screen) {
        String value;
        if (CLASSIC.equals(screen)) value = CLASSIC;
        else if (UNIVERSAL.equals(screen)) value = UNIVERSAL;
        else if (POLLY.equals(screen)) value = POLLY;
        else if (WINTV.equals(screen)) value = WINTV;
        else value = CINEMA;
        prefs(ctx).edit().putString(KEY, value).apply();
    }'''),
    ('''    /** Next screen in the menu's cycle: Кино → Классика → Универсальный → Кино. */
    static String next(Context ctx) {
        switch (current(ctx)) {
            case CLASSIC: return UNIVERSAL;
            case UNIVERSAL: return CINEMA;
            default: return CLASSIC;
        }
    }''',
     '''    /** Next screen in the menu's cycle: Кино → Классика → Универсальный → PollyTV → WinTV → Кино. */
    static String next(Context ctx) {
        switch (current(ctx)) {
            case CLASSIC: return UNIVERSAL;
            case UNIVERSAL: return POLLY;
            case POLLY: return WINTV;
            case WINTV: return CINEMA;
            default: return CLASSIC;
        }
    }'''),
], "ScreenProfile")

# ---------------------------------------------------------------- ScreenBridge: cycle helper
patch(os.path.join(JAVA, "ScreenBridge.java"), [
    ('''    /** The apps configured for the dock, offered as the first "Favorites" list on the universal screen. */''',
     '''    /** Moves to the next Home screen; used by the screen-switch button on the PollyTV/WinTV screens. */
    public static void cycle(Activity activity) {
        switchTo(activity, ScreenProfile.next(activity));
    }

    /** The apps configured for the dock, offered as the first "Favorites" list on the universal screen. */'''),
], "ScreenBridge")

# ---------------------------------------------------------------- MainActivity routing/menu
patch(os.path.join(JAVA, "MainActivity.java"), [
    ('''        if (ScreenProfile.isUniversal(this)) {
            // Nothing below ran, so every cleanup in onPause/onStop/onDestroy would touch fields that were never
            // created: mark the short route and skip the normal lifecycle work.
            routedAway = true;
            startActivity(new Intent(this, com.gathin.tvhome.universal.UniversalActivity.class));
            finish();
            return;
        }''',
     '''        Class<?> routedScreen = handledScreenActivity(ScreenProfile.current(this));
        if (routedScreen != null) {
            // Nothing below ran, so every cleanup in onPause/onStop/onDestroy would touch fields that were never
            // created: mark the short route and skip the normal lifecycle work.
            routedAway = true;
            startActivity(new Intent(this, routedScreen));
            finish();
            return;
        }'''),
    ('''    private int screenProfileName(String screen) {
        if (ScreenProfile.CLASSIC.equals(screen)) return R.string.screen_profile_classic;
        if (ScreenProfile.UNIVERSAL.equals(screen)) return R.string.screen_profile_universal;
        return R.string.screen_profile_cinema;
    }''',
     '''    private int screenProfileName(String screen) {
        if (ScreenProfile.CLASSIC.equals(screen)) return R.string.screen_profile_classic;
        if (ScreenProfile.UNIVERSAL.equals(screen)) return R.string.screen_profile_universal;
        if (ScreenProfile.POLLY.equals(screen)) return R.string.screen_profile_polly;
        if (ScreenProfile.WINTV.equals(screen)) return R.string.screen_profile_wintv;
        return R.string.screen_profile_cinema;
    }

    /** The new Home screens live in their own activities; returns null for the cinema/classic ones. */
    private Class<?> handledScreenActivity(String screen) {
        if (ScreenProfile.UNIVERSAL.equals(screen)) return com.gathin.tvhome.universal.UniversalActivity.class;
        if (ScreenProfile.POLLY.equals(screen)) return com.gathin.tvhome.polly.PollyActivity.class;
        if (ScreenProfile.WINTV.equals(screen)) return com.gathin.tvhome.wintv.WinTvActivity.class;
        return null;
    }'''),
    ('''    /** Cycles Кино → Классика → Универсальный; the universal one opens its own (Compose) activity. */
    private void switchScreenProfile() {
        String next = ScreenProfile.next(this);
        ScreenProfile.setScreen(this, next);
        Toast.makeText(this, getString(screenProfileName(next)), Toast.LENGTH_SHORT).show();
        if (ScreenProfile.UNIVERSAL.equals(next)) {
            startActivity(new Intent(this, com.gathin.tvhome.universal.UniversalActivity.class));
            finish();
        } else {
            recreate();
        }
    }''',
     '''    /** Cycles Кино → Классика → Универсальный → PollyTV → WinTV. */
    private void switchScreenProfile() {
        String next = ScreenProfile.next(this);
        ScreenProfile.setScreen(this, next);
        Toast.makeText(this, getString(screenProfileName(next)), Toast.LENGTH_SHORT).show();
        Class<?> handled = handledScreenActivity(next);
        if (handled != null) {
            startActivity(new Intent(this, handled));
            finish();
        } else {
            recreate();
        }
    }'''),
], "MainActivity")

# ---------------------------------------------------------------- manifest activities
patch(os.path.join(MAIN, "AndroidManifest.xml"), [
    ('''        <activity
            android:name=".LauncherSettingsActivity"''',
     '''        <activity
            android:name=".polly.PollyActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|uiMode|density|fontScale|locale|layoutDirection"
            android:theme="@style/Theme.TvLauncher" />

        <activity
            android:name=".wintv.WinTvActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|uiMode|density|fontScale|locale|layoutDirection"
            android:theme="@style/Theme.TvLauncher" />

        <activity
            android:name=".LauncherSettingsActivity"'''),
], "AndroidManifest")

# ---------------------------------------------------------------- strings
for path, pairs in (
    (os.path.join(MAIN, "res", "values", "strings.xml"), [
        ('    <string name="screen_profile_classic">Screen: Classic</string>',
         '    <string name="screen_profile_classic">Screen: Classic</string>\n'
         '    <string name="screen_profile_polly">Screen: PollyTV</string>\n'
         '    <string name="screen_profile_wintv">Screen: WinTV</string>'),
        ('    <string name="uni_screen_universal">Universal</string>',
         '    <string name="uni_screen_universal">Universal</string>\n'
         '    <string name="uni_screen_polly">PollyTV</string>\n'
         '    <string name="uni_screen_wintv">WinTV</string>'),
    ]),
    (os.path.join(MAIN, "res", "values-ru", "strings.xml"), [
        ('    <string name="screen_profile_classic">Экран: Классика</string>',
         '    <string name="screen_profile_classic">Экран: Классика</string>\n'
         '    <string name="screen_profile_polly">Экран: PollyTV</string>\n'
         '    <string name="screen_profile_wintv">Экран: WinTV</string>'),
        ('    <string name="uni_screen_universal">Универсальный</string>',
         '    <string name="uni_screen_universal">Универсальный</string>\n'
         '    <string name="uni_screen_polly">PollyTV</string>\n'
         '    <string name="uni_screen_wintv">WinTV</string>'),
    ]),
):
    patch(path, pairs, os.path.relpath(path, BASE))

# ---------------------------------------------------------------- universal picker entries
picker = os.path.join(JAVA, "universal", "ui", "Overlays.kt")
with open(picker, encoding="utf-8") as f:
    s = f.read()
old = '''        MenuButton(
            text = stringResource(R.string.uni_screen_universal),
            value = if (current == "universal") tick else null,
        ) { onDismiss() }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }'''
new = '''        MenuButton(
            text = stringResource(R.string.uni_screen_universal),
            value = if (current == "universal") tick else null,
        ) {
            if (current == "universal") onDismiss()
            else if (activity != null) ScreenBridge.switchTo(activity, "universal") else onDismiss()
        }
        MenuButton(
            text = stringResource(R.string.uni_screen_polly),
            value = if (current == "polly") tick else null,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "polly") else onDismiss() }
        MenuButton(
            text = stringResource(R.string.uni_screen_wintv),
            value = if (current == "wintv") tick else null,
        ) { if (activity != null) ScreenBridge.switchTo(activity, "wintv") else onDismiss() }
        MenuButton(stringResource(R.string.uni_close)) { onDismiss() }'''
assert old in s
s = s.replace(old, new, 1)
with open(picker, "w", encoding="utf-8") as f:
    f.write(s)
print("patched: universal screen picker")

# ---------------------------------------------------------------- gradle: material3 + version 4.2.0
with open(os.path.join(APP, "build.gradle"), encoding="utf-8") as f:
    s = f.read()
if "compose.material3:material3" not in s:
    s = s.replace("    implementation 'androidx.tv:tv-material:1.0.0'",
                  "    implementation 'androidx.tv:tv-material:1.0.0'\n"
                  "    // PollyTV's settings use Material 3 toggles/sliders on the same Compose BOM.\n"
                  "    implementation 'androidx.compose.material3:material3'")
s = s.replace("        versionCode 26\n        versionName '4.1.7'", "        versionCode 27\n        versionName '4.2.0'")
with open(os.path.join(APP, "build.gradle"), "w", encoding="utf-8") as f:
    f.write(s)
print("patched: build.gradle (material3, 4.2.0)")
print("integration done")
