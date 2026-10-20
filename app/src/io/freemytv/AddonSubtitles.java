package io.freemytv;

import android.content.Context;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class AddonSubtitles {
    static final class Subtitle {
        String url, language, name, mime;
        /** The subtitle's own label ("SDH") and the add-on that offered it ("OpenSubtitles"). */
        String label = "", addon = "", release = "";
        /** Timed to this exact file: a hash match (the add-on says so), or the same release name. */
        boolean matched, hashMatch;
        /** The add-on's own order, and the rank from it (see rank()). */
        int order, score;
    }
    private static final Map<String, Object[]> recent = new LinkedHashMap<String, Object[]>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) { return size() > 4; }
    };
    private static String key(String id, AddonStreams.Source source) {
        return id + "\n" + (source == null ? "" : source.videoHash + "\n" + source.videoSize);
    }
    /** A list loaded in the last 15 minutes for this video and file (warmed or prefetched), or null. */
    static List<Subtitle> cached(String id, AddonStreams.Source source) {
        synchronized (recent) {
            Object[] saved = recent.get(key(id, source));
            if (saved == null || android.os.SystemClock.elapsedRealtime() - (Long) saved[0] > 15 * 60000L) return null;
            @SuppressWarnings("unchecked") List<Subtitle> list = (List<Subtitle>) saved[1];
            return new ArrayList<>(list);
        }
    }
    static List<Subtitle> load(Context context, String id, AddonStreams.Source source) throws Exception {
        List<Subtitle> saved = cached(id, source);
        if (saved != null) return saved;
        List<Subtitle> result = fetch(context, id, source);
        rank(result, source);
        synchronized (recent) { recent.put(key(id, source), new Object[]{android.os.SystemClock.elapsedRealtime(), new ArrayList<>(result)}); }
        return result;
    }
    private static List<Subtitle> fetch(Context context, String id, AddonStreams.Source source) throws Exception {
        List<Subtitle> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CompletionService<List<Subtitle>> completion = new java.util.concurrent.ExecutorCompletionService<>(pool);
        int submitted = 0;
        try {
        JSONArray addons = AddonStore.load(context);
        String type = id.contains(":") ? "series" : "movie";
        for (int i = 0; i < Math.min(60, addons.length()); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            JSONObject addon = addons.optJSONObject(i);
            if (addon == null) continue;
            JSONObject manifest = addon.optJSONObject("manifest");
            if (manifest == null) continue;
            JSONArray resources = manifest.optJSONArray("resources");
            boolean supports = false;
            if (resources != null) for (int j = 0; j < resources.length(); j++) {
                Object resource = resources.opt(j);
                if ("subtitles".equals(resource) || resource instanceof JSONObject
                        && "subtitles".equals(((JSONObject) resource).optString("name"))) supports = true;
            }
            if (!supports) continue;
            final JSONObject descriptor = addon, info = manifest;
            completion.submit(() -> {
              List<Subtitle> loaded = new ArrayList<>();
              try {
                String endpoint = AddonStreams.endpoint(descriptor.optString("transportUrl", ""), type, id).replace("/stream/", "/subtitles/");
                String extra = "";
                if (source != null && source.videoHash.matches("[a-fA-F0-9]{16}")) extra = "videoHash=" + source.videoHash;
                if (source != null && source.videoSize > 0) extra += (extra.isEmpty() ? "" : "&") + "videoSize=" + source.videoSize;
                if (!extra.isEmpty()) {
                    Uri uri = Uri.parse(endpoint);
                    endpoint = uri.buildUpon().path(uri.getPath().substring(0, uri.getPath().length() - 5) + "/" + extra + ".json").build().toString();
                }
                JSONArray values = MediaHttp.json(endpoint, null, 8000).optJSONArray("subtitles");
                if (values == null) return loaded;
                for (int j = 0; j < Math.min(200, values.length()); j++) {
                    JSONObject value = values.optJSONObject(j);
                    if (value == null) continue;
                    Subtitle subtitle = parse(value, info.optString("name", "Add-on"));
                    if (subtitle != null) { subtitle.order = j; loaded.add(subtitle); }
                }
              } catch (Exception ignored) {}
              return loaded;
            });
            submitted++;
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 16000;
        for (int i = 0; i < submitted; i++) {
            long remaining = deadline - android.os.SystemClock.elapsedRealtime(); if (remaining <= 0) break;
            java.util.concurrent.Future<List<Subtitle>> ready = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ready == null) break;
            for (Subtitle subtitle : ready.get()) if (seen.add(subtitle.url) && result.size() < 200) result.add(subtitle);
        }
        } finally { pool.shutdownNow(); }
        return result;
    }
    static Subtitle parse(JSONObject value, String addon) {
        String url = value.optString("url", "");
        if (!MediaHttp.https(url)) return null;
        String path = Uri.parse(url).getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".zip") || path.endsWith(".gz")) return null;
        Subtitle subtitle = new Subtitle(); subtitle.url = url;
        subtitle.language = value.optString("lang", "und");
        if (!subtitle.language.matches("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*")) subtitle.language = "und";
        subtitle.language = subtitle.language.replace('_', '-');
        subtitle.label = AddonStreams.clean(value.optString("label", ""), 90);
        subtitle.addon = AddonStreams.clean(addon, 60);
        subtitle.name = (subtitle.label.isEmpty() ? language(subtitle.language) : subtitle.label) + " · " + subtitle.addon;
        subtitle.mime = path.endsWith(".vtt") ? "text/vtt" : path.endsWith(".ass") || path.endsWith(".ssa")
                ? "text/x-ssa" : "application/x-subrip";
        // Some add-ons say when a subtitle was found by the file's hash.
        subtitle.matched = subtitle.hashMatch = value.optBoolean("hashMatch") || value.optBoolean("moviehashMatch") || value.optBoolean("matchedByHash")
                || "hash".equalsIgnoreCase(value.optString("match")) || "moviehash".equalsIgnoreCase(value.optString("matched_by"));
        subtitle.release = (value.optString("release", "") + " " + value.optString("filename", "") + " " + subtitle.label + " " + path).toLowerCase(Locale.ROOT);
        return subtitle;
    }

    /**
     * Subtitles made for this exact file first (hash match, then the same release name), keeping the add-ons' own order
     * otherwise. Labels like "SDH" or "Forced" sort after plain ones.
     */
    static void rank(List<Subtitle> subtitles, AddonStreams.Source source) {
        Set<String> tokens = tokens(source == null ? "" : source.filename + " " + source.name + " " + source.detail);
        for (Subtitle s : subtitles) {
            int shared = 0;
            for (String t : tokens(s.release == null ? "" : s.release)) if (tokens.contains(t)) shared++;
            boolean sameRelease = shared >= 3;
            s.matched = s.hashMatch || sameRelease;
            String label = s.label.toLowerCase(Locale.ROOT);
            s.score = -s.order + (s.hashMatch ? 200000 : 0) + (sameRelease ? 100000 : 0) + shared * 1000
                    - (label.contains("forced") || label.contains("sdh") || label.contains("hi") && label.length() <= 3 ? 500 : 0);
        }
        subtitles.sort((a, b) -> Integer.compare(b.score, a.score));
    }
    private static final Set<String> COMMON = new HashSet<>(java.util.Arrays.asList(
            "the", "and", "srt", "vtt", "ass", "eng", "english", "por", "pt", "br", "subs", "sub", "subtitles", "www", "com", "org", "mkv", "mp4", "avi", "x264", "x265", "h264", "h265", "hevc", "aac", "ac3", "dts", "ddp5", "dd5", "2ch", "6ch"));
    /** Release words ("web-dl", "ntb", "1080p"), without episode tags or common words. */
    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() < 2 || COMMON.contains(word) || word.matches("s[0-9]+e[0-9]+|[0-9]{1,2}|[0-9]{4}")) continue;
            out.add(word);
        }
        return out;
    }

    /** The language code's base ("pt-BR" → "pt", "por" → "pt", "eng" → "en"). */
    static String base(String code) {
        if (code == null || code.isEmpty()) return "";
        String c = code.toLowerCase(Locale.ROOT);
        int dash = c.indexOf('-'); if (dash > 0) c = c.substring(0, dash);
        switch (c) {
            case "eng": return "en";
            case "por": case "pob": case "pb": return "pt";
            case "spa": case "esp": return "es";
            case "fre": case "fra": return "fr";
            case "ger": case "deu": return "de";
            case "ita": return "it";
            case "jpn": return "ja";
            default:
                if (c.length() == 3) try { return new Locale(c).getLanguage(); } catch (Exception ignored) {}
                return c;
        }
    }
    static boolean sameLanguage(String a, String b) {
        String x = base(a), y = base(b);
        return !x.isEmpty() && !x.equals("und") && x.equals(y);
    }
    static String language(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return Ui.tr("Idioma desconhecido", "Unknown language");
        java.util.Locale locale = java.util.Locale.forLanguageTag(code.replace("pob", "pt-BR"));
        String name = locale.getDisplayName(locale);
        if (name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }
}
