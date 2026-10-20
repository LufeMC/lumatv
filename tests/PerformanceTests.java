package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Field;

/** Measure idle callbacks and check that navigation wakes the renderer. */
public class PerformanceTests extends TvTest {
    private int checks;
    private Activity home;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
        else getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private void key(int code) {
        long now = SystemClock.uptimeMillis(); getUiAutomation().injectInputEvent(new KeyEvent(now, now, 0, code, 0), true);
        getUiAutomation().injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true);
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Ui.init(getTargetContext()); getUiAutomation();
            home = startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Field field = HomeActivity.class.getDeclaredField("home"); field.setAccessible(true); HomeView view = (HomeView) field.get(home);
            SystemClock.sleep(6000);
            long[] first = {0}, last = {0}; runOnMainSync(() -> first[0] = view.frameCount);
            SystemClock.sleep(4000); runOnMainSync(() -> last[0] = view.frameCount);
            long callbacks = last[0] - first[0]; check(callbacks <= 12, "idle rendering does not run continuously");
            long before = last[0]; key(KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(450);
            runOnMainSync(() -> last[0] = view.frameCount);
            check(view.zone == HomeView.Z_ICONS && last[0] > before, "remote input changes focus and draws the new state");
            SystemClock.sleep(2000); runOnMainSync(() -> first[0] = view.frameCount); SystemClock.sleep(3000); runOnMainSync(() -> last[0] = view.frameCount);
            check(last[0] - first[0] <= 10, "renderer returns to idle after navigation");
            output.putString("stream", "Passed " + checks + " performance checks. Idle callbacks in four seconds: " + callbacks + ".\n");
        } catch (Throwable failure) {
            output.putString("stream", "FAILED after " + checks + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally { if (home != null) runOnMainSync(() -> home.finish()); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
