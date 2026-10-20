package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import androidx.media3.exoplayer.ExoPlayer;

/** Exercise remote navigation and episode changes with generated video and real title metadata. */
public class EpisodeFlowTests extends TvTest {
    private SourcesActivity title;
    private PlayerActivity player;
    private int checks;
    private Object value(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private void set(Object target, String name, Object value) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private void call(Object target, String name) throws Exception { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
    private ExoPlayer video() throws Exception { return (ExoPlayer) value(player, "player"); }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; Throwable[] failure = {null};
            runOnMainSync(() -> { try { pass[0] = condition.get(); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(label + ": " + failure[0]);
            if (pass[0]) { checks++; return; } SystemClock.sleep(50);
        }
        throw new AssertionError(label);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true);
    }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(400);
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
        } finally { image.recycle(); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @SuppressWarnings("unchecked") private void controlled(int episode) throws Exception {
        ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
        runOnMainSync(() -> {
            try {
                android.os.Handler handler = (android.os.Handler) value(title, "playback"); handler.removeCallbacksAndMessages(null);
                set(title, "generation", (Integer) value(title, "generation") + 1);
                for (java.util.concurrent.Future<?> request : (List<java.util.concurrent.Future<?>>) value(title, "sourceRequests")) request.cancel(true);
                ((List<?>) value(title, "sourceRequests")).clear();
                set(title, "remaining", 0); set(title, "probing", 0); set(title, "episodes", false); set(title, "pendingPlay", false);
                set(title, "season", 9); set(title, "focusedEpisode", episode); set(title, "videoId", "tt0898266:9:" + episode);
                set(title, "videoTitle", "The Big Bang Theory · S9 E" + episode);
                AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(new java.io.File(getTargetContext().getCacheDir(), "episode-test.mkv")).toString();
                source.name = "Generated test video"; source.quality = 360; source.probeState = SourceProbe.AVAILABLE;
                List<AddonStreams.Source> sources = (List<AddonStreams.Source>) value(title, "sources"); sources.clear(); sources.add(source);
                Method start = SourcesActivity.class.getDeclaredMethod("startPlayback", AddonStreams.Source.class); start.setAccessible(true); start.invoke(title, source);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        player = (PlayerActivity) waitForMonitorWithTimeout(monitor, 10000); removeMonitor(monitor);
        if (player == null) throw new AssertionError("The player did not open");
        await(() -> video() != null && (Boolean) value(player, "sourceValidated") && (Boolean) value(player, "audioAdvancing"), "decoded video and audio output start");
        await(() -> value(player, "nextEpisode") != null, "next aired episode is available");
    }
    private void endEpisode() {
        runOnMainSync(() -> { try { video().seekTo(video().getDuration() - 100); video().setPlayWhenReady(true); } catch (Exception error) { throw new RuntimeException(error); } });
    }
    private void backToEpisodes(int episode) throws Exception {
        // The first Back hides visible controls; with the controls hidden, one Back leaves the player.
        if (player != null) runOnMainSync(() -> { try { call(player, "hideControls"); } catch (Exception error) { throw new RuntimeException(error); } });
        key(KeyEvent.KEYCODE_BACK);
        await(() -> (Boolean) value(title, "active") && (Boolean) value(title, "episodes") && !((List<?>) value(title, "episodeList")).isEmpty(), "one Back returns directly to episodes");
        await(() -> (Integer) value(title, "focusedEpisode") == episode && title.getCurrentFocus() != null
                && title.getCurrentFocus().getContentDescription() != null && title.getCurrentFocus().getContentDescription().toString().startsWith("S9 E" + episode + "."), "Back restores the current episode card");
        player = null;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            try (java.io.InputStream in = getContext().getAssets().open("verified.mkv")) {
                java.nio.file.Files.copy(in, new java.io.File(getTargetContext().getCacheDir(), "episode-test.mkv").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            TitleSearch.Title love = null;
            for (TitleSearch.Title item : TitleSearch.search("Love Is Blind")) if (item.name.equalsIgnoreCase("Love Is Blind") && item.series()) { love = item; break; }
            if (love == null) throw new AssertionError("Love Is Blind metadata is unavailable");
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", love.id).putExtra("title_kind", "tv").putExtra("title_name", love.name));
            await(() -> ((android.widget.Button) value(title, "servicePlay")).getText().toString().equals("Watch on Netflix"), "Love Is Blind offers Netflix first");
            await(() -> ((View) value(title, "servicePlay")).hasFocus(), "Netflix receives initial remote focus");
            screenshot("netflix-first.png"); runOnMainSync(() -> title.finish()); title = null; waitForIdleSync();
            getTargetContext().getSharedPreferences("series-ui", 0).edit().putInt("season:1418", 9).putInt("episode:1418", 22).commit();
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory"));
            await(() -> (Boolean) value(title, "episodes") && !((List<?>) value(title, "episodeList")).isEmpty(), "series episodes load");
            controlled(22); screenshot("next-episode-control.png"); backToEpisodes(22);
            controlled(22);
            runOnMainSync(() -> { try { ((View) value(player, "nextEpisodeButton")).performClick(); } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> "tt0898266:9:23".equals(value(title, "videoId")) && (Boolean) value(title, "active"), "Next episode requests the exact following episode");
            controlled(23); endEpisode();
            await(() -> (Boolean) value(player, "playbackEnded") && ((View) value(player, "cancelAutoplay")).isShown() && (Boolean) value(player, "upNextShown"), "episode end shows Up next with a cancelable countdown");
            screenshot("next-episode-countdown.png");
            runOnMainSync(() -> { try { ((View) value(player, "cancelAutoplay")).performClick(); } catch (Exception error) { throw new RuntimeException(error); } });
            SystemClock.sleep(11000);
            await(() -> !player.isFinishing() && (Boolean) value(player, "autoNextCancelled") && PlaybackSession.current.videoId.equals("tt0898266:9:23"), "cancel prevents automatic episode changes");
            runOnMainSync(() -> { try { ((View) value(player, "pause")).performClick(); } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> video().isPlaying() && !(Boolean) value(player, "playbackEnded"), "Play restarts a completed episode");
            backToEpisodes(23); controlled(23); endEpisode();
            await(() -> "tt0898266:9:24".equals(value(title, "videoId")) && (Boolean) value(title, "active"), "countdown starts the next exact episode");
            controlled(24); backToEpisodes(24);
            await(() -> ((android.widget.Button) value(title, "play")).getLineCount() == 1, "Stremio action remains on one line");
            screenshot("back-to-episodes.png");
            result.putString("stream", "Passed " + checks + " episode flow checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            runOnMainSync(() -> { if (player != null) player.finish(); if (title != null) title.finish(); }); waitForIdleSync(); SystemClock.sleep(500);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            new java.io.File(getTargetContext().getCacheDir(), "episode-test.mkv").delete();
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
