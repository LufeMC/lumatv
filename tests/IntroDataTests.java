package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;

/** Check marker identity, limits, and countdown behavior without network requests. */
public class IntroDataTests extends Instrumentation {
    private int checks;
    private void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
    private JSONObject response() throws Exception {
        return new JSONObject("{\"imdb_id\":\"tt0898266\",\"media_type\":\"tv\",\"season\":9,\"episode\":22,\"intro\":{\"start_ms\":137000,\"end_ms\":161000,\"confidence\":1}}");
    }
    private IntroMarkers.Marker parse(JSONObject value) { return IntroMarkers.parse(value, "tt0898266", 9, 22); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            IntroMarkers.Marker marker = parse(response());
            check(marker != null && marker.start == 137000 && marker.end == 161000, "valid episode marker");
            check(marker.fits(1200000) && !marker.fits(180000), "marker must fit the full episode");
            check(parse(response().put("episode", 23)) == null, "reject another episode");
            check(parse(response().put("season", 8)) == null, "reject another season");
            check(parse(response().put("imdb_id", "tt0903747")) == null, "reject another show");
            check(parse(response().put("no_intro", true)) == null, "respect no intro");
            check(parse(response().put("intro", JSONObject.NULL)) == null, "unknown intro has no marker");
            JSONObject low = response(); low.getJSONObject("intro").put("confidence", 0.5);
            check(parse(low) == null, "reject uncertain marker");
            JSONObject reverse = response(); reverse.getJSONObject("intro").put("end_ms", 130000);
            check(parse(reverse) == null, "reject reversed bounds");
            JSONObject huge = response(); huge.getJSONObject("intro").put("end_ms", 9000000);
            check(parse(huge) == null, "reject excessive intro");
            JSONObject absent = response(); absent.getJSONObject("intro").remove("start_ms");
            check(parse(absent) == null, "reject missing timestamp");
            IntroSkip state = new IntroSkip(); state.marker = marker;
            check(!state.update(100000, 1200000, true, 1000) && !state.visible, "hide before intro");
            check(!state.update(138000, 1200000, true, 2000) && state.visible && state.seconds() == 5, "show five second countdown");
            for (int i = 1; i <= 4; i++) check(!state.update(138000 + i * 1000, 1200000, true, 2000 + i * 1000), "do not skip early");
            check(state.update(143000, 1200000, true, 7000), "skip after five seconds");
            state.skip(); check(!state.update(140000, 1200000, true, 8000) && !state.visible, "do not skip twice");
            state = new IntroSkip(); state.marker = marker; state.update(138000, 1200000, true, 0);
            state.update(139000, 1200000, true, 1000); long remaining = state.remaining;
            state.update(139000, 1200000, false, 2000); state.update(139000, 1200000, false, 9000);
            check(state.remaining == remaining, "pause stops countdown");
            state.keep(); check(!state.update(140000, 1200000, true, 10000) && !state.visible, "keep intro cancels countdown");
            state = new IntroSkip(); state.marker = marker; state.update(138000, 1200000, true, 0);
            state.update(300000, 1200000, true, 1000);
            check(!state.visible && state.remaining == 5000, "seeking outside intro cancels countdown");
            result.putString("stream", "Passed " + checks + " intro marker checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getMessage() + "\n"); }
        finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
    }
}
