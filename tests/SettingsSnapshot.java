package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Preserve track preferences if the TV stops a test process. */
final class SettingsSnapshot {
    static void capture(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "player-test-restore.json");
        if (file.isFile()) restore(context);
        JSONObject values = new JSONObject();
        for (java.util.Map.Entry<String, ?> item : context.getSharedPreferences("playback-settings", 0).getAll().entrySet()) values.put(item.getKey(), item.getValue());
        Files.write(file.toPath(), values.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void restore(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "player-test-restore.json"); if (!file.isFile()) return;
        JSONObject values = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        SharedPreferences.Editor edit = context.getSharedPreferences("playback-settings", 0).edit().clear();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = values.get(key);
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Number) edit.putInt(key, ((Number) value).intValue());
        }
        if (edit.commit()) file.delete();
    }
}
