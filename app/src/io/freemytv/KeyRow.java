package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Shared six-column keyboard for Home and Search. Arrow keys move between adjacent keys. */
final class KeyRow extends View {
    interface Listener {
        void type(String text);
        void delete();
        void clear();
        void voice();
        default boolean exit(int keyCode) { return false; }
    }
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz", DIGITS = "1234567890-'&:.,!?()/+@#%_";
    private static final String[] LABELS = new String[128];
    static { for (int i = 0; i < LABELS.length; i++) LABELS[i] = String.valueOf((char) i); }
    private static final int SPACE = -1, DELETE = -2, NUMBERS = -3, CLEAR = -4, VOICE = -5;
    private final Listener listener;
    private final boolean hasVoice;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final Path delete = SvgPath.parse("M9 5h11v14H9l-6-7z M12.5 9.5l5 5M17.5 9.5l-5 5");
    private String chars = LETTERS;
    private int index, column;
    private boolean active, dark = true;

    KeyRow(Context c, boolean voice, Listener listener) {
        super(c);
        this.listener = listener;
        this.hasVoice = voice;
        setFocusable(true);
        setClickable(true);
        text.setTypeface(Ui.regular);
        text.setTextAlign(Paint.Align.CENTER);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        setOnClickListener(v -> press(false));
        updateDescription();
    }
    void setActive(boolean value) { if (active != value) { active = value; invalidate(); } }
    void setDark(boolean value) { if (dark != value) { dark = value; invalidate(); } }
    void reset() {
        chars = LETTERS;
        index = column = 0;
        updateDescription(); invalidate();
    }
    private int count() { return hasVoice ? 31 : 30; }
    private int row(int i) { return i < 26 ? i / 6 : i < 28 ? 4 : 5; }
    private int col(int i) { return i < 26 ? i % 6 : i == 26 ? 2 : i == 27 ? 4 : (i - 28) * 2; }
    private int span(int i) { return i < 26 ? 1 : i == 29 && !hasVoice ? 4 : 2; }
    private int code(int i) {
        return i < 26 ? chars.charAt(i) : i == 26 ? SPACE : i == 27 ? DELETE : i == 28 ? NUMBERS : i == 29 ? CLEAR : VOICE;
    }
    private int at(int row, int column) {
        for (int i = 0; i < count(); i++) if (row(i) == row && column >= col(i) && column < col(i) + span(i)) return i;
        return index;
    }
    private String label(int code) {
        if (code >= 0) return LABELS[code];
        switch (code) {
            case SPACE: return Ui.tr("Espaço", "Space");
            case DELETE: return Ui.tr("Apagar", "Delete");
            case NUMBERS: return chars == LETTERS ? "123" : "ABC";
            case CLEAR: return Ui.tr("Limpar", "Clear");
            default: return Ui.tr("Voz", "Voice");
        }
    }
    String description() { return Ui.tr("Teclado. ", "Keyboard. ") + label(code(index)); }
    private void updateDescription() { setContentDescription(description()); }
    private void cell(int i) {
        float gap = Look.s(getContext(), 8);
        float width = (getWidth() - 5 * gap) / 6, height = (getHeight() - 5 * gap) / 6;
        float x = col(i) * (width + gap), y = row(i) * (height + gap);
        cell.set(x, y, x + width * span(i) + gap * (span(i) - 1), y + height);
    }
    /** An outer Up, Down, or Right press returns to the screen's focus navigation. */
    boolean handle(KeyEvent e) {
        int key = e.getKeyCode();
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!arrow && !select) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (select) { press(e.getRepeatCount() > 0); return true; }
        int next;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (col(index) == 0) return true;
            column = col(index) - 1; next = at(row(index), column);
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (col(index) + span(index) == 6) return listener.exit(key);
            column = col(index) + span(index); next = at(row(index), column);
        } else {
            int nextRow = row(index) + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (nextRow < 0 || nextRow > 5) return listener.exit(key);
            next = at(nextRow, column);
        }
        index = next;
        updateDescription();
        if (isFocused()) announceForAccessibility(getContentDescription());
        invalidate(); return true;
    }
    private void press(boolean repeat) {
        int code = code(index);
        if (repeat && code != DELETE) return;
        if (code == NUMBERS) {
            chars = chars == LETTERS ? DIGITS : LETTERS;
            updateDescription(); invalidate();
        } else if (code == SPACE) listener.type(" ");
        else if (code == DELETE) listener.delete();
        else if (code == CLEAR) listener.clear();
        else if (code == VOICE) listener.voice();
        else listener.type(String.valueOf((char) code));
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return handle(event) || super.onKeyDown(keyCode, event); }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return handle(event) || super.onKeyUp(keyCode, event); }
    @Override protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous); invalidate();
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
    }
    @Override protected void onDraw(Canvas c) {
        float k = Look.s(getContext(), 1);
        boolean focus = active || isFocused();
        int ink = dark ? Look.INK : Look.DAY_INK;
        for (int i = 0; i < count(); i++) {
            cell(i);
            boolean on = focus && i == index;
            fill.setColor(on ? ink : Ui.alpha(ink, i >= 26 ? 0.08f : 0.025f));
            c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            int color = on ? dark ? Look.ON_INK : Look.DAY_GROUND : Ui.alpha(ink, 0.86f);
            int code = code(i);
            if (code == DELETE) {
                icon.setColor(color); icon.setStrokeWidth(1.8f);
                float size = 30 * k;
                c.save(); c.translate(cell.centerX() - size / 2, cell.centerY() - size / 2); c.scale(size / 24f, size / 24f);
                c.drawPath(delete, icon); c.restore();
            } else {
                text.setTextSize((code >= 0 ? 32 : 25) * k); text.setColor(color);
                c.drawText(label(code), cell.centerX(), cell.centerY() + Ui.halfCap(text), text);
            }
        }
    }
}
