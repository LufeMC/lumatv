package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Show saved episode artwork and position in the approved centered recovery card. */
final class ResumePrompt {
    final Dialog dialog;
    final ArtTile artwork;
    final PlayerViews.Pill resume, later;

    ResumePrompt(Activity activity, ContinueWatching.Entry entry, Runnable play, Runnable dismiss) {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(activity);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(px(activity, 20), px(activity, 20), px(activity, 20), px(activity, 52));
        card.setBackground(PlayerViews.glass(activity, 36));
        artwork = new ArtTile(activity);
        artwork.setFallback(entry.title, ArtTile.TITLE);
        artwork.setProgress(entry.duration > 0 ? Math.min(1, entry.position / (float) entry.duration) : -1);
        card.addView(artwork, new LinearLayout.LayoutParams(px(activity, 640), px(activity, 360)));
        TextView heading = PlayerViews.text(activity, Ui.tr("Continuar de onde parou?", "Resume playback?"), 44, Look.INK, Ui.semibold);
        heading.setAccessibilityHeading(true);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading, below(activity, 44));
        String episode = entry.episode > 0 ? " · " + entry.code() : "";
        TextView line = PlayerViews.text(activity, entry.title + episode + " · " + SeekPreview.clock(entry.position), 26, Look.INK_2, Ui.regular);
        line.setGravity(Gravity.CENTER);
        line.setMaxLines(2);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(line, below(activity, 14));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER);
        resume = new PlayerViews.Pill(activity, "play", false, Ui.tr("Continuar", "Resume"), 72, 26, Look.RAISED);
        later = new PlayerViews.Pill(activity, null, false, Ui.tr("Agora não", "Not now"), 72, 26, Look.RAISED);
        resume.setOnClickListener(v -> { dialog.dismiss(); play.run(); });
        later.setOnClickListener(v -> { dialog.dismiss(); dismiss.run(); });
        buttons.addView(resume, new LinearLayout.LayoutParams(-2, px(activity, 72)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(activity, 72));
        lp.leftMargin = px(activity, 16);
        buttons.addView(later, lp);
        card.addView(buttons, below(activity, 44));
        root.addView(card, new FrameLayout.LayoutParams(px(activity, 680), -2, Gravity.CENTER));
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.65f);
        dialog.setOnDismissListener(d -> artwork.setImage(null));
        dialog.show();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        resume.requestFocus();
        Thread image = new Thread(() -> {
            Bitmap bitmap = null;
            try {
                ContinueWatching.resolve(entry);
                bitmap = entry.art.startsWith("/") ? TitleArtwork.load(entry.art, "w780") : HomeArt.fetch(activity.getApplicationContext(), entry.art, true);
            } catch (Exception ignored) {}
            final Bitmap ready = bitmap;
            activity.runOnUiThread(() -> { if (dialog.isShowing() && !activity.isFinishing()) artwork.setImage(ready); });
        }, "lumatv-resume-art");
        image.setPriority(Thread.MIN_PRIORITY);
        image.start();
    }

    private static int px(Activity activity, float value) { return Look.si(activity, value); }
    private static LinearLayout.LayoutParams below(Activity activity, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(activity, top);
        return lp;
    }
}
