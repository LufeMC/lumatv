package io.freemytv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Search uses the shared keyboard, phone input, and title cards. */
public class SearchActivity extends TvActivity {
    private final ExecutorService requests = Executors.newFixedThreadPool(3), pictures = Executors.newFixedThreadPool(2), providers = Executors.newFixedThreadPool(3);
    private final android.os.Handler typing = new android.os.Handler(android.os.Looper.getMainLooper());
    private EditText query;
    private KeyRow keys;
    private TextView status;
    private LinearLayout content;
    private ImageView phone;
    private View phoneCard;
    private final Map<String, String> offerLabels = new LinkedHashMap<>();
    private final Set<String> pendingOffers = new HashSet<>();
    private final Map<String, LinearLayout> titleCards = new HashMap<>();
    private List<TitleSearch.Title> titles = new ArrayList<>();
    private List<TitlePage.Person> people = new ArrayList<>();
    private volatile int requestId, pictureEpoch;
    private volatile boolean picturesActive = true;
    private boolean active, picturesReleased;
    private final Runnable suggestions = () -> search(false);
    private final PhoneLink.Listener phoneListener = new PhoneLink.Listener() {
        public void onText(String text) { query.setText(text); query.setSelection(query.length()); }
        public void onOpen(String id) { TitleSearch.Title title = PhoneLink.result(id); if (active && title != null) showOptions(title); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Look.GROUND); root.setClipChildren(false);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setClipChildren(false);
        PlayerIcon searchIcon = new PlayerIcon(this, "search"); searchIcon.colors(Look.INK_2, Look.INK_2);
        top.addView(searchIcon, new LinearLayout.LayoutParams(px(40), px(40)));
        query = new EditText(this); query.setId(View.generateViewId()); query.setSingleLine(true); query.setTextColor(Look.INK);
        query.setTypeface(Ui.displaySemibold); Look.textSize(query, 64); query.setLetterSpacing(-0.025f); query.setHintTextColor(Look.INK_3);
        query.setHint(Ui.tr("Filmes, séries ou pessoas", "Movies, shows, or people")); query.setContentDescription(query.getHint());
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)}); query.setShowSoftInputOnFocus(false);
        query.setBackgroundColor(android.graphics.Color.TRANSPARENT); query.setPadding(0, 0, 0, 0);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setOnEditorActionListener((v, action, event) -> { search(true); return true; });
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(0, -1, 1); qp.leftMargin = px(24); top.addView(query, qp);
        status = text("", 24); status.setTextColor(Look.INK_2); status.setMaxLines(2); status.setGravity(Gravity.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        top.addView(status, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, at(-1, 88, 96, 64, 96));
        keys = new KeyRow(this, voiceIntent().resolveActivity(getPackageManager()) != null, new KeyRow.Listener() {
            public void type(String text) {
                int a=query.getSelectionStart(), b=query.getSelectionEnd();
                if(a<0||b<0){query.append(text);return;}
                int start=Math.min(a,b); query.getText().replace(start,Math.max(a,b),text);query.setSelection(start+text.length());
            }
            public void delete() { if (query.length() > 0) query.getText().delete(query.length() - 1, query.length()); }
            public void voice() { try { startActivityForResult(voiceIntent(), 20); } catch (RuntimeException e) { status.setText(Ui.tr("A voz não está disponível.", "Voice input is unavailable.")); } }
        });
        keys.setId(View.generateViewId()); query.setNextFocusDownId(keys.getId()); keys.setNextFocusUpId(query.getId());
        root.addView(keys, at(-1, 56, 96, 178, 96));
        FrameLayout results = new FrameLayout(this); results.setClipChildren(false);
        ScrollView scroll = new ScrollView(this); scroll.setClipChildren(false); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        content = column(); content.setClipChildren(false); content.setPadding(0, px(20), 0, px(64)); scroll.addView(content);
        results.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout phonePanel = column(); phonePanel.setPadding(px(36), px(36), px(36), px(36));
        phonePanel.setBackground(PlayerViews.glass(this, 32));
        TextView phoneHeading = text(Ui.tr("Digite pelo telefone", "Type on your phone"), 30); phoneHeading.setTypeface(Ui.semibold); phonePanel.addView(phoneHeading);
        TextView phoneHint = text(Ui.tr("Escaneie com a câmera.\nO texto aparece aqui.", "Scan with your camera.\nYour text appears here."), 22);
        phoneHint.setTextColor(Look.INK_2); phoneHint.setLineSpacing(0, 1.3f); phonePanel.addView(phoneHint, gap(-1, -2, 8));
        phone = new ImageView(this); phone.setBackground(Look.round(this, 0xFFE9E6DF, 20)); phone.setPadding(px(26), px(26), px(26), px(26));
        phone.setContentDescription(Ui.tr("Código para buscar pelo telefone", "Code for phone search")); phonePanel.addView(phone, gap(px(328), px(328), 25));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(400), -2, Gravity.TOP | Gravity.END); pp.topMargin = px(20); results.addView(phonePanel, pp); phoneCard = phonePanel;
        FrameLayout.LayoutParams rp = at(-1, -1, 96, 300, 96); root.addView(results, rp);
        setContentView(root);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                typing.removeCallbacks(suggestions); requestId++; pictureEpoch++;
                if (pictures instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) pictures).getQueue().clear();
                titles.clear(); people.clear(); pendingOffers.clear(); titleCards.clear(); content.removeAllViews();
                phoneCard.setVisibility(s.toString().trim().length() < 2 ? View.VISIBLE : View.GONE);
                if (s.toString().trim().length() < 2) { empty(); return; }
                status.setText(Ui.tr("Buscando…", "Searching…")); typing.postDelayed(suggestions, 350);
            }
        });
        if (state != null) query.setText(state.getString("query", ""));
        else if (getIntent().getIntExtra("title_id", 0) > 0) {
            TitleSearch.Title title = new TitleSearch.Title(); title.id = getIntent().getIntExtra("title_id", 0);
            title.kind = getIntent().getStringExtra("title_kind"); title.name = getIntent().getStringExtra("title_name");
            if (title.name != null && ("tv".equals(title.kind) || "movie".equals(title.kind))) showOptions(title);
        } else empty();
        keys.requestFocus();
    }
    private Intent voiceIntent() { return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null) { ArrayList<String> values = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if (values != null && !values.isEmpty()) query.setText(values.get(0)); }
    }
    void focusSearch() { query.selectAll(); keys.requestFocus(); }
    private void empty() {
        int generation = requestId; status.setText(""); phoneCard.setVisibility(View.VISIBLE); content.setPadding(0, px(20), 0, px(64));
        content.removeAllViews(); LinearLayout recentBlock = column(); recentBlock.setMinimumHeight(px(160));
        LinearLayout recent = new LinearLayout(this);
        try {
            JSONArray saved = new JSONArray(Prefs.get(this, Prefs.HOME_UI).getString(Prefs.RECENT_SEARCHES, "[]"));
            for (int i = 0; i < saved.length() && i < 3; i++) {
                String value = saved.optString(i); if (value.isEmpty()) continue;
                Button button = TvWidgets.button(this, value, v -> query.setText(value));
                LinearLayout.LayoutParams bp = gap(-2, px(60), 0); bp.rightMargin = px(12); recent.addView(button, bp);
            }
        } catch (Exception ignored) {}
        if (recent.getChildCount() > 0) {
            TextView h = text(Ui.tr("Recentes", "Recent"), 24); h.setTextColor(Look.INK_2); recentBlock.addView(h);
            recentBlock.addView(recent, gap(-2, -2, 20));
        }
        content.addView(recentBlock);
        requests.execute(() -> {
            try {
                List<TitleSearch.Title> result = TitleSearch.parse(TitleSearch.get("/trending/all/week?language=" + People.language()).optJSONArray("results"));
                runOnUiThread(() -> { if (current(generation) && query.length() < 2) addTitles(Ui.tr("Em alta nesta semana", "Trending this week"), result.subList(0, Math.min(3, result.size())), generation, false); });
            } catch (Exception ignored) {}
        });
    }
    private void search(boolean focus) {
        typing.removeCallbacks(suggestions); String value = query.getText().toString().trim(); if (value.length() < 2) return;
        final int generation = ++requestId; titles.clear(); people.clear(); content.removeAllViews(); phoneCard.setVisibility(View.GONE);
        status.setText(Ui.tr("Buscando…", "Searching…"));
        requests.execute(() -> {
            try { List<TitleSearch.Title> found = TitleSearch.searchBoth(value); runOnUiThread(() -> { if (current(generation)) { merge(found); render(generation, focus); } }); }
            catch (Exception e) { runOnUiThread(() -> { if (current(generation)) status.setText(Setup.hasKey() ? Ui.tr("Não foi possível buscar. Tente novamente.", "Cannot search. Try again.") : Ui.tr("Configure a chave TMDB em Ajustes.", "Set the TMDB key in Settings.")); }); }
        });
        requests.execute(() -> {
            try { List<TitlePage.Person> found = People.search(value); runOnUiThread(() -> { if (current(generation)) { people = found; render(generation, false); } }); } catch (Exception ignored) {}
        });
        requests.execute(() -> {
            try { List<TitleSearch.Title> found = AddonCatalogs.search(this, value); runOnUiThread(() -> { if (current(generation) && !found.isEmpty()) { merge(found); render(generation, false); } }); } catch (Exception ignored) {}
        });
    }
    private void merge(List<TitleSearch.Title> found) {
        Set<String> seen = new HashSet<>(); for (TitleSearch.Title t : titles) seen.add(t.kind + t.id);
        for (TitleSearch.Title t : found) if (seen.add(t.kind + t.id)) titles.add(t);
        String typed = query.getText().toString().trim().toLowerCase(Locale.ROOT);
        titles.sort((a,b) -> Double.compare(score(b, typed), score(a, typed)));
    }
    private static double score(TitleSearch.Title t, String typed) { return TitleSearch.rank(t, typed); }
    private void render(int generation, boolean focus) {
        Object selected = getCurrentFocus() == null ? null : getCurrentFocus().getTag(); content.removeAllViews(); content.setPadding(0, 0, 0, px(64));
        int count = titles.size(); status.setText(count == 0 && people.isEmpty() ? Ui.tr("Nenhum resultado.", "No results.")
                : count + (count == 1 ? Ui.tr(" título", " title") : Ui.tr(" títulos", " titles")));
        addTitles(null, titles, generation, true);
        if (!people.isEmpty()) {
            LinearLayout row = row(Ui.tr("Pessoas", "People"));
            for (TitlePage.Person p : people) {
                ArtTile tile = new ArtTile(this).circle(); tile.setFallback(ArtTile.initials(p.name), ArtTile.INITIALS);
                LinearLayout card = card(tile, p.name, p.role, 192, 192); card.setTag("person:" + p.id);
                card.setOnClickListener(v -> startActivity(new Intent(this, PersonActivity.class).putExtra("person_id", p.id).putExtra("person_name", p.name)));
                row.addView(card, gap(px(252), -2, 0)); load(tile, p.photo, generation);
            }
        }
        View restored = selected == null ? null : content.findViewWithTag(selected);
        if (restored != null) restored.requestFocus(); else if (focus) { View first = content.focusSearch(View.FOCUS_FORWARD); if (first != null) first.requestFocus(); }
    }
    private void addTitles(String label, List<TitleSearch.Title> found, int generation, boolean posters) {
        if (found.isEmpty()) return;
        LinearLayout grid = null, titleRow = null;
        if (posters) { grid = column(); grid.setClipChildren(false); content.addView(grid); }
        else titleRow = row(label);
        for (int i = 0; i < Math.min(20, found.size()); i++) {
            TitleSearch.Title title = found.get(i);
            if (posters && i % 6 == 0) {
                titleRow = new LinearLayout(this); titleRow.setClipChildren(false); grid.addView(titleRow, gap(-1, -2, i == 0 ? 0 : 36));
            }
            int width = posters ? 264 : 400, height = posters ? 396 : 225;
            String key = title.kind + ":" + title.id;
            LinearLayout card = titleCards.get(key);
            if (card == null) {
                ArtTile tile = new ArtTile(this); tile.setFallback(title.name, ArtTile.TITLE);
                card = card(tile, title.name, offerLabels.getOrDefault(key, title.year), width, height); card.setTag(key);
                card.setOnClickListener(v -> { remember(); showOptions(title); }); titleCards.put(key, card);
                load(tile, posters ? title.poster : title.backdrop == null || title.backdrop.isEmpty() ? title.poster : title.backdrop, generation);
            } else if (card.getParent() instanceof ViewGroup) ((ViewGroup) card.getParent()).removeView(card);
            LinearLayout.LayoutParams cp = gap(px(width), -2, 0); cp.rightMargin = px(28); titleRow.addView(card, cp);
            if (i < 6) fillProvider(card, title, generation);
        }
    }
    private void fillProvider(LinearLayout card, TitleSearch.Title title, int generation) {
        String key = title.kind + ":" + title.id, pending = generation + ":" + key;
        if (offerLabels.containsKey(key) || !pendingOffers.add(pending)) return;
        providers.execute(() -> {
            String label = title.year;
            try {
                if (!current(generation)) return;
                WatchOptions.Option preferred = WatchOptions.preferred(WatchOptions.catalog(this, title).options);
                if (preferred != null) label = Ui.tr("Abre no ", "Opens in ") + preferred.service.name;
                else if (AddonStore.connected(this)) label = "Stremio";
            } catch (Exception ignored) {}
            final String service = label;
            runOnUiThread(() -> {
                pendingOffers.remove(pending);
                if (!current(generation)) return;
                if (offerLabels.size() > 64) offerLabels.clear(); offerLabels.put(key, service);
                View shown = content.findViewWithTag(key);
                if (shown instanceof LinearLayout) { ((TextView) ((LinearLayout) shown).getChildAt(2)).setText(service); shown.setContentDescription(title.name + ". " + service); }
            });
        });
    }
    private LinearLayout row(String label) {
        TextView h = text(label, 26); h.setTextColor(Look.INK_2); h.setAccessibilityHeading(true); content.addView(h, gap(-1, -2, 20));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false); scroll.setClipChildren(false);
        LinearLayout row = new LinearLayout(this); row.setClipChildren(false); row.setPadding(px(8), px(16), px(8), px(24)); scroll.addView(row); content.addView(scroll); return row;
    }
    private LinearLayout card(ArtTile tile, String name, String line, int width, int height) {
        LinearLayout card = column(); card.setFocusable(true); card.setClickable(true); card.setClipChildren(false); card.setContentDescription(name + ". " + line);
        tile.setDuplicateParentStateEnabled(true); tile.setForeground(Look.ring(this, 16)); card.addView(tile, gap(px(width), px(height), 0));
        card.setOnFocusChangeListener((v, focused) -> tile.animate().scaleX(focused ? 1.04f : 1).scaleY(focused ? 1.04f : 1).setDuration(140).start());
        TextView title = text(name, 24); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); card.addView(title, gap(px(width), -2, 14));
        TextView detail = text(line, 22); detail.setTextColor(Look.INK_3); card.addView(detail, gap(px(width), -2, 4)); return card;
    }
    private void load(ArtTile tile, String path, int generation) {
        if (path == null || path.isEmpty()) return;
        tile.setTag(path);
        if (!picturesActive) return;
        int epoch = pictureEpoch;
        pictures.execute(() -> {
            if (!picturesActive || epoch != pictureEpoch) return;
            Bitmap b = TitleArtwork.load(path, "w300");
            runOnUiThread(() -> { if (b != null && picturesActive && epoch == pictureEpoch && current(generation)) tile.setImage(b); });
        });
    }
    private void images(android.view.ViewGroup parent, boolean restore) {
        for (int i = 0; i < parent.getChildCount(); i++) { View v = parent.getChildAt(i);
            if (v instanceof ArtTile) { if (restore && v.getTag() instanceof String) load((ArtTile) v, (String) v.getTag(), requestId); else ((ArtTile) v).setImage(null); }
            else if (v instanceof android.view.ViewGroup) images((android.view.ViewGroup) v, restore);
        }
    }
    private void remember() {
        String value = query.getText().toString().trim(); if (value.isEmpty()) return;
        JSONArray next = new JSONArray().put(value);
        try { JSONArray old = new JSONArray(Prefs.get(this, Prefs.HOME_UI).getString(Prefs.RECENT_SEARCHES, "[]")); for (int i = 0; i < old.length() && next.length() < 8; i++) if (!value.equals(old.optString(i))) next.put(old.optString(i)); } catch (Exception ignored) {}
        Prefs.get(this, Prefs.HOME_UI).edit().putString(Prefs.RECENT_SEARCHES, next.toString()).apply();
    }
    private void showOptions(TitleSearch.Title title) { startActivity(new Intent(this, SourcesActivity.class).putExtra("title_id", title.id).putExtra("title_kind", title.kind).putExtra("title_name", title.name)); }
    private boolean current(int generation) { return generation == requestId && !isFinishing() && !isDestroyed(); }
    @Override protected void onResume() {
        super.onResume(); active = true; picturesActive = true; PhoneLink.setTypingListener(phoneListener);
        String url = PhoneLink.typingUrl(this); phone.setImageBitmap(url == null ? null : Qr.bitmap(url, px(275)));
        if (picturesReleased) { picturesReleased = false; images(content, true); }
    }
    @Override protected void onPause() { active = false; PhoneLink.setTypingListener(null); super.onPause(); }
    @Override protected void onStop() {
        picturesActive = false; picturesReleased = true; pictureEpoch++;
        if (pictures instanceof java.util.concurrent.ThreadPoolExecutor) ((java.util.concurrent.ThreadPoolExecutor) pictures).getQueue().clear();
        images(content, false); TitleArtwork.trimMemory(); super.onStop();
    }
    @Override protected void onDestroy() { requestId++; typing.removeCallbacksAndMessages(null); requests.shutdownNow(); pictures.shutdownNow(); providers.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("query", query.getText().toString()); super.onSaveInstanceState(out); }
    private FrameLayout.LayoutParams at(int width, int height, float left, float top, float right) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width < 0 ? width : px(width), height < 0 ? height : px(height));
        p.leftMargin = px(left); p.topMargin = px(top); p.rightMargin = px(right); return p;
    }
    private int px(float value) { return Look.si(this, value); }
    private TextView text(String value, float size) { TextView t = new TextView(this); t.setText(value); t.setTextColor(Look.INK); t.setTypeface(Ui.regular); Look.textSize(t, size); return t; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout.LayoutParams gap(int width, int height, float margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.topMargin = px(margin); return p; }
}
