package io.freemytv;

import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import org.json.JSONObject;

/** Test Android key delivery through the enabled accessibility service. */
public class RemoteServiceTests extends TvTest {
    private int checks;
    private UiAutomation automation;
    private String inputDevice;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private String resumed() throws Exception {
        try (android.os.ParcelFileDescriptor fd = automation.executeShellCommand("dumpsys activity activities");
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) > 0 && out.size() < 2 * 1024 * 1024) out.write(bytes, 0, count);
            for (String line : out.toString("UTF-8").split("\n")) if (line.contains("mResumedActivity") || line.contains("topResumedActivity")) return line;
        }
        return "";
    }
    private void await(String component) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) { if (resumed().contains(component)) { checks++; return; } SystemClock.sleep(200); }
        throw new AssertionError("The expected screen did not open: " + component);
    }
    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = automation.executeShellCommand(command);
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) > 0 && out.size() < 128 * 1024) out.write(bytes, 0, count);
            return out.toString("UTF-8");
        }
    }
    private void key(int code) throws Exception {
        int linux = code == KeyEvent.KEYCODE_F1 ? 59 : code == KeyEvent.KEYCODE_MEDIA_PLAY ? 207 : code == KeyEvent.KEYCODE_MEDIA_NEXT ? 163 : 0;
        if (linux != 0) {
            try {
                shell("sendevent " + inputDevice + " 1 " + linux + " 1");
                shell("sendevent " + inputDevice + " 0 0 0");
            } finally {
                shell("sendevent " + inputDevice + " 1 " + linux + " 0");
                shell("sendevent " + inputDevice + " 0 0 0");
            }
            return;
        }
        long now = SystemClock.uptimeMillis(); automation.injectInputEvent(new KeyEvent(now, now, 0, code, 0), true);
        automation.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), 1, code, 0), true);
    }
    private void home() {
        getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            java.util.regex.Matcher device = java.util.regex.Pattern.compile("add device [0-9]+: (/dev/input/event[0-9]+)\\s+name:\\s+\"virtual-search\"").matcher(shell("getevent -pl"));
            if (!device.find()) throw new AssertionError("The existing virtual input device is unavailable"); inputDevice = device.group(1);
            if (!RemoteKeys.enabledInSystem(getTargetContext())) throw new AssertionError("The existing remote service is not enabled"); checks++;
            android.content.Context keyContext = new android.content.ContextWrapper(getTargetContext()) {
                @Override public java.io.File getFilesDir() { return super.getFilesDir().getParentFile(); }
            };
            if (!new java.io.File(keyContext.getFilesDir(), "adbkey").isFile()) throw new AssertionError("The existing approved key is missing");
            String enabled = android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(), "enabled_accessibility_services");
            java.io.File mapping = new java.io.File(keyContext.getFilesDir().getParentFile(), "shared_prefs/remote-keys.xml");
            byte[] savedMapping = mapping.isFile() ? java.nio.file.Files.readAllBytes(mapping.toPath()) : new byte[0];
            JSONObject profile = HomeMode.state(keyContext), copy = HomeMode.state(getTargetContext());
            Bundle status = new Bundle(); status.putString("stream", "Remote service preconditions: saved_mode=" + profile.optString("mode")
                    + "; saved_reverted=" + profile.optBoolean("homeReverted") + "; isolated_mode=" + copy.optString("mode") + "\n"); sendStatus(0, status);
            try (Adb adb = Adb.connectQuiet(keyContext)) { HomeMode.rebindRemoteKeys(getTargetContext(), adb); }
            SystemClock.sleep(1500);
            Prefs.setRemoteMap(getTargetContext(), new JSONObject().put(String.valueOf(KeyEvent.KEYCODE_F1), RemoteKeys.TARGET_SEARCH));
            home(); await("io.freemytv/.HomeActivity"); SystemClock.sleep(4500);
            key(KeyEvent.KEYCODE_F1); await("io.freemytv/.SearchActivity");
            key(KeyEvent.KEYCODE_BACK); await("io.freemytv/.HomeActivity"); SystemClock.sleep(1000);
            if (getTargetContext().getSystemService(android.media.AudioManager.class).isMusicActive()) throw new AssertionError("Audio must be idle for the stray-key test");
            key(KeyEvent.KEYCODE_MEDIA_PLAY); SystemClock.sleep(1500); await("io.freemytv/.HomeActivity");
            key(KeyEvent.KEYCODE_MEDIA_NEXT); SystemClock.sleep(1500); await("io.freemytv/.HomeActivity");
            if (!enabled.equals(android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(), "enabled_accessibility_services")))
                throw new AssertionError("The enabled-service list changed"); checks++;
            if (!java.util.Arrays.equals(savedMapping, mapping.isFile() ? java.nio.file.Files.readAllBytes(mapping.toPath()) : new byte[0]))
                throw new AssertionError("The household button mapping changed"); checks++;
            output.putString("stream", "Passed " + checks + " Android remote service checks. The test used an isolated button mapping.\n");
        } catch (Throwable error) { output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally { home(); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
