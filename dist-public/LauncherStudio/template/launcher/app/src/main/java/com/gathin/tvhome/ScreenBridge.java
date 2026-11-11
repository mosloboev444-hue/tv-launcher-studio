package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * The small public surface the universal (Compose) Home screen needs from the Java launcher: which Home screen
 * is chosen, the configured dock and VPN app, and the weather chip's text.
 *
 * <p>Kept in this package so it can read {@link Config}, {@link ScreenProfile} and {@link Weather} without making
 * those package-private classes public.
 */
public final class ScreenBridge {

    private ScreenBridge() {
    }

    /** "cinema", "classic" or "universal". */
    public static String current(Context context) {
        return ScreenProfile.current(context);
    }

    public static boolean isUniversal(Context context) {
        return ScreenProfile.isUniversal(context);
    }

    /** Stores the choice and opens Home again (the caller activity should finish itself). */
    public static void switchTo(Activity activity, String screen) {
        ScreenProfile.setScreen(activity, screen);
        Intent home = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(home);
        activity.finish();
    }

    /** Moves to the next Home screen; used by the screen-switch button on the PollyTV/WinTV screens. */
    public static void cycle(Activity activity) {
        switchTo(activity, ScreenProfile.next(activity));
    }

    /** The apps configured for the dock, offered as the first "Favorites" list on the universal screen. */
    public static String[] defaultFavorites(Context context) {
        try {
            Config config = Config.load(context);
            List<String> dock = config.dockApps;
            return dock == null ? new String[0] : dock.toArray(new String[0]);
        } catch (RuntimeException e) {
            return new String[0];
        }
    }

    /** The VPN app configured for CleanYourTV's VPN pill, used as the universal screen's default VPN. */
    public static String defaultVpn(Context context) {
        try {
            Config config = Config.load(context);
            String vpn = config.vpnApp;
            return vpn == null || vpn.trim().isEmpty() ? null : vpn.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A weather reading in the form the Compose pill shows, or null until the first reading arrives. */
    public static final class WeatherInfo {
        public final String text;
        /** {@code Weather.Kind} ordinal: 0 clear, 1 partly, 2 cloudy, 3 fog, 4 rain, 5 snow, 6 thunder. */
        public final int kind;

        WeatherInfo(String text, int kind) {
            this.text = text;
            this.kind = kind;
        }
    }

    public static WeatherInfo cachedWeather(Context context) {
        Weather.Reading reading = Weather.cached(context);
        if (reading == null) return null;
        String text = Weather.formatTemperature(reading.temperature) + " " + reading.condition;
        return new WeatherInfo(text, reading.kind.ordinal());
    }

    /** Starts a refresh in the background when the cached reading is older than half an hour. */
    public static void refreshWeather(Context context) {
        Weather.Reading reading = Weather.cached(context);
        if (Weather.needsRefresh(reading)) {
            Weather.refreshAsync(context.getApplicationContext(), null);
        }
    }
}
