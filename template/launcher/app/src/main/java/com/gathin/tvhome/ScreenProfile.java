package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Two Home screens on one launcher, chosen on the TV:
 * <ul>
 *   <li>{@link #CINEMA} — the video-reference look: clock and date in the corner, round header buttons, a frosted
 *       dock of app marks at the bottom over a photo wallpaper, the focused app's name in a chip above its tile.</li>
 *   <li>{@link #CLASSIC} — the built-in Home the TV shipped with: greeting, big dock cards, rows below the dock.</li>
 * </ul>
 *
 * The choice lives in the launcher's own settings and is applied as a JSON overlay on top of the theme's
 * config.json, so it wins over whatever theme the TV has. It is switched from the menu that opens on a held OK
 * on the Settings button (MainActivity#showSettingsMenu). MainActivity restyles the top shelf for the cinema screen,
 * and Config skips the on-TV layout choices (columns, rows, dock opacity, clock format) for it so the preset stays
 * exactly as designed; the classic screen keeps them.
 */
final class ScreenProfile {
    static final String KEY = "screen_profile";
    static final String CINEMA = "cinema";
    static final String CLASSIC = "classic";
    /** The third Home screen: the universal Compose launcher (works on any Android TV or box). */
    static final String UNIVERSAL = "universal";
    /** Fourth Home screen: the dark atmospheric PollyTV dock. */
    static final String POLLY = "polly";
    /** Fifth Home screen: the Windows-styled WinTV launcher. */
    static final String WINTV = "wintv";

    private ScreenProfile() {
    }

    /** The chosen screen; unknown values fall back to the cinema one. */
    static String current(Context ctx) {
        String value = prefs(ctx).getString(KEY, CINEMA);
        if (CLASSIC.equals(value)) return CLASSIC;
        if (UNIVERSAL.equals(value)) return UNIVERSAL;
        if (POLLY.equals(value)) return POLLY;
        if (WINTV.equals(value)) return WINTV;
        return CINEMA;
    }

    static boolean isCinema(Context ctx) {
        return CINEMA.equals(current(ctx));
    }

    static boolean isClassic(Context ctx) {
        return CLASSIC.equals(current(ctx));
    }

    /** True when the universal (Compose) Home should be shown instead of this activity. */
    static boolean isUniversal(Context ctx) {
        return UNIVERSAL.equals(current(ctx));
    }

    static boolean isPolly(Context ctx) {
        return POLLY.equals(current(ctx));
    }

    static boolean isWinTv(Context ctx) {
        return WINTV.equals(current(ctx));
    }

    /** Stores the screen choice; the caller restarts or recreates Home itself. */
    static void setScreen(Context ctx, String screen) {
        String value;
        if (CLASSIC.equals(screen)) value = CLASSIC;
        else if (UNIVERSAL.equals(screen)) value = UNIVERSAL;
        else if (POLLY.equals(screen)) value = POLLY;
        else if (WINTV.equals(screen)) value = WINTV;
        else value = CINEMA;
        prefs(ctx).edit().putString(KEY, value).apply();
    }

    static void set(Context ctx, boolean cinema) {
        setScreen(ctx, cinema ? CINEMA : CLASSIC);
    }

    /** Flips between the two CleanYourTV screens and returns whether the cinema one is now on. */
    static boolean toggle(Context ctx) {
        boolean cinema = !isCinema(ctx);
        set(ctx, cinema);
        return cinema;
    }

    /** Next screen in the menu's cycle: Кино → Классика → Универсальный → PollyTV → WinTV → Кино. */
    static String next(Context ctx) {
        switch (current(ctx)) {
            case CLASSIC: return UNIVERSAL;
            case UNIVERSAL: return POLLY;
            case POLLY: return WINTV;
            case WINTV: return CINEMA;
            default: return CLASSIC;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The chosen screen's keys over the theme's: the profile decides these, whatever config.json is on the TV.
     * Returns a copy, never the argument.
     */
    static JSONObject overlay(Context ctx, JSONObject theme) {
        JSONObject out = new JSONObject();
        if (theme != null) {
            for (Iterator<String> it = theme.keys(); it.hasNext(); ) {
                String key = it.next();
                try {
                    out.put(key, theme.get(key));
                } catch (JSONException ignored) {
                    // a theme key that cannot be copied is simply left to the config.xml default
                }
            }
        }
        JSONObject preset = isClassic(ctx) ? classicPreset() : cinemaPreset();
        for (Iterator<String> it = preset.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                out.put(key, preset.get(key));
            } catch (JSONException ignored) {
                // cannot happen for the literals below; a missing key is the config.xml default
            }
        }
        return out;
    }

    /** The reference screen: minimal header, round buttons, glass dock of marks over the wallpaper. */
    private static JSONObject cinemaPreset() {
        JSONObject j = new JSONObject();
        try {
            j.put("dock_apps", list("com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv",
                    "ru.rutube.app", "com.gathin.vpngate"));
            j.put("columns", 5);
            j.put("fill_dock", false);
            j.put("rows_below_dock", 0);
            j.put("single_row", true);
            j.put("dock_band_all", true);
            j.put("dock_row", true);
            j.put("dock_glass", true);
            j.put("dock_band", true);
            j.put("dock_even_padding", true);
            j.put("dock_title", false);
            j.put("dock_opacity", 70);
            j.put("always_show_names", false);
            j.put("uniform_tiles", true);
            j.put("glass_style", "rim");
            j.put("show_greeting", false);
            j.put("date_below_clock", false);
            j.put("header_white", true);
            j.put("clock_format", "24");
            j.put("icon_only_buttons", true);
            j.put("icon_only_inputs", true);
            j.put("icon_only_vpn", true);
            j.put("top_menu", false);
            j.put("launcher_settings_button", false);
            j.put("wallpaper", true);
            j.put("wallpaper_true_color", true);
            j.put("wallpaper_dim", 0);
            j.put("background_color", "#05060A");
            j.put("sky_intensity", 0);
            j.put("ambient_glow", false);
            j.put("app_glow", false);
            j.put("fade_rows", false);
            j.put("vpn_app", "st.uboo.android.client");
            j.put("vpn_pill", true);
            j.put("animations", true);
            j.put("advanced_effects", true);
            j.put("home_idle_clock_minutes", 0);
        } catch (JSONException ignored) {
            // JSONObject.put only throws for NaN and null keys; neither is used here
        }
        return j;
    }

    /** The screen the TV had before this build: the greeting, the big dock cards, the rows below. */
    private static JSONObject classicPreset() {
        JSONObject j = new JSONObject();
        try {
            j.put("dock_apps", list("com.google.android.youtube.tv", "com.gathin.vpngate",
                    "com.gathin.karaoke", "ru.iptvremote.android.iptv"));
            j.put("columns", 4);
            j.put("rows_below_dock", 2);
            j.put("fill_dock", true);
            j.put("single_row", false);
            j.put("dock_band_all", false);
            j.put("dock_row", true);
            j.put("dock_glass", false);
            j.put("dock_band", true);
            j.put("dock_even_padding", false);
            j.put("dock_title", true);
            j.put("dock_opacity", 100);
            j.put("always_show_names", false);
            j.put("uniform_tiles", false);
            j.put("glass_style", "glass");
            j.put("show_greeting", true);
            j.put("date_below_clock", true);
            j.put("header_white", false);
            j.put("clock_format", "system");
            j.put("icon_only_buttons", false);
            j.put("icon_only_inputs", false);
            j.put("icon_only_vpn", false);
            j.put("top_menu", false);
            j.put("launcher_settings_button", false);
            j.put("wallpaper", false);
            j.put("sky_intensity", 0);
            j.put("ambient_glow", true);
            j.put("app_glow", false);
            j.put("fade_rows", false);
            j.put("vpn_app", "st.uboo.android.client");
            j.put("vpn_pill", true);
            j.put("animations", true);
            j.put("advanced_effects", false);
            j.put("home_idle_clock_minutes", 0);
        } catch (JSONException ignored) {
            // see cinemaPreset()
        }
        return j;
    }

    private static JSONArray list(String... values) {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return a;
    }
}
