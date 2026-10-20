package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;

public class DiscoveryUiTests extends Instrumentation {
    private int checks;
    private Bundle arguments;
    private final java.util.Map<String, java.util.Map<String, ?>> saved = new java.util.LinkedHashMap<>();
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); arguments = args;
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
        else getTargetContext().startActivity(new android.content.Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private String value(AccessibilityNodeInfo node) {
        return node.getContentDescription() != null ? node.getContentDescription().toString() : node.getText() == null ? "" : node.getText().toString();
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        if (value(node).startsWith(prefix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = find(node.getChild(i), prefix); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String prefix) {
        long end = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), prefix);
            if (found != null) { checks++; return found; } SystemClock.sleep(100);
        }
        throw new AssertionError("Missing: " + prefix);
    }
    private void click(String prefix) {
        if (!waitFor(prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Click: " + prefix);
        SystemClock.sleep(250);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(new KeyEvent(now, now, 0, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true); SystemClock.sleep(180);
    }
    private void shell(String command) throws Exception {
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) { while (output.read() != -1) {} }
    }
    private boolean playerVisible() {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("dumpsys activity activities"))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] data = new byte[8192]; int count;
            while ((count = input.read(data)) != -1) output.write(data, 0, count);
            for (String line : output.toString("UTF-8").split("\n")) if (line.contains("mResumedActivity") && line.contains(".PlayerActivity")) return true;
        } catch (Exception ignored) {} return false;
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle();
        }
    }
    private void query(String text) {
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!waitFor("Title to search").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) throw new AssertionError("Type query");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (arguments != null && arguments.getString("cleanupPrevious", "false").equals("true")) {
                android.content.SharedPreferences progress = getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0);
                org.json.JSONArray before = new org.json.JSONArray(progress.getString("local", "[]")), after = new org.json.JSONArray();
                for (int i = 0; i < before.length(); i++) if (!before.getJSONObject(i).optString("video").equals("tt2467372:1:1")) after.put(before.getJSONObject(i));
                progress.edit().putString("local", after.toString()).commit();
                getTargetContext().getSharedPreferences("playback-progress", 0).edit().remove("tt2467372:1:1").commit();
                getTargetContext().getSharedPreferences("series-ui", 0).edit().remove("season:48891").remove("episode:48891").commit();
            }
            for (String name : new String[]{ContinueWatching.PREFS, "playback-progress", "series-ui"}) saved.put(name, getTargetContext().getSharedPreferences(name, 0).getAll());
            getUiAutomation(); shell("input keyevent KEYCODE_WAKEUP");
            shell("am start -W -n io.freemytv/.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME");
            SystemClock.sleep(4000); screenshot("discovery-home.png");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            waitFor("Title to search");
            query("brooklyn"); waitFor("Brooklyn Nine-Nine (2013)");
            SystemClock.sleep(2000); screenshot("discovery-search.png");
            AccessibilityNodeInfo input = waitFor("Title to search");
            if (!input.isFocused()) throw new AssertionError("Suggestions stole input focus"); checks++;
            click("Brooklyn Nine-Nine (2013)");
            waitFor("S1 E1. Pilot");
            for (int i = 1; i <= 8; i++) waitFor("Season " + i);
            if (find(getUiAutomation().getRootInActiveWindow(), "Title to search") != null) throw new AssertionError("Search form on detail page"); checks++;
            SystemClock.sleep(2500); screenshot("discovery-series.png");
            click("Season 8"); waitFor("S8 E1."); screenshot("discovery-season8.png");
            click("Season 1"); waitFor("S1 E1. Pilot");
            click("S1 E1. Pilot");
            long target = SystemClock.uptimeMillis() + 30000; boolean launched = false;
            while (SystemClock.uptimeMillis() < target) {
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (playerVisible() || find(root, "Stremio account") != null) { launched = true; break; }
                SystemClock.sleep(100);
            }
            if (!launched) throw new AssertionError("Automatic playback did not start"); checks++;
            screenshot("discovery-automatic.png");
            for (int i = 0; i < 4; i++) {
                if (find(getUiAutomation().getRootInActiveWindow(), "S1 E1. Pilot") != null) break;
                key(KeyEvent.KEYCODE_BACK); SystemClock.sleep(1000);
            }
            waitFor("S1 E1. Pilot");
            screenshot("discovery-series-final.png");
            result.putString("stream", "Passed " + checks + " discovery UI checks.\n");
        } catch (Throwable failure) {
            try { screenshot("discovery-failed.png"); } catch (Exception ignored) {}
            result.putString("stream", "FAILED after " + checks + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally {
            for (java.util.Map.Entry<String, java.util.Map<String, ?>> preference : saved.entrySet()) {
                android.content.SharedPreferences.Editor editor = getTargetContext().getSharedPreferences(preference.getKey(), 0).edit().clear();
                for (java.util.Map.Entry<String, ?> item : preference.getValue().entrySet()) {
                    if (item.getValue() instanceof String) editor.putString(item.getKey(), (String) item.getValue());
                    else if (item.getValue() instanceof Long) editor.putLong(item.getKey(), (Long) item.getValue());
                    else if (item.getValue() instanceof Integer) editor.putInt(item.getKey(), (Integer) item.getValue());
                    else if (item.getValue() instanceof Boolean) editor.putBoolean(item.getKey(), (Boolean) item.getValue());
                }
                editor.commit();
            }
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
