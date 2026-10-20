package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A movie's or a show's page (the TitleRow, TitleMovie, TitleNetflix, TitleCastMore and NoSources boards): the
 * backdrop on the right, the title, facts and summary, the actions, then seasons and episodes, the cast and More like
 * this further down. Native views, so the remote's focus moves the way Android TV expects; one focus at a time.
 */
final class TitlePage extends FrameLayout {
    interface Episodes {
        void select(SeriesEpisodes.Episode episode);
        void focus(SeriesEpisodes.Episode episode);
    }

    /** Someone in the cast (TMDB). */
    static final class Person {
        int id;
        String name = "", role = "", photo = "";
    }

    /** A title in More like this or a person's Known for. */
    static final class Item {
        int id;
        String kind = "", name = "", line = "", picture = "";
    }

    interface Open<T> { void open(T value); }

    final TextView heading, metadata, summary, status;
    final LinearLayout content, actions;
    private final Activity activity;
    private final ImageView artwork;
    private final ScrollView scroll;
    private final LinearLayout hero, notice, credits, castSection, moreSection, castRow, moreRow;
    private final ArtTile poster;
    private final TextView clock;
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private final List<Object[]> pending = new ArrayList<>();   // {ArtTile, path, size}
    private int revision;
    private volatile boolean artworkActive = true;
    private String backdropPath = "", posterPath = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(Ui.clock(System.currentTimeMillis()));
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    TitlePage(Activity activity, String title, View.OnClickListener back) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Look.GROUND);
        artwork = new ImageView(activity);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams art = new LayoutParams(px(1340), px(850));
        art.gravity = Gravity.END | Gravity.TOP;
        addView(artwork, art);
        View shade = new View(activity);
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {Look.GROUND, Look.GROUND, 0xB30A0B0D, 0x000A0B0D});
        GradientDrawable bottom = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {Look.GROUND, Look.GROUND, 0x990A0B0D, 0x000A0B0D});
        shade.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[] {left, bottom}));
        shade.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(shade, new LayoutParams(-1, -1));

        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        addView(scroll, new LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setClipChildren(false);
        page.setPadding(px(Look.SAFE_X), px(136), px(Look.SAFE_X), px(96));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(activity);
        top.setClipChildren(false);
        top.setMinimumHeight(px(640 - 136));
        page.addView(top, new LinearLayout.LayoutParams(-1, -2));
        poster = new ArtTile(activity);
        poster.setFallback(title, ArtTile.TITLE);
        poster.setVisibility(GONE);
        LinearLayout.LayoutParams posterSize = new LinearLayout.LayoutParams(px(400), px(600));
        posterSize.rightMargin = px(80);
        posterSize.topMargin = px(-40);
        top.addView(poster, posterSize);
        hero = column();
        hero.setClipChildren(false);
        top.addView(hero, new LinearLayout.LayoutParams(px(1060), -2));
        heading = text(title, Look.TITLE + 20, Look.INK);
        heading.setTypeface(Ui.displaySemibold);
        heading.setLetterSpacing(-0.03f);
        heading.setIncludeFontPadding(false);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setAccessibilityHeading(true);
        hero.addView(heading, new LinearLayout.LayoutParams(px(1000), -2));
        metadata = text("", 26, Look.INK_2);
        hero.addView(metadata, margins(-2, 22));
        summary = text("", Look.BODY, 0xDBF5F3EF);
        summary.setMaxLines(3);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(0, 1.3f);
        hero.addView(summary, margins(px(900), 22));
        notice = column();
        notice.setVisibility(GONE);
        hero.addView(notice, margins(-2, 22));
        actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false);
        hero.addView(actions, margins(-2, 36));
        status = text("", Look.SMALL, Look.INK_3);
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        hero.addView(status, margins(-2, 18));
        credits = column();
        credits.setVisibility(GONE);
        hero.addView(credits, margins(-2, 40));

        content = column();
        content.setClipChildren(false);
        page.addView(content, new LinearLayout.LayoutParams(-1, -2));
        castRow = row();
        castSection = section(page, Ui.tr("Elenco", "Cast"), castRow);
        moreRow = row();
        moreSection = section(page, Ui.tr("Mais como este", "More like this"), moreRow);

        clock = text("", 40, Look.INK);
        clock.setTypeface(Ui.medium);
        LayoutParams clockAt = new LayoutParams(-2, -2);
        clockAt.gravity = Gravity.END | Gravity.TOP;
        clockAt.topMargin = px(56);
        clockAt.rightMargin = px(Look.SAFE_X);
        addView(clock, clockAt);
        clock.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        tick.run();
    }

    // MARK: - Building blocks

    private int px(float design) { return Look.si(activity, design); }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(activity);
        v.setClipChildren(false);
        v.setClipToPadding(false);
        v.setPadding(0, px(12), px(Look.SAFE_X), px(16));
        return v;
    }

    private LinearLayout.LayoutParams margins(int width, float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
        p.topMargin = px(top);
        return p;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(activity);
        v.setText(value);
        Look.textSize(v, size);
        v.setTextColor(color);
        v.setTypeface(Ui.regular);
        return v;
    }

    /** A titled row that scrolls sideways; hidden until it has something. */
    private LinearLayout section(LinearLayout page, String title, LinearLayout items) {
        LinearLayout s = column();
        s.setClipChildren(false);
        s.setVisibility(GONE);
        TextView h = text(title, 24, Look.INK_2);
        h.setTypeface(Ui.medium);
        h.setAccessibilityHeading(true);
        s.addView(h, margins(-2, 0));
        HorizontalScrollView sideways = new HorizontalScrollView(activity);
        sideways.setHorizontalScrollBarEnabled(false);
        sideways.setClipChildren(false);
        sideways.setClipToPadding(false);
        sideways.addView(items);
        s.addView(sideways, margins(-1, 12));
        LinearLayout.LayoutParams at = margins(-1, 72);
        page.addView(s, at);
        return s;
    }

    /**
     * A focusable card: the tile (ring and lift when focused) and up to two lines under it. Width in design px; the
     * tile is width × height.
     */
    private LinearLayout card(ArtTile tile, float width, float height, CharSequence first, String second, boolean centered) {
        LinearLayout card = column();
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);
        card.setId(View.generateViewId());
        if (centered) card.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setDuplicateParentStateEnabled(true);
        tile.setForeground(Look.ring(activity, tile == null ? Look.R_CARD : 16));
        card.addView(tile, new LinearLayout.LayoutParams(px(width), px(height)));
        card.setOnFocusChangeListener((v, focused) -> {
            tile.animate().scaleX(focused ? Look.FOCUS_SCALE : 1).scaleY(focused ? Look.FOCUS_SCALE : 1).setDuration(160).start();
            if (focused) revealRow(v);
        });
        TextView one = text("", 24, Look.INK);
        one.setText(first);
        one.setTypeface(Ui.semibold);
        one.setSingleLine(true);
        one.setEllipsize(TextUtils.TruncateAt.END);
        if (centered) one.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(one, margins(centered ? px(width + 60) : px(width), centered ? 24 : 14));
        if (second != null && !second.isEmpty()) {
            TextView two = text(second, Look.SMALL, 0xA8F5F3EF);
            two.setSingleLine(true);
            two.setEllipsize(TextUtils.TruncateAt.END);
            if (centered) two.setGravity(Gravity.CENTER_HORIZONTAL);
            card.addView(two, margins(centered ? px(width + 60) : px(width), 4));
        }
        for (int i = 1; i < card.getChildCount(); i++) card.getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.setContentDescription(first + (second == null || second.isEmpty() ? "" : ", " + second));
        return card;
    }

    /** Lower rows scroll the page so the whole row (title to its last line) is in view. */
    private void revealRow(View card) {
        View block = card;
        while (block.getParent() instanceof View && block.getParent() != scroll) {
            View parent = (View) block.getParent();
            if (parent == content || parent == castSection.getParent()) break;
            block = parent;
        }
        final View target = block;
        scroll.post(() -> {
            int[] at = new int[2], base = new int[2];
            target.getLocationInWindow(at);
            scroll.getLocationInWindow(base);
            int top = at[1] - base[1], bottom = top + target.getHeight();
            if (target == hero.getParent() || top < px(80)) scroll.smoothScrollBy(0, top - px(Look.SAFE_Y + 40));
            else if (bottom > scroll.getHeight() - px(40)) scroll.smoothScrollBy(0, bottom - scroll.getHeight() + px(60));
        });
    }

    // MARK: - Actions

    /** A button in the actions row. `width` is ignored: buttons fit their label (Portuguese labels never wrap). */
    Button action(String label, View.OnClickListener listener, int width) {
        Button button = TvWidgets.button(activity, label, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, px(72));
        params.rightMargin = px(16);
        actions.addView(button, params);
        button.setOnFocusChangeListener((v, focused) -> { if (focused) scroll.smoothScrollTo(0, 0); });
        return button;
    }

    /** The round "+" button (Watchlist): a check once the title is saved. */
    Button watchlistButton(View.OnClickListener listener) {
        Button button = TvWidgets.button(activity, "+", listener);
        button.setPadding(0, 0, 0, px(4));
        Look.textSize(button, 34);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(px(72), px(72));
        actions.addView(button, params);
        button.setContentDescription(Ui.tr("Adicionar à lista", "Add to Watchlist"));
        return button;
    }

    static void setSaved(Button button, boolean saved) {
        button.setText(saved ? "✓" : "+");
        button.setContentDescription(saved ? Ui.tr("Na sua lista. Remover", "In your Watchlist. Remove")
                : Ui.tr("Adicionar à lista", "Add to Watchlist"));
    }

    // MARK: - Facts

    void backdrop(String path) {
        backdropPath = path == null ? "" : path;
        if (!artworkActive || backdropPath.isEmpty()) return;
        final int request = revision;
        pictures.execute(() -> {
            if (!artworkActive) return;
            Bitmap bitmap = TitleArtwork.load(backdropPath, "w1280");
            activity.runOnUiThread(() -> {
                if (artworkActive && !activity.isDestroyed() && request == revision && bitmap != null) artwork.setImageBitmap(bitmap);
            });
        });
    }

    /** Movies: the poster beside the facts. */
    void poster(String path) {
        posterPath = path == null ? "" : path;
        poster.setVisibility(VISIBLE);
        hero.getLayoutParams().width = px(1920 - 2 * Look.SAFE_X - 480);
        load(poster, posterPath, "w780", 400, 600);
    }

    /** Movies: Director, Writer, Starring. */
    void credits(String[][] rows) {
        credits.removeAllViews();
        for (String[] r : rows) {
            if (r[1] == null || r[1].isEmpty()) continue;
            LinearLayout line = new LinearLayout(activity);
            TextView label = text(r[0], Look.SMALL, Look.INK_3);
            line.addView(label, new LinearLayout.LayoutParams(px(180), -2));
            TextView value = text(r[1], Look.SMALL, 0xDBF5F3EF);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(value, new LinearLayout.LayoutParams(px(780), -2));
            credits.addView(line, margins(-1, credits.getChildCount() == 0 ? 0 : 10));
        }
        credits.setVisibility(credits.getChildCount() > 0 ? VISIBLE : GONE);
    }

    /**
     * Nothing plays here: the reasons (counts the source search collected) and what to do next, in place of the
     * summary. Pass null to put the page back.
     */
    void deadEnd(String title, List<String> reasons, String[] labels, View.OnClickListener[] actionsFor) {
        notice.removeAllViews();
        if (title == null) {
            notice.setVisibility(GONE);
            summary.setVisibility(VISIBLE);
            actions.setVisibility(VISIBLE);
            return;
        }
        TextView h = text(title, 30, Look.INK);
        h.setTypeface(Ui.semibold);
        notice.addView(h);
        for (String reason : reasons) {
            TextView line = text("·  " + reason, Look.SMALL + 2, 0xDBF5F3EF);
            notice.addView(line, margins(-2, 8));
        }
        LinearLayout buttons = new LinearLayout(activity);
        for (int i = 0; i < labels.length; i++) {
            Button b = TvWidgets.button(activity, labels[i], actionsFor[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(72));
            p.rightMargin = px(16);
            buttons.addView(b, p);
        }
        notice.addView(buttons, margins(-2, 32));
        notice.setVisibility(VISIBLE);
        summary.setVisibility(GONE);
        actions.setVisibility(GONE);
        status.setText("");
        scroll.smoothScrollTo(0, 0);
        if (buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
    }

    // MARK: - Episodes

    void episodes(List<SeriesEpisodes.Episode> episodes, String imdb, List<Integer> seasons, int season, int selected, boolean focus,
                  java.util.function.IntConsumer chooseSeason, Episodes listener) {
        episodes(episodes, imdb, seasons, season, selected, focus, java.util.Collections.emptySet(), chooseSeason, listener);
    }

    /** The season strip and that season's episodes (watched ones checked, the one in progress with its line). */
    void episodes(List<SeriesEpisodes.Episode> episodes, String imdb, List<Integer> seasons, int season, int selected, boolean focus,
                  Set<String> watched, java.util.function.IntConsumer chooseSeason, Episodes listener) {
        int request = revision;
        pending.removeIf(item -> {
            android.view.ViewParent parent = ((View) item[0]).getParent();
            while (parent != null) { if (parent == content) return true; parent = parent.getParent(); }
            return false;
        });
        content.removeAllViews();
        LinearLayout strip = new LinearLayout(activity);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(Ui.tr("Temporada", "Season"), 24, 0x9EF5F3EF);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelAt = new LinearLayout.LayoutParams(-2, -2);
        labelAt.rightMargin = px(18);
        strip.addView(label, labelAt);
        Button picker = null;
        for (int number : seasons) {
            String name = number == 0 ? Ui.tr("Extras", "Extras") : String.valueOf(number);
            Button option = TvWidgets.button(activity, name, view -> chooseSeason.accept(number));
            Look.textSize(option, 24);
            option.setSelected(number == season);
            option.setTypeface(number == season ? Ui.semibold : Ui.regular);
            TvWidgets.tab(option);
            option.setContentDescription((number == 0 ? Ui.tr("Extras", "Specials") : Ui.tr("Temporada ", "Season ") + number)
                    + (number == season ? Ui.tr(", selecionada", ", selected") : ""));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, px(56));
            size.rightMargin = px(6);
            strip.addView(option, size);
            option.setOnFocusChangeListener((v, focused) -> { if (focused) scroll.smoothScrollTo(0, Math.max(0, content.getTop() - px(420))); });
            if (number == season) picker = option;
        }
        HorizontalScrollView stripScroll = new HorizontalScrollView(activity);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.addView(strip);
        content.addView(stripScroll, new LinearLayout.LayoutParams(-1, -2));
        final Button selectedSeason = picker;
        if (picker != null) stripScroll.post(() -> stripScroll.scrollTo(Math.max(0, selectedSeason.getLeft() - px(200)), 0));

        HorizontalScrollView sideways = new HorizontalScrollView(activity);
        sideways.setHorizontalScrollBarEnabled(false);
        sideways.setClipChildren(false);
        sideways.setClipToPadding(false);
        LinearLayout cards = row();
        cards.setPadding(0, px(20), px(Look.SAFE_X), px(16));
        sideways.addView(cards);
        content.addView(sideways, margins(-1, 22));
        View restore = null;
        android.content.SharedPreferences progress = activity.getSharedPreferences("playback-progress", 0);
        for (SeriesEpisodes.Episode episode : episodes) {
            ArtTile tile = new ArtTile(activity);
            tile.setFallback(String.valueOf(episode.number), ArtTile.NUMBER);
            String key = episode.season + ":" + episode.number;
            boolean seen = watched.contains(key);
            long position = imdb == null ? 0 : progress.getLong(episode.id(imdb), 0);
            String detail;
            if (episode.future) {
                detail = Ui.tr("Estreia em ", "Airs ") + episode.date;
            } else if (seen) {
                detail = Ui.tr("✓ Assistido", "✓ Watched");
                tile.setDim(true);
            } else if (position > 0 && episode.minutes > 0) {
                long left = Math.max(1, episode.minutes - position / 60_000);
                detail = left + Ui.tr(" min restantes", " min left");
                tile.setProgress(Math.min(1, position / (episode.minutes * 60_000f)));
            } else if (position > 0) {
                detail = Ui.tr("Continuar em ", "Resume at ") + clock(position);
            } else {
                detail = episode.minutes > 0 ? episode.minutes + " min" : "";
            }
            android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
            line.append("E").append(String.valueOf(episode.number));
            line.setSpan(new android.text.style.ForegroundColorSpan(0x8CF5F3EF), 0, line.length(), 0);
            line.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, line.length(), 0);
            line.append("  ").append(episode.name);
            LinearLayout card = card(tile, 400, 225, line, detail, false);
            card.setContentDescription(episode.code() + ". " + episode.name + ". " + detail + ". " + episode.overview);
            if (selectedSeason != null) card.setNextFocusUpId(selectedSeason.getId());
            card.setOnClickListener(v -> listener.select(episode));
            card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), (v, focused) -> { if (focused) listener.focus(episode); }));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
            at.rightMargin = px(32);
            cards.addView(card, at);
            if (episode.number == selected) restore = card;
            load(tile, episode.still, "w780", 400, 225);
        }
        View first = restore != null ? restore : cards.getChildCount() > 0 ? cards.getChildAt(0) : null;
        if (first != null) {
            if (picker != null) for (int i = 0; i < strip.getChildCount(); i++) strip.getChildAt(i).setNextFocusDownId(first.getId());
            if (focus) first.requestFocus();
            final View scrollTo = first;
            sideways.post(() -> sideways.scrollTo(Math.max(0, scrollTo.getLeft() - px(400 + 32)), 0));
        }
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, focused) -> {
            if (a != null) a.onFocusChange(v, focused);
            b.onFocusChange(v, focused);
        };
    }

    // MARK: - Cast and More like this

    void cast(List<Person> people, Open<Person> open) {
        castRow.removeAllViews();
        for (Person p : people) {
            ArtTile tile = new ArtTile(activity).circle();
            tile.setFallback(ArtTile.initials(p.name), ArtTile.INITIALS);
            LinearLayout card = card(tile, 192, 192, p.name, p.role, true);
            card.setOnClickListener(v -> open.open(p));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(px(252), -2);
            at.rightMargin = px(4);
            castRow.addView(card, at);
            if (!p.photo.isEmpty()) load(tile, p.photo, "w300", 192, 192);
        }
        castSection.setVisibility(people.isEmpty() ? GONE : VISIBLE);
    }

    void similar(List<Item> items, Open<Item> open) {
        moreRow.removeAllViews();
        for (Item it : items) {
            ArtTile tile = new ArtTile(activity);
            tile.setFallback(it.name, ArtTile.TITLE);
            LinearLayout card = card(tile, 320, 180, it.name, it.line, false);
            card.setOnClickListener(v -> open.open(it));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
            at.rightMargin = px(32);
            moreRow.addView(card, at);
            load(tile, it.picture, "w780", 320, 180);
        }
        moreSection.setVisibility(items.isEmpty() ? GONE : VISIBLE);
    }

    void filmography(List<Item> items, Open<Item> open) {
        ((TextView) moreSection.getChildAt(0)).setText(Ui.tr("Trabalhos", "Known for"));
        similar(items, open);
    }

    // MARK: - Pictures

    /** Loads a TMDB picture into a tile, cropped to the tile's size so the screen keeps little in memory. */
    private void load(ArtTile tile, String path, String size, int w, int h) {
        if (path == null || path.isEmpty()) return;
        pending.add(new Object[] {tile, path, size, w, h});
        if (!artworkActive) return;
        final int request = revision;
        pictures.execute(() -> {
            if (!artworkActive || request != revision) return;
            Bitmap full = null;
            try { full = MediaHttp.https(path) ? HomeArt.fetch(activity, path, false) : TitleArtwork.load(path, size); }
            catch (Exception ignored) {}
            Bitmap fit = full == null ? null : HomeArt.crop(full, px(w), px(h));
            activity.runOnUiThread(() -> {
                if (artworkActive && !activity.isDestroyed() && request == revision && fit != null) tile.setImage(fit);
            });
        });
    }

    void pauseArtwork() {
        artworkActive = false;
        revision++;
        artwork.setImageDrawable(null);
        clearTiles(this);
        if (pictures instanceof java.util.concurrent.ThreadPoolExecutor) ((java.util.concurrent.ThreadPoolExecutor) pictures).getQueue().clear();
        TitleArtwork.trimMemory();
    }

    void resumeArtwork() {
        if (artworkActive) return;
        artworkActive = true;
        revision++;
        backdrop(backdropPath);
        List<Object[]> again = new ArrayList<>(pending);
        pending.clear();
        for (Object[] p : again) load((ArtTile) p[0], (String) p[1], (String) p[2], (Integer) p[3], (Integer) p[4]);
    }

    private void clearTiles(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ArtTile) ((ArtTile) child).setImage(null);
            else if (child instanceof ViewGroup) clearTiles((ViewGroup) child);
        }
    }

    void release() {
        artworkActive = false;
        revision++;
        removeCallbacks(tick);
        pictures.shutdownNow();
    }

    private static String clock(long value) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", value / 60000, value / 1000 % 60);
    }
}
