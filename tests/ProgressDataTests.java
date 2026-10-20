package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Verify resume identity, cloud filtering, and uncommitted seeking. */
public class ProgressDataTests extends TvTest {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    private JSONObject item(String id, String video, long offset) throws Exception {
        return new JSONObject().put("_id", id).put("name", "Test series").put("type", "series").put("_mtime", "2026-10-19T10:00:00Z")
                .put("state", new JSONObject().put("video_id", video).put("timeOffset", offset).put("duration", 1200000).put("lastWatched", "2026-10-19T09:00:00Z"));
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext());
            check(ContinueWatching.watchNextKind(android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SERIES).equals("tv"), "Watch Next series keep their content type");
            check(ContinueWatching.watchNextKind(android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SEASON).equals("tv"), "Watch Next seasons keep their content type");
            ContinueWatching.Entry movieName = new ContinueWatching.Entry(), seriesName = new ContinueWatching.Entry();
            movieName.title="The Flash"; seriesName.title="The Flash"; seriesName.kind="tv";
            check(!ContinueWatching.sameShow(movieName,seriesName), "a movie cannot hide a series with the same name");
            JSONArray cloud = new JSONArray().put(item("tt123", "tt123:2:4", 120000));
            cloud.put(item("tt124", "tt124:1:2", 0));
            cloud.put(item("tt125", "tt125:1:2", 10000).put("removed", true));
            cloud.put(item("tt126", "tt126:1:2", 10000).put("removed", true).put("temp", true));
            cloud.put(item("tt127", "file:/private", 10000));
            cloud.put(item("unsupported", "unsupported:1:2", 10000));
            List<ContinueWatching.Entry> entries = ContinueWatching.stremio(cloud);
            check(entries.size() == 2, "skip removed, unwatched, and invalid library records");
            ContinueWatching.Entry entry = entries.get(0);
            check(entry.video.equals("tt123:2:4") && entry.season == 2 && entry.episode == 4, "preserve exact episode");
            check(entry.position == 120000 && entry.duration == 1200000, "preserve millisecond progress");
            check(entry.updated == java.time.Instant.parse("2026-10-19T09:00:00Z").toEpochMilli(), "use last watch time");
            ContinueWatching.Entry latest = ContinueWatching.Entry.parse(entry.json()); latest.video = "tt123:3:1"; latest.updated++;
            List<ContinueWatching.Entry> merged = ContinueWatching.merge(entries, Collections.singletonList(latest));
            check(merged.size() == 2 && merged.get(0).video.equals("tt123:3:1"), "one most recent episode per show");
            ContinueWatching.Entry netflix = ContinueWatching.Entry.parse(entry.json()); netflix.pkg = "com.netflix.ninja"; netflix.id = "netflix-test";
            check(ContinueWatching.merge(entries, Collections.singletonList(netflix)).size() == 2, "one card per show across services");
            check(ContinueWatching.provider("com.netflix.ninja").equals("Netflix"), "identify Netflix");
            check(ContinueWatching.provider("com.amazon.amazonvideo.livingroom").equals("Prime Video"), "identify Prime Video");
            check(ContinueWatching.provider("untrusted.application").isEmpty(), "ignore unknown providers");
            check(ContinueWatching.externalIntent(getTargetContext(), netflix) == null, "reject empty service intent");
            netflix.intent = "intent:#Intent;component=untrusted.application/.Main;end";
            check(ContinueWatching.externalIntent(getTargetContext(), netflix) == null, "reject redirected service intent");
            android.content.Context isolated = new android.content.ContextWrapper(getTargetContext()) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("test-progress-" + name, mode); }
            };
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().commit();
            ContinueWatching.record(isolated, entry, 45000, 1200000, false);
            check(ContinueWatching.cached(isolated).size() == 1 && ContinueWatching.cached(isolated).get(0).position == 45000, "save resume card");
            ContinueWatching.record(isolated, entry, 55000, 1200000, false);
            check(ContinueWatching.cached(isolated).size() == 1 && ContinueWatching.cached(isolated).get(0).position == 55000, "update resume card");
            ContinueWatching.record(isolated, entry, 0, 1200000, true);
            check(ContinueWatching.cached(isolated).isEmpty(), "remove completed title");
            JSONArray oldCloud = new JSONArray().put(latest.json());
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().putString("stremio", oldCloud.toString()).commit();
            check(ContinueWatching.cached(isolated).isEmpty(), "completed title suppresses older cloud progress");
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().remove("stremio").commit();
            ContinueWatching.record(isolated, entry, 100, 1200000, false);
            check(ContinueWatching.cached(isolated).isEmpty(), "ignore accidental short playback");
            android.content.Intent resume = ContinueWatching.internalIntent(isolated, latest);
            check(resume.getStringExtra("resume_video").equals("tt123:3:1") && resume.getBooleanExtra("auto_play", false), "resume exact episode automatically");
            check(resume.getLongExtra("resume_position", -1) == latest.position, "pass resume position");
            SeekState seek = new SeekState(); seek.begin(30000, 120000, true); seek.move(1, 0);
            check(seek.target == 40000 && seek.original == 30000 && seek.active, "preview preserves original position");
            seek.move(1, 12); check(seek.target == 70000, "hold accelerates to thirty seconds");
            seek.move(1, 30); check(seek.target == 119000, "hold accelerates without passing duration");
            check(seek.finish(false) == 30000 && !seek.active, "cancel restores original position");
            seek.begin(30000, 120000, false); seek.move(-1, 0); check(seek.finish(true) == 20000 && !seek.playing, "confirm preserves paused state");
            seek.begin(2000, 120000, true); seek.move(-1, 0); check(seek.target == 0, "cannot seek before start");
            List<ContinueWatching.Entry> many = new ArrayList<>();
            for (int i = 0; i < 60; i++) { ContinueWatching.Entry e = ContinueWatching.Entry.parse(entry.json()); e.imdb = "tt" + i; e.updated = i; e.completed = false; many.add(e); }
            check(ContinueWatching.merge(many, Collections.emptyList()).size() == 40, "bound home history");
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().commit();
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            for (ContinueWatching.Entry e : ContinueWatching.television(getTargetContext())) counts.put(e.provider, counts.getOrDefault(e.provider, 0) + 1);
            boolean account = !AddonStore.account(getTargetContext()).isEmpty();
            int library = 0; if (account) for (ContinueWatching.Entry e : ContinueWatching.refresh(getTargetContext())) if (e.provider.equals("Stremio")) library++;
            output.putString("stream", "Passed " + checks + " progress data checks.\nShared TV progress: " + counts + "; Stremio account: " + account + "; recent Stremio entries: " + library + "\n"); finish(-1, output);
        } catch (Throwable failure) {
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName() + (failure instanceof AssertionError ? ": " + failure.getMessage() : "") + "\n"); finish(0, output);
        }
    }
}
