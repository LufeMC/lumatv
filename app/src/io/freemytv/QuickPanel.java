package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.LinearLayout;

/**
 * Quick settings on a long press of the remote's settings or menu button (player and home): a sleep timer that
 * pauses playback and lets the TV sleep, the TV's picture settings, and Bluetooth headphones.
 */
final class QuickPanel {
    private QuickPanel() {}

    static final int OFF = 0, HALF_HOUR = 1, HOUR = 2, EPISODE = 3;

    /** The player: when its episode ends, and what to do when the timer runs out. */
    interface Host {
        /** Wall-clock time the episode ends, or 0 when unknown. */
        long endsAt();
        /** Pause and let the screen sleep. */
        void sleep();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int mode = OFF;
    private static long at;   // elapsedRealtime the timer fires (HALF_HOUR, HOUR)
    private static Host host;
    private static final Runnable fire = () -> { mode = OFF; at = 0; if (host != null) host.sleep(); };

    static void attach(Host value) { host = value; }
    static void detach(Host value) { if (host == value) host = null; }

    static int mode() { return mode; }

    /** The player asks at the end of an episode: stop here instead of autoplaying? Clears the timer. */
    static boolean consumeEndOfEpisode() {
        if (mode != EPISODE) return false;
        mode = OFF; return true;
    }

    static void set(int value) {
        main.removeCallbacks(fire);
        mode = value; at = 0;
        if (value == HALF_HOUR || value == HOUR) {
            long delay = (value == HALF_HOUR ? 30 : 60) * 60000L;
            at = SystemClock.elapsedRealtime() + delay;
            main.postDelayed(fire, delay);
        }
    }

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        PlayerSheet sheet = new PlayerSheet(activity, Ui.tr("Ajustes rápidos", "Quick settings"), null, 680);
        sheet.dim(0.35f);
        LinearLayout list = sheet.list(sheet.body, 48);
        sheet.section(list, Ui.tr("Timer para dormir", "Sleep timer"));
        Host player = activity instanceof Host ? (Host) activity : null;
        String[] labels = {Ui.tr("Desligado", "Off"), "30 min", Ui.tr("1 hora", "1 hour"), Ui.tr("Fim deste episódio", "End of this episode")};
        int count = player != null ? 4 : 3;
        PlayerSheet.Row[] rows = new PlayerSheet.Row[count];
        for (int i = 0; i < count; i++) {
            final int choice = i;
            String note = "";
            if (i == mode && at > 0) note = Ui.tr("Pausa às ", "Pauses at ") + Ui.time(System.currentTimeMillis() + Math.max(0, at - SystemClock.elapsedRealtime()));
            if (i == EPISODE && player != null && player.endsAt() > 0) note = Ui.tr("Termina às ", "Ends at ") + Ui.time(player.endsAt());
            rows[i] = sheet.row(list, labels[i], note, i == mode, true, () -> {
                set(choice);
                for (int j = 0; j < rows.length; j++) rows[j].checked(j == choice);
            });
        }
        spacer(sheet, list);
        sheet.section(list, Ui.tr("Imagem", "Picture"));
        PlayerSheet.Row picture = sheet.row(list, Ui.tr("Modo de imagem", "Picture mode"), null, false, true, () -> {
            if (open(activity, "android.settings.PICTURE_SETTINGS", "com.android.tv.settings.PICTURE_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)) sheet.dismiss();
        });
        picture.checked(false); picture.trailing.symbol("chevron"); picture.trailing.setVisibility(android.view.View.VISIBLE);
        spacer(sheet, list);
        sheet.section(list, Ui.tr("Fones de ouvido", "Headphones"));
        PlayerSheet.Row headphones = sheet.row(list, Ui.tr("Parear fones Bluetooth", "Pair Bluetooth headphones"),
                Ui.tr("O som vai para os fones quando eles conectam", "Sound moves to headphones when they connect"), false, true, () -> {
            if (open(activity, Settings.ACTION_BLUETOOTH_SETTINGS, "android.settings.BLUETOOTH_PAIRING_SETTINGS", Settings.ACTION_SETTINGS)) sheet.dismiss();
        });
        headphones.trailing.symbol("chevron"); headphones.trailing.setVisibility(android.view.View.VISIBLE);
        sheet.show(rows[mode < count ? mode : 0]);
    }

    private static void spacer(PlayerSheet sheet, LinearLayout list) {
        android.view.View gap = new android.view.View(sheet.activity);
        list.addView(gap, new LinearLayout.LayoutParams(1, sheet.px(40)));
    }

    /** Opens the first of these settings screens the TV has. */
    static boolean open(Context context, String... actions) {
        for (String action : actions) {
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // No resolveActivity(): package visibility can hide the system settings app from it.
            try { context.startActivity(intent); return true; } catch (Exception ignored) {}
        }
        return false;
    }
}
