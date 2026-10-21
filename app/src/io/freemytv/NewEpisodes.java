package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Check recent episode releases without delaying Home or interrupting playback. */
final class NewEpisodes {
    static final String PREFS = "new-episodes";
    static final long WINDOW = 14L * 24 * 60 * 60000;
    private static final long REFRESH = 2 * 60 * 60000L;
    private static final java.util.concurrent.ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean();
    static final class Notice {
        ContinueWatching.Entry entry;
        long released;
        boolean waiting;
        String key() { return entry.video; }
    }

    static List<ContinueWatching.Entry> followed(Context context, List<ContinueWatching.Entry> recent) {
        LinkedHashMap<String, ContinueWatching.Entry> shows = new LinkedHashMap<>();
        for (ContinueWatching.Entry e : recent)
            if ("tv".equals(e.kind) && e.imdb.matches("tt[0-9]+")) shows.put(e.imdb, e);
        for (ContinueWatching.Entry e : ContinueWatching.history(context))
            if ("tv".equals(e.kind) && e.imdb.matches("tt[0-9]+") && !shows.containsKey(e.imdb)) shows.put(e.imdb, e);
        JSONObject library = LibrarySync.items(context);
        List<JSONObject> saved = new ArrayList<>();
        Iterator<String> keys = library.keys();
        while (keys.hasNext()) {
            JSONObject item = library.optJSONObject(keys.next());
            if (item != null) saved.add(item);
        }
        saved.sort((a, b) -> b.optString("_mtime").compareTo(a.optString("_mtime")));
        for (JSONObject item : saved) {
            String id = item.optString("_id"); JSONObject state = item.optJSONObject("state");
            if (!"series".equals(item.optString("type")) || !id.matches("tt[0-9]+")) continue;
            if (item.optBoolean("removed") && !item.optBoolean("temp")) continue;
            ContinueWatching.Entry e = new ContinueWatching.Entry(); e.imdb = id; e.kind = "tv"; e.title = item.optString("name");
            e.art = item.isNull("poster") ? "" : item.optString("poster");
            if (state != null) {
                e.video = state.optString("video_id"); e.position = state.optLong("timeOffset");
                e.duration = state.optLong("duration");
                e.completed = state.optInt("flaggedWatched") > 0 || ContinueWatching.finished(e.position, e.duration);
                try { e.updated = java.time.Instant.parse(state.optString("lastWatched")).toEpochMilli(); } catch (RuntimeException ignored) {}
                String[] parts = e.video.split(":");
                if (parts.length == 3) try { e.season = Integer.parseInt(parts[1]); e.episode = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) {}
            }
            ContinueWatching.Entry existing = shows.get(id);
            if (existing == null && (item.optBoolean("removed") || item.optBoolean("temp")) && (e.episode <= 0 || e.updated <= 0)) continue;
            if (!e.title.isEmpty() && (existing == null || e.episode > 0 && e.updated > existing.updated)) {
                if (existing != null) { e.tmdb = existing.tmdb; if (e.art.isEmpty()) e.art = existing.art; }
                shows.put(id, e);
            }
        }
        List<ContinueWatching.Entry> ordered = new ArrayList<>(shows.values());
        ordered.sort((a, b) -> Long.compare(b.updated, a.updated));
        return ordered.subList(0, Math.min(40, ordered.size()));
    }

