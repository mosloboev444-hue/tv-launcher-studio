package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The current weather behind the cinema screen's header pill.
 *
 * <p>Public, key-less services, only while Home is on screen: the city and its coordinates come from the
 * TV's public IP ({@code ipwho.is}), the reading from {@code wttr.in} and, if that fails, from
 * {@code api.met.no}. The result is cached in SharedPreferences for half an hour, so the launcher normally
 * shows the cached temperature without a request at all; a failed refresh keeps the old reading. Nothing
 * personal is sent: ipwho.is sees the connection's public IP, the weather services only the coordinates.
 *
 * <p>The launcher has no other network use, no telemetry and no background service: one small request pair
 * per half hour, on a background thread, only while the cinema Home is in front.
 */
final class Weather {

    /** Which glyph the pill draws. */
    enum Kind {CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER}

    static final class Reading {
        final String city;
        final int temperature;
        final String condition;
        final Kind kind;
        final long fetchedAt;

        Reading(String city, int temperature, String condition, Kind kind, long fetchedAt) {
            this.city = city;
            this.temperature = temperature;
            this.condition = condition;
            this.kind = kind;
            this.fetchedAt = fetchedAt;
        }
    }

    interface Callback {
        void onWeather(Reading reading);
    }

    private static final String TAG = "CinemaWeather";
    private static final String PREFS = "cinema_weather";
    private static final String USER_AGENT =
            "CleanYourTV/3.4 (+https://cleanyour.tv; personal TV launcher)";
    /** A reading older than this is refreshed when Home comes back; the pill never blanks in the meantime. */
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int TIMEOUT_MS = 6000;
    private static volatile boolean refreshing;

    private Weather() {
    }

