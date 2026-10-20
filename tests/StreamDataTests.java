package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class StreamDataTests extends TvTest {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext());
            check(!MediaHttp.https("http://example.com/video"), "reject cleartext");
            check(!MediaHttp.https("https://user:pass@example.com/video"), "reject user info");
            check(!MediaHttp.https("file:///private/file"), "reject local files");
            check(AddonStreams.quality("Movie.2160p.UHD") == 2160, "4K labels");
            check(AddonStreams.quality("Movie.1080p.WEB") == 1080, "1080p labels");
            check(AddonStreams.quality("Movie 720p") == 720, "720p labels");
            check(AddonStreams.quality("Movie 17200") == 0, "no false resolution");
            check(AddonStreams.quality("No resolution") == 0, "unknown resolution");
            String endpoint = AddonStreams.endpoint("https://example.com/private/config/manifest.json?token=test", "series", "tt123:2:3");
            check(android.net.Uri.parse(endpoint).getPath().equals("/private/config/stream/series/tt123:2:3.json")
                    && "test".equals(android.net.Uri.parse(endpoint).getQueryParameter("token")), "keep private path and query");
            JSONObject manifest = new JSONObject("{\"types\":[\"movie\",\"series\"],\"resources\":[{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}]}");
            check(AddonStreams.supports(manifest, "movie", "tt123"), "resource support");
            check(!AddonStreams.supports(manifest, "series", "tt123:1:1"), "resource type restriction");
            check(!AddonStreams.supports(manifest, "movie", "other:123"), "ID prefix restriction");
            JSONArray streams = new JSONArray("[{\"url\":\"https://example.com/a\",\"name\":\"1080p\",\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"Referer\":\"https://example.com/\",\"Host\":\"bad\",\"X-Test\":\"bad\\r\\nInjected: true\"}}}},"
                    + "{\"url\":\"https://example.com/a\",\"name\":\"Duplicate\"},{\"url\":\"https://example.com/b\",\"name\":\"2160p\"},"
                    + "{\"url\":\"https://example.com/c\",\"name\":\"720p\"},{\"infoHash\":\"abc\"},{\"url\":\"http://example.com/d\"}]");
            AddonStreams.Result parsed = AddonStreams.parse(streams, "Test");
            check(parsed.sources.size() == 3 && parsed.unsupported == 2, "filter unsupported and duplicate sources");
            check(parsed.sources.get(0).headers.size() == 1 && parsed.sources.get(0).headers.containsKey("Referer"), "safe request headers");
            List<AddonStreams.Source> exact = AddonStreams.select(parsed.sources, 1080);
            check(exact.size() == 1 && exact.get(0).quality == 1080, "never downgrade exact quality");
            List<AddonStreams.Source> best = AddonStreams.select(parsed.sources, -1);
            check(best.get(0).quality == 2160 && best.get(2).quality == 720, "best quality first");
            PlaybackSession queue = new PlaybackSession("tt123:2:3", "Test", best, -1);
            for (int i = 0; i < 3; i++) check(queue.next() != null, "finite source queue item");
            check(queue.next() == null, "no retry loop");
            AddonSubtitles.Subtitle subtitle = AddonSubtitles.parse(new JSONObject("{\"url\":\"https://example.com/en.vtt\",\"lang\":\"eng\",\"label\":\"English CC\"}"), "Test");
            check(subtitle.mime.equals("text/vtt") && subtitle.name.startsWith("English CC"), "subtitle language and format");
            check(AddonSubtitles.parse(new JSONObject("{\"url\":\"https://example.com/en.zip\",\"lang\":\"eng\"}"), "Test") == null, "reject subtitle archives");
            android.content.Context isolated = new android.content.ContextWrapper(getTargetContext()) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("test-" + name, mode);
                }
            };
            JSONArray privateData = new JSONArray("[{\"transportUrl\":\"https://example.com/private-key/manifest.json\"}]");
            AddonStore.save(isolated, privateData);
            check(AddonStore.load(isolated).toString().equals(privateData.toString()), "encrypted add-on round trip");
            check(!isolated.getSharedPreferences("media-addons", 0).getString("data", "").contains("private-key"), "no plain credential storage");
            AddonStore.clear(isolated);
            JSONArray savedAddons = new JSONArray("[{\"transportUrl\":\"https://example.com/addon/manifest.json\"}]");
            AddonStore.commit(isolated, AddonStore.sealAccount(savedAddons, "private-account-key"));
            check(AddonStore.account(isolated).equals("private-account-key"), "encrypted account persistence");
            check(!isolated.getSharedPreferences("media-addons", 0).getString("data", "").contains("private-account-key"), "no plain account key");
            check(!AddonStore.refreshDue(isolated), "fresh add-ons need no network synchronization");
            check(StremioAccount.addons(isolated).toString().equals(savedAddons.toString()), "automatic cached account reuse");
            isolated.getSharedPreferences("media-addons", 0).edit().putLong("updated", 1).commit();
            check(AddonStore.refreshDue(isolated), "automatic synchronization schedule");
            AddonStore.clear(isolated);
            JSONArray seasonData = new JSONArray("[{\"season_number\":0,\"episode_count\":2},{\"season_number\":2,\"episode_count\":3},{\"season_number\":1,\"episode_count\":4},{\"season_number\":3,\"episode_count\":0}]");
            List<SeriesEpisodes.Season> seasons = SeriesEpisodes.seasons(seasonData);
            check(seasons.size() == 3 && seasons.get(0).number == 1 && seasons.get(2).number == 0, "regular seasons before specials");
            check(SeriesEpisodes.initialSeason(seasons, 9) == 1 && SeriesEpisodes.initialSeason(seasons, 2) == 2, "restore valid season");
            JSONArray episodeData = new JSONArray("[{\"episode_number\":2,\"name\":\"Later\",\"air_date\":\"2030-01-01\"},{\"episode_number\":1,\"name\":\"Pilot\",\"air_date\":\"2020-01-01\"},{\"episode_number\":1,\"name\":\"Duplicate\"}]");
            List<SeriesEpisodes.Episode> episodes = SeriesEpisodes.episodes(episodeData, 2, "2026-10-19");
            check(episodes.size() == 2 && episodes.get(0).number == 1, "ordered episodes without duplicates");
            check(episodes.get(1).future && !episodes.get(0).future, "unreleased episode state");
            check(episodes.get(0).id("tt123").equals("tt123:2:1"), "exact season and episode identity");
            java.util.ArrayList<AddonStreams.Source> candidates = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                AddonStreams.Source candidate = new AddonStreams.Source(); candidate.url = "https://example.com/" + i;
                candidate.quality = i < 10 ? 2160 : i < 15 ? 1080 : 720; candidates.add(candidate);
            }
            List<AddonStreams.Source> plan = PlaybackPlan.automatic(candidates);
            check(plan.get(0).quality == 2160 && plan.get(2).quality == 1080 && plan.get(4).quality == 720, "automatic resolution fallback");
            check(plan.size() == 12, "bounded automatic retry plan");
            output.putString("stream", "Passed " + checks + " stream data checks.\n"); finish(-1, output);
        } catch (Throwable failure) {
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? ": " + failure.getMessage() : "") + "\n"); finish(0, output);
        }
    }
}
