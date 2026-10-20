package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remote buttons (Settings → Remote buttons). An accessibility service that only filters keys, in the main process
 * (the home screen's thread never delays a key press). It does two things, and only in LumaTV mode:
 * <ul>
 * <li>A button you mapped opens what you chose (an app, the inputs, the home screen). Only mapped buttons are taken.</li>
 * <li>A stray media key (play/pause…) pressed while the LumaTV home screen or screensaver is in front, with nothing
 * playing, is dropped: otherwise Android hands it to the last media app, which is how Netflix opened by itself at
 * night. With any app or our player in front, or audio playing, media keys pass untouched.</li>
 * </ul>
 * D-pad, OK, Back, Home, volume, power, digits, the mic and the settings button are never touched.
 * HomeMode turns the service on with LumaTV mode (settings put secure enabled_accessibility_services) and off with
 * Original, restoring what was there before.
 */
public class RemoteKeys extends AccessibilityService {
    static final String ACTION_LEARNED = "io.freemytv.REMOTE_KEY_LEARNED";
    static final String PERMISSION = "io.freemytv.permission.INTERNAL";
    static final String TARGET_HOME = "home", TARGET_INPUTS = "inputs", TARGET_SEARCH = "search";
    /** Extra on HomeActivity's intent: what a remote button asked the home screen to open ("inputs"). */
    static final String EXTRA_OPEN = "io.freemytv.extra.OPEN";

    private final Set<Integer> taken = new HashSet<>();     // keys whose press we took: their release is ours too
    private volatile String frontPackage = "", frontClass = "";
    private long modeCheckedAt;
    private boolean modeFree;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null) return;
        String p = pkg.toString();
        // The system bar, toasts and the keyboard don't change what's in front.
        if (p.equals("com.android.systemui") || p.startsWith("com.google.android.inputmethod")) return;
        frontPackage = p;
        frontClass = cls == null ? "" : cls.toString();
    }

    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean media = isMedia(code), learnable = isLearnable(code);
        if (!media && !learnable) return false;                 // everything else: untouched, at once
        if (event.getAction() != KeyEvent.ACTION_DOWN) return taken.remove(code);
        try {
            if (learnable && learning(this)) {
                if (event.getRepeatCount() == 0) {
                    sendBroadcast(new Intent(ACTION_LEARNED).setPackage(getPackageName()).putExtra("code", code), PERMISSION);
                }
                taken.add(code);
                return true;
            }
            if (!lumaMode()) return false;
            String target = learnable ? Prefs.remoteMapFresh(this).optString(String.valueOf(code), "") : "";
            if (!target.isEmpty()) {
                if (event.getRepeatCount() == 0) open(this, target);
                taken.add(code);
                return true;
            }
            if (media && dropStrayMedia()) {
                if (event.getRepeatCount() == 0) AppLog.w("dropped a stray media key " + KeyEvent.keyCodeToString(code));
                taken.add(code);
                return true;
            }
        } catch (RuntimeException e) {
            AppLog.w("remote key: " + e);
        }
        return false;
    }

    /** LumaTV's home screen or screensaver in front (or the TV dark after it), and no app playing sound. */
    private boolean dropStrayMedia() {
        boolean ours = getPackageName().equals(frontPackage)
                && (frontClass.endsWith("HomeActivity") || frontClass.contains("Dream") || frontClass.endsWith("Screensaver"));
        if (!ours) return false;
        AudioManager audio = getSystemService(AudioManager.class);
        return audio == null || !audio.isMusicActive();
    }

    private boolean lumaMode() {
        long now = SystemClock.uptimeMillis();
        if (modeCheckedAt == 0 || now - modeCheckedAt > 5_000) {
            JSONObject s = HomeMode.state(this);
            modeFree = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
            modeCheckedAt = now;
        }
        return modeFree;
    }

    /** Opens a mapping's target. Also used by the settings screen to try a mapping. */
    static boolean open(Context c, String target) {
        Intent intent;
        switch (target) {
            case TARGET_HOME:
                intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                break;
            case TARGET_INPUTS:
                intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(new ComponentName(c, HomeActivity.class)).putExtra(EXTRA_OPEN, TARGET_INPUTS);
                break;
            case TARGET_SEARCH:
                intent = new Intent(c, SearchActivity.class);
                break;
            default:
                intent = HomeApps.launchIntent(c.getPackageManager(), target);
        }
        if (intent == null) return false;
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("remote button target unavailable: " + target);
            return false;
        }
    }

    // MARK: - Which keys

    static boolean isMedia(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Buttons that may be mapped: app, color, function and TV buttons, plus codes beyond Android's list (TV makers'
     * own). Never navigation, OK, Back, Home, volume, power, digits, letters, media, the mic, captions or settings.
     */
    static boolean isLearnable(int code) {
        if (code == KeyEvent.KEYCODE_EXPLORER || code == KeyEvent.KEYCODE_ENVELOPE) return true;
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return true;
        switch (code) {
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_ZOOM_IN: case KeyEvent.KEYCODE_ZOOM_OUT:
            case KeyEvent.KEYCODE_TV: case KeyEvent.KEYCODE_WINDOW: case KeyEvent.KEYCODE_GUIDE: case KeyEvent.KEYCODE_DVR:
            case KeyEvent.KEYCODE_BOOKMARK: case KeyEvent.KEYCODE_TV_INPUT: case KeyEvent.KEYCODE_STB_INPUT: case KeyEvent.KEYCODE_PROG_RED: case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_PROG_BLUE: case KeyEvent.KEYCODE_CONTACTS:
            case KeyEvent.KEYCODE_CALENDAR: case KeyEvent.KEYCODE_MUSIC: case KeyEvent.KEYCODE_CALCULATOR:
            case KeyEvent.KEYCODE_MEDIA_TOP_MENU: case KeyEvent.KEYCODE_TV_DATA_SERVICE: case KeyEvent.KEYCODE_ALL_APPS:
            case KeyEvent.KEYCODE_THUMBS_UP: case KeyEvent.KEYCODE_THUMBS_DOWN:
                return true;
            default:
                break;
        }
        if (code >= KeyEvent.KEYCODE_BUTTON_1 && code <= KeyEvent.KEYCODE_BUTTON_16) return true;
        if (code >= KeyEvent.KEYCODE_TV_RADIO_SERVICE && code <= KeyEvent.KEYCODE_TV_TIMER_PROGRAMMING) return true;
        if (code >= KeyEvent.KEYCODE_VIDEO_APP_1 && code <= KeyEvent.KEYCODE_DEMO_APP_4) return true;
        return code > KeyEvent.KEYCODE_DEMO_APP_4 && code < 1000;
    }

    /** A short name for a button: "Red button", "Button 3", or the code. */
    static String label(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: return Ui.tr("Botão vermelho", "Red button");
            case KeyEvent.KEYCODE_PROG_GREEN: return Ui.tr("Botão verde", "Green button");
            case KeyEvent.KEYCODE_PROG_YELLOW: return Ui.tr("Botão amarelo", "Yellow button");
            case KeyEvent.KEYCODE_PROG_BLUE: return Ui.tr("Botão azul", "Blue button");
            case KeyEvent.KEYCODE_GUIDE: return Ui.tr("Botão Guia", "Guide button");
            case KeyEvent.KEYCODE_INFO: return Ui.tr("Botão Info", "Info button");
            case KeyEvent.KEYCODE_TV_INPUT: return Ui.tr("Botão Entradas", "Input button");
            case KeyEvent.KEYCODE_ALL_APPS: return Ui.tr("Botão Apps", "Apps button");
            default: break;
        }
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) {
            String words = name.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
            return Ui.tr("Botão ", "") + Character.toUpperCase(words.charAt(0)) + words.substring(1)
                    + Ui.tr("", " button");
        }
        return Ui.tr("Botão " + code, "Button " + code);
    }

    /** The text on the key cap in Settings: the app name for app buttons, the color or a short code otherwise. */
    static String cap(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: return Ui.tr("Vermelho", "Red");
            case KeyEvent.KEYCODE_PROG_GREEN: return Ui.tr("Verde", "Green");
            case KeyEvent.KEYCODE_PROG_YELLOW: return Ui.tr("Amarelo", "Yellow");
            case KeyEvent.KEYCODE_PROG_BLUE: return Ui.tr("Azul", "Blue");
            default: break;
        }
        String name = KeyEvent.keyCodeToString(code);
        if (!name.startsWith("KEYCODE_")) return String.valueOf(code);
        String words = name.substring(8).replace('_', ' ');
        return words.length() > 12 ? words.substring(0, 12) : words;
    }

    // MARK: - Learning ("Press a button now to find it")

    /** Settings asks the service (another process) to report the next buttons instead of acting on them. */
    static void setLearning(Context c, boolean on) {
        File f = learnFile(c);
        if (!on) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(String.valueOf(System.currentTimeMillis() + 45_000).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("learn flag not saved: " + e.getMessage());
        }
    }

    static boolean learning(Context c) {
        File f = learnFile(c);
        if (!f.exists()) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[32];
            int n = in.read(data);
            long until = n <= 0 ? 0 : Long.parseLong(new String(data, 0, n, StandardCharsets.UTF_8).trim());
            return System.currentTimeMillis() < until;
        } catch (Exception e) {
            return false;
        }
    }

    private static File learnFile(Context c) {
        return new File(c.getFilesDir(), "remote-learn");
    }

    // MARK: - The system setting (pure logic: HomeMode runs the commands; tested in RemoteKeysTests)

    static String component(Context c) {
        return new ComponentName(c.getPackageName(), RemoteKeys.class.getName()).flattenToString();
    }

    /** `settings get` says "null" for a missing value. */
    static boolean unset(String value) {
        return value == null || value.trim().isEmpty() || value.trim().equals("null");
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (unset(value)) return out;
        for (String part : value.trim().split(":")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out;
    }

    private static boolean same(String a, String b) {
        ComponentName x = ComponentName.unflattenFromString(a), y = ComponentName.unflattenFromString(b);
        return x != null && x.equals(y) || a.equals(b);
    }

    static boolean contains(String services, String component) {
        for (String s : split(services)) if (same(s, component)) return true;
        return false;
    }

    /** enabled_accessibility_services with ours added at the end (others untouched, in order). */
    static String withService(String services, String component) {
        if (contains(services, component)) return unset(services) ? component : services.trim();
        List<String> list = split(services);
        list.add(component);
        return String.join(":", list);
    }

    /** enabled_accessibility_services without ours ("" when nothing is left). */
    static String withoutService(String services, String component) {
        List<String> kept = new ArrayList<>();
        for (String s : split(services)) if (!same(s, component)) kept.add(s);
        return String.join(":", kept);
    }

    /**
     * What to put back when leaving LumaTV mode: exactly the value saved before (null = delete the setting, as it
     * was unset), unless other services were turned on or off meanwhile; then today's list without ours.
     */
    static String restoreServices(String saved, String current, String component) {
        String now = withoutService(current, component);
        String before = withoutService(saved, component);
        if (now.equals(before)) return unset(saved) ? null : saved.trim();
        return now.isEmpty() ? null : now;
    }

    /** accessibility_enabled to put back: the saved value (null = delete) when no other service remains, else "1". */
    static String restoreEnabled(String savedEnabled, String restoredServices) {
        if (restoredServices != null && !restoredServices.isEmpty()) return "1";
        return unset(savedEnabled) ? null : savedEnabled.trim();
    }

    // MARK: - Mappings

    /** Saves one mapping (target "" removes it). Returns the new map. */
    static JSONObject map(Context c, int code, String target) {
        JSONObject m = Prefs.remoteMap(c);
        try {
            if (target == null || target.isEmpty()) m.remove(String.valueOf(code)); else m.put(String.valueOf(code), target);
        } catch (Exception ignored) {
        }
        Prefs.setRemoteMap(c, m);
        return m;
    }

    static boolean enabledInSystem(Context c) {
        String value = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return contains(value, component(c));
    }
}
