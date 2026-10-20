package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class MediaCredits {
    static void show(Activity activity) {
        android.widget.LinearLayout content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = TvWidgets.dp(activity, 24); content.setPadding(padding, padding, padding, 0);
        android.widget.ImageView logo = new android.widget.ImageView(activity);
        logo.setImageResource(R.drawable.tmdb_logo); logo.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        logo.setContentDescription("TMDB");
        content.addView(logo, new android.widget.LinearLayout.LayoutParams(TvWidgets.dp(activity, 160), TvWidgets.dp(activity, 28)));
        content.addView(TvWidgets.text(activity, "This product uses the TMDB API but is not endorsed or certified by TMDB.\n\n"
                + "Availability: JustWatch via TMDB. Service links: Stremio WatchHub.\n\n"
                + "Playback: AndroidX Media3 1.8.1. Add-on protocol: Stremio.\n\n"
                + "Free My TV: simbasjungles/free-my-tv. This application is a modified build.", 15));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle(Ui.tr("Créditos", "Credits"))
                .setView(scroll)
                .setPositiveButton(Ui.tr("Fechar", "Close"), null)
                .setNeutralButton(Ui.tr("Licenças", "Licenses"), (dialog, which) -> {
                    String notices = asset(activity, "FreeMyTV-LICENSE.txt") + "\n\n" + asset(activity, "ThirdPartyNotices.txt")
                            + "\n\n" + asset(activity, "Apache-2.0.txt") + "\n\n" + asset(activity, "JCodec-LICENSE.txt");
                    new AlertDialog.Builder(activity).setTitle(Ui.tr("Licenças", "Licenses")).setMessage(notices)
                            .setPositiveButton(Ui.tr("Fechar", "Close"), null).show();
                }).show();
    }
    private static String asset(Activity activity, String name) {
        try (InputStream input = activity.getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
}
