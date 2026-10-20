package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.EditText;
import java.lang.reflect.Field;

/** Verify short remote paths to Search and lazy watch-card navigation. */
public class SearchAccessTests extends Instrumentation {
    private int checks;
    private Activity home, search;
    private HomeView view;
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    private Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    private void key(Activity activity, int code) {
        runOnMainSync(() -> { long now = SystemClock.uptimeMillis(); activity.dispatchKeyEvent(new KeyEvent(now, now, 0, code, 0)); activity.dispatchKeyEvent(new KeyEvent(now, now, 1, code, 0)); });
        SystemClock.sleep(200);
    }
    private boolean searchFocused() throws Exception { return ((Integer) field(view, "topIndex")) == 6 && ((Integer) field(view, "zone")).equals(field(view, "Z_TOP")); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            getUiAutomation(); home = startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            view = (HomeView) field(home, "home"); SystemClock.sleep(5000);
            check(searchFocused(), "Search has default focus");
            ContinueRow row = (ContinueRow) field(view, "continueRow"); android.widget.LinearLayout cards = (android.widget.LinearLayout) field(row, "cards");
            int[] count = {0}; runOnMainSync(() -> count[0] = cards.getChildCount());
            check(count[0] <= 10, "initial watch cards are bounded");
            key(home, KeyEvent.KEYCODE_DPAD_DOWN); key(home, KeyEvent.KEYCODE_BACK); check(searchFocused(), "Back from watch row focuses Search");
            key(home, KeyEvent.KEYCODE_DPAD_DOWN); key(home, KeyEvent.KEYCODE_DPAD_DOWN); key(home, KeyEvent.KEYCODE_BACK); check(searchFocused(), "Back from app shelf focuses Search");
            key(home, KeyEvent.KEYCODE_DPAD_LEFT); key(home, KeyEvent.KEYCODE_DPAD_UP); check(searchFocused(), "Up from top controls focuses Search");
            key(home, KeyEvent.KEYCODE_DPAD_DOWN);
            for (int i = 0; i < 20; i++) key(home, KeyEvent.KEYCODE_DPAD_RIGHT);
            runOnMainSync(() -> count[0] = cards.getChildCount());
            check(count[0] > 20, "later watch cards remain available");
            key(home, KeyEvent.KEYCODE_BACK); check(searchFocused(), "Back from later watch card focuses Search");
            ActivityMonitor monitor = addMonitor(SearchActivity.class.getName(), null, false);
            key(home, KeyEvent.KEYCODE_DPAD_CENTER); search = waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor);
            check(search != null, "one OK opens Search"); SystemClock.sleep(1500);
            EditText query = (EditText) field(search, "query"); boolean[] ready = {false};
            runOnMainSync(() -> ready[0] = query.hasFocus() && search.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
            check(ready[0], "Search opens with keyboard ready");
            runOnMainSync(() -> query.setText("test")); key(search, KeyEvent.KEYCODE_SEARCH); SystemClock.sleep(400);
            runOnMainSync(() -> ready[0] = query.getSelectionStart() == 0 && query.getSelectionEnd() == query.length());
            check(ready[0], "Search key selects current query for replacement");
            runOnMainSync(() -> search.finish()); search = null; SystemClock.sleep(500);
            monitor = addMonitor(SearchActivity.class.getName(), null, false);
            key(home, KeyEvent.KEYCODE_SEARCH); search = waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor);
            check(search != null, "remote Search key opens Search");
            output.putString("stream", "Passed " + checks + " Search access and lazy card checks.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally { runOnMainSync(() -> { if (search != null) search.finish(); if (home != null) home.finish(); }); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
