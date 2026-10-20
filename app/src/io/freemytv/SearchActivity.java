package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Search titles once, then choose an installed service with the remote. */
public class SearchActivity extends TvActivity {
    private final ExecutorService requests = Executors.newFixedThreadPool(2);
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private EditText query;
    private TextView status;
    private LinearLayout content;
    private List<TitleSearch.Title> titles = new ArrayList<>();
    private TitleSearch.Title selected;
    private String lastQuery = "";
    private volatile int requestId;
    private boolean active;
    private volatile int pictureEpoch;
    private volatile boolean picturesActive = true;
    private boolean picturesReleased;
    private final android.os.Handler typing = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable suggestions = () -> search(false);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Watchdog.install(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout root = column();
        root.setPadding(dp(40), dp(20), dp(40), dp(14));
        root.setBackgroundColor(0xFF101014);
        TextView heading = text(Ui.tr("Buscar", "Search"), 30);
        heading.setTypeface(Ui.displaySemibold);
        heading.setAccessibilityHeading(true);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(TvWidgets.button(this, Ui.tr("Créditos", "Credits"), v -> MediaCredits.show(this)), actionParams());
        root.addView(header);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(10), 0, dp(8));
        query = new EditText(this);
        query.setId(View.generateViewId());
        query.setSingleLine(true);
        query.setTextSize(18);
        query.setTypeface(Ui.regular);
        query.setTextColor(0xFFF5F5F7);
        query.setHintTextColor(0xFFBEBEC6);
        query.setHint(Ui.tr("Filme ou série", "Movie or TV show"));
        query.setContentDescription(Ui.tr("Título para buscar", "Title to search"));
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        query.setPadding(dp(14), dp(9), dp(14), dp(9));
        android.graphics.drawable.StateListDrawable inputBackground = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable focused = TvWidgets.shape(this, 0xFF36363C);
        focused.setStroke(dp(2), 0xFFF5F5F7);
        inputBackground.addState(new int[]{android.R.attr.state_focused}, focused);
        inputBackground.addState(new int[]{}, TvWidgets.shape(this, 0xFF242428));
        query.setBackground(inputBackground);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; }
            return false;
        });
        controls.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));

        Intent recognition = voiceIntent();
        if (recognition.resolveActivity(getPackageManager()) != null) {
            Button voice = TvWidgets.button(this, Ui.tr("Usar voz", "Use voice"), v -> {
                try { startActivityForResult(voiceIntent(), 20); }
                catch (ActivityNotFoundException | SecurityException e) {
                    status.setText(Ui.tr("A entrada por voz não está disponível. Use o teclado da TV.",
                            "Voice input is unavailable. Use the TV keyboard."));
                    query.requestFocus();
                }
            });
            controls.addView(voice, actionParams());
        }
        root.addView(controls);
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable text) {
                typing.removeCallbacks(suggestions); requestId++;
                if (status == null || content == null) return;
                content.removeAllViews();
                if (text.toString().trim().length() < 2) {
                    status.setText(Ui.tr("Busque filmes e séries.", "Search movies and shows.")); return;
                }
                status.setText(Ui.tr("Buscando…", "Searching…")); typing.postDelayed(suggestions, 400);
            }
        });
        status = text(Ui.tr("Busque filmes e séries.", "Search movies and shows."), 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(0, dp(10), 0, dp(6));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        query.requestFocus();
        if (state == null && getIntent().getIntExtra("title_id", 0) <= 0) query.postDelayed(() -> { if (active) focusSearch(); }, 150);
        if (state != null) {
            query.setText(state.getString("query", ""));
            if (!query.getText().toString().trim().isEmpty()) search();
        } else if (getIntent().getIntExtra("title_id", 0) > 0) {
            TitleSearch.Title title = new TitleSearch.Title();
            title.id = getIntent().getIntExtra("title_id", 0);
            title.kind = getIntent().getStringExtra("title_kind");
            title.name = getIntent().getStringExtra("title_name");
            title.year = ""; title.overview = ""; title.poster = "";
            if (("movie".equals(title.kind) || "tv".equals(title.kind)) && title.name != null) {
                query.setText(title.name);
                showOptions(title);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume(); active = true; picturesActive = true;
        if (picturesReleased) { picturesReleased = false; restorePosters(content); }
    }
    @Override protected void onStop() {
        picturesActive = false; pictureEpoch++; picturesReleased = true; clearPosters(content);
        if (pictures instanceof java.util.concurrent.ThreadPoolExecutor) ((java.util.concurrent.ThreadPoolExecutor) pictures).getQueue().clear();
        TitleArtwork.trimMemory(); super.onStop();
    }
    private void clearPosters(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) ((ImageView) child).setImageDrawable(null);
            else if (child instanceof android.view.ViewGroup) clearPosters((android.view.ViewGroup) child);
        }
    }
    private void restorePosters(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView && child.getTag() instanceof String) loadPoster((ImageView) child, (String) child.getTag(), requestId);
            else if (child instanceof android.view.ViewGroup) restorePosters((android.view.ViewGroup) child);
        }
    }
    private void loadPoster(ImageView poster, String path, int generation) {
        if (!picturesActive || path.isEmpty()) return;
        int epoch = pictureEpoch;
        pictures.execute(() -> {
            if (!picturesActive || epoch != pictureEpoch) return;
            Bitmap bitmap = TitleArtwork.load(path, "w342");
            runOnUiThread(() -> { if (bitmap != null && picturesActive && epoch == pictureEpoch && current(generation)) poster.setImageBitmap(bitmap); });
        });
    }
    @Override protected void onPause() { active = false; super.onPause(); }
    @Override protected void onDestroy() {
        picturesActive = false; pictureEpoch++; requestId++; typing.removeCallbacksAndMessages(null);
        requests.shutdownNow();
        pictures.shutdownNow();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("query", query.getText().toString());
        super.onSaveInstanceState(out);
    }
    private Intent voiceIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Diga o título de um filme ou série", "Say a movie or TV show title"));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null) {
            ArrayList<String> values = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (values != null && !values.isEmpty()) { query.setText(values.get(0)); search(); }
        }
    }

    void focusSearch() {
        if (query == null) return;
        query.requestFocus(); query.selectAll();
        String selectedText = query.getText().toString();
        query.postDelayed(() -> { if (query.hasFocus() && selectedText.equals(query.getText().toString())) query.selectAll(); }, 200);
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }
    private void search() { search(true); }
    private void search(boolean focusResults) {
        typing.removeCallbacks(suggestions);
        String value = query.getText().toString().trim();
        if (value.isEmpty()) {
            status.setText(Ui.tr("Digite o título de um filme ou série.", "Enter a movie or TV show title."));
            query.requestFocus();
            return;
        }
        lastQuery = value;
        selected = null;
        int request = ++requestId;
        content.removeAllViews();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focusResults && keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        status.setText(Ui.tr("Buscando…", "Searching…"));
        requests.execute(() -> {
            try {
                List<TitleSearch.Title> result = TitleSearch.search(value);
                runOnUiThread(() -> {
                    if (!current(request)) return;
                    if (!value.equals(query.getText().toString().trim())) {
                        status.setText(Ui.tr("Selecione Buscar para atualizar os resultados.", "Select Search to update the results."));
                        return;
                    }
                    titles = result;
                    showResults(focusResults);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!current(request)) return;
                    String code = error.getMessage();
                    status.setText("missing_key".equals(code) || "invalid_key".equals(code)
                            ? Ui.tr("Verifique a chave TMDB nos ajustes locais.", "Check the TMDB key in your local settings.")
                            : Ui.tr("Não foi possível buscar. Verifique a conexão e selecione Buscar novamente.",
                                    "Cannot search. Check the connection and select Search again."));
                });
            }
        });
    }

    private void showResults(boolean focusFirst) {
        selected = null; content.removeAllViews();
        status.setText(titles.isEmpty() ? Ui.tr("Nenhum título encontrado. Tente outro nome.", "No titles found. Try another name.")
                : String.format(Ui.tr("%d resultados", "%d results"), titles.size()));
        int generation = requestId;
        android.widget.GridLayout grid = new android.widget.GridLayout(this); grid.setColumnCount(5);
        int available = content.getWidth() > 0 ? content.getWidth() : getResources().getDisplayMetrics().widthPixels - dp(80);
        int width = (available - dp(16) * 4) / 5;
        View first = null;
        for (int i = 0; i < titles.size(); i++) {
            TitleSearch.Title title = titles.get(i);
            LinearLayout card = column(); card.setPadding(dp(6), dp(6), dp(6), dp(6));
            card.setFocusable(true); card.setClickable(true); card.setBackground(TvWidgets.cardBackground(this));
            card.setContentDescription(title.label()); card.setOnClickListener(view -> showOptions(title));
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(TvWidgets.shape(this, 0xFF242428)); poster.setClipToOutline(true);
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(poster, new LinearLayout.LayoutParams(-1, (width - dp(12)) * 3 / 2));
            TextView name = text(title.name, 16); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            TextView year = text(title.year + " · " + (title.series() ? Ui.tr("Série", "Series") : Ui.tr("Filme", "Movie")), 12);
            year.setTextColor(0xFFBEBEC6); card.addView(name); card.addView(year);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); year.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
            params.width = width; params.height = -2; params.rightMargin = i % 5 == 4 ? 0 : dp(16); params.bottomMargin = dp(14);
            grid.addView(card, params); if (first == null) first = card;
            if (title.poster != null && !title.poster.isEmpty()) { poster.setTag(title.poster); loadPoster(poster, title.poster, generation); }
        }
        content.addView(grid);
        if (focusFirst && first != null && active) first.requestFocus();
    }

    private void showOptions(TitleSearch.Title title) {
        startActivity(new Intent(this, SourcesActivity.class).putExtra("title_id", title.id)
                .putExtra("title_kind", title.kind).putExtra("title_name", title.name)
                .putExtra("title_overview", title.overview));
    }

    private boolean current(int request) { return request == requestId && !isFinishing() && !isDestroyed(); }
    private TextView text(String value, int size) { return TvWidgets.text(this, value, size); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(float value) { return TvWidgets.dp(this, value); }
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2); result.bottomMargin = dp(10); return result;
    }
    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(dp(112), dp(48)); result.setMarginStart(dp(10)); return result;
    }
}
