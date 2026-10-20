package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Check episode boundaries and service priority without private account requests. */
public class EpisodeDataTests extends Instrumentation {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    private JSONObject season(int number, String date) throws Exception {
        return new JSONObject().put("episodes", new JSONArray().put(new JSONObject().put("episode_number", number).put("air_date", date).put("name", "Episode " + number)));
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Ui.init(getTargetContext());
            ContinueWatching.Entry current = new ContinueWatching.Entry(); current.tmdb = 123; current.kind = "tv"; current.season = 1; current.episode = 1;
            JSONObject same = season(3, "2020-01-01");
            same.getJSONArray("episodes").put(new JSONObject().put("episode_number", 2).put("air_date", "2020-01-01"));
            SeriesEpisodes.Episode next = EpisodeSequence.next(current, path -> same, "2026-10-20");
            check(next.season == 1 && next.number == 2, "next episode follows episode number");
            JSONObject details = new JSONObject().put("seasons", new JSONArray("[{\"season_number\":0,\"episode_count\":1},{\"season_number\":1,\"episode_count\":1},{\"season_number\":2,\"episode_count\":1},{\"season_number\":3,\"episode_count\":1}]"));
            EpisodeSequence.Loader boundary = path -> path.endsWith("/123") ? details : path.contains("/season/1?") ? season(1, "2020-01-01") : path.contains("/season/2?") ? season(1, "2030-01-01") : season(1, "2021-01-01");
            next = EpisodeSequence.next(current, boundary, "2026-10-20");
            check(next.season == 3 && next.number == 1, "cross season boundary and skip future episodes");
            current.season = 3;
            check(EpisodeSequence.next(current, boundary, "2026-10-20") == null, "series end has no next episode");
            current.season = 0;
            check(EpisodeSequence.next(current, boundary, "2026-10-20") == null, "specials do not start regular seasons");
            current.kind = "movie";
            check(EpisodeSequence.next(current, path -> { throw new AssertionError("movie metadata request"); }, "2026-10-20") == null, "movies have no episode requests");
            current.kind = "tv"; current.tmdb = 0;
            check(EpisodeSequence.next(current, path -> { throw new AssertionError("invalid metadata request"); }, "2026-10-20") == null, "missing identity has no episode request");
            List<WatchOptions.Option> offers = new ArrayList<>();
            WatchOptions.Option rent = new WatchOptions.Option(); rent.service = WatchOptions.SERVICES[1]; rent.offers.add("rent"); offers.add(rent);
            check(WatchOptions.preferred(offers) == null, "rental does not become the default action");
            WatchOptions.Option free = new WatchOptions.Option(); free.service = WatchOptions.SERVICES[10]; free.offers.add("free"); offers.add(free);
            check(WatchOptions.preferred(offers) == free, "free offer precedes rentals");
            WatchOptions.Option netflix = new WatchOptions.Option(); netflix.service = WatchOptions.SERVICES[0]; netflix.offers.add("flatrate"); offers.add(netflix);
            check(WatchOptions.preferred(offers) == netflix, "Netflix subscription offer precedes free and rental offers");
            JSONObject old = new ContinueWatching.Entry().json().put("provider", "Free My TV");
            check(ContinueWatching.Entry.parse(old).provider.equals("Stremio"), "old internal progress identifies Stremio");
            old.put("provider", "Netflix").put("pkg", "com.netflix.ninja");
            check(ContinueWatching.Entry.parse(old).provider.equals("Netflix"), "external progress retains Netflix identity");
            SeekState seek = new SeekState(); seek.begin(10000, 1200000, true); seek.input(1000);
            check(!seek.due(3999) && seek.due(4000), "seek waits three seconds before confirmation");
            seek.move(1, 0); seek.input(3500);
            check(!seek.due(4000) && seek.due(6500), "each arrow press restarts the confirmation timer");
            check(seek.finish(false) == 10000 && !seek.due(9000), "Back cancels the pending seek");
            result.putString("stream", "Passed " + checks + " episode and provider data checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
    }
}
