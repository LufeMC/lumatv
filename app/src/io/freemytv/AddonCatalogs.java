package io.freemytv;

import android.content.Context;
import android.net.Uri;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Search installed add-on catalogs without delaying public title results. */
final class AddonCatalogs {
    private static final ExecutorService WORK = Executors.newFixedThreadPool(3);
    static List<String> endpoints(JSONArray descriptors, String query) {
        List<String> out = new ArrayList<>();
        if (query == null || query.trim().length() < 2 || query.length() > 200) return out;
        for (int i = 0; descriptors != null && i < descriptors.length() && out.size() < 8; i++) {
            JSONObject d = descriptors.optJSONObject(i), manifest = d == null ? null : d.optJSONObject("manifest");
            String address = d == null ? "" : d.optString("transportUrl");
            if (manifest == null || !MediaHttp.https(address) || !address.endsWith("/manifest.json")) continue;
            JSONArray catalogs = manifest.optJSONArray("catalogs");
            for (int j = 0; catalogs != null && j < catalogs.length() && out.size() < 8; j++) {
                JSONObject c = catalogs.optJSONObject(j); if (c == null) continue;
                String type = c.optString("type"), id = c.optString("id");
                if (!(type.equals("movie") || type.equals("series")) || id.isEmpty()) continue;
                boolean search = false, unavailable = false;
                JSONArray extra = c.optJSONArray("extra");
                for (int k = 0; extra != null && k < extra.length(); k++) {
                    JSONObject e = extra.optJSONObject(k); if (e == null) continue;
                    if (e.optString("name").equals("search")) search = true;
                    else if (e.optBoolean("isRequired")) unavailable = true;
                }
                JSONArray supported = c.optJSONArray("extraSupported");
                for (int k = 0; supported != null && k < supported.length(); k++) if (supported.optString(k).equals("search")) search = true;
                if (search && !unavailable) out.add(address.substring(0, address.length() - 13) + "catalog/" + type + "/"
                        + Uri.encode(id) + "/search=" + Uri.encode(query.trim()) + ".json");
            }
        }
        return out;
    }
    static List<TitleSearch.Title> search(Context c, String query) throws Exception {
        List<Future<JSONObject>> requests = new ArrayList<>();
        for (String endpoint : endpoints(AddonStore.load(c), query)) requests.add(WORK.submit(() -> MediaHttp.json(endpoint, null, 4000)));
        Set<String> seen = new HashSet<>(); List<TitleSearch.Title> out = new ArrayList<>();
        long deadline = android.os.SystemClock.elapsedRealtime() + 6000;
        try {
            for (Future<JSONObject> request : requests) {
                long left = deadline - android.os.SystemClock.elapsedRealtime(); if (left <= 0) break;
                JSONObject response; try { response = request.get(left, TimeUnit.MILLISECONDS); } catch (Exception ignored) { continue; }
                JSONArray metas = response.optJSONArray("metas");
                for (int i = 0; metas != null && i < metas.length() && out.size() < 12; i++) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) break;
                    JSONObject m = metas.optJSONObject(i); if (m == null) continue;
                    String id = m.optString("imdb_id", m.optString("id"));
                    if (!id.matches("tt[0-9]+") || !seen.add(id)) continue;
                    JSONObject find; try { find = TitleSearch.get("/find/" + id + "?external_source=imdb_id&language=" + People.language()); } catch (Exception ignored) { continue; }
                    for (String kind : new String[]{"movie", "tv"}) {
                        JSONArray items = find.optJSONArray(kind + "_results");
                        for (int j = 0; items != null && j < items.length(); j++) { JSONObject item = items.optJSONObject(j); if (item != null) item.put("media_type", kind); }
                        out.addAll(TitleSearch.parse(items));
                    }
                }
            }
        } finally { for (Future<?> request : requests) if (!request.isDone()) request.cancel(true); }
        return out;
    }
}
