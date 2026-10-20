package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * LumaTV's opening, about 4 s: Flora (the owner's dog) drawn in one line, her eyes close into a smile, a small tongue
 * pops out, then she shrinks into the LumaTV wordmark and the home screen fades in (the OpeningLine board). It plays on
 * the first home screen after the TV starts or after an update; any key skips it.
 */
final class Opening extends View {
    interface Done { void run(); }

    // Flora's line mark, in the viewBox "40 85 385 300" (tools/make_brand.py draws the icon from the same paths).
    static final String OUTLINE = "M137.1 370.4 Q114.9 376.7 111.8 365.9 Q86.0 369.5 82.6 358.1 Q63.7 353.7 68.3 337.9 "
            + "Q52.6 330.2 65.7 313.1 Q52.8 303.2 62.2 289.4 Q50.9 278.0 66.1 265.7 Q54.2 252.9 71.5 243.1 Q63.4 229.3 77.2 221.0 "
            + "Q71.3 206.5 90.0 201.9 Q84.0 185.8 103.1 184.2 Q101.4 169.1 119.0 169.2 Q120.6 155.8 137.4 158.1 Q138.4 141.5 151.0 140.9 "
            + "Q152.9 121.1 167.8 125.9 Q173.9 110.3 188.6 118.2 Q196.8 102.3 209.8 109.2 Q220.5 97.0 232.6 105.6 Q244.9 94.2 255.4 109.2 "
            + "Q268.3 103.0 276.6 118.2 Q291.9 108.6 297.4 125.9 Q312.0 121.7 314.2 140.9 Q327.8 140.0 327.8 158.1 Q344.2 156.2 346.2 169.2 "
            + "Q365.2 168.1 362.1 184.2 Q379.9 186.6 375.2 201.9 Q393.0 206.9 388.0 221.0 Q401.4 229.4 393.7 243.1 Q411.3 252.8 399.1 265.7 "
            + "Q415.2 278.0 403.0 289.4 Q413.8 303.5 399.5 313.1 Q409.5 329.2 396.9 337.9 Q404.1 355.0 382.6 358.1 Q382.8 371.9 353.4 365.9 "
            + "Q350.9 377.2 328.2 370.4";
    static final String EAR_L = "M142.9 176.3 Q148.8 184.8 139.0 193.3 Q144.9 201.7 135.1 210.2 Q141.0 218.7 131.2 227.2 "
            + "Q137.1 235.7 127.3 244.1 Q133.2 252.6 123.4 261.1 Q132.4 269.9 125.4 278.8 Q134.3 287.6 127.3 296.4 Q136.3 305.2 129.3 314.1 "
            + "Q138.2 322.9 131.2 331.7 Q140.2 340.5 133.2 349.4 Q142.1 358.2 135.1 367.0";
    static final String EAR_R = "M322.3 168.4 Q316.4 176.9 326.2 185.4 Q320.3 193.9 330.1 202.4 Q324.2 210.8 334.0 219.3 "
            + "Q328.1 227.8 337.9 236.3 Q332.0 244.8 341.8 253.2 Q332.2 262.7 339.8 272.2 Q330.3 281.7 337.9 291.2 Q328.3 300.6 335.9 310.1 "
            + "Q326.4 319.6 334.0 329.1 Q324.4 338.6 332.0 348.0 Q322.5 357.5 330.1 367.0";
    static final String[] CURLS = {"M166.3 159.2 a12.7 12.7 0 1 1 17.7 11.4", "M205.3 176.8 a12.7 12.7 0 1 1 17.7 11.4",
            "M244.3 159.2 a12.7 12.7 0 1 1 17.7 11.4", "M278.4 176.8 a12.7 12.7 0 1 1 17.7 11.4"};
    static final String[] SMILES = {"M178 243 Q190 230 202 243", "M264 243 Q276 230 288 243"};
    static final String NOSE = "M219 276 Q233 269 247 276 Q250 285 240 292 Q233 297 226 292 Q216 285 219 276 Z";
    static final String MOUTH = "M211 305 Q222 315 233 307 Q244 315 255 305";
    static final String TONGUE = "M226 309 Q233 330 240 309 Z";
    static final float VB_X = 40, VB_Y = 85, VB_W = 385;

