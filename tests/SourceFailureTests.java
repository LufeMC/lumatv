package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise controlled add-on failures without changing the user's account. */
public class SourceFailureTests extends TvTest {
    private int checks;
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    private HttpURLConnection response(int status, InputStream body, long length) throws Exception {
        return new HttpURLConnection(new URL("https://example.com/private-test-token")) {
            public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
            public int getResponseCode() { return status; }
            public long getContentLengthLong() { return length; }
            public InputStream getInputStream() { return body; }
        };
    }
    private InputStream bytes(String body) { return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private void rejected(int status, InputStream body, long length, String name) throws Exception {
        try { MediaHttp.read(response(status, body, length)); throw new AssertionError(name); }
        catch (Exception error) { check("network".equals(error.getMessage()), name + " is sanitized"); }
    }
    private AddonStreams.Source source(String name, int quality) { AddonStreams.Source s = new AddonStreams.Source(); s.url = "https://example.com/" + name; s.quality = quality; return s; }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            for (int code : new int[]{401, 403, 404, 429, 500, 503}) rejected(code, bytes("private-test-token"), 18, "HTTP " + code);
            rejected(302, bytes("{}"), 2, "redirect");
            rejected(200, bytes("<html>expired account</html>"), -1, "HTML response");
            rejected(200, bytes(""), 0, "empty response");
            rejected(200, bytes("{broken"), -1, "malformed JSON");
            rejected(200, bytes("{}"), 4 * 1024 * 1024, "declared oversized response");
            rejected(200, new ByteArrayInputStream(new byte[3 * 1024 * 1024 + 1]), -1, "chunked oversized response");
            rejected(200, new InputStream() { public int read() throws java.io.IOException { throw new java.net.SocketTimeoutException("private-test-token"); } }, -1, "read timeout");
            rejected(200, new InputStream() { public int read() throws java.io.IOException { throw new java.io.IOException("offline private-test-token"); } }, -1, "connection lost");
            check(MediaHttp.read(response(200, bytes("{\"streams\":[]}"), -1)).getJSONArray("streams").length() == 0, "valid empty result");
            try { MediaHttp.result(new JSONObject("{\"error\":\"expired account\"}")); throw new AssertionError("account error"); }
            catch (Exception error) { check("api_error".equals(error.getMessage()), "account rejection"); }
            AddonStreams.Result parsed = AddonStreams.parse(new JSONArray("[null,7,{}, {\"infoHash\":\"test\"}, {\"externalUrl\":\"https://example.com\"}, {\"url\":\"javascript:bad\"}]"), "Test");
            check(parsed.sources.isEmpty(), "invalid and unsupported stream types");
            List<AddonStreams.Source> candidates = new ArrayList<>(); candidates.add(source("a", 1080));
            SourceGate gate = new SourceGate();
            check(gate.delay(1000, 3, candidates) == 800, "short quality opportunity");
            check(gate.delay(1799, 1, candidates) == 1 && gate.delay(1800, 1, candidates) == 0, "slow add-on cannot block playback");
            candidates.add(source("b", 2160)); check(gate.delay(1100, 2, candidates) == 0, "4K starts immediately");
            check(new SourceGate().delay(1000, 0, candidates) == 0, "finished discovery starts immediately");
            check(new SourceGate().delay(1000, 0, new ArrayList<>()) < 0, "empty discovery does not start");
            PlaybackSession session = new PlaybackSession("tt0000000", "Test", PlaybackPlan.automatic(candidates), -1);
            session.discovering = true;
            check(session.next().quality == 2160 && session.next().quality == 1080, "resolution fallback");
            check(session.next() == null && session.index == 1, "wait for late source without skipping it");
            candidates.add(source("c", 720)); session.updateSources(candidates, true);
            check(session.next().quality == 720, "late source recovers exhausted initial list");
            session.updateSources(candidates, false); check(session.next() == null && session.index == 3, "finite retry after discovery ends");
            for (int i = 0; i < 40; i++) candidates.add(source("extra" + i, 2160));
            session = new PlaybackSession("tt0000000", "Test", PlaybackPlan.automatic(candidates), -1); session.next();
            String playing = session.sources.get(0).url; session.updateSources(candidates, false);
            check(session.sources.size() <= 12 && session.sources.get(0).url.equals(playing), "late results preserve current source and retry limit");
            check(MetadataCache.lifetime("/search/multi?query=test") < MetadataCache.lifetime("/tv/123"), "search expires before title details");
            MetadataCache.init(getTargetContext()); String path = "/test-performance-cache";
            MetadataCache.write(path, "{\"id\":123}");
            check(new JSONObject(MetadataCache.read(path, System.currentTimeMillis())).getInt("id") == 123, "metadata survives memory cache loss");
            check(MetadataCache.read(path, System.currentTimeMillis() + MetadataCache.lifetime(path) + 1000) == null, "expired metadata needs refresh");
            android.graphics.Bitmap backgroundInput = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888);
            Backdrop background = Backdrop.background(backgroundInput);
            check(background.lightImage != null && background.nightImage != null, "already-sized background remains readable during reduction");
            background.lightImage.recycle(); background.nightImage.recycle();
            ThumbnailCache.init(getTargetContext());
            android.graphics.Bitmap thumbnail = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888);
            thumbnail.eraseColor(android.graphics.Color.BLUE); ThumbnailCache.write("https://example.com/private-thumbnail-test", thumbnail); thumbnail.recycle();
            thumbnail = ThumbnailCache.read("https://example.com/private-thumbnail-test");
            check(thumbnail != null && thumbnail.getWidth() <= 640 && thumbnail.getHeight() <= 640, "service artwork survives memory cache loss at a bounded size");
            thumbnail.recycle();
            check(ThumbnailCache.read("https://example.com/not-cached") == null, "missing artwork cache permits normal loading");
            check(PlaybackValidation.shortPlaceholder(1200000, 30000), "thirty-second provider error clip is rejected");
            check(!PlaybackValidation.shortPlaceholder(60000, 30000), "legitimate short title remains available");
            check(!PlaybackValidation.shortPlaceholder(1200000, 1190000), "normal episode duration is accepted");
            check(!PlaybackValidation.shortPlaceholder(1200000, -1), "unknown live duration is not misclassified");
            check(SourceProbe.read(response(403, bytes("denied"), 6)) == SourceProbe.FAILED, "source probe rejects denied link");
            check(SourceProbe.read(response(404, bytes("missing"), 7)) == SourceProbe.FAILED, "source probe rejects missing link");
            check(SourceProbe.read(response(200, bytes("<html>failure</html>"), -1)) == SourceProbe.FAILED, "source probe rejects an HTML error with HTTP success");
            check(SourceProbe.read(response(200, bytes("{\"error\":\"expired\"}"), -1)) == SourceProbe.FAILED, "source probe rejects JSON error with HTTP success");
            check(SourceProbe.read(response(200, bytes(""), 0)) == SourceProbe.FAILED, "source probe rejects empty media");
            check(SourceProbe.read(response(416, bytes(""), 0)) == SourceProbe.UNCERTAIN, "range refusal permits player verification");
            check(SourceProbe.read(response(206, new ByteArrayInputStream(new byte[4096]), -1)) == SourceProbe.AVAILABLE, "source probe accepts binary partial response");
            check(SourceProbe.read(response(200, bytes("#EXTM3U\n#EXT-X-VERSION:3"), -1)) == SourceProbe.AVAILABLE, "source probe accepts an HLS playlist");
            check(SourceProbe.read(response(200, new InputStream() { public int read() throws java.io.IOException { throw new java.net.SocketTimeoutException(); } }, -1)) == SourceProbe.UNCERTAIN, "probe timeout does not discard an unverified source");
            String stamp = LibrarySync.fingerprint(new JSONArray("[[\"tt123\",1000],[\"tt456\",2000]]"));
            check(stamp.equals(LibrarySync.fingerprint(new JSONArray("[[\"tt456\",2000],[\"tt123\",1000]]"))), "library order does not force a full download");
            check(!stamp.equals(LibrarySync.fingerprint(new JSONArray("[[\"tt123\",1001],[\"tt456\",2000]]"))), "changed progress refreshes history");
            check(!stamp.equals(LibrarySync.fingerprint(new JSONArray("[[\"tt123\",1000]]"))), "removed library item refreshes history");
            try { LibrarySync.fingerprint(new JSONArray("[[\"tt123\",\"bad\"]]")); throw new AssertionError("invalid metadata"); }
            catch (Exception error) { check("library_metadata".equals(error.getMessage()), "invalid metadata preserves previous history"); }
            output.putString("stream", "Passed " + checks + " source failure and cache checks.\n"); finish(-1, output);
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); finish(0, output); }
    }
}
