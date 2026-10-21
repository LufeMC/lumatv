package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Test the reported episode through the normal metadata and source flow. */
public class ReportedEpisodeTests extends TvTest {
    private SourcesActivity title;
    private PlayerActivity activity;
    private String episode = "tt0898266:10:2";
    private int seconds = 60;
    private boolean englishCaptions;
    private void main(Runnable task) {
        Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private Object value(String name) throws Exception {
        Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null && args.getString("episode", "").matches("tt0898266:[0-9]+:[0-9]+")) episode = args.getString("episode");
        if (args != null) try { seconds = Math.max(15, Math.min(600, Integer.parseInt(args.getString("seconds", "60")))); } catch (NumberFormatException ignored) {}
        englishCaptions = args != null && "en".equals(args.getString("captions"));
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) return;
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
        } finally { image.recycle(); }
    }
    @Override public void onStart() {
        Bundle output = new Bundle(); JSONObject report = new JSONObject();
        long openedAt = SystemClock.elapsedRealtime();
        try {
            getUiAutomation(); ParallelSourceChecks.peak.set(0); ParallelSourceChecks.results.clear();
            if (englishCaptions) {
                getTargetContext().getSharedPreferences(Prefs.PLAYBACK, 0).edit().putString(Prefs.SUBTITLE_MODE, "always")
                        .putString(Prefs.SUBTITLE_LANGUAGE, "en").commit();
                PlaybackMemory.rememberSubtitles(getTargetContext(), episode, "embedded:en");
            }
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory")
                    .putExtra("imdb", "tt0898266").putExtra("resume_video", episode).putExtra("resume_position", 0L).putExtra("auto_play", true));
            activity = (PlayerActivity) waitForMonitorWithTimeout(monitor, 35000); removeMonitor(monitor);
            report.put("player_open_ms", SystemClock.elapsedRealtime() - openedAt);
            if (activity == null) throw new AssertionError("The title did not open its player");
            long deadline = SystemClock.elapsedRealtime() + 150000;
            boolean[] valid = {false};
            while (SystemClock.elapsedRealtime() < deadline) {
                main(() -> {
                    try {
                        ExoPlayer video = (ExoPlayer) value("player"); PlaybackSession session = PlaybackSession.current;
                        valid[0] = video != null && video.isPlaying() && (Boolean) value("sourceValidated");
                        report.put("episode", episode).put("expected_ms", session.expectedDuration).put("index", session.index)
                                .put("queued", session.sources.size()).put("validated", valid[0]).put("failure_notes", session.failureNotes);
                        if (video != null) report.put("duration_ms", video.getDuration()).put("position_ms", video.getCurrentPosition())
                                .put("width", video.getVideoSize().width).put("height", video.getVideoSize().height);
                        if (session.index >= 0 && session.index < session.sources.size()) {
                            AddonStreams.Source source = session.sources.get(session.index);
                            report.put("addon", source.addon).put("advertised_quality", source.quality);
                        }
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                if (valid[0]) break;
                if (SystemClock.elapsedRealtime() % 5000 < 110) capture("reported-current.png");
                SystemClock.sleep(100);
            }
            report.put("startup_ms", SystemClock.elapsedRealtime() - openedAt);
            SystemClock.sleep(1000); capture("reported-episode.png");
            if (!valid[0]) throw new AssertionError("No source produced verified video and audio");
            if (report.optLong("duration_ms") < 600000) throw new AssertionError("The accepted source is too short for this episode");
            long[] start = {0};
            main(() -> { try { start[0] = ((ExoPlayer) value("player")).getCurrentPosition(); } catch (Exception error) { throw new RuntimeException(error); } });
            for (int i = 0; i < seconds; i++) {
                SystemClock.sleep(1000);
                main(() -> {
                    try {
                        String shown = (String) value("shownLines");
                        if (shown.contains("support@subtito.com")) throw new AssertionError("A subtitle-provider error reached the screen");
                        if (!shown.isEmpty() || !((ExoPlayer) value("player")).getCurrentCues().cues.isEmpty()) report.put("captions_seen", true);
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                if (i % 15 == 14) { Bundle update = new Bundle(); update.putString("stream", "Reported episode playback: " + (i + 1) + " seconds.\n"); sendStatus(0, update); }
            }
            main(() -> {
                try {
                    ExoPlayer video = (ExoPlayer) value("player");
                    if (video == null || !video.isPlaying() || !(Boolean) value("sourceValidated") || video.getCurrentPosition() - start[0] < seconds * 1000L - 5000)
                        throw new AssertionError("Playback did not continue with verified output");
                    report.put("observed_seconds", seconds).put("advanced_ms", video.getCurrentPosition() - start[0]);
                    report.put("subtitle_language", value("wantText") == null ? "off" : value("wantText"))
                            .put("external_subtitle_loaded", value("externalCues") != null)
                            .put("subtitle_candidates", ((java.util.List<?>) value("externalSubtitles")).size())
                            .put("rejected_subtitles", ((java.util.Set<?>) value("rejectedSubtitles")).size());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            capture("reported-episode-sustained.png"); report.put("parallel_media_checks", ParallelSourceChecks.peak.get());
            report.put("media_check_results", new JSONObject(ParallelSourceChecks.results));
            if (englishCaptions && !report.optBoolean("captions_seen")) throw new AssertionError("English captions did not appear during the live sample");
            output.putString("stream", "Passed reported episode playback. " + report + "\n");
        } catch (Throwable error) {
            try { report.put("failure", error.getClass().getSimpleName() + ": " + error.getMessage()); capture("reported-episode.png"); } catch (Exception ignored) {}
            output.putString("stream", "FAILED reported episode playback. " + report + "\n");
        } finally {
            try { report.put("media_check_results", new JSONObject(ParallelSourceChecks.results)); } catch (Exception ignored) {}
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "reported-episode.json"))) {
                file.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            runOnMainSync(() -> { if (activity != null) activity.finish(); if (title != null) title.finish(); });
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
