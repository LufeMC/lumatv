package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Artwork remains visible while native controls handle remote navigation. */
final class TitlePage extends FrameLayout {
    interface Episodes {
        void select(SeriesEpisodes.Episode episode);
        void focus(SeriesEpisodes.Episode episode);
    }
    final TextView heading, metadata, summary, status;
    final LinearLayout content, actions;
    private final Activity activity;
    private final ImageView artwork;
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private int revision;
    private volatile boolean artworkActive = true;
    private String backdropPath = "";

    TitlePage(Activity activity, String title, View.OnClickListener back) {
        super(activity); this.activity = activity; setBackgroundColor(0xFF101014);
        artwork = new ImageView(activity); artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(artwork, new LayoutParams(-1, -1));
        shade(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF101014, 0xD9101014, 0x20101014});
        shade(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00101014, 0x60101014, 0xFF101014});
        LinearLayout page = column(); page.setPadding(dp(36), dp(14), dp(36), dp(18)); addView(page, new LayoutParams(-1, -1));
        LinearLayout nav = new LinearLayout(activity); nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(new PlayerIcon(activity, "back", Ui.tr("Voltar", "Back"), back), new LinearLayout.LayoutParams(dp(40), dp(38)));
        TextView brand = text("FREE MY TV", 12); brand.setTextColor(0xFFBEBEC6); brand.setPadding(dp(18), 0, 0, 0); nav.addView(brand); page.addView(nav);
        LinearLayout hero = column(); hero.setPadding(0, dp(12), 0, dp(8));
        heading = text(title, 32); heading.setTypeface(Ui.displaySemibold); heading.setAccessibilityHeading(true);
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END); hero.addView(heading, new LinearLayout.LayoutParams(dp(600), -2));
        metadata = text("", 14); metadata.setTextColor(0xFFCCCCD4); hero.addView(metadata);
        summary = text("", 15); summary.setMaxLines(2); summary.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(summary, new LinearLayout.LayoutParams(dp(500), -2));
        actions = new LinearLayout(activity); actions.setPadding(0, dp(10), 0, 0); hero.addView(actions); page.addView(hero);
        status = text("", 13); status.setTextColor(0xFFBEBEC6); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        content = column(); page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    Button action(String label, View.OnClickListener listener, int width) {
        Button button = TvWidgets.button(activity, label, listener);
        button.setSingleLine(true); button.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(44)); params.rightMargin = dp(10); actions.addView(button, params); return button;
    }
    void backdrop(String path) {
        backdropPath = path;
        if (!artworkActive) return;
        pictures.execute(() -> { if (!artworkActive) return; Bitmap bitmap = TitleArtwork.load(path, "w1280");
            activity.runOnUiThread(() -> { if (artworkActive && !activity.isDestroyed() && path.equals(backdropPath) && bitmap != null) artwork.setImageBitmap(bitmap); }); });
    }
    void pauseArtwork() {
        artworkActive = false; revision++; artwork.setImageDrawable(null); clearPictures(content);
        if (pictures instanceof java.util.concurrent.ThreadPoolExecutor) ((java.util.concurrent.ThreadPoolExecutor) pictures).getQueue().clear();
        TitleArtwork.trimMemory();
    }
    void resumeArtwork() {
        if (artworkActive) return;
        artworkActive = true; backdrop(backdropPath); restorePictures(content, revision);
    }
    private void clearPictures(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) ((ImageView) child).setImageDrawable(null);
            else if (child instanceof android.view.ViewGroup) clearPictures((android.view.ViewGroup) child);
        }
    }
    private void restorePictures(android.view.ViewGroup parent, int request) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView && child.getTag() instanceof String) loadStill((ImageView) child, (String) child.getTag(), request);
            else if (child instanceof android.view.ViewGroup) restorePictures((android.view.ViewGroup) child, request);
        }
    }
    private void loadStill(ImageView image, String path, int request) {
        if (!artworkActive || path.isEmpty()) return;
        pictures.execute(() -> {
            if (!artworkActive) return; Bitmap bitmap = TitleArtwork.load(path, "w300");
            activity.runOnUiThread(() -> { if (artworkActive && !activity.isDestroyed() && request == revision && bitmap != null) image.setImageBitmap(bitmap); });
        });
    }
    void episodes(List<SeriesEpisodes.Episode> episodes, String imdb, List<Integer> seasons, int season, int selected, boolean focus,
                  java.util.function.IntConsumer chooseSeason, Episodes listener) {
        int request = ++revision; content.removeAllViews();
        HorizontalScrollView seasonScroll = new HorizontalScrollView(activity); seasonScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout caption = new LinearLayout(activity); caption.setGravity(Gravity.CENTER_VERTICAL); seasonScroll.addView(caption);
        Button selectedSeason = null;
        for (int number : seasons) {
            String name = number == 0 ? Ui.tr("Especiais", "Specials") : String.format(Ui.tr("Temporada %d", "Season %d"), number);
            Button option = TvWidgets.button(activity, name, view -> chooseSeason.accept(number));
            option.setTextSize(14); option.setContentDescription(name + (number == season ? Ui.tr(", selecionada", ", selected") : ""));
            option.setSelected(number == season); TvWidgets.tab(option);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(100), dp(38)); size.rightMargin = dp(6); caption.addView(option, size);
            if (number == season) selectedSeason = option;
        }
        content.addView(seasonScroll); final Button picker = selectedSeason;
        if (picker != null) seasonScroll.post(() -> seasonScroll.scrollTo(Math.max(0, picker.getLeft() - dp(12)), 0));
        HorizontalScrollView scroll = new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, 0); LinearLayout cards = new LinearLayout(activity); cards.setGravity(Gravity.TOP); scroll.addView(cards);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); View restore = null;
        for (SeriesEpisodes.Episode episode : episodes) {
            LinearLayout card = column(); card.setId(View.generateViewId()); card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setFocusable(true); card.setClickable(true); card.setBackground(TvWidgets.cardBackground(activity));
            if (picker != null) card.setNextFocusUpId(picker.getId());
            ImageView still = new ImageView(activity); still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            still.setBackground(TvWidgets.shape(activity, 0xFF242428)); still.setClipToOutline(true); still.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(still, new LinearLayout.LayoutParams(-1, dp(104)));
            TextView name = text(episode.number + ". " + episode.name, 16); name.setTypeface(Ui.medium); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END); card.addView(name);
            long position = imdb == null ? 0 : activity.getSharedPreferences("playback-progress", 0).getLong(episode.id(imdb), 0);
            String detail = episode.minutes > 0 ? episode.minutes + " min" : "";
            if (position > 0) detail = Ui.tr("Continuar em ", "Resume at ") + clock(position);
            else if (episode.future) detail = Ui.tr("Estreia em ", "Airs on ") + episode.date;
            TextView info = text(detail, 12); info.setTextColor(0xFFBEBEC6); card.addView(info);
            TextView synopsis = text(episode.overview, 13); synopsis.setTextColor(0xFFCCCCD4); synopsis.setMaxLines(1); synopsis.setEllipsize(TextUtils.TruncateAt.END); card.addView(synopsis);
            card.setContentDescription(episode.code() + ". " + episode.name + ". " + detail + ". " + episode.overview);
            for (TextView child : new TextView[]{name, info, synopsis}) child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.setOnFocusChangeListener((view, focused) -> { if (focused) listener.focus(episode); });
            card.setOnClickListener(view -> listener.select(episode));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), -2); params.rightMargin = dp(14); cards.addView(card, params);
            if (episode.number == selected) restore = card;
            still.setTag(episode.still); loadStill(still, episode.still, request);
        }
        View first = restore != null ? restore : cards.getChildCount() > 0 ? cards.getChildAt(0) : null;
        if (first != null) { if (picker != null) picker.setNextFocusDownId(first.getId()); if (focus) first.requestFocus(); }
    }
    void release() { artworkActive = false; revision++; pictures.shutdownNow(); }
    private void shade(GradientDrawable.Orientation orientation, int[] colors) {
        View shade = new View(activity); shade.setBackground(new GradientDrawable(orientation, colors)); addView(shade, new LayoutParams(-1, -1));
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value, int size) { return TvWidgets.text(activity, value, size); }
    private int dp(float value) { return TvWidgets.dp(activity, value); }
    private static String clock(long value) { return String.format(java.util.Locale.ROOT, "%d:%02d", value / 60000, value / 1000 % 60); }
}
