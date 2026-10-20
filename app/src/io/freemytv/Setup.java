package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The TMDB key and home location. Entered on the TV (or from a phone) and kept encrypted on the TV, so a published
 * APK carries no key or address. Values from config.properties are the fallback for builds made on the owner's Mac.
 */
final class Setup {
    private Setup() {}

    private static final String FILE = "setup", ALIAS = "lumatv.setup";
    private static volatile JSONObject cache;
    private static volatile Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    static String tmdbKey() { return value("tmdb_key", Config.TMDB_KEY); }
    static String region() { return value("region", Config.TMDB_REGION).toUpperCase(Locale.ROOT); }
    static String city() { return value("city", Config.CITY); }
    static String latitude() { return value("latitude", Config.LATITUDE); }
    static String longitude() { return value("longitude", Config.LONGITUDE); }
    static String temperature() { return value("temperature", Config.TEMPERATURE); }

    static boolean hasKey() { return !tmdbKey().isEmpty(); }

    /** True when the TV itself holds the key (not only the build). */
    static boolean onTv() {
        return !load().optString("tmdb_key", "").isEmpty();
    }

    private static String value(String key, String fallback) {
        String v = load().optString(key, "").trim();
        return v.isEmpty() ? (fallback == null ? "" : fallback.trim()) : v;
    }

    private static JSONObject load() {
        JSONObject c = cache;
        if (c != null) return c;
        JSONObject loaded = new JSONObject();
        Context ctx = app;
        if (ctx != null) {
            try {
                String raw = decrypt(ctx);
                if (!raw.isEmpty()) loaded = new JSONObject(raw);
            } catch (Exception e) {
                AppLog.w("setup unreadable: " + e.getClass().getSimpleName());
            }
        }
        cache = loaded;
        return loaded;
    }

    /** Saves the given fields (others stay). Blank values remove the field. */
    static synchronized boolean save(Context c, JSONObject fields) {
        try {
            JSONObject next = new JSONObject(load().toString());
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = fields.optString(k, "").trim();
                if (v.isEmpty()) next.remove(k); else next.put(k, v);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] sealed = cipher.doFinal(next.toString().getBytes(StandardCharsets.UTF_8));
            boolean ok = Prefs.get(c, FILE).edit()
                    .putString("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit();
            if (ok) cache = next;
            return ok;
        } catch (Exception e) {
            AppLog.w("setup not saved: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** What may be shown back (never the key itself). */
    static JSONObject publicView() {
        JSONObject out = new JSONObject();
        try {
            out.put("hasKey", hasKey()).put("city", city()).put("region", region())
                    .put("latitude", latitude()).put("longitude", longitude()).put("temperature", temperature());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String decrypt(Context c) throws Exception {
        SharedPreferences p = Prefs.get(c, FILE);
        String data = p.getString("data", "");
        if (data.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
}
