package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The LumaTV home screen (Android's "Home" role in LumaTV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        return getApplicationContext().getSharedPreferences(name, mode);
    }
    @Override public java.io.File getFilesDir() { return getApplicationContext().getFilesDir(); }
    static final int VOICE = 20;
    static final String EDIT_APPS = "io.freemytv.extra.EDIT_APPS";
    static java.lang.ref.WeakReference<HomeActivity> visible = new java.lang.ref.WeakReference<>(null);
    private HomeView home;
    long fontsMs, viewMs;
    private final android.os.Handler remote = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean quickShown;
    private final Runnable quick = () -> { quickShown = true; QuickPanel.show(this); };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            setContentView(CrashFallback.create(this));
            return;

        }
        long phase = android.os.SystemClock.elapsedRealtime();
        Ui.init(this); fontsMs = android.os.SystemClock.elapsedRealtime() - phase;
        Setup.migrateConfig(this);
        phase = android.os.SystemClock.elapsedRealtime();
        home = new HomeView(this); viewMs = android.os.SystemClock.elapsedRealtime() - phase;
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (RemoteKeys.TARGET_INPUTS.equals(getIntent().getStringExtra(RemoteKeys.EXTRA_OPEN))) home.openRemoteInputs();
        if (getIntent().getBooleanExtra(EDIT_APPS, false)) home.openAppEditor();
        if (getIntent().getBooleanExtra("play_opening", false)) home.playOpening();
    }

    @Override protected void onResume() {
        super.onResume(); visible = new java.lang.ref.WeakReference<>(this);
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (visible.get() == this) visible.clear();
        if (home != null) home.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (home != null) home.releaseHiddenArtwork();
        super.onStop();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (home != null) home.reset(true);
        if (home != null && RemoteKeys.TARGET_INPUTS.equals(intent.getStringExtra(RemoteKeys.EXTRA_OPEN))) home.openRemoteInputs();
        if (home != null && intent.getBooleanExtra(EDIT_APPS, false)) home.openAppEditor();
        if (home != null && intent.getBooleanExtra("play_opening", false)) home.playOpening();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null || home == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard != null && !heard.isEmpty()) home.voiceResult(heard.get(0));
    }

    @Override protected void onDestroy() {
        remote.removeCallbacksAndMessages(null);
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) { quickShown = false; remote.postDelayed(quick, android.view.ViewConfiguration.getLongPressTimeout()); }
            if (event.getAction() == KeyEvent.ACTION_UP) { remote.removeCallbacks(quick); if (!quickShown) startActivity(new Intent(this, SettingsActivity.class)); }
            return true;
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
