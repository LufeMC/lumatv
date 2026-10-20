package io.freemytv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Settings and system logic, data level only: the accessibility setting saved and restored by LumaTV / Original,
 * which remote keys are touched, which crashes count, QR codes, pairing, sync encryption and merge, updates.
 * Nothing here changes the TV's settings or the app's own data: files and preferences go to isolated test copies.
 * adb shell am instrument -w io.freemytv.tests/io.freemytv.SystemTests
 */
public class SystemTests extends Instrumentation {
    private int checks;

    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    /** Preferences and files under test names, so the app's real ones stay untouched. */
    private Context isolated() {
        final File dir = new File(getTargetContext().getCacheDir(), "system-tests");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("system-test-" + name, mode);
            }
            @Override public File getFilesDir() { return dir; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = isolated();
        try {
            accessibilitySetting();
            remoteKeys();
            watchdog(c);
            qr();
            pairing();
            syncCrypto();
            syncMerge(c);
            places();
            updates();
            playbackLog(c);
            result.putString("stream", "Passed " + checks + " settings and system checks.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED after " + checks + " checks: " + error + "\n");
            finish(0, result);
        } finally {
            for (String name : new String[]{"tv-sync", "playback-settings", "home-ui", "remote-keys", "my-services", "playback-log", "media-addons", "home-apps"}) {
                c.getSharedPreferences(name, 0).edit().clear().commit();
            }
            Watchdog.clear(c);
        }
    }

    /** LumaTV adds RemoteKeys to enabled_accessibility_services; Original puts back exactly what was there. */
    private void accessibilitySetting() {
        String me = "io.freemytv/io.freemytv.RemoteKeys", shortMe = "io.freemytv/.RemoteKeys";
        String other = "com.example.reader/com.example.reader.Service";
        check(RemoteKeys.withService("null", me).equals(me), "add to an unset value");
        check(RemoteKeys.withService("", me).equals(me), "add to an empty value");
        check(RemoteKeys.withService(other, me).equals(other + ":" + me), "add after the others, in order");
        check(RemoteKeys.withService(other + ":" + shortMe, me).equals(other + ":" + shortMe), "already there (short form): unchanged");
        check(RemoteKeys.contains(other + ":" + shortMe, me), "short and long forms are the same service");
        check(!RemoteKeys.contains("null", me), "nothing in an unset value");
        check(RemoteKeys.withoutService(other + ":" + me, me).equals(other), "remove ours only");
        check(RemoteKeys.withoutService(me, me).isEmpty(), "remove the only one");

        // Unchanged since LumaTV mode: exactly the saved value (or delete it, if it was unset).
        check(RemoteKeys.restoreServices("null", me, me) == null, "was unset: delete again");
        check(RemoteKeys.restoreServices("", me, me) == null, "was empty: delete");
        check(other.equals(RemoteKeys.restoreServices(other, other + ":" + me, me)), "exact previous value back");
        String spaced = other + ":com.b/.S";
        check(spaced.equals(RemoteKeys.restoreServices(spaced, spaced + ":" + me, me)), "previous list with two services back");
        // Someone changed other services meanwhile: keep their change, only take ours out.
        String added = other + ":com.new/.Service:" + me;
        check((other + ":com.new/.Service").equals(RemoteKeys.restoreServices(other, added, me)), "keeps a service added later");
        check(RemoteKeys.restoreServices(other, me, me) == null, "the other service was removed later: nothing left");
        check(RemoteKeys.restoreEnabled("0", null).equals("0"), "accessibility_enabled back to 0");
        check(RemoteKeys.restoreEnabled("null", null) == null, "accessibility_enabled unset again");
        check(RemoteKeys.restoreEnabled("0", other).equals("1"), "stays on while another service remains");
        check(RemoteKeys.component(getTargetContext()).equals(me), "component name");
    }

