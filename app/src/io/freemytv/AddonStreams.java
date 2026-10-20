package io.freemytv;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        String videoHash = "";
        long videoSize;
        final Map<String, String> headers = new LinkedHashMap<>();
        String qualityLabel() { return quality == 0 ? Ui.tr("Qualidade desconhecida", "Unknown quality") : quality + "p"; }
        String label() { return qualityLabel() + " · " + addon + " · " + name; }
    }
    static final class Result {
        final List<Source> sources = new ArrayList<>();
        int unsupported;
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
    static Result fetch(Addon addon, String type, String id) throws Exception {
        return parse(MediaHttp.json(endpoint(addon.address, type, id), null, 12000).optJSONArray("streams"), addon.name);
    }
    static Result parse(JSONArray streams, String addon) {
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
            if (hints != null) { source.videoHash = hints.optString("videoHash", ""); source.videoSize = hints.optLong("videoSize", 0); }
            String filename = hints == null ? "" : hints.optString("filename", "");
            source.quality = quality(source.name + " " + source.detail + " " + filename);
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
