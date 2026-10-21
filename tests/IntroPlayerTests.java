package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/** Check intro controls and system media routing through the actual Android player. */
public class IntroPlayerTests extends TvTest {
    private PlayerActivity activity;
    private SourcesActivity anchor;
    private int checks;
    private Object value(String name) throws Exception { Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    private IntroSkip intro() throws Exception { return (IntroSkip) value("intro"); }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition test, String label) throws Exception {
        long end = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] pass = {false}; Throwable[] failure = {null};
            runOnMainSync(() -> { try { pass[0] = test.get(); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError(label + ": " + failure[0]);
            if (pass[0]) { checks++; return; } SystemClock.sleep(25);
        }
        throw new AssertionError(label);
    }
    private void invoke(String name) throws Exception { Method method = PlayerActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
    private void command(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
                java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[1024]; int count; while ((count = input.read(block)) != -1) output.write(block, 0, count);
            if (output.toString("UTF-8").contains("Error:")) throw new AssertionError(output.toString("UTF-8"));
        }
    }
    private void seekIntoIntro() {
        runOnMainSync(() -> { try {
            IntroSkip state = intro(); state.kept = false; state.skipped = false; state.visible = false; state.remaining = 5000;
            state.marker = new IntroMarkers.Marker(5000, 25000, 1);
            player().seekTo(6000); player().setPlayWhenReady(true);
        } catch (Exception error) { throw new RuntimeException(error); } });
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(500); WatchSnapshot.capture(getTargetContext());
            anchor = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "Playback test"));
            SystemClock.sleep(700);
            java.io.File fixture = new java.io.File(getTargetContext().getCacheDir(), "intro-test.mkv");
            try (java.io.InputStream input = getContext().getAssets().open("sample.mkv")) { java.nio.file.Files.copy(input, fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(fixture).toString(); source.quality = 360; source.name = "Intro test";
            PlaybackSession.current = new PlaybackSession("tt0000010", "Intro and media control test", Collections.singletonList(source), 360);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> player() != null && (Boolean) value("sourceValidated") && player().isPlaying(), "video and audio output start");
            await(() -> ((android.media.session.MediaSession) value("mediaSession")).isActive(), "player registers an active media session");
            command("cmd media_session dispatch pause");
            await(() -> !player().getPlayWhenReady() && activity.hasWindowFocus(), "system pause reaches this player");
            command("cmd media_session dispatch play");
            await(() -> player().isPlaying() && activity.hasWindowFocus(), "system play does not open Netflix");
            command("cmd media_session dispatch rewind");
            await(() -> ((SeekState) value("scrub")).active && activity.hasWindowFocus(), "system rewind reaches this player");
            runOnMainSync(() -> { try { Method method = PlayerActivity.class.getDeclaredMethod("finishScrub", boolean.class); method.setAccessible(true); method.invoke(activity, false); } catch (Exception error) { throw new RuntimeException(error); } });
            seekIntoIntro();
            await(() -> ((View) value("introActions")).getVisibility() == View.VISIBLE && intro().seconds() >= 4, "intro action shows countdown");
            runOnMainSync(() -> { try { ((View) value("skipIntro")).requestFocus(); invoke("hideControls"); } catch (Exception error) { throw new RuntimeException(error); } });
            waitForIdleSync(); SystemClock.sleep(500);
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "skip-intro.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); } finally { image.recycle(); }
            command("cmd media_session dispatch pause"); await(() -> !player().getPlayWhenReady(), "pause during intro");
            SystemClock.sleep(750); long[] remaining = {0}; runOnMainSync(() -> { try { remaining[0] = intro().remaining; } catch (Exception error) { throw new RuntimeException(error); } });
            SystemClock.sleep(2000);
            await(() -> intro().remaining == remaining[0] && !intro().skipped, "paused intro does not auto skip");
            runOnMainSync(() -> { try { ((View) value("keepIntro")).performClick(); } catch (Exception error) { throw new RuntimeException(error); } });
            command("cmd media_session dispatch play"); SystemClock.sleep(5500);
            await(() -> intro().kept && !intro().skipped && player().getCurrentPosition() < 25000, "Keep intro cancels automatic skip");
            seekIntoIntro(); await(() -> intro().visible && intro().seconds() >= 4, "new countdown starts");
            SystemClock.sleep(2000);
            await(() -> !intro().skipped && player().getCurrentPosition() < 25000, "intro does not skip before countdown");
            await(() -> intro().skipped && player().getCurrentPosition() >= 25000, "intro skips automatically after five seconds");
            seekIntoIntro(); await(() -> intro().visible, "manual skip available");
            runOnMainSync(() -> { try { ((View) value("skipIntro")).performClick(); } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> intro().skipped && player().getCurrentPosition() >= 25000, "manual skip works immediately");
            android.media.session.MediaSession[] session = {null}; runOnMainSync(() -> { try { session[0] = (android.media.session.MediaSession) value("mediaSession"); activity.finish(); } catch (Exception error) { throw new RuntimeException(error); } });
            await(() -> !session[0].isActive() && activity.isDestroyed(), "media session becomes inactive after the player closes");
            result.putString("stream", "Passed " + checks + " intro and system media control checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getMessage() + "\n"); }
        finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            if (anchor != null) runOnMainSync(() -> anchor.finish()); waitForIdleSync(); SystemClock.sleep(300);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000010").commit();
            new java.io.File(getTargetContext().getCacheDir(), "intro-test.mkv").delete();
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
