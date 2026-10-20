package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

/**
 * The one-line keyboard of the home screen and of Search: a to z, then numbers, space and delete (and the
 * microphone when the TV has voice input). Left and right move along it, OK types; up and down are left to the screen.
 * The home screen drives it directly (it draws focus with `active`); in Search it is an ordinary focusable view.
 */
final class KeyRow extends View {
    interface Listener {
        void type(String text);
        void delete();
        void voice();
    }

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz", DIGITS = "1234567890-'&:";
    private static final int NUMBERS = -1, SPACE = -2, DELETE = -3, VOICE = -4;
    private final Listener listener;
    private final boolean hasVoice;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final Path space = SvgPath.parse("M4 10v4h16v-4"), delete = SvgPath.parse("M9 5h11v14H9l-6-7z M12.5 9.5l5 5M17.5 9.5l-5 5");
    private final Path mic = SvgPath.parse("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1 -6 0z M5 11a7 7 0 0 0 14 0M12 18v3");
    private String chars = LETTERS;
    private int index;
    private boolean active, dark = true;

    KeyRow(Context c, boolean voice, Listener listener) {
        super(c);
        this.listener = listener;
        this.hasVoice = voice;
        setFocusable(true);
        setContentDescription(Ui.tr("Teclado", "Keyboard"));
        text.setTypeface(Ui.regular);
        text.setTextAlign(Paint.Align.CENTER);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Home screen: whether the row has the (drawn) focus. */
    void setActive(boolean value) {
        if (active != value) {
            active = value;
            invalidate();
        }
    }

    void setDark(boolean value) {
        if (dark != value) {
            dark = value;
            invalidate();
        }
    }

    void reset() {
        chars = LETTERS;
        index = 0;
        invalidate();
    }

    private int count() {
        return chars.length() + 3 + (hasVoice ? 1 : 0);
    }

    private int code(int i) {
        if (i < chars.length()) return chars.charAt(i);
        int extra = i - chars.length();
        return extra == 0 ? NUMBERS : extra == 1 ? SPACE : extra == 2 ? DELETE : VOICE;
    }

    /** Fixed widths for the special keys (design px); letters share the rest. */
    private float width(int code) {
        switch (code) {
            case NUMBERS: return 96;
            case SPACE: return 96;
            case DELETE: case VOICE: return 72;
            default: return 0;
        }
    }

    private void cell(int i) {
        float k = Look.s(getContext(), 1), gap = 2 * k;
        float fixed = 0;
        for (int j = chars.length(); j < count(); j++) fixed += width(code(j)) * k;
        float letter = (getWidth() - fixed - gap * (count() - 1)) / chars.length();
        float x = 0;
        for (int j = 0; j < i; j++) x += (j < chars.length() ? letter : width(code(j)) * k) + gap;
        float w = i < chars.length() ? letter : width(code(i)) * k;
        cell.set(x, 0, x + w, getHeight());
    }

    boolean handle(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (index == 0) return true;
                index--;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (index >= count() - 1) return true;
                index++;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press(e.getRepeatCount() > 0);
                return true;
            default:
                return false;
        }
    }

    private void press(boolean repeat) {
        int code = code(index);
        if (repeat && code != DELETE) return;
        if (code == NUMBERS) {
            chars = chars == LETTERS ? DIGITS : LETTERS;
            index = chars.length();
            invalidate();
        } else if (code == SPACE) {
            listener.type(" ");
        } else if (code == DELETE) {
            listener.delete();
        } else if (code == VOICE) {
            listener.voice();
        } else {
            listener.type(String.valueOf((char) code));
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handle(event) || super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handle(event) || super.onKeyUp(keyCode, event);
    }

    @Override protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float k = Look.s(getContext(), 1);
        boolean focus = active || isFocused();
        int ink = dark ? Look.INK : Look.DAY_INK;
        text.setTextSize(26 * k);
        icon.setStrokeWidth(1.8f);
        for (int i = 0; i < count(); i++) {
            cell(i);
            boolean on = focus && i == index;
            if (on) {
                fill.setColor(dark ? Look.INK : Look.DAY_INK);
                float r = 14 * k;
                c.drawRoundRect(cell, r, r, fill);
            }
            int color = on ? (dark ? Look.ON_INK : Look.DAY_GROUND) : Ui.alpha(ink, 0.72f);
            int code = code(i);
            if (code >= 0) {
                text.setColor(color);
                c.drawText(String.valueOf((char) code), cell.centerX(), cell.centerY() + Ui.halfCap(text) - 2 * k, text);
            } else if (code == NUMBERS) {
                text.setColor(color);
                c.drawText(chars == LETTERS ? "123" : "abc", cell.centerX(), cell.centerY() + Ui.halfCap(text), text);
            } else {
                Path p = code == SPACE ? space : code == DELETE ? delete : mic;
                icon.setColor(color);
                float size = 30 * k;
                c.save();
                c.translate(cell.centerX() - size / 2, cell.centerY() - size / 2);
                c.scale(size / 24f, size / 24f);
                c.drawPath(p, icon);
                c.restore();
            }
        }
    }
}
