package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * LumaTV's design tokens: one set of colors, sizes and focus treatments for the Canvas screens (home, settings,
 * screensaver) and the View screens (search, title pages, player). Sizes are in 1920×1080 design pixels; s() scales
 * them to the screen, so the TV shows the design 1:1 whatever its density.
 */
final class Look {
    private Look() {}

    // Ink and grounds (night look)
    static final int INK = 0xFFF5F3EF;
    static final int INK_2 = 0xB8F5F3EF;      // secondary, .72
    static final int INK_3 = 0x8FF5F3EF;      // tertiary, .56
    static final int INK_4 = 0x66F5F3EF;      // disabled, .40
    static final int GROUND = 0xFF0A0B0D;
    static final int GROUND_NIGHT = 0xFF070A12;
    static final int PLAYER = 0xFF000000;
    static final int ON_INK = 0xFF0A0B0D;      // text on a focused (solid ink) control
    static final int RAISED = 0x24FFFFFF;      // unfocused button, .14
    static final int RAISED_2 = 0x14FFFFFF;    // quiet button, .08
    static final int GLASS = 0xD1121214;       // sheets and menus, .82
    static final int GLASS_LINE = 0x1AFFFFFF;
    static final int SCRIM = 0xB3000000;
    static final int PROGRESS_TRACK = 0x40FFFFFF;
    static final int FALLBACK_TOP = 0xFF2A2B31, FALLBACK_BOTTOM = 0xFF16171A;   // no-art tiles
    static final int TONGUE = 0xFFF29C9C;

    // Day look
    static final int DAY_GROUND = 0xFFF2EFE8;
    static final int DAY_NAV = 0xFFE9E5DC;
    static final int DAY_INK = 0xFF1C1B19;
    static final int DAY_INK_2 = 0xA31C1B19;  // .64
    static final int AMBER_FILL = 0xFFF6E3B4, AMBER_INK = 0xFF5A3D00;

    // Type scale (design px)
    static final float DISPLAY = 84, TITLE = 64, HEADING = 36, BODY = 28, LABEL = 26, META = 24, SMALL = 22;
    // Radii and spacing (design px)
    static final float R_CARD = 16, R_SHEET = 34, R_ROW = 18, SAFE_X = 96, SAFE_Y = 60;
    static final float RING_GAP = 4, RING = 4, FOCUS_SCALE = 1.05f;

    /** Design px → screen px. */
    static float s(Context c, float designPx) {
        return designPx * c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static int si(Context c, float designPx) {
        return Math.round(s(c, designPx));
    }

    static void textSize(TextView view, float designPx) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(view.getContext(), designPx));
    }

    static GradientDrawable round(Context c, int color, float radiusDesign) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(s(c, radiusDesign));
        return d;
    }

    /** Pill buttons: focused = solid ink with dark text; otherwise raised glass with ink text. */
    static ColorStateList buttonText() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}},
                new int[]{INK_4, ON_INK, INK});
    }

    /** Artwork focus: a ring outside the card, drawn by the card's parent through this foreground. */
    static Drawable ring(Context c, float radiusDesign) {
        return new Ring(c, radiusDesign);
    }

    /** Scales a card up when focused (and back), like the canvas boards. */
    static void lift(View v) {
        v.setOnFocusChangeListener((view, focused) ->
                view.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f).setDuration(160).start());
    }

    private static final class Ring extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius, inset, width;
        private boolean focused;

        Ring(Context c, float radiusDesign) {
            radius = s(c, radiusDesign);
            inset = s(c, RING_GAP);
            width = s(c, RING);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(INK);
        }

        @Override public void draw(Canvas canvas) {
            if (!focused) return;
            Rect b = getBounds();
            float o = inset + width / 2f;
            canvas.drawRoundRect(b.left - o, b.top - o, b.right + o, b.bottom + o, radius + o, radius + o, paint);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean f = false;
            for (int s : state) f |= s == android.R.attr.state_focused;
            if (f == focused) return false;
            focused = f;
            invalidateSelf();
            return true;
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
