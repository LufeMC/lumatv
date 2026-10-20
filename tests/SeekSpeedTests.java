package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.util.Collections;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONObject;

/** Measure first and cached seek previews with the same generated fixture. */
public class SeekSpeedTests extends TvTest {
    private PlayerActivity activity;
    private SourcesActivity anchor;
    private String phase = "after", step = "startup";
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private SeekState scrub() throws Exception { return (SeekState) value("scrub"); }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition test) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] good = {false}; Throwable[] failure = {null};
            runOnMainSync(() -> { try { good[0] = test.get(); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            if (good[0]) return; SystemClock.sleep(5);
        }
        throw new AssertionError("Seek preview deadline: " + step);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (args != null) phase = args.getString("phase", "after"); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(500); WatchSnapshot.capture(getTargetContext());
            anchor = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "Playback test"));
            SystemClock.sleep(700);
            java.io.File fixture = new java.io.File(getTargetContext().getCacheDir(), "seek-test.mkv");
            try (java.io.InputStream input = getContext().getAssets().open("sample.mkv")) { java.nio.file.Files.copy(input, fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(fixture).toString(); source.quality = 360; source.name = "Seek test";
            PlaybackSession.current = new PlaybackSession("tt0000008", "Seek preview test", Collections.singletonList(source), 360);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> player() != null && (Boolean) value("sourceValidated"));
            runOnMainSync(() -> { try { player().seekTo(30000); ((android.view.View) value("seek")).requestFocus(); } catch (Exception e) { throw new RuntimeException(e); } });
            await(() -> player().getCurrentPosition() >= 30000 && player().isPlaying());
            step = "first preview";
            long first = SystemClock.elapsedRealtime(); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            await(() -> scrub().active && ((SeekPreview) value("preview")).available(scrub().target));
            first = SystemClock.elapsedRealtime() - first;
            step = "adjacent preview";
            await(() -> scrub().active && ((SeekPreview) value("preview")).available(scrub().target + 10000));
            long[] target = {0}; runOnMainSync(() -> { try { target[0] = scrub().target + 10000; } catch (Exception e) { throw new RuntimeException(e); } });
            step = "cached preview";
            long cached = SystemClock.elapsedRealtime(); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            await(() -> scrub().active && scrub().target >= target[0] && ((SeekPreview) value("preview")).available(scrub().target));
            cached = SystemClock.elapsedRealtime() - cached;
            if (phase.equals("after")) {
                SystemClock.sleep(2000);
                runOnMainSync(() -> { try { if (!scrub().active) throw new AssertionError("Seek confirmed before three seconds"); } catch (Exception e) { throw new RuntimeException(e); } });
                key(KeyEvent.KEYCODE_DPAD_RIGHT);
                runOnMainSync(() -> { try { target[0] = scrub().target; } catch (Exception e) { throw new RuntimeException(e); } });
                SystemClock.sleep(2000);
                runOnMainSync(() -> { try { if (!scrub().active) throw new AssertionError("Repeated arrow did not restart the timer"); } catch (Exception e) { throw new RuntimeException(e); } });
                step = "automatic confirmation";
                await(() -> !scrub().active && player().isPlaying() && player().getCurrentPosition() >= target[0] - 2000);
                runOnMainSync(() -> { try { ((android.view.View) value("seek")).requestFocus(); } catch (Exception e) { throw new RuntimeException(e); } });
                key(KeyEvent.KEYCODE_DPAD_LEFT); await(() -> scrub().active); key(KeyEvent.KEYCODE_BACK);
                step = "automatic confirmation";
                await(() -> !scrub().active && player().isPlaying());
            } else key(KeyEvent.KEYCODE_BACK);
            JSONObject result = new JSONObject().put("phase", phase).put("first_preview_ms", first).put("cached_forward_preview_ms", cached).put("auto_confirmation_seconds", phase.equals("after") ? 3 : 0);
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "seek-" + phase + ".json"))) { file.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            output.putString("stream", "Passed seek preview checks. " + result + "\n");
        } catch (Throwable error) { output.putString("stream", "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            if (anchor != null) runOnMainSync(() -> anchor.finish()); waitForIdleSync(); SystemClock.sleep(300);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000008").commit();
            new java.io.File(getTargetContext().getCacheDir(), "seek-test.mkv").delete();
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
