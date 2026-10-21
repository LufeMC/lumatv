package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.LruCache;

/**
 * Decoded frames around the selected position (PlayerScrub board): the chosen frame large with a ring and the time
 * above it, 10 s and 20 s either side smaller and fading. The saved playback position doesn't change.
 */
final class SeekPreview extends LinearLayout {
    private final ImageView[] images = new ImageView[5];
    private final TextView time, state;
    private final LruCache<Long, Bitmap> frames = new LruCache<>(32);
    private long selected, selectedPosition;
    long drawnPosition = -1, drawnAt;
    SeekPreview(Context context) {
        super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        time = PlayerViews.text(context, "", 44, Look.INK, Ui.semibold); time.setFontFeatureSettings("'tnum'");
        time.setGravity(Gravity.CENTER); addView(time, new LayoutParams(-2, -2));
        LinearLayout strip = new LinearLayout(context); strip.setGravity(Gravity.CENTER); strip.setClipChildren(false);
        int[][] sizes = {{224, 126}, {288, 162}, {480, 270}, {288, 162}, {224, 126}};
        float[] alpha = {0.4f, 0.7f, 1f, 0.7f, 0.4f};
        for (int i = 0; i < 5; i++) {
            images[i] = image(strip, sizes[i][0], sizes[i][1], i == 2);
            images[i].setAlpha(alpha[i]);
        }
        android.graphics.drawable.GradientDrawable ring = Look.round(context, Look.FALLBACK_BOTTOM, 14);
        ring.setStroke(px(4), Look.INK); images[2].setBackground(ring); images[2].setPadding(px(4), px(4), px(4), px(4));
        LayoutParams stripParams = new LayoutParams(-2, -2); stripParams.topMargin = px(16);
        addView(strip, stripParams);
        state = PlayerViews.text(context, "", Look.SMALL, Look.INK_3, Ui.regular); state.setGravity(Gravity.CENTER);
        LayoutParams stateParams = new LayoutParams(-2, -2); stateParams.topMargin = px(12);
        addView(state, stateParams);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private ImageView image(LinearLayout row, int width, int height, boolean measure) {
        ImageView image = new ImageView(getContext()) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (measure && drawnPosition != selected && isShown() && frames.get(selected) != null) {
                    drawnPosition = selected; drawnAt = android.os.SystemClock.elapsedRealtime();
                }
            }
        };
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Look.round(getContext(), Look.FALLBACK_BOTTOM, 14)); image.setClipToOutline(true);
        LayoutParams size = new LayoutParams(px(width), px(height)); size.leftMargin = px(12); size.rightMargin = px(12);
        row.addView(image, size); return image;
    }
    boolean select(long position) {
        drawnPosition = -1; return show(position);
    }
    private boolean show(long position) {
        selectedPosition = position; selected = key(position); time.setText(clock(position));
        Bitmap frame = frames.get(selected);
        for (int i = 0; i < 5; i++) images[i].setImageBitmap(i == 2 ? frame : frames.get(selected + (i - 2) * 10000L));
        state.setText(frame == null ? Ui.tr("Carregando prévia…", "Loading preview…") : "");
        return frame != null;
    }
    void frame(long position, Bitmap image) { image.prepareToDraw(); frames.put(key(position), image); show(selectedPosition); }
    void unavailable() { if (frames.get(selected) == null) state.setText(Ui.tr("Prévia indisponível", "Preview unavailable")); }
    boolean available(long position) { return frames.get(key(position)) != null; }
    void clear() { frames.evictAll(); for (ImageView image : images) image.setImageBitmap(null); }
    private static long key(long position) { return bucket(position); }
    static long bucket(long position) { return (position + 5000) / 10000 * 10000; }
    private int px(float value) { return Look.si(getContext(), value); }
    static String clock(long value) {
        long seconds = Math.max(0, value) / 1000; return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