    static JSONArray releases(String imdb, JSONArray videos, long now) {
        List<JSONObject> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i); if (v == null) continue;
            int season = v.optInt("season"), episode = v.optInt("episode");
            String id = v.optString("id"); long released;
            try { released = java.time.Instant.parse(v.optString("released")).toEpochMilli(); }
            catch (RuntimeException invalid) { continue; }
            if (season < 1 || episode < 1 || !id.equals(imdb + ":" + season + ":" + episode)
                    || released > now || now - released > WINDOW || !seen.add(id)) continue;
            try { out.add(new JSONObject().put("id", id).put("season", season).put("episode", episode).put("released", released)); }
            catch (JSONException ignored) {}
        }
        out.sort((a, b) -> Long.compare(a.optLong("released"), b.optLong("released")));
        JSONArray result = new JSONArray(); for (JSONObject v : out) if (result.length() < 40) result.put(v);
        return result;
    }

    static List<Notice> cached(Context context, List<ContinueWatching.Entry> shows, long now) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        Set<String> dismissed = prefs.getStringSet("dismissed", Collections.emptySet());
        JSONObject library = LibrarySync.items(context);
        List<Notice> result = new ArrayList<>();
        for (ContinueWatching.Entry show : shows) try {
            JSONObject item = library.optJSONObject(show.imdb), state = item == null ? null : item.optJSONObject("state");
            if (item != null && (item.optBoolean("noNotif") || state != null && state.optBoolean("noNotif"))) continue;
            Set<String> watched = ContinueWatching.watched(context, show.imdb);
            JSONArray episodes = new JSONArray(prefs.getString("episodes:" + show.imdb, "[]"));
            JSONObject schedule = new JSONObject(prefs.getString("timeline:" + show.imdb, "{}"));
            JSONObject scheduled = schedule.optJSONObject("next");
            if (scheduled != null && scheduled.optLong("released") <= now) episodes.put(scheduled);
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject v = episodes.getJSONObject(i); long released = v.optLong("released");
                int season = v.optInt("season"), episode = v.optInt("episode"); String id = v.optString("id");
                if (released > now || now - released > WINDOW || dismissed.contains(id) || watched.contains(season + ":" + episode)) continue;
                boolean earlier = show.episode > 0 && (season < show.season || season == show.season && episode < show.episode);
                boolean started = season == show.season && episode == show.episode && (show.position > 0 || show.completed);
                if (earlier || started) continue;
                Notice notice = new Notice(); notice.entry = ContinueWatching.Entry.parse(show.json()); notice.released = released;
                notice.entry.video = id; notice.entry.season = season; notice.entry.episode = episode;
                notice.entry.position = notice.entry.duration = 0; notice.entry.completed = notice.entry.upNext = false;
                notice.entry.pkg = notice.entry.intent = ""; notice.entry.provider = "";
                result.add(notice); break;
            }
        } catch (Exception ignored) {}
        result.sort((a, b) -> Long.compare(b.released, a.released));
        return result.subList(0, Math.min(6, result.size()));
    }

    static JSONObject timeline(String imdb, JSONObject metadata, long now) throws JSONException {
        return timeline(imdb, metadata, now, null);
    }
    static JSONObject timeline(String imdb, JSONObject metadata, long now, ContinueWatching.Entry current) throws JSONException {
        JSONObject latest = null, next = null, following = null; JSONArray videos = metadata.optJSONArray("videos");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i); if (v == null) continue;
            int season = v.optInt("season"), episode = v.optInt("episode"); long released;
            if (season < 1 || episode < 1 || !v.optString("id").equals(imdb + ":" + season + ":" + episode)) continue;
            try { released = java.time.Instant.parse(v.optString("released")).toEpochMilli(); }
            catch (RuntimeException invalid) { continue; }
            JSONObject e = new JSONObject().put("id", v.optString("id")).put("season", season).put("episode", episode).put("released", released);
            if (released <= now && (latest == null || order(e, latest) > 0)) latest = e;
            if (released > now && (next == null || order(e, next) < 0)) next = e;
            if (current != null && (season > current.season || season == current.season && episode > current.episode)
                    && (following == null || order(e, following) < 0)) following = e;
        }
        String status = metadata.optString("status");
        return new JSONObject().put("latest", latest).put("next", next).put("following", following).put("anchor", current == null ? "" : current.video)
                .put("ended", status.equalsIgnoreCase("Ended") || status.equalsIgnoreCase("Canceled") || status.equalsIgnoreCase("Cancelled"));
    }
    private static int order(JSONObject a, JSONObject b) {
        int season = Integer.compare(a.optInt("season"), b.optInt("season"));
        return season == 0 ? Integer.compare(a.optInt("episode"), b.optInt("episode")) : season;
    }
    static List<Notice> retained(Context context, List<ContinueWatching.Entry> shows, long now) {
        List<Notice> result = new ArrayList<>(); SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        for (ContinueWatching.Entry show : shows) try {
            JSONObject schedule = new JSONObject(prefs.getString("timeline:" + show.imdb, "{}"));
            JSONObject latest = schedule.optJSONObject("latest"), next = schedule.optJSONObject("next"), following = schedule.optJSONObject("following");
            if (following != null && (following.optInt("season") < show.season
                    || following.optInt("season") == show.season && following.optInt("episode") <= show.episode)) following = null;
            if (following != null) next = following;
            if (schedule.optBoolean("ended") && next == null || latest == null || show.episode <= 0) continue;
            int season = latest.optInt("season"), episode = latest.optInt("episode");
            if (following == null && (show.season < season || show.season == season && show.episode < episode)) continue;
            boolean watched = show.completed || ContinueWatching.finished(show.position, show.duration)
                    || ContinueWatching.watched(context, show.imdb).contains(show.season + ":" + show.episode);
            if (!watched && !show.upNext) continue;
            Notice notice = new Notice(); notice.waiting = next == null || next.optLong("released") > now; notice.entry = ContinueWatching.Entry.parse(show.json());
            if (next != null) {
                notice.entry.video = next.optString("id"); notice.entry.season = next.optInt("season"); notice.entry.episode = next.optInt("episode");
                notice.released = next.optLong("released");
            }
            notice.entry.position = notice.entry.duration = 0;
            notice.entry.pkg = notice.entry.intent = ""; notice.entry.provider = "";
            result.add(notice);
        } catch (Exception ignored) {}
        return result.subList(0, Math.min(6, result.size()));
    }

    static void dismiss(Context context, Notice notice) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        Set<String> saved = new HashSet<>(prefs.getStringSet("dismissed", Collections.emptySet()));
        if (saved.size() >= 200) saved.clear();
        saved.add(notice.key()); prefs.edit().putStringSet("dismissed", saved).apply();
    }

    static void refresh(Context context, List<ContinueWatching.Entry> shows, java.util.function.BooleanSupplier visible, Runnable changed) {
        if (TestState.active || !busy.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, 0); int requests = 0;
                for (ContinueWatching.Entry show : shows) {
                    if (!visible.getAsBoolean() || Thread.currentThread().isInterrupted() || requests >= 2) break;
                    long now = System.currentTimeMillis(), age = now - prefs.getLong("checked:" + show.imdb, 0);
                    if (now < prefs.getLong("retry:" + show.imdb, 0)) continue;
                    JSONObject saved;
                    try { saved = new JSONObject(prefs.getString("timeline:" + show.imdb, "{}")); } catch (JSONException invalid) { saved = new JSONObject(); }
                    if (age >= 0 && age < REFRESH && saved.optString("anchor").equals(show.video)) continue;
                    requests++;
                    try {
                        JSONObject meta = MediaHttp.json("https://v3-cinemeta.strem.io/meta/series/" + show.imdb + ".json", null, 3500).optJSONObject("meta");
                        if (meta == null) throw new Exception("metadata");
                        JSONArray list = releases(show.imdb, meta.optJSONArray("videos"), now);
                        prefs.edit().putString("episodes:" + show.imdb, list.toString())
                                .putString("timeline:" + show.imdb, timeline(show.imdb, meta, now, show).toString())
                                .putLong("checked:" + show.imdb, now).remove("retry:" + show.imdb).apply();
                        changed.run();
                    } catch (Exception ignored) {
                        prefs.edit().putLong("retry:" + show.imdb, now + 10 * 60000L).apply();
                    }
                }
                Set<String> keep = new HashSet<>(); for (ContinueWatching.Entry show : shows) keep.add(show.imdb);
                SharedPreferences.Editor edit = prefs.edit();
                for (String key : prefs.getAll().keySet()) if ((key.startsWith("checked:") || key.startsWith("episodes:") || key.startsWith("timeline:") || key.startsWith("retry:")) && !keep.contains(key.substring(key.indexOf(':') + 1))) edit.remove(key);
                edit.apply();
            } finally { busy.set(false); }
        });
    }
}
