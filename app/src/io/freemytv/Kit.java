package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native building blocks for the system screens (settings, Stremio connect, crash fallback) in LumaTV's look:
 * one focus at a time (solid ink with dark text), everything else quiet. Two themes: night (dark ground) and day
 * (cream ground, every text at least 4.5:1). Sizes are design px (1920 wide), via Look.s.
 */
final class Kit {
    private Kit() {}

    /** Colors for one look. */
    static final class Theme {
        final boolean day;
        final int ground, nav, navLine, ink, ink2, ink3, navInk, focus, onFocus, onFocus2, current, card, cardSoft,
                ring, button, segment, chosen, trackOn, trackOff, knobOn, knobOff, fTrackOn, fTrackOff, fKnobOn, fKnobOff;

        private Theme(boolean day) {
            this.day = day;
            if (!day) {
                ground = Look.GROUND; nav = 0x08FFFFFF; navLine = 0x0FFFFFFF;
                ink = Look.INK; ink2 = 0xB3F5F3EF; ink3 = 0x8FF5F3EF; navInk = 0xA8F5F3EF;
                focus = Look.INK; onFocus = Look.ON_INK; onFocus2 = 0xA80A0B0D;
                current = 0x1AFFFFFF; card = 0x14FFFFFF; cardSoft = 0x0AFFFFFF; ring = 0x80F5F3EF;
                button = 0x1FFFFFFF; segment = 0x0FFFFFFF; chosen = 0x29FFFFFF;
                trackOn = 0x57F5F3EF; trackOff = 0x1FFFFFFF; knobOn = Look.INK; knobOff = 0x8FF5F3EF;
                fTrackOn = 0x570A0B0D; fTrackOff = 0x1F0A0B0D; fKnobOn = Look.ON_INK; fKnobOff = 0x800A0B0D;
            } else {
                ground = Look.DAY_GROUND; nav = 0xFFE8E4DA; navLine = 0x141C1B19;
                ink = Look.DAY_INK; ink2 = 0xA31C1B19; ink3 = 0xA31C1B19; navInk = 0xB81C1B19;
                focus = Look.DAY_INK; onFocus = Look.DAY_GROUND; onFocus2 = 0xC2F2EFE8;
                current = 0x141C1B19; card = 0xB8FFFFFF; cardSoft = 0x0B1C1B19; ring = 0x4D1C1B19;
                button = 0x171C1B19; segment = 0x0F1C1B19; chosen = 0x241C1B19;
                trackOn = Look.DAY_INK; trackOff = 0x2E1C1B19; knobOn = Look.DAY_GROUND; knobOff = 0xA31C1B19;
                fTrackOn = 0x80F2EFE8; fTrackOff = 0x2EF2EFE8; fKnobOn = Look.DAY_GROUND; fKnobOff = 0x99F2EFE8;
            }
        }

        static Theme night() { return new Theme(false); }
        static Theme day() { return new Theme(true); }

        /** Prefs.LOOK: always day, always night, or (default) day until sunset at the TV's location. */
        static Theme current(Context c) {
            String look = Prefs.look(c);
            if ("day".equals(look)) return day();
            if ("night".equals(look)) return night();
            return HomeData.get(c).isNight(System.currentTimeMillis()) ? night() : day();
        }

        ColorStateList text() { return states(ink, onFocus); }
        ColorStateList text2() { return states(ink2, onFocus2); }
    }

