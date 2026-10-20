package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import androidx.media3.exoplayer.ExoPlayer;

/** Verify the reported episode through the actual authorized account. */
public class LivePlaybackCheck extends Instrumentation {
    private int sustainedSeconds = 60;
    private Activity title;
    private PlayerActivity player;
    private final java.util.Map<String, java.util.Map<String, ?>> saved = new java.util.LinkedHashMap<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    private Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new android.view.KeyEvent(now, now, 0, code, 0), true);
        getUiAutomation().injectInputEvent(new android.view.KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getUiAutomation(); SourceProbe.peak.set(0);
            WatchSnapshot.capture(getTargetContext());
            for (String name : new String[]{ContinueWatching.PREFS, "playback-progress", "series-ui"}) saved.put(name, getTargetContext().getSharedPreferences(name, 0).getAll());
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            title = startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory")
                    .putExtra("imdb", "tt0898266").putExtra("resume_video", "tt0898266:9:22").putExtra("resume_position", 0L)
                    .putExtra("resume_duration", 1200000L).putExtra("auto_play", true));
            player = (PlayerActivity) waitForMonitorWithTimeout(monitor, 25000); removeMonitor(monitor);
            if (player == null) throw new AssertionError("No playable source was returned");
            long end = SystemClock.elapsedRealtime() + 65000; boolean[] good = {false}; long[] duration = {0}; int[] index = {-1};
            while (SystemClock.elapsedRealtime() < end) {
                runOnMainSync(() -> {
                    try {
                        ExoPlayer video = (ExoPlayer) field(player, "player");
                        good[0] = video != null && (Boolean) field(player, "sourceValidated") && video.isPlaying() && video.getDuration() > 600000;
                        if (good[0]) duration[0] = video.getDuration();
                        index[0] = PlaybackSession.current == null ? -1 : PlaybackSession.current.index;
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                if (good[0]) break; SystemClock.sleep(150);
            }
            if (!good[0]) throw new AssertionError("The available sources did not produce a verified full episode");
            for (int i = 0; i < sustainedSeconds; i++) {
                SystemClock.sleep(1000);
                if (i % 15 == 14) { Bundle update = new Bundle(); update.putString("stream", "Sustained playback: " + (i + 1) + " seconds.\n"); sendStatus(0, update); }
            }
            runOnMainSync(() -> {
                try {
                    ExoPlayer video = (ExoPlayer) field(player, "player");
                    good[0] = video != null && video.isPlaying() && video.getCurrentPosition() > 5000 && (Boolean) field(player, "sourceValidated");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (!good[0]) throw new AssertionError("The verified source did not sustain playback");
            runOnMainSync(() -> { try {
                if (!(Boolean) field(player, "videoRendered") || !(Boolean) field(player, "audioAdvancing")) throw new AssertionError("Video and audio were not both verified");
                java.lang.reflect.Method controls = PlayerActivity.class.getDeclaredMethod("showControls"); controls.setAccessible(true); controls.invoke(player);
                ((android.view.View) field(player, "pause")).requestFocus();
            } catch (Exception error) { throw new RuntimeException(error); } });
            key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            long previewStart = SystemClock.elapsedRealtime(); boolean[] previewReady = {false};
            while (SystemClock.elapsedRealtime() - previewStart < 2700) {
                runOnMainSync(() -> { try { SeekState seek = (SeekState) field(player, "scrub"); previewReady[0] = seek.active && ((SeekPreview) field(player, "preview")).available(seek.target); } catch (Exception error) { throw new RuntimeException(error); } });
                if (previewReady[0]) break; SystemClock.sleep(10);
            }
            long previewMs = SystemClock.elapsedRealtime() - previewStart;
            long cachedPreviewMs = -1;
            SystemClock.sleep(50);
            android.graphics.Bitmap previewImage = getUiAutomation().takeScreenshot();
            if (!previewReady[0]) throw new AssertionError("The real episode preview did not appear before automatic confirmation");
            if (previewReady[0]) {
                long warmDeadline = Math.min(SystemClock.elapsedRealtime() + 1000, previewStart + 2500); boolean[] warm = {false};
                while (SystemClock.elapsedRealtime() < warmDeadline) {
                    runOnMainSync(() -> { try { SeekState seek = (SeekState) field(player, "scrub"); warm[0] = seek.active && ((SeekPreview) field(player, "preview")).available(seek.target + 10000); } catch (Exception error) { throw new RuntimeException(error); } });
                    if (warm[0]) break; SystemClock.sleep(10);
                }
                if (warm[0]) {
                    long cachedStart = SystemClock.elapsedRealtime(); key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    runOnMainSync(() -> { try { SeekState seek = (SeekState) field(player, "scrub"); warm[0] = seek.active && ((SeekPreview) field(player, "preview")).available(seek.target); } catch (Exception error) { throw new RuntimeException(error); } });
                    if (warm[0]) cachedPreviewMs = SystemClock.elapsedRealtime() - cachedStart;
                }
            }
            key(android.view.KeyEvent.KEYCODE_BACK);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "live-seek-preview.png"))) { previewImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { previewImage.recycle(); }
            waitForIdleSync();
            android.graphics.Bitmap shot = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "verified-episode.png"))) { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { shot.recycle(); }
            IntroSkip[] introState = {null};
            runOnMainSync(() -> { try { introState[0] = (IntroSkip) field(player, "intro"); } catch (Exception error) { throw new RuntimeException(error); } });
            if (introState[0].marker == null) throw new AssertionError("The public BBT intro marker did not load");
            long introEnd = introState[0].marker.end;
            runOnMainSync(() -> { try { ExoPlayer video = (ExoPlayer) field(player, "player"); video.seekTo(introState[0].marker.start + 500); video.setPlayWhenReady(true); } catch (Exception error) { throw new RuntimeException(error); } });
            long introSeekAt = SystemClock.elapsedRealtime(); long introDeadline = introSeekAt + 18000; boolean[] introSkipped = {false}, introVisible = {false}; boolean introCaptured = false;
            while (SystemClock.elapsedRealtime() < introDeadline) {
                runOnMainSync(() -> { try { introVisible[0] = introState[0].visible && ((ExoPlayer) field(player, "player")).isPlaying(); introSkipped[0] = introState[0].skipped && ((ExoPlayer) field(player, "player")).getCurrentPosition() >= introEnd && ((ExoPlayer) field(player, "player")).isPlaying(); } catch (Exception error) { throw new RuntimeException(error); } });
                if (introVisible[0] && !introCaptured && SystemClock.elapsedRealtime() - introSeekAt > 3000) {
                    SystemClock.sleep(500);
                    android.graphics.Bitmap introImage = getUiAutomation().takeScreenshot();
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "live-skip-intro.png"))) { introImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { introImage.recycle(); }
                    introCaptured = true;
                }
                if (introSkipped[0]) break; SystemClock.sleep(50);
            }
            if (!introSkipped[0]) throw new AssertionError("The real episode did not skip its marked intro");
            result.putString("stream", "Verified full episode. Duration: " + duration[0] + " ms; observed: " + sustainedSeconds + " seconds; source index: " + index[0] + "; peak parallel checks: " + SourceProbe.peak.get() + "; real preview: " + previewMs + " ms; cached real preview: " + cachedPreviewMs + " ms; video and audio output verified; public intro marker skipped.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "; peak parallel checks: " + SourceProbe.peak.get() + ".\n"); }
        finally {
            runOnMainSync(() -> { if (player != null) player.finish(); if (title != null) title.finish(); }); waitForIdleSync(); SystemClock.sleep(500);
            for (java.util.Map.Entry<String, java.util.Map<String, ?>> preference : saved.entrySet()) {
                android.content.SharedPreferences.Editor edit = getTargetContext().getSharedPreferences(preference.getKey(), 0).edit().clear();
                for (java.util.Map.Entry<String, ?> item : preference.getValue().entrySet()) {
                    Object value = item.getValue();
                    if (value instanceof String) edit.putString(item.getKey(), (String) value);
                    else if (value instanceof Long) edit.putLong(item.getKey(), (Long) value);
                    else if (value instanceof Integer) edit.putInt(item.getKey(), (Integer) value);
                    else if (value instanceof Boolean) edit.putBoolean(item.getKey(), (Boolean) value);
                }
                edit.commit();
            }
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            finish(result.getString("stream", "").startsWith("Verified") ? -1 : 0, result); }
    }
}
