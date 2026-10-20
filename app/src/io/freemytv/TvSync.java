package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Your TVs (Settings): keeps the shelf order, settings, Stremio add-ons and My services the same on your LumaTV TVs.
 * Opt-in: nothing happens until two TVs are paired, once, with a 4-digit code shown on one and typed on the other
 * (Pake). Then the TVs find each other on the local network (NsdManager, "_lumatv._tcp") and talk through PhoneLink's
 * server: every message is encrypted and authenticated with the pairing secret (AES-GCM, with a timestamp and a
 * one-time number against replays). Per category the newest change wins; the first sync copies from the TV where
 * you pressed Sync now. Add-ons are only ever added or replaced, never removed by a sync. Runs in :home.
 */
final class TvSync {
    private TvSync() {}

    static final String TYPE = "_lumatv._tcp";
    static final String APPS = "apps", SETTINGS = "settings", ADDONS = "addons", SERVICES = "services";
    static final String[] CATEGORIES = {APPS, SETTINGS, ADDONS, SERVICES};
    private static final String ALIAS = "lumatv.sync";
    private static final long SKEW = 5 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    /** Turning the server on and off (a socket bind): off the main thread, never behind a slow sync. */
    private static final ExecutorService CONTROL = Executors.newSingleThreadExecutor();
    private static final String[] PLAYBACK_KEYS = {Prefs.AUDIO_LANGUAGE, Prefs.SUBTITLE_LANGUAGE, Prefs.SUBTITLE_MODE,
            Prefs.SUBTITLE_SIZE, Prefs.SUBTITLE_BACKGROUND, Prefs.AUTO_SYNC, Prefs.AUTOPLAY, Prefs.MATCH_FRAME_RATE,
            Prefs.STILL_WATCHING};

    /** A paired TV. */
    static final class Peer {
        String id = "", name = "", host = "";
        int port;
        byte[] secret;
        long syncedAt;
    }

    /** A LumaTV TV seen on the network. */
    static final class Found {
        String id = "", name = "", host = "";
        int port;
    }

    interface Discovery { void changed(List<Found> found); }

    /** The code didn't match (or the other TV stopped accepting tries). */
    static final class WrongCode extends IOException {
        WrongCode(String m) { super(m); }
    }

    // MARK: - This TV

    static String id(Context c) {
        SharedPreferences p = Prefs.get(c, Prefs.SYNC);
        String id = p.getString("id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("id", id).commit();
        }
        return id;
    }