    static ColorStateList states(int rest, int focused) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{focused, focused, rest});
    }

    static int px(Context c, float design) {
        return Look.si(c, design);
    }

    static GradientDrawable round(Context c, int color, float radius) {
        return Look.round(c, color, radius);
    }

    /** Rest color → focus color, rounded. "selected" counts as focused (a key being learned, a digit being set). */
    static StateListDrawable fill(Context c, int rest, int focused, float radius) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, round(c, focused, radius));
        d.addState(new int[]{android.R.attr.state_selected}, round(c, focused, radius));
        d.addState(new int[]{}, round(c, rest, radius));
        return d;
    }

    static TextView text(Context c, String value, float size, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Look.s(c, size));
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView text(Context c, String value, float size, Typeface face, ColorStateList colors) {
        TextView t = text(c, value, size, face, colors.getDefaultColor());
        t.setTextColor(colors);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    static TextView line(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    static LinearLayout.LayoutParams top(Context c, LinearLayout.LayoutParams p, float design) {
        p.topMargin = px(c, design);
        return p;
    }

    static LinearLayout row(Context c, int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        if (orientation == LinearLayout.HORIZONTAL) l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A pill button: focused = solid ink; at rest the theme's raised fill (never looks focused). */
    static TextView button(Context c, Theme t, String label, View.OnClickListener click) {
        TextView b = text(c, label, 26, Ui.semibold, t.text());
        b.setDuplicateParentStateEnabled(false);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        int padX = px(c, 36);
        b.setPadding(padX, 0, padX, 0);
        b.setMinHeight(px(c, 72));
        b.setBackground(fill(c, t.button, t.focus, 36));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    // MARK: - Small drawn parts

    /** Line icons drawn in code (24-unit grid, like the boards' SVGs), colored from a state list. */
    static final class Glyph extends View {
        static final int CHEVRON = 0, CHECK = 1, INFO = 2, HOME = 3, TV = 4, SPINNER = 5, BULLET = 6;
        private final int kind;
        private ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private ColorStateList tick = ColorStateList.valueOf(0xFF000000);   // the check's tick (cut out of the disc)

        Glyph(Context c, int kind, ColorStateList colors) {
            super(c);
            this.kind = kind;
            this.colors = colors;
            setDuplicateParentStateEnabled(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        Glyph tick(ColorStateList tick) {
            this.tick = tick;
            invalidate();
            return this;
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float u = Math.min(getWidth(), getHeight()) / 24f;
            float ox = (getWidth() - 24 * u) / 2f, oy = (getHeight() - 24 * u) / 2f;
            canvas.save();
            canvas.translate(ox, oy);
            int color = colors.getColorForState(getDrawableState(), colors.getDefaultColor());
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * u);
            path.reset();
            switch (kind) {
                case CHEVRON:
                    path.moveTo(9 * u, 6 * u); path.lineTo(15 * u, 12 * u); path.lineTo(9 * u, 18 * u);
                    canvas.drawPath(path, paint);
                    break;
                case CHECK:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12 * u, 12 * u, 11 * u, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2.2f * u);
                    paint.setColor(tick.getColorForState(getDrawableState(), tick.getDefaultColor()));
                    path.moveTo(7 * u, 12.5f * u); path.lineTo(10.2f * u, 15.7f * u); path.lineTo(17 * u, 9 * u);
                    canvas.drawPath(path, paint);
                    break;
                case INFO:
                    canvas.drawCircle(12 * u, 12 * u, 9.5f * u, paint);
                    canvas.drawLine(12 * u, 11 * u, 12 * u, 16.5f * u, paint);
                    canvas.drawLine(12 * u, 7.6f * u, 12 * u, 7.7f * u, paint);
                    break;
                case HOME:
                    path.moveTo(4 * u, 11 * u); path.lineTo(12 * u, 4.5f * u); path.lineTo(20 * u, 11 * u);
                    path.moveTo(6.5f * u, 9.2f * u); path.lineTo(6.5f * u, 19 * u); path.lineTo(17.5f * u, 19 * u);
                    path.lineTo(17.5f * u, 9.2f * u);
                    canvas.drawPath(path, paint);
                    break;
                case TV:
                    paint.setStrokeWidth(1.6f * u);
                    oval.set(2.5f * u, 4 * u, 21.5f * u, 16.5f * u);
                    canvas.drawRoundRect(oval, 2 * u, 2 * u, paint);
                    canvas.drawLine(8.5f * u, 20 * u, 15.5f * u, 20 * u, paint);
                    canvas.drawLine(12 * u, 16.5f * u, 12 * u, 20 * u, paint);
                    break;
                case SPINNER:
                    paint.setStrokeWidth(3 * u);
                    paint.setAlpha(Math.round(Color.alpha(color) * 0.2f));
                    canvas.drawCircle(12 * u, 12 * u, 9 * u, paint);
                    paint.setColor(color);
                    oval.set(3 * u, 3 * u, 21 * u, 21 * u);
                    float a = (SystemClock.uptimeMillis() % 1000) * 0.36f;
                    canvas.drawArc(oval, a, 90, false, paint);
                    postInvalidateOnAnimation();
                    break;
                case BULLET:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12 * u, 12 * u, 4 * u, paint);
                    break;
                default:
                    break;
            }
            canvas.restore();
        }
    }

    /** The on/off switch (80×46, 36 knob) from the boards; follows its row's focus. */
    static final class Switch extends View {
        private final Theme t;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean on;

        Switch(Context c, Theme t, boolean on) {
            super(c);
            this.t = t;
            this.on = on;
            setDuplicateParentStateEnabled(true);
        }

        void set(boolean value) {
            if (on == value) return;
            on = value;
            invalidate();
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean f = isFocused() || isSelected() || (getParent() instanceof View && ((View) getParent()).isFocused());
            float h = getHeight(), w = getWidth();
            paint.setColor(f ? (on ? t.fTrackOn : t.fTrackOff) : (on ? t.trackOn : t.trackOff));
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, h / 2, h / 2, paint);
            float inset = h * 5 / 46f, knob = h * 36 / 46f;
            float x = on ? w - inset - knob : inset;
            paint.setColor(f ? (on ? t.fKnobOn : t.fKnobOff) : (on ? t.knobOn : t.knobOff));
            canvas.drawCircle(x + knob / 2, h / 2, knob / 2, paint);
        }
    }

    /** android.graphics.Color without the import clash in Glyph. */
    private static final class Color {
        static int alpha(int c) { return c >>> 24; }
    }

    /** A drawable that paints nothing (keeps a focusable view's default highlight away). */
    static Drawable none() {
        return new GradientDrawable();
    }
}
