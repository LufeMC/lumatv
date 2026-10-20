package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;

/**
 * A short rolling log of source attempts and failure reasons, readable on the TV (Settings shows the last line).
 * Lines never carry addresses, keys or tokens: anything that looks like one is removed before saving.
 */
final class PlaybackLog {
    private PlaybackLog() {}

    static final String FILE = "playback-log";
    static final int LIMIT = 50;

    static synchronized void add(Context context, String line) {
        if (context == null || line == null) return;
        String clean = scrub(line);
        if (clean.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(FILE, 0);
            JSONArray old = new JSONArray(prefs.getString("lines", "[]")), lines = new JSONArray();
            String stamp = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date());
            lines.put(stamp + " " + clean);
            for (int i = 0; i < old.length() && lines.length() < LIMIT; i++) lines.put(old.getString(i));
            prefs.edit().putString("lines", lines.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The newest line, or "" when nothing was logged. */
    static String last(Context context) {
        try {
            JSONArray lines = new JSONArray(context.getApplicationContext().getSharedPreferences(FILE, 0).getString("lines", "[]"));
            return lines.length() == 0 ? "" : lines.getString(0);
        } catch (Exception ignored) { return ""; }
    }

    /** All lines, newest first. */
    static java.util.List<String> lines(Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray lines = new JSONArray(context.getApplicationContext().getSharedPreferences(FILE, 0).getString("lines", "[]"));
            for (int i = 0; i < lines.length(); i++) out.add(lines.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    /** Removes addresses, query strings, long tokens and control characters; caps the length. */
    static String scrub(String line) {
        String text = line.replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://\\S+", "[link]")
                .replaceAll("(?i)\\b(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+/\\S*", "[link]")
                .replaceAll("[?&][A-Za-z0-9_.-]+=\\S*", "")
                .replaceAll("[A-Za-z0-9_\\-+/=]{24,}", "[id]")
                .replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
}
