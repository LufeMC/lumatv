package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

final class PlayerSheet {
    static Dialog show(Activity activity, String title, List<String> labels, int checked, List<Runnable> actions, Runnable dismissed) {
        Dialog dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL);
        int padding = TvWidgets.dp(activity, 24); page.setPadding(padding, padding, padding, padding); page.setBackgroundColor(0xFF151518);
        TextView heading = TvWidgets.text(activity, title, 25); heading.setTypeface(Ui.displaySemibold); heading.setAccessibilityHeading(true); page.addView(heading);
        ScrollView scroll = new ScrollView(activity); LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button selected = null;
        for (int i = 0; i < labels.size(); i++) {
            final int choice = i;
            Button button = TvWidgets.button(activity, (i == checked ? "✓  " : "") + labels.get(i), view -> { actions.get(choice).run(); dialog.dismiss(); });
            button.setContentDescription(labels.get(i) + (i == checked ? Ui.tr(", selecionado", ", selected") : ""));
            button.setTextColor(TvWidgets.textColors()); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setTextSize(17); button.setBackground(TvWidgets.background(activity));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = TvWidgets.dp(activity, 8); list.addView(button, params);
            if (i == checked || selected == null) selected = button;
        }
        page.addView(TvWidgets.button(activity, Ui.tr("Fechar", "Close"), view -> dialog.dismiss()));
        dialog.setContentView(page); dialog.setOnDismissListener(window -> dismissed.run()); dialog.show();
        Window window = dialog.getWindow(); window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.RIGHT); window.setLayout(TvWidgets.dp(activity, 390), -1); window.setDimAmount(0.35f);
        if (selected != null) selected.requestFocus(); return dialog;
    }
}
