package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Preserve watch data if the firmware stops a playback test. */
final class WatchSnapshot {
    static void capture(Context context) throws Exception {
        restore(context);
        JSONObject root = new JSONObject();
        for (String name : new String[]{ContinueWatching.PREFS, "playback-progress", "series-ui"}) {
            JSONObject values = new JSONObject();
            for (java.util.Map.Entry<String, ?> item : context.getSharedPreferences(name, 0).getAll().entrySet()) {
                Object value = item.getValue();
                String type = value instanceof Integer ? "int" : value instanceof Long ? "long" : value instanceof Boolean ? "boolean" : "string";
                values.put(item.getKey(), new JSONObject().put("type", type).put("value", value));
            }
            root.put(name, values);
        }
        Files.write(new File(context.getFilesDir(), "watch-test-restore.json").toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void restore(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "watch-test-restore.json");
        if (!file.isFile()) return;
        JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        java.util.Iterator<String> names = root.keys();
        while (names.hasNext()) {
            String name = names.next(); JSONObject values = root.getJSONObject(name);
            SharedPreferences.Editor edit = context.getSharedPreferences(name, 0).edit().clear();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject item = values.getJSONObject(key);
                switch (item.getString("type")) {
                    case "int": edit.putInt(key, item.getInt("value")); break;
                    case "long": edit.putLong(key, item.getLong("value")); break;
                    case "boolean": edit.putBoolean(key, item.getBoolean("value")); break;
                    default: edit.putString(key, item.getString("value")); break;
                }
            }
            if (!edit.commit()) throw new java.io.IOException("Could not restore watch data");
        }
        file.delete();
    }
}