    /** The reading stored by the last successful refresh, or null if there never was one. */
    static Reading cached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int temperature = prefs.getInt("temperature", Integer.MIN_VALUE);
        if (temperature == Integer.MIN_VALUE) return null;
        return new Reading(prefs.getString("city", ""), temperature, prefs.getString("condition", ""),
                kindOfOrdinal(prefs.getInt("kind", 0)), prefs.getLong("fetchedAt", 0L));
    }

    static boolean needsRefresh(Reading reading) {
        return reading == null || System.currentTimeMillis() - reading.fetchedAt > TTL_MS;
    }

    /** "Владимир, −3° пасмурно" for the pill's talkback description. */
    static String description(Reading reading) {
        if (reading == null) return "";
        String temperature = formatTemperature(reading.temperature);
        String where = reading.city == null || reading.city.trim().isEmpty()
                ? "" : reading.city.trim() + ", ";
        return where + temperature + " " + reading.condition.toLowerCase(Locale.ROOT);
    }

    static String formatTemperature(int celsius) {
        return celsius > 0 ? "+" + celsius + "°" : celsius + "°";
    }

    /** Fetches a fresh reading on a background thread; the callback runs on a background thread too. */
    static void refreshAsync(Context context, Callback callback) {
        if (refreshing) return;
        refreshing = true;
        final Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Reading reading = null;
            try {
                reading = fetch(app);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "refresh failed", e);
            } finally {
                refreshing = false;
            }
            if (reading != null) {
                store(app, reading);
                if (callback != null) callback.onWeather(reading);
            }
        }, "CinemaWeather");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static Reading fetch(Context context) throws IOException, JSONException {
        JSONObject where = new JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"));
        if (!where.optBoolean("success", false)) throw new IOException("no location from ipwho.is");
        String city = where.optString("city", "").trim();
        double latitude = where.getDouble("latitude");
        double longitude = where.getDouble("longitude");
        try {
            return wttr(city, latitude, longitude);
        } catch (IOException | JSONException | RuntimeException wttr) {
            Log.w(TAG, "wttr.in failed, trying met.no", wttr);
            return metNo(city, latitude, longitude);
        }
    }

    /** wttr.in's j1 JSON: the whole current condition, already in Russian, in one small reply. */
    private static Reading wttr(String city, double latitude, double longitude)
            throws IOException, JSONException {
        String url = String.format(Locale.ROOT, "https://wttr.in/%.4f,%.4f?format=j1", latitude, longitude);
        JSONObject condition = new JSONObject(get(url)).getJSONArray("current_condition").getJSONObject(0);
        int temperature = (int) Math.round(condition.getDouble("temp_C"));
        int code = condition.optInt("weatherCode", 113);
        return new Reading(city, temperature, condition(code), kindOf(code), System.currentTimeMillis());
    }

    /** met.no's location forecast: a little more to parse, but a stable public service with a real feed. */
    private static Reading metNo(String city, double latitude, double longitude)
            throws IOException, JSONException {
        String url = String.format(Locale.ROOT,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f",
                latitude, longitude);
        JSONObject entry = new JSONObject(get(url)).getJSONObject("properties")
                .getJSONArray("timeseries").getJSONObject(0);
        double temperature = entry.getJSONObject("data").getJSONObject("instant")
                .getJSONObject("details").getDouble("air_temperature");
        String symbol = "";
        try {
            symbol = entry.getJSONObject("data").getJSONObject("next_1_hours")
                    .getJSONObject("summary").optString("symbol_code", "");
        } catch (JSONException ignored) {
            // a forecast entry without the next hour: the temperature alone is still useful
        }
        return new Reading(city, (int) Math.round(temperature), symbolCondition(symbol),
                symbolKind(symbol), System.currentTimeMillis());
    }

    private static void store(Context context, Reading reading) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("city", reading.city)
                .putInt("temperature", reading.temperature)
                .putString("condition", reading.condition)
                .putInt("kind", reading.kind.ordinal())
                .putLong("fetchedAt", reading.fetchedAt)
                .apply();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Short Russian condition for the pill, from wttr.in's WW weather code. */
    private static String condition(int code) {
        switch (code) {
            case 113: return "Ясно";
            case 116: return "Малооблачно";
            case 119:
            case 122: return "Облачно";
            case 143:
            case 248:
            case 260: return "Туман";
            case 263:
            case 266: return "Морось";
            case 281:
            case 284:
            case 311:
            case 314:
            case 350: return "Ледяной дождь";
            case 293:
            case 296:
            case 299:
            case 302:
            case 305:
            case 308: return "Дождь";
            case 317:
            case 320: return "Слякоть";
            case 323:
            case 326:
            case 329:
            case 332:
            case 335:
            case 338: return "Снег";
            case 353:
            case 356:
            case 359: return "Ливень";
            case 362:
            case 365:
            case 368:
            case 371:
            case 374:
            case 377: return "Снегопад";
            case 386:
            case 389: return "Гроза";
            case 392:
            case 395: return "Гроза со снегом";
            default: return "Погода";
        }
    }

    private static Kind kindOf(int code) {
        switch (code) {
            case 113: return Kind.CLEAR;
            case 116: return Kind.PARTLY;
            case 119:
            case 122: return Kind.CLOUDY;
            case 143:
            case 248:
            case 260: return Kind.FOG;
            case 263:
            case 266:
            case 281:
            case 284:
            case 293:
            case 296:
            case 299:
            case 302:
            case 305:
            case 308:
            case 311:
            case 314:
            case 317:
            case 320:
            case 350:
            case 353:
            case 356:
            case 359: return Kind.RAIN;
            case 323:
            case 326:
            case 329:
            case 332:
            case 335:
            case 338:
            case 362:
            case 365:
            case 368:
            case 371:
            case 374:
            case 377: return Kind.SNOW;
            case 386:
            case 389:
            case 392:
            case 395: return Kind.THUNDER;
            default: return Kind.CLOUDY;
        }
    }

    /** Short Russian condition from a met.no symbol code ("clearsky_day", "lightrain", "lightsnow"...). */
    private static String symbolCondition(String symbol) {
        String s = symbol == null ? "" : symbol.toLowerCase(Locale.ROOT);
        if (s.startsWith("clearsky")) return "Ясно";
        if (s.startsWith("fair")) return "Малооблачно";
        if (s.startsWith("partlycloudy")) return "Переменная облачность";
        if (s.startsWith("cloudy")) return "Облачно";
        if (s.startsWith("fog")) return "Туман";
        if (s.startsWith("heavythunder") || s.startsWith("thunder")) return "Гроза";
        if (s.contains("sleet")) return "Слякоть";
        if (s.contains("snow")) return "Снег";
        if (s.startsWith("heavyrain")) return "Ливень";
        if (s.contains("drizzle")) return "Морось";
        if (s.contains("rain")) return "Дождь";
        if (s.startsWith("heavy")) return "Сильно";
        if (s.startsWith("light")) return "Слабо";
        return "Погода";
    }

    private static Kind symbolKind(String symbol) {
        String s = symbol == null ? "" : symbol.toLowerCase(Locale.ROOT);
        if (s.startsWith("clearsky")) return Kind.CLEAR;
        if (s.startsWith("fair") || s.startsWith("partlycloudy")) return Kind.PARTLY;
        if (s.startsWith("fog")) return Kind.FOG;
        if (s.contains("thunder")) return Kind.THUNDER;
        if (s.contains("snow") || s.contains("sleet")) return Kind.SNOW;
        if (s.contains("rain") || s.contains("drizzle")) return Kind.RAIN;
        return Kind.CLOUDY;
    }

    private static Kind kindOfOrdinal(int ordinal) {
        Kind[] kinds = Kind.values();
        return ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : Kind.CLOUDY;
    }
}
