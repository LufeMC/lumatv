package io.freemytv;

import android.content.SharedPreferences;
import java.util.*;

/** Only signed instrumentation enables this process-local state. */
final class TestState {
    static volatile boolean active;
    static final String PREFIX = "instrumentation-";
    static final Set<String> copied = new HashSet<>();
    static boolean filesReady;
    static void copyFiles(java.io.File from, java.io.File to) {
        if (filesReady) return;
        filesReady = true;
        for (String name : new String[]{"home-state.json", "stremio-library.json", "recs-status.json", "update-state.json"}) {
            java.io.File source = new java.io.File(from, name), target = new java.io.File(to, name);
            try {
                if (source.isFile() && source.length() <= 8 * 1024 * 1024) java.nio.file.Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else target.delete();
            } catch (java.io.IOException error) { throw new IllegalStateException("Cannot copy isolated test state", error); }
        }
    }
    static void copy(SharedPreferences from, SharedPreferences to) {
        SharedPreferences.Editor e = to.edit().clear();
        for (Map.Entry<String, ?> item : from.getAll().entrySet()) {
            String k = item.getKey(); Object v = item.getValue();
            if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof Set) e.putStringSet(k, new HashSet<>((Set<String>) v));
        }
        e.commit();
    }
}
