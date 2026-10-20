package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * The redesigned player with generated media: the screen sleeps when paused, the first Back hides the controls,
 * Skip recap, Up next when the credits begin (and the card turns into Up next), the same source after a network drop,
 * and subtitle timing saved per release.
 */
public class PlayerFlowTests extends TvTest {
    private PlayerActivity activity;
    private SourcesActivity anchor;
    private int checks;
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void set(String name, Object v) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, v); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = PlayerActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(activity, args);
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition test, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; Throwable[] failure = {null};
            runOnMainSync(() -> { try { pass[0] = test.get(); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(label + ": " + failure[0]);
            if (pass[0]) { checks++; return; } SystemClock.sleep(50);
        }
        throw new AssertionError(label);
    }
    private interface Action { void run() throws Exception; }
    private void main(Action action) {
        runOnMainSync(() -> { try { action.run(); } catch (Exception error) { throw new RuntimeException(error); } });
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true);
        SystemClock.sleep(200);
    }
    private boolean awake() { return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0; }
    private void shot(String name) throws Exception {
        SystemClock.sleep(300);
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        java.io.File folder = new java.io.File(getTargetContext().getExternalFilesDir(null), "design-review"); folder.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.recycle(); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String release = "";
        try {
            Ui.init(getTargetContext()); getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            anchor = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "Playback test"));
            SystemClock.sleep(700);
            java.io.File fixtures = new java.io.File(getTargetContext().getCacheDir(), "test-fixtures"); fixtures.mkdirs();
            for (String name : new String[]{"verified.mkv", "subtitle-en.srt"})
                try (java.io.InputStream input = getContext().getAssets().open(name)) { java.nio.file.Files.copy(input, new java.io.File(fixtures, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            AddonStreams.Source source = new AddonStreams.Source();
            source.url = android.net.Uri.fromFile(new java.io.File(fixtures, "verified.mkv")).toString(); source.quality = 360; source.name = "Flow test"; source.addon = "Test";
            source.filename = "Playback.Test.S01E01.mkv";
            release = source.release();
            ContinueWatching.Entry entry = new ContinueWatching.Entry();
            entry.id = "local:tt0000020:1:1"; entry.video = "tt0000020:1:1"; entry.imdb = "tt0000020"; entry.title = "Player flow test"; entry.kind = "tv";
            entry.season = 1; entry.episode = 1; entry.subtitle = "Pilot";
            PlaybackSession.current = new PlaybackSession("tt0000020:1:1", "Player flow test · S1 E1 · Pilot", Collections.singletonList(source), 360);
            PlaybackSession.current.entry = entry;
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> player() != null && (Boolean) value("sourceValidated") && player().isPlaying(), "video and audio start");
            await(this::awake, "the screen stays on while playing");
            main(() -> call("showControls", new Class<?>[0])); shot("player-controls");
            main(() -> call("chooseTracks", new Class<?>[]{int.class}, androidx.media3.common.C.TRACK_TYPE_AUDIO)); shot("player-tracks"); key(KeyEvent.KEYCODE_BACK);
            main(() -> call("chooseSource", new Class<?>[0])); shot("player-sources"); key(KeyEvent.KEYCODE_BACK);
            main(() -> call("playbackOptions", new Class<?>[0])); shot("player-more"); key(KeyEvent.KEYCODE_BACK);
            main(() -> { PlaybackSession.current.sources.add(source); set("failure", "silent"); call("showRecover", new Class<?>[0]); });
            shot("player-recover");
            main(() -> { ((View) value("recover")).setVisibility(View.GONE); PlaybackSession.current.sources.remove(1); set("failure", ""); });
            main(() -> call("togglePause", new Class<?>[0]));
            await(() -> !awake(), "the screen may sleep while paused");
            main(() -> call("togglePause", new Class<?>[0]));
            await(() -> awake() && player().isPlaying(), "awake again when playing");

            main(() -> call("showControls", new Class<?>[0]));
            key(KeyEvent.KEYCODE_BACK);
            await(() -> ((View) value("overlay")).getVisibility() != View.VISIBLE && !activity.isFinishing(), "the first Back hides the controls");

            // Skip recap.
            main(() -> {
                IntroSkip recap = (IntroSkip) value("recap"); recap.marker = new IntroMarkers.Marker(5000, 25000, 1);
                recap.kept = false; recap.skipped = false; player().seekTo(6000); player().setPlayWhenReady(true);
            });
            await(() -> ((View) value("introActions")).getVisibility() == View.VISIBLE
                    && ((PlayerViews.Pill) value("skipIntro")).getText().toString().equals(Ui.tr("Pular resumo", "Skip recap")), "Skip recap shows");
            shot("player-recap");
            main(() -> ((View) value("skipIntro")).performClick());
            await(() -> ((IntroSkip) value("recap")).skipped && player().getCurrentPosition() >= 25000, "Skip recap jumps past it");

            // Network drop on a source that already played: same source, waiting pill, no switch.
            main(() -> { set("online", false); call("networkFailure", new Class<?>[]{AddonStreams.Source.class}, source); });
            await(() -> (Boolean) value("waitingForNetwork") && ((View) value("networkPill")).getVisibility() == View.VISIBLE
                    && player() == null && PlaybackSession.current.index == 0, "waits for the network on the same source");
            shot("player-network");
            main(() -> call("onNetwork", new Class<?>[]{boolean.class}, true));
            await(() -> player() != null && (Boolean) value("sourceValidated") && PlaybackSession.current.index == 0
                    && !(Boolean) value("waitingForNetwork"), "the same source resumes when the network returns");

            // Subtitle timing, saved for this release.
            main(() -> {
                AddonSubtitles.Subtitle subtitle = new AddonSubtitles.Subtitle();
                subtitle.url = android.net.Uri.fromFile(new java.io.File(fixtures, "subtitle-en.srt")).toString(); subtitle.language = "en";
                subtitle.name = "Flow test English"; subtitle.mime = "application/x-subrip";
                call("useExternal", new Class<?>[]{AddonSubtitles.Subtitle.class, boolean.class}, subtitle, false);
            });
            await(() -> value("externalCues") != null, "add-on subtitle parsed");
            main(() -> call("showTiming", new Class<?>[0]));
            shot("player-subtitle-timing");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
            final String saved = release;
            await(() -> Prefs.subtitleOffset(getTargetContext(), saved) == 200 && ((View) value("timing")).getVisibility() != View.VISIBLE, "timing saved per release");

            // Automatic timing respects the setting and a manual adjustment.
            final SubtitleCues[] original = {null};
            main(() -> {
                original[0] = (SubtitleCues) value("externalCues");
                set("externalCues", SubtitleCues.parse("1\n00:20:40,000 --> 00:20:50,000\nTest\n"));
                getTargetContext().getSharedPreferences(Prefs.PLAYBACK, 0).edit().putBoolean(Prefs.AUTO_SYNC, false).commit();
                call("applyDrift", new Class<?>[]{long.class}, 1200000L);
            });
            await(() -> ((SubtitleCues) value("externalCues")).scale == 1, "automatic timing stays off when disabled");
            main(() -> {
                getTargetContext().getSharedPreferences(Prefs.PLAYBACK, 0).edit().putBoolean(Prefs.AUTO_SYNC, true).commit();
                call("applyDrift", new Class<?>[]{long.class}, 1200000L);
            });
            await(() -> ((SubtitleCues) value("externalCues")).scale == 1, "a manual timing adjustment takes priority");
            main(() -> { Prefs.setSubtitleOffset(getTargetContext(), saved, 0); call("applyDrift", new Class<?>[]{long.class}, 1200000L); });
            await(() -> ((SubtitleCues) value("externalCues")).scale != 1 && ((View) value("synced")).getVisibility() == View.VISIBLE, "automatic speed correction offers Undo");
            shot("player-subtitles-synced");
            main(() -> call("undoSync", new Class<?>[0]));
            await(() -> ((SubtitleCues) value("externalCues")).scale == 1 && ((View) value("synced")).getVisibility() != View.VISIBLE, "Undo restores subtitle speed");
            main(() -> set("externalCues", original[0]));

            // Credits: Up next with a countdown; the card turns into Up next.
            main(() -> {
                SeriesEpisodes.Episode next = new SeriesEpisodes.Episode(); next.season = 1; next.number = 2; next.name = "Second"; next.minutes = 20; next.still = "";
                set("nextEpisode", next); set("nextLoaded", true);
                IntroMarkers.Segments segments = new IntroMarkers.Segments(); segments.creditsStart = player().getDuration() - 60000;
                set("segments", segments);
                player().seekTo(player().getDuration() - 59000); player().setPlayWhenReady(true);
            });
            await(() -> (Boolean) value("upNextShown") && (Boolean) value("finishedRecorded") && ((View) value("cancelAutoplay")).isShown(), "Up next when the credits begin");
            shot("player-up-next");
            await(() -> {
                for (ContinueWatching.Entry e : ContinueWatching.cached(getTargetContext())) if (e.imdb.equals("tt0000020")) return e.upNext && e.episode == 2;
                return false;
            }, "the show's card becomes Up next");
            check(ContinueWatching.watched(getTargetContext(), "tt0000020").contains("1:1"), "the episode counts as watched at the credits");
            main(() -> ((View) value("cancelAutoplay")).performClick());
            await(() -> !(Boolean) value("upNextShown") && (Boolean) value("autoNextCancelled") && player().isPlaying(), "Watch the credits returns to full screen");
            main(() -> {
                PlaybackMemory.setAutoplayChain(getTargetContext(), 3);
                TestState.playbackHour = 23;
                set("autoNextCancelled", false);
                call("startUpNext", new Class<?>[]{boolean.class}, false);
            });
            await(() -> (Boolean) value("upNextShown") && !(Boolean) value("stillShown"), "three episodes before midnight use normal Up next");
            main(() -> { call("closeUpNext", new Class<?>[0]); TestState.playbackHour = 0; call("startUpNext", new Class<?>[]{boolean.class}, false); });
            await(() -> (Boolean) value("stillShown") && ((PlayerScreens.StillWatching) value("stillWatching")).keep.hasFocus(), "after midnight the prompt receives focus");
            shot("player-still-watching");
            main(() -> call("updateStillWatching", new Class<?>[]{long.class}, SystemClock.elapsedRealtime() + 61000));
            await(() -> (Boolean) value("stillPaused") && !player().getPlayWhenReady() && !awake(), "an unanswered prompt pauses playback and releases the wake flag");
            main(() -> ((PlayerScreens.StillWatching) value("stillWatching")).keep.performClick());
            await(() -> activity.isFinishing() && PlaybackMemory.autoplayChain(getTargetContext()) == 0, "Keep watching advances and resets the episode count");
            result.putString("stream", "Passed " + checks + " player flow checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            TestState.playbackHour = -1;
            if (activity != null) runOnMainSync(() -> activity.finish());
            if (anchor != null) runOnMainSync(() -> anchor.finish()); waitForIdleSync(); SystemClock.sleep(300);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            Prefs.setSubtitleOffset(getTargetContext(), release, 0);
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).edit().remove("watched:tt0000020").commit();
            getTargetContext().getSharedPreferences(PlaybackMemory.FILE, 0).edit().remove("source:tt0000020").remove("subtitles:tt0000020").commit();
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000020:1:1").commit();
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
