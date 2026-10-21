package io.freemytv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class SeriesEpisodes {
    static final class Season {
        int number, count;
        String name;
        String label() { return number == 0 ? Ui.tr("Especiais", "Specials") : String.format(Ui.tr("Temporada %d", "Season %d"), number); }
    }
    static final class Episode {
        int season, number, minutes;
        String name, overview, date, still, sourceId;
        boolean future;
        String code() { return "S" + season + " E" + number; }
        String id(String imdb) { return sourceId != null ? sourceId : EpisodeMap.id(imdb, this); }
    }
    static List<Season> seasons(JSONArray input) {
        List<Season> result = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (int i = 0; input != null && i < input.length(); i++) {
            JSONObject entry = input.optJSONObject(i); if (entry == null) continue;
            int number = entry.optInt("season_number", -1), count = entry.optInt("episode_count", 0);
            if (number < 0 || count <= 0 || !seen.add(number)) continue;
            Season season = new Season(); season.number = number; season.count = count; season.name = entry.optString("name", "");
            result.add(season);
        }
        result.sort(Comparator.comparingInt(value -> value.number == 0 ? Integer.MAX_VALUE : value.number));
        return result;
    }
    static int initialSeason(List<Season> seasons, int saved) {
        for (Season season : seasons) if (season.number == saved) return saved;
        return seasons.isEmpty() ? 1 : seasons.get(0).number;
    }
    static List<Episode> episodes(JSONArray input, int season, String today) {
        List<Episode> result = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (int i = 0; input != null && i < input.length(); i++) {
            JSONObject entry = input.optJSONObject(i); if (entry == null) continue;
            int number = entry.optInt("episode_number", 0);
            if (number < 1 || !seen.add(number)) continue;
            Episode episode = new Episode(); episode.season = season; episode.number = number;
            episode.name = entry.optString("name", "").trim();
            if (episode.name.isEmpty()) episode.name = String.format(Ui.tr("Episódio %d", "Episode %d"), number);
            episode.overview = entry.optString("overview", ""); episode.still = entry.optString("still_path", "");
            episode.date = entry.optString("air_date", ""); episode.minutes = Math.max(0, entry.optInt("runtime", 0));
            episode.future = episode.date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && episode.date.compareTo(today) > 0;
            result.add(episode);
        }
        result.sort(Comparator.comparingInt(value -> value.number));
        return result;
    }
    static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date()); }
    static boolean future(String released, String today, long now) {
        try { return java.time.Instant.parse(released).toEpochMilli() > now; }
        catch (RuntimeException invalid) {
            return released != null && released.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && released.compareTo(today) > 0;
        }
    }
}
