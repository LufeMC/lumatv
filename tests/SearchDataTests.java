package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;

/** Check metadata parsing and app routing without starting video playback. */
public class SearchDataTests extends Instrumentation {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private void rejects(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (android.app.Application.getProcessName().equals("io.freemytv")) start();
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext());
            JSONArray input = new JSONArray("[{\"media_type\":\"person\",\"id\":1,\"name\":\"Actor\"},"
                    + "{\"media_type\":\"movie\",\"id\":2,\"title\":\"Movie\",\"release_date\":\"1999-01-01\"},"
                    + "{\"media_type\":\"movie\",\"id\":2,\"title\":\"Duplicate\"},"
                    + "{\"media_type\":\"tv\",\"id\":2,\"name\":\"Show\",\"first_air_date\":\"unknown\"},"
                    + "{\"media_type\":\"movie\",\"id\":3,\"title\":\"Adult\",\"adult\":true},"
                    + "{\"media_type\":\"movie\",\"id\":0,\"title\":\"Invalid\"}]");
            List<TitleSearch.Title> parsed = TitleSearch.parse(input);
            check(parsed.size() == 2, "filter people, adult titles, invalid IDs, and duplicates");
            check(parsed.get(0).year.equals("1999"), "movie year");
            check(parsed.get(1).series() && parsed.get(1).year.isEmpty(), "series with no valid year");
            check(TitleSearch.parse(null).isEmpty(), "empty response");
            check(StremioLinks.titleUri("movie", "tt0133093", true).toString().equals(
                    "stremio:///detail/movie/tt0133093/tt0133093?autoPlay=true"), "movie autoplay link");
            check(StremioLinks.titleUri("tv", "tt0108778", true).toString().equals(
                    "stremio:///detail/series/tt0108778"), "series opens episodes without autoplay");
            rejects(() -> StremioLinks.titleUri("person", "tt1", true), "reject invalid kind");
            rejects(() -> StremioLinks.titleUri("movie", "tt1/../bad", true), "reject invalid ID");
            Intent search = StremioLinks.searchIntent("A & B / C");
            check("A & B / C".equals(search.getData().getQueryParameter("search")), "encode search query");
            check(search.getPackage().equals("com.stremio.one"), "route only to Stremio");
            WatchOptions.Service netflix = WatchOptions.service("Netflix");
            check(netflix != null, "Netflix service mapping");
            check(WatchOptions.service("Amazon Prime Video with Ads").key.equals("prime"), "Prime service mapping");
            check(WatchOptions.service("HBO Max Amazon Channel") == null, "channel subscription is not standalone Max");
            check(WatchOptions.safeLink(netflix, "https://www.netflix.com/title/123") != null, "allow Netflix HTTPS title");
            check(WatchOptions.safeLink(netflix, "https://netflix.com.evil.test/title/123") == null, "reject unrelated host");
            check(WatchOptions.safeLink(netflix, "https://evil.test@netflix.com/title/123") == null, "reject URL user info");
            check(WatchOptions.safeLink(netflix, "http://www.netflix.com/title/123") == null, "require HTTPS");
            check(WatchOptions.safeLink(netflix, "https://www.netflix.com:999/title/123") == null, "reject custom port");
            check(WatchOptions.safeLink(netflix, "intent://anything#Intent;end") == null, "reject executable intent data");
            JSONObject country = new JSONObject("{\"flatrate\":[{\"provider_name\":\"Netflix\"},{\"provider_name\":\"HBO Max Amazon Channel\"}],"
                    + "\"rent\":[{\"provider_name\":\"Amazon Video\"}],\"buy\":[{\"provider_name\":\"Amazon Video\"}]}");
            Map<String, WatchOptions.Option> offers = WatchOptions.parseCatalog(country);
            check(offers.size() == 2 && offers.get("netflix").offers.contains("flatrate"), "regional provider offers");
            check(offers.get("prime").offers.size() == 2 && !offers.get("prime").offers.contains("flatrate"), "rent and buy are not subscription offers");
            check(WatchOptions.parseCatalog(null).isEmpty(), "missing regional offers");
            List<TitleSearch.Title> live = TitleSearch.search("Stranger Things");
            TitleSearch.Title title = null;
            for (TitleSearch.Title candidate : live) if (candidate.kind.equals("tv") && candidate.id == 66732) title = candidate;
            check(title != null, "live title search");
            check("tt4574334".equals(TitleSearch.imdbId(title)), "live IMDb mapping");
            WatchOptions.Result result = WatchOptions.load(getTargetContext(), title);
            check(result.catalogChecked && "tt4574334".equals(result.imdb), "live regional catalog");
            boolean netflixFound = false;
            for (WatchOptions.Option option : result.options) if (option.service.key.equals("netflix")) {
                netflixFound = true;
                check(option.link != null, "live Netflix title link");
                check(WatchOptions.intent(getTargetContext(), option).resolveActivity(getTargetContext().getPackageManager()) != null,
                        "Netflix title intent resolves on this TV");
            }
            check(netflixFound, "Netflix listed for installed app");
            output.putString("stream", "Passed " + checks + " search data checks.\n"); finish(-1, output);
        } catch (Throwable failure) {
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? ": " + failure.getMessage() : "") + "\n");
            finish(0, output);
        }
    }
}
