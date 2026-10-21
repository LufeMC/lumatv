package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;

/** Measure first-use prepared frames on a real stream and preserve the user's watch data. */
public class PreparedPreviewTests extends TvTest {
    private SourcesActivity title;
    private PlayerActivity activity;
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void main(Runnable task) {
        Throwable[] failure = {null}; super.runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private SeekPreview preview() throws Exception { return (SeekPreview) value("preview"); }
    private SeekState scrub() throws Exception { return (SeekState) value("scrub"); }
    private PreparedPreviews prepared() throws Exception { return (PreparedPreviews) value("preparedPreviews"); }
    private String codecName(PreparedPreviews previews) throws Exception {
        Field codec = PreparedPreviews.class.getDeclaredField("codec"); codec.setAccessible(true); return String.valueOf(codec.get(previews));
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, long timeout, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; Throwable[] failed = {null}; main(() -> { try { pass[0] = condition.get(); } catch (Throwable error) { failed[0] = error; } });
            if (failed[0] != null) throw new AssertionError(label + ": " + failed[0]);
            if (pass[0]) return; SystemClock.sleep(5);
        }
        throw new AssertionError(label);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis(); getUiAutomation().injectInputEvent(new KeyEvent(now, now, 0, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true);
    }
    private void progress(String text) { Bundle update = new Bundle(); update.putString("stream", text + "\n"); sendStatus(0, update); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); JSONObject report = new JSONObject();
        try {
            getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            java.io.File[] oldFrames = new java.io.File(getTargetContext().getCacheDir(), "seek-previews").listFiles();
            if (oldFrames != null) for (java.io.File frame : oldFrames) frame.delete();
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory").putExtra("imdb", "tt0898266")
                    .putExtra("resume_video", "tt0898266:9:22").putExtra("resume_position", 0L).putExtra("resume_duration", 1200000L).putExtra("auto_play", true));
            activity = (PlayerActivity) waitForMonitorWithTimeout(monitor, 25000); removeMonitor(monitor); if (activity == null) throw new AssertionError("No player");
            await(() -> player() != null && player().isPlaying() && (Boolean) value("sourceValidated"), 65000, "verified video and audio playback");
            long start = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime(); long[] original = {0}, minMemory = {Long.MAX_VALUE}; int[] droppedBefore = {0};
            main(() -> { try { original[0] = player().getCurrentPosition(); if (player().getVideoDecoderCounters() != null) droppedBefore[0] = player().getVideoDecoderCounters().droppedBufferCount; } catch (Exception error) { throw new RuntimeException(error); } });
            JSONArray readiness = new JSONArray();
            for (int second = 1; second <= 60; second++) {
                SystemClock.sleep(1000);
                android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager) getTargetContext().getSystemService(android.content.Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
                minMemory[0] = Math.min(minMemory[0], memory.availMem);
                if (second % 15 == 0) {
                    final int sampleSecond = second; String[] state = {""}; main(() -> { try { PreparedPreviews p = prepared(); state[0] = p == null ? "none" : p.state + ", frames=" + p.prepared + ", network=" + p.networkBytes + ", codec=" + codecName(p) + ", heap_free=" + Runtime.getRuntime().freeMemory(); readiness.put(new JSONObject().put("second", sampleSecond).put("state", state[0])); } catch (Exception error) { throw new RuntimeException(error); } });
                    progress("Background preparation at " + second + " seconds: " + state[0]);
                }
            }
            long[] position = {0}; int[] droppedAfter = {0};
            main(() -> { try {
                if (!player().isPlaying() || !(Boolean) value("sourceValidated")) throw new AssertionError("Preparation interrupted playback");
                position[0] = player().getCurrentPosition(); if (player().getVideoDecoderCounters() != null) droppedAfter[0] = player().getVideoDecoderCounters().droppedBufferCount;
                java.lang.reflect.Method controls = PlayerActivity.class.getDeclaredMethod("showControls"); controls.setAccessible(true); controls.invoke(activity);
                ((android.view.View) value("seek")).requestFocus();
            } catch (Exception error) { throw new RuntimeException(error); } });
            report.put("observed_ms", SystemClock.elapsedRealtime() - start).put("advanced_ms", position[0] - original[0])
                    .put("cpu_ms", android.os.Process.getElapsedCpuTime() - cpu).put("min_available_memory_bytes", minMemory[0])
                    .put("dropped_frames", droppedAfter[0] - droppedBefore[0]).put("readiness", readiness);
            if (position[0] - original[0] < 55000) throw new AssertionError("Playback lost more than five seconds during preparation");
            JSONArray samples = new JSONArray(), selectedSamples = new JSONArray(), deliverySamples = new JSONArray();
            report.put("remote_to_draw_ms", samples).put("selected_to_draw_ms", selectedSamples).put("input_delivery_ms", deliverySamples);
            for (int code : new int[]{KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT}) {
                SystemClock.sleep(200);
                long[] target = {0}; main(() -> { try {
                    long base = scrub().active ? scrub().target : player().getCurrentPosition();
                    target[0] = Math.max(0, base + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10000 : -10000));
                    if (!preview().available(target[0])) throw new AssertionError("The next frame was not prepared before input: " + target[0]);
                } catch (Exception error) { throw new RuntimeException(error); } });
                long pressed = SystemClock.elapsedRealtime(); key(code);
                await(() -> scrub().active && preview().drawnPosition == SeekPreview.bucket(scrub().target) && preview().drawnAt >= pressed, 1000, "prepared preview is drawn");
                long[] drawn = {0}; main(() -> { try { drawn[0] = preview().drawnAt; selectedSamples.put(drawn[0] - preview().selectedAt);
                    deliverySamples.put(activity.seekInputAt - pressed);
                    if ((Boolean) value("previewMovedPlayer")) throw new AssertionError("Preview moved the playback decoder"); } catch (Exception error) { throw new RuntimeException(error); } });
                long elapsed = drawn[0] - pressed; samples.put(elapsed);
            }
            long[] canceledPosition = {0}; main(() -> { try { canceledPosition[0] = scrub().original; } catch (Exception error) { throw new RuntimeException(error); } });
            key(KeyEvent.KEYCODE_BACK);
            await(() -> !scrub().active && player().isPlaying() && Math.abs(player().getCurrentPosition() - canceledPosition[0]) < 2000, 5000, "Back resumes the original position");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); long[] selected = {0}; main(() -> { try { selected[0] = scrub().target; } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> !scrub().active && player().isPlaying() && player().getCurrentPosition() >= selected[0] - 500, 7000, "automatic confirmation resumes at the selected time");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot(); key(KeyEvent.KEYCODE_BACK);
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "prepared-preview.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); } finally { image.recycle(); }
            main(() -> { try { PreparedPreviews p = prepared(); report.put("prepared_frames", p.prepared).put("network_bytes", p.networkBytes).put("read_bytes", p.readBytes).put("disk_hits", p.diskHits).put("state", p.state);
                Field codec = PreparedPreviews.class.getDeclaredField("codec"); codec.setAccessible(true); report.put("codec", codec.get(p)); } catch (Exception error) { throw new RuntimeException(error); } });
            for (int i = 0; i < samples.length(); i++) if (samples.getLong(i) > 100) throw new AssertionError("Prepared preview exceeded 100 ms: " + samples.getLong(i));
            output.putString("stream", "Passed prepared preview checks. " + report + "\n");
        } catch (Throwable error) { try { report.put("failure", error.getMessage()); } catch (Exception ignored) {} output.putString("stream", "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "; " + report + "\n"); }
        finally {
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "prepared-preview-results.json"))) { file.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            main(() -> { if (activity != null) activity.finish(); if (title != null) title.finish(); }); waitForIdleSync(); SystemClock.sleep(1000);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
