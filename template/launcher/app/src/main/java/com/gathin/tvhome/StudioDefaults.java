package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Build-time defaults written by Launcher Studio into {@code assets/studio_defaults.json}.
 *
 * <p>Only read while the user has not made the choice on the TV yet, so anything set on the device wins.
 * The asset is absent in the normal build, and every getter then behaves exactly as before.
 */
public final class StudioDefaults {

    private static final String ASSET = "studio_defaults.json";
    private static JSONObject cached;
    private static boolean loaded;

    private StudioDefaults() {
    }

    private static synchronized JSONObject data(Context context) {
        if (!loaded) {
            loaded = true;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(ASSET), "UTF-8"))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) text.append(line).append('\n');
                cached = new JSONObject(text.toString());
            } catch (Throwable ignored) {
                cached = null;
            }
        }
        return cached;
    }

    public static String string(Context context, String key) {
        JSONObject json = data(context);
        if (json == null) return null;
        String value = json.optString(key, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static int integer(Context context, String key, int fallback) {
        JSONObject json = data(context);
        return json == null ? fallback : json.optInt(key, fallback);
    }

    public static String[] stringArray(Context context, String key) {
        JSONObject json = data(context);
        if (json == null) return null;
        JSONArray array = json.optJSONArray(key);
        if (array == null) return null;
        String[] out = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            out[i] = value == null ? null : value.trim();
        }
        return out;
    }
}
