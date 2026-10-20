package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repair the specific test setting and reported failed-clip progress. */
public class PreferenceRepair extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            SettingsSnapshot.restore(getTargetContext());
            WatchSnapshot.restore(getTargetContext());
            getTargetContext().getSharedPreferences("playback-settings", 0).edit().putString("audio-language", "en").remove("audio-title:tt0000000").remove("audio-title:tt0000001").commit();
            android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0);
            JSONArray before = new JSONArray(prefs.getString("local", "[]")), after = new JSONArray(); int repaired = 0;
            for (int i = 0; i < before.length(); i++) {
                JSONObject item = before.getJSONObject(i);
                boolean recent = System.currentTimeMillis() - item.optLong("updated") < 60 * 60000;
                boolean bad = PlaybackValidation.shortPlaceholder(1200000, item.optLong("duration"));
                if (item.optString("video").equals("tt0898266:9:22") && recent && bad) { repaired++; continue; }
                after.put(item);
            }
            prefs.edit().putString("local", after.toString()).commit();
            android.content.SharedPreferences progress = getTargetContext().getSharedPreferences("playback-progress", 0);
            if (repaired > 0 && progress.getLong("tt0898266:9:22", 0) <= 30000) progress.edit().remove("tt0898266:9:22").commit();
            java.io.File fixtureFolder = new java.io.File(getTargetContext().getCacheDir(), "test-fixtures");
            for (String name : new String[]{"sample.mkv", "verified.mkv", "placeholder.mkv", "audio-only.mkv", "video-only.mkv", "corrupt.mkv", "truncated.mkv", "subtitle-en.srt", "subtitle-es.srt"})
                new java.io.File(fixtureFolder, name).delete();
            fixtureFolder.delete();
            result.putString("stream", "English default saved. Failed-clip progress records repaired: " + repaired + ".\n"); finish(-1, result);
        } catch (Exception error) { result.putString("stream", "FAILED: " + error.getClass().getSimpleName() + "\n"); finish(0, result); }
    }
}