    /** The TV's name from its own settings ("Living room TV"), or its model. */
    static String name(Context c) {
        String n = null;
        try {
            n = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name");
        } catch (RuntimeException ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        n = n.trim();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    static boolean category(Context c, String cat) {
        return Prefs.get(c, Prefs.SYNC).getBoolean("cat-" + cat, true);
    }

    static void setCategory(Context c, String cat, boolean on) {
        Prefs.get(c, Prefs.SYNC).edit().putBoolean("cat-" + cat, on).apply();
        refreshListening(c);
    }

    static long lastSync(Context c) {
        return Prefs.get(c, Prefs.SYNC).getLong("last", 0);
    }

    static String lastError(Context c) {
        return Prefs.get(c, Prefs.SYNC).getString("error", "");
    }

    private static boolean anyCategory(Context c) {
        for (String cat : CATEGORIES) if (category(c, cat)) return true;
        return false;
    }

    // MARK: - Lifecycle

    /** When :home starts (home screen, settings): listen for paired TVs and sync if it's been a while. Cheap if unpaired. */
    static void start(Context c) {
        if (TestState.active) return;
        Context app = c.getApplicationContext();
        refreshListening(app);
        if (!peers(app).isEmpty() && anyCategory(app) && System.currentTimeMillis() - lastSync(app) > 6 * 60 * 60_000L) {
            MAIN.postDelayed(() -> syncSoon(app), 30_000);
        }
    }

    /** Server on while paired with sync on (opening a socket stays off the main thread). */
    private static void refreshListening(Context c) {
        Context app = c.getApplicationContext();
        CONTROL.execute(() -> {
            if (!peers(app).isEmpty() && anyCategory(app)) PhoneLink.hold(app, "sync"); else PhoneLink.release("sync");
            MAIN.post(() -> advertise(app));
        });
    }

    static void serverStarted(Context c) { MAIN.post(() -> advertise(c)); }

    static void serverStopped(Context c) { MAIN.post(() -> advertise(c)); }

    /** Quietly syncs with every paired TV (after settings change, or a while after start). */
    static void syncSoon(Context c) {
        if (TestState.active) return;
        Context app = c.getApplicationContext();
        if (peers(app).isEmpty() || !anyCategory(app)) return;
        WORK.execute(() -> {
            try {
                syncNow(app);
            } catch (Exception e) {
                AppLog.w("tv sync: " + e.getMessage());
            }
        });
    }

    // MARK: - Network presence (NSD)

    private static NsdManager.RegistrationListener registration;
    private static NsdManager.DiscoveryListener discovery;
    private static boolean pairingOpen;

    /** Main thread: advertise this TV while the server runs and someone could want it (paired, or pairing open). */
    private static void advertise(Context c) {
        if (TestState.active) return;
        NsdManager nsd = c.getSystemService(NsdManager.class);
        if (nsd == null) return;
        boolean want = PhoneLink.running() && (pairingOpen || !peers(c).isEmpty() && anyCategory(c));
        if (want && registration == null) {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("LumaTV " + name(c));
            info.setServiceType(TYPE);
            info.setPort(PhoneLink.port());
            info.setAttribute("id", id(c));
            registration = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { AppLog.w("nsd register failed " + e); }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
                @Override public void onServiceRegistered(NsdServiceInfo s) {}
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            };
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
            } catch (RuntimeException e) {
                registration = null;
            }
        } else if (!want && registration != null) {
            try { nsd.unregisterService(registration); } catch (RuntimeException ignored) {}
            registration = null;
        }
    }

    /** Main thread: finds LumaTV TVs nearby until stopDiscovery. */
    static void discover(Context c, Discovery callback) {
        stopDiscovery(c);
        NsdManager nsd = c.getSystemService(NsdManager.class);
        if (nsd == null) return;
        String self = id(c);
        Map<String, Found> found = new LinkedHashMap<>();
        Deque<NsdServiceInfo> queue = new ArrayDeque<>();
        boolean[] resolving = {false};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (resolving[0] || queue.isEmpty() || discovery == null) return;
            resolving[0] = true;
            NsdServiceInfo info = queue.poll();
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int e) {
                        MAIN.post(() -> { resolving[0] = false; next[0].run(); });
                    }

