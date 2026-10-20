package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home row uses native focus and opens the saved movie or episode. */
final class ContinueRow extends LinearLayout {
    interface Navigation { void leave(boolean up); }
    private final Activity activity;
    private final Navigation navigation;
    private final LinearLayout cards;
    private final HorizontalScrollView scroll;
    private final TextView status, heading;
    private boolean dark = true;
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private final ExecutorService data = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final List<ContinueWatching.Entry> entries = new ArrayList<>();
    private int revision;
    private boolean refreshing, released, opening, active = true;
    private static final int INITIAL_CARDS = 6;
    private String signature = "";
    private final android.util.LruCache<String, Bitmap> artworkCache = new android.util.LruCache<String, Bitmap>(3072) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getAllocationByteCount() / 1024); }
    };
    ContinueRow(Activity activity, Navigation navigation) {
        super(activity); this.activity = activity; this.navigation = navigation; setOrientation(VERTICAL);
        heading = TvWidgets.text(activity, Ui.tr("Continuar assistindo", "Continue Watching"), 22);
        heading.setTypeface(Ui.semibold); heading.setAccessibilityHeading(true); addView(heading);
        scroll = new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false);
        cards = new LinearLayout(activity); cards.setPadding(0, dp(12), dp(10), dp(8)); scroll.addView(cards); addView(scroll);
        status = TvWidgets.text(activity, "", 13); status.setVisibility(GONE); addView(status);
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> loadArtwork());
        update(ContinueWatching.cached(activity));
    }
    void tone(boolean value) { if (dark != value) { dark = value; applyTone(); } }
    private void applyTone() {
        int ink = dark ? 0xFFF5F5F7 : 0xFF1D1D1F;
        heading.setTextColor(ink); status.setTextColor(dark ? 0xFFBABAC4 : 0xFF595963);
        for (int i = 0; i < cards.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) cards.getChildAt(i);
            android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
            GradientDrawable focused = TvWidgets.shape(activity, dark ? 0xFF242428 : 0xFFF5F5F7); focused.setStroke(dp(2), ink);
            background.addState(new int[]{android.R.attr.state_focused}, focused); background.addState(new int[]{}, TvWidgets.shape(activity, 0x00000000)); card.setBackground(background);
            ((ProgressBar) card.getChildAt(1)).setProgressTintList(android.content.res.ColorStateList.valueOf(ink));
            for (int j = 2; j < card.getChildCount(); j++) ((TextView) card.getChildAt(j)).setTextColor(j == 2 ? ink : dark ? 0xFFBABAC4 : 0xFF595963);
        }
    }
    boolean available() { return !entries.isEmpty(); }
    void focus() { if (cards.getChildCount() > 0) cards.getChildAt(0).requestFocus(); }
    void refresh(Runnable changed) {
        if (refreshing || released) return; refreshing = true;
        data.execute(() -> {
            List<ContinueWatching.Entry> loaded = ContinueWatching.refresh(activity);
            activity.runOnUiThread(() -> { refreshing = false; if (!released) { update(loaded); changed.run(); } });
        });
    }
    void pause() {
        active = false; revision++; artworkCache.evictAll();
        for (int i = 0; i < cards.getChildCount(); i++) {
            ImageView image = (ImageView) ((LinearLayout) cards.getChildAt(i)).getChildAt(0);
            image.setImageDrawable(null); image.setTag(false);
        }
    }
    void resume() { active = true; post(() -> loadArtwork()); }
    void release() { released = true; pictures.shutdownNow(); data.shutdownNow(); actions.shutdownNow(); }
    private void update(List<ContinueWatching.Entry> loaded) {
        StringBuilder key = new StringBuilder(); for (ContinueWatching.Entry e : loaded) key.append(e.identity()).append(e.position).append(e.updated);
        if (key.toString().equals(signature)) { post(() -> loadArtwork()); return; }
        signature = key.toString(); String focused = "";
        for (int i = 0; i < cards.getChildCount() && i < entries.size(); i++) if (cards.getChildAt(i).hasFocus()) focused = entries.get(i).identity();
        ++revision; entries.clear(); entries.addAll(loaded); cards.removeAllViews();
        int visible = INITIAL_CARDS;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).identity().equals(focused)) visible = Math.max(visible, i + 2);
        appendCards(visible);
        for (int i = 0; i < cards.getChildCount(); i++) if (((ContinueWatching.Entry) cards.getChildAt(i).getTag()).identity().equals(focused)) cards.getChildAt(i).requestFocus();
        post(() -> loadArtwork());
    }
    private void appendCards(int count) {
        for (int index = cards.getChildCount(); index < Math.min(count, entries.size()); index++) {
            ContinueWatching.Entry entry = entries.get(index);
            LinearLayout card = new LinearLayout(activity); card.setOrientation(VERTICAL); card.setPadding(dp(6), dp(6), dp(6), dp(6));
            card.setId(View.generateViewId()); card.setFocusable(true); card.setClickable(true); card.setBackground(TvWidgets.cardBackground(activity));
            card.setTag(entry); card.setContentDescription(entry.title + ", " + entry.detail()); card.setOnClickListener(v -> open(entry));
            card.setOnKeyListener((v, code, event) -> {
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) navigation.leave(code != KeyEvent.KEYCODE_DPAD_DOWN); return true;
                }
                return false;
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(232), -2); size.rightMargin = dp(12); cards.addView(card, size);
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(TvWidgets.shape(activity, 0xFF29292F));
            image.setClipToOutline(true); image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); card.addView(image, new LayoutParams(-1, dp(124)));
            ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
            progress.setProgress(entry.duration > 0 ? (int) Math.min(1000, entry.position * 1000 / entry.duration) : 0);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF5F5F7)); progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF55555E));
            progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); card.addView(progress, new LayoutParams(-1, dp(3)));
            TextView title = TvWidgets.text(activity, entry.title, 18); title.setTypeface(Ui.medium); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(title);
            TextView detail = TvWidgets.text(activity, entry.detail(), 12); detail.setTextColor(0xFFBABAC4); detail.setMaxLines(1); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(detail);
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused && cards.indexOfChild(v) >= cards.getChildCount() - 2) appendCards(cards.getChildCount() + 4);
            });
        }
        for (int i = 0; i < cards.getChildCount(); i++) {
            cards.getChildAt(i).setNextFocusLeftId(cards.getChildAt(Math.max(0, i - 1)).getId());
            cards.getChildAt(i).setNextFocusRightId(cards.getChildAt(Math.min(cards.getChildCount() - 1, i + 1)).getId());
        }
        applyTone();
    }
    private void open(ContinueWatching.Entry entry) {
        if (opening || released) return;
        opening = true; status.setText(Ui.tr("Carregando…", "Loading…"));
        actions.execute(() -> {
            Intent intent = null;
            try {
                if (entry.external()) intent = ContinueWatching.externalIntent(activity, entry);
                else { ContinueWatching.resolve(entry); if (entry.tmdb > 0) intent = ContinueWatching.internalIntent(activity, entry); }
            } catch (Exception ignored) {}
            final Intent ready = intent;
            activity.runOnUiThread(() -> {
                opening = false;
                if (released) return;
                if (ready != null) try { activity.startActivity(ready); status.setText(""); return; } catch (Exception ignored) {}
                status.setVisibility(VISIBLE); status.setText(Ui.tr("Não foi possível abrir este título. Tente novamente.", "Cannot open this title. Try again."));
            });
        });
    }
    private void loadArtwork() {
        if (released || !active) return;
        int request = revision, left = scroll.getScrollX(), viewport = scroll.getWidth();
        if (viewport > 0 && left + viewport >= cards.getWidth() - dp(500)) appendCards(cards.getChildCount() + 4);
        for (int i = 0; i < cards.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) cards.getChildAt(i); ImageView image = (ImageView) card.getChildAt(0);
            if (viewport == 0 ? i >= 4 : card.getRight() < left - card.getWidth() || card.getLeft() > left + viewport + card.getWidth()) {
                image.setImageDrawable(null); continue;
            }
            if (image.getDrawable() != null || Boolean.TRUE.equals(image.getTag())) continue;
            image.setTag(true); ContinueWatching.Entry entry = (ContinueWatching.Entry) card.getTag();
            pictures.execute(() -> {
                Bitmap bitmap = artwork(entry);
                activity.runOnUiThread(() -> { if (!released && active && request == revision) { image.setTag(false); if (bitmap != null) image.setImageBitmap(bitmap); } });
            });
        }
    }
    private Bitmap artwork(ContinueWatching.Entry entry) {
        String key = entry.identity() + entry.art; Bitmap cached = artworkCache.get(key); if (cached != null) return cached;
        if (entry.external()) {
            cached = ThumbnailCache.read(key);
            if (cached != null) { artworkCache.put(key, cached); return cached; }
        }
        if (!entry.external() && entry.provider.equals("Stremio")) try { ContinueWatching.resolve(entry); } catch (Exception ignored) {}
        String address = entry.art.startsWith("http://") ? "https://" + entry.art.substring(7) : entry.art;
        Bitmap bitmap = addressArtwork(address, 0);
        if (bitmap == null && !released) try {
            TitleSearch.Title selected = null;
            for (TitleSearch.Title title : TitleSearch.search(entry.title)) {
                if (normalize(title.name).equals(normalize(entry.title))) { if (selected == null) selected = title; if (title.kind.equals(entry.kind)) { selected = title; break; } }
            }
            if (selected != null) {
                org.json.JSONObject details = TitleSearch.get("/" + selected.kind + "/" + selected.id + "?language=" + (Ui.en ? "en-US" : "pt-BR"));
                bitmap = TitleArtwork.load(details.optString("backdrop_path", ""), "w780");
                if (bitmap == null) bitmap = TitleArtwork.load(selected.poster, "w342");
            }
        } catch (Exception ignored) {}
        if (bitmap != null) { artworkCache.put(key, bitmap); if (entry.external()) ThumbnailCache.write(key, bitmap); }
        return bitmap;
    }
    private static String normalize(String value) { return value == null ? "" : java.text.Normalizer.normalize(value.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private Bitmap addressArtwork(String address, int redirects) {
        java.net.HttpURLConnection connection = null;
        try {
            if (address.startsWith("https://image.tmdb.org/t/p/")) {
                android.net.Uri uri = android.net.Uri.parse(address); java.util.List<String> segments = uri.getPathSegments();
                if (segments.size() == 4) return TitleArtwork.load("/" + segments.get(3), segments.get(2));
            }
            if (address.startsWith("content://")) {
                try (java.io.InputStream input = activity.getContentResolver().openInputStream(android.net.Uri.parse(address))) { return decode(input); }
            }
            if (!MediaHttp.https(address)) return null;
            connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            int response = connection.getResponseCode();
            if (response >= 300 && response < 400 && redirects < 3) return addressArtwork(new java.net.URL(new java.net.URL(address), connection.getHeaderField("Location")).toString(), redirects + 1);
            if (response != 200) return null;
            try (java.io.InputStream input = connection.getInputStream()) { return decode(input); }
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private Bitmap decode(java.io.InputStream input) throws Exception {
        if (input == null) return null;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
        while ((count = input.read(block)) != -1) { if (output.size() + count > 3 * 1024 * 1024) return null; output.write(block, 0, count); }
        byte[] bytes = output.toByteArray(); android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 12000 || options.outHeight > 12000) return null;
        options.inSampleSize = Math.max(1, options.outWidth / 640); options.inJustDecodeBounds = false;
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
    private int dp(float value) { return TvWidgets.dp(activity, value); }
}
