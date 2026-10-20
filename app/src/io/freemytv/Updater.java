package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Updates from GitHub Releases (LufeMC/lumatv), without a computer. Each release carries the APK and a latest.json
 * ({versionCode, versionName, apk, sha256, notes}); tools/release.sh makes both. An update is installed only when
 * its SHA-256 matches AND it's signed with the same certificate as the installed app (and is newer, and is this app).
 * It installs through the app's own ADB connection, then the new version speeds up its code (afterUpdate).
 *
 * Runs in the main process (ModeJob). The settings screen reads the state file, update-state.json; your choices
 * (automatic updates, a test address) are in the "updates" preferences, written only by the settings screen.
 */
final class Updater {
    private Updater() {}

    static final String AUTO = "auto", CHECK = "check";
    static final String RELEASES = "https://api.github.com/repos/LufeMC/lumatv/releases/latest";
    /** Prefs.UPDATES key: an https base address with latest.json and the APK next to it, for testing. */
    static final String BASE = "base";
    private static final long MAX_APK = 80L * 1024 * 1024;
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("api.github.com", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com"));
    private static final Object LOCK = new Object();

    // MARK: - What the settings screen shows

    /** The newer release waiting, or null: {versionCode, versionName, notes[], ready}. */
    static JSONObject available(Context c) {
        JSONObject s = state(c);
        JSONObject r = s.optJSONObject("release");
        if (r == null || r.optLong("versionCode") <= installedCode(c)) return null;
        return r;
    }

    /** "checking" | "downloading" | "installing" | "" and the last error ("" if none). */
    static JSONObject state(Context c) {
        File f = file(c);
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(read(in, 256 * 1024), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @SuppressWarnings("deprecation")
    static boolean automaticOn(Context c) {
        return c.getApplicationContext().getSharedPreferences(Prefs.UPDATES, Context.MODE_MULTI_PROCESS)
                .getBoolean(Prefs.AUTO_UPDATE, true);
    }

    static void setAutomatic(Context c, boolean on) {
        Prefs.get(c, Prefs.UPDATES).edit().putBoolean(Prefs.AUTO_UPDATE, on).commit();
    }

    static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Config.VERSION_CODE;
        }
    }

    /** Settings → "Update now": runs as a job in the main process. */
    static void requestInstall(Context c) {
        ModeJob.schedule(c, ModeJob.UPDATE, null, 0);
    }

    /** Settings → "Check for updates". */
    static void requestCheck(Context c) {
        ModeJob.schedule(c, ModeJob.UPDATE, CHECK, 0);
    }

    static void checkNow(Context c) {
        synchronized (LOCK) {
            try {
                check(c);
            } catch (Exception e) {
                fail(c, e);
            }
        }
    }

    // MARK: - Jobs

    /** The 12-hourly job: check once a day; with automatic updates on, download and wait for an idle night. */
    static void automatic(Context c) {
        if (!automaticOn(c)) return;
        JSONObject s = state(c);
        if (System.currentTimeMillis() - s.optLong("checkedAt") < 20 * 60 * 60_000L && available(c) == null) return;
        try {
            JSONObject release = check(c);
            if (release == null) return;
            download(c, release);
            ModeJob.scheduleIdleUpdate(c, 0);
        } catch (Exception e) {
            fail(c, e);
        }
    }

    /**
     * Installs the newest release. `auto`: from the idle job: only between 1:00 and 6:00 with the screen off, and
     * never with the debugging prompt (it waits for the next night instead).
     */
    static void installNow(Context c, boolean auto) {
        synchronized (LOCK) {
            try {
                if (auto) {
                    if (!automaticOn(c)) return;
                    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    PowerManager pm = c.getSystemService(PowerManager.class);
                    boolean dark = pm != null && !pm.isInteractive();
                    if (hour < 1 || hour >= 6 || !dark) {
                        ModeJob.scheduleIdleUpdate(c, 60 * 60_000L);
                        return;
                    }
                }
                JSONObject release = check(c);
                if (release == null) {
                    put(c, "phase", "");
                    return;
                }
                File apk = download(c, release);
                put(c, "phase", "installing");
                JSONObject s = state(c);
                s.put("installing", release.optLong("versionCode")).put("installingAt", System.currentTimeMillis());
                save(c, s);
                String out;
                try (Adb adb = auto ? Adb.connectQuiet(c) : Adb.connect(c, 120_000, () -> put(c, "phase", "approval"));
                     FileInputStream in = new FileInputStream(apk)) {
                    put(c, "phase", "installing");
                    out = adb.install(in, apk.length());
                }
                // Usually not reached on success: the install stops this app. afterUpdate finishes the job.
                if (!out.contains("Success")) throw new IOException(out.trim().isEmpty() ? "install failed" : out.trim());
                put(c, "phase", "");
            } catch (Adb.NotApproved e) {
                put(c, "phase", "");
                ModeJob.scheduleIdleUpdate(c, 24 * 60 * 60_000L);
            } catch (Exception e) {
                fail(c, e);
            }
        }
    }

    /** After any update of this app (MY_PACKAGE_REPLACED): compile the new code, TCL's auto-start, a notice. */
    static void afterUpdate(Context c) {
        JSONObject s = state(c);
        boolean ours = s.optLong("installing") == installedCode(c);
        try (Adb adb = Adb.connectQuiet(c)) {
            adb.shell("cmd package compile -m speed -f " + c.getPackageName());
            if (Build.BRAND.toLowerCase(Locale.ROOT).contains("tcl") || Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("tcl")) {
                adb.shell("appops set " + c.getPackageName() + " AUTO_START allow");
            }
        } catch (Exception e) {
            AppLog.w("after update: " + e.getMessage());
        }
        if (ours) {
            HomeMode.notice(c, Ui.tr("Atualizada para a LumaTV ", "Updated to LumaTV ") + Config.VERSION_NAME + ".");
        }
        s.remove("installing");
        s.remove("release");
        s.remove("error");
        try { s.put("phase", ""); } catch (JSONException ignored) {}
        save(c, s);
        File apk = apkFile(c);
        //noinspection ResultOfMethodCallIgnored
        apk.delete();
    }

    // MARK: - Steps

    /** Fetches the latest release's latest.json. Returns it when newer than this app (also saved), else null. */
    static JSONObject check(Context c) throws IOException, JSONException {
        put(c, "phase", "checking");
        String base = base(c);
        JSONObject latest;
        String apkUrl;
        if (!base.isEmpty()) {
            if (!base.endsWith("/")) base += "/";
            URL b = new URL(base);
            if (!"https".equals(b.getProtocol())) throw new IOException("the test address must be https");
            latest = new JSONObject(new String(get(base + "latest.json", 256 * 1024, b.getHost()), StandardCharsets.UTF_8));
            apkUrl = resolve(latest.optString("apk"), base, null, b.getHost());
        } else {
            JSONObject release = new JSONObject(new String(get(RELEASES, 1024 * 1024, null), StandardCharsets.UTF_8));
            JSONArray assets = release.optJSONArray("assets");
            String json = asset(assets, "latest.json");
            if (json == null) throw new IOException("the release has no latest.json");
            latest = new JSONObject(new String(get(json, 256 * 1024, null), StandardCharsets.UTF_8));
            apkUrl = resolve(latest.optString("apk"), null, assets, null);
        }
        JSONObject r = parse(latest);
        r.put("url", apkUrl);
        JSONObject s = state(c);
        s.put("checkedAt", System.currentTimeMillis()).put("phase", "").remove("error");
        if (r.optLong("versionCode") > installedCode(c)) s.put("release", r); else s.remove("release");
        save(c, s);
        return r.optLong("versionCode") > installedCode(c) ? r : null;
    }

    /** latest.json, checked: a version code, a 64-hex SHA-256, notes as a list of short lines. */
    static JSONObject parse(JSONObject latest) throws JSONException, IOException {
        long code = latest.optLong("versionCode", -1);
        String sha = latest.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (code <= 0 || !sha.matches("[0-9a-f]{64}")) throw new IOException("latest.json is incomplete");
        JSONArray notes = new JSONArray();
        Object raw = latest.opt("notes");
        if (raw instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) raw).length() && notes.length() < 5; i++) notes.put(((JSONArray) raw).optString(i));
        } else if (raw instanceof String) {
            for (String line : ((String) raw).split("\n")) if (!line.trim().isEmpty() && notes.length() < 5) notes.put(line.trim());
        }
        return new JSONObject().put("versionCode", code).put("versionName", latest.optString("versionName", String.valueOf(code)))
                .put("sha256", sha).put("notes", notes);
    }

    /** Downloads (or reuses) the APK and verifies it. Returns the verified file. */
    static File download(Context c, JSONObject release) throws Exception {
        File apk = apkFile(c);
        String sha = release.getString("sha256");
        if (!(apk.exists() && sha.equals(sha256(apk)))) {
            put(c, "phase", "downloading");
            File tmp = new File(apk.getPath() + ".part");
            String url = release.getString("url");
            URL u = new URL(url);
            String base = base(c);
            String extraHost = base.isEmpty() ? null : new URL(base.endsWith("/") ? base : base + "/").getHost();
            if (!allowed(u, extraHost)) throw new IOException("unexpected download address");
            fetch(url, tmp, extraHost);
            if (!sha.equals(sha256(tmp))) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException(Ui.tr("o arquivo baixado não confere", "the downloaded file doesn't match"));
            }
            if (!tmp.renameTo(apk)) throw new IOException("could not keep the update");
        }
        verify(c, apk, release.optLong("versionCode"));
        put(c, "phase", "");
        return apk;
    }

    /** Same package, newer, and signed with exactly the installed app's certificate(s). */
    static void verify(Context c, File apk, long expectedCode) throws IOException {
        PackageManager pm = c.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !c.getPackageName().equals(archive.packageName)) {
            throw new IOException(Ui.tr("o arquivo não é da LumaTV", "the file isn't LumaTV"));
        }
        if (archive.getLongVersionCode() <= installedCode(c) || (expectedCode > 0 && archive.getLongVersionCode() != expectedCode)) {
            throw new IOException(Ui.tr("a versão não confere", "the version doesn't match"));
        }
        try {
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive.signingInfo == null || installed.signingInfo == null
                    || !sameSigners(installed.signingInfo.getApkContentsSigners(), archive.signingInfo.getApkContentsSigners())) {
                throw new IOException(Ui.tr("a assinatura não é a desta TV", "it isn't signed like the installed app"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("installed app not found");
        }
    }

    static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        Set<String> x = new HashSet<>(), y = new HashSet<>();
        for (Signature s : a) x.add(hex(digest(s.toByteArray())));
        for (Signature s : b) y.add(hex(digest(s.toByteArray())));
        return x.equals(y);
    }

    // MARK: - Network (https only, GitHub's hosts or the test address; redirects followed by hand)

    private static String asset(JSONArray assets, String name) {
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && name.equals(a.optString("name"))) return a.optString("browser_download_url", null);
        }
        return null;
    }

    private static String resolve(String apk, String base, JSONArray assets, String extraHost) throws IOException {
        if (apk == null || apk.isEmpty()) apk = "FreeMyTV.apk";
        if (apk.startsWith("https://")) {
            if (!allowed(new URL(apk), extraHost)) throw new IOException("unexpected download address");
            return apk;
        }
        if (!apk.matches("[A-Za-z0-9._-]{1,80}\\.apk")) throw new IOException("unexpected APK name");
        if (base != null) return base + apk;
        String url = asset(assets, apk);
        if (url == null) throw new IOException("the release has no " + apk);
        return url;
    }

    static boolean allowed(URL u, String extraHost) {
        if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null) return false;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        return HOSTS.contains(host) || host.equals(extraHost);
    }

    private static HttpURLConnection open(String url, String extraHost) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            if (!allowed(u, extraHost)) throw new IOException("unexpected address");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", "LumaTV/" + Config.VERSION_NAME);
            c.setRequestProperty("Accept", u.getHost().equals("api.github.com") ? "application/vnd.github+json" : "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("redirect without an address");
                url = new URL(u, next).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException(code == 404 ? Ui.tr("nenhuma versão publicada", "no release published") : "HTTP " + code);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static byte[] get(String url, int limit, String extraHost) throws IOException {
        HttpURLConnection c = open(url, extraHost);
        try (InputStream in = c.getInputStream()) {
            return read(in, limit);
        } finally {
            c.disconnect();
        }
    }

    private static void fetch(String url, File to, String extraHost) throws IOException {
        HttpURLConnection c = open(url, extraHost);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
            if (c.getContentLengthLong() > MAX_APK) throw new IOException("the update is too large");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_APK) throw new IOException("the update is too large");
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > limit) throw new IOException("response too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // MARK: - Files

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) d.update(buffer, 0, n);
            return hex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static File apkFile(Context c) {
        File dir = new File(c.getFilesDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "LumaTV.apk");
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "update-state.json");
    }

    private static void fail(Context c, Exception e) {
        AppLog.w("update: " + e.getMessage());
        JSONObject s = state(c);
        try { s.put("phase", "").put("error", String.valueOf(e.getMessage())).put("errorAt", System.currentTimeMillis()); }
        catch (JSONException ignored) {}
        save(c, s);
    }

    private static void put(Context c, String key, String value) {
        synchronized (LOCK) {
            JSONObject s = state(c);
            try { s.put(key, value); } catch (JSONException ignored) {}
            if ("phase".equals(key) && !value.isEmpty()) s.remove("error");
            save(c, s);
        }
    }

    private static void save(Context c, JSONObject s) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, "update-state.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file(c));
    }

    /** The test address (written by another process, hence re-read from disk). */
    @SuppressWarnings("deprecation")
    private static String base(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(Prefs.UPDATES, Context.MODE_MULTI_PROCESS);
        return p.getString(BASE, "").trim();
    }
}
