package io.freemytv;

import android.app.Application;
import android.content.SharedPreferences;
import java.io.File;

/** Instrumentation uses private copies of preferences and state files. */
public class LumaApp extends Application {
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!TestState.active || name.startsWith(TestState.PREFIX)) return super.getSharedPreferences(name, mode);
        synchronized (TestState.class) {
            SharedPreferences copy = super.getSharedPreferences(TestState.PREFIX + name, mode);
            if (TestState.copied.add(name)) TestState.copy(super.getSharedPreferences(name, mode), copy);
            return copy;
        }
    }
    @Override public File getFilesDir() {
        File root = super.getFilesDir();
        if (!TestState.active) return root;
        File folder = new File(root, "instrumentation"); folder.mkdirs();
        synchronized (TestState.class) { TestState.copyFiles(root, folder); }
        return folder;
    }
}
