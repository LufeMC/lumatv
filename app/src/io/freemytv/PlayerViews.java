package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Building blocks of the player boards: pills, glass, text, key hints and the scrub bar. Sizes in design px. */
final class PlayerViews {
    private PlayerViews() {}

    static int px(Context c, float design) { return Look.si(c, design); }

    static TextView text(Context c, String value, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setText(value); Look.textSize(t, size); t.setTextColor(color); t.setTypeface(face);
        t.setIncludeFontPadding(false); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Glass sheet or card: rgba(18,18,20,.8) with a 1 px light edge. */
    static GradientDrawable glass(Context c, float radius) {
        GradientDrawable d = Look.round(c, Look.GLASS, radius);
        d.setStroke(Math.max(1, px(c, 1)), Look.GLASS_LINE);
        return d;
    }

    static StateListDrawable states(Context c, int rest, float radius) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{-android.R.attr.state_enabled}, Look.round(c, rest == 0 ? 0 : Look.RAISED_2, radius));
        d.addState(new int[]{android.R.attr.state_focused}, Look.round(c, Look.INK, radius));
        d.addState(new int[]{android.R.attr.state_pressed}, Look.round(c, Look.INK, radius));
        d.addState(new int[]{}, Look.round(c, rest, radius));
        return d;
    }

    static ColorStateList ink(int rest) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {-android.R.attr.state_enabled}, {}},
                new int[]{Look.ON_INK, Look.ON_INK, Look.INK_4, rest});
    }

    /** "OK", "Back", "←": a key cap outlined in ink. */
    static TextView key(Context c, String label) {
        TextView k = text(c, label, Look.SMALL, Look.INK, Ui.semibold);
        GradientDrawable d = new GradientDrawable(); d.setColor(0); d.setCornerRadius(Look.s(c, 8));
        d.setStroke(Math.max(1, Math.round(Look.s(c, 1.5f))), Look.INK_3);
        k.setBackground(d); k.setPadding(px(c, 12), px(c, 3), px(c, 12), px(c, 3)); k.setGravity(Gravity.CENTER);
        return k;
    }

    /** A key cap followed by what it does: [OK] Jump now. */
    static LinearLayout hint(Context c, String key, String action, int color) {
        LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(key(c, key));
        TextView label = text(c, action, Look.META, color, Ui.regular);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = px(c, 10);
        row.addView(label, lp);
        return row;
    }

    /**
     * A pill button: optional icon (with optional countdown ring), label, optional trailing text ("8 s"). Solid ink
     * when focused; at rest the given fill (Look.RAISED, 0 for none). Height and text in design px.
     */
    static final class Pill extends LinearLayout {
        final TextView label, trailing;
        final PlayerIcon icon;
        Pill(Context c, String symbol, boolean ring, String text, float height, float textSize, int rest) {
            super(c);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true); setClickable(true); setId(View.generateViewId());
            setBackground(states(c, rest, height / 2f));
            setMinimumHeight(px(c, height));
            int padStart = px(c, symbol == null ? 26 : ring ? 18 : 22), padEnd = px(c, 26);
            setPadding(padStart, 0, padEnd, 0);
            if (symbol != null) {
                icon = new PlayerIcon(c, symbol);
                icon.setDuplicateParentStateEnabled(true);
                float s = ring ? 44 : 26;
                LayoutParams lp = new LayoutParams(px(c, s), px(c, s)); lp.rightMargin = px(c, ring ? 14 : 12);
                addView(icon, lp);
                if (ring) icon.progress(1);
            } else icon = null;
            label = text(c, text, textSize, Look.INK, ring ? Ui.semibold : Ui.medium);
            label.setTextColor(ink(Look.INK)); label.setDuplicateParentStateEnabled(true);
            addView(label, new LayoutParams(-2, -2));
            trailing = text(c, "", textSize, Look.INK_2, Ui.medium);
            trailing.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_2}));
            trailing.setDuplicateParentStateEnabled(true); trailing.setFontFeatureSettings("'tnum'");
            LayoutParams tp = new LayoutParams(0, -2, 1); trailing.setGravity(Gravity.END); tp.leftMargin = px(c, 16);
            trailing.setVisibility(GONE);
            addView(trailing, tp);
            setContentDescription(text);
        }
        void setText(String value) { label.setText(value); setContentDescription(value); }
        CharSequence getText() { return label.getText(); }
        void trailing(String value) {
            trailing.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE);
            trailing.setText(value == null ? "" : value);
        }
    }

    static Pill pill(Context c, String symbol, String text, View.OnClickListener action) {
        Pill p = new Pill(c, symbol, false, text, 64, Look.META, Look.RAISED);
        p.setOnClickListener(action); return p;
    }

    /**
     * The playback bar: track, buffered, played, and the knob. While seeking it also shows the jump (from the
     * original position to the target) and a halo on the knob. Focusable: Left and Right on it start seeking.
     */
    static final class ScrubBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long duration, position, buffered, original = -1;
        ScrubBar(Context c) {
            super(c); setFocusable(true); setId(View.generateViewId());
            setContentDescription(Ui.tr("Posição de reprodução", "Playback position"));
        }
        void set(long position, long buffered, long duration) {
            if (position == this.position && buffered == this.buffered && duration == this.duration && original < 0) return;
            this.position = position; this.buffered = buffered; this.duration = duration; this.original = -1; invalidate();
        }
        /** Seeking from original to target. */
        void seeking(long original, long target, long duration) {
            this.original = original; this.position = target; this.duration = duration; invalidate();
        }
        @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) { super.onFocusChanged(gained, direction, previous); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float h = Look.s(getContext(), 6), knob = Look.s(getContext(), original >= 0 ? 26 : isFocused() ? 26 : 22);
            float inset = Look.s(getContext(), 13), left = inset, right = getWidth() - inset, cy = getHeight() / 2f, w = right - left;
            float r = h / 2f;
            paint.setColor(Look.PROGRESS_TRACK); rect.set(left, cy - r, right, cy + r); canvas.drawRoundRect(rect, r, r, paint);
            if (duration <= 0) return;
            float at = left + w * Math.max(0, Math.min(1, position / (float) duration));
            if (original >= 0) {
                float from = left + w * Math.max(0, Math.min(1, original / (float) duration));
                paint.setColor(Look.INK_4); rect.set(left, cy - r, Math.min(from, at), cy + r); canvas.drawRoundRect(rect, r, r, paint);
                paint.setColor(Look.INK); rect.set(Math.min(from, at), cy - r, Math.max(from, at), cy + r); canvas.drawRect(rect, paint);
                paint.setColor(Look.PROGRESS_TRACK); canvas.drawCircle(at, cy, knob / 2f + Look.s(getContext(), 6), paint);
            } else {
                float buffer = left + w * Math.max(0, Math.min(1, buffered / (float) duration));
                paint.setColor(Look.INK_4); rect.set(left, cy - r, buffer, cy + r); canvas.drawRoundRect(rect, r, r, paint);
                paint.setColor(Look.INK); rect.set(left, cy - r, at, cy + r); canvas.drawRoundRect(rect, r, r, paint);
                if (isFocused()) { paint.setColor(Look.PROGRESS_TRACK); canvas.drawCircle(at, cy, knob / 2f + Look.s(getContext(), 6), paint); }
            }
            paint.setColor(Look.INK); canvas.drawCircle(at, cy, knob / 2f, paint);
        }
    }

    /** A vertical gradient (top to bottom), for the shades behind the controls. */
    static View shade(Context c, int top, int bottom) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom}));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }
}
