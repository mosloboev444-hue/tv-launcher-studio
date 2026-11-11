package com.gathin.tvhome.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.amnezia.awg.config.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AmneziaWG profiles the launcher's VPN can use: {@code .conf} files placed in the app's external
 * files dir, {@code Android/data/<package>/files/vpn/}. The chosen one is remembered in preferences.
 *
 *   adb push STR.WARP59009.conf /sdcard/Android/data/com.gathin.tvhome/files/vpn/
 */
public final class VpnProfiles {

    private static final String PREFS = "vpn";
    private static final String KEY_SELECTED = "selected_profile";

    private VpnProfiles() {
    }

    /** The folder the launcher reads profiles from (created on first use). */
    public static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        File dir = new File(base != null ? base : context.getFilesDir(), "vpn");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // fall back to internal storage when the external one is not writable
            dir = new File(context.getFilesDir(), "vpn");
            if (!dir.isDirectory()) dir.mkdirs();
        }
        return dir;
    }

    /** All {@code .conf} profiles, sorted by name. */
    public static List<File> list(Context context) {
        File[] files = dir(context).listFiles();
        List<File> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".conf") || name.endsWith(".config"))) out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** The profile to use: the remembered one if it still exists, otherwise the first file. */
    public static File selected(Context context) {
        List<File> all = list(context);
        if (all.isEmpty()) return null;
        String path = prefs(context).getString(KEY_SELECTED, null);
        if (path != null) {
            for (File f : all) {
                if (f.getAbsolutePath().equals(path)) return f;
            }
        }
        return all.get(0);
    }

    public static void select(Context context, File file) {
        prefs(context).edit().putString(KEY_SELECTED, file.getAbsolutePath()).apply();
    }

    /** Parses the selected profile, or returns null when there is none (or it is broken). */
    public static Config load(Context context) {
        File file = selected(context);
        if (file == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            return Config.parse(reader);
        } catch (Exception e) {   // IOException, BadConfigException, parsing errors
            return null;
        }
    }


    // ---------------- which VPN the button uses ----------------

    /** The VPN pill uses the launcher's own AmneziaWG tunnel. */
    public static final String SOURCE_BUILTIN = "builtin";
    private static final String KEY_SOURCE = "source";

    /** "builtin" or the package name of the VPN app the user picked. */
    public static String source(Context context) {
        String value = prefs(context).getString(KEY_SOURCE, SOURCE_BUILTIN);
        return value == null || value.trim().isEmpty() ? SOURCE_BUILTIN : value;
    }

    public static void setSource(Context context, String source) {
        if (source == null || source.trim().isEmpty()) source = SOURCE_BUILTIN;
        prefs(context).edit().putString(KEY_SOURCE, source).apply();
        // keep the older "default VPN app" preference in sync for the other screens
        if (SOURCE_BUILTIN.equals(source)) {
            prefs(context).edit().remove("default_vpn").apply();
        } else {
            prefs(context).edit().putString("default_vpn", source).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
