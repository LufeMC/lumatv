package io.freemytv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Add-on addresses can contain private account keys. */
final class AddonStore {
    private static final String ALIAS = "freemytv.addons";
    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static void save(Context context, JSONArray addons) throws Exception {
        commit(context, seal(addons));
    }
    static String[] seal(JSONArray addons) throws Exception {
        return encrypt(addons.toString());
    }
    static String[] sealAccount(JSONArray addons, String account) throws Exception {
        return encrypt(new JSONObject().put("addons", addons).put("account", account).toString());
    }
    private static String[] encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new String[]{Base64.encodeToString(encrypted, Base64.NO_WRAP), Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)};
    }
    static void commit(Context context, String[] sealed) throws Exception {
        boolean saved = context.getSharedPreferences("media-addons", 0).edit()
                .putString("data", sealed[0]).putString("iv", sealed[1]).putLong("updated", System.currentTimeMillis()).commit();
        if (!saved) throw new Exception("storage");
    }
    static JSONArray load(Context context) throws Exception {
        String value = decrypt(context);
        return value.startsWith("[") ? new JSONArray(value) : new JSONObject(value).getJSONArray("addons");
    }
    static String account(Context context) throws Exception {
        String value = decrypt(context);
        return value.startsWith("[") ? "" : new JSONObject(value).optString("account", "");
    }
    static boolean refreshDue(Context context) {
        long age = System.currentTimeMillis() - context.getSharedPreferences("media-addons", 0).getLong("updated", 0);
        return age < 0 || age > 6 * 60 * 60 * 1000L;
    }
    private static String decrypt(Context context) throws Exception {
        android.content.SharedPreferences preferences = context.getSharedPreferences("media-addons", 0);
        String data = preferences.getString("data", "");
        if (data.isEmpty()) return "[]";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    static boolean connected(Context context) {
        return context.getSharedPreferences("media-addons", 0).contains("data");
    }
    static void clear(Context context) {
        context.getSharedPreferences("media-addons", 0).edit().clear().apply();
        context.getSharedPreferences(ContinueWatching.PREFS, 0).edit().remove("stremio").remove("cloud-at").remove("cloud-attempt-at").remove("cloud-meta").apply();
    }
}
