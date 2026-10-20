package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Search public title metadata with the user's existing TMDB key. */
final class TitleSearch {
    static final class Title {
        int id;
        String kind, name, year, overview, poster, backdrop = "";
        double popularity;
        int votes;
        boolean series() { return "tv".equals(kind); }
        String label() {
            return name + (year.isEmpty() ? "" : " (" + year + ")") + " · "
                    + (series() ? Ui.tr("Série", "Series") : Ui.tr("Filme", "Movie"));
        }
        String action() { return series() ? Ui.tr("Ver episódios no Stremio", "Open episodes in Stremio")
                : Ui.tr("Reproduzir no Stremio", "Play in Stremio"); }
    }

    static String normalized(String value) {
        return java.text.Normalizer.normalize(value.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    static double rank(Title t, String query) {
        String typed = normalized(query), name = normalized(t.name); double match = 0;
        if (name.equals(typed)) match = 2;
        else if (name.startsWith(typed)) match = 1.5;
        else {
            for (String word : t.name.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                if (!word.isEmpty() && normalized(word).startsWith(typed)) { match = 1; break; }
            if (match == 0 && name.contains(typed)) match = .5;
        }
        return match + .6 * Math.log10(1 + Math.max(0,t.popularity)) + .6 * Math.log10(1 + Math.max(0,t.votes));
    }

    private static final java.util.LinkedHashMap<String, String> responses = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> fetched = new java.util.HashMap<>();
    private TitleSearch() {}

    static List<Title> search(String query) throws Exception {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) throw new IllegalArgumentException("query");
        return parse(get("/search/multi?include_adult=false&page=1&language=" + (Ui.en ? "en-US" : "pt-BR")
                + "&query=" + URLEncoder.encode(cleaned, "UTF-8")).optJSONArray("results"));
    }

    /**
     * Shows and movies searched separately (two lists of 20, so neither crowds out the other and no people take
     * places), merged without repeats. One failing list still returns the other.
     */
    static List<Title> searchBoth(String query) throws Exception {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) throw new IllegalArgumentException("query");
        String q = "?include_adult=false&page=1&language=" + (Ui.en ? "en-US" : "pt-BR") + "&query=" + URLEncoder.encode(cleaned, "UTF-8");
        java.util.concurrent.Future<JSONObject> shows = BOTH.submit(() -> get("/search/tv" + q));
        java.util.concurrent.Future<JSONObject> movies = BOTH.submit(() -> get("/search/movie" + q));
        List<Title> result = new ArrayList<>();
        Exception failure = null;
        for (Object[] part : new Object[][] {{shows, "tv"}, {movies, "movie"}}) {
            try {
                JSONArray list = ((java.util.concurrent.Future<JSONObject>) part[0]).get(12, java.util.concurrent.TimeUnit.SECONDS).optJSONArray("results");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null) o.put("media_type", part[1]);
                }
                result.addAll(parse(list));
            } catch (java.util.concurrent.ExecutionException e) {
                failure = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            } catch (java.util.concurrent.TimeoutException e) { ((java.util.concurrent.Future<?>) part[0]).cancel(true); failure=e;
            } catch (InterruptedException e) { shows.cancel(true);movies.cancel(true);Thread.currentThread().interrupt();throw e; }
        }
        if (result.isEmpty() && failure != null) throw failure;
        return result;
    }

    private static final java.util.concurrent.ExecutorService BOTH = java.util.concurrent.Executors.newFixedThreadPool(2);

    static List<Title> parse(JSONArray data) {
        List<Title> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; data != null && i < data.length() && result.size() < 40; i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null || o.optBoolean("adult")) continue;
            String kind = o.optString("media_type");
            int id = o.optInt("id");
            if (!(kind.equals("movie") || kind.equals("tv")) || id <= 0 || !seen.add(kind + ":" + id)) continue;
            String name = o.optString(kind.equals("movie") ? "title" : "name").trim();
            if (name.isEmpty()) continue;
            Title title = new Title();
            title.id = id;
            title.kind = kind;
            title.name = name;
            String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
            title.year = date.matches("[0-9]{4}-.*") ? date.substring(0, 4) : "";
            title.overview = o.optString("overview");
            title.poster = o.optString("poster_path", "");
            title.backdrop = o.optString("backdrop_path", "");
            title.popularity = o.optDouble("popularity", 0);
            title.votes = o.optInt("vote_count", 0);
            if (title.backdrop.equals("null")) title.backdrop = "";
            result.add(title);
        }
        return result;
    }

    static String imdbId(Title title) throws Exception {
        if (title.id <= 0 || !("movie".equals(title.kind) || "tv".equals(title.kind)))
            throw new IllegalArgumentException("title");
        String value = get("/" + title.kind + "/" + title.id + "/external_ids").optString("imdb_id");
        return value.matches("tt[0-9]+") ? value : null;
    }

    private static void remember(String path, String body) {
        synchronized (responses) {
            responses.put(path, body); fetched.put(path, System.currentTimeMillis());
            while (responses.size() > 24) { String oldest = responses.keySet().iterator().next(); responses.remove(oldest); fetched.remove(oldest); }
        }
    }
    static JSONObject get(String path) throws Exception {
        synchronized (responses) {
            Long at = fetched.get(path); long age = at == null ? Long.MAX_VALUE : System.currentTimeMillis() - at;
            if (responses.containsKey(path) && age >= 0 && age < MetadataCache.lifetime(path)) return new JSONObject(responses.get(path));
        }
        String key = Setup.tmdbKey();
        if (key.isEmpty()) throw new IOException("missing_key");
        String saved = MetadataCache.read(path, System.currentTimeMillis());
        if (saved != null) { remember(path, saved); return new JSONObject(saved); }
        boolean bearer = key.startsWith("eyJ");
        String address = "https://api.themoviedb.org/3" + path;
        if (!bearer) address += (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("invalid_key");
            if (code != 200) throw new IOException("network");
            try (InputStream input = c.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("response_size");
                    output.write(block, 0, count);
                }
                String body = new String(output.toByteArray(), StandardCharsets.UTF_8); JSONObject result = new JSONObject(body);
                remember(path, body); MetadataCache.write(path, body);
                return result;
            }
        } finally { c.disconnect(); }
    }
}
