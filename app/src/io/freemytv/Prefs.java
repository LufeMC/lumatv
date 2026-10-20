package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Every setting more than one screen reads, in one place: file names, keys and defaults. */
final class Prefs {
    private Prefs() {}

    // Playback (file shared with the player since before LumaTV)
    static final String PLAYBACK = "playback-settings";
    static final String AUDIO_LANGUAGE = "audio-language";              // "" = the source's default
    static final String SUBTITLE_LANGUAGE = "subtitle-language";        // "" = none chosen
    static final String SUBTITLE_MODE = "subtitle-mode";                // foreign | always | forced
    static final String SUBTITLE_SIZE = "subtitle-size";                // small | medium | large
    static final String SUBTITLE_BACKGROUND = "subtitle-background";    // shadow | box
    static final String AUTO_SYNC = "subtitle-auto-sync";               // bool, default true
    static final String AUTOPLAY = "autoplay-next";                     // bool, default true
    static final String MATCH_FRAME_RATE = "match-frame-rate";          // bool, default true
    static final String STILL_WATCHING = "still-watching";              // bool, default true

    // Subtitle timing per release (key = source id), in ms; positive = later
    static final String SUBTITLE_OFFSETS = "subtitle-offsets";

    // Services the user pays for: the installed services they switched off
    static final String SERVICES = "my-services";
    static final String SERVICES_OFF = "off";

    // Remote buttons: key code → package (or "inputs", "home")
    static final String REMOTE = "remote-keys";

    // Home
    static final String HOME_UI = "home-ui";
    static final String OPENING_SEEN = "opening-seen";                  // versionCode the opening last played for
    static final String LOOK = "look";                                  // sunset | day | night
    static final String RECENT_SEARCHES = "recent-searches";            // JSON array, newest first

    // Updates and sync
    static final String UPDATES = "updates";
    static final String AUTO_UPDATE = "auto";
    static final String SYNC = "tv-sync";

    static SharedPreferences get(Context c, String file) {
        return c.getApplicationContext().getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    static boolean flag(Context c, String file, String key, boolean fallback) {
        return get(c, file).getBoolean(key, fallback);
    }

    /** Installed services the user marked as not paid for. */
    static Set<String> servicesOff(Context c) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(get(c, SERVICES).getString(SERVICES_OFF, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void setServiceOn(Context c, String pkg, boolean on) {
        Set<String> off = servicesOff(c);
        if (on) off.remove(pkg); else off.add(pkg);
        get(c, SERVICES).edit().putString(SERVICES_OFF, new JSONArray(off).toString()).apply();
    }

    static boolean serviceOn(Context c, String pkg) {
        return !servicesOff(c).contains(pkg);
    }

    static long subtitleOffset(Context c, String release) {
        return release == null || release.isEmpty() ? 0 : get(c, SUBTITLE_OFFSETS).getLong(release, 0);
    }

    static void setSubtitleOffset(Context c, String release, long ms) {
        if (release == null || release.isEmpty()) return;
        SharedPreferences.Editor e = get(c, SUBTITLE_OFFSETS).edit();
        if (ms == 0) e.remove(release); else e.putLong(release, ms);
        e.apply();
    }

    static JSONObject remoteMap(Context c) {
        try {
            return new JSONObject(get(c, REMOTE).getString("map", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void setRemoteMap(Context c, JSONObject map) {
        get(c, REMOTE).edit().putString("map", map.toString()).commit();
    }

    /**
     * The remote map as written by another process (Settings runs in :home, the RemoteKeys service in the main one):
     * MODE_MULTI_PROCESS re-reads the file when it changed on disk. Cheap when it didn't.
     */
    @SuppressWarnings("deprecation")
    static JSONObject remoteMapFresh(Context c) {
        try {
            return new JSONObject(c.getApplicationContext().getSharedPreferences(REMOTE, Context.MODE_MULTI_PROCESS)
                    .getString("map", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Dark appearance is the default. The user can select daylight or automatic appearance. */
    static String look(Context c) {
        String v = get(c, HOME_UI).getString(LOOK, "night");
        return "day".equals(v) || "sunset".equals(v) ? v : "night";
    }
}
