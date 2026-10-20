package io.freemytv;

import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/** Compare library modification metadata before downloading watch history. */
final class LibrarySync {
    static String fingerprint(JSONArray metadata) throws Exception {
        if (metadata == null || metadata.length() > 20000) throw new Exception("library_metadata");
        TreeMap<String, Long> items = new TreeMap<>();
        for (int i = 0; i < metadata.length(); i++) {
            JSONArray item = metadata.optJSONArray(i);
            if (item == null || item.length() != 2 || !(item.opt(0) instanceof String) || !(item.opt(1) instanceof Number)
                    || item.getString(0).length() > 200 || item.getLong(1) < 0) throw new Exception("library_metadata");
            items.put(item.getString(0), item.getLong(1));
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (java.util.Map.Entry<String, Long> item : items.entrySet()) {
            digest.update(item.getKey().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            digest.update(Long.toString(item.getValue()).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
    }
}
