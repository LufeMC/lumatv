package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * A picture tile for the View screens (episodes, cast, More like this): the picture cropped to fill, rounded (or a
 * circle), an optional progress line, and an honest fallback when there's no picture: the episode number large and
 * faint, a title, or initials on a dark gradient. Never a made-up image.
 */
final class ArtTile extends View {
    static final int NUMBER = 0, TITLE = 1, INITIALS = 2;
    private Bitmap image;
    private String fallback = "";
    private int fallbackKind = TITLE;
    private float progress = -1, radiusDesign = Look.R_CARD;
    private boolean circle, dim;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect src = new Rect();
    private Shader ground;
    private StaticLayout layout;

    ArtTile(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    ArtTile circle() {
        circle = true;
        fallbackKind = INITIALS;
        return this;
    }

    void setRadius(float design) {
        radiusDesign = design;
        invalidate();
    }

    void setImage(Bitmap b) {
        image = b;
        invalidate();
    }

    Bitmap image() { return image; }

    void setFallback(String value, int kind) {
        fallback = value == null ? "" : value;
        fallbackKind = kind;
        layout = null;
        invalidate();
    }

    /** 0..1, or below 0 for none. */
    void setProgress(float value) {
        progress = value;
        invalidate();
    }

    /** Watched episodes look a little quieter. */
    void setDim(boolean value) {
        dim = value;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        ground = new LinearGradient(0, 0, w, h, 0xFF24252B, 0xFF16171A, Shader.TileMode.CLAMP);
        layout = null;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float k = Look.s(getContext(), 1);
        box.set(0, 0, w, h);
        float r = circle ? Math.min(w, h) / 2f : radiusDesign * k;
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (image != null) {
            float scale = Math.max((float) w / image.getWidth(), (float) h / image.getHeight());
            float sw = w / scale, sh = h / scale;
            float top = image.getHeight() > image.getWidth() && !circle ? (image.getHeight() - sh) * 0.2f : (image.getHeight() - sh) / 2;
            src.set(Math.round((image.getWidth() - sw) / 2), Math.round(top), Math.round((image.getWidth() + sw) / 2), Math.round(top + sh));
            paint.setAlpha(dim ? 179 : 255);
            c.drawBitmap(image, src, box, paint);
            paint.setAlpha(255);
        } else {
            paint.setShader(ground);
            c.drawRect(box, paint);
            paint.setShader(null);
            drawFallback(c, w, h, k);
        }
        if (progress >= 0) {
            paint.setColor(Look.PROGRESS_TRACK);
            c.drawRect(0, h - 5 * k, w, h, paint);
            paint.setColor(Look.INK);
            c.drawRect(0, h - 5 * k, w * Math.min(1, progress), h, paint);
        }
        c.restore();
    }

    private void drawFallback(Canvas c, int w, int h, float k) {
        if (fallback.isEmpty()) return;
        if (fallbackKind == INITIALS) {
            text.setTypeface(Ui.semibold);
            text.setTextSize(h * 0.31f);
            text.setLetterSpacing(-0.03f);
            text.setColor(Ui.alpha(Look.INK, 0.6f));
            c.drawText(fallback, (w - text.measureText(fallback)) / 2, h / 2f + Ui.halfCap(text), text);
        } else if (fallbackKind == NUMBER) {
            text.setTypeface(Ui.semibold);
            text.setTextSize(64 * k);
            text.setLetterSpacing(-0.03f);
            text.setColor(Ui.alpha(Look.INK, 0.22f));
            c.drawText(fallback, 24 * k, h - 24 * k, text);
        } else {
            if (layout == null) {
                text.setTypeface(Ui.semibold);
                text.setTextSize(30 * k);
                text.setLetterSpacing(-0.02f);
                text.setColor(Ui.alpha(Look.INK, 0.4f));
                int width = Math.max(1, Math.round(w - 52 * k));
                layout = StaticLayout.Builder.obtain(fallback, 0, fallback.length(), text, width).setMaxLines(2)
                        .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            }
            c.save();
            c.translate(26 * k, h - 24 * k - layout.getHeight());
            layout.draw(c);
            c.restore();
        }
    }

    /** "Jim Parsons" → "JP". */
    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && Character.isLetter(part.charAt(0))) out.append(Character.toUpperCase(part.charAt(0)));
            if (out.length() == 2) break;
        }
        return out.toString();
    }
}
