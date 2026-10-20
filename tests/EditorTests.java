package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Exercise the installed editor through its remote keys and accessibility controls. */
public class EditorTests extends Instrumentation {
    private int checks;
    private boolean restoreOnly;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        restoreOnly = args != null && args.getBoolean("restore", false);
        if (args != null && "true".equals(args.getString("restore"))) restoreOnly = true;
        if (android.app.Application.getProcessName().equals("io.freemytv.tests")) start();
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value) {
        if (node == null) return null;
        if (value.contentEquals(node.getText() == null ? "" : node.getText()) ||
                value.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), value);
            if (found != null) return found;
        }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String value) {
        long until = SystemClock.uptimeMillis() + 7000;
        while (SystemClock.uptimeMillis() < until) {
            AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), value);
            if (node != null) return node;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing control: " + value);
    }
    private void click(String value) {
        AccessibilityNodeInfo node = waitFor(value);
        check(node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click " + value);
        SystemClock.sleep(200);
    }
    private boolean scroll(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
        return false;
    }
    private void collectNames(AccessibilityNodeInfo node, java.util.Map<Integer, String> names) {
        if (node == null) return;
        String text = node.getText() == null ? "" : node.getText().toString();
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^([1-8])\\. (.+)$").matcher(text);
        if (match.matches()) names.put(Integer.parseInt(match.group(1)), match.group(2));
        for (int i = 0; i < node.getChildCount(); i++) collectNames(node.getChild(i), names);
    }
    private boolean scrollBack(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scrollBack(node.getChild(i))) return true;
        return false;
    }
    private String[] currentNames() {
        java.util.TreeMap<Integer, String> names = new java.util.TreeMap<>();
        for (int i = 0; i < 8; i++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            collectNames(root, names);
            if (names.size() == 8 || !scroll(root)) break;
            SystemClock.sleep(200);
        }
        for (int i = 0; i < 8 && scrollBack(getUiAutomation().getRootInActiveWindow()); i++) SystemClock.sleep(200);
        check(names.size() == 8, "capture original app selection");
        return names.values().toArray(new String[0]);
    }
    private void pick(String name) {
        for (int attempt = 0; attempt < 80; attempt++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo choice = find(root, name);
            if (choice != null) {
                check(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK), "pick " + name);
                SystemClock.sleep(300);
                return;
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            SystemClock.sleep(150);
        }
        throw new AssertionError("Missing app in picker: " + name);
    }
    private void inject(KeyEvent event) {
        if (!getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Remote key injection failed");
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        inject(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        SystemClock.sleep(150);
    }
    private void editor() {
        try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand("am start -W -n io.freemytv/.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME"))) {
            while (stream.read() != -1) {}
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        SystemClock.sleep(1200);
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        SystemClock.sleep(800);
        inject(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        waitFor("Edit home apps");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getUiAutomation();
            try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP"))) {
                while (stream.read() != -1) {}
            }
            SystemClock.sleep(2000);
            if (restoreOnly) {
                try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        getUiAutomation().executeShellCommand("am start -W -n io.freemytv/.SettingsActivity"))) {
                    while (stream.read() != -1) {}
                }
                SystemClock.sleep(500);
                for (int i = 0; i < 4; i++) key(KeyEvent.KEYCODE_DPAD_UP);
                key(KeyEvent.KEYCODE_DPAD_DOWN);
                key(KeyEvent.KEYCODE_DPAD_DOWN);
                key(KeyEvent.KEYCODE_DPAD_CENTER);
                waitFor("No home apps. Select Add app.");
                for (String app : new String[]{"Netflix", "YouTube", "Prime Video", "Disney+", "HBO Max", "Apple TV", "Sun NXT", "Stremio"}) {
                    click("Add app");
                    pick(app);
                }
                click("Save");
                editor();
                waitFor("1. Netflix");
                check(!waitFor("Add app").isEnabled(), "original app selection restored");
                click("Cancel");
                result.putString("stream", "Passed " + checks + " empty-screen recovery checks.\n");
                finish(-1, result);
                return;
            }
            editor();
            String[] apps = currentNames();
            check(waitFor("Move Netflix left").isEnabled() == false, "first app cannot move left");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(find(getUiAutomation().getRootInActiveWindow(), "Move Netflix right").isFocused(), "remote focus skips disabled move");
            click("Move Netflix right");
            waitFor("1. YouTube");
            click("Save");
            try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    getUiAutomation().executeShellCommand("am force-stop io.freemytv"))) {
                while (stream.read() != -1) {}
            }
            editor();
            waitFor("1. YouTube");
            check(true, "saved order after process restart");
            click("Move Netflix left");
            click("Remove Netflix from Home");
            check(waitFor("Add app").isEnabled(), "removal frees a slot");
            click("Add app");
            pick("Netflix");
            waitFor("8. Netflix");
            check(!waitFor("Add app").isEnabled(), "eight-app limit");
            key(KeyEvent.KEYCODE_BACK);
            waitFor("Discard changes?");
            click("Keep editing");
            waitFor("8. Netflix");
            click("Cancel");
            click("Discard");
            editor();
            waitFor("1. YouTube");
            check(true, "discard preserves saved order");
            click("Move Netflix left");
            click("Save");
            editor();
            waitFor("1. Netflix");
            check(true, "original order restored");
            for (String app : apps) click("Remove " + app + " from Home");
            waitFor("No home apps. Select Add app.");
            click("Save");
            SystemClock.sleep(1200);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(600);
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            waitFor("No home apps. Select Add app.");
            check(true, "empty home screen reaches editor with remote");
            for (String app : apps) {
                click("Add app");
                pick(app);
            }
            click("Save");
            editor();
            waitFor("1. Netflix");
            check(!waitFor("Add app").isEnabled(), "original eight apps restored");
            click("Cancel");
            result.putString("stream", "Passed " + checks + " editor checks.\n");
            finish(-1, result);
        } catch (Throwable e) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            String window = root == null ? "none" : String.valueOf(root.getPackageName());
            try {
                android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
                if (image != null) {
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "editor-test.png"))) {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                    }
                }
            } catch (Exception ignored) {}
            result.putString("stream", "FAILED after " + checks + " checks: " + e.toString() + "\nWindow: " + window + "\n");
            finish(0, result);
        }
    }
}
