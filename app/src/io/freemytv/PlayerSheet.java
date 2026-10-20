package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * The right-hand glass sheet of the player boards (Audio & subtitles, Choose a source, More, Quick settings): a
 * title, optional line under it, sections of two-line rows. A row is solid ink only while focused; the chosen row
 * has a check. Back closes the sheet.
 */
final class PlayerSheet {
    final Activity activity;
    final Dialog dialog;
    final LinearLayout body;
    private final FrameLayout panel;
    private View first, chosen;
    private Runnable dismissed;

    PlayerSheet(Activity activity, String title, String line, float width) {
        this.activity = activity;
        dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context c = activity;
        FrameLayout root = new FrameLayout(c);
        int margin = px(24);
        root.setPadding(margin, margin, margin, margin);
        root.setClipToPadding(false);
        panel = new FrameLayout(c); panel.setBackground(PlayerViews.glass(c, 36));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        body = new LinearLayout(c); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(40), px(56), px(40), px(40));
        panel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        TextView heading = PlayerViews.text(c, title, Look.HEADING, Look.INK, Ui.semibold);
        heading.setAccessibilityHeading(true); heading.setPadding(px(16), 0, px(16), 0);
        body.addView(heading);
        if (line != null && !line.isEmpty()) {
            TextView sub = PlayerViews.text(c, line, Look.META, Look.INK_3, Ui.regular);
            sub.setSingleLine(false); sub.setMaxLines(2); sub.setPadding(px(16), px(12), px(16), 0);
            body.addView(sub);
        }
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.END | Gravity.FILL_VERTICAL);
        window.setLayout(px(width + 48), ViewGroup.LayoutParams.MATCH_PARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.5f);
        dialog.setOnDismissListener(d -> { if (dismissed != null) dismissed.run(); });
    }

    int px(float design) { return Look.si(activity, design); }

    void dim(float amount) { dialog.getWindow().setDimAmount(amount); }

    /** A scrolling column for rows (fills the space left in the sheet). */
    LinearLayout list(ViewGroup parent, float topMargin) {
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1); lp.topMargin = px(topMargin);
        parent.addView(scroll, lp);
        return column;
    }

    TextView section(ViewGroup parent, String label) {
        TextView t = PlayerViews.text(activity, label, Look.SMALL, Look.INK_3, Ui.medium);
        t.setPadding(px(20), 0, px(20), px(12));
        parent.addView(t);
        return t;
    }

    /** A two-line row; trailing: "check" (when checked), "chevron", or a status text. */
    Row row(ViewGroup parent, String label, String note, boolean checked, boolean enabled, Runnable action) {
        Row row = new Row(activity, label, note, checked ? "check" : null, null);
        row.setEnabled(enabled); row.setFocusable(enabled); row.setActivated(checked);
        row.setOnClickListener(v -> { if (action != null) action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = px(4);
        parent.addView(row, lp);
        if (first == null && enabled) first = row;
        if (checked && chosen == null) chosen = row;
        return row;
    }

    static final class Row extends LinearLayout {
        final TextView label, note, status;
        final PlayerIcon trailing;
        Row(Context c, String text, String detail, String symbol, String statusText) {
            super(c);
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true); setClickable(true); setId(View.generateViewId());
            setBackground(PlayerViews.states(c, 0, 16));
            setMinimumHeight(Look.si(c, 76));
            setPadding(Look.si(c, 20), Look.si(c, 8), Look.si(c, 20), Look.si(c, 8));
            LinearLayout texts = new LinearLayout(c); texts.setOrientation(VERTICAL); texts.setDuplicateParentStateEnabled(true);
            label = PlayerViews.text(c, text, Look.LABEL, Look.INK, Ui.regular);
            label.setTextColor(PlayerViews.ink(Look.INK)); label.setDuplicateParentStateEnabled(true);
            texts.addView(label);
            note = PlayerViews.text(c, detail == null ? "" : detail, Look.SMALL, Look.INK_3, Ui.regular);
            note.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {-android.R.attr.state_enabled}, {}},
                    new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_4, Look.INK_3}));
            note.setDuplicateParentStateEnabled(true);
            note.setPadding(0, Look.si(c, 2), 0, 0);
            if (detail == null || detail.isEmpty()) note.setVisibility(GONE);
            texts.addView(note);
            addView(texts, new LayoutParams(0, -2, 1));
            status = PlayerViews.text(c, statusText == null ? "" : statusText, Look.SMALL, Look.INK_3, Ui.regular);
            status.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_3}));
            status.setDuplicateParentStateEnabled(true); status.setGravity(Gravity.END);
            if (statusText == null || statusText.isEmpty()) status.setVisibility(GONE);
            LayoutParams sp = new LayoutParams(-2, -2); sp.leftMargin = Look.si(c, 16);
            addView(status, sp);
            trailing = new PlayerIcon(c, symbol == null ? "check" : symbol);
            trailing.setDuplicateParentStateEnabled(true);
            trailing.setVisibility(symbol == null ? GONE : VISIBLE);
            LayoutParams ip = new LayoutParams(Look.si(c, 24), Look.si(c, 24)); ip.leftMargin = Look.si(c, 16);
            addView(trailing, ip);
            setContentDescription(text + (detail == null || detail.isEmpty() ? "" : ", " + detail));
        }
        void checked(boolean on) {
            trailing.symbol("check"); trailing.setVisibility(on ? VISIBLE : GONE); setActivated(on);
            setSelected(on);
        }
        void status(String text) { status.setText(text == null ? "" : text); status.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE); }
        @Override public CharSequence getAccessibilityClassName() { return android.widget.RadioButton.class.getName(); }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(trailing.getVisibility() == VISIBLE || isActivated()); info.setChecked(isActivated() || trailing.getVisibility() == VISIBLE && "check".equals(trailing.symbol()));
        }
    }

    void onDismiss(Runnable value) { dismissed = value; }

    void show() { show(null); }

    void show(View focus) {
        dialog.show();
        View target = focus != null ? focus : chosen != null ? chosen : first;
        if (target != null) target.requestFocus();
    }

    void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }

    /** The simple list sheet (More options and similar). */
    static Dialog show(Activity activity, String title, List<String> labels, int checked, List<Runnable> actions, Runnable dismissed) {
        PlayerSheet sheet = new PlayerSheet(activity, title, null, 680);
        LinearLayout list = sheet.list(sheet.body, 36);
        for (int i = 0; i < labels.size(); i++) {
            final int choice = i;
            sheet.row(list, labels.get(i), null, i == checked, true, () -> { actions.get(choice).run(); sheet.dismiss(); });
        }
        sheet.onDismiss(dismissed);
        sheet.show();
        return sheet.dialog;
    }
}
