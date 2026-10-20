package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * What worked for each show (imdb id): the add-on and release group (Stremio's bingeGroup) that last played, and the
 * subtitle pick. The next episodes try the same add-on and release first and start with the same subtitles.
 */
final class PlaybackMemory {
    private PlaybackMemory() {}

    static final String FILE = "playback-memory";
    private static volatile Context app;

    /** Any screen that starts source discovery calls this, so the queue can use the memory without a Context. */
    static void init(Context context) { if (context != null && app == null) app = context.getApplicationContext(); }

    static String show(String videoId) {
        if (videoId == null) return "";
        int colon = videoId.indexOf(':');
        return colon < 0 ? videoId : videoId.substring(0, colon);
    }

    /** {addon, bingeGroup} that last played for this show, or null. */
    static String[] source(String videoId) {
        Context c = app; String show = show(videoId);
        if (c == null || !show.matches("tt[0-9]+")) return null;
        try {
            String raw = c.getSharedPreferences(FILE, 0).getString("source:" + show, null);
            if (raw == null) return null;
            JSONObject value = new JSONObject(raw);
            return new String[]{value.optString("addon", ""), value.optString("binge", "")};
        } catch (Exception ignored) { return null; }
    }

    static void rememberSource(Context context, String videoId, AddonStreams.Source source) {
        String show = show(videoId);
        if (context == null || source == null || !show.matches("tt[0-9]+") || source.addon == null) return;
        try {
            context.getApplicationContext().getSharedPreferences(FILE, 0).edit().putString("source:" + show,
                    new JSONObject().put("addon", source.addon).put("binge", source.bingeGroup == null ? "" : source.bingeGroup)
                            .put("at", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The subtitle pick for a show: "off", "embedded:<lang>", "addon:<lang>", or null when the viewer never chose. */
    static String subtitles(Context context, String videoId) {
        String show = show(videoId);
        if (context == null || show.isEmpty()) return null;
        return context.getApplicationContext().getSharedPreferences(FILE, 0).getString("subtitles:" + show, null);
    }

    static void rememberSubtitles(Context context, String videoId, String pick) {
        String show = show(videoId);
        if (context == null || show.isEmpty()) return;
        SharedPreferences.Editor e = context.getApplicationContext().getSharedPreferences(FILE, 0).edit();
        if (pick == null) e.remove("subtitles:" + show); else e.putString("subtitles:" + show, pick);
        e.apply();
    }

    // Episodes started by the autoplay countdown, in a row (for "Still watching?").
    static int autoplayChain(Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(FILE, 0);
        // A chain older than 4 hours has been broken by sleep or another evening.
        return System.currentTimeMillis() - p.getLong("chain-at", 0) > 4 * 3600000L ? 0 : p.getInt("chain", 0);
    }

    static void setAutoplayChain(Context context, int count) {
        context.getApplicationContext().getSharedPreferences(FILE, 0).edit().putInt("chain", count)
                .putLong("chain-at", System.currentTimeMillis()).apply();
    }

    /** Ask "Still watching?" before the next autoplay: 3 autoplayed episodes in a row, between 00:00 and 05:00. */
    static boolean stillWatchingDue(int chain, int hour, boolean enabled) {
        return enabled && chain >= 3 && hour >= 0 && hour < 5;
    }
}
