package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Verify that a ready source starts while another check remains blocked. */
public class SourceRaceTests extends TvTest {
    private PlayerActivity activity;
    private HomeActivity home;
    private ParallelSourceChecks pool;
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void main(Runnable action) {
        Throwable[] error = {null}; runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); CountDownLatch blocked = new CountDownLatch(1);
        try {
            getUiAutomation();
            home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            java.io.File file = new java.io.File(getTargetContext().getCacheDir(), "race-anchor.mkv");
            try (java.io.InputStream in = getContext().getAssets().open("verified.mkv")) {
                java.nio.file.Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            AddonStreams.Source local = new AddonStreams.Source(); local.url = android.net.Uri.fromFile(file).toString(); local.addon = "Test"; local.name = "Anchor";
            PlaybackSession.current = new PlaybackSession("tt0000041", "Source race test", java.util.Collections.singletonList(local), -1);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1000);
            AddonStreams.Source slow = new AddonStreams.Source(); slow.url = "https://example.invalid/blocked"; slow.quality = 180; slow.addon = "Test"; slow.name = "Blocked";
            AddonStreams.Source fast = new AddonStreams.Source(); fast.url = "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"; fast.quality = 180; fast.addon = "Test"; fast.name = "Ready";
            pool = new ParallelSourceChecks((source, expected) -> {
                if (source == slow) try { blocked.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ParallelSourceChecks.Result(SourceProbe.AVAILABLE, "fixture");
            });
            long start = SystemClock.elapsedRealtime();
            main(() -> { try {
                Field f = PlayerActivity.class.getDeclaredField("sourceChecks"); f.setAccessible(true); f.set(activity, pool);
                PlaybackSession.current.sources.clear(); PlaybackSession.current.sources.addAll(Arrays.asList(slow, fast)); PlaybackSession.current.index = -1;
                Method next = PlayerActivity.class.getDeclaredMethod("next"); next.setAccessible(true); next.invoke(activity);
            } catch (Exception e) { throw new RuntimeException(e); } });
            boolean[] playing = {false};
            while (SystemClock.elapsedRealtime() - start < 9000) {
                main(() -> { try {
                    ExoPlayer player = (ExoPlayer) value("player");
                    playing[0] = PlaybackSession.current.sources.get(PlaybackSession.current.index) == fast
                            && player != null && player.isPlaying() && (Boolean) value("sourceValidated");
                } catch (Exception e) { throw new RuntimeException(e); } });
                if (playing[0]) break;
                SystemClock.sleep(50);
            }
            if (!playing[0] || blocked.getCount() != 1) throw new AssertionError("Playback waited for a blocked source check");
            output.putString("stream", "Passed source race playback. Verified video and audio started in " + (SystemClock.elapsedRealtime() - start) + " ms while the first check remained blocked.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED source race: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            blocked.countDown(); if (pool != null) pool.close();
            main(() -> { if (activity != null) activity.finish(); if (home != null) home.finish(); });
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
