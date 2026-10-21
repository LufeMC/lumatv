package io.freemytv;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read the public Stremio stream protocol without opening Stremio. */
final class AddonStreams {
    static final class Addon {
        String address, name;
        JSONObject manifest;
    }
    static final class Source {
        String url, name, detail, addon;
        int quality, probeState;
        String videoHash = "", bingeGroup = "", filename = "", videoId = "", codec = "";
        /** Why this TV can't decode it (CodecSupport), or null. */
        String unsupported;
        /** The file names another episode, or two episodes at once. */
        boolean otherEpisode;
        long videoSize;
        final Map<String, String> headers = new LinkedHashMap<>();
        String qualityLabel() { return quality == 0 ? Ui.tr("Qualidade desconhecida", "Unknown quality") : quality + "p"; }
        String label() { return qualityLabel() + " · " + addon + " · " + name; }
        /** "4K", "1080p", "SD". */
        String shortQuality() { return quality >= 2160 ? "4K" : quality > 0 && quality < 720 ? "SD" : quality > 0 ? quality + "p" : "–"; }
        /** "HEVC · 1.1 GB" for the source sheet. */
        String meta() {
            String size = size(videoSize);
            return codec.isEmpty() ? size : size.isEmpty() ? codec : codec + " · " + size;
        }
        /** The same file across sessions (debrid links change): video hash, file name, or a digest of the label. */
        String release() {
            if (videoHash != null && !videoHash.isEmpty()) return "h:" + videoHash;
            if (filename != null && !filename.isEmpty()) return "f:" + filename;
            return "n:" + MediaCache.digest(addon + "\n" + name + "\n" + detail).substring(0, 24);
        }
        Source copy() {
            Source s = new Source(); s.url = url; s.name = name; s.detail = detail; s.addon = addon; s.quality = quality;
            s.videoHash = videoHash; s.bingeGroup = bingeGroup; s.filename = filename; s.videoId = videoId; s.codec = codec;
            s.unsupported = unsupported; s.otherEpisode = otherEpisode; s.videoSize = videoSize; s.headers.putAll(headers);
            return s;
        }
    }
    static final class Result {
        final List<Source> sources = new ArrayList<>();
        int unsupported;
    }
    static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1000L * 1000 * 1000) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        return Math.max(1, Math.round(bytes / 1e6)) + " MB";
    }
    static List<Addon> addons(JSONArray descriptors, String type, String id) {
        List<Addon> result = new ArrayList<>();
        if (descriptors == null) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(60, descriptors.length()); i++) {
            JSONObject item = descriptors.optJSONObject(i);
            if (item == null) continue;
            JSONObject manifest = item.optJSONObject("manifest");
            String address = item.optString("transportUrl", "");
            String path = Uri.parse(address).getPath();
            if (manifest == null || !MediaHttp.https(address) || path == null || !path.endsWith("/manifest.json")
                    || !seen.add(address) || !supports(manifest, type, id)) continue;
            Addon addon = new Addon(); addon.address = address; addon.manifest = manifest;
            addon.name = clean(manifest.optString("name", "Add-on"), 60);
            result.add(addon);
        }
        return result;
    }
    static boolean supports(JSONObject manifest, String type, String id) {
        if (!contains(manifest.optJSONArray("types"), type)) return false;
        JSONArray resources = manifest.optJSONArray("resources");
        if (resources == null) return false;
        for (int i = 0; i < resources.length(); i++) {
            Object value = resources.opt(i);
            if ("stream".equals(value)) return prefixes(manifest.optJSONArray("idPrefixes"), id);
            if (value instanceof JSONObject) {
                JSONObject resource = (JSONObject) value;
                if ("stream".equals(resource.optString("name"))
                        && (resource.optJSONArray("types") == null || contains(resource.optJSONArray("types"), type))
                        && prefixes(resource.optJSONArray("idPrefixes"), id)) return true;
            }
        }
        return false;
    }
    private static boolean contains(JSONArray values, String expected) {
        if (values != null) for (int i = 0; i < values.length(); i++) if (expected.equals(values.optString(i))) return true;
        return false;
    }
    private static boolean prefixes(JSONArray values, String id) {
        if (values == null) return true;
        for (int i = 0; i < values.length(); i++) if (id.startsWith(values.optString(i))) return true;
        return false;
    }
    static String endpoint(String manifest, String type, String id) {
        if (!MediaHttp.https(manifest) || !("movie".equals(type) || "series".equals(type))
                || !id.matches("tt[0-9]+(?::[0-9]+:[0-9]+)?")) throw new IllegalArgumentException();
        Uri address = Uri.parse(manifest);
        String path = address.getPath();
        if (path == null || !path.endsWith("/manifest.json")) throw new IllegalArgumentException();
        return address.buildUpon().path(path.substring(0, path.length() - "manifest.json".length())
                + "stream/" + type + "/" + id + ".json").build().toString();
    }

    // Answers fetched ahead of time (PlaybackPrefetch) wait in memory for the start that needs them, used once, so
    // a warmed or prefetched video starts without asking again while "Try again" still asks afresh.
    private static final long FRESH_MS = 15 * 60000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<JSONArray>> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Object[]> responses = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) { return size() > 40; }
    };
    private static final Map<String, List<Source>> discovered = new LinkedHashMap<String, List<Source>>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Source>> eldest) { return size() > 3; }
    };
    static Result fetch(Addon addon, String type, String id) throws Exception {
        String key = addon.address + "\n" + type + "\n" + id;
        JSONArray streams = null;
        synchronized (responses) {
            Object[] saved = responses.remove(key);
            if (saved != null && android.os.SystemClock.elapsedRealtime() - (Long) saved[0] < FRESH_MS) streams = (JSONArray) saved[1];
        }
        if (streams == null) {
            streams = request(addon, type, id);
            synchronized (responses) { responses.remove(key); }
        }
        Result result = parse(streams, addon.name, id);
        remember(id, result.sources);
        return result;
    }
    /** Asks the add-on now and keeps the answer for the next fetch() of this video (prefetching). */
    static Result prefetch(Addon addon, String type, String id) throws Exception {
        JSONArray streams = request(addon, type, id);
        Result result = parse(streams, addon.name, id);
        remember(id, result.sources);
        return result;
    }
    private static JSONArray request(Addon addon, String type, String id) throws Exception {
        String key = addon.address + "\n" + type + "\n" + id;
        return sharedRequest(key, () -> {
            JSONArray loaded = MediaHttp.json(endpoint(addon.address, type, id), null, 12000).optJSONArray("streams");
            JSONArray streams = loaded == null ? new JSONArray() : loaded;
            synchronized (responses) { responses.put(key, new Object[]{android.os.SystemClock.elapsedRealtime(), streams}); }
            return streams;
        });
    }
    static JSONArray sharedRequest(String key, java.util.concurrent.Callable<JSONArray> load) throws Exception {
        java.util.concurrent.FutureTask<JSONArray> task = new java.util.concurrent.FutureTask<>(load);
        java.util.concurrent.FutureTask<JSONArray> running = inFlight.putIfAbsent(key, task);
        boolean owner = running == null;
        if (owner) { running = task; task.run(); }
        try { return running.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Exception("network"); }
        catch (java.util.concurrent.ExecutionException error) { throw new Exception("network"); }
        finally { if (owner) inFlight.remove(key, task); }
    }
    /** True when the add-on's answer for this video is already in memory (warmed or prefetched). */
    static boolean cached(Addon addon, String type, String id) {
        synchronized (responses) {
            Object[] saved = responses.get(addon.address + "\n" + type + "\n" + id);
            return saved != null && android.os.SystemClock.elapsedRealtime() - (Long) saved[0] < FRESH_MS;
        }
    }
    /** Drops what was fetched for a video (a prefetch the viewer didn't use). */
    static void forget(String id) {
        synchronized (responses) { responses.keySet().removeIf(key -> key.endsWith("\n" + id)); }
        synchronized (discovered) { discovered.remove(id); }
    }
    private static void remember(String id, List<Source> sources) {
        if (id == null || id.isEmpty()) return;
        synchronized (discovered) {
            List<Source> all = discovered.get(id);
            if (all == null) { all = new ArrayList<>(); discovered.put(id, all); }
            Set<String> seen = new HashSet<>(); for (Source s : all) seen.add(s.url);
            for (Source s : sources) if (all.size() < 400 && seen.add(s.url)) all.add(s.copy());
        }
    }
    /** Every source the add-ons returned for this video recently (copies), for the source sheet. */
    static List<Source> discovered(String id) {
        List<Source> out = new ArrayList<>();
        synchronized (discovered) { List<Source> all = discovered.get(id); if (all != null) for (Source s : all) out.add(s.copy()); }
        return out;
    }

    static Result parse(JSONArray streams, String addon) { return parse(streams, addon, ""); }
    static Result parse(JSONArray streams, String addon, String id) {
        Result result = new Result();
        if (streams == null) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(300, streams.length()); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null) continue;
            String url = stream.optString("url", "");
            if (!MediaHttp.https(url)) { result.unsupported++; continue; }
            if (!seen.add(url)) continue;
            Source source = new Source(); source.url = url; source.addon = addon;
            source.name = clean(stream.optString("name", addon), 90);
            source.detail = clean(stream.optString("description", stream.optString("title", "")), 240);
            JSONObject hints = stream.optJSONObject("behaviorHints");
            if (hints != null) {
                source.videoHash = hints.optString("videoHash", ""); source.videoSize = hints.optLong("videoSize", 0);
                source.bingeGroup = clean(hints.optString("bingeGroup", ""), 200);
            }
            String filename = hints == null ? "" : clean(hints.optString("filename", ""), 240);
            source.filename = filename; source.videoId = id == null ? "" : id;
            String text = source.name + " " + source.detail + " " + filename;
            source.quality = quality(text);
            source.codec = CodecSupport.codec(text);
            source.unsupported = CodecSupport.unsupported(text, source.quality);
            source.otherEpisode = otherEpisode(filename.isEmpty() ? source.name + " " + source.detail : filename, id);
            JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
            JSONObject headers = proxy == null ? null : proxy.optJSONObject("request");
            if (headers != null) {
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext() && source.headers.size() < 20) {
                    String key = keys.next(), value = headers.optString(key, "");
                    String lower = key.toLowerCase(Locale.ROOT);
                    if (key.matches("[A-Za-z0-9-]{1,80}") && !value.contains("\r") && !value.contains("\n")
                            && value.length() <= 8192 && !lower.equals("host") && !lower.equals("content-length")
                            && !lower.equals("connection") && !lower.equals("accept-encoding")) source.headers.put(key, value);
                }
            }
            result.sources.add(source);
        }
        return result;
    }

    private static final Pattern EPISODE_TAG = Pattern.compile("(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?:[ ._-]?(?:e|-e|-)(\\d{1,3}))?(?![0-9])");
    private static final Pattern CROSS_TAG = Pattern.compile("(?<![0-9a-z])(\\d{1,2})x(\\d{2,3})(?:-(\\d{2,3}))?(?![0-9])");
    /** The text names an episode other than id's ("tt1:2:3"), or two episodes in one file. No tag: false. */
    static boolean otherEpisode(String text, String id) {
        if (text == null || id == null || !id.matches("tt[0-9]+:[0-9]+:[0-9]+")) return false;
        String[] parts = id.split(":");
        int season = Integer.parseInt(parts[1]), episode = Integer.parseInt(parts[2]);
        String t = text.toLowerCase(Locale.ROOT);
        Matcher m = EPISODE_TAG.matcher(t);
        if (!m.find()) { m = CROSS_TAG.matcher(t); if (!m.find()) return false; }
        int s = Integer.parseInt(m.group(1)), e = Integer.parseInt(m.group(2));
        if (m.group(3) != null && Integer.parseInt(m.group(3)) != e) return true;
        return s != season || e != episode;
    }
    static int quality(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        if (Pattern.compile("(?<![0-9])(2160p?|4k|uhd)(?![a-z0-9])").matcher(text).find()) return 2160;
        for (int quality : new int[]{1080, 720, 480, 360})
            if (Pattern.compile("(?<![0-9])" + quality + "p?(?![0-9])").matcher(text).find()) return quality;
        return 0;
    }
    static List<Source> select(List<Source> sources, int quality) {
        List<Source> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Source source : sources) if ((quality == -1 || quality == source.quality) && seen.add(source.url)) result.add(source);
        if (quality == -1) Collections.sort(result, (a, b) -> Integer.compare(b.quality, a.quality));
        return result;
    }
    static String clean(String value, int limit) {
        String text = value.replaceAll("https?://\\S+", "[link]").replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
