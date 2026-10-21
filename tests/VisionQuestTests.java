package io.freemytv;

import android.os.Bundle;
import java.util.*;
import org.json.*;

/** Check the reported episode gap against the connected library without uploading progress. */
public class VisionQuestTests extends TvTest {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); JSONObject report = new JSONObject();
        try {
            Ui.init(getTargetContext()); long now = System.currentTimeMillis(); String imdb = "tt23112594";
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).edit().putLong("cloud-at", 0).putLong("cloud-attempt-at", 0).commit();
            List<ContinueWatching.Entry> recent = ContinueWatching.refresh(getTargetContext());
            JSONObject item = LibrarySync.items(getTargetContext()).optJSONObject(imdb);
            JSONObject metadata = MediaHttp.json("https://v3-cinemeta.strem.io/meta/series/" + imdb + ".json", null, 5000).getJSONObject("meta");
            JSONObject timeline = NewEpisodes.timeline(imdb, metadata, now);
            report.put("library_item_present", item != null).put("series_status", metadata.optString("status"));
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS, 0).edit().putString("timeline:" + imdb, timeline.toString()).commit();
            if (item == null) throw new AssertionError("The connected library does not contain the reported series");
            JSONObject state = item.optJSONObject("state"); if (state == null) throw new AssertionError("The series has no saved watch state");
            report.put("video", state.optString("video_id")).put("position_ms", state.optLong("timeOffset"))
                    .put("duration_ms", state.optLong("duration")).put("flagged_watched", state.optInt("flaggedWatched"))
                    .put("times_watched", state.optInt("timesWatched")).put("temporary", item.optBoolean("temp"));
            List<ContinueWatching.Entry> followed = NewEpisodes.followed(getTargetContext(), recent);
            boolean retained = false; for (ContinueWatching.Entry e : followed) if (imdb.equals(e.imdb)) retained = true;
            if (!retained) throw new AssertionError("The reported series was lost from followed history");
            List<NewEpisodes.Notice> waiting = NewEpisodes.waiting(getTargetContext(), followed, now);
            NewEpisodes.Notice match = null; for (NewEpisodes.Notice n : waiting) if (imdb.equals(n.entry.imdb)) match = n;
            report.put("retained", retained).put("waiting", match != null);
            if (match == null) throw new AssertionError("The reported completed episode did not produce a waiting card");
            report.put("next_video", match.entry.video).put("next_release_ms", match.released);
            output.putString("stream", "Passed reported VisionQuest library and waiting-state checks. " + report + "\n");
        } catch (Throwable error) { output.putString("stream", "FAILED reported VisionQuest check: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "; " + report + "\n"); }
        finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
    }
}
