package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import androidx.media3.exoplayer.ExoPlayer;

/** Compare public marker times with displayed episode frames. */
public class IntroTimingTests extends Instrumentation {
    private SourcesActivity title;
    private PlayerActivity activity;
    private Object value(String name) throws Exception { Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + 65000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; runOnMainSync(() -> { try { pass[0] = condition.get(); } catch (Exception error) { throw new RuntimeException(error); } });
            if (pass[0]) return; SystemClock.sleep(50);
        }
        throw new AssertionError("Frame deadline");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory")
                    .putExtra("imdb", "tt0898266").putExtra("resume_video", "tt0898266:9:22").putExtra("resume_position", 0L)
                    .putExtra("resume_duration", 1200000L).putExtra("auto_play", true));
            activity = (PlayerActivity) waitForMonitorWithTimeout(monitor, 25000); removeMonitor(monitor);
            if (activity == null) throw new AssertionError("No player");
            await(() -> player() != null && (Boolean) value("sourceValidated") && player().isPlaying());
            runOnMainSync(() -> { try { ((IntroSkip) value("intro")).keep(); } catch (Exception error) { throw new RuntimeException(error); } });
            for (long position : new long[]{90000, 120000, 138000, 148000, 158000, 168000}) {
                runOnMainSync(() -> { try { player().seekTo(position); player().play(); } catch (Exception error) { throw new RuntimeException(error); } });
                await(() -> player().isPlaying() && player().getCurrentPosition() >= position + 2000);
                runOnMainSync(() -> { try { java.lang.reflect.Method method = PlayerActivity.class.getDeclaredMethod("showControls"); method.setAccessible(true); method.invoke(activity); } catch (Exception error) { throw new RuntimeException(error); } });
                SystemClock.sleep(700);
                android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "intro-timing-" + position + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); } finally { image.recycle(); }
            }
            output.putString("stream", "Captured six episode timing frames.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED: " + error.getMessage() + "\n"); }
        finally {
            runOnMainSync(() -> { if (activity != null) activity.finish(); if (title != null) title.finish(); }); waitForIdleSync(); SystemClock.sleep(700);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            finish(output.getString("stream", "").startsWith("Captured") ? -1 : 0, output);
        }
    }
}
