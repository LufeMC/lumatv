package io.freemytv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Read public episode markers without account credentials or source addresses. */
final class IntroMarkers {
    static final class Marker {
        final long start, end;
        final double confidence;
        Marker(long start, long end, double confidence) { this.start = start; this.end = end; this.confidence = confidence; }
        boolean fits(long duration) { return duration > end + 10000 && end < duration / 2; }
    }
    static Marker parse(JSONObject response, String imdb, int season, int episode) {
        if (!imdb.equals(response.optString("imdb_id")) || response.optInt("season", -1) != season
                || response.optInt("episode", -1) != episode || !"tv".equals(response.optString("media_type"))
                || response.optBoolean("no_intro")) return null;
        JSONObject intro = response.optJSONObject("intro"); if (intro == null) return null;
        double start = intro.optDouble("start_ms", Double.NaN), end = intro.optDouble("end_ms", Double.NaN);
        double confidence = intro.optDouble("confidence", 0);
        if (!Double.isFinite(start) || !Double.isFinite(end) || !Double.isFinite(confidence)
                || start < 0 || start > 1200000 || end - start < 5000 || end - start > 300000
                || confidence < 0.8 || confidence > 1) return null;
        return new Marker((long) start, (long) end, confidence);
    }
    static Marker load(Context context, String video) throws Exception {
        if (video == null || !video.matches("tt[0-9]+:[0-9]+:[0-9]+")) return null;
        String[] parts = video.split(":"); int season = Integer.parseInt(parts[1]), episode = Integer.parseInt(parts[2]);
        if (episode < 1) return null;
        MetadataCache.init(context);
        String key = "introdb/" + video;
        String saved = MetadataCache.read(key, System.currentTimeMillis());
        if (saved != null) return parse(new JSONObject(saved), parts[0], season, episode);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.introdb.app/segments?imdb_id=" + parts[0]
                + "&season=" + season + "&episode=" + episode).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                MetadataCache.write(key, new JSONObject().put("imdb_id", parts[0]).put("season", season).put("episode", episode)
                        .put("media_type", "tv").toString()); return null;
            }
            if (code != 200) return null;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[4096]; int count;
                while ((count = input.read(block)) != -1) {
                    if (output.size() + count > 65536) return null;
                    output.write(block, 0, count);
                }
                String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
                JSONObject response = new JSONObject(body);
                Marker marker = parse(response, parts[0], season, episode);
                MetadataCache.write(key, body); return marker;
            }
        } finally { connection.disconnect(); }
    }
}
