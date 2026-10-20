package io.freemytv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Return saved add-ons immediately and synchronize outside the playback request. */
final class StremioAccount {
    private static final java.util.concurrent.ExecutorService sync = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean refreshing;
    private static long attempted;
    static synchronized JSONArray addons(Context context) throws Exception {
        JSONArray cached = AddonStore.load(context);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!AddonStore.refreshDue(context) || refreshing || attempted != 0 && now - attempted < 180000) return cached;
        final String account = AddonStore.account(context);
        if (account.isEmpty()) return cached;
        android.content.Context app = context instanceof android.app.Activity ? context.getApplicationContext() : context;
        final String revision = app.getSharedPreferences("media-addons", 0).getString("data", "");
        refreshing = true; attempted = now;
        sync.execute(() -> {
            try {
                JSONArray updated = fetch(account);
                if (revision.equals(app.getSharedPreferences("media-addons", 0).getString("data", "")))
                    AddonStore.commit(app, AddonStore.sealAccount(updated, account));
            } catch (Exception ignored) {} finally { synchronized (StremioAccount.class) { refreshing = false; } }
        });
        return cached;
    }
    static JSONArray fetch(String account) throws Exception {
        JSONObject body = new JSONObject().put("type", "AddonCollectionGet").put("authKey", account).put("update", true);
        try { return MediaHttp.result(MediaHttp.json("https://api.strem.io/api/addonCollectionGet", body, 10000)).getJSONArray("addons"); }
        finally { body.remove("authKey"); }
    }
}
