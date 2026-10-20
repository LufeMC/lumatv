package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONObject;

/** Report preference checksums without reporting preference values. This test does not write data. */
public class HouseholdState extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject checksums = new JSONObject();
            for (String name : new String[]{"playback-settings", "home-apps", "my-services", "remote-keys", "setup", "media-addons"}) {
                TreeMap<String, Object> ordered = new TreeMap<>();
                for (Map.Entry<String, ?> item : getTargetContext().getSharedPreferences(name, 0).getAll().entrySet()) {
                    Object value = item.getValue();
                    if (value instanceof java.util.Set) value = new TreeSet<>((java.util.Set<String>) value);
                    ordered.put(item.getKey(), value);
                }
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(ordered.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                checksums.put(name, hex.toString());
            }
            result.putString("stream", "Household checksums: " + checksums + "\n");
            Setup.init(getTargetContext());
            result.putString("stream", result.getString("stream") + "Encrypted TV setup: " + Setup.onTv()
                    + "; location configured: " + Setup.hasLocation() + "; temperature: " + Setup.temperature() + "\n");
            finish(-1, result);
        } catch (Exception error) {
            result.putString("stream", "FAILED: Cannot read household checksums.\n");
            finish(0, result);
        }
    }
}