    private void remoteKeys() {
        int[] never = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_UNKNOWN};
        for (int code : never) check(!RemoteKeys.isLearnable(code), "never mapped: " + KeyEvent.keyCodeToString(code));
        int[] mappable = {KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_TV_INPUT,
                KeyEvent.KEYCODE_VIDEO_APP_1, KeyEvent.KEYCODE_FEATURED_APP_1, KeyEvent.KEYCODE_F1, 320};
        for (int code : mappable) check(RemoteKeys.isLearnable(code), "mappable: " + code);
        check(RemoteKeys.isMedia(KeyEvent.KEYCODE_MEDIA_PLAY) && RemoteKeys.isMedia(KeyEvent.KEYCODE_MEDIA_NEXT), "media keys");
        check(!RemoteKeys.isMedia(KeyEvent.KEYCODE_VOLUME_UP), "volume isn't a media key here");
        check(!RemoteKeys.label(KeyEvent.KEYCODE_PROG_RED).isEmpty() && !RemoteKeys.cap(400).isEmpty(), "labels");
    }

    private void watchdog(Context c) throws Exception {
        check(Watchdog.counts("io.freemytv.HomeActivity"), "home screen crash counts");
        check(Watchdog.counts("android.service.dreams.DreamActivity"), "screensaver (DreamActivity) crash counts");
        check(Watchdog.counts(Watchdog.SCREENSAVER), "screensaver crash counts");
        check(!Watchdog.counts("io.freemytv.PlayerActivity"), "player crash doesn't count");
        check(!Watchdog.counts("io.freemytv.SearchActivity"), "search crash doesn't count");
        check(!Watchdog.counts("io.freemytv.SettingsActivity"), "settings crash doesn't count");
        check(!Watchdog.counts(""), "unknown surface doesn't count");
        Watchdog.clear(c);
        long now = System.currentTimeMillis();
        Watchdog.record(c, now - 10 * 60_000L);   // outside the 5-minute window
        Watchdog.record(c, now - 1000);
        check(!Watchdog.crashedTooOften(c), "two recent crashes are not enough");
        Watchdog.record(c, now);
        check(!Watchdog.crashedTooOften(c), "a crash older than 5 minutes doesn't count");
        Watchdog.clear(c);
        Watchdog.record(c, now - 2000);
        Watchdog.record(c, now - 1000);
        Watchdog.record(c, now);
        check(Watchdog.crashedTooOften(c), "three in five minutes");
        Watchdog.clear(c);
        check(!Watchdog.crashedTooOften(c), "cleared");
    }

    private void qr() {
        boolean[][] m = Qr.modules("https://link.stremio.com/ABC123");
        check(m != null && m.length >= 21 && m.length == m[0].length, "QR grid");
        check(m[0][0] && m[0][6] && m[6][0] && !m[1][1] && m[3][3], "finder pattern top left");
        int size = 452;
        Bitmap b = Qr.bitmap("http://192.168.1.20:48613/t?k=abcdefghijklmnop", size);
        check(b != null && b.getWidth() == size && b.getHeight() == size, "bitmap size");
        check(b.getPixel(2, 2) == Color.WHITE && b.getPixel(size - 3, size - 3) == Color.WHITE, "quiet zone is white");
        int n = Qr.modules("http://192.168.1.20:48613/t?k=abcdefghijklmnop").length;
        int unit = size / (n + 2 * Qr.QUIET), offset = (size - unit * n) / 2;
        check(b.getPixel(offset + unit / 2, offset + unit / 2) == Color.BLACK, "first module is black");
        check(Qr.bitmap("", size) == null && Qr.modules(null) == null, "empty text");
        b.recycle();
    }

    private void pairing() {
        String a = "11111111-2222-3333-4444-555555555555", bId = "66666666-7777-8888-9999-000000000000";
        Pake.Party left = new Pake.Party(true, "4831", a, bId), right = new Pake.Party(false, "4831", a, bId);
        check(Pake.valid(left.mine) && Pake.valid(right.mine), "elements in the group");
        check(left.finish(right.mine) && right.finish(left.mine), "both finish");
        check(java.util.Arrays.equals(left.sharedSecret(), right.sharedSecret()), "same code: same secret");
        check(java.util.Arrays.equals(left.confirm("B"), right.confirm("B")), "confirmations match");
        Pake.Party wrong = new Pake.Party(true, "4832", a, bId), other = new Pake.Party(false, "4831", a, bId);
        check(wrong.finish(other.mine) && other.finish(wrong.mine), "wrong code still completes the math");
        check(!java.util.Arrays.equals(wrong.confirm("B"), other.confirm("B")), "wrong code: confirmation fails");
        check(!java.util.Arrays.equals(wrong.sharedSecret(), other.sharedSecret()), "wrong code: different secret");
        check(!Pake.valid(BigInteger.ONE) && !Pake.valid(BigInteger.ZERO) && !Pake.valid(Pake.P.subtract(BigInteger.ONE)), "rejects trivial elements");
        check(Pake.pin().matches("[0-9]{4}"), "4-digit code");
    }

    private void syncCrypto() throws Exception {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        byte[] sealed = TvSync.seal(secret, "a>b", "hello".getBytes(StandardCharsets.UTF_8));
        check(new String(TvSync.open(secret, "a>b", sealed), StandardCharsets.UTF_8).equals("hello"), "seal and open");
        boolean refused = false;
        try { TvSync.open(secret, "b>a", sealed); } catch (Exception e) { refused = true; }
        check(refused, "the other direction can't replay it");
        sealed[sealed.length - 1] ^= 1;
        refused = false;
        try { TvSync.open(secret, "a>b", sealed); } catch (Exception e) { refused = true; }
        check(refused, "tampered message refused");
        check(TvSync.hex(TvSync.unhex("00ff10")).equals("00ff10") && TvSync.unhex("zz").length == 0, "hex");
    }

    /** What the other TV sends is applied, and the hashes settle so the two don't bounce. */
    private void syncMerge(Context c) throws Exception {
        JSONObject settings = new JSONObject().put("playback", new JSONObject().put(Prefs.AUDIO_LANGUAGE, "pt-BR").put(Prefs.AUTOPLAY, false))
                .put("look", "night").put("remote", new JSONObject());
        TvSync.apply(c, TvSync.SETTINGS, settings, 1234);
        check("pt-BR".equals(Prefs.get(c, Prefs.PLAYBACK).getString(Prefs.AUDIO_LANGUAGE, "")), "audio language applied");
        check(!Prefs.get(c, Prefs.PLAYBACK).getBoolean(Prefs.AUTOPLAY, true), "autoplay applied");
        check("night".equals(Prefs.look(c)), "look applied");
        JSONObject snap = TvSync.snapshot(c);
        check(snap.getJSONObject(TvSync.SETTINGS).optLong("changed") == 1234, "applied category keeps the sender's time");
        TvSync.apply(c, TvSync.SERVICES, new JSONObject().put("off", new JSONArray().put("com.netflix.ninja")), 99);
        check(!Prefs.serviceOn(c, "com.netflix.ninja") && Prefs.serviceOn(c, "com.disney.disneyplus"), "services applied");
        TvSync.apply(c, TvSync.ADDONS, new JSONObject().put("addons", new JSONArray()), 5);
        check(!AddonStore.connected(c), "an empty add-on list never clears or creates anything");
        check(!TvSync.snapshot(c).has(TvSync.ADDONS), "no add-ons: nothing to send");
        Prefs.get(c, Prefs.PLAYBACK).edit().putString(Prefs.AUDIO_LANGUAGE, "es").commit();
        check(TvSync.snapshot(c).getJSONObject(TvSync.SETTINGS).optLong("changed") > 1234, "a local change moves the time");
    }

    private void places() throws Exception {
        JSONArray results = new JSONArray()
                .put(new JSONObject().put("name", "San Francisco").put("country", "Argentina").put("country_code", "AR"))
                .put(new JSONObject().put("name", "San Francisco").put("country", "United States").put("country_code", "US"));
        check("AR".equals(PhoneLink.pickPlace(results, "").optString("country_code")), "no country: first result");
        check("US".equals(PhoneLink.pickPlace(results, "United States").optString("country_code")), "by country name");
        check("US".equals(PhoneLink.pickPlace(results, "us").optString("country_code")), "by country code");
        check(PhoneLink.pickPlace(results, "Brazil") == null, "not in that country");
        check(PhoneLink.pickPlace(new JSONArray(), "") == null, "nothing found");
        check(PhoneLink.same("abc", "abc") && !PhoneLink.same("abc", "abd") && !PhoneLink.same("", ""), "token compare");
    }

    private void updates() throws Exception {
        String sha = "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82";
        JSONObject r = Updater.parse(new JSONObject().put("versionCode", 140).put("versionName", "1.1.40").put("sha256", sha)
                .put("notes", "Next episode starts at the credits\nSubtitles stay in sync"));
        check(r.optLong("versionCode") == 140 && r.optJSONArray("notes").length() == 2, "latest.json read");
        boolean refused = false;
        try { Updater.parse(new JSONObject().put("versionCode", 140).put("sha256", "abc")); } catch (Exception e) { refused = true; }
        check(refused, "a bad hash is refused");
        check(Updater.allowed(new URL("https://github.com/LufeMC/lumatv/releases/download/v1/FreeMyTV.apk"), null), "GitHub allowed");
        check(Updater.allowed(new URL("https://objects.githubusercontent.com/x"), null), "GitHub's file host allowed");
        check(!Updater.allowed(new URL("http://github.com/x"), null), "plain http refused");
        check(!Updater.allowed(new URL("https://example.com/x.apk"), null), "other hosts refused");
        check(Updater.allowed(new URL("https://test.example.com/x.apk"), "test.example.com"), "the test address allowed");
        Signature s1 = new Signature(new byte[]{1, 2, 3}), s2 = new Signature(new byte[]{4, 5, 6});
        check(Updater.sameSigners(new Signature[]{s1}, new Signature[]{new Signature(new byte[]{1, 2, 3})}), "same signer");
        check(!Updater.sameSigners(new Signature[]{s1}, new Signature[]{s2}), "different signer");
        check(!Updater.sameSigners(new Signature[0], new Signature[0]), "no signer");
    }

    private void playbackLog(Context c) {
        check(PlaybackLog.last(c).isEmpty(), "empty log");
        PlaybackLog.add(c, "A source had no sound, switched to another");
        check(PlaybackLog.last(c).endsWith("A source had no sound, switched to another"), "last line");
    }
}
