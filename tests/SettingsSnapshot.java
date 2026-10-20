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
        for (java.util.Map.Entry<String, ?> item : context.getSharedPreferences("playback-settings", 0).getAll().entrySet()) {
            Object v = item.getValue();
            String type = v instanceof Long ? "long" : v instanceof Integer ? "int" : v instanceof Float ? "float" : v instanceof Boolean ? "bool" : "string";
            values.put(item.getKey(), new JSONObject().put("t", type).put("v", v instanceof java.util.Set ? new org.json.JSONArray((java.util.Set<?>) v) : v));
        }
        Files.write(file.toPath(), values.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void restore(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "player-test-restore.json"); if (!file.isFile()) return;
        JSONObject values = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        SharedPreferences.Editor edit = context.getSharedPreferences("playback-settings", 0).edit().clear();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object raw = values.get(key);
            // Snapshots from before typed values stored the bare value.
            String type = raw instanceof JSONObject ? ((JSONObject) raw).optString("t") : raw instanceof Boolean ? "bool" : raw instanceof Number ? "long" : "string";
            Object value = raw instanceof JSONObject ? ((JSONObject) raw).get("v") : raw;
            switch (type) {
                case "long": edit.putLong(key, ((Number) value).longValue()); break;
                case "int": edit.putInt(key, ((Number) value).intValue()); break;
                case "float": edit.putFloat(key, ((Number) value).floatValue()); break;
                case "bool": edit.putBoolean(key, (Boolean) value); break;
                default: edit.putString(key, String.valueOf(value));
            }
        }
        if (edit.commit()) file.delete();
    }
}
