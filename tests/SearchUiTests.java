package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;

/** Search and app handoff checks use native remote and accessibility actions. */
public class SearchUiTests extends Instrumentation {
    private int checks;
    private boolean firstEntry = true;
    private boolean connectOnly;
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); connectOnly = args != null && "true".equals(args.getString("connectOnly")); start(); }
    private String value(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription(), text = node.getText();
        return desc != null ? desc.toString() : text == null ? "" : text.toString();
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        if (value(node).startsWith(prefix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), prefix);
            if (found != null) return found;
        }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String prefix) {
        long end = SystemClock.uptimeMillis() + 25000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), prefix);
            if (found != null) return found;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing control: " + prefix);
    }
    private void click(String prefix) {
        check(waitFor(prefix).performAction(AccessibilityNodeInfo.ACTION_CLICK), "click " + prefix);
        SystemClock.sleep(200);
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        check(getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true), "remote key down");
        check(getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true), "remote key up");
        SystemClock.sleep(120);
    }
    private void shell(String command) throws Exception {
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            while (output.read() != -1) {}
        }
    }
    private void homeSearch() throws Exception {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("am start -W -n io.freemytv/.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME");
        waitPackage("io.freemytv");
        SystemClock.sleep(4000);
        if (firstEntry) {
            key(KeyEvent.KEYCODE_BACK);
            for (int i = 0; i < 3; i++) key(KeyEvent.KEYCODE_DPAD_UP);
            for (int i = 0; i < 8; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            firstEntry = false;
        } else key(KeyEvent.KEYCODE_SEARCH);
        waitFor("Search movies and shows");
    }
    private void search(String term) {
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term);
        check(waitFor("Title to search").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "enter query");
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        AccessibilityNodeInfo button = exact(root, "Search");
        check(button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "submit search");
    }
    private AccessibilityNodeInfo exact(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (value(node).equals(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = exact(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private boolean contains(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (value(node).contains(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (contains(node.getChild(i), text)) return true;
        return false;
    }
    private void waitPackage(String pkg) {
        long end = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null && pkg.contentEquals(root.getPackageName())) { checks++; return; }
            SystemClock.sleep(200);
        }
        throw new AssertionError("App handoff failed: " + pkg);
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            getUiAutomation();
            homeSearch();
            if (connectOnly) {
                search("Stranger Things"); click("Stranger Things (2016)"); click("Choose episode.");
                click("Connect sources"); click("Connect Stremio");
                String address = value(waitFor("https://link.stremio.com/"));
                Bundle code = new Bundle(); code.putString("stream", "Authorize at " + address + "\n"); sendStatus(0, code);
                long end = SystemClock.uptimeMillis() + 240000;
                while (SystemClock.uptimeMillis() < end) {
                    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                    if (contains(root, "add-ons imported")) {
                        output.putString("stream", "Add-ons imported.\n"); finish(-1, output); return;
                    }
                    SystemClock.sleep(500);
                }
                output.putString("stream", "Connection authorization is pending.\n"); finish(-1, output); return;
            }
            click("Credits"); waitFor("TMDB");
            click("Close");
            search("");
            waitFor("Enter a movie or TV show title."); checks++;
            search("Stranger Things");
            click("Stranger Things (2016)");
            waitFor("US catalog."); checks++;
            check(waitFor("Open title in Netflix.").isClickable(), "Netflix title route");
            check(waitFor("Choose episode.").isClickable(), "internal episode route");
            click("Choose episode.");
            waitFor("Connect your add-ons to watch without opening Stremio."); checks++;
            click("Connect sources");
            waitFor("Import your Stremio add-ons into Free My TV."); checks++;
            key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK);
            homeSearch();
            search("Reacher");
            click("Reacher (2022)");
            waitFor("US catalog.");
            check(waitFor("Open title in Prime Video.").isClickable(), "Prime title route");
            key(KeyEvent.KEYCODE_BACK);
            waitFor("Reacher (2022)"); checks++;
            search("zzzxqvnoresult7391826");
            waitFor("No titles found."); checks++;
            search("Stranger Things");
            click("Stranger Things (2016)");
            waitFor("US catalog.");
            click("Open title in Netflix.");
            waitPackage("com.netflix.ninja");
            SystemClock.sleep(3000);
            homeSearch();
            search("Stranger Things");
            waitFor("Stranger Things (2016)");
            click("Stranger Things (2016)");
            click("Choose episode.");
            click("Connect sources");
            click("Connect Stremio");
            waitFor("https://link.stremio.com/"); checks++;
            output.putString("stream", "Passed " + checks + " search UI checks.\n"); finish(-1, output);
        } catch (Throwable failure) {
            try {
                android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
                if (image != null) try (java.io.FileOutputStream file = new java.io.FileOutputStream(
                        new java.io.File(getTargetContext().getExternalFilesDir(null), "search-ui.png"))) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
                }
            } catch (Exception ignored) {}
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName()
                    + (failure instanceof AssertionError ? ": " + failure.getMessage() : "") + "\n");
            finish(0, output);
        }
    }
}