    private static final long LENGTH = 4440;
    private static final int GROUND = 0xFF050506;

    private final Line outline, earL, earR, mouth;
    private final Line[] curls = new Line[4], smiles = new Line[2];
    private final Path nose = SvgPath.parse(NOSE), tongue = SvgPath.parse(TONGUE);
    private final RectF noseBox = new RectF();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint luma = new Paint(Paint.ANTI_ALIAS_FLAG), tv = new Paint(Paint.ANTI_ALIAS_FLAG), tag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Done done;
    private long start = -1, skipAt = -1;
    private boolean finished;

    /** One line of the drawing, revealed from its start (like SVG's stroke-dashoffset). */
    private static final class Line {
        final PathMeasure measure;
        final Path part = new Path();
        final float length, width;

        Line(String d, float width) {
            measure = new PathMeasure(SvgPath.parse(d), false);
            length = measure.getLength();
            this.width = width;
        }

        void draw(Canvas c, Paint p, float t) {
            if (t <= 0) return;
            part.reset();
            measure.getSegment(0, length * Math.min(1, t), part, true);
            p.setStrokeWidth(width);
            c.drawPath(part, p);
        }
    }

    Opening(Context c, Done done) {
        super(c);
        this.done = done;
        outline = new Line(OUTLINE, 2.7f);
        earL = new Line(EAR_L, 2.7f);
        earR = new Line(EAR_R, 2.7f);
        mouth = new Line(MOUTH, 2.4f);
        for (int i = 0; i < 4; i++) curls[i] = new Line(CURLS[i], 2f);
        for (int i = 0; i < 2; i++) smiles[i] = new Line(SMILES[i], 3f);
        nose.computeBounds(noseBox, true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Look.INK);
        fill.setColor(Look.INK);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Plays on the first home screen after the TV starts or after an update (never twice for the same pair). */
    static boolean due(Context c) {
        if (Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return false;
        String key = Config.VERSION_CODE + ":" + Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        return !key.equals(Prefs.get(c, Prefs.HOME_UI).getString(Prefs.OPENING_SEEN, ""));
    }

    static void markSeen(Context c) {
        String key = Config.VERSION_CODE + ":" + Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        Prefs.get(c, Prefs.HOME_UI).edit().putString(Prefs.OPENING_SEEN, key).apply();
    }

    /** Any key: a quick fade to the home screen. */
    void skip() {
        if (skipAt < 0 && !finished) {
            skipAt = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    boolean playing() { return !finished; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float k = w / 1920f;
        luma.setTypeface(Ui.displaySemibold);
        tv.setTypeface(Ui.light);
        for (Paint p : new Paint[] {luma, tv}) {
            p.setTextSize(120 * k);
            p.setLetterSpacing(-0.04f);
        }
        luma.setColor(Look.INK);
        tv.setColor(Ui.alpha(Look.INK, 0.7f));
        tag.setTypeface(Ui.medium);
        tag.setTextSize(22 * k);
        tag.setLetterSpacing(0.28f);
        tag.setColor(Ui.alpha(Look.INK, 0.5f));
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        float t = now - start;
        float out = 1;      // the opening's own opacity (a skip fades it all in 300 ms)
        if (skipAt >= 0) out = 1 - Ui.clamp((now - skipAt) / 300f);
        float ground = (1 - ramp(t, 3840, 4440)) * out, lock = (1 - ramp(t, 3720, 4080)) * out;
        if (t >= LENGTH || out <= 0) {
            finish();
            return;
        }
        c.drawColor(Ui.alpha(GROUND, ground));
        if (lock > 0.004f) drawLock(c, t, lock);
        postInvalidateOnAnimation();
    }

    private void drawLock(Canvas c, float t, float alpha) {
        float k = getWidth() / 1920f;
        int save = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
        // The stage (616 × 480 at 652, 230) shrinks to 0.42 and moves 238 px left, about its center.
        float move = bezier(0.65f, 0, 0.25f, 1, ramp(t, 2340, 2940));
        float scale = 1 - 0.58f * move, cx = 960 * k, cy = 470 * k;
        c.save();
        c.translate(cx - 238 * k * move, cy);
        c.scale(scale, scale);
        c.translate(-cx, -cy);
        c.translate(652 * k, 230 * k);
        float unit = 616 * k / VB_W;
        c.scale(unit, unit);
        c.translate(-VB_X, -VB_Y);
        float draw = bezier(0.5f, 0, 0.3f, 1, ramp(t, 180, 1320));
        outline.draw(c, stroke, draw);
        float ears = bezier(0.5f, 0, 0.3f, 1, ramp(t, 480, 1320));
        earL.draw(c, stroke, ears);
        earR.draw(c, stroke, ears);
        for (Line curl : curls) curl.draw(c, stroke, easeOut(ramp(t, 1020, 1380)));
        mouth.draw(c, stroke, easeOut(ramp(t, 1440, 1680)));
        // Dot eyes appear, then close into happy arcs.
        float open = easeInOut(ramp(t, 1320, 1500)), close = 1 - easeInOut(ramp(t, 1800, 1950));
        if (open > 0 && close > 0) {
            fill.setColor(Look.INK);
            for (float x : new float[] {190, 276}) {
                c.save();
                c.scale(open, open * close, x, 241);
                c.drawCircle(x, 241, 8, fill);
                c.restore();
            }
        }
        for (Line smile : smiles) smile.draw(c, stroke, easeOut(ramp(t, 1890, 2100)));
        float noseScale = easeOut(ramp(t, 1380, 1560));
        if (noseScale > 0) {
            c.save();
            c.scale(noseScale, noseScale, noseBox.centerX(), noseBox.centerY());
            c.drawPath(nose, fill);
            c.restore();
        }
        float tongueScale = t < 2040 ? 0 : t < 2220 ? 1.15f * easeOut(ramp(t, 2040, 2220)) : 1.15f - 0.15f * easeOut(ramp(t, 2220, 2310));
        if (tongueScale > 0) {
            c.save();
            c.scale(1, tongueScale, 233, 309);
            fill.setColor(Look.TONGUE);
            c.drawPath(tongue, fill);
            fill.setColor(Look.INK);
            c.restore();
        }
        c.restore();
        // The wordmark slides out from behind the mark, clipped to its box.
        float word = bezier(0.2f, 0.8f, 0.2f, 1, ramp(t, 2700, 3300));
        if (word > 0) {
            c.save();
            c.clipRect(899 * k, 404 * k, 1459 * k, 544 * k);
            float x = 899 * k - 605 * k * (1 - word);
            float base = 404 * k + 70 * k + Ui.halfCap(luma);
            c.drawText("Luma", x, base, luma);
            c.drawText("TV", x + luma.measureText("Luma"), base, tv);
            c.restore();
        }
        float tagAlpha = easeOut(ramp(t, 3060, 3420));
        if (tagAlpha > 0) {
            tag.setAlpha(Math.round(128 * tagAlpha));
            c.drawText("BY MADFER OPERATIONS", 905 * k, 556 * k + Ui.cap(tag), tag);
        }
        c.restoreToCount(save);
    }

    private void finish() {
        if (finished) return;
        finished = true;
        Opening.markSeen(getContext());
        post(() -> done.run());
    }

    private static float ramp(float t, float from, float to) {
        return Ui.clamp((t - from) / (to - from));
    }

    private static float easeOut(float t) {
        return bezier(0, 0, 0.58f, 1, t);
    }

    private static float easeInOut(float t) {
        return bezier(0.42f, 0, 0.58f, 1, t);
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) at progress t. */
    static float bezier(float x1, float y1, float x2, float y2, float t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        float lo = 0, hi = 1, u = t;
        for (int i = 0; i < 20; i++) {
            u = (lo + hi) / 2;
            float x = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u;
            if (x < t) lo = u; else hi = u;
        }
        return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u;
    }
}
