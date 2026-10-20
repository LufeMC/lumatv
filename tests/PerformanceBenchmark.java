package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewTreeObserver;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Measure the same home workload before and after changes. */
public class PerformanceBenchmark extends Instrumentation {
    private String label;
    private Activity home;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); label = args == null ? "sample" : args.getString("label", "sample");
        if (android.app.Application.getProcessName().equals("io.freemytv:home")) start();
    }
    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            getUiAutomation();
            long launch = SystemClock.elapsedRealtime();
            runOnMainSync(() -> home = HomeActivity.visible.get());
            if (home == null) home = startActivitySync(new Intent(getTargetContext(), HomeActivity.class).setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            HomeView view = (HomeView) field(home, "home");
            long opened = SystemClock.elapsedRealtime() - launch;
            long readyEnd = SystemClock.elapsedRealtime() + 15000; boolean[] contentReady = {false};
            while (!contentReady[0] && SystemClock.elapsedRealtime() < readyEnd) {
                runOnMainSync(() -> {
                    try {
                        ContinueRow row = (ContinueRow) field(view, "continueRow");
                        android.widget.LinearLayout cards = (android.widget.LinearLayout) field(row, "cards");
                        contentReady[0] = !((java.util.List<?>) field(view, "shelf")).isEmpty() && cards.getChildCount() > 0
                                && ((android.widget.ImageView) ((android.widget.LinearLayout) cards.getChildAt(0)).getChildAt(0)).getDrawable() != null;
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                if (!contentReady[0]) SystemClock.sleep(20);
            }
            if (!contentReady[0]) throw new AssertionError("Home content did not load");
            long contentMs = SystemClock.elapsedRealtime() - launch;
            SystemClock.sleep(8000);
            long[] idleFrames = {0, 0}; runOnMainSync(() -> idleFrames[0] = view.frameCount);
            long cpu = android.os.Process.getElapsedCpuTime();
            int uid = android.os.Process.myUid();
            long received = android.net.TrafficStats.getUidRxBytes(uid), sent = android.net.TrafficStats.getUidTxBytes(uid);
            SystemClock.sleep(10000);
            long idleCpu = android.os.Process.getElapsedCpuTime() - cpu;
            long idleReceived = android.net.TrafficStats.getUidRxBytes(uid) - received, idleSent = android.net.TrafficStats.getUidTxBytes(uid) - sent;
            runOnMainSync(() -> idleFrames[1] = view.frameCount);
            double[] latency = new double[12];
            for (int i = 0; i < latency.length; i++) {
                CountDownLatch drawn = new CountDownLatch(1); long[] times = new long[2];
                ViewTreeObserver.OnDrawListener listener = () -> { if (times[1] == 0) { times[1] = SystemClock.elapsedRealtimeNanos(); drawn.countDown(); } };
                int code = i % 2 == 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                runOnMainSync(() -> {
                    view.getViewTreeObserver().addOnDrawListener(listener);
                    times[0] = SystemClock.elapsedRealtimeNanos(); long now = SystemClock.uptimeMillis();
                    home.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                    home.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                });
                if (!drawn.await(2, TimeUnit.SECONDS)) throw new AssertionError("No remote focus frame");
                runOnMainSync(() -> view.getViewTreeObserver().removeOnDrawListener(listener));
                latency[i] = (times[1] - times[0]) / 1000000.0; SystemClock.sleep(650);
            }
            Arrays.sort(latency); Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
            JSONObject result = new JSONObject().put("label", label).put("activity_open_ms", opened)
                    .put("apps_and_first_thumbnail_ms", contentMs)
                    .put("input_to_draw_median_ms", latency[latency.length / 2]).put("input_to_draw_max_ms", latency[latency.length - 1])
                    .put("idle_cpu_ms_per_10s", idleCpu).put("idle_callbacks_per_10s", idleFrames[1] - idleFrames[0])
                    .put("idle_received_bytes_per_10s", idleReceived).put("idle_sent_bytes_per_10s", idleSent)
                    .put("fonts_ms", ((HomeActivity) home).fontsMs).put("home_view_ms", ((HomeActivity) home).viewMs)
                    .put("pss_kb", memory.getTotalPss()).put("threads", Thread.getAllStackTraces().size())
                    .put("stremio_change_check_active", getTargetContext().getSharedPreferences(ContinueWatching.PREFS, 0).contains("cloud-meta"));
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "performance-" + label + ".json"))) {
                file.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            output.putString("stream", result.toString(2) + "\n"); finish(-1, output);
        } catch (Throwable error) { output.putString("stream", "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); finish(0, output); }
    }
}
