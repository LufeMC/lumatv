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
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/**
 * Shared native controls in LumaTV's look (see Look): one focus treatment everywhere. A focused control is a solid
 * ink pill with dark text; an unfocused one is raised glass with ink text, so a primary action that isn't focused
 * never looks focused. Artwork cards get a ring and a small lift instead.
 */
final class TvWidgets {
    private TvWidgets() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int color) {
        return Look.round(c, color, 14);
    }

    private static GradientDrawable pill(Context c, int color) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(Look.s(c, 999));
        return value;
    }

    /** Buttons: raised glass at rest, solid ink when focused. */
    static StateListDrawable background(Context c) {
        return states(c, Look.RAISED);
    }

    /** List rows and quiet controls: nothing at rest, solid ink when focused. */
    static StateListDrawable quiet(Context c) {
        return states(c, 0x00000000);
    }

    private static StateListDrawable states(Context c, int rest) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{-android.R.attr.state_enabled}, pill(c, Look.RAISED_2));
        result.addState(new int[]{android.R.attr.state_focused}, pill(c, Look.INK));
        result.addState(new int[]{android.R.attr.state_pressed}, pill(c, Look.INK));
        result.addState(new int[]{}, pill(c, rest));
        return result;
    }

    /** The screen's main action. Same treatment as every button: it only looks selected when it is. */
    static void primary(Button button) {
        button.setBackground(background(button.getContext()));
        button.setTextColor(Look.buttonText());
        button.setTypeface(Ui.semibold);
    }

    /** Season numbers and similar: solid ink when focused, a soft disc when chosen, plain text otherwise. */
    static void tab(Button button) {
        Context c = button.getContext();
        button.setBackground(new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                boolean focused = false, selected = false;
                for (int state : getState()) {
                    focused |= state == android.R.attr.state_focused;
                    selected |= state == android.R.attr.state_selected;
                }
                if (!focused && !selected) return;
                paint.setColor(focused ? Look.INK : 0x29FFFFFF);
                float r = b.height() / 2f;
                canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, r, r, paint);
            }

            @Override public boolean isStateful() { return true; }
            @Override protected boolean onStateChange(int[] states) { invalidateSelf(); return true; }
            @Override public void setAlpha(int value) {}
            @Override public void setColorFilter(ColorFilter value) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        button.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Look.ON_INK, Look.INK, Look.INK_2}));
        int pad = Look.si(c, 14);
        button.setPadding(pad, 0, pad, 0);
        button.setMinWidth(Look.si(c, 56));
        button.setMinimumWidth(Look.si(c, 56));
    }

    static ColorStateList textColors() {
        return Look.buttonText();
    }

    static TextView text(Context c, String value, int size) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Look.INK);
        view.setTypeface(Ui.regular);
        view.setPadding(0, dp(c, 3), dp(c, 8), dp(c, 3));
        return view;
    }

    static Button button(Context c, String label, View.OnClickListener action) {
        Button b = new Button(c);
        b.setText(label);
        Look.textSize(b, Look.LABEL);
        b.setTypeface(Ui.medium);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int padX = Look.si(c, 32);
        b.setPadding(padX, 0, padX, 0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Look.si(c, 72));
        b.setMinimumHeight(Look.si(c, 72));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setId(View.generateViewId());
        b.setOnClickListener(action);
        b.setBackground(background(c));
        b.setBackgroundTintList(null);
        b.setTextColor(textColors());
        return b;
    }

    /** Artwork cards: no fill; a ring outside the card when focused (the parent must not clip children). */
    static StateListDrawable cardBackground(Context c) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{}, pill(c, 0x00000000));
        return result;
    }

    /** Makes a card focusable with the ring-and-lift treatment. */
    static void card(View card, float radiusDesign) {
        card.setFocusable(true);
        card.setClickable(true);
        card.setForeground(Look.ring(card.getContext(), radiusDesign));
        Look.lift(card);
        if (card.getParent() instanceof ViewGroup) {
            ((ViewGroup) card.getParent()).setClipChildren(false);
            ((ViewGroup) card.getParent()).setClipToPadding(false);
        }
    }
}
