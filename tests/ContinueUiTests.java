package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Map;

/** Use temporary cards and restore the TV's actual progress after the test. */
public class ContinueUiTests extends Instrumentation {
    private int checks;
    private Map<String, ?> saved;
    private Activity home;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
        else getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        CharSequence text = node.getContentDescription() != null ? node.getContentDescription() : node.getText();
        if (text != null && text.toString().startsWith(prefix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo match = find(node.getChild(i), prefix); if (match != null) return match; }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String prefix) {
        long end = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), prefix);
            if (found != null) { checks++; return found; } SystemClock.sleep(100);
        }
        throw new AssertionError("Missing " + prefix);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis(); getUiAutomation().injectInputEvent(new KeyEvent(now, now, 0, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true); SystemClock.sleep(200);
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
        } finally { bitmap.recycle(); }
    }
    @Override public void onStart() {
        Bundle output = new Bundle(); Activity opened = null;
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            saved = getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).getAll();
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().putBoolean("migrated", true).putLong("cloud-at", System.currentTimeMillis()).commit();
            org.json.JSONObject details = TitleSearch.get("/tv/48891?append_to_response=external_ids");
            ContinueWatching.Entry entry = new ContinueWatching.Entry(); entry.title = "Brooklyn Nine-Nine"; entry.imdb = "tt2467372";
            entry.tmdb = 48891; entry.kind = "tv"; entry.season = 2; entry.episode = 3; entry.video = "tt2467372:2:3"; entry.id = "test-resume";
            entry.subtitle = "UI test episode"; entry.art = "https://image.tmdb.org/t/p/w780" + details.optString("backdrop_path");
            ContinueWatching.record(getTargetContext(), entry, 90000, 1260000, false);
            home = startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor("Continue Watching"); waitFor("Brooklyn Nine-Nine,");
            SystemClock.sleep(2500); key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(waitFor("Brooklyn Nine-Nine,").isFocused(), "Down focuses Continue Watching");
            screenshot("continue-home-test.png");
            key(KeyEvent.KEYCODE_DPAD_LEFT); check(waitFor("Brooklyn Nine-Nine,").isFocused(), "row retains focus at its edge");
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Title to search");
            key(KeyEvent.KEYCODE_BACK); SystemClock.sleep(400);
            if (find(getUiAutomation().getRootInActiveWindow(), "Title to search") != null) key(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(1000); key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(waitFor("Brooklyn Nine-Nine,").isFocused(), "return to home preserves navigation");
            ActivityMonitor monitor = addMonitor(SourcesActivity.class.getName(), null, false);
            key(KeyEvent.KEYCODE_DPAD_CENTER); opened = waitForMonitorWithTimeout(monitor, 10000); removeMonitor(monitor);
            check(opened != null, "open resume title");
            check(opened.getIntent().getStringExtra("resume_video").equals("tt2467372:2:3"), "resume selected episode");
            check(opened.getIntent().getLongExtra("resume_position", 0) == 90000 && opened.getIntent().getBooleanExtra("auto_play", false), "resume automatically at saved position");
            output.putString("stream", "Passed " + checks + " Continue Watching UI checks.\n");
        } catch (Throwable failure) {
            try { screenshot("continue-failed.png"); } catch (Exception ignored) {}
            output.putString("stream", "FAILED after " + checks + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally {
            final Activity title = opened; runOnMainSync(() -> { if (title != null) title.finish(); if (home != null) home.finish(); });
            if (saved != null) {
                SharedPreferences.Editor edit = getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear();
                for (Map.Entry<String, ?> item : saved.entrySet()) {
                    if (item.getValue() instanceof String) edit.putString(item.getKey(), (String) item.getValue());
                    else if (item.getValue() instanceof Long) edit.putLong(item.getKey(), (Long) item.getValue());
                    else if (item.getValue() instanceof Boolean) edit.putBoolean(item.getKey(), (Boolean) item.getValue());
                }
                edit.commit();
            }
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
}
