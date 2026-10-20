package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Shared native controls use the editor's type and focus colors. */
final class TvWidgets {
    private TvWidgets() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int color) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(c, 8));
        return result;
    }

    static StateListDrawable background(Context c) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, pill(c, 0xFFF5F5F7));
        result.addState(new int[]{android.R.attr.state_pressed}, pill(c, 0xFFF5F5F7));
        result.addState(new int[]{}, pill(c, 0x00000000));
        return result;
    }


    private static GradientDrawable pill(Context c, int color) {
        GradientDrawable value = shape(c, color); value.setCornerRadius(dp(c, 28)); return value;
    }
    static void primary(Button button) {
        Context context = button.getContext(); StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = pill(context, 0xFFFFFFFF); focused.setStroke(dp(context, 3), 0xFF8DBBFF);
        states.addState(new int[]{-android.R.attr.state_enabled}, pill(context, 0xFF35353B));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, pill(context, 0xFFF5F5F7));
        button.setBackground(states); button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xFF9A9AA4, 0xFF111115}));
    }
    static void tab(Button button) {
        button.setBackground(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(3);
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds(); boolean focused = false, selected = false;
                for (int state : getState()) { focused |= state == android.R.attr.state_focused; selected |= state == android.R.attr.state_selected; }
                if (focused) { paint.setColor(0xFFF5F5F7); canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, dp(button.getContext(), 24), dp(button.getContext(), 24), paint); }
                else if (selected) { paint.setColor(0xFFF5F5F7); float inset = dp(button.getContext(), 18); canvas.drawRoundRect(bounds.left + inset, bounds.bottom - dp(button.getContext(), 3), bounds.right - inset, bounds.bottom, 2, 2, paint); }
            }
            public boolean isStateful() { return true; }
            protected boolean onStateChange(int[] states) { invalidateSelf(); return true; }
            public void setAlpha(int value) {} public void setColorFilter(android.graphics.ColorFilter value) {}
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        button.setPadding(dp(button.getContext(), 10), 0, dp(button.getContext(), 10), 0);
    }
    static ColorStateList textColors() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused},
                {android.R.attr.state_pressed}, {}}, new int[]{0xFF94949E, 0xFF1C1C1E, 0xFF1C1C1E, 0xFFFFFFFF});
    }

    static TextView text(Context c, String value, int size) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xFFF5F5F7);
        view.setTypeface(Ui.regular);
        view.setPadding(0, dp(c, 3), dp(c, 8), dp(c, 3));
        return view;
    }

    static Button button(Context c, String label, View.OnClickListener action) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Ui.medium);
        b.setAllCaps(false);
        b.setPadding(dp(c, 18), dp(c, 6), dp(c, 18), dp(c, 6));
        b.setStateListAnimator(null);
        b.setGravity(android.view.Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setId(View.generateViewId());
        b.setOnClickListener(action);
        b.setBackground(background(c));
        b.setBackgroundTintList(null);
        b.setTextColor(textColors());
        return b;
    }
    static StateListDrawable cardBackground(Context c) {
        StateListDrawable result = new StateListDrawable();
        GradientDrawable focus = shape(c, 0xFF242428); focus.setStroke(dp(c, 2), 0xFFF5F5F7);
        result.addState(new int[]{android.R.attr.state_focused}, focus);
        result.addState(new int[]{}, shape(c, 0x00151518));
        return result;
    }
}
