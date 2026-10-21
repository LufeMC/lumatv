package io.freemytv;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Check a small byte range before committing the video decoder to a source. */
final class SourceProbe {
    static final int UNKNOWN = 0, CHECKING = 1, AVAILABLE = 2, FAILED = 3, UNCERTAIN = 4;
    static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
    // Results of recent checks, so a warmed or prefetched source doesn't need checking again.
    private static final java.util.Map<String, long[]> recent = new java.util.LinkedHashMap<String, long[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, long[]> eldest) { return size() > 48; }
    };
    static int cached(String url) {
        synchronized (recent) {
            long[] saved = recent.get(url);
            if (saved == null) return UNKNOWN;
            long age = android.os.SystemClock.elapsedRealtime() - saved[1];
            // A failure may have been a passing network problem: trust it only briefly.
            return age < (saved[0] == FAILED ? 2 : 10) * 60000L ? (int) saved[0] : UNKNOWN;
        }
    }
    static void forget(String url) { synchronized (recent) { recent.remove(url); } }
    static int check(AddonStreams.Source source) {
        int saved = cached(source.url);
        if (saved == AVAILABLE || saved == FAILED) return saved;
        int result = probe(source);
        if (result == AVAILABLE || result == FAILED) synchronized (recent) { recent.put(source.url, new long[]{result, android.os.SystemClock.elapsedRealtime()}); }
        return result;
    }
    private static int probe(AddonStreams.Source source) {
        int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
        HttpURLConnection connection = null;
        try {
            URL original = new URL(source.url), address = original;
            for (int redirects = 0; redirects <= 3; redirects++) {
                if (!MediaHttp.https(address.toString())) return FAILED;
                connection = (HttpURLConnection) address.openConnection();
                connection.setConnectTimeout(1500); connection.setReadTimeout(1500); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "FreeMyTV/1.0");
                for (java.util.Map.Entry<String, String> header : source.headers.entrySet()) {
                    String key = header.getKey();
                    if (!original.getHost().equalsIgnoreCase(address.getHost()) && (key.equalsIgnoreCase("Authorization") || key.equalsIgnoreCase("Cookie"))) continue;
                    connection.setRequestProperty(key, header.getValue());
                }
                connection.setRequestProperty("Range", "bytes=0-4095"); connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location"); if (location == null) return FAILED;
                    URL next = new URL(address, location); connection.disconnect(); connection = null; address = next; continue;
                }
                return read(connection, source.videoSize);
            }
            return FAILED;
        } catch (Exception ignored) { return UNCERTAIN; }
        finally { if (connection != null) connection.disconnect(); active.decrementAndGet(); }
    }
    static int read(HttpURLConnection connection) {
        return read(connection, 0);
    }
    static int read(HttpURLConnection connection, long expectedSize) {
        try {
            int code = connection.getResponseCode();
            if (code == 416) return UNCERTAIN;
            if (code != 200 && code != 206) return FAILED;
            String type = connection.getContentType(); type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (type.contains("json") || type.contains("html") || type.startsWith("image/")) return FAILED;
            byte[] prefix = new byte[4096]; int size = 0; long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
            try (InputStream input = connection.getInputStream()) {
                while (size < prefix.length) {
                    long left = deadline - android.os.SystemClock.elapsedRealtime();
                    if (Thread.currentThread().isInterrupted() || left <= 0) return UNCERTAIN;
                    connection.setReadTimeout((int) Math.min(1500, left));
                    int count = input.read(prefix, size, prefix.length - size); if (count < 0) break; size += count;
                    if (size >= 512) break;
                }
            }
            if (size == 0) return FAILED;
            String start = new String(prefix, 0, Math.min(size, 512), StandardCharsets.ISO_8859_1).trim().toLowerCase(Locale.ROOT);
            boolean playlist = start.startsWith("#extm3u") || start.contains("<mpd") || type.contains("mpegurl") || type.contains("dash+xml");
            long actualSize = code == 200 ? connection.getContentLengthLong() : -1;
            String range = connection.getHeaderField("Content-Range");
            if (range != null && range.matches("(?i)bytes [0-9]+-[0-9]+/[0-9]+"))
                try { actualSize = Long.parseLong(range.substring(range.lastIndexOf('/') + 1)); } catch (NumberFormatException ignored) {}
            if (!playlist && expectedSize >= 10L * 1024 * 1024 && actualSize > 0 && actualSize <= 1024 * 1024 && actualSize < expectedSize / 10) return FAILED;
            if (type.startsWith("text/") && !type.contains("mpegurl") && !start.startsWith("#extm3u") && !start.contains("<mpd")) return FAILED;
            if (start.startsWith("<html") || start.startsWith("<!doctype html") || start.startsWith("<error") || start.startsWith("{\"") || start.startsWith("torrent download failed")) return FAILED;
            return AVAILABLE;
        } catch (Exception ignored) { return UNCERTAIN; }
    }
}