                    @Override public void onServiceResolved(NsdServiceInfo s) {
                        Found f = new Found();
                        byte[] raw = s.getAttributes().get("id");
                        f.id = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
                        f.name = s.getServiceName().startsWith("LumaTV ") ? s.getServiceName().substring(7) : s.getServiceName();
                        f.host = s.getHost() == null ? "" : s.getHost().getHostAddress();
                        f.port = s.getPort();
                        MAIN.post(() -> {
                            resolving[0] = false;
                            if (!f.id.isEmpty() && !f.id.equals(self) && !f.host.isEmpty() && discovery != null) {
                                found.put(f.id, f);
                                callback.changed(new ArrayList<>(found.values()));
                            }
                            next[0].run();
                        });
                    }
                });
            } catch (RuntimeException e) {
                resolving[0] = false;
            }
        };
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                MAIN.post(() -> { queue.add(s); next[0].run(); });
            }
            @Override public void onServiceLost(NsdServiceInfo s) {
                MAIN.post(() -> {
                    String n = s.getServiceName();
                    Iterator<Found> it = found.values().iterator();
                    boolean changed = false;
                    while (it.hasNext()) if (("LumaTV " + it.next().name).equals(n)) { it.remove(); changed = true; }
                    if (changed && discovery != null) callback.changed(new ArrayList<>(found.values()));
                });
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    static void stopDiscovery(Context c) {
        if (discovery == null) return;
        NsdManager nsd = c.getSystemService(NsdManager.class);
        try { if (nsd != null) nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) {}
        discovery = null;
    }

    // MARK: - Pairing, the TV that shows the code (B)

    private static String pin;
    private static long pinUntil;
    private static int failures;
    private static String pendingId = "", pendingName = "";
    private static Pake.Party pending;
    private static String justPaired;

    /** The Your TVs page is open: this TV can be found and paired. */
    static synchronized void openPairing(Context c) {
        pairingOpen = true;
        failures = 0;
        Context app = c.getApplicationContext();
        CONTROL.execute(() -> {
            PhoneLink.hold(app, "pairing");
            MAIN.post(() -> advertise(app));
        });
    }

    static synchronized void closePairing(Context c) {
        pairingOpen = false;
        pin = null;
        pending = null;
        Context app = c.getApplicationContext();
        CONTROL.execute(() -> {
            PhoneLink.release("pairing");
            MAIN.post(() -> advertise(app));
        });
    }

    /** The code to show, and for which TV, or null. */
    static synchronized String[] pairingCode() {
        if (pin == null || System.currentTimeMillis() > pinUntil) return null;
        return new String[]{pin, pendingName};
    }

    /** The name of a TV that just finished pairing with this one (once), or null. */
    static synchronized String takeJustPaired() {
        String n = justPaired;
        justPaired = null;
        return n;
    }

    // MARK: - Pairing, the TV where the code is typed (A); call off the main thread

    /** Asks the other TV to show a code. Returns its name. */
    static String pairStart(Context c, Found tv) throws IOException {
        JSONObject out = post(tv.host, tv.port, "/sync/pair/hello", null, json("id", id(c), "name", name(c)));
        if (!out.optString("error").isEmpty()) {
            throw new IOException("closed".equals(out.optString("error"))
                    ? Ui.tr("Abra Ajustes → Suas TVs na " + tv.name + ".", "Open Settings → Your TVs on " + tv.name + ".")
                    : out.optString("error"));
        }
        return out.optString("name", tv.name);
    }

    /** Proves both TVs know the code; on success both keep the shared secret. */
    static void pairFinish(Context c, Found tv, String code) throws IOException {
        String me = id(c);
        Pake.Party a = new Pake.Party(true, code, me, tv.id);
        JSONObject out = post(tv.host, tv.port, "/sync/pair/x", null, json("id", me, "x", a.mine.toString(16)));
        if (!out.optString("error").isEmpty()) throw new WrongCode(out.optString("error"));
        BigInteger y;
        try { y = new BigInteger(out.optString("y", "0"), 16); } catch (NumberFormatException e) { throw new IOException("bad reply"); }
        if (!a.finish(y) || !MessageDigest.isEqual(a.confirm("B"), unhex(out.optString("mac")))) {
            throw new WrongCode(Ui.tr("O código não confere.", "That code didn't match."));
        }
        JSONObject done = post(tv.host, tv.port, "/sync/pair/confirm", null, json("id", me, "mac", hex(a.confirm("A"))));
        if (!done.optBoolean("ok")) throw new WrongCode(Ui.tr("O código não confere.", "That code didn't match."));
        Peer p = new Peer();
        p.id = tv.id;
        p.name = done.optString("name", tv.name);
        p.host = tv.host;
        p.port = tv.port;
        p.secret = a.sharedSecret();
        putPeer(c, p);
        refreshListening(c);
    }

    // MARK: - Peers (encrypted with a key that never leaves this TV)

    static synchronized List<Peer> peers(Context c) {
        List<Peer> out = new ArrayList<>();
        String data = Prefs.get(c, Prefs.SYNC).getString("peers", "");
        if (data.isEmpty()) return out;
        try {
            String[] parts = data.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, storeKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONArray a = new JSONArray(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Peer p = new Peer();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.host = o.optString("host");
                p.port = o.optInt("port");
                p.secret = Base64.decode(o.optString("secret"), Base64.NO_WRAP);
                p.syncedAt = o.optLong("syncedAt");
                out.add(p);
            }
        } catch (Exception e) {
            AppLog.w("paired TVs unreadable: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static synchronized void savePeers(Context c, List<Peer> list) {
        try {
            JSONArray a = new JSONArray();
            for (Peer p : list) {
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("host", p.host).put("port", p.port)
                        .put("secret", Base64.encodeToString(p.secret, Base64.NO_WRAP)).put("syncedAt", p.syncedAt));
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, storeKey());
            byte[] sealed = cipher.doFinal(a.toString().getBytes(StandardCharsets.UTF_8));
            Prefs.get(c, Prefs.SYNC).edit().putString("peers", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(sealed, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            AppLog.w("paired TVs not saved: " + e.getClass().getSimpleName());
        }
    }

    private static synchronized void putPeer(Context c, Peer p) {
        List<Peer> list = peers(c);
        Iterator<Peer> it = list.iterator();
        while (it.hasNext()) if (it.next().id.equals(p.id)) it.remove();
        list.add(p);
        savePeers(c, list);
    }

    /** Forgets a paired TV here (and tells it to forget this one, if it answers). Off the main thread. */
    static void forget(Context c, String peerId) {
        for (Peer p : peers(c)) {
            if (!p.id.equals(peerId)) continue;
            try { message(c, p, "forget", new JSONObject()); } catch (Exception ignored) {}
        }
        removePeer(c, peerId);
    }

    private static synchronized void removePeer(Context c, String peerId) {
        List<Peer> list = peers(c);
        Iterator<Peer> it = list.iterator();
        while (it.hasNext()) if (it.next().id.equals(peerId)) it.remove();
        savePeers(c, list);
        refreshListening(c);
    }

    private static synchronized SecretKey storeKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }

    // MARK: - Sync (A side); off the main thread

    /** Syncs with every paired TV. Returns how many answered. */
    static int syncNow(Context c) throws IOException {
        // Its own lock: while waiting for the other TV, this TV's server must still answer it.
        synchronized (SYNC_LOCK) {
            return syncAll(c);
        }
    }

    private static final Object SYNC_LOCK = new Object();

    private static int syncAll(Context c) throws IOException {
        List<Peer> list = peers(c);
        if (list.isEmpty()) return 0;
        int ok = 0;
        IOException last = null;
        for (Peer p : list) {
            try {
                syncWith(c, p);
                ok++;
            } catch (IOException e) {
                last = e;
            }
        }
        SharedPreferences.Editor e = Prefs.get(c, Prefs.SYNC).edit();
        if (ok > 0) e.putLong("last", System.currentTimeMillis());
        e.putString("error", last == null ? "" : String.valueOf(last.getMessage())).apply();
        if (ok == 0 && last != null) throw last;
        return ok;
    }

    private static void syncWith(Context c, Peer p) throws IOException {
        JSONObject mine = snapshot(c);
        JSONObject theirs;
        try {
            theirs = message(c, p, "state", new JSONObject());
        } catch (IOException e) {
            if (!relocate(c, p)) throw new IOException(Ui.tr(p.name + " não está na rede.", p.name + " isn't on the network."));
            theirs = message(c, p, "state", new JSONObject());
        }
        JSONObject remote = theirs.optJSONObject("categories");
        if (remote == null) remote = new JSONObject();
        JSONArray enabled = theirs.optJSONArray("enabled");
        java.util.Set<String> both = new java.util.HashSet<>();
        for (int i = 0; enabled != null && i < enabled.length(); i++) both.add(enabled.optString(i));
        JSONObject send = new JSONObject();
        try {
            for (String cat : CATEGORIES) {
                JSONObject a = mine.optJSONObject(cat), b = remote.optJSONObject(cat);
                if (!category(c, cat) || !both.contains(cat)) continue;
                if (a == null && b == null) continue;
                boolean oursWins = b == null || a != null && (p.syncedAt == 0 || a.optLong("changed") >= b.optLong("changed"));
                if (oursWins) {
                    if (b == null || !a.optString("hash").equals(b.optString("hash"))) send.put(cat, a);
                } else if (a == null || !a.optString("hash").equals(b.optString("hash"))) {
                    apply(c, cat, b.getJSONObject("data"), b.optLong("changed"));
                }
            }
            if (send.length() > 0) message(c, p, "apply", new JSONObject().put("categories", send));
        } catch (org.json.JSONException e) {
            throw new IOException("sync data");
        }
        p.syncedAt = System.currentTimeMillis();
        if (!theirs.optString("name").isEmpty()) p.name = theirs.optString("name");
        putPeer(c, p);
    }

    /** The paired TV moved (new address): look for it by id for a few seconds. */
    private static boolean relocate(Context c, Peer p) {
        final Object lock = new Object();
        final Found[] hit = new Found[1];
        MAIN.post(() -> discover(c, list -> {
            for (Found f : list) if (f.id.equals(p.id)) synchronized (lock) { hit[0] = f; lock.notifyAll(); }
        }));
        synchronized (lock) {
            long until = System.currentTimeMillis() + 8000;
            while (hit[0] == null && System.currentTimeMillis() < until) {
                try { lock.wait(500); } catch (InterruptedException e) { break; }
            }
        }
        MAIN.post(() -> stopDiscovery(c));
        if (hit[0] == null) return false;
        p.host = hit[0].host;
        p.port = hit[0].port;
        putPeer(c, p);
        return true;
    }

    // MARK: - What's synced

    /** This TV's categories: {cat: {hash, changed, data}}; `changed` moves when the content changes. */
    static synchronized JSONObject snapshot(Context c) {
        JSONObject out = new JSONObject();
        SharedPreferences p = Prefs.get(c, Prefs.SYNC);
        JSONObject seen;
        try { seen = new JSONObject(p.getString("hashes", "{}")); } catch (Exception e) { seen = new JSONObject(); }
        long now = System.currentTimeMillis();
        try {
            for (String cat : CATEGORIES) {
                if (!category(c, cat)) continue;
                JSONObject data = read(c, cat);
                if (data == null) continue;
                String hash = hex(Pake.sha256(data.toString().getBytes(StandardCharsets.UTF_8)));
                JSONObject s = seen.optJSONObject(cat);
                if (s == null || !hash.equals(s.optString("hash"))) {
                    s = new JSONObject().put("hash", hash).put("changed", now);
                    seen.put(cat, s);
                }
                out.put(cat, new JSONObject().put("hash", hash).put("changed", s.optLong("changed")).put("data", data));
            }
        } catch (org.json.JSONException ignored) {
        }
        p.edit().putString("hashes", seen.toString()).apply();
        return out;
    }

    private static JSONObject read(Context c, String cat) throws org.json.JSONException {
        switch (cat) {
            case APPS: {
                String saved = c.getSharedPreferences("home-apps", Context.MODE_PRIVATE).getString("packages", null);
                if (saved == null) return null;
                return new JSONObject().put("packages", new JSONArray(saved));
            }
            case SETTINGS: {
                SharedPreferences play = Prefs.get(c, Prefs.PLAYBACK);
                JSONObject playback = new JSONObject();
                Map<String, ?> all = play.getAll();
                for (String k : PLAYBACK_KEYS) {
                    Object v = all.get(k);
                    if (v instanceof Boolean || v instanceof String) playback.put(k, v);
                }
                return new JSONObject().put("playback", playback).put("look", Prefs.look(c)).put("remote", Prefs.remoteMap(c));
            }
            case SERVICES:
                return new JSONObject().put("off", new JSONArray(new java.util.TreeSet<>(Prefs.servicesOff(c))));
            case ADDONS:
                if (!AddonStore.connected(c)) return null;
                try {
                    return new JSONObject().put("addons", AddonStore.load(c)).put("account", AddonStore.account(c));
                } catch (Exception e) {
                    return null;
                }
            default:
                return null;
        }
    }

    /** Applies a category from the other TV, and remembers its hash and time so the two don't bounce. */
    static synchronized void apply(Context c, String cat, JSONObject data, long changed) {
        try {
            switch (cat) {
                case APPS: {
                    JSONArray a = data.optJSONArray("packages");
                    if (a == null) return;
                    List<String> keep = new ArrayList<>();
                    for (int i = 0; i < a.length() && keep.size() < HomeApps.MAX_SHELF; i++) {
                        String pkg = a.optString(i);
                        if (!keep.contains(pkg) && !pkg.equals(c.getPackageName())
                                && HomeApps.launchIntent(c.getPackageManager(), pkg) != null) keep.add(pkg);
                    }
                    HomeApps.saveShelf(c, keep);
                    break;
                }
                case SETTINGS: {
                    JSONObject playback = data.optJSONObject("playback");
                    SharedPreferences.Editor e = Prefs.get(c, Prefs.PLAYBACK).edit();
                    for (String k : PLAYBACK_KEYS) {
                        Object v = playback == null ? null : playback.opt(k);
                        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                        else if (v instanceof String) e.putString(k, (String) v);
                    }
                    e.commit();
                    String look = data.optString("look", "");
                    if (look.equals("sunset") || look.equals("day") || look.equals("night")) {
                        Prefs.get(c, Prefs.HOME_UI).edit().putString(Prefs.LOOK, look).commit();
                    }
                    JSONObject remote = data.optJSONObject("remote");
                    if (remote != null) {
                        boolean had = Prefs.remoteMap(c).length() > 0;
                        Prefs.setRemoteMap(c, remote);
                        if (!had && remote.length() > 0) HomeMode.requestRemoteKeys(c);
                    }
                    break;
                }
                case SERVICES: {
                    JSONArray off = data.optJSONArray("off");
                    if (off != null) Prefs.get(c, Prefs.SERVICES).edit().putString(Prefs.SERVICES_OFF, off.toString()).commit();
                    break;
                }
                case ADDONS: {
                    JSONArray addons = data.optJSONArray("addons");
                    if (addons == null || addons.length() == 0) return;   // never empties this TV's add-ons
                    String account = data.optString("account", "");
                    AddonStore.commit(c, account.isEmpty() ? AddonStore.seal(addons) : AddonStore.sealAccount(addons, account));
                    break;
                }
                default:
                    return;
            }
            SharedPreferences p = Prefs.get(c, Prefs.SYNC);
            JSONObject seen;
            try { seen = new JSONObject(p.getString("hashes", "{}")); } catch (Exception e) { seen = new JSONObject(); }
            JSONObject now = read(c, cat);
            if (now != null) {
                seen.put(cat, new JSONObject().put("hash", hex(Pake.sha256(now.toString().getBytes(StandardCharsets.UTF_8))))
                        .put("changed", changed));
                p.edit().putString("hashes", seen.toString()).apply();
            }
        } catch (Exception e) {
            AppLog.w("sync apply " + cat + ": " + e.getClass().getSimpleName());
        }
    }

    // MARK: - Server side (PhoneLink → /sync/…)

    private static final LinkedHashMap<String, Long> nonces = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 512; }
    };

    static PhoneLink.Response handle(Context c, PhoneLink.Request r) {
        try {
            switch (r.path) {
                case "/sync/pair/hello": return pairHello(c, body(r));
                case "/sync/pair/x": return pairX(c, body(r));
                case "/sync/pair/confirm": return pairConfirm(c, body(r));
                case "/sync/msg": return serveMessage(c, r);
                default: return PhoneLink.Response.json(404, json("error", "not found"));
            }
        } catch (Exception e) {
            AppLog.w("sync request failed: " + e.getClass().getSimpleName());
            return PhoneLink.Response.json(400, json("error", "bad request"));
        }
    }

    private static JSONObject body(PhoneLink.Request r) throws org.json.JSONException {
        return new JSONObject(new String(r.body, StandardCharsets.UTF_8));
    }

    private static synchronized PhoneLink.Response pairHello(Context c, JSONObject in) {
        if (!pairingOpen || failures >= 5) return PhoneLink.Response.json(200, json("error", "closed"));
        String peer = in.optString("id", "");
        if (!peer.matches("[0-9a-f-]{36}")) return PhoneLink.Response.json(400, json("error", "id"));
        pendingId = peer;
        pendingName = clip(in.optString("name", "TV"));
        pin = Pake.pin();
        pinUntil = System.currentTimeMillis() + 3 * 60_000L;
        pending = null;
        return PhoneLink.Response.json(200, json("id", id(c), "name", name(c)));
    }

    private static synchronized PhoneLink.Response pairX(Context c, JSONObject in) {
        if (!pairingOpen || pin == null || System.currentTimeMillis() > pinUntil || failures >= 5
                || !pendingId.equals(in.optString("id"))) {
            return PhoneLink.Response.json(200, json("error", Ui.tr("O código expirou. Tente de novo.", "The code expired. Try again.")));
        }
        // Every try is one guess at the code: a few per code, a few in all, until the page is opened again.
        failures++;
        BigInteger x;
        try { x = new BigInteger(in.optString("x", "0"), 16); } catch (NumberFormatException e) { x = BigInteger.ZERO; }
        Pake.Party b = new Pake.Party(false, pin, pendingId, id(c));
        if (failures % 3 == 0) {   // the next tries get a new code
            pin = Pake.pin();
            pinUntil = System.currentTimeMillis() + 3 * 60_000L;
        }
        if (!b.finish(x)) return PhoneLink.Response.json(400, json("error", "x"));
        pending = b;
        return PhoneLink.Response.json(200, json("y", b.mine.toString(16), "mac", hex(b.confirm("B"))));
    }

    private static synchronized PhoneLink.Response pairConfirm(Context c, JSONObject in) {
        Pake.Party b = pending;
        pending = null;
        if (b == null || !pendingId.equals(in.optString("id"))) return PhoneLink.Response.json(200, json("ok", false));
        if (!MessageDigest.isEqual(b.confirm("A"), unhex(in.optString("mac")))) {
            return PhoneLink.Response.json(200, json("ok", false));
        }
        failures = 0;
        Peer p = new Peer();
        p.id = pendingId;
        p.name = pendingName;
        p.secret = b.sharedSecret();
        putPeer(c, p);
        justPaired = pendingName;
        pin = null;
        refreshListening(c);
        try {
            return PhoneLink.Response.json(200, new JSONObject().put("ok", true).put("name", name(c)));
        } catch (org.json.JSONException e) {
            return PhoneLink.Response.json(200, json("ok", true));
        }
    }

    private static PhoneLink.Response serveMessage(Context c, PhoneLink.Request r) throws Exception {
        String from = r.header("x-luma-from");
        Peer peer = null;
        for (Peer p : peers(c)) if (p.id.equals(from)) peer = p;
        if (peer == null) return PhoneLink.Response.json(403, json("error", "unpaired"));
        String me = id(c);
        JSONObject in = new JSONObject(new String(open(peer.secret, from + ">" + me, r.body), StandardCharsets.UTF_8));
        long ts = in.optLong("ts");
        String nonce = in.optString("nonce");
        synchronized (nonces) {
            if (Math.abs(System.currentTimeMillis() - ts) > SKEW || nonce.length() < 16 || nonces.containsKey(nonce)) {
                return PhoneLink.Response.json(403, json("error", "replay"));
            }
            nonces.put(nonce, ts);
        }
        JSONObject out = new JSONObject();
        switch (in.optString("op")) {
            case "state": {
                out.put("name", name(c)).put("categories", snapshot(c));
                JSONArray enabled = new JSONArray();
                for (String cat : CATEGORIES) if (category(c, cat)) enabled.put(cat);
                out.put("enabled", enabled);
                break;
            }
            case "apply": {
                JSONObject cats = in.optJSONObject("data") == null ? null : in.getJSONObject("data").optJSONObject("categories");
                for (String cat : CATEGORIES) {
                    JSONObject o = cats == null ? null : cats.optJSONObject(cat);
                    if (o != null && category(c, cat) && o.optJSONObject("data") != null) {
                        apply(c, cat, o.getJSONObject("data"), o.optLong("changed"));
                    }
                }
                peer.syncedAt = System.currentTimeMillis();
                putPeer(c, peer);
                Prefs.get(c, Prefs.SYNC).edit().putLong("last", peer.syncedAt).apply();
                out.put("ok", true);
                break;
            }
            case "forget":
                removePeer(c, peer.id);
                out.put("ok", true);
                break;
            default:
                return PhoneLink.Response.json(400, json("error", "op"));
        }
        out.put("ts", System.currentTimeMillis());
        return PhoneLink.Response.bytes(200, "application/octet-stream",
                seal(peer.secret, me + ">" + from, out.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // MARK: - Client

    private static JSONObject message(Context c, Peer p, String op, JSONObject data) throws IOException {
        if (p.host.isEmpty() || p.port <= 0) throw new IOException("no address");
        String me = id(c);
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        try {
            JSONObject msg = new JSONObject().put("ts", System.currentTimeMillis()).put("nonce", hex(n)).put("op", op).put("data", data);
            byte[] sealed = seal(p.secret, me + ">" + p.id, msg.toString().getBytes(StandardCharsets.UTF_8));
            byte[] reply = request(p.host, p.port, "/sync/msg", me, sealed);
            JSONObject out = new JSONObject(new String(open(p.secret, p.id + ">" + me, reply), StandardCharsets.UTF_8));
            if (Math.abs(System.currentTimeMillis() - out.optLong("ts")) > SKEW) throw new IOException("stale reply");
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(Ui.tr("A outra TV não reconheceu esta. Pareie de novo.", "The other TV didn't recognize this one. Pair them again."));
        }
    }

    private static JSONObject post(String host, int port, String path, String from, JSONObject body) throws IOException {
        byte[] reply = request(host, port, path, from, body.toString().getBytes(StandardCharsets.UTF_8));
        try {
            return new JSONObject(new String(reply, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("bad reply");
        }
    }

    /** Plain HTTP/1.1 over a socket (the app's network policy keeps HttpURLConnection on https). LAN addresses only. */
    private static byte[] request(String host, int port, String path, String from, byte[] body) throws IOException {
        java.net.InetAddress address = java.net.InetAddress.getByName(host);
        if (!address.isSiteLocalAddress()) throw new IOException("not a local address");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), 4000);
            s.setSoTimeout(20_000);
            OutputStream out = s.getOutputStream();
            String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nContent-Type: application/octet-stream\r\n"
                    + (from == null ? "" : "X-Luma-From: " + from + "\r\n")
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                if (all.size() + n > 8 * 1024 * 1024) throw new IOException("reply too large");
                all.write(b, 0, n);
            }
            byte[] raw = all.toByteArray();
            int split = -1;
            for (int i = 0; i + 3 < raw.length; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') { split = i; break; }
            }
            if (split < 0) throw new IOException("bad reply");
            String status = new String(raw, 0, Math.min(split, 64), StandardCharsets.ISO_8859_1);
            byte[] payload = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
            if (!status.startsWith("HTTP/1.1 200")) {
                if (status.startsWith("HTTP/1.1 403")) throw new IOException(Ui.tr("A outra TV recusou.", "The other TV refused."));
                throw new IOException(status.split("\r\n")[0]);
            }
            return payload;
        }
    }

    // MARK: - Crypto helpers

    static byte[] seal(byte[] secret, String aad, byte[] plain) throws Exception { return SyncCipher.seal(secret, aad, plain); }
    static byte[] open(byte[] secret, String aad, byte[] sealed) throws Exception { return SyncCipher.open(secret, aad, sealed); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0 || s.length() > 256) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        return out;
    }

    private static String clip(String s) {
        s = s.replaceAll("[\\p{Cntrl}]", "").trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static JSONObject json(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }
}
