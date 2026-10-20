package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.LruCache;

/** Display decoded frames without changing the saved playback position. */
final class SeekPreview extends LinearLayout {
    private final ImageView previous, current, next;
    private final TextView time, state;
    private final LruCache<Long, Bitmap> frames = new LruCache<>(32);
    private long selected, selectedPosition;
    long drawnPosition = -1, drawnAt;
    SeekPreview(Context context) {
        super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER); setPadding(0, dp(10), 0, dp(10));
        LinearLayout strip = new LinearLayout(context); strip.setGravity(Gravity.CENTER);
        previous = image(strip, 152, 86); previous.setAlpha(0.6f);
        current = image(strip, 224, 126, true);
        android.graphics.drawable.GradientDrawable border = TvWidgets.shape(context, 0xFF151518); border.setStroke(dp(2), 0xFFF5F5F7); current.setBackground(border);
        next = image(strip, 152, 86); next.setAlpha(0.6f); addView(strip);
        time = TvWidgets.text(context, "", 18); time.setTypeface(Ui.medium); time.setGravity(Gravity.CENTER); addView(time);
        state = TvWidgets.text(context, "", 12); state.setTextColor(0xFFD0D0D8); state.setGravity(Gravity.CENTER); addView(state);
        TextView help = TvWidgets.text(context, Ui.tr("← → Escolher posição    OK Confirmar    Voltar Cancelar", "← → Choose position    OK Confirm    Back Cancel    Auto-confirm after 3 seconds"), 12);
        help.setGravity(Gravity.CENTER); addView(help); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private ImageView image(LinearLayout row, int width, int height) { return image(row, width, height, false); }
    private ImageView image(LinearLayout row, int width, int height, boolean measure) {
        ImageView image = new ImageView(getContext()) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (measure && drawnPosition != selected && isShown() && frames.get(selected) != null) {
                    drawnPosition = selected; drawnAt = android.os.SystemClock.elapsedRealtime();
                }
            }
        }; image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setPadding(dp(2), dp(2), dp(2), dp(2));
        image.setBackground(TvWidgets.shape(getContext(), 0xDD151518)); image.setClipToOutline(true);
        LayoutParams size = new LayoutParams(dp(width), dp(height)); size.leftMargin = dp(8); size.rightMargin = dp(8); row.addView(image, size); return image;
    }
    boolean select(long position) {
        drawnPosition = -1; return show(position);
    }
    private boolean show(long position) {
        selectedPosition = position; selected = key(position); time.setText(clock(position));
        Bitmap frame = frames.get(selected); current.setImageBitmap(frame); previous.setImageBitmap(frames.get(selected - 10000)); next.setImageBitmap(frames.get(selected + 10000));
        state.setText(frame == null ? Ui.tr("Carregando prévia…", "Loading preview…") : ""); return frame != null;
    }
    void frame(long position, Bitmap image) { frames.put(key(position), image); show(selectedPosition); }
    void unavailable() { if (frames.get(selected) == null) state.setText(Ui.tr("Prévia indisponível", "Preview unavailable")); }
    boolean available(long position) { return frames.get(key(position)) != null; }
    void clear() { frames.evictAll(); current.setImageBitmap(null); previous.setImageBitmap(null); next.setImageBitmap(null); }
    private static long key(long position) { return bucket(position); }
    static long bucket(long position) { return (position + 5000) / 10000 * 10000; }
    private int dp(float value) { return TvWidgets.dp(getContext(), value); }
    private static String clock(long value) {
        long seconds = value / 1000; return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
