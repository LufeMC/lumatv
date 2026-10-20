package io.freemytv;

import android.os.Bundle;
import java.util.*;
import org.json.*;

/** Check catalog constraints, public metadata, and episode identity. */
public class DiscoveryDataTests extends TvTest {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Ui.init(getTargetContext());
            JSONArray extras = new JSONArray().put(new JSONObject().put("name", "search").put("isRequired", true));
            JSONObject catalog = new JSONObject().put("type", "series").put("id", "my/catalog").put("extra", extras);
            JSONObject manifest = new JSONObject().put("catalogs", new JSONArray().put(catalog));
            JSONObject descriptor = new JSONObject().put("transportUrl", "https://example.invalid/private/manifest.json").put("manifest", manifest);
            List<String> endpoints = AddonCatalogs.endpoints(new JSONArray().put(descriptor), "one & two");
            check(endpoints.size() == 1, "search catalog is discovered");
            check(endpoints.get(0).contains("/private/catalog/series/my%2Fcatalog/search=one%20%26%20two.json"), "catalog paths and query are encoded");
            check(AddonCatalogs.endpoints(new JSONArray().put(descriptor), "a").isEmpty(), "short query sends no catalog requests");
            extras.put(new JSONObject().put("name", "genre").put("isRequired", true));
            check(AddonCatalogs.endpoints(new JSONArray().put(descriptor), "query").isEmpty(), "unsatisfied required filters prevent a request");
            descriptor.put("transportUrl", "http://example.invalid/manifest.json");
            check(AddonCatalogs.endpoints(new JSONArray().put(descriptor), "query").isEmpty(), "insecure catalog address is rejected");
            JSONArray cast = new JSONArray().put(new JSONObject().put("id", 1).put("name", "Person").put("profile_path", "/image.jpg"))
                    .put(new JSONObject().put("id", 1).put("name", "Duplicate"))
                    .put(new JSONObject().put("id", 2).put("name", "Excluded").put("adult", true));
            check(People.parse(cast).size() == 1, "people exclude duplicates and adult metadata");
            JSONArray titles = new JSONArray().put(new JSONObject().put("id", 3).put("media_type", "movie").put("title", "Movie"))
                    .put(new JSONObject().put("id", 3).put("media_type", "tv").put("name", "Show"));
            check(People.items(titles, "").size() == 2, "movie and series identifiers remain distinct");
            SeriesEpisodes.Episode episode = new SeriesEpisodes.Episode(); episode.season = 2; episode.number = 3; episode.name = "A New Beginning"; episode.date = "2020-01-02";
            JSONArray videos = new JSONArray().put(new JSONObject().put("id", "tt123:1:13").put("title", "A New Beginning").put("released", "2020-01-02T00:00:00Z"));
            check(EpisodeMap.map("tt123", episode, videos).equals("tt123:1:13"), "unique episode identity uses source numbering");
            check(EpisodeMap.map("tt123", episode, null).equals("tt123:2:3"), "absent metadata keeps existing numbering");
            videos.put(new JSONObject().put("id", "tt123:4:5").put("title", "A New Beginning").put("released", "2020-01-02"));
            check(EpisodeMap.map("tt123", episode, videos).equals("tt123:2:3"), "ambiguous episode match is not guessed");
            videos.remove(1); videos.getJSONObject(0).put("released", "2020-02-03");
            check(EpisodeMap.map("tt123", episode, videos).equals("tt123:2:3"), "different air date cannot remap an episode");
            videos.getJSONObject(0).put("released", "2020-01-02").put("id", "tt999:1:13");
            check(EpisodeMap.map("tt123", episode, videos).equals("tt123:2:3"), "another series cannot remap an episode");
            SeriesEpisodes.Episode broadcast = new SeriesEpisodes.Episode(); broadcast.season=10; broadcast.number=1; broadcast.name="The Conjugal Conjecture"; broadcast.date="2016-09-19"; broadcast.still="/still.jpg"; broadcast.minutes=22;
            JSONArray canonical=new JSONArray().put(new JSONObject().put("id","tt123:10:1").put("season",10).put("episode",1).put("name",broadcast.name).put("released","2016-09-20T04:00:00.000Z").put("thumbnail","https://example.invalid/episode.jpg"));
            List<SeriesEpisodes.Episode> mapped=EpisodeMap.parseVideos("tt123",canonical,10,Collections.singletonList(broadcast),"2026-10-20");
            check(mapped.size()==1&&mapped.get(0).name.equals(broadcast.name),"canonical episode names use the provider name field");
            check(mapped.get(0).still.equals("/still.jpg")&&mapped.get(0).minutes==22,"a time-zone date difference retains matched artwork and runtime");
            mapped=EpisodeMap.parseVideos("tt123",canonical,10,Collections.emptyList(),"2026-10-20");
            check(mapped.get(0).still.equals("https://example.invalid/episode.jpg"),"provider artwork remains available without a TMDB match");
            check(StremioLinks.videoUri("tv", "tt123", "tt123:1:13").toString().endsWith("/tt123/tt123%3A1%3A13"), "episode fallback link contains the selected video");
            result.putString("stream", "Passed " + checks + " discovery data checks.\n");
        } catch (Throwable e) { result.putString("stream", "FAILED after " + checks + ": " + e.getMessage() + "\n"); }
        finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
    }
}
