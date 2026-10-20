package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The player's line icons, drawn in the 24-unit grid of the canvas boards. Ink at rest, dark ink when focused (its
 * own focus, or its parent's when it sits inside a pill or row). With a progress value it also draws the countdown
 * ring the boards use for Play now and Skip intro. As a standalone button it keeps remote focus and its accessible
 * name. Nothing is allocated while drawing.
 */
final class PlayerIcon extends View {
    private String symbol;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private float progress = -1;
    private int rest = Look.INK, focused = Look.ON_INK;
    private boolean circle;

    PlayerIcon(Context context, String symbol) {
        super(context); this.symbol = symbol;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** A round icon-only button (Play/Pause, −10, +10, More). restFill: Look.RAISED, or 0 for none. */
    PlayerIcon(Context context, String symbol, String label, View.OnClickListener action) {
        this(context, symbol);
        circle = true;
        setContentDescription(label); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true); setClickable(true); setOnClickListener(action); setId(View.generateViewId());
        setBackground(TvWidgets.background(context));
    }

    PlayerIcon restFill(int color) {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, Look.round(getContext(), Look.INK, 999));
        d.addState(new int[]{android.R.attr.state_pressed}, Look.round(getContext(), Look.INK, 999));
        d.addState(new int[]{}, Look.round(getContext(), color, 999));
        setBackground(d); return this;
    }

    void colors(int rest, int focused) { this.rest = rest; this.focused = focused; invalidate(); }
    void symbol(String value) { if (!value.equals(symbol)) { symbol = value; invalidate(); } }
    String symbol() { return symbol; }
    /** 0..1 of the countdown left, or -1 for no ring. */
    void progress(float value) { if (value != progress) { progress = value; invalidate(); } }

    /** Older code and tests read the label as text. */
    CharSequence getText() { return getContentDescription(); }
    void setText(CharSequence label) { setContentDescription(label); }

    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        boolean focus = isFocused() || isPressed() || (!circle && (isActivated() || hasParentFocus()));
        int color = focus ? focused : rest;
        float size = Math.min(getWidth(), getHeight());
        // Glyphs are drawn in a 24-unit box; a round button shows them at about 40% of its size.
        float box = circle ? size * 0.42f : progress >= 0 ? size * 0.5f : size;
        float u = box / 24f, cx = getWidth() / 2f, cy = getHeight() / 2f, x0 = cx - 12 * u, y0 = cy - 12 * u;
        paint.setColor(color); paint.setStrokeWidth(Math.max(1.5f, 1.7f * u)); paint.setStyle(Paint.Style.STROKE);
        if (progress >= 0) {
            float r = size / 2f - Math.max(2, size * 0.07f);
            paint.setStrokeWidth(Math.max(2, size * 0.068f));
            paint.setAlpha(Math.round(Color.alpha(color) * 0.18f));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setAlpha(Color.alpha(color));
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, -90, 360 * Math.max(0, Math.min(1, progress)), false, paint);
            paint.setStrokeWidth(Math.max(1.5f, 1.7f * u));
        }
        switch (symbol) {
            case "pause":
                paint.setStyle(Paint.Style.FILL);
                oval.set(x0 + 6 * u, y0 + 4.5f * u, x0 + 10 * u, y0 + 19.5f * u); canvas.drawRoundRect(oval, 1.2f * u, 1.2f * u, paint);
                oval.set(x0 + 14 * u, y0 + 4.5f * u, x0 + 18 * u, y0 + 19.5f * u); canvas.drawRoundRect(oval, 1.2f * u, 1.2f * u, paint);
                break;
            case "play":
                paint.setStyle(Paint.Style.FILL); path.reset();
                path.moveTo(x0 + 7 * u, y0 + 4.5f * u); path.lineTo(x0 + 19.5f * u, y0 + 12 * u); path.lineTo(x0 + 7 * u, y0 + 19.5f * u); path.close();
                canvas.drawPath(path, paint); break;
            case "next":
                paint.setStyle(Paint.Style.FILL); path.reset();
                path.moveTo(x0 + 5.5f * u, y0 + 5 * u); path.lineTo(x0 + 15.5f * u, y0 + 12 * u); path.lineTo(x0 + 5.5f * u, y0 + 19 * u); path.close();
                canvas.drawPath(path, paint);
                oval.set(x0 + 16.5f * u, y0 + 5 * u, x0 + 18.9f * u, y0 + 19 * u); canvas.drawRoundRect(oval, u, u, paint); break;
            case "skip":
                paint.setStyle(Paint.Style.FILL); path.reset();
                path.moveTo(x0 + 3.5f * u, y0 + 6 * u); path.lineTo(x0 + 11.5f * u, y0 + 12 * u); path.lineTo(x0 + 3.5f * u, y0 + 18 * u); path.close();
                path.moveTo(x0 + 12.5f * u, y0 + 6 * u); path.lineTo(x0 + 20.5f * u, y0 + 12 * u); path.lineTo(x0 + 12.5f * u, y0 + 18 * u); path.close();
                canvas.drawPath(path, paint); break;
            case "back10": case "forward10": {
                boolean back = symbol.equals("back10");
                oval.set(x0 + 4.5f * u, y0 + 4.5f * u, x0 + 19.5f * u, y0 + 19.5f * u);
                canvas.drawArc(oval, back ? 180 : 0, back ? -315 : 315, false, paint);
                path.reset();
                if (back) { path.moveTo(x0 + 4.5f * u, y0 + 4.5f * u); path.lineTo(x0 + 4.5f * u, y0 + 7.7f * u); path.lineTo(x0 + 7.7f * u, y0 + 7.7f * u); }
                else { path.moveTo(x0 + 19.5f * u, y0 + 4.5f * u); path.lineTo(x0 + 19.5f * u, y0 + 7.7f * u); path.lineTo(x0 + 16.3f * u, y0 + 7.7f * u); }
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL); paint.setTextSize(7.4f * u); paint.setTypeface(Ui.semibold); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("10", cx, y0 + 15.3f * u, paint); break;
            }
            case "subtitles":
                oval.set(x0 + 3 * u, y0 + 5 * u, x0 + 21 * u, y0 + 19 * u); canvas.drawRoundRect(oval, 3 * u, 3 * u, paint);
                canvas.drawLine(x0 + 7 * u, y0 + 12 * u, x0 + 11 * u, y0 + 12 * u, paint); canvas.drawLine(x0 + 13 * u, y0 + 12 * u, x0 + 17 * u, y0 + 12 * u, paint);
                canvas.drawLine(x0 + 7 * u, y0 + 15.5f * u, x0 + 14 * u, y0 + 15.5f * u, paint); canvas.drawLine(x0 + 16 * u, y0 + 15.5f * u, x0 + 17 * u, y0 + 15.5f * u, paint);
                break;
            case "more":
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) canvas.drawCircle(x0 + (5 + 7 * i) * u, cy, 1.9f * u, paint);
                break;
            case "check":
                paint.setStrokeWidth(2.2f * u); path.reset();
                path.moveTo(x0 + 5 * u, y0 + 12.5f * u); path.lineTo(x0 + 9.5f * u, y0 + 17 * u); path.lineTo(x0 + 19 * u, y0 + 7.5f * u);
                canvas.drawPath(path, paint); break;
            case "done":   // check in a soft disc (subtitles synced)
                paint.setStyle(Paint.Style.FILL); paint.setAlpha(Math.round(Color.alpha(color) * 0.12f));
                canvas.drawCircle(cx, cy, 12 * u, paint); paint.setAlpha(Color.alpha(color)); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * u); path.reset();
                path.moveTo(x0 + 7.5f * u, y0 + 12.3f * u); path.lineTo(x0 + 10.5f * u, y0 + 15.3f * u); path.lineTo(x0 + 16.5f * u, y0 + 9 * u);
                canvas.drawPath(path, paint); break;
            case "chevron":
                path.reset(); path.moveTo(x0 + 9 * u, y0 + 5.5f * u); path.lineTo(x0 + 15.5f * u, y0 + 12 * u); path.lineTo(x0 + 9 * u, y0 + 18.5f * u);
                canvas.drawPath(path, paint); break;
            case "spinner": {
                float r = 10.5f * u;
                paint.setStrokeWidth(Math.max(2, 1.6f * u));
                paint.setAlpha(Math.round(Color.alpha(color) * 0.16f)); canvas.drawCircle(cx, cy, r, paint); paint.setAlpha(Color.alpha(color));
                oval.set(cx - r, cy - r, cx + r, cy + r);
                float turn = (android.os.SystemClock.uptimeMillis() % 1000L) * 0.36f;
                canvas.drawArc(oval, turn - 90, 90, false, paint);
                if (isShown()) postInvalidateOnAnimation();
                break;
            }
            default: break;
        }
    }

    private boolean hasParentFocus() {
        return getParent() instanceof View && (((View) getParent()).isFocused() || ((View) getParent()).isPressed());
    }

    private static final class Color {
        static int alpha(int c) { return c >>> 24; }
    }
}
