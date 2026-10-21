package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Combine local progress, the authorized Stremio library, and shared TV programs: one card per show, the service the
 * viewer pays for first. A finished episode turns the show's card into "Up next" for the next aired episode. Also
 * per-episode watched history, removed cards (which don't come back from the library or Watch Next), and the
 * Stremio watchlist.
 */
final class ContinueWatching {
    static final String PREFS = "continue-watching";
    static final int LIMIT = 40;
    static final class Entry {
        String id = "", video = "", imdb = "", title = "", subtitle = "", kind = "movie", art = "";
        String provider = "Stremio", pkg = "", intent = "";
        String episodeName = "";
        int tmdb, season, episode;
        boolean completed, upNext;
        long position, duration, updated;
        boolean external() { return !pkg.isEmpty(); }
        String identity() { return external() ? pkg + ":" + id : imdb.isEmpty() ? video : imdb; }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("video", video).put("imdb", imdb).put("title", title).put("subtitle", subtitle)
                    .put("kind", kind).put("art", art).put("provider", provider).put("pkg", pkg).put("intent", intent)
                    .put("tmdb", tmdb).put("season", season).put("episode", episode).put("position", position)
                    .put("duration", duration).put("updated", updated).put("completed", completed)
                    .put("upNext", upNext).put("episodeName", episodeName);
        }
        static Entry parse(JSONObject value) {
            Entry e = new Entry(); e.id = value.optString("id"); e.video = value.optString("video"); e.imdb = value.optString("imdb");
            e.title = value.optString("title"); e.subtitle = value.optString("subtitle"); e.kind = value.optString("kind", "movie");
            e.art = value.optString("art"); e.provider = value.optString("provider", "Stremio"); e.pkg = value.optString("pkg");
            if (e.pkg.isEmpty() && e.provider.equals("Free My TV")) e.provider = "Stremio";
            e.intent = value.optString("intent"); e.tmdb = value.optInt("tmdb"); e.season = value.optInt("season"); e.episode = value.optInt("episode");
            e.completed = value.optBoolean("completed"); e.position = Math.max(0, value.optLong("position")); e.duration = Math.max(0, value.optLong("duration")); e.updated = value.optLong("updated");
            e.upNext = value.optBoolean("upNext"); e.episodeName = value.optString("episodeName", "");
            return e;
        }
        String code() { return episode > 0 ? Ui.tr("T", "S") + season + " E" + episode : ""; }
        String detail() {
            if (upNext && episode > 0) return Ui.tr("A seguir", "Up next") + " · " + code();
            String code = episode > 0 ? code() + "  ·  " : "";
            long left = duration > position ? (duration - position + 59999) / 60000 : 0;
            return code + provider + (left > 0 ? "  ·  " + left + Ui.tr(" min restantes", " min left") : "");
        }
    }

    /** At 95%, or with 2 minutes or less left in anything longer than 10 minutes, a video counts as watched. */
    static boolean finished(long position, long duration) {
        if (duration <= 0) return false;
        return position >= duration * 0.95 || duration > 10 * 60000L && duration - position <= 120000;
    }

    static synchronized void record(Context context, Entry entry, long position, long duration, boolean ended) {
        if (entry == null || entry.video.isEmpty() || position < 5000 && !ended) return;
        try {
            List<Entry> saved = stored(context, "local");
            saved.removeIf(item -> item.identity().equals(entry.identity()));
            entry.position = Math.max(0, position); entry.duration = Math.max(0, duration); entry.updated = System.currentTimeMillis();
            entry.completed = ended || finished(position, duration);
            entry.upNext = false;
            saved.add(0, entry);
            save(context, "local", saved);
            if (entry.completed && entry.episode > 0) setWatched(context, entry.imdb, entry.season, entry.episode, true);
        } catch (Exception ignored) {}
    }

    /**
     * The episode (or movie) is done: count it as watched and, for a series with a next aired episode, replace the
     * show's card with "Up next". Without a next episode the card goes away.
     */
    static synchronized Entry finished(Context context, Entry entry, SeriesEpisodes.Episode next) {
        if (entry == null || entry.video.isEmpty()) return null;
        try {
            if (entry.episode > 0) setWatched(context, entry.imdb, entry.season, entry.episode, true);
            List<Entry> saved = stored(context, "local");
            saved.removeIf(item -> item.identity().equals(entry.identity()));
            Entry card = entry;
            if (next != null && "tv".equals(entry.kind) && entry.imdb.matches("tt[0-9]+")) {
                card = Entry.parse(entry.json());
                card.video = next.id(entry.imdb); card.id = "local:" + card.video; card.season = next.season; card.episode = next.number;
                card.episodeName = next.name == null ? "" : next.name; card.subtitle = card.episodeName;
                card.position = 0; card.duration = next.minutes * 60000L; card.upNext = true; card.completed = false;
            } else { card.completed = true; card.upNext = false; card.position = 0; }
            card.updated = System.currentTimeMillis();
            saved.add(0, card);
            save(context, "local", saved);
            return card;
        } catch (Exception ignored) { return null; }
    }

    // --- Watched history: "season:episode" keys per show

    static Set<String> watched(Context context, String imdb) {
        Set<String> out = new HashSet<>();
        if (imdb == null || imdb.isEmpty()) return out;
        try {
            JSONArray list = new JSONArray(context.getSharedPreferences(PREFS, 0).getString("watched:" + imdb, "[]"));
            for (int i = 0; i < list.length(); i++) out.add(list.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    static synchronized void markWatched(Context context, Entry entry, boolean watched) {
        if (entry == null) return;
        setWatched(context, entry.imdb, entry.season, entry.episode, watched);
        if (!watched) return;
        // Marking the card's own episode as watched finishes the card.
        try {
            List<Entry> saved = stored(context, "local"); boolean changed = false;
            for (Entry e : saved) if (e.identity().equals(entry.identity()) && e.video.equals(entry.video) && !e.upNext) {
                e.completed = true; e.updated = System.currentTimeMillis(); changed = true;
            }
            if (changed) save(context, "local", saved);
        } catch (Exception ignored) {}
    }

    private static synchronized void setWatched(Context context, String imdb, int season, int episode, boolean watched) {
        if (imdb == null || imdb.isEmpty()) return;
        Set<String> set = watched(context, imdb);
        String key = season + ":" + episode;
        if (watched ? !set.add(key) : !set.remove(key)) return;
        context.getSharedPreferences(PREFS, 0).edit().putString("watched:" + imdb, new JSONArray(set).toString()).apply();
    }

    // --- Removed cards

    /** Removes a card. Older progress for the same show from the Stremio library or Watch Next stays hidden. */
    static synchronized void remove(Context context, Entry entry) {
        if (entry == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            JSONObject stones = new JSONObject(prefs.getString("removed", "{}"));
            long now = System.currentTimeMillis();
            stones.put("id:" + entry.identity(), now);
            if (!entry.imdb.isEmpty()) stones.put("show:" + entry.imdb, now);
            stones.put("title:" + showKey(entry), now);
            // Keep the newest 200.
            if (stones.length() > 200) {
                List<String> keys = new ArrayList<>(); java.util.Iterator<String> it = stones.keys(); while (it.hasNext()) keys.add(it.next());
                keys.sort((a, b) -> Long.compare(stones.optLong(a), stones.optLong(b)));
                for (int i = 0; i < keys.size() - 200; i++) stones.remove(keys.get(i));
            }
            prefs.edit().putString("removed", stones.toString()).apply();
            List<Entry> saved = stored(context, "local");
            saved.removeIf(item -> item.identity().equals(entry.identity()) || sameShow(item, entry));
            save(context, "local", saved);
        } catch (Exception ignored) {}
    }

    private static List<Entry> visible(Context context, List<Entry> entries) {
        JSONObject stones;
        try { stones = new JSONObject(context.getSharedPreferences(PREFS, 0).getString("removed", "{}")); } catch (Exception e) { stones = new JSONObject(); }
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            long removed = Math.max(stones.optLong("id:" + e.identity()), e.imdb.isEmpty() ? stones.optLong("title:" + showKey(e)) : stones.optLong("show:" + e.imdb));
            if (removed == 0 || e.updated > removed) out.add(e);
        }
        return out;
    }

    /** One card per show across services: the title without punctuation or case. */
    static String showKey(Entry e) {
        String t = e.title == null ? "" : e.title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        return t.isEmpty() ? e.identity() : t;
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
    static List<Entry> cached(Context context) {
        return visible(context, merge(stored(context, "local"), stored(context, "stremio"), services(context)));
    }
    static List<Entry> history(Context context) {
        List<Entry> entries = new ArrayList<>(stored(context, "local"));
        entries.addAll(stored(context, "stremio"));
        entries.sort((a, b) -> Long.compare(b.updated, a.updated));
        return visible(context, entries);
    }
    static List<Entry> refresh(Context context) {
        migrate(context);
        PlaybackMemory.init(context);
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
        long age = System.currentTimeMillis() - Math.max(preferences.getLong("cloud-at", 0), preferences.getLong("cloud-attempt-at", 0));
        if (age < 0 || age > 180000) try {
            preferences.edit().putLong("cloud-attempt-at", System.currentTimeMillis()).apply();
            String account = AddonStore.account(context);
            if (account.isEmpty()) LibrarySync.clear(context);
            else {
                JSONObject body = new JSONObject().put("authKey", account).put("collection", "libraryItem");
                try {
                    String stamp = LibrarySync.fingerprint(MediaHttp.json(LibrarySync.API + "datastoreMeta", body, 10000).optJSONArray("result"));
                    if (!stamp.equals(preferences.getString("cloud-meta", ""))) {
                        body.put("ids", new JSONArray()).put("all", true);
                        JSONArray list = MediaHttp.json(LibrarySync.API + "datastoreGet", body, 10000).optJSONArray("result");
                        if (list == null) throw new Exception("library");
                        if (!account.equals(AddonStore.account(context))) throw new Exception("account_changed");
                        save(context, "stremio", stremio(list));
                        LibrarySync.store(context, list);
                    }
                    if (account.equals(AddonStore.account(context))) preferences.edit().putString("cloud-meta", stamp).putLong("cloud-at", System.currentTimeMillis()).apply();
                } finally { body.remove("authKey"); }
            }
        } catch (Exception ignored) {}
        List<Entry> all = new ArrayList<>(stored(context, "local"));
        all.addAll(stored(context, "stremio"));
        return visible(context, merge(all, television(context), services(context)));
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
            // Stremio moves a finished series on to the next episode with an offset of 1 ms.
            if (e.kind.equals("tv") && e.position < 5000) { e.upNext = true; e.position = 0; }
            if (!e.title.isEmpty()) result.add(e);
        }
        return merge(result, Collections.emptyList());
    }
    static List<Entry> merge(List<Entry> first, List<Entry> second) { return merge(first, second, null); }

    /**
     * Newest record per identity (a finished one hides older progress), then one card per show: among a show's
     * cards from different services, a service switched off in "My services" loses, then the most recent wins.
     */
    static List<Entry> merge(List<Entry> first, List<Entry> second, Set<String> servicesOff) {
        List<Entry> all = new ArrayList<>(first); all.addAll(second); all.sort((a, b) -> Long.compare(b.updated, a.updated));
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry e : all) if (!e.title.isEmpty() && !unique.containsKey(e.identity())) unique.put(e.identity(), e);
        List<Entry> shows = new ArrayList<>();
        for (Entry entry : unique.values()) {
            if (entry.completed) continue;
            int at = -1;
            for (int i = 0; i < shows.size() && at < 0; i++) if (sameShow(shows.get(i), entry)) at = i;
            if (at < 0) { shows.add(entry); continue; }
            Entry kept = shows.get(at);
            boolean keptOff = servicesOff != null && kept.external() && servicesOff.contains(kept.pkg);
            boolean entryOff = servicesOff != null && entry.external() && servicesOff.contains(entry.pkg);
            if (keptOff && !entryOff) shows.set(at, entry);
        }
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : shows) if (visible.size() < LIMIT) visible.add(entry);
        visible.sort((a, b) -> Long.compare(b.updated, a.updated));
        return visible;
    }
    /** Same show: the same imdb id when both have one; otherwise (a service's card) the same title. */
    static boolean sameShow(Entry a, Entry b) {
        if (!a.imdb.isEmpty() && !b.imdb.isEmpty()) return a.imdb.equals(b.imdb);
        if (!a.kind.equals(b.kind)) return false;
        String x = showKey(a);
        return !x.isEmpty() && x.equals(showKey(b));
    }
    private static Set<String> services(Context context) {
        try { return Prefs.servicesOff(context); } catch (Exception ignored) { return null; }
    }

    // --- Stremio library write-back and the watchlist

    /** Saves playback progress to the Stremio library (in the background). watchedMs: time actually played. */
    static void syncProgress(Context context, Entry entry, long position, long duration, long watchedMs, boolean finished, String nextVideo) {
        if (entry == null || !entry.imdb.matches("tt[0-9]+") || entry.video.isEmpty()) return;
        final String video = entry.video, imdb = entry.imdb, kind = entry.kind, title = entry.title, art = entry.art;
        LibrarySync.change(context, imdb, () -> LibrarySync.item(imdb, kind, title, art, false),
                item -> LibrarySync.progress(item, video, position, duration, watchedMs, finished, nextVideo));
    }

    static boolean inWatchlist(Context context, String imdb) {
        if (imdb == null || imdb.isEmpty()) return false;
        JSONObject item = LibrarySync.items(context).optJSONObject(imdb);
        return item != null && !item.optBoolean("removed") && !item.optBoolean("temp");
    }

    /** Adds or removes a title from the Stremio library (kind: "movie" or "tv"). Call off the UI thread. */
    static void setWatchlist(Context context, String imdb, String kind, String title, String art, boolean on) {
        if (imdb == null || !imdb.matches("tt[0-9]+")) return;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        LibrarySync.change(context, imdb, () -> LibrarySync.item(imdb, kind, title, art, on), item -> {
            item.put("removed", !on).put("temp", false).put("_mtime", LibrarySync.now());
            if (title != null && !title.isEmpty() && item.optString("name").isEmpty()) item.put("name", title);
            if (on && art != null && !art.isEmpty() && item.isNull("poster")) item.put("poster", art);
            done.countDown();
        });
        // The local copy is written before this returns, so inWatchlist() agrees right away.
        try { if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) done.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    /** Titles saved to the library but not started, newest first. */
    static List<Entry> watchlist(Context context) {
        List<Entry> out = new ArrayList<>();
        JSONObject items = LibrarySync.items(context);
        java.util.Iterator<String> keys = items.keys();
        while (keys.hasNext()) {
            JSONObject item = items.optJSONObject(keys.next());
            if (item == null || item.optBoolean("removed") || item.optBoolean("temp")) continue;
            JSONObject state = item.optJSONObject("state");
            if (state != null && (state.optLong("timeOffset") > 0 || state.optInt("timesWatched") > 0)) continue;
            Entry e = new Entry(); e.imdb = item.optString("_id"); e.video = e.imdb; e.id = "watchlist:" + e.imdb;
            e.kind = "series".equals(item.optString("type")) ? "tv" : "movie"; e.title = item.optString("name");
            e.art = item.isNull("poster") ? "" : item.optString("poster", ""); e.provider = "Stremio";
            e.updated = date(item.optString("_mtime"));
            if (!e.title.isEmpty()) out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
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
                e.kind = watchNextKind(cursor.getInt(11));
                e.position = Math.max(0, cursor.getLong(6)); e.duration = Math.max(0, cursor.getLong(7)); e.updated = cursor.getLong(8);
                if (e.title != null && e.intent != null && externalIntent(context, e) != null) result.add(e);
            }
        } catch (Exception ignored) {}
        return result;
    }
    static String watchNextKind(int type) {
        return type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE
                || type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SEASON
                || type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SERIES ? "tv" : "movie";
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
                .putExtra("resume_position", e.upNext ? 0 : e.position).putExtra("resume_duration", e.duration).putExtra("resume_updated", e.updated).putExtra("auto_play", true);
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
