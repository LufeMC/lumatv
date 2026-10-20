package io.freemytv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.service.dreams.DreamService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safety net for the :home process. Only crashes that happen while the home screen or the screensaver is showing
 * count (a player, search or settings crash doesn't take your home screen away). On the 3rd within 5 min the home
 * screen goes back to the TV's original one (requested from the main process, which records whether that worked) and
 * HomeActivity shows CrashFallback instead of drawing again. File: files/home-crashes.txt.
 */
final class Watchdog {
    private Watchdog() {}

    static final String HOME = "home", SCREENSAVER = "screensaver";
    private static final long WINDOW = 5 * 60_000L;
    private static final int LIMIT = 3;
    private static boolean installed;
    /** What is in front in this process: an activity's class name, or SCREENSAVER. */
    private static volatile String surface = "";

    static synchronized void install(Context c) {
        if (c instanceof DreamService) surface = SCREENSAVER;
        else if (c instanceof Activity) surface = c.getClass().getName();
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(new Tracker());
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (counts(surface) && record(app, System.currentTimeMillis()) >= LIMIT) HomeMode.requestRevertHome(app);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Crashes count only with the home screen (or the screensaver, which runs as Android's DreamActivity) in front. */
    static boolean counts(String surface) {
        if (surface == null || surface.isEmpty()) return false;
        return surface.equals(SCREENSAVER) || surface.endsWith(".HomeActivity") || surface.equals(HOME)
                || surface.endsWith(".DreamActivity") || surface.endsWith(".Screensaver");
    }

    static String surface() {
        return surface;
    }

    /** Our home screen crashed too often just now: don't retry (avoids a crash loop while the original comes back). */
    static boolean crashedTooOften(Context c) {
        return recent(c, System.currentTimeMillis()).size() >= LIMIT;
    }

    /** After LumaTV mode is turned back on, or "Try LumaTV again", the count starts over. */
    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    static int record(Context c, long now) throws IOException {
        List<Long> list = recent(c, now);
        list.add(now);
        StringBuilder sb = new StringBuilder();
        for (long at : list) sb.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return list.size();
    }

    private static List<Long> recent(Context c, long now) {
        List<Long> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try {
                    long at = Long.parseLong(line.trim());
                    if (now - at < WINDOW && at <= now) list.add(at);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "home-crashes.txt");
    }

    /** Follows the activity in front (the screensaver shows up as android.service.dreams.DreamActivity). */
    private static final class Tracker implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) { surface = a.getClass().getName(); }
        // Also on create and start: a crash while the home screen is still starting is a home screen crash.
        @Override public void onActivityCreated(Activity a, Bundle b) { surface = a.getClass().getName(); }
        @Override public void onActivityStarted(Activity a) { surface = a.getClass().getName(); }
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}
