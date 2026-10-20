package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal rolling playback log (stub until the player's own log lands; keep this API when replacing it). Lines are
 * short, privacy-safe descriptions ("A source had no sound, switched to another"), never addresses or keys.
 * Stored in prefs "playback-log" as a JSON array of {at, text}, newest first, at most 30.
 */
final class PlaybackLog {
    private PlaybackLog() {}

    private static final String FILE = "playback-log";

    static synchronized void add(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        JSONArray next = new JSONArray();
        try {
            next.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text.trim()));
            JSONArray old = new JSONArray(p.getString("lines", "[]"));
            for (int i = 0; i < old.length() && next.length() < 30; i++) next.put(old.get(i));
        } catch (Exception ignored) {
        }
        p.edit().putString("lines", next.toString()).apply();
    }

    /** The newest line as "<when> · <text>", or "" when nothing went wrong yet. */
    static String last(Context c) {
        try {
            JSONArray a = new JSONArray(c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString("lines", "[]"));
            JSONObject o = a.optJSONObject(0);
            if (o == null) return "";
            return Ui.time(o.optLong("at")) + " · " + o.optString("text");
        } catch (Exception e) {
            return "";
        }
    }
}
