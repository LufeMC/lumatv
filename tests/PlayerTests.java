package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Run the installed player with generated, silent test media. */
public class PlayerTests extends TvTest {
    private int checks;
    private PlayerActivity activity;
    private SourcesActivity anchor;
    private Map<String, ?> saved;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private void progress(String message) { Bundle update = new Bundle(); update.putString("stream", message + "\n"); sendStatus(0, update); }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
        else getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private ExoPlayer player() throws Exception {
        Field field = PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true); return (ExoPlayer) field.get(activity);
    }
    private SeekState scrub() throws Exception {
        Field field = PlayerActivity.class.getDeclaredField("scrub"); field.setAccessible(true); return (SeekState) field.get(activity);
    }
    private SeekPreview preview() throws Exception {
        Field field = PlayerActivity.class.getDeclaredField("preview"); field.setAccessible(true); return (SeekPreview) field.get(activity);
    }
    private interface Condition { boolean get() throws Exception; }
    private void waitMain(Condition condition, String label) throws Exception {
        long end = SystemClock.uptimeMillis() + 35000;
        while (SystemClock.uptimeMillis() < end) {
            boolean[] done = {false}; Throwable[] error = {null};
            runOnMainSync(() -> { try { done[0] = condition.get(); } catch (Throwable e) { error[0] = e; } });
            if (error[0] != null) throw new AssertionError(label + ": " + error[0].getClass().getSimpleName());
            if (done[0]) { checks++; return; }
            SystemClock.sleep(150);
        }
        throw new AssertionError(label);
    }
    private String value(AccessibilityNodeInfo node) {
        return node.getContentDescription() != null ? node.getContentDescription().toString() : node.getText() == null ? "" : node.getText().toString();
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (node.isClickable() && value(node).contains(label)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), label); if (found != null) return found;
        }
        return null;
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true);
        SystemClock.sleep(150);
    }
    private void click(String label) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), label);
            if (node != null) { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click " + label); SystemClock.sleep(250); return; }
            key(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(100);
        }
        throw new AssertionError("Missing control: " + label);
    }
    private String mediaUri(String name) {
        return android.net.Uri.fromFile(new java.io.File(new java.io.File(getTargetContext().getCacheDir(), "test-fixtures"), name)).toString();
    }
    private AddonStreams.Source source(String url, String name) {
        AddonStreams.Source source = new AddonStreams.Source();
        if (url.startsWith("content://io.freemytv.tests.media/")) {
            android.net.Uri original = android.net.Uri.parse(url);
            url = android.net.Uri.parse(mediaUri(original.getLastPathSegment())).buildUpon().encodedQuery(original.getEncodedQuery()).build().toString();
        }
        source.url = url; source.name = name;
        source.detail = "Controlled test media"; source.addon = "Playback test"; source.quality = 360; return source;
    }
    private void screenshot(String name) {
        try {
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            if (image != null) try (java.io.FileOutputStream file = new java.io.FileOutputStream(
                    new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); image.recycle();
            }
        } catch (Exception ignored) {}
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            try (java.io.InputStream wake = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP"))) { while (wake.read() != -1) {} }
            startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(500); WatchSnapshot.capture(getTargetContext());
            anchor = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "Playback test"));
            SystemClock.sleep(700);
            java.io.File fixtures = new java.io.File(getTargetContext().getCacheDir(), "test-fixtures"); fixtures.mkdirs();
            for (String name : new String[]{"sample.mkv", "subtitle-en.srt", "subtitle-es.srt"}) {
                try (java.io.InputStream input = getContext().getAssets().open(name)) { java.nio.file.Files.copy(input, new java.io.File(fixtures, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
            SettingsSnapshot.capture(getTargetContext());
            saved = getTargetContext().getSharedPreferences("playback-settings", 0).getAll();
            getTargetContext().getSharedPreferences("playback-settings", 0).edit().clear().putBoolean("subtitles", false).commit();
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000000").remove("tt0000001").commit();
            List<AddonStreams.Source> queue = new ArrayList<>();
            queue.add(source("https://storage.googleapis.com/exoplayer-test-media-0/freemytv-missing-test-file.mp4", "Unavailable source"));
            queue.add(source("content://io.freemytv.tests.media/sample.mkv", "Generated video"));
            queue.add(source("content://io.freemytv.tests.media/sample.mkv?source=3", "Second source"));
            PlaybackSession.current = new PlaybackSession("tt0000000", "Free My TV playback test", queue, 360);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitMain(() -> PlaybackSession.current.index == 1 && player() != null && player().isPlaying(), "automatic failover starts video");
            progress("Automatic failover started the generated video.");
            waitMain(() -> {
                int audio = 0, text = 0;
                for (Tracks.Group group : player().getCurrentTracks().getGroups()) {
                    if (group.getType() == C.TRACK_TYPE_AUDIO) audio += group.length;
                    if (group.getType() == C.TRACK_TYPE_TEXT) text += group.length;
                }
                return audio == 2 && text == 2;
            }, "two audio and two subtitle tracks detected");
            key(KeyEvent.KEYCODE_DPAD_UP); click("Audio & subtitles"); screenshot("player-audio-panel.png"); click("Español");
            waitMain(() -> {
                for (Tracks.Group group : player().getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_AUDIO)
                    for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i) && ("spa".equals(group.getTrackFormat(i).language) || "es".equals(group.getTrackFormat(i).language))) return true;
                return false;
            }, "Spanish audio selected");
            check(getTargetContext().getSharedPreferences("playback-settings", 0).getString("audio-language", "en").equals("en"), "per-title audio leaves English default unchanged");
            String titleLanguage = getTargetContext().getSharedPreferences("playback-settings", 0).getString("audio-title:tt0000000", "");
            check(titleLanguage.equals("es") || titleLanguage.equals("spa"), "per-title audio choice is saved");
            progress("Spanish audio selection passed.");
            click("English, Built in");
            waitMain(() -> !player().getCurrentCues().cues.isEmpty() && player().getCurrentCues().cues.get(0).text.toString().contains("Subtitle test"), "English subtitle rendered");
            check(PlaybackSession.current.index == 1, "subtitle selection does not reject valid media during track replacement");
            screenshot("player-subtitles.png");
            click("Off");
            waitMain(() -> {
                for (Tracks.Group group : player().getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) return false;
                return player().getCurrentCues().cues.isEmpty();
            }, "subtitles off (only forced lines would show)");
            key(KeyEvent.KEYCODE_BACK);
            check(PlaybackSession.current.index == 1, "subtitle changes keep the current source");
            runOnMainSync(() -> { try { player().seekTo(30000); } catch (Exception e) { throw new RuntimeException(e); } });
            waitMain(() -> player().getCurrentPosition() >= 29000 && player().isPlaying(), "seek position");
            runOnMainSync(() -> { try { java.lang.reflect.Field field = PlayerActivity.class.getDeclaredField("seek"); field.setAccessible(true); ((android.view.View) field.get(activity)).requestFocus(); } catch (Exception e) { throw new RuntimeException(e); } });
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitMain(() -> scrub().active && scrub().target >= 39000 && !player().getPlayWhenReady(), "remote opens paused preview");
            long[] before = {0}; runOnMainSync(() -> { try { before[0] = scrub().original; } catch (Exception e) { throw new RuntimeException(e); } });
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitMain(() -> scrub().active && scrub().target >= 49000, "repeated arrows continue seeking");
            // Preview timing has a separate suite. Cancel before the three-second confirmation deadline.
            runOnMainSync(() -> { try {
                if (!scrub().active) throw new AssertionError("Cancel test exceeded the confirmation deadline");
                long now = SystemClock.uptimeMillis();
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
            } catch (Exception error) { throw new RuntimeException(error); } });
            waitMain(() -> !scrub().active && player().isPlaying() && Math.abs(player().getCurrentPosition() - before[0]) < 4000, "back cancels preview");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
            waitMain(() -> !scrub().active && player().isPlaying() && player().getCurrentPosition() >= 39000, "OK commits selected position");
            key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_CENTER);
            waitMain(() -> !scrub().active && player().isPlaying() && player().getCurrentPosition() < 37000, "left seeks backward");
            click("More options"); click("Try another source");
            waitMain(() -> PlaybackSession.current.index == 2 && player().isPlaying() && player().getCurrentPosition() >= 26000,
                    "source change preserves position");
            waitMain(() -> {
                for (Tracks.Group group : player().getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_AUDIO)
                    for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i) && ("spa".equals(group.getTrackFormat(i).language) || "es".equals(group.getTrackFormat(i).language))) return true;
                return false;
            }, "source change preserves audio language");
            runOnMainSync(() -> {
                try {
                    Field field = PlayerActivity.class.getDeclaredField("externalSubtitles"); field.setAccessible(true);
                    List<AddonSubtitles.Subtitle> values = (List<AddonSubtitles.Subtitle>) field.get(activity);
                    AddonSubtitles.Subtitle subtitle = new AddonSubtitles.Subtitle();
                    subtitle.url = mediaUri("subtitle-en.srt"); subtitle.language = "en";
                    subtitle.name = "Test add-on English"; subtitle.mime = "application/x-subrip"; values.add(subtitle);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            key(KeyEvent.KEYCODE_DPAD_UP); click("Audio & subtitles"); click("Test add-on English");
            waitMain(() -> {
                Field shown = PlayerActivity.class.getDeclaredField("shownLines"); shown.setAccessible(true);
                return ((String) shown.get(activity)).contains("Subtitle test") && player().getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
            }, "add-on subtitle shown by the app's own scheduler");
            check(PlaybackSession.current.index == 2, "add-on subtitles never re-prepare the player");
            key(KeyEvent.KEYCODE_BACK);
            click("Pause"); waitMain(() -> !player().getPlayWhenReady(), "pause");
            click("Play"); waitMain(() -> player().isPlaying(), "resume");
            click("More options"); click("Try another source");
            waitMain(() -> player() == null && PlaybackSession.current.index == 3, "source queue stops at end");
            screenshot("player-exhausted.png");
            runOnMainSync(() -> activity.finish()); activity = null;
            queue.clear(); queue.add(source("https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4", "HTTPS sample"));
            PlaybackSession.current = new PlaybackSession("tt0000001", "HTTPS playback test", queue, -1);
            activity = (PlayerActivity) startActivitySync(new Intent(getTargetContext(), PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitMain(() -> player() != null && player().isPlaying() && player().getCurrentPosition() > 500, "HTTPS video playback");
            check("en".equals(PlaybackSession.current.audioLanguage), "another title starts with English default");
            SystemClock.sleep(5500);
            key(KeyEvent.KEYCODE_DPAD_UP);
            screenshot("player-https.png");
            runOnMainSync(() -> { try { java.lang.reflect.Field field = PlayerActivity.class.getDeclaredField("seek"); field.setAccessible(true); ((android.view.View) field.get(activity)).requestFocus(); } catch (Exception e) { throw new RuntimeException(e); } });
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitMain(() -> scrub().active && !player().getPlayWhenReady(), "HTTPS seek preview opens");
            waitMain(() -> preview().available(scrub().target), "HTTPS decoded preview frame available");
            waitMain(() -> preview().available(scrub().target - 10000) && preview().available(scrub().target + 10000), "adjacent preview frames preloaded");
            runOnMainSync(() -> { try {
                if (scrub().active) {
                    long now = SystemClock.uptimeMillis();
                    activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
                    activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
                }
            } catch (Exception error) { throw new RuntimeException(error); } });
            waitMain(() -> player() != null && player().isPlaying(), "HTTPS seek completion resumes playback");
            output.putString("stream", "Passed " + checks + " player checks.\n");
        } catch (Throwable failure) {
            screenshot("player-failed.png");
            if (activity != null) runOnMainSync(() -> {
                try { progress("Source failure notes: " + (PlaybackSession.current == null ? "none" : PlaybackSession.current.failureNotes));
                    progress("Player state: present=" + (player() != null) + " focused=" + activity.hasWindowFocus()
                        + " index=" + (PlaybackSession.current == null ? -1 : PlaybackSession.current.index)
                        + " position=" + (player() == null ? -1 : player().getCurrentPosition()) + " state=" + (player() == null ? -1 : player().getPlaybackState())
                        + " cues=" + (player() == null ? -1 : player().getCurrentCues().cues.size())); } catch (Exception ignored) {}
            });
            if (activity != null) runOnMainSync(() -> {
                try {
                    if (player() != null) for (Tracks.Group group : player().getCurrentTracks().getGroups())
                        for (int i = 0; i < group.length; i++) {
                            androidx.media3.common.Format format = group.getTrackFormat(i);
                            progress("Track: type=" + group.getType() + " selected=" + group.isTrackSelected(i)
                                    + " language=" + format.language + " label=" + format.label + " mime=" + format.sampleMimeType);
                        }
                } catch (Exception ignored) {}
            });
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? ": " + failure.getMessage() : "") + "\n");
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            if (anchor != null) runOnMainSync(() -> anchor.finish()); waitForIdleSync(); SystemClock.sleep(300);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            if (saved != null) {
                android.content.SharedPreferences.Editor edit = getTargetContext().getSharedPreferences("playback-settings", 0).edit().clear();
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    if (entry.getValue() instanceof String) edit.putString(entry.getKey(), (String) entry.getValue());
                    if (entry.getValue() instanceof Integer) edit.putInt(entry.getKey(), (Integer) entry.getValue());
                    if (entry.getValue() instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                }
                edit.commit();
            }
            try { SettingsSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt0000000").remove("tt0000001").commit();
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
