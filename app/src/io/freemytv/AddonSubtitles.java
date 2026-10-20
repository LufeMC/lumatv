package io.freemytv;

import android.content.Context;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class AddonSubtitles {
    static final class Subtitle {
        String url, language, name, mime;
    }
    static List<Subtitle> load(Context context, String id, AddonStreams.Source source) throws Exception {
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
                    if (subtitle != null) loaded.add(subtitle);
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
        subtitle.name = AddonStreams.clean(value.optString("label", language(subtitle.language)), 90) + " · " + AddonStreams.clean(addon, 60);
        subtitle.mime = path.endsWith(".vtt") ? "text/vtt" : path.endsWith(".ass") || path.endsWith(".ssa")
                ? "text/x-ssa" : "application/x-subrip";
        return subtitle;
    }
    static String language(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return Ui.tr("Idioma desconhecido", "Unknown language");
        java.util.Locale locale = java.util.Locale.forLanguageTag(code);
        String name = locale.getDisplayName(java.util.Locale.getDefault());
        return name.isEmpty() ? code : name;
    }
}
