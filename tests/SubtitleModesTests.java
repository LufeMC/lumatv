package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/** Verify forced captions and temporary captions after a rewind. */
public class SubtitleModesTests extends TvTest {
    private PlayerActivity activity;
    private HomeActivity home;
    private int checks;
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private interface Action { void run() throws Exception; }
    private void main(Action action) {
        Throwable[] error = {null}; runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition, String name) {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; main(() -> pass[0] = condition.get());
            if (pass[0]) { checks++; return; } SystemClock.sleep(50);
        }
        throw new AssertionError(name);
    }
    private boolean cue(String text) throws Exception {
        for (androidx.media3.common.text.Cue c : player().getCurrentCues().cues) if (c.text != null && c.text.toString().contains(text)) return true;
        return false;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            getUiAutomation();
            getTargetContext().getSharedPreferences(Prefs.PLAYBACK, 0).edit().putString(Prefs.SUBTITLE_MODE, "forced")
                    .putString(Prefs.SUBTITLE_LANGUAGE, "en").putString("audio-language", "en").commit();
            java.io.File file = new java.io.File(getTargetContext().getCacheDir(), "forced-captions.mkv");
            try (java.io.InputStream in = getContext().getAssets().open("forced-subtitles.mkv")) {
                java.nio.file.Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(file).toString(); source.addon = "Test"; source.name = "Subtitle modes";
            PlaybackSession.current = new PlaybackSession("tt0000042", "Subtitle modes test", Collections.singletonList(source), -1);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> player() != null && (Boolean) value("sourceValidated") && player().isPlaying(), "video and audio start");
            await(() -> cue("Forced dialogue test") && value("wantText") == null, "forced captions appear with full captions off");
            main(() -> player().seekTo(22000));
            await(() -> player().getCurrentPosition() >= 22000 && player().getCurrentCues().cues.isEmpty(), "full captions remain off after the forced passage");
            main(() -> {
                Method replay = PlayerActivity.class.getDeclaredMethod("replaySubtitles", long.class); replay.setAccessible(true); replay.invoke(activity, 30000L);
            });
            await(() -> cue("Subtitle test: English") && (Long) value("replayUntil") == 30000L, "a short rewind displays temporary English captions");
            main(() -> player().seekTo(30500));
            await(() -> (Long) value("replayUntil") < 0 && player().getCurrentCues().cues.isEmpty(), "captions return to Off after playback reaches the previous position");
            await(() -> !player().getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && value("wantText") == null,
                    "forced captions remain available after temporary captions stop");
            output.putString("stream", "Passed " + checks + " subtitle mode checks.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            main(() -> { if (activity != null) activity.finish(); if (home != null) home.finish(); });
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
