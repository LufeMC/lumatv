package io.freemytv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;

/** UI tests cannot change household preferences or Stremio library data. */
public abstract class TvTest extends Instrumentation {
    private boolean noticesCleared;
    @Override public void onCreate(Bundle args) { TestState.active = true; super.onCreate(args); }
    @Override public Context getTargetContext() {
        Context raw = super.getTargetContext();
        Context app = raw.getApplicationContext();
        if (!noticesCleared && app != null) {
            noticesCleared = true;
            app.getSharedPreferences(NewEpisodes.PREFS, 0).edit().clear().commit();
        }
        return new ContextWrapper(raw) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return raw.getApplicationContext().getSharedPreferences(name, mode); }
            @Override public File getFilesDir() { return raw.getApplicationContext().getFilesDir(); }
        };
    }
    @Override public void finish(int result, Bundle data) {
        // Keep isolation active until Android stops this process, including late callbacks.
        Context app = super.getTargetContext().getApplicationContext();
        for (String name : new java.util.ArrayList<>(TestState.copied)) app.deleteSharedPreferences(TestState.PREFIX + name);
        super.finish(result, data);
    }
}
