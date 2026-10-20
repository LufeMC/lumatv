package io.freemytv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Read public episode markers (IntroDB: intro, recap, and outro = credits) without account credentials or source
 * addresses. https://introdb.app/docs/api
 */
final class IntroMarkers {
    static final class Marker {
        final long start, end;
        final double confidence;
        Marker(long start, long end, double confidence) { this.start = start; this.end = end; this.confidence = confidence; }
        boolean fits(long duration) { return duration > end + 10000 && end < duration / 2; }
    }
    /** Everything IntroDB knows about one episode. Any part can be missing. */
    static final class Segments {
        Marker intro, recap;
        /** When the credits start (ms), or -1. Trust it only through {@link #credits(long)}. */
        long creditsStart = -1;
        /** The credits start, if it falls in the last 20% of this runtime (and not in the last 5 s); else -1. */
        long credits(long duration) {
            return creditsStart > 0 && duration > 5 * 60000L && creditsStart >= duration * 0.8 && creditsStart < duration - 5000 ? creditsStart : -1;
        }
    }
    static Marker parse(JSONObject response, String imdb, int season, int episode) {
        Segments segments = parseSegments(response, imdb, season, episode);
        return segments == null ? null : segments.intro;
    }
    static Segments parseSegments(JSONObject response, String imdb, int season, int episode) {
        if (!imdb.equals(response.optString("imdb_id")) || response.optInt("season", -1) != season
                || response.optInt("episode", -1) != episode || !"tv".equals(response.optString("media_type"))) return null;
        Segments segments = new Segments();
        if (!response.optBoolean("no_intro")) segments.intro = segment(response.optJSONObject("intro"), 1200000, 300000);
        // A recap opens the episode and runs from a few seconds to a few minutes.
        if (!response.optBoolean("no_recap")) segments.recap = segment(response.optJSONObject("recap"), 600000, 300000);
        JSONObject outro = response.optBoolean("no_outro") ? null : response.optJSONObject("outro");
        if (outro != null) {
            double start = outro.optDouble("start_ms", Double.NaN), confidence = outro.optDouble("confidence", 0);
            if (Double.isFinite(start) && start > 0 && Double.isFinite(confidence) && confidence >= 0.8 && confidence <= 1) segments.creditsStart = (long) start;
        }
        return segments;
    }
    private static Marker segment(JSONObject value, long latestStart, long longest) {
        if (value == null) return null;
        double start = value.optDouble("start_ms", Double.NaN), end = value.optDouble("end_ms", Double.NaN);
        double confidence = value.optDouble("confidence", 0);
        if (!Double.isFinite(start) || !Double.isFinite(end) || !Double.isFinite(confidence)
                || start < 0 || start > latestStart || end - start < 5000 || end - start > longest
                || confidence < 0.8 || confidence > 1) return null;
        return new Marker((long) start, (long) end, confidence);
    }
    static Marker load(Context context, String video) throws Exception {
        Segments segments = loadSegments(context, video);
        return segments == null ? null : segments.intro;
    }
    static Segments loadSegments(Context context, String video) throws Exception {
        if (video == null || !video.matches("tt[0-9]+:[0-9]+:[0-9]+")) return null;
        String[] parts = video.split(":"); int season = Integer.parseInt(parts[1]), episode = Integer.parseInt(parts[2]);
        if (episode < 1) return null;
        MetadataCache.init(context);
        String key = "introdb/" + video;
        String saved = MetadataCache.read(key, System.currentTimeMillis());
        if (saved != null) return parseSegments(new JSONObject(saved), parts[0], season, episode);
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
                Segments segments = parseSegments(new JSONObject(body), parts[0], season, episode);
                MetadataCache.write(key, body); return segments;
            }
        } finally { connection.disconnect(); }
    }
}
