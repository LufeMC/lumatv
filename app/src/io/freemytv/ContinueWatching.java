package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combine local progress, the authorized Stremio library, and shared TV programs. */
final class ContinueWatching {
    static final String PREFS = "continue-watching";
    static final int LIMIT = 40;
    static final class Entry {
        String id = "", video = "", imdb = "", title = "", subtitle = "", kind = "movie", art = "";
        String provider = "Stremio", pkg = "", intent = "";
        int tmdb, season, episode;
        boolean completed;
        long position, duration, updated;
        boolean external() { return !pkg.isEmpty(); }
        String identity() { return external() ? pkg + ":" + id : imdb.isEmpty() ? video : imdb; }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("video", video).put("imdb", imdb).put("title", title).put("subtitle", subtitle)
                    .put("kind", kind).put("art", art).put("provider", provider).put("pkg", pkg).put("intent", intent)
                    .put("tmdb", tmdb).put("season", season).put("episode", episode).put("position", position)
                    .put("duration", duration).put("updated", updated).put("completed", completed);
        }
        static Entry parse(JSONObject value) {
            Entry e = new Entry(); e.id = value.optString("id"); e.video = value.optString("video"); e.imdb = value.optString("imdb");
            e.title = value.optString("title"); e.subtitle = value.optString("subtitle"); e.kind = value.optString("kind", "movie");
            e.art = value.optString("art"); e.provider = value.optString("provider", "Stremio"); e.pkg = value.optString("pkg");
            if (e.pkg.isEmpty() && e.provider.equals("Free My TV")) e.provider = "Stremio";
            e.intent = value.optString("intent"); e.tmdb = value.optInt("tmdb"); e.season = value.optInt("season"); e.episode = value.optInt("episode");
            e.completed = value.optBoolean("completed"); e.position = Math.max(0, value.optLong("position")); e.duration = Math.max(0, value.optLong("duration")); e.updated = value.optLong("updated"); return e;
        }
        String detail() {
            String code = episode > 0 ? "S" + season + " E" + episode + "  ·  " : "";
            long left = duration > position ? (duration - position + 59999) / 60000 : 0;
            return code + provider + (left > 0 ? "  ·  " + left + Ui.tr(" min restantes", " min left") : "");
        }
    }
    static synchronized void record(Context context, Entry entry, long position, long duration, boolean ended) {
        if (entry == null || entry.video.isEmpty() || position < 5000 && !ended) return;
        try {
            List<Entry> saved = stored(context, "local");
            saved.removeIf(item -> item.identity().equals(entry.identity()));
            entry.position = Math.max(0, position); entry.duration = Math.max(0, duration); entry.updated = System.currentTimeMillis();
            entry.completed = ended || duration > 0 && position >= duration - 1000;
            saved.add(0, entry);
            save(context, "local", saved);
        } catch (Exception ignored) {}
    }
    private static List<Entry> stored(Context context, String key) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(context.getSharedPreferences(PREFS, 0).getString(key, "[]"));
            for (int i = 0; i < list.length() && result.size() < LIMIT; i++) {
                Entry e = Entry.parse(list.getJSONObject(i)); if (!e.title.isEmpty()) result.add(e);
            }
        } catch (Exception ignored) {}
        return result;
    }
    private static void save(Context context, String key, List<Entry> entries) throws Exception {
        JSONArray data = new JSONArray(); for (Entry e : entries) { if (data.length() == LIMIT) break; data.put(e.json()); }
        context.getSharedPreferences(PREFS, 0).edit().putString(key, data.toString()).apply();
    }
    static List<Entry> cached(Context context) { return merge(stored(context, "local"), stored(context, "stremio")); }
    static List<Entry> refresh(Context context) {
        migrate(context);
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
        long age = System.currentTimeMillis() - Math.max(preferences.getLong("cloud-at", 0), preferences.getLong("cloud-attempt-at", 0));
        if (age < 0 || age > 180000) try {
            preferences.edit().putLong("cloud-attempt-at", System.currentTimeMillis()).apply();
            String account = AddonStore.account(context);
            if (!account.isEmpty()) {
                JSONObject body = new JSONObject().put("authKey", account).put("collection", "libraryItem");
                try {
                    String stamp = LibrarySync.fingerprint(MediaHttp.json("https://api.strem.io/api/datastoreMeta", body, 10000).optJSONArray("result"));
                    if (!stamp.equals(preferences.getString("cloud-meta", ""))) {
                        body.put("ids", new JSONArray()).put("all", true);
                        JSONArray list = MediaHttp.json("https://api.strem.io/api/datastoreGet", body, 10000).optJSONArray("result");
                        if (list == null) throw new Exception("library");
                        if (!account.equals(AddonStore.account(context))) throw new Exception("account_changed");
                        save(context, "stremio", stremio(list));
                    }
                    if (account.equals(AddonStore.account(context))) preferences.edit().putString("cloud-meta", stamp).putLong("cloud-at", System.currentTimeMillis()).apply();
                } finally { body.remove("authKey"); }
            }
        } catch (Exception ignored) {}
        return merge(cached(context), television(context));
    }
    static List<Entry> stremio(JSONArray list) {
        List<Entry> result = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject value = list.optJSONObject(i); if (value == null) continue;
            JSONObject state = value.optJSONObject("state"); String kind = value.optString("type"); String imdb = value.optString("_id");
            if (state == null || !(kind.equals("movie") || kind.equals("series")) || !imdb.matches("tt[0-9]+")
                    || value.optBoolean("removed") && !value.optBoolean("temp") || state.optLong("timeOffset") <= 0) continue;
            Entry e = new Entry(); e.imdb = imdb; e.kind = kind.equals("series") ? "tv" : "movie"; e.title = value.optString("name");
            e.video = state.optString("video_id", ""); if (e.video.isEmpty() || e.video.equals("null")) e.video = imdb; if (!e.video.matches("tt[0-9]+(?::[0-9]+:[0-9]+)?")) continue;
            if (e.kind.equals("tv") && !e.video.contains(":")) continue;
            e.id = "stremio:" + e.video; e.position = state.optLong("timeOffset"); e.duration = state.optLong("duration");
            e.updated = date(state.optString("lastWatched")); if (e.updated == 0) e.updated = date(value.optString("_mtime")); e.provider = "Stremio";
            e.art = value.optString("poster");
            if (e.video.contains(":")) { String[] parts = e.video.split(":"); try { e.season = Integer.parseInt(parts[1]); e.episode = Integer.parseInt(parts[2]); } catch (Exception ignored) { continue; } }
            if (!e.title.isEmpty()) result.add(e);
        }
        return merge(result, Collections.emptyList());
    }
    static List<Entry> merge(List<Entry> first, List<Entry> second) {
        List<Entry> all = new ArrayList<>(first); all.addAll(second); all.sort((a, b) -> Long.compare(b.updated, a.updated));
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry e : all) if (!e.title.isEmpty() && !unique.containsKey(e.identity())) unique.put(e.identity(), e);
        List<Entry> visible = new ArrayList<>(); for (Entry entry : unique.values()) if (!entry.completed && visible.size() < LIMIT) visible.add(entry);
        return visible;
    }
    static String provider(String pkg) {
        if (pkg == null) return "";
        switch (pkg) {
            case "com.netflix.ninja": return "Netflix";
            case "com.amazon.amazonvideo.livingroom": return "Prime Video";
            case "com.stremio.one": return "Stremio";
            case "com.disney.disneyplus": return "Disney+";
            case "com.wbd.stream": return "HBO Max";
            case "com.apple.atve.androidtv.appletv": return "Apple TV";
            case "com.hulu.livingroomplus": return "Hulu";
            case "com.cbs.ott": return "Paramount+";
            case "com.peacocktv.peacockandroid": return "Peacock";
            case "com.plexapp.android": return "Plex";
            case "com.google.android.youtube.tv": return "YouTube";
            default: return "";
        }
    }
    static List<Entry> television(Context context) {
        List<Entry> result = new ArrayList<>();
        String[] projection = {"_id", "package_name", "title", "episode_title", "poster_art_uri", "intent_uri", "last_playback_position_millis",
                "duration_millis", "last_engagement_time_utc_millis", "watch_next_type", "browsable", "type"};
        try (Cursor cursor = context.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"), projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext() && result.size() < 200) {
                String pkg = cursor.getString(1); int type = cursor.getInt(9);
                if (provider(pkg).isEmpty() || type > 1 || cursor.getInt(10) == 0) continue;
                Entry e = new Entry(); e.pkg = pkg; e.provider = provider(pkg); e.id = "tv:" + cursor.getLong(0); e.title = cursor.getString(2);
                e.subtitle = cursor.isNull(3) ? "" : cursor.getString(3); e.art = cursor.isNull(4) ? "" : cursor.getString(4); e.intent = cursor.getString(5);
                e.kind = cursor.getInt(11) == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE ? "tv" : "movie";
                e.position = Math.max(0, cursor.getLong(6)); e.duration = Math.max(0, cursor.getLong(7)); e.updated = cursor.getLong(8);
                if (e.title != null && e.intent != null && externalIntent(context, e) != null) result.add(e);
            }
        } catch (Exception ignored) {}
        return result;
    }
    static Intent externalIntent(Context context, Entry entry) {
        if (provider(entry.pkg).isEmpty() || entry.intent == null || entry.intent.isEmpty()) return null;
        try {
            Intent intent = Intent.parseUri(entry.intent, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null); intent.setPackage(entry.pkg); intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.getComponent() != null && !entry.pkg.equals(intent.getComponent().getPackageName())) return null;
            android.content.pm.ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, 0);
            return resolved != null && resolved.activityInfo.exported && entry.pkg.equals(resolved.activityInfo.packageName) ? intent : null;
        } catch (Exception ignored) { return null; }
    }
    static Intent internalIntent(Context context, Entry e) {
        return new Intent(context, SourcesActivity.class).putExtra("title_id", e.tmdb).putExtra("title_kind", e.kind)
                .putExtra("title_name", e.title).putExtra("imdb", e.imdb).putExtra("resume_video", e.video)
                .putExtra("resume_position", e.position).putExtra("resume_duration", e.duration).putExtra("resume_updated", e.updated).putExtra("auto_play", true);
    }
    static void resolve(Entry e) throws Exception {
        if (e.tmdb > 0 || !e.imdb.matches("tt[0-9]+")) return;
        JSONObject found = TitleSearch.get("/find/" + e.imdb + "?external_source=imdb_id&language=" + (Ui.en ? "en-US" : "pt-BR"));
        JSONArray list = found.optJSONArray(e.kind.equals("tv") ? "tv_results" : "movie_results");
        if (list == null || list.length() == 0) throw new Exception("metadata");
        JSONObject title = list.getJSONObject(0); e.tmdb = title.getInt("id");
        String path = title.optString("backdrop_path", ""); if (!path.isEmpty()) e.art = "https://image.tmdb.org/t/p/w780" + path;
    }
    private static void migrate(Context context) {
        if (context.getSharedPreferences(PREFS, 0).getBoolean("migrated", false)) return;
        List<Entry> entries = stored(context, "local"); int count = 0; boolean failed = false;
        for (Map.Entry<String, ?> item : context.getSharedPreferences("playback-progress", 0).getAll().entrySet()) {
            if (!(item.getValue() instanceof Long) || (Long) item.getValue() < 5000 || !item.getKey().matches("tt[1-9][0-9]*(?::[0-9]+:[0-9]+)?") || count++ >= 12) continue;
            try {
                Entry e = new Entry(); e.video = item.getKey(); e.imdb = e.video.split(":")[0]; e.kind = e.video.contains(":") ? "tv" : "movie";
                e.position = (Long) item.getValue(); e.updated = System.currentTimeMillis(); e.id = "local:" + e.video;
                JSONObject found = TitleSearch.get("/find/" + e.imdb + "?external_source=imdb_id");
                JSONArray titles = found.optJSONArray(e.kind.equals("tv") ? "tv_results" : "movie_results");
                if (titles == null || titles.length() == 0) continue;
                JSONObject title = titles.getJSONObject(0); e.tmdb = title.getInt("id"); e.title = title.optString(e.kind.equals("tv") ? "name" : "title");
                e.art = "https://image.tmdb.org/t/p/w780" + title.optString("backdrop_path");
                if (e.video.contains(":")) { String[] parts = e.video.split(":"); e.season = Integer.parseInt(parts[1]); e.episode = Integer.parseInt(parts[2]); }
                boolean present = false; for (Entry existing : entries) if (existing.identity().equals(e.identity())) present = true;
                if (!present) entries.add(e);
            } catch (Exception ignored) { failed = true; }
        }
        try { save(context, "local", entries); if (!failed) context.getSharedPreferences(PREFS, 0).edit().putBoolean("migrated", true).apply(); } catch (Exception ignored) {}
    }
    private static long date(String value) { try { return java.time.Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { return 0; } }
}
