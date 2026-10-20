package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Exercise player recovery with denied, missing, and damaged test media. */
public class PlayerFailureTests extends Instrumentation {
    private PlayerActivity activity;
    private SourcesActivity anchor;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    private Field field(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private ExoPlayer player() throws Exception { return (ExoPlayer) field("player").get(activity); }
    private AddonStreams.Source source(String path) {
        AddonStreams.Source s = new AddonStreams.Source(); s.url = "content://io.freemytv.tests.media/" + path;
        s.name = "Controlled failure test"; s.detail = "Test media"; s.addon = "Test"; s.quality = 360; return s;
    }
    private interface Condition { boolean get() throws Exception; }
    private void waitFor(Condition condition, String name) throws Exception {
        long end = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] done = {false}; Throwable[] error = {null};
            runOnMainSync(() -> { try { done[0] = condition.get(); } catch (Throwable e) { error[0] = e; } });
            if (error[0] != null) throw new AssertionError(name + ": " + error[0].getClass().getSimpleName());
            if (done[0]) { checks++; return; } SystemClock.sleep(100);
        }
        throw new AssertionError(name);
    }
    private void launch(List<AddonStreams.Source> queue, boolean pending) { launch(queue, pending, 0); }
    private void launch(List<AddonStreams.Source> queue, boolean pending, long expected) {
        PlaybackSession.current = new PlaybackSession("tt0000009", "Playback recovery test", queue, 360);
        PlaybackSession.current.discovering = pending; PlaybackSession.current.expectedDuration = expected;
        activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void close() { if (activity != null) runOnMainSync(() -> activity.finish()); activity = null; SystemClock.sleep(350); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(500); WatchSnapshot.capture(getTargetContext());
            anchor = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "Playback test"));
            SystemClock.sleep(700);
            List<AddonStreams.Source> queue = new ArrayList<>();
            queue.add(source("missing.mkv")); queue.add(source("denied.mkv")); queue.add(source("corrupt.mkv")); queue.add(source("truncated.mkv")); queue.add(source("sample.mkv"));
            launch(queue, false);
            waitFor(() -> PlaybackSession.current.index == 4 && player() != null && player().isPlaying(), "recover after missing, denied, corrupt, and truncated sources");
            close(); queue.clear(); queue.add(source("corrupt.mkv")); launch(queue, true);
            waitFor(() -> player() == null && field("waitingForSources").getBoolean(activity), "wait after initial sources fail");
            queue.add(source("sample.mkv")); runOnMainSync(() -> PlaybackSession.current.updateSources(queue, false));
            waitFor(() -> PlaybackSession.current.index == 1 && player() != null && player().isPlaying(), "late source starts after failed source");
            close(); queue.clear(); queue.add(source("corrupt.mkv")); launch(queue, true);
            waitFor(() -> player() == null && field("waitingForSources").getBoolean(activity), "wait for discovery deadline");
            runOnMainSync(() -> PlaybackSession.current.updateSources(queue, false));
            waitFor(() -> player() == null && field("exhausted").getBoolean(activity), "empty late discovery stops retrying");
            close(); queue.clear(); queue.add(source("sample.mkv?first")); queue.add(source("sample.mkv?fallback")); launch(queue, false);
            waitFor(() -> player() != null && player().isPlaying(), "start before buffer stall");
            runOnMainSync(() -> {
                try {
                    ExoPlayer p = player(); p.setMediaItem(androidx.media3.common.MediaItem.fromUri("content://io.freemytv.tests.media/sample.mkv?stall")); p.prepare(); p.setPlayWhenReady(true);
                    if (p.getPlaybackState() != Player.STATE_BUFFERING) throw new AssertionError("Test did not enter buffering");
                    field("bufferingSince").setLong(activity, SystemClock.elapsedRealtime() - 21000);
                    ((Runnable) field("tick").get(activity)).run();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            waitFor(() -> PlaybackSession.current.index == 1 && player() != null && player().isPlaying(), "buffering deadline switches source");
            close(); queue.clear(); queue.add(source("placeholder.mkv")); queue.add(source("audio-only.mkv")); queue.add(source("video-only.mkv")); queue.add(source("verified.mkv"));
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000009").commit();
            launch(queue, false, 1200000);
            waitFor(() -> PlaybackSession.current.index == 3 && player() != null && player().isPlaying() && field("sourceValidated").getBoolean(activity), "reject a short error clip, audio-only source, and video-only source");
            waitFor(() -> !getTargetContext().getSharedPreferences("playback-progress", 0).contains("tt0000009") || getTargetContext().getSharedPreferences("playback-progress", 0).getLong("tt0000009", 0) < 5000, "placeholder does not replace saved progress");
            waitFor(() -> player().getDuration() > 1000000, "accepted source has the expected full duration");
            waitFor(() -> field("videoRendered").getBoolean(activity) && field("audioAdvancing").getBoolean(activity), "accepted source has decoded video and advancing audio output");
            close(); queue.clear(); queue.add(source("sample.mkv?audio-stall")); queue.add(source("sample.mkv?audio-recovery")); launch(queue, false);
            waitFor(() -> player() != null && field("sourceValidated").getBoolean(activity), "start before missing audio output");
            runOnMainSync(() -> { try {
                field("audioAdvancing").setBoolean(activity, false); field("sourceValidated").setBoolean(activity, false);
                field("sourceOpenedAt").setLong(activity, SystemClock.elapsedRealtime() - 9000); ((Runnable) field("tick").get(activity)).run();
            } catch (Exception error) { throw new RuntimeException(error); } });
            waitFor(() -> PlaybackSession.current.index == 1 && player() != null && field("sourceValidated").getBoolean(activity), "missing audio output triggers source recovery");
            output.putString("stream", "Passed " + checks + " player failure recovery checks.\n");
        } catch (Throwable error) {
            if (activity != null) runOnMainSync(() -> { try {
                ExoPlayer p = player(); Bundle debug = new Bundle();
                debug.putString("stream", "Player diagnostic: index=" + (PlaybackSession.current == null ? -1 : PlaybackSession.current.index)
                    + "; state=" + (p == null ? -1 : p.getPlaybackState()) + "; play=" + (p != null && p.getPlayWhenReady())
                    + "; suppression=" + (p == null ? -1 : p.getPlaybackSuppressionReason()) + "; wanted=" + field("wanted").getBoolean(activity)
                    + "; failures=" + (PlaybackSession.current == null ? "none" : PlaybackSession.current.failureNotes)
                    + "; position=" + (p == null ? -1 : p.getCurrentPosition()) + "; video=" + field("videoRendered").getBoolean(activity)
                    + "; audio=" + field("audioAdvancing").getBoolean(activity) + "; validated=" + field("sourceValidated").getBoolean(activity) + "\n"); sendStatus(0, debug);
                if (p != null) for (androidx.media3.common.Tracks.Group group : p.getCurrentTracks().getGroups()) for (int i = 0; i < group.length; i++) {
                    Bundle track = new Bundle(); track.putString("stream", "Track: type=" + group.getType() + "; selected=" + group.isTrackSelected(i) + "; supported=" + group.isTrackSupported(i) + "; mime=" + group.getTrackFormat(i).sampleMimeType + "\n"); sendStatus(0, track);
                }
            } catch (Exception ignored) {} });
            output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            close(); if (anchor != null) runOnMainSync(() -> anchor.finish()); waitForIdleSync(); SystemClock.sleep(300);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000009").commit();
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
