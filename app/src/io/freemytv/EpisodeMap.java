package io.freemytv;

import java.util.*;
import org.json.*;

/** Use source numbering only when episode identity has a unique match. */
final class EpisodeMap {
    private static final Map<String, JSONArray> memory = new java.util.concurrent.ConcurrentHashMap<>();
    static void load(String imdb) {
        if (imdb == null || !imdb.matches("tt[0-9]+") || memory.containsKey(imdb)) return;
        try {
            String path = "cinemeta:" + imdb;
            String cached = MetadataCache.read(path, System.currentTimeMillis());
            JSONObject o = cached == null ? MediaHttp.json("https://v3-cinemeta.strem.io/meta/series/" + imdb + ".json", null, 4000) : new JSONObject(cached);
            JSONObject meta = o.optJSONObject("meta"); JSONArray videos = meta == null ? null : meta.optJSONArray("videos");
            if (videos != null) { if (memory.size() >= 4) memory.clear(); memory.put(imdb, videos); if (cached == null) MetadataCache.write(path, o.toString()); }
        } catch (Exception ignored) {}
    }
    static boolean available(String imdb) { JSONArray a = imdb == null ? null : memory.get(imdb); return a != null && a.length() > 0; }
    static java.util.List<Integer> seasons(String imdb) {
        java.util.Set<Integer> numbers = new java.util.TreeSet<>();
        JSONArray videos = memory.get(imdb);
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject o = videos.optJSONObject(i); if (o != null && o.optInt("season", -1) >= 0 && o.optInt("episode") > 0) numbers.add(o.optInt("season"));
        }
        java.util.List<Integer> out = new java.util.ArrayList<>(numbers); if (out.remove(Integer.valueOf(0))) out.add(0); return out;
    }
    static java.util.List<SeriesEpisodes.Episode> episodes(String imdb, int season, java.util.List<SeriesEpisodes.Episode> fallback, String today) {
        return parseVideos(imdb, memory.get(imdb), season, fallback, today);
    }
    static java.util.List<SeriesEpisodes.Episode> parseVideos(String imdb, JSONArray videos, int season, java.util.List<SeriesEpisodes.Episode> fallback, String today) {
        if (videos == null) return fallback;
        java.util.List<SeriesEpisodes.Episode> out = new java.util.ArrayList<>(); java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject o = videos.optJSONObject(i); if (o == null || o.optInt("season", -1) != season) continue;
            int number = o.optInt("episode"); String source = o.optString("id", "");
            if (number <= 0 || !source.equals(imdb + ":" + season + ":" + number) || !seen.add(number)) continue;
            SeriesEpisodes.Episode e = new SeriesEpisodes.Episode(); e.season = season; e.number = number; e.sourceId = source;
            e.name = o.optString("title", o.optString("name", ""));
            if (e.name.isEmpty() || e.name.equals("null")) e.name = o.optString("name", Ui.tr("Episódio ", "Episode ") + number);
            e.overview = o.optString("overview", ""); e.still = o.optString("thumbnail", "");
            if (!MediaHttp.https(e.still)) e.still = "";
            String released = o.optString("released", ""); e.date = released.length() >= 10 ? released.substring(0,10) : "";
            e.future = SeriesEpisodes.future(released, today, System.currentTimeMillis());
            SeriesEpisodes.Episode match = null; int matches = 0;
            for (SeriesEpisodes.Episode t : fallback) if (normalized(t.name).equals(normalized(e.name)) && matchingDate(t.date, e.date)) {
                match = t; matches++;
            }
            if (matches == 1) {
                e.name = match.name; e.overview = match.overview; if (!match.still.isEmpty()) e.still = match.still; e.minutes = match.minutes;
            }
            out.add(e);
        }
        out.sort(java.util.Comparator.comparingInt(e -> e.number)); return out.isEmpty() ? fallback : out;
    }
    static String id(String imdb, SeriesEpisodes.Episode episode) {
        return map(imdb, episode, memory.get(imdb));
    }
    private static String normalized(String name) { return java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    /** Broadcast dates can differ by one day because providers use different time zones. */
    private static boolean matchingDate(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return true;
        try { return Math.abs(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a.substring(0,10)), java.time.LocalDate.parse(b.substring(0,10)))) <= 1; }
        catch (RuntimeException invalid) { return false; }
    }
    static String map(String imdb, SeriesEpisodes.Episode episode, JSONArray videos) {
        String fallback = imdb + ":" + episode.season + ":" + episode.number, match = null;
        String title = normalized(episode.name); if (title.isEmpty() || title.matches("(episode|episodio)[0-9]+")) return fallback;
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i); if (v == null || !title.equals(normalized(v.optString("title", v.optString("name"))))) continue;
            String id = v.optString("id");
            if (!id.matches(java.util.regex.Pattern.quote(imdb) + ":[0-9]+:[0-9]+")) continue;
            String released = v.optString("released", "");
            if (!matchingDate(episode.date, released)) continue;
            if (match != null && !match.equals(id)) return fallback;
            match = id;
        }
        return match == null ? fallback : match;
    }
}
