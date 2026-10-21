package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;

/** Read saved playback diagnostics without changing household data. */
public class PlaybackDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject report = new JSONObject(), hashes = new JSONObject();
            for (String name : new String[]{"playback-settings", "home-apps", "my-services", "remote-keys", "setup", "media-addons",
                    ContinueWatching.PREFS, "playback-progress", "series-ui", PlaybackSession.STATE}) {
                TreeMap<String, Object> ordered = new TreeMap<>();
                for (Map.Entry<String, ?> item : getTargetContext().getSharedPreferences(name, 0).getAll().entrySet()) {
                    Object value = item.getValue();
                    if (value instanceof java.util.Set) value = new java.util.TreeSet<>((java.util.Set<String>) value);
                    ordered.put(item.getKey(), value);
                }
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(ordered.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                hashes.put(name, hex.toString());
            }
            report.put("checksums", hashes);
            report.put("playback_log", new org.json.JSONArray(PlaybackLog.lines(getTargetContext())));
            ContinueWatching.Entry interrupted = PlaybackSession.interrupted(getTargetContext());
            if (interrupted != null) report.put("interrupted_title", interrupted.title).put("interrupted_video", interrupted.video)
                    .put("interrupted_position", interrupted.position);
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            File directory = getTargetContext().getExternalFilesDir(null);
            if (image != null) {
                try (FileOutputStream file = new FileOutputStream(new File(directory, "playback-diagnostic.png"))) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
                } finally { image.recycle(); }
            }
            try (FileOutputStream file = new FileOutputStream(new File(directory, "playback-diagnostic.json"))) {
                file.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            result.putString("stream", "Passed read-only playback diagnostic capture.\n" + report + "\n");
            finish(-1, result);
        } catch (Exception error) {
            result.putString("stream", "FAILED playback diagnostics: " + error.getClass().getSimpleName() + "\n");
            finish(0, result);
        }
    }
}
