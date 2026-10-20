package io.freemytv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * The Stremio account library ("libraryItem" collection): compare modification metadata before downloading it, keep
 * the downloaded items (a private file, read only when writing), and write changes back with datastorePut so
 * progress and the watchlist follow the viewer to the phone and other TVs. Item shapes follow stremio-core's
 * LibraryItem; an existing item is changed in place so fields this app doesn't use (watched episodes) survive.
 */
final class LibrarySync {
    static final String API = "https://api.strem.io/api/";
    private static final java.util.concurrent.ExecutorService writes = java.util.concurrent.Executors.newSingleThreadExecutor();

    static String fingerprint(JSONArray metadata) throws Exception {
        if (metadata == null || metadata.length() > 20000) throw new Exception("library_metadata");
        TreeMap<String, Long> items = new TreeMap<>();
        for (int i = 0; i < metadata.length(); i++) {
            JSONArray item = metadata.optJSONArray(i);
            if (item == null || item.length() != 2 || !(item.opt(0) instanceof String) || !(item.opt(1) instanceof Number)
                    || item.getString(0).length() > 200 || item.getLong(1) < 0) throw new Exception("library_metadata");
            items.put(item.getString(0), item.getLong(1));
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (java.util.Map.Entry<String, Long> item : items.entrySet()) {
            digest.update(item.getKey().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            digest.update(Long.toString(item.getValue()).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
    }

    // --- The downloaded library, by id

    private static File file(Context context) { return new File(context.getFilesDir(), "stremio-library.json"); }

    static synchronized void store(Context context, JSONArray list) {
        try {
            JSONObject byId = new JSONObject();
            for (int i = 0; list != null && i < list.length() && byId.length() < 3000; i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("_id"), type = item.optString("type");
                if (id.matches("tt[0-9]+") && (type.equals("movie") || type.equals("series"))) byId.put(id, item);
            }
            write(context, byId);
        } catch (Exception ignored) {}
    }

    static synchronized JSONObject items(Context context) {
        try {
            File f = file(context);
            if (!f.isFile() || f.length() > 8 * 1024 * 1024) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static void write(Context context, JSONObject byId) throws Exception {
        File f = file(context), part = new File(f.getPath() + ".part");
        Files.write(part.toPath(), byId.toString().getBytes(StandardCharsets.UTF_8));
        if (!part.renameTo(f)) part.delete();
    }

    static synchronized void clear(Context context) { file(context).delete(); }

    static String now() { return java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(); }

    /** A new library item, as stremio-core creates one. Played-but-not-saved items are removed + temp. */
    static JSONObject item(String imdb, String kind, String title, String poster, boolean saved) throws Exception {
        String stamp = now();
        return new JSONObject().put("_id", imdb).put("name", title == null ? "" : title).put("type", "tv".equals(kind) || "series".equals(kind) ? "series" : "movie")
                .put("poster", poster == null || poster.isEmpty() ? JSONObject.NULL : poster).put("posterShape", "poster")
                .put("removed", !saved).put("temp", !saved).put("_ctime", stamp).put("_mtime", stamp)
                .put("state", new JSONObject().put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                        .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0)
                        .put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL).put("noNotif", false))
                .put("behaviorHints", new JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL)
                        .put("hasScheduledVideos", false));
    }

    /** Progress on an item (stremio-core's player updates): offset, duration, video, watch time; finished advances. */
    static void progress(JSONObject item, String video, long position, long duration, long watchedMs, boolean finished, String nextVideo) throws Exception {
        JSONObject state = item.optJSONObject("state");
        if (state == null) { state = new JSONObject(); item.put("state", state); }
        state.put("lastWatched", now()).put("duration", Math.max(0, duration)).put("video_id", video)
                .put("timeWatched", state.optLong("timeWatched") + Math.max(0, watchedMs)).put("timeOffset", Math.max(0, position));
        if (finished) {
            state.put("timesWatched", state.optInt("timesWatched") + 1).put("flaggedWatched", 1);
            if (nextVideo != null && !nextVideo.isEmpty()) {
                // advance_to_video: the next episode with offset 1 shows in Continue Watching everywhere.
                state.put("video_id", nextVideo).put("overallTimeWatched", state.optLong("overallTimeWatched") + state.optLong("timeWatched"))
                        .put("timeWatched", 0).put("flaggedWatched", 0).put("timeOffset", 1);
            } else state.put("timeOffset", 0);
        }
        item.put("_mtime", now());
    }

    /** Sends one changed item (blocking). */
    static void put(String account, JSONObject item) throws Exception {
        JSONObject body = new JSONObject().put("authKey", account).put("collection", "libraryItem").put("changes", new JSONArray().put(item));
        try { MediaHttp.json(API + "datastorePut", body, 10000); } finally { body.remove("authKey"); }
    }

    interface Change { void apply(JSONObject item) throws Exception; }

    /**
     * Changes the item for imdb (creating it with create when the library lacks it), saves it locally and sends it to
     * the account in the background. Without an account only the local copy changes.
     */
    static void change(Context context, String imdb, java.util.concurrent.Callable<JSONObject> create, Change change) {
        // Not an Activity past its life; a plain or wrapped context (tests) keeps its own files.
        final Context app = context instanceof android.app.Activity ? context.getApplicationContext() : context;
        writes.execute(() -> {
            try {
                JSONObject item;
                synchronized (LibrarySync.class) {
                    JSONObject all = items(app);
                    item = all.optJSONObject(imdb);
                    if (item == null) item = create.call();
                    change.apply(item);
                    all.put(imdb, item);
                    write(app, all);
                }
                String account = TestState.active ? "" : AddonStore.account(app);
                if (!account.isEmpty()) put(account, item);
            } catch (Exception failure) { PlaybackLog.add(app, "Library sync failed: " + failure.getClass().getSimpleName()); }
        });
    }
}
