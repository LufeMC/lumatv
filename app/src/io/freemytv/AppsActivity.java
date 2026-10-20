package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native controls provide remote navigation and accessible app actions. */
public class AppsActivity extends TvActivity {
    private final ArrayList<String> selected = new ArrayList<>();
    private final ArrayList<String> original = new ArrayList<>();
    private List<HomeApps.Item> available = new ArrayList<>();
    private LinearLayout rows;
    private TextView status;
    private Button add, save, cancel;
    private boolean loaded, saving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Watchdog.install(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(24), dp(40), dp(20));
        root.setBackgroundColor(0xFF151518);
        TextView title = text(Ui.tr("Editar apps do Início", "Edit home apps"), 28);
        title.setTypeface(Ui.displaySemibold);
        title.setAccessibilityHeading(true);
        root.addView(title);
        root.addView(text(Ui.tr("Os apps aparecem nesta ordem, da esquerda para a direita.",
                "Apps appear from left to right in this order."), 16));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(12), 0, dp(8));
        add = button(Ui.tr("Adicionar app", "Add app"), v -> showPicker());
        save = button(Ui.tr("Salvar", "Save"), v -> save());
        cancel = button(Ui.tr("Cancelar", "Cancel"), v -> onBackPressed());
        actions.addView(add, actionParams());
        actions.addView(save, actionParams());
        actions.addView(cancel, actionParams());
        root.addView(actions);
        status = text(Ui.tr("Carregando apps…", "Loading apps…"), 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, 0);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        add.setEnabled(false);
        save.setEnabled(false);
        cancel.requestFocus();
        new Thread(() -> {
            try {
                List<String> saved = HomeApps.shelfPackages(this);
                List<HomeApps.Item> apps = HomeApps.editableApps(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    available = apps;
                    original.addAll(saved);
                    ArrayList<String> draft = state == null ? null : state.getStringArrayList("draft");
                    selected.addAll(draft == null ? saved : draft);
                    loaded = true;
                    render(getIntent().getStringExtra("package"), 0);
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> status.setText(Ui.tr("Não foi possível carregar os apps. Feche esta tela e tente novamente.",
                        "Cannot load apps. Close this screen and try again.")));
            }
        }, "home-apps-load").start();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (loaded) state.putStringArrayList("draft", new ArrayList<>(selected));
    }

    private void render(String focusPackage, int action) {
        rows.removeAllViews();
        add.setEnabled(selected.size() < HomeApps.MAX_SHELF);
        save.setEnabled(true);
        String count = String.format(Ui.tr("%d de %d apps.", "%d of %d apps."), selected.size(), HomeApps.MAX_SHELF);
        status.setText(count + " " + (selected.size() == HomeApps.MAX_SHELF
                ? Ui.tr("Remova um app para adicionar outro.", "Remove an app to add another.")
                : Ui.tr("Selecione Salvar para aplicar as alterações.", "Select Save to apply changes.")));
        View target = null;
        for (int i = 0; i < selected.size(); i++) {
            String pkg = selected.get(i);
            String name = label(pkg);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(4), dp(4), dp(4));
            row.setBackground(shape(0xFF242428));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            rows.addView(row, rp);
            TextView nameView = text((i + 1) + ". " + name, 18);
            nameView.setFocusable(true);
            StateListDrawable nameBackground = new StateListDrawable();
            nameBackground.addState(new int[]{android.R.attr.state_focused}, shape(0xFFF5F5F7));
            nameBackground.addState(new int[]{}, shape(0xFF242428));
            nameView.setBackground(nameBackground);
            nameView.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF1C1C1E, 0xFFF5F5F7}));
            nameView.setPadding(dp(8), dp(8), dp(8), dp(8));
            nameView.setMaxLines(2);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));
            Button left = button(Ui.tr("Mover à esquerda", "Move left"), v -> move(pkg, -1));
            Button right = button(Ui.tr("Mover à direita", "Move right"), v -> move(pkg, 1));
            Button remove = button(Ui.tr("Remover", "Remove"), v -> remove(pkg));
            left.setContentDescription(String.format(Ui.tr("Mover %s à esquerda", "Move %s left"), name));
            right.setContentDescription(String.format(Ui.tr("Mover %s à direita", "Move %s right"), name));
            remove.setContentDescription(String.format(Ui.tr("Remover %s do Início", "Remove %s from Home"), name));
            left.setEnabled(i > 0);
            right.setEnabled(i < selected.size() - 1);
            row.addView(left, actionParams());
            row.addView(right, actionParams());
            row.addView(remove, actionParams());
            if (pkg.equals(focusPackage)) {
                target = action == 2 ? remove : action == 1 ? right : left;
                if (!target.isEnabled()) target = nameView;
            }
        }
        if (selected.isEmpty()) rows.addView(text(Ui.tr("Nenhum app no Início. Selecione Adicionar app.",
                "No home apps. Select Add app."), 20));
        if (target == null) target = add.isEnabled() ? add : save;
        target.requestFocus();
    }

    private String label(String pkg) {
        for (HomeApps.Item item : available) if (pkg.equals(item.pkg)) return item.label;
        return HomeApps.appLabel(this, pkg);
    }

    private void move(String pkg, int delta) {
        int from = selected.indexOf(pkg), to = from + delta;
        if (from < 0 || to < 0 || to >= selected.size()) return;
        Collections.swap(selected, from, to);
        render(pkg, delta < 0 ? 0 : 1);
        status.setText(String.format(Ui.tr("%s está na posição %d. Selecione Salvar para aplicar.",
                "%s is at position %d. Select Save to apply."), label(pkg), to + 1));
    }

    private void remove(String pkg) {
        int index = selected.indexOf(pkg);
        selected.remove(pkg);
        render(selected.isEmpty() ? null : selected.get(Math.min(index, selected.size() - 1)), 2);
        status.setText(String.format(Ui.tr("%s foi removido do Início. O app continua instalado.",
                "%s was removed from Home. The app remains installed."), label(pkg)));
    }

    private void showPicker() {
        if (!loaded || saving || selected.size() >= HomeApps.MAX_SHELF) return;
        List<HomeApps.Item> choices = new ArrayList<>();
        for (HomeApps.Item app : available) if (!selected.contains(app.pkg)) choices.add(app);
        if (choices.isEmpty()) {
            status.setText(Ui.tr("Todos os apps disponíveis já estão no Início.", "All available apps are already on Home."));
            return;
        }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle(Ui.tr("Adicionar app", "Add app"))
                .setItems(names, (dialog, index) -> {
                    String pkg = choices.get(index).pkg;
                    if (!selected.contains(pkg) && selected.size() < HomeApps.MAX_SHELF) selected.add(pkg);
                    render(pkg, 0);
                }).setNegativeButton(Ui.tr("Cancelar", "Cancel"), null).show();
    }

    private void save() {
        if (!loaded || saving) return;
        saving = true;
        add.setEnabled(false);
        save.setEnabled(false);
        cancel.setEnabled(false);
        setChildrenEnabled(rows, false);
        status.setText(Ui.tr("Salvando…", "Saving…"));
        ArrayList<String> copy = new ArrayList<>(selected);
        new Thread(() -> {
            boolean success;
            try { success = HomeApps.saveShelf(this, copy); } catch (RuntimeException e) { success = false; }
            final boolean saved = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                saving = false;
                if (saved) { finish(); return; }
                cancel.setEnabled(true);
                render(null, 0);
                status.setText(Ui.tr("Não foi possível salvar. Verifique os apps e tente novamente.",
                        "Cannot save changes. Check the apps and try again."));
            });
        }, "home-apps-save").start();
    }

    private void setChildrenEnabled(android.view.ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof android.view.ViewGroup) setChildrenEnabled((android.view.ViewGroup) child, enabled);
        }
    }

    @Override public void onBackPressed() {
        if (saving) return;
        if (!loaded || selected.equals(original)) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle(Ui.tr("Descartar alterações?", "Discard changes?"))
                .setMessage(Ui.tr("A ordem salva dos apps não será alterada.", "The saved app order will stay the same."))
                .setNegativeButton(Ui.tr("Continuar editando", "Keep editing"), (d, w) -> cancel.requestFocus())
                .setPositiveButton(Ui.tr("Descartar", "Discard"), (d, w) -> finish()).show();
    }

    private TextView text(String value, int size) { return TvWidgets.text(this, value, size); }

    private Button button(String label, View.OnClickListener action) { return TvWidgets.button(this, label, action); }

    private GradientDrawable shape(int color) { return TvWidgets.shape(this, color); }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(122), -2);
        p.setMarginEnd(dp(8));
        return p;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
