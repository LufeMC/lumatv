package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The LumaTV home screen, search first (the HomeHybrid, HomeUpNext and HomeSearch boards):
 *  - at rest: the landscape for the time of day, the clock, three icon buttons, the search bar (the first focus), a
 *    Resume chip and a quiet row of white app logos;
 *  - Down: content takes over. A cinema hero for the focused card (its backdrop follows focus) and one row: Continue
 *    Watching (with Up next cards), Watchlist and what's trending on your apps;
 *  - OK on Search opens a letter grid beside results that identify each title's service;
 *  - long-press OK: a card's actions, or editing the app row in place; All apps is a plain text grid.
 * Measurements are in a 1920 × 1080 space (k = width / 1920). Nothing here uses live blur: the frosted bar reads a
 * pre-blurred copy of the landscape, so the TV's GPU only draws pictures and text.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, HomeArt.Listener {
    // Layout (design px)
    private static final float SAFE = 96, RIGHT = 1824;
    private static final float CHIP_TOP = 534, CHIP_H = 72;
    private static final float LOGO_CY = 984, LOGO_GAP = 76;
    private static final float HERO_TOP = 222;
    private static final float ROW_TOP = 618, CARDS_TOP = 665, CARD_W = 320, CARD_H = 180, CARD_GAP = 28, SECTION_GAP = 44;
    private static final float ICON = 56, ICON_GAP = 8, ICONS_TOP = 58;
    private static final float KEYS_TOP = 224, KEYS_H = 472, KEYS_W = 480, RESULTS_LEFT = 672;
    private static final float RESULTS_TOP = 208, RESULT_W = 352, RESULT_H = 198, RESULT_GAP = 28;
    private static final RectF PILL_REST = new RectF(480, 404, 1440, 500), PILL_CONTENT = new RectF(760, 56, 1160, 116),
            PILL_SEARCH = new RectF(480, 52, 1440, 140);
    // States and focus
    private Bitmap setupCode, phoneCode;
    private String interruptedShown = "";
    private final PhoneLink.Listener phoneListener = new PhoneLink.Listener() {
        public void onText(String text) { if (running) voiceResult(text); }
        public void onOpen(String id) { TitleSearch.Title t = PhoneLink.result(id); if (running && t != null) openTitle(t.id, t.kind, t.name, ""); }
        public void onSetup() {
            if (!running) return;
            reset(true);
            refreshPhoneCodes();
            data.refreshIfStale();
            recs.refreshIfStale();
        }
    };
    private static final int REST = 0, CONTENT = 1, SEARCH = 2;
    static final int Z_ICONS = 0, Z_PILL = 1, Z_CHIP = 2, Z_LOGOS = 3, Z_HERO = 4, Z_ROW = 5, Z_KEYS = 6, Z_RESULTS = 7,
            Z_RECENT = 9, Z_SETUP = 10, Z_NEWS = 11;
    private static final int OV_NONE = 0, OV_MENU = 1, OV_ALL = 2;
    private static final int CW = 0, PICK = 1, RELEASE = 2;
    private static final int ROW_MAX = 24, ROW_RESULTS = 8;
    private static final long TOAST_MS = 5000, FADE_MS = 500, HERO_DELAY = 250;
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2), WHERE = Executors.newFixedThreadPool(4);
    private static final PorterDuffColorFilter INK_TINT = new PorterDuffColorFilter(Look.INK, PorterDuff.Mode.SRC_IN),
            DAY_TINT = new PorterDuffColorFilter(Look.DAY_INK, PorterDuff.Mode.SRC_IN),
            ON_INK_TINT = new PorterDuffColorFilter(Look.ON_INK, PorterDuff.Mode.SRC_IN);

    // Icons (24-unit line drawings, from the boards)
    private static final Path SEARCH_ICON = SvgPath.parse("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0 M20 20l-3.5-3.5");
    private static final Path MIC_ICON = SvgPath.parse("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1 -6 0z M5 11a7 7 0 0 0 14 0M12 18v3");
    private static final Path INPUTS_ICON = SvgPath.parse("M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V7a2 2 0 0 1 2 -2z M8 21h8M12 17v4");
    private static final Path APPS_ICON = SvgPath.parse("M5.5 4h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z"
            + "M15.5 4h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z"
            + "M5.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z"
            + "M15.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1 -1.5 1.5h-3a1.5 1.5 0 0 1 -1.5 -1.5v-3a1.5 1.5 0 0 1 1.5 -1.5z");
    private static final Path SETTINGS_ICON = SvgPath.parse("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 "
            + "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1"
            + "a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3"
            + "a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9"
            + "a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1"
            + "a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z");
    private static final Path PLAY_ICON = SvgPath.parse("M7 4.5v15l13-7.5z");
    private static final Path SHOW_ICON = SvgPath.parse("M4 6h16v12H4z M4 10h16");
    private static final Path CHECK_ICON = SvgPath.parse("M5 12.5l4.5 4.5L19 7.5");
    private static final Path CLOSE_ICON = SvgPath.parse("M6 6l12 12M18 6L6 18");
    private static final Path PLUS_ICON = SvgPath.parse("M12 5v14M5 12h14");
    private static final Path OFFLINE_ICON = SvgPath.parse("M3 3l18 18 M8.5 16.4a5 5 0 0 1 7 0 M5 12.9a10 10 0 0 1 5.2-2.8 M19 12.9a10 10 0 0 0 -2.6-1.9 "
            + "M2 8.8a15 15 0 0 1 4.2-2.6 M22 8.8a15 15 0 0 0 -11.4-3.7 M12 20h.01");

    /** One card of the content row. */
    private static final class Card {
        int kind;
        String key = "", section = "", title = "", sub = "", kicker = "", meta = "", left = "", primary = "", secondary = "";
        float progress = -1;
        boolean sectionStart;
        float x;                        // left edge before scrolling (design px)
        String titleFit, subFit;
        ContinueWatching.Entry entry;
        Recs.Pick pick;
        HomeArt.Source source;
    }

    /** One search result: a title and where it plays. */
    private static final class Result {
        TitleSearch.Title title;
        String key = "", where = "", pkg = "";
        String titleFit, whereFit;
        HomeArt.Source source;
        double score;
        final Spring focus = new Spring(0.28f, 0.86f, 0);
    }

    /** A row of search results: shows, movies or apps. */
    private static final class Section {
        final String label;
        final boolean apps;
        final List<Result> titles = new ArrayList<>();
        final List<HomeApps.Item> items = new ArrayList<>();
        float top;      // design px, before scrolling
        final Spring scroll = new Spring(0.42f, 1f, 0);

        Section(String label, boolean apps) {
            this.label = label;
            this.apps = apps;
        }

        int size() { return apps ? items.size() : titles.size(); }

        float height() { return apps ? 200 : 380; }
    }

    /** One line of a menu (a card's actions, the inputs). */
    private static final class MenuItem {
        final String label;
        final Path icon;
        final Runnable action;
        final boolean dim;

        MenuItem(String label, Path icon, boolean dim, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.dim = dim;
            this.action = action;
        }
    }

    /** A layer of the screen; each redraws only when its part changes. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            if (clockP == null) return;
            if (kind == 0) drawGround(c);
            else if (kind == 1) drawChrome(c);
            else drawOverlay(c);
        }
    }

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final HomeArt art;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final ConnectivityManager connectivity;
    private final Layer ground, chrome, overlay;
    private final KeyRow keys;
    private final boolean voice;
    private Opening opening;

    // State and focus
    private int mode = REST;
    int zone = Z_PILL;
    private int iconIndex, heroIndex, rowIndex, logoIndex, resultIndex, recentIndex;
    private int overlayKind = OV_NONE;
    private int watchGeneration;
    private final Spring contentT = new Spring(0.5f, 1f, 0), searchT = new Spring(0.45f, 1f, 0), overlayT = new Spring(0.36f, 1f, 0);
    private final Spring rowScroll = new Spring(0.42f, 1f, 0), allScroll = new Spring(0.4f, 1f, 0);
    private Spring[] rowFocus = new Spring[0];
    private boolean editing, okDown, backDown, online = true;

    // Content
    final List<Card> row = new ArrayList<>();
    private List<ContinueWatching.Entry> entries = new ArrayList<>();
    private List<NewEpisodes.Notice> episodeNotices = new ArrayList<>();
    private List<NewEpisodes.Notice> waitingSeries = new ArrayList<>();
    private volatile boolean noticeVisible;
    List<HomeApps.Item> shelf = new ArrayList<>();
    private List<HomeApps.Item> others = new ArrayList<>(), inputs = new ArrayList<>();
    private float[] logoW = new float[0], logoH = new float[0], logoX = new float[0];
    private final java.util.Map<String, Bitmap> whiteLogos = new java.util.HashMap<>();

    // Search
    private String query = "";
    private final List<Result> results = new ArrayList<>();
    private final List<HomeApps.Item> appResults = new ArrayList<>();
    private final List<String> recent = new ArrayList<>();
    private final List<String> recentFits = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private int sectionIndex;
    private final Spring searchScroll = new Spring(0.42f, 1f, 0);
    private int searchToken;
    private boolean searching;
    private String searchError = "";
    private final Runnable runSearch = this::search;

    // Overlays
    private final List<MenuItem> menu = new ArrayList<>();
    private int menuIndex;
    private Card menuCard;
    private List<HomeApps.Item> allApps = new ArrayList<>();
    private int allIndex;
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private String toast;
    private long toastAt;

    // Pictures
    private Landscape land, landFrom;
    private long landFadeAt;
    private HomeArt.Hero heroFrom, heroTo;
    private String heroKey = "";
    private long heroFadeAt;
    private final Runnable heroLoad = this::loadHero;

    // Frames
    private boolean running, frameScheduled;
    private long lastNanos;
    long frameCount;
    private final Runnable idleFrame = this::wakeFrames;
    private float k = 1;
    private int w, h;
    private String clockText = "", weatherText = "";
    private long clockMinute = -1;

    private Paint clockP, weatherP, pillP, pillSmallP, queryP, chipP, chipStrongP, kickerP, metaP, buttonP, button2P, sectionP;
    private Paint cardTitleP, cardSubP, resultTitleP, whereP, recentP, logoNameP, hintP, kbdP, allTitleP, allItemP, allItemOnP;
    private Paint editP, menuItemP, menuItemOnP, bigTitleP, bigSubP, setupTitleP, setupBodyP, setupButtonP, offlineP, offlineLineP;
    private Paint toastP, labelP;
    private Paint glanceHeadP, glanceBodyP, glanceTempP, glanceValueP;
    private boolean inputsReady, pendingInputs, pendingAppEdit;
    private ResumePrompt resumePrompt;
    private TextPaint heroTitleP;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), scrim = new Paint(Paint.DITHER_FLAG);
    private Shader topScrim, bottomScrim, dayTop, dayBottom, heroLeft, heroBottom, heroTop, offlineGlow;
    private final RectF r = new RectF(), r2 = new RectF(), screen = new RectF();
    private final Path clip = new Path();
    private final Matrix matrix = new Matrix();
    private final android.graphics.Rect src = new android.graphics.Rect();
    private String heroTitleFit = "";
    private Card heroTitleFor;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            refreshRow();
            checkNotices();
            checkLandscape(true);
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable longPress = () -> {
        if (!okDown || !running) return;
        okDown = false;
        onLongPress();
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { handler.post(() -> setOnline(true)); }
        @Override public void onLost(Network n) { handler.post(() -> setOnline(hasNetwork())); }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        art = new HomeArt(a, this);
        click = new Click(a);
        ui = Prefs.get(a, Prefs.HOME_UI);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        connectivity = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(a.getPackageManager()) != null;
        setFocusable(true);
        setClipChildren(false);
        ground = new Layer(a, 0);
        chrome = new Layer(a, 1);
        overlay = new Layer(a, 2);
        keys = new KeyRow(a, voice, new KeyRow.Listener() {
            @Override public void type(String text) { setQuery(query + text); }
            @Override public void delete() { if (!query.isEmpty()) setQuery(query.substring(0, query.offsetByCodePoints(query.length(), -1))); }
            @Override public void clear() { setQuery(""); }
            @Override public void voice() { startVoice(); }
        });
        keys.setFocusable(false);
        addView(ground);
        addView(chrome);
        addView(keys);
        addView(overlay);
        keys.setAlpha(0);
        keys.setVisibility(INVISIBLE);
        overlay.setVisibility(INVISIBLE);
        loadRecent();
        entries = ContinueWatching.cached(a);
        List<ContinueWatching.Entry> followed = NewEpisodes.followed(a, entries);
        episodeNotices = NewEpisodes.cached(a, followed, System.currentTimeMillis());
        waitingSeries = NewEpisodes.retained(a, followed, System.currentTimeMillis());
        buildRow();
        online = hasNetwork();
        zone = restZone();
        if (Opening.due(a)) playOpening();
    }

    /** Flora's opening, over the home screen (also from Settings → About). */
    void playOpening() {
        if (opening != null) return;
        opening = new Opening(getContext(), () -> {
            removeView(opening);
            opening = null;
        });
        addView(opening);
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        PhoneLink.setTypingListener(phoneListener);
        TvSync.start(getContext());
        refreshPhoneCodes();
        ContinueWatching.Entry interrupted = PlaybackSession.interrupted(getContext());
        if (interrupted != null && !interrupted.video.equals(interruptedShown)) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!running || interrupted.video.equals(interruptedShown)) return;
                    if (opening != null) { handler.postDelayed(this, 250); return; }
                    interruptedShown = interrupted.video;
                    resumePrompt = new ResumePrompt(activity, interrupted,
                            () -> { PlaybackSession.clearInterrupted(getContext()); resume(interrupted); },
                            () -> PlaybackSession.clearInterrupted(getContext()));
                }
            }, 400);
        }
        lastNanos = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
        }
        setOnline(hasNetwork());
        handler.postDelayed(tick, 15_000);
        checkLandscape(false);
        checkNotices();
        reload();
        refreshRow();
        loadRecent();
        updateClock();
        invalidateAll();
        wakeFrames();
    }

    void pause() {
        noticeVisible = false;
        if (resumePrompt != null) { resumePrompt.dialog.dismiss(); resumePrompt = null; }
        if (!running) return;
        running = false;
        PhoneLink.setTypingListener(null);
        okDown = false;
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(idleFrame);
        handler.removeCallbacks(heroLoad);
        Choreographer.getInstance().removeFrameCallback(this);
        frameScheduled = false;
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        try {
            connectivity.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
        }
        if (editing) finishEditing();
        art.trim();
        heroFrom = heroTo = null;
        heroKey = "";
        TitleArtwork.trimMemory();
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Release hidden artwork before the player allocates video buffers. */
    void releaseHiddenArtwork() {
        land = landFrom = null;
        heroFrom = heroTo = null;
        heroKey = "";
        whiteLogos.clear();
        for (HomeApps.Item item : shelf) { item.tile = item.logo = item.logoDark = null; }
        for (HomeApps.Item item : allApps) { item.tile = item.logo = item.logoDark = null; }
        for (HomeApps.Item item : others) { item.tile = item.logo = item.logoDark = null; }
        for (HomeApps.Item item : inputs) { item.tile = item.logo = item.logoDark = null; }
        art.trim();
        recs.trimArtwork();
        HomeApps.trimArtwork();
        TitleArtwork.trimMemory();
    }

    /** Home pressed on the home screen: back to rest, search focused. */
    void reset(boolean animated) {
        if (opening != null) opening.skip();
        closeOverlay();
        if (editing) finishEditing();
        if (mode == SEARCH) setQuery("");
        setMode(REST);
        zone = restZone();
        rowIndex = 0;
        rowScroll.snap(0);
        if (!animated) {
            contentT.snap(0);
            searchT.snap(0);
        }
        changed();
    }

    private int restZone() {
        if (!online) return shelf.isEmpty() ? Z_ICONS : Z_LOGOS;
        return Setup.hasKey() ? Z_PILL : Z_SETUP;
    }

    // MARK: - Data

    @Override public void onData() {
        updateWeather();
        checkLandscape(true);
        changed();
    }

    @Override public void onRecs() {
        buildRow();
        changed();
    }

    @Override public void onArt() {
        if (!running) return;
        String key = heroKey;
        if (!key.isEmpty()) loadHero();
        chrome.invalidate();
        overlay.invalidate();
    }

    /** Apps, inputs and the All apps list (in the background, so returning home isn't delayed). */
    private void reload() {
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            handler.post(() -> { if (running && !editing) { setShelf(s); changed(); } });
            final List<HomeApps.Item> in = HomeApps.allInputs(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                if (!running) { HomeApps.trimArtwork(); return; }
                if (!editing) setShelf(s);
                inputs = in;
                inputsReady = true;
                others = o;
                if (overlayKind != OV_ALL) allApps = allAppsList();
                changed();
                if (pendingInputs) { pendingInputs = false; openInputs(); }
                if (pendingAppEdit) { pendingAppEdit = false; openAppEditor(); }
            });
        });
    }

    private void refreshRow() {
        final int generation = ++watchGeneration;
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            final List<ContinueWatching.Entry> loaded = ContinueWatching.refresh(c);
            final List<ContinueWatching.Entry> followed = NewEpisodes.followed(c, loaded);
            final List<NewEpisodes.Notice> notices = NewEpisodes.cached(c, followed, System.currentTimeMillis());
            final List<NewEpisodes.Notice> waiting = NewEpisodes.retained(c, followed, System.currentTimeMillis());
            handler.post(() -> {
                if (!running || generation != watchGeneration) return;
                entries = loaded;
                episodeNotices = notices;
                waitingSeries = waiting;
                buildRow();
                changed();
                noticeVisible = online;
                if (online) NewEpisodes.refresh(c, followed, () -> noticeVisible, () -> handler.post(() -> {
                    if (!running || generation != watchGeneration) return;
                    episodeNotices = NewEpisodes.cached(c, followed, System.currentTimeMillis());
                    waitingSeries = NewEpisodes.retained(c, followed, System.currentTimeMillis());
                    buildRow(); changed();
                }));
            });
        });
    }

    private void setShelf(List<HomeApps.Item> list) {
        String focused = logoIndex < shelf.size() ? shelf.get(logoIndex).key : null;
        shelf = list;
        logoIndex = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).key.equals(focused)) logoIndex = i;
        whiteLogos.clear();
        layoutLogos();
        if (zone == Z_LOGOS && shelf.isEmpty()) zone = Z_PILL;
    }

    /** Continue Watching, then what's trending on the TV's apps; the focused card stays focused. */
    private void buildRow() {
        int previousIndex = rowIndex;
        String focused = rowIndex < row.size() ? row.get(rowIndex).key : null;
        row.clear();
        String cw = Ui.tr("Continuar assistindo", "Continue Watching");
        for (ContinueWatching.Entry e : entries) {
            if (row.size() >= 12) break;
            row.add(cwCard(e, cw));
        }
        for (NewEpisodes.Notice notice : episodeNotices) {
            Card card = cwCard(notice.entry, Ui.tr("Novos episódios", "New episodes"));
            card.kind = RELEASE; card.key = "release:" + notice.key();
            card.kicker = Ui.tr("Novo episódio", "New episode"); card.left = "";
            card.sub = card.meta; card.progress = -1;
            card.primary = card.secondary = Ui.tr("Ver episódio", "View episode");
            row.add(card);
        }
        for (NewEpisodes.Notice notice : waitingSeries) {
            boolean announced = false;
            for (NewEpisodes.Notice fresh : episodeNotices) if (fresh.key().equals(notice.key())) announced = true;
            if (announced) continue;
            Card card = cwCard(notice.entry, Ui.tr("Suas séries", "Your series"));
            card.kind = RELEASE; card.key = "waiting:" + notice.entry.imdb;
            card.kicker = notice.waiting ? Ui.tr("Aguardando o próximo episódio", "Waiting for the next episode") : Ui.tr("A seguir", "Up next");
            card.meta = card.sub = !notice.waiting ? notice.entry.code() : notice.released > 0 ? Ui.tr("Próximo episódio: ", "Next episode: ")
                    + android.text.format.DateFormat.getMediumDateFormat(getContext()).format(new java.util.Date(notice.released)) : "";
            card.left = ""; card.progress = -1;
            card.primary = card.secondary = notice.waiting ? Ui.tr("Ver série", "View series") : Ui.tr("Ver episódio", "View episode"); row.add(card);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ContinueWatching.Entry e : entries) seen.add(ContinueWatching.showKey(e));
        for (NewEpisodes.Notice notice : episodeNotices) seen.add(ContinueWatching.showKey(notice.entry));
        for (NewEpisodes.Notice notice : waitingSeries) seen.add(ContinueWatching.showKey(notice.entry));
        for (ContinueWatching.Entry e : ContinueWatching.watchlist(getContext())) {
            if (row.size() >= 18) break;
            if (seen.add(ContinueWatching.showKey(e))) row.add(cwCard(e, Ui.tr("Minha lista", "Watchlist")));
        }
        String trending = Ui.tr("Em alta nos seus apps", "Trending on your apps");
        if (online) for (Recs.Pick p : recs.trending(ROW_MAX - row.size())) row.add(pickCard(p, trending));
        float x = SAFE;
        String section = null;
        for (Card c : row) {
            if (section != null && !section.equals(c.section)) x += SECTION_GAP - CARD_GAP;
            c.sectionStart = !c.section.equals(section);
            section = c.section;
            c.x = x;
            x += CARD_W + CARD_GAP;
            fitCard(c);
        }
        if (rowFocus.length != row.size()) {
            rowFocus = new Spring[row.size()];
            for (int i = 0; i < rowFocus.length; i++) rowFocus[i] = new Spring(0.28f, 0.86f, 0);
        }
        rowIndex = Math.min(previousIndex, Math.max(0, row.size() - 1));
        for (int i = 0; i < row.size(); i++) if (row.get(i).key.equals(focused)) rowIndex = i;
        if (row.isEmpty() && (zone == Z_ROW || zone == Z_HERO)) {
            zone = shelf.isEmpty() ? Z_PILL : Z_LOGOS;
            setMode(REST);
        }
        if (zone == Z_CHIP && chipEntry() == null) zone = Z_PILL;
        if (zone == Z_NEWS && episodeNotices.isEmpty()) zone = Z_PILL;
        aimRow(true);
        heroTitleFor = null;
        scheduleHero();
    }

    private Card cwCard(ContinueWatching.Entry e, String section) {
        Card c = new Card();
        c.kind = CW;
        c.entry = e;
        c.key = "cw:" + e.identity();
        c.section = section;
        c.title = e.title;
        String code = e.episode > 0 ? "S" + e.season + " E" + e.episode : "";
        long left = e.duration > e.position ? (e.duration - e.position + 59_999) / 60_000 : 0;
        c.left = left > 0 ? left + Ui.tr(" min restantes", " min left") : "";
        c.meta = code;
        c.sub = join(code, c.left);
        c.progress = e.duration > 0 ? Math.min(1, (float) e.position / e.duration) : -1;
        c.kicker = Ui.tr("Continuar", "Continue") + " · " + e.provider;
        c.primary = e.external() ? Ui.tr("Continuar em ", "Resume on ") + e.provider : e.position > 0 ? Ui.tr("Continuar", "Resume") : Ui.tr("Reproduzir", "Play");
        c.secondary = "tv".equals(e.kind) ? Ui.tr("Episódios", "Episodes") : Ui.tr("Detalhes", "Details");
        c.source = () -> {
            if (!e.external() && "Stremio".equals(e.provider)) try { ContinueWatching.resolve(e); } catch (Exception ignored) {}
            return e.art;
        };
        return c;
    }

    private Card pickCard(Recs.Pick p, String section) {
        Card c = new Card();
        c.kind = PICK;
        c.pick = p;
        c.key = "pick:" + Recs.key(p);
        c.section = section;
        c.title = p.title == null ? "" : p.title;
        String service = Recs.serviceName(p);
        c.kicker = Ui.tr("Em alta", "Trending") + (service.isEmpty() ? "" : " · " + service);
        c.meta = p.meta == null ? "" : p.meta;
        c.sub = join("tv".equals(p.kind) ? Ui.tr("Série", "Series") : Ui.tr("Filme", "Movie"), service);
        c.primary = service.isEmpty() ? Ui.tr("Detalhes", "Details") : Ui.tr("Assistir em ", "Watch on ") + service;
        c.secondary = "tv".equals(p.kind) ? Ui.tr("Episódios", "Episodes") : Ui.tr("Detalhes", "Details");
        final String backdrop = p.backdrop;
        c.source = () -> backdrop;
        return c;
    }

    private void fitCard(Card c) {
        if (cardTitleP == null) return;
        float width = CARD_W * k;
        c.titleFit = TextUtils.ellipsize(c.title, new TextPaint(cardTitleP), width, TextUtils.TruncateAt.END).toString();
        c.subFit = TextUtils.ellipsize(c.sub, new TextPaint(cardSubP), width, TextUtils.TruncateAt.END).toString();
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " · " + b;
    }

    /** The entry the Resume chip offers (the most recent one), or null. */
    private ContinueWatching.Entry chipEntry() {
        return online && !entries.isEmpty() ? entries.get(0) : null;
    }

    // MARK: - Logos

    /** Each logo at the same optical weight (thin logos larger), centered as a row, never wider than the safe area. */
    private void layoutLogos() {
        int n = shelf.size();
        logoW = new float[n];
        logoH = new float[n];
        logoX = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            HomeApps.Item it = shelf.get(i);
            if (it.logo != null) {
                float aspect = (float) it.logo.getWidth() / Math.max(1, it.logo.getHeight());
                float ink = Math.max(0.15f, Math.min(1, it.logoInk));
                float hh = (float) Math.sqrt(1100f * it.logoScale * it.logoScale / (aspect * ink));
                hh = Math.max(18, Math.min(42, hh));
                float ww = hh * aspect;
                if (ww > 150) { ww = 150; hh = ww / aspect; }
                logoW[i] = ww;
                logoH[i] = hh;
            } else if (logoNameP != null) {
                logoW[i] = logoNameP.measureText(it.label) / k;
                logoH[i] = 26;
            }
            total += logoW[i];
        }
        float gap = LOGO_GAP;
        float room = RIGHT - SAFE;
        if (n > 1 && total + gap * (n - 1) > room) gap = Math.max(32, (room - total) / (n - 1));
        float scale = n == 0 ? 1 : Math.min(1, room / (total + gap * Math.max(0, n - 1)));
        float x = 960 - (total * scale + gap * scale * Math.max(0, n - 1)) / 2;
        for (int i = 0; i < n; i++) {
            logoW[i] *= scale;
            logoH[i] *= scale;
            logoX[i] = x;
            x += logoW[i] + gap * scale;
        }
    }

    private Bitmap whiteLogo(HomeApps.Item it) {
        Bitmap b = whiteLogos.get(it.key);
        if (b != null || it.logo == null) return b;
        int index = shelf.indexOf(it);
        float scale = 1.12f * k;
        int ww = Math.max(1, Math.round(logoW[index] * scale)), hh = Math.max(1, Math.round(logoH[index] * scale));
        Bitmap small = Bitmap.createScaledBitmap(it.logo, ww, hh, true);
        whiteLogos.put(it.key, small);
        return small;
    }

    // MARK: - Size

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == keys) {
                v.measure(MeasureSpec.makeMeasureSpec(Math.round(KEYS_W * width / 1920f), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(KEYS_H * width / 1920f), MeasureSpec.EXACTLY));
            } else {
                v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int right, int b) {
        int width = right - l, height = b - t;
        float kk = width / 1920f;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == keys) {
                int x = Math.round(SAFE * kk), y = Math.round(KEYS_TOP * kk);
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            } else {
                v.layout(0, 0, width, height);
            }
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        makePaints();
        fitRecent();
        makeShaders();
        layoutLogos();
        whiteLogos.clear();
        for (Card c : row) fitCard(c);
        for (Result res : results) fitResult(res);
        clockMinute = -1;
        updateClock();
        updateWeather();
        heroTitleFor = null;
    }

    private Paint text(android.graphics.Typeface face, float size, int color) {
        return Ui.text(face, size * k, color);
    }

    private void makePaints() {
        glanceHeadP = text(Ui.medium, 22, Look.INK_3);
        glanceBodyP = text(Ui.regular, 28, Look.INK_2);
        glanceTempP = text(Ui.light, 104, Look.INK);
        glanceValueP = text(Ui.medium, 30, Look.INK);
        clockP = text(Ui.medium, 40, Look.INK);
        clockP.setLetterSpacing(-0.01f);
        weatherP = text(Ui.regular, 26, Look.INK);
        pillP = text(Ui.regular, 34, Look.INK);
        pillSmallP = text(Ui.regular, 24, Look.INK);
        queryP = text(Ui.semibold, 40, Look.INK);
        queryP.setLetterSpacing(-0.02f);
        chipP = text(Ui.regular, 24, Look.INK);
        chipStrongP = text(Ui.medium, 24, Look.INK);
        kickerP = text(Ui.medium, 24, Ui.alpha(Look.INK, 0.72f));
        heroTitleP = new TextPaint(text(Ui.displaySemibold, 84, Look.INK));
        heroTitleP.setLetterSpacing(-0.03f);
        metaP = text(Ui.regular, 26, Ui.alpha(Look.INK, 0.8f));
        buttonP = text(Ui.semibold, 26, Look.INK);
        button2P = text(Ui.medium, 26, Look.INK);
        sectionP = text(Ui.medium, 24, Ui.alpha(Look.INK, 0.72f));
        cardTitleP = text(Ui.medium, 22, Look.INK);
        cardSubP = text(Ui.regular, 22, Look.INK);
        resultTitleP = text(Ui.semibold, 26, Look.INK);
        whereP = text(Ui.regular, 22, Ui.alpha(Look.INK, 0.68f));
        recentP = text(Ui.regular, 24, Look.INK);
        logoNameP = text(Ui.semibold, 26, Look.INK);
        labelP = text(Ui.medium, 24, Ui.alpha(Look.INK, 0.66f));
        hintP = text(Ui.regular, 24, Ui.alpha(Look.INK, 0.85f));
        kbdP = text(Ui.semibold, 20, Ui.alpha(Look.INK, 0.85f));
        allTitleP = text(Ui.displaySemibold, 56, Look.INK);
        allTitleP.setLetterSpacing(-0.025f);
        allItemP = text(Ui.regular, 36, Look.INK);
        allItemP.setLetterSpacing(-0.015f);
        allItemOnP = text(Ui.semibold, 36, Look.ON_INK);
        allItemOnP.setLetterSpacing(-0.015f);
        editP = text(Ui.medium, 24, Look.INK);
        menuItemP = text(Ui.regular, 28, Look.INK);
        menuItemOnP = text(Ui.semibold, 28, Look.ON_INK);
        bigTitleP = text(Ui.semibold, 32, Look.INK);
        bigSubP = text(Ui.regular, 24, Ui.alpha(Look.INK, 0.7f));
        setupTitleP = text(Ui.semibold, 36, Look.INK);
        setupTitleP.setLetterSpacing(-0.02f);
        setupBodyP = text(Ui.regular, 26, Ui.alpha(Look.INK, 0.72f));
        setupButtonP = text(Ui.semibold, 26, Look.INK);
        offlineP = text(Ui.regular, 34, Ui.alpha(Look.INK, 0.7f));
        offlineLineP = text(Ui.regular, 26, Look.INK);
        offlineLineP.setTextAlign(Paint.Align.CENTER);
        toastP = text(Ui.medium, 26, Look.INK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        shadow.setMaskFilter(new BlurMaskFilter(40 * k, BlurMaskFilter.Blur.NORMAL));
    }

    private void makeShaders() {
        int ground = 0xFF0C0A0A;
        topScrim = new LinearGradient(0, 0, 0, h, new int[] {Ui.alpha(ground, 0.35f), 0, 0, Ui.alpha(ground, 0.55f)},
                new float[] {0, 0.24f, 0.6f, 1}, Shader.TileMode.CLAMP);
        dayTop = new LinearGradient(0, 0, 0, h * 0.26f, new int[] {Ui.alpha(Look.DAY_GROUND, 0.62f), Ui.alpha(Look.DAY_GROUND, 0.3f), 0},
                new float[] {0, 0.46f, 1}, Shader.TileMode.CLAMP);
        dayBottom = new LinearGradient(0, h, 0, h * 0.58f, new int[] {Ui.alpha(Look.DAY_GROUND, 0.94f), Ui.alpha(Look.DAY_GROUND, 0.8f),
                Ui.alpha(Look.DAY_GROUND, 0.36f), 0}, new float[] {0, 0.29f, 0.62f, 1}, Shader.TileMode.CLAMP);
        int hero = 0xFF08090B;
        heroLeft = new LinearGradient(0, 0, w, 0, new int[] {Ui.alpha(hero, 0.88f), Ui.alpha(hero, 0.5f), 0},
                new float[] {0, 0.38f, 0.68f}, Shader.TileMode.CLAMP);
        heroBottom = new LinearGradient(0, h, 0, 0, new int[] {Ui.alpha(hero, 0.96f), Ui.alpha(hero, 0.6f), 0},
                new float[] {0, 0.34f, 0.58f}, Shader.TileMode.CLAMP);
        heroTop = new LinearGradient(0, 0, 0, h * 0.2f, Ui.alpha(hero, 0.5f), 0, Shader.TileMode.CLAMP);
        bottomScrim = null;
        offlineGlow = new RadialGradient(1060 * k, 550 * k, 900 * k, new int[] {0x52100B09, 0x38100B09, 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
    }

    // MARK: - Frames: 60 per second while something moves; otherwise once a minute for the clock

    private void wakeFrames() {
        if (!running || frameScheduled) return;
        handler.removeCallbacks(idleFrame);
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Something changed: redraw everything once. */
    private void changed() {
        invalidateAll();
        wakeFrames();
    }

    private void invalidateAll() {
        ground.invalidate();
        chrome.invalidate();
        overlay.invalidate();
        keys.invalidate();
    }

    @Override public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (!running) return;
        frameCount++;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();
        boolean states = contentT.step(dt) | searchT.step(dt);
        boolean over = overlayT.step(dt) | allScroll.step(dt);
        boolean scroll = rowScroll.step(dt);
        boolean cards = false;
        for (Spring s : rowFocus) cards |= s.step(dt);
        for (Result res : results) cards |= res.focus.step(dt);
        cards |= searchScroll.step(dt);
        for (Section sec : sections) cards |= sec.scroll.step(dt);
        boolean heroFade = heroFadeAt != 0 && up - heroFadeAt < FADE_MS + 32;
        if (!heroFade && heroFadeAt != 0) { heroFadeAt = 0; heroFrom = null; ground.invalidate(); }
        boolean landFade = landFadeAt != 0 && up - landFadeAt < 2000 + 32;
        if (!landFade && landFadeAt != 0) { landFadeAt = 0; landFrom = null; ground.invalidate(); }
        boolean toastMoving = tickToast(up);
        if (states || heroFade || landFade || over) ground.invalidate();
        if (states || scroll || cards) chrome.invalidate();
        if (over || toastMoving) overlay.invalidate();
        if (states) {
            float sa = (float) searchT.value;
            keys.setAlpha(Ui.clamp(sa));
            keys.setVisibility(sa > 0.01f ? VISIBLE : INVISIBLE);
        }
        overlay.setVisibility(overlayT.value > 0.002f || overlayKind != OV_NONE || toast != null ? VISIBLE : INVISIBLE);
        if (updateClock()) chrome.invalidate();
        boolean busy = states || over || scroll || cards || heroFade || landFade || toastMoving;
        if (busy) {
            wakeFrames();
        } else {
            lastNanos = 0;
            long delay = 60_000 - Math.floorMod(System.currentTimeMillis(), 60_000);
            if (toast != null) delay = Math.min(delay, Math.max(16, toastAt + TOAST_MS - up));
            handler.postDelayed(idleFrame, delay);
        }
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updateWeather() {
        HomeData.Weather wx = data.weather;
        String city = Setup.city();
        weatherText = Double.isNaN(wx.temp) ? city : join(HomeData.degrees(wx.temp), city).replace(" · ", " ");
    }

    // MARK: - Landscape and backdrops

    private boolean landLoading;

    /** The landscape for now; a change (sunset) cross-fades over 2 s. Decoded in the background. */
    private void checkLandscape(boolean animate) {
        final int want = Landscape.now(getContext(), data.weather, System.currentTimeMillis());
        if ((land != null && land.kind == want) || landLoading) return;
        landLoading = true;
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            final Landscape next = Landscape.load(c, want);
            handler.post(() -> {
                landLoading = false;
                if (next == null || !running) return;
                if (animate && land != null && running) {
                    landFrom = land;
                    landFadeAt = SystemClock.uptimeMillis();
                }
                land = next;
                changed();
            });
        });
    }

    private boolean day() {
        return land != null && land.day();
    }

    /** The focused card's backdrop, after the focus has rested for a moment (moving fast doesn't decode pictures). */
    private void scheduleHero() {
        handler.removeCallbacks(heroLoad);
        handler.postDelayed(heroLoad, HERO_DELAY);
    }

    private void loadHero() {
        if (row.isEmpty() || !running) return;
        Card c = row.get(Math.min(rowIndex, row.size() - 1));
        HomeArt.Hero hero = art.hero(c.key, c.source);
        if (!c.key.equals(heroKey)) {
            heroKey = c.key;
            if (hero == null) return;      // it arrives later (onArt)
        }
        if (hero == null || hero == heroTo) return;
        heroFrom = heroTo;
        heroTo = hero;
        heroFadeAt = SystemClock.uptimeMillis();
        ground.invalidate();
        wakeFrames();
    }

    // MARK: - Drawing: ground (landscape, blurred landscape, backdrops)

    private void drawGround(Canvas c) {
        float ct = Ui.clamp(contentT.value), st = Ui.clamp(searchT.value);
        float dim = st;
        c.drawColor(Look.GROUND);
        float restAlpha = 1 - ct;
        if (restAlpha > 0.004f) {
            long up = SystemClock.uptimeMillis();
            float fade = landFadeAt == 0 ? 1 : Ui.clamp((up - landFadeAt) / 2000f);
            if (landFrom != null && fade < 1) drawLandscape(c, landFrom, restAlpha);
            if (land != null) drawLandscape(c, land, restAlpha * (landFrom != null ? fade : 1));
            if (!online) {
                fill.setColor(Ui.alpha(0xFF000000, 0.3f * restAlpha));
                c.drawRect(screen, fill);
                scrim.setShader(offlineGlow);
                scrim.setAlpha(Math.round(255 * restAlpha));
                c.drawRect(screen, scrim);
                scrim.setShader(null);
                scrim.setAlpha(255);
            }
        }
        if (editing) {
            fill.setColor(0x66000000);
            c.drawRect(screen, fill);
        }
        if (ct > 0.004f) drawBackdrop(c, ct);
        if (dim > 0.004f) drawDim(c, dim);
    }

    /** Search, All apps and menus sit on the landscape blurred and darkened (brightness 0.4). */
    private void drawDim(Canvas c, float alpha) {
        if (land != null) {
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(land.blurred, null, screen, image);
            image.setAlpha(255);
        }
        fill.setColor(Ui.alpha(0xFF000000, (land == null ? 0.9f : land.day() ? 0.7f : 0.6f) * alpha));
        c.drawRect(screen, fill);
    }

    private void drawLandscape(Canvas c, Landscape l, float alpha) {
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(l.image, null, screen, image);
        image.setAlpha(255);
        if (l.day()) {
            scrim.setAlpha(Math.round(255 * alpha));
            scrim.setShader(dayTop);
            c.drawRect(0, 0, w, h * 0.26f, scrim);
            scrim.setShader(dayBottom);
            c.drawRect(0, h * 0.58f, w, h, scrim);
        } else {
            scrim.setShader(topScrim);
            scrim.setAlpha(Math.round(255 * alpha));
            c.drawRect(screen, scrim);
        }
        scrim.setShader(null);
        scrim.setAlpha(255);
    }

    private void drawBackdrop(Canvas c, float alpha) {
        fill.setColor(Ui.alpha(0xFF08090B, alpha));
        c.drawRect(screen, fill);
        long up = SystemClock.uptimeMillis();
        float fade = heroFadeAt == 0 ? 1 : Ui.clamp((up - heroFadeAt) / (float) FADE_MS);
        if (heroFrom != null && fade < 1) drawHeroImage(c, heroFrom, alpha);
        if (heroTo != null) drawHeroImage(c, heroTo, alpha * fade);
        scrim.setAlpha(Math.round(255 * alpha));
        scrim.setShader(heroLeft);
        c.drawRect(screen, scrim);
        scrim.setShader(heroBottom);
        c.drawRect(screen, scrim);
        scrim.setShader(heroTop);
        c.drawRect(0, 0, w, h * 0.2f, scrim);
        scrim.setShader(null);
        scrim.setAlpha(255);
    }

    private void drawHeroImage(Canvas c, HomeArt.Hero hero, float alpha) {
        Bitmap b = hero.image;
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
        r.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        if (hero.soft) r.inset(-80 * k, -80 * k);
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, null, r, image);
        image.setAlpha(255);
        if (hero.soft) {
            fill.setColor(Ui.alpha(0xFF000000, 0.4f * alpha));
            c.drawRect(screen, fill);
        }
    }

    // MARK: - Drawing: chrome (everything you focus)

    private int restInk() {
        return day() ? Look.DAY_INK : Look.INK;
    }

    /** Ink at the top of the screen: the look's ink at rest, white over content and search. */
    private int topInk() {
        return blend(restInk(), Look.INK, Ui.clamp(Math.max(contentT.value, searchT.value)));
    }

    private void drawChrome(Canvas c) {
        float ct = Ui.clamp(contentT.value), st = Ui.clamp(searchT.value);
        float restA = (1 - ct) * (1 - st), contentA = ct * (1 - st);
        int ink = topInk();
        drawClock(c, ink);
        drawIcons(c, ink);
        if (overlayKind == OV_MENU && menuCard == null) return;
        boolean setup = !Setup.hasKey();
        if (!online && mode != SEARCH) {
            drawOffline(c, restA);
        } else if (setup && mode != SEARCH) {
            drawSetup(c, restA);
        } else {
            drawPill(c, ct, st);
            if (restA > 0.004f) { drawChip(c, restA); drawEpisodeNotice(c, restA); }
        }
        if (contentA > 0.004f) {
            drawHero(c, contentA);
            drawRow(c, contentA);
        }
        if (st > 0.004f) drawSearch(c, st);
        float logosA = 1 - st;
        if (logosA > 0.004f) drawLogos(c, logosA);
    }

    private void drawClock(Canvas c, int ink) {
        clockP.setColor(ink);
        float base = (60 + 40 * 0.96f) * k;
        c.drawText(clockText, SAFE * k, base, clockP);
        if (online && Setup.hasKey() && !weatherText.isEmpty()) {
            weatherP.setColor(Ui.alpha(ink, 0.72f));
            c.drawText(weatherText, SAFE * k + clockP.measureText(clockText) + 24 * k, base, weatherP);
        }
    }

    private void iconRect(int i) {
        float x = RIGHT - ICON - (2 - i) * (ICON + ICON_GAP);
        r.set(x * k, ICONS_TOP * k, (x + ICON) * k, (ICONS_TOP + ICON) * k);
    }

    private void drawIcons(Canvas c, int ink) {
        Path[] icons = {INPUTS_ICON, APPS_ICON, SETTINGS_ICON};
        for (int i = 0; i < 3; i++) {
            iconRect(i);
            boolean on = overlayKind == OV_NONE && zone == Z_ICONS && iconIndex == i;
            if (on) {
                fill.setColor(ink);
                c.drawOval(r, fill);
            }
            int color = on ? (day() && contentT.value < 0.5f && searchT.value < 0.5f ? Look.DAY_GROUND : Look.ON_INK) : Ui.alpha(ink, 0.78f);
            drawIcon(c, icons[i], r.centerX(), r.centerY(), 26 * k, color, 1.8f, false);
        }
    }

    /** A 24-unit line icon centered at (cx, cy), `size` px wide. */
    private void drawIcon(Canvas c, Path icon, float cx, float cy, float size, int color, float width, boolean filled) {
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(size / 24f, size / 24f);
        if (filled) {
            fill.setColor(color);
            c.drawPath(icon, fill);
        } else {
            stroke.setColor(color);
            stroke.setStrokeWidth(width);
            c.drawPath(icon, stroke);
        }
        c.restore();
    }

    private static void lerp(RectF out, RectF a, RectF b, float t) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t,
                a.bottom + (b.bottom - a.bottom) * t);
    }

    /** The search bar: large at rest, small at the top over content, the text field while searching. */
    private void drawPill(Canvas c, float ct, float st) {
        lerp(r2, PILL_REST, PILL_CONTENT, ct);
        lerp(r, r2, PILL_SEARCH, st);
        r.set(r.left * k, r.top * k, r.right * k, r.bottom * k);
        float radius = r.height() / 2;
        float restA = (1 - ct) * (1 - st);
        boolean focused = overlayKind == OV_NONE && zone == Z_PILL;
        boolean dayRest = day() && restA > 0.5f;
        // Frosted at rest: the pre-blurred landscape under the bar.
        if (restA > 0.004f && land != null && !dayRest) {
            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            image.setAlpha(Math.round(255 * restA));
            c.drawBitmap(land.blurred, null, screen, image);
            image.setAlpha(255);
            c.restore();
        }
        int restFill = dayRest ? (focused ? 0xFFFFFFFF : 0x66FFFFFF) : 0x29FFFFFF;
        int fillColor = blend(blend(restFill, 0x5C141416, ct), 0x1FFFFFFF, st);
        fill.setColor(fillColor);
        c.drawRoundRect(r, radius, radius, fill);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(dayRest ? 0x99FFFFFF : Ui.alpha(0xFFFFFFFF, 0.14f + 0.06f * (1 - ct)));
        c.drawRoundRect(r, radius, radius, stroke);
        int ink = blend(restInk(), Look.INK, Math.max(ct, st));
        if (focused) {
            float gap = (dayRest ? 0 : 4) * k, width = (dayRest ? 3 : 3) * k;
            stroke.setStrokeWidth(width);
            stroke.setColor(ink);
            r2.set(r);
            r2.inset(-gap - width / 2, -gap - width / 2);
            c.drawRoundRect(r2, radius + gap + width / 2, radius + gap + width / 2, stroke);
        }
        float pad = (34 + (22 - 34) * ct + (32 - 34) * st) * k;
        float iconSize = (34 + (24 - 34) * ct + (32 - 34) * st) * k;
        drawIcon(c, SEARCH_ICON, r.left + pad + iconSize / 2, r.centerY(), iconSize, Ui.alpha(ink, 0.85f), 1.9f, false);
        float textX = r.left + pad + iconSize + 20 * k;
        float micA = voice ? Math.max(restA, st) : 0;
        if (micA > 0.01f) drawIcon(c, MIC_ICON, r.right - pad - iconSize / 2, r.centerY(), iconSize, Ui.alpha(ink, 0.85f * micA), 1.8f, false);
        if (restA > 0.004f) {
            pillP.setColor(Ui.alpha(ink, (dayRest ? 0.74f : 0.8f) * restA));
            c.drawText(Ui.tr("Busque filmes, séries e apps", "Search movies, shows and apps"), textX, r.centerY() + Ui.halfCap(pillP), pillP);
        }
        float contentLabel = ct * (1 - st);
        if (contentLabel > 0.004f) {
            pillSmallP.setColor(Ui.alpha(ink, 0.8f * contentLabel));
            c.drawText(Ui.tr("Buscar", "Search"), textX, r.centerY() + Ui.halfCap(pillSmallP), pillSmallP);
        }
        if (st > 0.004f) {
            float room = r.right - pad - (voice ? iconSize + 20 * k : 0) - textX;
            if (query.isEmpty()) {
                pillP.setTextSize(40 * k);
                pillP.setColor(Ui.alpha(Look.INK, 0.5f * st));
                c.drawText(Ui.tr("Filmes, séries e apps", "Movies, shows and apps"), textX, r.centerY() + Ui.halfCap(pillP), pillP);
                pillP.setTextSize(34 * k);
            } else {
                queryP.setColor(Ui.alpha(Look.INK, st));
                float qw = queryP.measureText(query);
                c.save();
                c.clipRect(textX, r.top, textX + room, r.bottom);
                float x = qw > room ? textX + room - qw : textX;
                c.drawText(query, x, r.centerY() + Ui.halfCap(queryP), queryP);
                c.restore();
                fill.setColor(Ui.alpha(Look.INK, 0.9f * st));
                float cx = Math.min(textX + qw, textX + room) + 4 * k;
                c.drawRect(cx, r.centerY() - 22 * k, cx + 2.5f * k, r.centerY() + 22 * k, fill);
            }
        }
    }

    /** "Resume The Big Bang Theory · S10 E2 · 17 min left", centered under the search bar. */
    private void drawChip(Canvas c, float alpha) {
        ContinueWatching.Entry e = chipEntry();
        if (e == null) return;
        boolean dayLook = day();
        boolean focused = overlayKind == OV_NONE && zone == Z_CHIP;
        int ink = restInk();
        String lead = Ui.tr("Continuar", "Resume"), rest = chipRest(e);
        float textW = chipP.measureText(lead) + 10 * k + chipStrongP.measureText(e.title) + 10 * k + chipP.measureText(rest);
        float width = 8 * k + 100 * k + 18 * k + textW + 28 * k;
        float left = (w - width) / 2, top = CHIP_TOP * k;
        r.set(left, top, left + width, top + CHIP_H * k);
        float radius = r.height() / 2;
        if (focused) {
            fill.setColor(Ui.alpha(ink, alpha));
        } else {
            fill.setColor(Ui.alpha(dayLook ? 0x66FFFFFF : 0x52141010, alpha));
        }
        c.drawRoundRect(r, radius, radius, fill);
        if (!focused) {
            stroke.setStrokeWidth(Math.max(1, k));
            stroke.setColor(Ui.alpha(dayLook ? 0x99FFFFFF : 0x1FFFFFFF, alpha));
            c.drawRoundRect(r, radius, radius, stroke);
        }
        // Thumbnail with progress.
        r2.set(r.left + 8 * k, r.top + 8 * k, r.left + 108 * k, r.top + 64 * k);
        Bitmap thumb = row.isEmpty() || row.get(0).entry != e ? null : art.card(row.get(0).key, row.get(0).source);
        clip.reset();
        clip.addRoundRect(r2, 28 * k, 28 * k, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (thumb != null) {
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(thumb, null, r2, image);
            image.setAlpha(255);
        } else {
            fill.setColor(Ui.alpha(Look.FALLBACK_TOP, alpha));
            c.drawRect(r2, fill);
        }
        if (e.duration > 0) {
            float p = Math.min(1, (float) e.position / e.duration);
            fill.setColor(Ui.alpha(0x4DFFFFFF, alpha));
            c.drawRect(r2.left, r2.bottom - 4 * k, r2.right, r2.bottom, fill);
            fill.setColor(Ui.alpha(Look.INK, alpha));
            c.drawRect(r2.left, r2.bottom - 4 * k, r2.left + r2.width() * p, r2.bottom, fill);
        }
        c.restore();
        int strong = focused ? (dayLook ? Look.DAY_GROUND : Look.ON_INK) : ink;
        float x = r2.right + 18 * k, base = r.centerY() + Ui.halfCap(chipP);
        chipP.setColor(Ui.alpha(strong, (focused ? 0.8f : 0.66f) * alpha));
        c.drawText(lead, x, base, chipP);
        x += chipP.measureText(lead) + 10 * k;
        chipStrongP.setColor(Ui.alpha(strong, alpha));
        c.drawText(e.title, x, base, chipStrongP);
        x += chipStrongP.measureText(e.title) + 10 * k;
        c.drawText(rest, x, base, chipP);
        if (focused) drawOptionsHint(c, (w - hintP.measureText(optionsHint())) / 2,
                (CHIP_TOP + CHIP_H + (episodeNotices.isEmpty() ? 38 : 124)) * k, alpha, ink);
    }

    private static String chipRest(ContinueWatching.Entry e) {
        String code = e.episode > 0 ? "S" + e.season + " E" + e.episode : "";
        long left = e.duration > e.position ? (e.duration - e.position + 59_999) / 60_000 : 0;
        String s = join(code, left > 0 ? left + Ui.tr(" min restantes", " min left") : "");
        return s.isEmpty() ? "" : "· " + s;
    }

    private void drawEpisodeNotice(Canvas c, float alpha) {
        if (episodeNotices.isEmpty() || !online) return;
        ContinueWatching.Entry e = episodeNotices.get(0).entry;
        String label = Ui.tr("Novo episódio", "New episode") + " · " + e.title + " · " + e.code();
        if (episodeNotices.size() > 1) label += "  +" + (episodeNotices.size() - 1);
        float max = w - 2 * SAFE * k;
        label = android.text.TextUtils.ellipsize(label, new TextPaint(chipP), max - 48 * k, android.text.TextUtils.TruncateAt.END).toString();
        float width = chipP.measureText(label) + 48 * k;
        float top = (chipEntry() == null ? CHIP_TOP : CHIP_TOP + CHIP_H + 22) * k;
        r.set((w - width) / 2, top, (w + width) / 2, top + 64 * k);
        boolean focused = zone == Z_NEWS && overlayKind == OV_NONE;
        int ink = restInk();
        fill.setColor(Ui.alpha(focused ? ink : day() ? 0x66FFFFFF : 0x52141010, alpha));
        c.drawRoundRect(r, 32 * k, 32 * k, fill);
        chipP.setColor(Ui.alpha(focused ? day() ? Look.DAY_GROUND : Look.ON_INK : ink, alpha));
        c.drawText(label, r.left + 24 * k, r.centerY() + Ui.halfCap(chipP), chipP);
    }

    /** No TMDB key yet: apps work; a card offers setup from the phone or on the TV (HomeNoKey). */
    private void drawSetup(Canvas c, float alpha) {
        if (alpha <= 0.004f) return;
        String title = Ui.tr("Conclua a configuração para buscar filmes e séries", "Finish setup to search movies and shows");
        String line1 = Ui.tr("Use o celular para adicionar sua chave TMDB e a cidade.", "Scan with your phone to add your TMDB key and city.");
        String line2 = Ui.tr("Seus apps já funcionam.", "Your apps already work.");
        Bitmap qr = setupQr();
        String button = qr == null ? Ui.tr("Configurar nesta TV", "Set up on this TV") : Ui.tr("Configurar nesta TV", "Set up on this TV instead");
        float textW = Math.max(setupTitleP.measureText(title), Math.max(setupBodyP.measureText(line1), setupBodyP.measureText(line2)));
        float qrSize = qr == null ? 0 : 330 * k;
        float width = 20 * k + qrSize + (qr == null ? 36 * k : 56 * k) + textW + 56 * k;
        float height = 370 * k;
        float left = (w - width) / 2, top = 310 * k;
        r.set(left, top, left + width, top + height);
        fill.setColor(Ui.alpha(0xCC121214, alpha));
        c.drawRoundRect(r, 36 * k, 36 * k, fill);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(Ui.alpha(0x1AFFFFFF, alpha));
        c.drawRoundRect(r, 36 * k, 36 * k, stroke);
        float x = left + 20 * k;
        if (qr != null) {
            r2.set(x, top + 20 * k, x + qrSize, top + 20 * k + qrSize);
            fill.setColor(Ui.alpha(Look.INK, alpha));
            c.drawRoundRect(r2, 16 * k, 16 * k, fill);
            r2.inset(20 * k, 20 * k);
            image.setFilterBitmap(false);
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(qr, null, r2, image);
            image.setAlpha(255);
            image.setFilterBitmap(true);
            x += qrSize + 56 * k;
        } else {
            x += 36 * k;
        }
        float y = top + 92 * k;
        setupTitleP.setColor(Ui.alpha(Look.INK, alpha));
        c.drawText(title, x, y, setupTitleP);
        setupBodyP.setColor(Ui.alpha(Look.INK, 0.72f * alpha));
        c.drawText(line1, x, y + 58 * k, setupBodyP);
        c.drawText(line2, x, y + 96 * k, setupBodyP);
        boolean focused = overlayKind == OV_NONE && zone == Z_SETUP;
        float bw = setupButtonP.measureText(button) + 72 * k;
        r2.set(x, y + 140 * k, x + bw, y + 212 * k);
        fill.setColor(Ui.alpha(focused ? Look.INK : 0x24FFFFFF, alpha));
        c.drawRoundRect(r2, r2.height() / 2, r2.height() / 2, fill);
        setupButtonP.setColor(Ui.alpha(focused ? Look.ON_INK : Look.INK, alpha));
        c.drawText(button, r2.left + 36 * k, r2.centerY() + Ui.halfCap(setupButtonP), setupButtonP);
    }

    /** The phone setup page's QR code (PhoneLink), or null when the phone link isn't available. */
    private Bitmap setupQr() { return setupCode; }

    private void refreshPhoneCodes() {
        WORK.execute(() -> {
            String setup = Setup.hasKey() ? null : PhoneLink.setupUrl(getContext());
            String search = mode == SEARCH ? PhoneLink.typingUrl(getContext()) : null;
            Bitmap a = setup == null ? null : Qr.bitmap(setup, Look.si(getContext(), 144));
            Bitmap b = search == null ? null : Qr.bitmap(search, Look.si(getContext(), 100));
            handler.post(() -> { if (running) { setupCode = a; phoneCode = b; changed(); } });
        });
    }

    /** Offline: the bar says so and the apps keep working (HomeOffline). */
    private void drawOffline(Canvas c, float alpha) {
        if (alpha <= 0.004f) return;
        r.set(PILL_REST.left * k, PILL_REST.top * k, PILL_REST.right * k, PILL_REST.bottom * k);
        float radius = r.height() / 2;
        fill.setColor(Ui.alpha(0x5C141110, alpha));
        c.drawRoundRect(r, radius, radius, fill);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(Ui.alpha(0x24FFFFFF, alpha));
        c.drawRoundRect(r, radius, radius, stroke);
        drawIcon(c, OFFLINE_ICON, r.left + 34 * k + 17 * k, r.centerY(), 34 * k, Ui.alpha(Look.INK, 0.7f * alpha), 1.8f, false);
        offlineP.setColor(Ui.alpha(Look.INK, 0.7f * alpha));
        c.drawText(Ui.tr("Sem conexão com a internet", "No internet connection"), r.left + 88 * k, r.centerY() + Ui.halfCap(offlineP), offlineP);
        offlineLineP.setColor(Ui.alpha(Look.INK, alpha));
        c.drawText(Ui.tr("Os apps desta TV continuam abrindo. A busca e as sugestões voltam quando a internet voltar.",
                "Apps on this TV still open. Search and picks come back when you’re online."), w / 2f, (532 + 26) * k, offlineLineP);
    }

    // MARK: - Drawing: content (hero and row)

    private Card focusedCard() {
        return row.isEmpty() ? null : row.get(Math.min(rowIndex, row.size() - 1));
    }

    private void drawHero(Canvas c, float alpha) {
        Card card = focusedCard();
        if (card == null) return;
        float x = SAFE * k, y = HERO_TOP * k;
        kickerP.setColor(Ui.alpha(Look.INK, 0.72f * alpha));
        c.drawText(card.kicker, x, y + 24 * 0.96f * k, kickerP);
        if (heroTitleFor != card) {
            heroTitleFor = card;
            heroTitleFit = TextUtils.ellipsize(card.title, heroTitleP, 1280 * k, TextUtils.TruncateAt.END).toString();
        }
        heroTitleP.setColor(Ui.alpha(Look.INK, alpha));
        float titleTop = y + 29 * k + 12 * k;
        c.drawText(heroTitleFit, x - Ui.leftBearing(heroTitleP, heroTitleFit), titleTop + 84 * 0.78f * k, heroTitleP);
        float metaY = titleTop + 84 * k + 22 * k + 16 * k;
        float mx = x;
        metaP.setColor(Ui.alpha(Look.INK, 0.8f * alpha));
        if (!card.meta.isEmpty()) {
            c.drawText(card.meta, mx, metaY + Ui.halfCap(metaP), metaP);
            mx += metaP.measureText(card.meta) + 20 * k;
        }
        if (card.progress >= 0) {
            r.set(mx, metaY - 2.5f * k, mx + 200 * k, metaY + 2.5f * k);
            fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            c.drawRoundRect(r, 3 * k, 3 * k, fill);
            fill.setColor(Ui.alpha(Look.INK, alpha));
            r.right = r.left + r.width() * card.progress;
            c.drawRoundRect(r, 3 * k, 3 * k, fill);
            mx += 220 * k;
            if (!card.left.isEmpty()) c.drawText(card.left, mx, metaY + Ui.halfCap(metaP), metaP);
        }
        float by = metaY + 16 * k + 36 * k;
        heroButton(c, 0, card.primary, x, by, alpha);
        if (!card.secondary.equals(card.primary))
            heroButton(c, 1, card.secondary, x + heroButtonWidth(0, card.primary) + 16 * k, by, alpha);
    }

    private float heroButtonWidth(int i, String label) {
        return i == 0 ? (28 + 22 + 14 + 34) * k + buttonP.measureText(label) : 64 * k + button2P.measureText(label);
    }

    private void heroButton(Canvas c, int i, String label, float x, float top, float alpha) {
        boolean focused = overlayKind == OV_NONE && zone == Z_HERO && heroIndex == i;
        r.set(x, top, x + heroButtonWidth(i, label), top + 72 * k);
        fill.setColor(Ui.alpha(focused ? Look.INK : i == 0 ? 0x24FFFFFF : 0x14FFFFFF, alpha));
        c.drawRoundRect(r, 36 * k, 36 * k, fill);
        int ink = focused ? Look.ON_INK : i == 0 ? Look.INK : Ui.alpha(Look.INK, 0.86f);
        Paint p = i == 0 ? buttonP : button2P;
        p.setColor(Ui.alpha(ink, alpha));
        if (i == 0) {
            drawIcon(c, PLAY_ICON, r.left + 28 * k + 11 * k, r.centerY(), 22 * k, Ui.alpha(ink, alpha), 0, true);
            c.drawText(label, r.left + (28 + 22 + 14) * k, r.centerY() + Ui.halfCap(p), p);
        } else {
            c.drawText(label, r.left + 32 * k, r.centerY() + Ui.halfCap(p), p);
        }
    }

    private void drawRow(Canvas c, float alpha) {
        float scroll = rowScroll.value * k;
        float top = CARDS_TOP * k;
        for (int i = 0; i < row.size(); i++) {
            Card card = row.get(i);
            float left = card.x * k - scroll;
            if (left > w || left + CARD_W * k < -60 * k) continue;
            if (card.sectionStart) {
                sectionP.setColor(Ui.alpha(Look.INK, 0.72f * alpha));
                c.drawText(card.section, left, ROW_TOP * k + 24 * 0.96f * k, sectionP);
            }
        }
        // The focused card last: it grows over its neighbors.
        int top1 = -1;
        for (int i = 0; i < row.size() && i < rowFocus.length; i++) {
            if (rowFocus[i].value > 0.02f && (top1 < 0 || rowFocus[i].value > rowFocus[top1].value)) top1 = i;
        }
        for (int i = 0; i < row.size(); i++) if (i != top1) drawCard(c, i, top, scroll, alpha);
        if (top1 >= 0) drawCard(c, top1, top, scroll, alpha);
        Card focused = focusedCard();
        if (overlayKind == OV_NONE && zone == Z_ROW && focused != null && removable(focused))
            drawOptionsHint(c, SAFE * k, 936 * k, alpha, Look.INK);
    }

    private String optionsHint() { return Ui.tr("Segure OK para ver opções", "Hold OK for options"); }
    private void drawOptionsHint(Canvas c, float x, float y, float alpha, int ink) {
        hintP.setColor(Ui.alpha(ink, 0.72f * alpha));
        c.drawText(optionsHint(), x, y, hintP);
    }

    private void drawCard(Canvas c, int i, float top, float scroll, float alpha) {
        Card card = row.get(i);
        float left = card.x * k - scroll;
        if (left > w || left + CARD_W * k < -60 * k) return;
        float f = Ui.clamp(rowFocus[i].value), s = 1 + 0.05f * rowFocus[i].value;
        float cw = CARD_W * k, ch = CARD_H * k, cx = left + cw / 2, cy = top + ch / 2;
        r.set(cx - cw * s / 2, cy - ch * s / 2, cx + cw * s / 2, cy + ch * s / 2);
        float radius = 14 * k * s;
        Bitmap b = art.card(card.key, card.source);
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (b != null) {
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, r, image);
            image.setAlpha(255);
        } else {
            drawFallback(c, r, card.title, alpha);
        }
        if (card.progress >= 0) {
            fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            c.drawRect(r.left, r.bottom - 5 * k, r.right, r.bottom, fill);
            fill.setColor(Ui.alpha(Look.INK, alpha));
            c.drawRect(r.left, r.bottom - 5 * k, r.left + r.width() * card.progress, r.bottom, fill);
        }
        c.restore();
        if (f > 0.01f) {
            float gap = 4 * k, ring = 4 * k;
            stroke.setStrokeWidth(ring);
            stroke.setColor(Ui.alpha(Look.INK, f * alpha));
            r2.set(r);
            r2.inset(-gap - ring / 2, -gap - ring / 2);
            c.drawRoundRect(r2, radius + gap + ring / 2, radius + gap + ring / 2, stroke);
        }
        float textTop = top + ch + 14 * k;
        if (card.titleFit != null) {
            cardTitleP.setColor(Ui.alpha(Look.INK, (0.78f + 0.22f * f) * alpha));
            c.drawText(card.titleFit, left, textTop + 22 * 0.96f * k, cardTitleP);
            cardSubP.setColor(Ui.alpha(Look.INK, (0.56f + 0.16f * f) * alpha));
            c.drawText(card.subFit, left, textTop + 22 * 0.96f * k + 4 * k + 22 * 1.2f * k, cardSubP);
        }
    }

    /** No picture: the title on a dark gradient (never a fake image). */
    private void drawFallback(Canvas c, RectF box, String title, float alpha) {
        fill.setShader(new LinearGradient(0, box.top, 0, box.bottom, Look.FALLBACK_TOP, Look.FALLBACK_BOTTOM, Shader.TileMode.CLAMP));
        fill.setAlpha(Math.round(255 * alpha));
        c.drawRect(box, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        TextPaint p = new TextPaint(bigSubP);
        p.setTextSize(Math.min(30 * k, box.height() * 0.16f));
        p.setTypeface(Ui.semibold);
        p.setColor(Ui.alpha(Look.INK, 0.7f * alpha));
        String fit = TextUtils.ellipsize(title, p, box.width() - 40 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(fit, box.left + 20 * k, box.bottom - 24 * k, p);
    }

    // MARK: - Drawing: logos

    private void drawLogos(Canvas c, float alpha) {
        if (overlayKind == OV_ALL) alpha *= 1 - Ui.clamp(overlayT.value);
        if (alpha <= 0.004f) return;
        boolean dayLook = day() && contentT.value < 0.5f && !(overlayKind == OV_MENU && menuCard == null);
        int ink = dayLook ? Look.DAY_INK : Look.INK;
        PorterDuffColorFilter tint = dayLook ? DAY_TINT : INK_TINT;
        for (int i = 0; i < shelf.size() && i < logoX.length; i++) {
            HomeApps.Item it = shelf.get(i);
            boolean focused = overlayKind == OV_NONE && zone == Z_LOGOS && logoIndex == i;
            float s = focused ? 1.12f : 1;
            float ww = logoW[i] * k * s, hh = logoH[i] * k * s;
            float cx = (logoX[i] + logoW[i] / 2) * k, cy = LOGO_CY * k;
            float a = alpha * (focused ? 1 : dayLook ? 0.7f : 0.6f);
            Bitmap b = whiteLogo(it);
            if (b != null) {
                r.set(cx - ww / 2, cy - hh / 2, cx + ww / 2, cy + hh / 2);
                image.setColorFilter(tint);
                image.setAlpha(Math.round(255 * a));
                c.drawBitmap(b, null, r, image);
                image.setColorFilter(null);
                image.setAlpha(255);
            } else {
                logoNameP.setColor(Ui.alpha(ink, a));
                logoNameP.setTextSize(26 * k * s);
                c.drawText(it.label, cx - logoNameP.measureText(it.label) / 2, cy + Ui.halfCap(logoNameP), logoNameP);
                logoNameP.setTextSize(26 * k);
            }
            if (focused) {
                fill.setColor(Ui.alpha(ink, alpha));
                c.drawCircle(cx, cy + 32 * k - 5 * k, 3 * k, fill);
                if (editing) {
                    logoNameP.setColor(Ui.alpha(ink, alpha));
                    c.drawText("‹", cx - ww / 2 - 26 * k, cy + Ui.halfCap(logoNameP), logoNameP);
                    c.drawText("›", cx + ww / 2 + 14 * k, cy + Ui.halfCap(logoNameP), logoNameP);
                }
            }
        }
        if (editing && logoIndex < shelf.size()) drawEditHints(c, ink, alpha);
    }

    /** "← → Move Netflix   OK Done   ↓ Remove from Home" above the logos while editing (EditApps). */
    private void drawEditHints(Canvas c, int ink, float alpha) {
        String name = shelf.get(logoIndex).label;
        String[][] hints = {{"← →", Ui.tr("Mover ", "Move ") + name}, {"OK", Ui.tr("Pronto", "Done")}, {"↓", Ui.tr("Tirar do Início", "Remove from Home")}};
        float total = 0;
        for (String[] hint : hints) total += kbdP.measureText(hint[0]) + 24 * k + 12 * k + hintP.measureText(hint[1]);
        total += 36 * k * (hints.length - 1);
        float x = (w - total) / 2, cy = (820 + 15) * k;
        for (String[] hint : hints) {
            float kw = kbdP.measureText(hint[0]) + 24 * k;
            r.set(x, cy - 17 * k, x + kw, cy + 17 * k);
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(Ui.alpha(ink, 0.6f * alpha));
            c.drawRoundRect(r, 8 * k, 8 * k, stroke);
            kbdP.setColor(Ui.alpha(ink, 0.85f * alpha));
            c.drawText(hint[0], x + 12 * k, cy + Ui.halfCap(kbdP), kbdP);
            x += kw + 12 * k;
            hintP.setColor(Ui.alpha(ink, 0.85f * alpha));
            c.drawText(hint[1], x, cy + Ui.halfCap(hintP), hintP);
            x += hintP.measureText(hint[1]) + 36 * k;
        }
    }

    // MARK: - Drawing: search

    private void drawSearch(Canvas c, float alpha) {
        keys.setActive(overlayKind == OV_NONE && zone == Z_KEYS);
        if (phoneCode != null) {
            r.set(1480 * k, 40 * k, 1580 * k, 140 * k);
            image.setAlpha(Math.round(255 * alpha)); c.drawBitmap(phoneCode, null, r, image); image.setAlpha(255);
        }
        float top = RESULTS_TOP * k;
        if (query.trim().isEmpty()) {
            if (recent.isEmpty()) return;
            labelP.setColor(Ui.alpha(Look.INK, 0.66f * alpha));
            c.drawText(Ui.tr("Recentes", "Recent"), RESULTS_LEFT * k, top + 24 * 0.96f * k, labelP);
            float x = RESULTS_LEFT * k, y = top + 64 * k;
            for (int i = 0; i < recent.size(); i++) {
                String q = recent.get(i);
                String label = i < recentFits.size() ? recentFits.get(i) : q;
                float bw = recentP.measureText(label) + 56 * k;
                r.set(x, y, x + bw, y + 60 * k);
                boolean on = overlayKind == OV_NONE && zone == Z_RECENT && recentIndex == i;
                fill.setColor(Ui.alpha(on ? Look.INK : 0x1AFFFFFF, alpha));
                c.drawRoundRect(r, 30 * k, 30 * k, fill);
                recentP.setColor(Ui.alpha(on ? Look.ON_INK : Look.INK, alpha));
                c.drawText(label, x + 28 * k, r.centerY() + Ui.halfCap(recentP), recentP);
                y += 76 * k;
            }
            return;
        }
        if (sections.isEmpty()) {
            String label;
            if (searching) label = Ui.tr("Buscando…", "Searching…");
            else if (!searchError.isEmpty()) label = searchError;
            else label = Ui.tr("Nenhum título com “", "No titles match “") + query.trim() + "”";
            labelP.setColor(Ui.alpha(Look.INK, 0.66f * alpha));
            c.save(); c.clipRect(RESULTS_LEFT * k, top, RIGHT * k, h);
            c.drawText(label, RESULTS_LEFT * k, top + 24 * 0.96f * k, labelP);
            c.restore();
            return;
        }
        // Results stay beside the keyboard while their rows scroll.
        c.save();
        c.clipRect((RESULTS_LEFT - 16) * k, (RESULTS_TOP - 12) * k, w, h);
        float scroll = searchScroll.value;
        for (int si = 0; si < sections.size(); si++) {
            Section sec = sections.get(si);
            float y = (sec.top - scroll) * k;
            if (y > h || y + sec.height() * k < 0) continue;
            labelP.setColor(Ui.alpha(Look.INK, 0.66f * alpha));
            String label = sec.label;
            if (si == 0 && searching && !sec.apps) label += "  ·  " + Ui.tr("buscando…", "searching…");
            c.drawText(label, RESULTS_LEFT * k, y + 24 * 0.96f * k, labelP);
            float itemsTop = y + (29 + 20) * k;
            c.save();
            c.translate(-sec.scroll.value * k, 0);
            if (sec.apps) drawAppResults(c, sec, si, itemsTop, alpha);
            else for (int i = 0; i < sec.titles.size(); i++) drawResult(c, sec.titles.get(i), i, itemsTop, alpha);
            c.restore();
        }
        c.restore();
    }

    private void drawAppResults(Canvas c, Section sec, int si, float y, float alpha) {
        float x = RESULTS_LEFT * k;
        for (int i = 0; i < sec.items.size(); i++) {
            HomeApps.Item it = sec.items.get(i);
            boolean on = overlayKind == OV_NONE && zone == Z_RESULTS && sectionIndex == si && resultIndex == i;
            r.set(x, y, x + 240 * k, y + 120 * k);
            fill.setColor(Ui.alpha(on ? Look.INK : 0x14FFFFFF, alpha));
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            int ink = on ? Look.ON_INK : Look.INK;
            if (it.logo != null) {
                float aspect = (float) it.logo.getWidth() / it.logo.getHeight();
                float lh = Math.min(48 * k, 180 * k / aspect), lw = lh * aspect;
                r2.set(r.centerX() - lw / 2, r.centerY() - lh / 2, r.centerX() + lw / 2, r.centerY() + lh / 2);
                image.setColorFilter(on ? ON_INK_TINT : INK_TINT);
                image.setAlpha(Math.round(255 * alpha));
                c.drawBitmap(it.logo, null, r2, image);
                image.setColorFilter(null);
                image.setAlpha(255);
            } else {
                logoNameP.setColor(Ui.alpha(ink, alpha));
                String fit = TextUtils.ellipsize(it.label, new TextPaint(logoNameP), 200 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(fit, r.centerX() - logoNameP.measureText(fit) / 2, r.centerY() + Ui.halfCap(logoNameP), logoNameP);
            }
            x += 260 * k;
        }
    }

    private void drawResult(Canvas c, Result res, int i, float top, float alpha) {
        float left = (RESULTS_LEFT + i * (RESULT_W + RESULT_GAP)) * k;
        float f = Ui.clamp(res.focus.value);
        float s = 1 + 0.04f * res.focus.value;
        float cw = RESULT_W * k, ch = RESULT_H * k, cx = left + cw / 2, cy = top + ch / 2;
        r.set(cx - cw * s / 2, cy - ch * s / 2, cx + cw * s / 2, cy + ch * s / 2);
        float radius = 16 * k * s;
        Bitmap b = art.card(res.key, res.source);
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (b != null) {
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, r, image);
            image.setAlpha(255);
        } else {
            drawFallback(c, r, res.title.name, alpha);
        }
        c.restore();
        if (f > 0.01f) {
            float gap = 4 * k, ring = 4 * k;
            stroke.setStrokeWidth(ring);
            stroke.setColor(Ui.alpha(Look.INK, f * alpha));
            r2.set(r);
            r2.inset(-gap - ring / 2, -gap - ring / 2);
            c.drawRoundRect(r2, radius + gap + ring / 2, radius + gap + ring / 2, stroke);
        }
        float y = top + ch + 14 * k;
        resultTitleP.setColor(Ui.alpha(Look.INK, alpha));
        if (res.titleFit != null) c.drawText(res.titleFit, left, y + 26 * 0.96f * k, resultTitleP);
        if (res.whereFit != null && !res.whereFit.isEmpty()) {
            float wy = y + 26 * 1.2f * k + 10 * k;
            float x = left;
            HomeApps.Item app = appFor(res.pkg);
            if (app != null && app.logo != null) {
                float lh = 18 * k, lw = lh * app.logo.getWidth() / app.logo.getHeight();
                r2.set(x, wy, x + lw, wy + lh);
                image.setColorFilter(INK_TINT);
                image.setAlpha(Math.round(230 * alpha));
                c.drawBitmap(app.logo, null, r2, image);
                image.setColorFilter(null);
                image.setAlpha(255);
                x += lw + 12 * k;
            }
            whereP.setColor(Ui.alpha(Look.INK, 0.68f * alpha));
            c.drawText(res.whereFit, x, wy + 9 * k + Ui.halfCap(whereP), whereP);
        }
    }

    private HomeApps.Item appFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (HomeApps.Item it : shelf) if (pkg.equals(it.pkg)) return it;
        return null;
    }

    // MARK: - Drawing: overlays (menus, All apps, toasts)

    private void drawOverlay(Canvas c) {
        float ot = Ui.clamp(overlayT.value);
        if (ot > 0.004f) {
            if (overlayKind == OV_ALL || (overlayKind == OV_MENU && menuCard != null)) drawDim(c, ot);
            if (overlayKind == OV_ALL) drawAllApps(c, ot);
            else if (overlayKind == OV_MENU) drawMenu(c, ot);
        }
        drawToast(c);
    }

    private void drawMenu(Canvas c, float alpha) {
        if (menuCard == null) { drawGlance(c, alpha); return; }
        float menuLeft, menuTop, menuW = 600 * k;
        if (menuCard != null) {
            // A card's actions: the card large on the left, the menu beside it (LongPress).
            labelP.setColor(Ui.alpha(Look.INK, 0.5f * alpha));
            labelP.setTextSize(28 * k);
            c.drawText(menuCard.section, SAFE * k, (168 + 28 * 0.96f) * k, labelP);
            labelP.setTextSize(24 * k);
            r.set(SAFE * k, 236 * k, (SAFE + 560) * k, (236 + 315) * k);
            Bitmap b = art.card(menuCard.key, menuCard.source);
            clip.reset();
            clip.addRoundRect(r, 20 * k, 20 * k, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (b != null) {
                image.setAlpha(Math.round(255 * alpha));
                c.drawBitmap(b, null, r, image);
                image.setAlpha(255);
            } else {
                drawFallback(c, r, menuCard.title, alpha);
            }
            if (menuCard.progress >= 0) {
                fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
                c.drawRect(r.left, r.bottom - 6 * k, r.right, r.bottom, fill);
                fill.setColor(Ui.alpha(Look.INK, alpha));
                c.drawRect(r.left, r.bottom - 6 * k, r.left + r.width() * menuCard.progress, r.bottom, fill);
            }
            c.restore();
            bigTitleP.setColor(Ui.alpha(Look.INK, alpha));
            c.drawText(menuCard.title, r.left, r.bottom + (22 + 32 * 0.96f) * k, bigTitleP);
            bigSubP.setColor(Ui.alpha(Look.INK, 0.7f * alpha));
            String sub = menuCard.kind == CW ? join(menuCard.sub, menuCard.entry.provider) : menuCard.sub;
            c.drawText(sub, r.left, r.bottom + (22 + 32 * 1.2f + 6 + 24 * 0.96f) * k, bigSubP);
            menuLeft = 712 * k;
            menuTop = 236 * k;
        } else {
            // The inputs, under the Inputs button.
            iconRect(0);
            menuLeft = RIGHT * k - menuW;
            menuTop = r.bottom + 20 * k;
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * alpha));
            c.drawRect(screen, fill);
        }
        float itemH = 76 * k, pad = 16 * k;
        r.set(menuLeft, menuTop, menuLeft + menuW, menuTop + pad * 2 + menu.size() * itemH + Math.max(0, menu.size() - 1) * 4 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.5f * alpha));
        r2.set(r);
        r2.offset(0, 30 * k);
        c.drawRoundRect(r2, 30 * k, 30 * k, shadow);
        fill.setColor(Ui.alpha(0xF0161211, alpha));
        c.drawRoundRect(r, 30 * k, 30 * k, fill);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(Ui.alpha(0x1FFFFFFF, alpha));
        c.drawRoundRect(r, 30 * k, 30 * k, stroke);
        float y = r.top + pad;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.get(i);
            boolean on = i == menuIndex;
            r2.set(r.left + pad, y, r.right - pad, y + itemH);
            if (on) {
                fill.setColor(Ui.alpha(Look.INK, alpha));
                c.drawRoundRect(r2, 18 * k, 18 * k, fill);
            }
            int ink = on ? 0xFF14100F : Ui.alpha(Look.INK, item.dim ? 0.5f : 1);
            float x = r2.left + 24 * k;
            if (item.icon != null) {
                drawIcon(c, item.icon, x + 11 * k, r2.centerY(), 22 * k, Ui.alpha(ink, alpha), 2f, item.icon == PLAY_ICON);
                x += 22 * k + 18 * k;
            }
            Paint p = on ? menuItemOnP : menuItemP;
            p.setColor(Ui.alpha(ink, alpha));
            c.drawText(item.label, x, r2.centerY() + Ui.halfCap(p), p);
            y += itemH + 4 * k;
        }
    }

    /** The approved glance panel keeps inputs beside weather and cached market data. */
    private void drawGlance(Canvas c, float alpha) {
        fill.setColor(Ui.alpha(0xFF000000, 0.35f * alpha)); c.drawRect(screen, fill);
        c.save(); c.translate(0, -600 * k); drawLogos(c, 0.5f * alpha); c.restore();
        r.set(24 * k, 460 * k, 1896 * k, 1056 * k);
        clip.reset(); clip.addRoundRect(r, 40 * k, 40 * k, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        if (land != null) { image.setAlpha(Math.round(255 * alpha)); c.drawBitmap(land.blurred, null, screen, image); image.setAlpha(255); }
        fill.setColor(Ui.alpha(0xFF100D0C, 0.82f * alpha)); c.drawRect(r, fill); c.restore();
        stroke.setStrokeWidth(Math.max(1, k)); stroke.setColor(Ui.alpha(Look.GLASS_LINE, alpha)); c.drawRoundRect(r, 40 * k, 40 * k, stroke);
        glanceHeadP.setColor(Ui.alpha(Look.INK_3, alpha)); glanceBodyP.setColor(Ui.alpha(Look.INK_2, alpha));
        glanceTempP.setColor(Ui.alpha(Look.INK, alpha)); glanceValueP.setColor(Ui.alpha(Look.INK, alpha));
        c.drawText(Ui.tr("Entradas", "Inputs"), 116 * k, 552 * k, glanceHeadP);
        if (menu.isEmpty()) c.drawText(Ui.tr("Nenhuma entrada disponível", "No inputs available"), 116 * k, 622 * k, glanceBodyP);
        int first = Math.max(0, menuIndex - 5);
        for (int i = first; i < menu.size() && i < first + 6; i++) {
            MenuItem item = menu.get(i); float top = (572 + (i - first) * 72) * k;
            boolean selected = i == menuIndex;
            if (selected) { fill.setColor(Ui.alpha(Look.INK, alpha)); r2.set(96 * k, top, 548 * k, top + 68 * k); c.drawRoundRect(r2, 18 * k, 18 * k, fill); }
            menuItemP.setColor(Ui.alpha(selected ? Look.ON_INK : item.dim ? Look.INK_3 : Look.INK, alpha));
            String label = TextUtils.ellipsize(item.label, new TextPaint(menuItemP), 410 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(label, 116 * k, top + 34 * k + Ui.halfCap(menuItemP), menuItemP);
        }
        HomeData.Weather wx = data.weather;
        String city = Setup.city().isEmpty() ? Ui.tr("Tempo", "Weather") : Setup.city();
        c.drawText(city, 620 * k, 552 * k, glanceHeadP);
        String temperature = Double.isNaN(wx.temp) ? "—" : HomeData.degrees(wx.temp);
        c.drawText(temperature, 620 * k, 674 * k, glanceTempP);
        c.drawText(HomeData.condition(wx.code, wx.isDay), 640 * k + glanceTempP.measureText(temperature), 674 * k, glanceBodyP);
        int hours = Math.min(5, Math.min(wx.hours.size(), wx.hourTemp.size()));
        for (int i = 0; i < hours; i++) {
            float x = (620 + i * 128) * k;
            c.drawText(HomeData.hour(wx.hours.get(i)), x, 746 * k, glanceHeadP);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), x, 792 * k, glanceValueP);
        }
        if (hours == 0) c.drawText(Ui.tr("Previsão indisponível", "Forecast unavailable"), 620 * k, 758 * k, glanceBodyP);
        if (wx.sunrise > 0 && wx.sunset > 0) c.drawText(Ui.tr("Nascer do sol ", "Sunrise ") + Ui.time(wx.sunrise)
                + Ui.tr(" · Pôr do sol ", " · Sunset ") + Ui.time(wx.sunset), 620 * k, 856 * k, glanceHeadP);
        c.drawText(Ui.tr("Mercados · ", "Markets · ") + Config.CURRENCY.toUpperCase(Locale.ROOT), 1370 * k, 552 * k, glanceHeadP);
        for (int i = 0; i < HomeData.coinCount(); i++) {
            float baseline = (632 + i * 82) * k;
            c.drawText(HomeData.coinName(i), 1370 * k, baseline, glanceBodyP);
            glanceValueP.setTextAlign(Paint.Align.RIGHT); c.drawText(HomeData.compact(data.prices[i]), 1824 * k, baseline, glanceValueP); glanceValueP.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawAllApps(Canvas c, float alpha) {
        allTitleP.setColor(Ui.alpha(Look.INK, alpha));
        float shift = allScroll.value * 100 * k;
        c.drawText(Ui.tr("Todos os apps", "All apps"), SAFE * k, (88 + 30 + 56 * 0.36f) * k - shift, allTitleP);
        String edit = Ui.tr("Editar apps do Início", "Edit Home apps");
        float bw = editP.measureText(edit) + 56 * k;
        r.set(RIGHT * k - bw, (88 + 30 - 30) * k - shift, RIGHT * k, (88 + 30 + 30) * k - shift);
        boolean editOn = allIndex < 0;
        fill.setColor(Ui.alpha(editOn ? Look.INK : 0x1FFFFFFF, alpha));
        c.drawRoundRect(r, 30 * k, 30 * k, fill);
        editP.setColor(Ui.alpha(editOn ? Look.ON_INK : Look.INK, alpha));
        c.drawText(edit, r.left + 28 * k, r.centerY() + Ui.halfCap(editP), editP);
        float colW = (1768 - 3 * 24) / 4f;
        for (int i = 0; i < allApps.size(); i++) {
            int rowN = i / 4, col = i % 4;
            float x = (76 + col * (colW + 24)) * k, y = (248 + rowN * 100) * k - shift;
            if (y > h || y + 92 * k < 0) continue;
            r.set(x, y, x + colW * k, y + 92 * k);
            boolean on = i == allIndex;
            if (on) {
                fill.setColor(Ui.alpha(Look.INK, alpha));
                c.drawRoundRect(r, 22 * k, 22 * k, fill);
            }
            Paint p = on ? allItemOnP : allItemP;
            p.setColor(Ui.alpha(on ? Look.ON_INK : Look.INK, alpha));
            String label = allApps.get(i).label;
            c.drawText(label, r.left + 20 * k, r.centerY() + Ui.halfCap(p), p);
        }
    }

    void showToast(String text) {
        toasts.add(text);
        wakeFrames();
    }

    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            overlay.invalidate();
        }
        if (toast == null) {
            String next = toasts.poll();
            if (next != null) {
                toast = next;
                toastAt = up;
                overlay.setVisibility(VISIBLE);
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        return age < 350 || age > TOAST_MS - 450;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        String fit = TextUtils.ellipsize(toast, new TextPaint(toastP), 1100 * k, TextUtils.TruncateAt.END).toString();
        float tw = toastP.measureText(fit), top = (150 - (1 - a) * 16) * k;
        r.set((w - tw) / 2 - 32 * k, top, (w + tw) / 2 + 32 * k, top + 64 * k);
        fill.setColor(Ui.alpha(0xEB121214, a));
        c.drawRoundRect(r, 32 * k, 32 * k, fill);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(Ui.alpha(0x1AFFFFFF, a));
        c.drawRoundRect(r, 32 * k, 32 * k, stroke);
        toastP.setColor(Ui.alpha(Look.INK, a));
        c.drawText(fit, r.left + 32 * k, r.centerY() + Ui.halfCap(toastP), toastP);
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Modes and focus

    private void setMode(int next) {
        mode = next;
        if (next == SEARCH) refreshPhoneCodes();
        contentT.target = next == CONTENT ? 1 : 0;
        searchT.target = next == SEARCH ? 1 : 0;
        if (next == SEARCH) {
            keys.setVisibility(VISIBLE);
        } else {
            keys.setActive(false);
        }
        wakeFrames();
    }

    private void aimRow(boolean snap) {
        for (int i = 0; i < rowFocus.length; i++) {
            rowFocus[i].target = overlayKind == OV_NONE && zone == Z_ROW && i == rowIndex ? 1 : 0;
            if (snap) rowFocus[i].snap(rowFocus[i].target);
        }
        if (!row.isEmpty()) {
            Card c = row.get(Math.min(rowIndex, row.size() - 1));
            float target = rowScroll.target;
            if (c.x - target < SAFE) target = c.x - SAFE;
            if (c.x + CARD_W - target > RIGHT - 120) target = c.x + CARD_W - (RIGHT - 120);
            float last = row.get(row.size() - 1).x + CARD_W - RIGHT;
            rowScroll.target = Math.max(0, Math.min(Math.max(0, last), target));
            if (snap) rowScroll.snap(rowScroll.target);
        }
        Result focused = focusedResult();
        for (Result res : results) {
            res.focus.target = overlayKind == OV_NONE && zone == Z_RESULTS && res == focused ? 1 : 0;
            if (snap) res.focus.snap(res.focus.target);
        }
        // Keep the focused row of results in view (it scrolls under the keyboard).
        float target = searchScroll.target;
        if (zone == Z_RESULTS && sectionIndex < sections.size()) {
            Section sec = sections.get(sectionIndex);
            if (sec.top - target < RESULTS_TOP) target = sec.top - RESULTS_TOP;
            if (sec.top + sec.height() - target > 1060) target = sec.top + sec.height() - 1060;
            // Sideways: keep the focused card inside the safe area.
            float step = sec.apps ? 260 : RESULT_W + RESULT_GAP, width = sec.apps ? 240 : RESULT_W;
            float x = resultIndex * step, sx = sec.scroll.target;
            if (x - sx < 0) sx = x;
            if (x + width - sx > RIGHT - RESULTS_LEFT) sx = x + width - (RIGHT - RESULTS_LEFT);
            sec.scroll.target = Math.max(0, sx);
            if (snap) sec.scroll.snap(sec.scroll.target);
        } else if (zone != Z_RESULTS) {
            target = 0;
        }
        searchScroll.target = Math.max(0, target);
        if (snap) searchScroll.snap(searchScroll.target);
    }

    /** After every move: springs, the hero's picture, a click, and TalkBack's description. */
    private void moved() {
        aimRow(false);
        if (zone == Z_ROW) scheduleHero();
        if (zone == Z_CHIP) PlaybackPrefetch.warm(getContext(), chipEntry());
        click.move();
        setContentDescription(describe());
        changed();
    }

    private String describe() {
        switch (zone) {
            case Z_ICONS: return new String[] {Ui.tr("Entradas", "Inputs"), Ui.tr("Todos os apps", "All apps"), Ui.tr("Configurações", "Settings")}[iconIndex];
            case Z_PILL: return Ui.tr("Buscar", "Search") + (query.isEmpty() ? "" : ": " + query);
            case Z_CHIP: return chipEntry() == null ? "" : Ui.tr("Continuar ", "Resume ") + chipEntry().title + ". " + optionsHint();
            case Z_KEYS: return keys.description();
            case Z_NEWS: return episodeNotices.isEmpty() ? "" : Ui.tr("Novo episódio de ", "New episode of ") + episodeNotices.get(0).entry.title + " " + episodeNotices.get(0).entry.code();
            case Z_LOGOS: return logoIndex < shelf.size() ? shelf.get(logoIndex).label : "";
            case Z_ROW: return focusedCard() == null ? "" : focusedCard().title + ", " + focusedCard().sub
                    + (removable(focusedCard()) ? ". " + optionsHint() : "");
            case Z_HERO: return focusedCard() == null ? "" : heroIndex == 0 ? focusedCard().primary : focusedCard().secondary;
            case Z_RESULTS: {
                Result res = focusedResult();
                if (res != null) return res.title.name + ", " + res.where;
                HomeApps.Item app = focusedApp();
                return app == null ? "" : app.label;
            }
            default: return "";
        }
    }

    private boolean hasRow() {
        return online && !row.isEmpty();
    }

    private void toRow() {
        zone = Z_ROW;
        if (mode != CONTENT) {
            setMode(CONTENT);
            rowIndex = 0;
            aimRow(true);
        }
        scheduleHero();
    }

    private void toLogos() {
        if (shelf.isEmpty()) return;
        zone = Z_LOGOS;
    }

    private void move(int dx, int dy) {
        if (editing) {
            moveEditing(dx, dy);
            return;
        }
        switch (zone) {
            case Z_ICONS:
                if (dx < 0 && iconIndex == 0) { zone = Z_PILL; break; }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, iconIndex + dx));
                    if (next == iconIndex) return;
                    iconIndex = next;
                } else if (dy > 0) {
                    if (mode == CONTENT) { zone = Z_HERO; heroIndex = 0; }
                    else if (mode == SEARCH || (online && Setup.hasKey())) zone = Z_PILL;
                    else if (online) zone = Z_SETUP;
                    else if (!shelf.isEmpty()) zone = Z_LOGOS;
                    else return;
                } else {
                    return;
                }
                break;
            case Z_PILL:
                if (dy < 0 || (dx > 0 && mode != REST)) { zone = Z_ICONS; iconIndex = 0; break; }
                if (dx != 0) return;
                if (mode == SEARCH) { zone = Z_KEYS; break; }
                if (mode == CONTENT) { zone = Z_HERO; heroIndex = 0; break; }
                if (chipEntry() != null) zone = Z_CHIP;
                else if (!episodeNotices.isEmpty()) zone = Z_NEWS;
                else if (hasRow()) toRow();
                else if (!shelf.isEmpty()) toLogos();
                else return;
                break;
            case Z_SETUP:
                if (dy < 0) { zone = Z_ICONS; iconIndex = 0; }
                else if (dy > 0 && !shelf.isEmpty()) toLogos();
                else return;
                break;
            case Z_CHIP:
                if (dy < 0) zone = Z_PILL;
                else if (dy > 0) { if (!episodeNotices.isEmpty()) zone = Z_NEWS; else if (hasRow()) toRow(); else if (!shelf.isEmpty()) toLogos(); else return; }
                else return;
                break;
            case Z_NEWS:
                if (dy < 0) zone = chipEntry() == null ? Z_PILL : Z_CHIP;
                else if (dy > 0) {
                    if (hasRow()) { toRow(); for (int i = 0; i < row.size(); i++) if (row.get(i).kind == RELEASE) { rowIndex = i; aimRow(true); break; } }
                    else if (!shelf.isEmpty()) toLogos();
                } else return;
                break;
            case Z_HERO:
                if (dx != 0) {
                    Card card = focusedCard();
                    int last = card == null || card.secondary.equals(card.primary) ? 0 : 1;
                    int next = Math.max(0, Math.min(last, heroIndex + dx));
                    if (next == heroIndex) return;
                    heroIndex = next;
                } else if (dy < 0) {
                    zone = Z_PILL;
                } else {
                    zone = Z_ROW;
                }
                break;
            case Z_ROW:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(row.size() - 1, rowIndex + dx));
                    if (next == rowIndex) return;
                    rowIndex = next;
                    heroTitleFor = null;
                } else if (dy < 0) {
                    zone = Z_HERO;
                    heroIndex = 0;
                } else {
                    if (shelf.isEmpty()) return;
                    toLogos();
                }
                break;
            case Z_LOGOS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, logoIndex + dx));
                    if (next == logoIndex) return;
                    logoIndex = next;
                } else if (dy < 0) {
                    if (mode == CONTENT && hasRow()) zone = Z_ROW;
                    else if (!online || !Setup.hasKey()) zone = !online ? Z_ICONS : Z_SETUP;
                    else if (chipEntry() != null) zone = Z_CHIP;
                    else zone = Z_PILL;
                } else {
                    return;
                }
                break;
            case Z_KEYS:
                if (dy < 0) zone = Z_PILL;
                else if (dy > 0 || dx > 0) {
                    if (query.trim().isEmpty()) { if (recent.isEmpty()) return; zone = Z_RECENT; recentIndex = 0; }
                    else if (!sections.isEmpty()) { zone = Z_RESULTS; sectionIndex = 0; resultIndex = 0; }
                    else return;
                } else {
                    return;
                }
                break;
            case Z_RESULTS: {
                Section sec = sections.get(Math.min(sectionIndex, sections.size() - 1));
                if (dx != 0) {
                    if (dx < 0 && resultIndex == 0) { zone = Z_KEYS; break; }
                    int next = Math.max(0, Math.min(sec.size() - 1, resultIndex + dx));
                    if (next == resultIndex) return;
                    resultIndex = next;
                } else if (dy < 0) {
                    if (sectionIndex == 0) zone = Z_KEYS;
                    else { sectionIndex--; resultIndex = Math.min(resultIndex, sections.get(sectionIndex).size() - 1); }
                } else {
                    if (sectionIndex >= sections.size() - 1) return;
                    sectionIndex++;
                    resultIndex = Math.min(resultIndex, sections.get(sectionIndex).size() - 1);
                }
                break;
            }
            case Z_RECENT:
                if (dx < 0) { zone = Z_KEYS; }
                else if (dy != 0) {
                    int next = Math.max(0, Math.min(recent.size() - 1, recentIndex + dy));
                    if (dy < 0 && recentIndex == 0) { zone = Z_PILL; break; }
                    if (next == recentIndex) return;
                    recentIndex = next;
                } else {
                    return;
                }
                break;
            default:
                return;
        }
        moved();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (opening != null && opening.playing()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) opening.skip();
            return true;
        }
        wakeFrames();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (overlayKind != OV_NONE) return overlayKey(e);
        if (mode == SEARCH && zone == Z_KEYS && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (keys.handle(e)) {
                if (down) { click.move(); setContentDescription(describe()); }
                return true;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_SEARCH:
                if (down && e.getRepeatCount() == 0) startSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here (the release of an OK pressed inside another
                // app opens nothing). Held: the long-press actions.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        handler.postDelayed(longPress, 650);
                    } else if (e.isLongPress() && okDown) {
                        handler.removeCallbacks(longPress);
                        longPress.run();
                    }
                } else {
                    handler.removeCallbacks(longPress);
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) onLongPress();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (down && mode == SEARCH && !query.isEmpty()) setQuery(query.substring(0, query.length() - 1));
                return mode == SEARCH;
            default:
                int ch = e.getUnicodeChar();
                if (down && ch > 0 && !Character.isISOControl(ch) && (Character.isLetterOrDigit(ch) || ch == ' ')) {
                    if (mode != SEARCH) startSearch();
                    setQuery(query + Character.toString((char) ch).toLowerCase(Locale.ROOT));
                    return true;
                }
                return false;
        }
    }

    private void back() {
        if (editing) {
            finishEditing();
        } else if (mode == SEARCH) {
            setQuery("");
            setMode(REST);
            zone = Z_PILL;
        } else if (mode == CONTENT) {
            setMode(REST);
            zone = Z_PILL;
        } else if (zone != Z_PILL && Setup.hasKey() && online) {
            zone = Z_PILL;
        } else {
            return;
        }
        click.move();
        aimRow(false);
        changed();
    }

    private void activate() {
        if (editing) {
            finishEditing();
            return;
        }
        switch (zone) {
            case Z_ICONS:
                click.open();
                if (iconIndex == 0) openInputs();
                else if (iconIndex == 1) openAllApps();
                else start(new Intent(activity, SettingsActivity.class));
                return;
            case Z_PILL:
                if (mode == SEARCH) { if (voice) startVoice(); }
                else startSearch();
                return;
            case Z_SETUP:
                click.open();
                start(new Intent(activity, SettingsActivity.class).putExtra("section", "setup"));
                return;
            case Z_CHIP:
                if (chipEntry() != null) { click.open(); resume(chipEntry()); }
                return;
            case Z_NEWS:
                if (!episodeNotices.isEmpty()) {
                    for (Card card : row) if (card.kind == RELEASE) { click.open(); details(card); break; }
                }
                return;
            case Z_HERO: {
                Card c = focusedCard();
                if (c == null) return;
                click.open();
                if (heroIndex == 0) primary(c);
                else details(c);
                return;
            }
            case Z_ROW: {
                Card c = focusedCard();
                if (c == null) return;
                click.open();
                if (c.kind == CW) resume(c.entry);
                else details(c);
                return;
            }
            case Z_LOGOS:
                if (logoIndex < shelf.size()) {
                    click.open();
                    HomeApps.Item it = shelf.get(logoIndex);
                    float cx = (logoX[logoIndex] + logoW[logoIndex] / 2) * k;
                    if (!HomeApps.open(activity, it, this, Math.round(cx), Math.round(LOGO_CY * k), 1, 1)) {
                        showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
                    }
                }
                return;
            case Z_RESULTS: {
                Result res = focusedResult();
                HomeApps.Item app = focusedApp();
                if (res != null) {
                    click.open();
                    remember(query);
                    openTitle(res.title.id, res.title.kind, res.title.name, null);
                } else if (app != null) {
                    click.open();
                    remember(query);
                    if (!HomeApps.open(activity, app, this, w / 2, h / 2, 1, 1)) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + app.label + ".");
                }
                return;
            }
            case Z_RECENT:
                if (recentIndex < recent.size()) {
                    setQuery(recent.get(recentIndex));
                    zone = Z_KEYS;
                    changed();
                }
                return;
            default:
        }
    }

    private void onLongPress() {
        if (zone == Z_NEWS && !episodeNotices.isEmpty()) {
            NewEpisodes.dismiss(getContext(), episodeNotices.get(0));
            episodeNotices = NewEpisodes.cached(getContext(), NewEpisodes.followed(getContext(), entries), System.currentTimeMillis());
            buildRow(); changed();
        } else if (zone == Z_ROW && focusedCard() != null) {
            openCardMenu(focusedCard());
        } else if (zone == Z_CHIP && !row.isEmpty() && row.get(0).kind == CW) {
            openCardMenu(row.get(0));
        } else if (zone == Z_LOGOS && !shelf.isEmpty() && !editing) {
            editing = true;
            setMode(REST);
            click.open();
            changed();
        } else if (zone == Z_ICONS && iconIndex == 2) {
            openQuickPanel();
        }
    }

    /** The quick panel (sleep timer, picture, headphones), when the player part of the app provides it. */
    private void openQuickPanel() {
        QuickPanel.show(activity);
    }

    void openRemoteInputs() {
        if (opening != null) opening.skip();
        if (inputsReady && running) openInputs(); else pendingInputs = true;
    }

    void openAppEditor() {
        if (opening != null) opening.skip();
        if (!inputsReady || !running) { pendingAppEdit = true; return; }
        setMode(REST);
        if (shelf.isEmpty()) { openAllApps(); return; }
        zone = Z_LOGOS;
        editing = true;
        aimRow(false);
        changed();
    }

    // MARK: - Editing the app row in place

    private void moveEditing(int dx, int dy) {
        if (dx != 0) {
            int next = logoIndex + dx;
            if (next < 0 || next >= shelf.size()) return;
            List<HomeApps.Item> list = new ArrayList<>(shelf);
            HomeApps.Item it = list.remove(logoIndex);
            list.add(next, it);
            shelf = list;
            logoIndex = next;
            layoutLogos();
            whiteLogos.clear();
        } else if (dy > 0) {
            HomeApps.Item it = shelf.get(logoIndex);
            List<HomeApps.Item> list = new ArrayList<>(shelf);
            list.remove(logoIndex);
            shelf = list;
            logoIndex = Math.max(0, Math.min(logoIndex, shelf.size() - 1));
            layoutLogos();
            whiteLogos.clear();
            saveShelf();
            showToast(it.label + Ui.tr(" saiu do Início. Adicione de volta em Todos os apps.", " is off Home. Add it back from All apps."));
            if (shelf.isEmpty()) finishEditing();
        } else {
            return;
        }
        click.move();
        changed();
    }

    private void finishEditing() {
        editing = false;
        saveShelf();
        changed();
    }

    private void saveShelf() {
        final List<String> packages = new ArrayList<>();
        for (HomeApps.Item it : shelf) packages.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> HomeApps.saveShelf(c, packages));
    }

    // MARK: - Overlays

    private void openOverlay(int kind) {
        overlayKind = kind;
        overlayT.target = 1;
        overlay.setVisibility(VISIBLE);
        if (kind == OV_MENU && !menu.isEmpty()) setContentDescription(menu.get(menuIndex).label);
        aimRow(false);
        changed();
    }

    private void closeOverlay() {
        if (overlayKind == OV_NONE) return;
        overlayKind = OV_NONE;
        menuCard = null;
        overlayT.target = 0;
        overlayT.snap(0);
        setContentDescription(describe());
        aimRow(false);
        changed();
    }

    private void openInputs() {
        menu.clear();
        menuCard = null;
        Intent live = HomeApps.liveTv(getContext());
        if (live != null) menu.add(new MenuItem(Ui.tr("TV ao vivo", "Live TV"), null, false, () -> start(live)));
        for (HomeApps.Item it : inputs) {
            if (it.kind != HomeApps.INPUT) continue;
            final HomeApps.Item input = it;
            menu.add(new MenuItem(it.label, null, !it.connected, () -> {
                if (!HomeApps.open(activity, input, this, w / 2, h / 2, 1, 1)) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + input.label + ".");
            }));
        }
        setMode(REST);
        menuIndex = 0;
        openOverlay(OV_MENU);
    }

    private void openCardMenu(Card card) {
        menu.clear();
        menuCard = card;
        if (card.kind == CW) {
            ContinueWatching.Entry e = card.entry;
            String code = e.episode > 0 ? " S" + e.season + " E" + e.episode : "";
            menu.add(new MenuItem((e.external() ? card.primary : Ui.tr("Continuar", "Resume") + code), PLAY_ICON, false, () -> resume(e)));
            menu.add(new MenuItem("tv".equals(e.kind) ? Ui.tr("Ir para a série", "Go to the show") : Ui.tr("Detalhes", "Details"),
                    SHOW_ICON, false, () -> details(card)));
            addEntryActions(card);
        } else {
            menu.add(new MenuItem(card.primary, PLAY_ICON, false, () -> primary(card)));
            if (card.kind != RELEASE) menu.add(new MenuItem(Ui.tr("Detalhes", "Details"), SHOW_ICON, false, () -> details(card)));
            addEntryActions(card);
        }
        menuIndex = 0;
        click.open();
        openOverlay(OV_MENU);
    }

    private boolean removable(Card card) {
        return card.entry != null && (card.kind == RELEASE || card.kind == CW
                && card.section.equals(Ui.tr("Continuar assistindo", "Continue Watching")));
    }

    /** Hide the title locally without removing it from the account's library. */
    private void addEntryActions(Card card) {
        if (!removable(card)) return;
        String label = card.kind == CW ? Ui.tr("Remover de Continuar assistindo", "Remove from Continue Watching")
                : Ui.tr("Remover das suas séries", "Remove from Your series");
        menu.add(new MenuItem(label, CLOSE_ICON, false, () -> {
            watchGeneration++;
            ContinueWatching.remove(getContext(), card.entry);
            entries = ContinueWatching.cached(getContext());
            List<ContinueWatching.Entry> followed = NewEpisodes.followed(getContext(), entries);
            episodeNotices = NewEpisodes.cached(getContext(), followed, System.currentTimeMillis());
            waitingSeries = NewEpisodes.retained(getContext(), followed, System.currentTimeMillis());
            buildRow();
            if (zone == Z_CHIP && chipEntry() == null || zone == Z_ROW && row.isEmpty()) zone = Z_PILL;
            showToast(Ui.tr("Título removido.", "Title removed."));
            setContentDescription(describe()); changed();
        }));
    }

    private void openAllApps() {
        allApps = allAppsList();
        allIndex = allApps.isEmpty() ? -1 : 0;
        allScroll.snap(0);
        openOverlay(OV_ALL);
    }

    /** Everything that opens: the Home apps and the others, Live TV and the TV's settings, by name. */
    private List<HomeApps.Item> allAppsList() {
        List<HomeApps.Item> list = new ArrayList<>(shelf);
        list.addAll(others);
        if (HomeApps.liveTv(getContext()) != null) list.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV ao vivo", "Live TV"), "live_tv"));
        boolean settings = false;
        for (HomeApps.Item it : others) settings |= "com.android.tv.settings".equals(it.pkg) || "com.android.settings".equals(it.pkg);
        if (!settings) list.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("Configurações da TV", "TV Settings"), "settings"));
        java.util.Collections.sort(list, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return list;
    }

    private boolean overlayKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) {
                closeOverlay();
                click.move();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && e.getRepeatCount() == 0) {
                okDown = true;
                if (overlayKind == OV_ALL) handler.postDelayed(allLongPress, 650);
            } else if (down && e.isLongPress() && okDown && overlayKind == OV_ALL) {
                handler.removeCallbacks(allLongPress);
                allLongPress.run();
            } else if (!down) {
                handler.removeCallbacks(allLongPress);
                if (okDown && !e.isCanceled()) overlayActivate();
                okDown = false;
            }
            return true;
        }
        if (!down) return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        if (overlayKind == OV_MENU) {
            if (dy == 0) return true;
            int next = Math.max(0, Math.min(menu.size() - 1, menuIndex + dy));
            if (next != menuIndex) {
                menuIndex = next;
                setContentDescription(menu.get(menuIndex).label);
                click.move();
                overlay.invalidate();
            }
            return true;
        }
        int n = allApps.size();
        int next = allIndex;
        if (allIndex < 0) {
            if (dy > 0 && n > 0) next = 0;
        } else if (dx != 0) {
            int candidate = allIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / 4 == allIndex / 4) next = candidate;
        } else {
            int candidate = allIndex + dy * 4;
            if (candidate < 0) next = -1;
            else if (candidate < n) next = candidate;
            else if (allIndex / 4 < (n - 1) / 4) next = n - 1;
        }
        if (next != allIndex) {
            allIndex = next;
            int rowN = Math.max(0, allIndex) / 4;
            int rows = (n + 3) / 4;
            float target = allScroll.target;
            if (rowN < target) target = rowN;
            if (rowN > target + 7) target = rowN - 7;
            allScroll.target = Math.max(0, Math.min(Math.max(0, rows - 8), target));
            click.move();
            changed();
        }
        return true;
    }

    private final Runnable allLongPress = this::allAppsLongPress;

    /** Long-press in All apps: puts the app on Home, or takes it off. */
    private void allAppsLongPress() {
        if (!okDown || overlayKind != OV_ALL || allIndex < 0 || allIndex >= allApps.size()) return;
        okDown = false;
        HomeApps.Item it = allApps.get(allIndex);
        if (it.kind != HomeApps.APP || it.pkg == null) return;
        List<String> packages = new ArrayList<>();
        for (HomeApps.Item s : shelf) packages.add(s.pkg);
        boolean on = packages.contains(it.pkg);
        if (on) packages.remove(it.pkg); else packages.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            HomeApps.saveShelf(c, packages);
            handler.post(this::reload);
        });
        showToast(it.label + (on ? Ui.tr(" saiu do Início.", " is off Home.") : Ui.tr(" está no Início.", " is on Home.")));
        click.open();
    }

    private void overlayActivate() {
        if (overlayKind == OV_MENU) {
            if (menuIndex >= menu.size()) return;
            Runnable action = menu.get(menuIndex).action;
            click.open();
            closeOverlay();
            action.run();
            return;
        }
        if (allIndex < 0) {
            click.open();
            closeOverlay();
            start(new Intent(activity, AppsActivity.class));
            return;
        }
        if (allIndex >= allApps.size()) return;
        HomeApps.Item it = allApps.get(allIndex);
        click.open();
        closeOverlay();
        if (!HomeApps.open(activity, it, this, w / 2, h / 2, 1, 1)) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Actions

    private void start(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            showToast(Ui.tr("Não deu para abrir.", "Couldn't open that."));
        }
    }

    /** Resume: the external app's own link, or our player at the saved position. */
    private void resume(ContinueWatching.Entry entry) {
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            Intent intent = null;
            try {
                if (entry.external()) intent = ContinueWatching.externalIntent(c, entry);
                else {
                    ContinueWatching.resolve(entry);
                    if (entry.tmdb > 0) intent = ContinueWatching.internalIntent(c, entry);
                }
            } catch (Exception ignored) {
            }
            final Intent ready = intent;
            handler.post(() -> {
                if (ready != null) start(ready);
                else showToast(Ui.tr("Não foi possível abrir este título. Tente de novo.", "Couldn't open this title. Try again."));
            });
        });
    }

    /** The hero's main button. */
    private void primary(Card card) {
        if (card.kind == RELEASE) { details(card); return; }
        if (card.kind == CW) {
            resume(card.entry);
            return;
        }
        Recs.Pick p = card.pick;
        if (p == null) return;
        if (StremioLinks.PACKAGE.equals(p.pkg)) { details(card); return; }
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            Intent intent = null;
            try {
                TitleSearch.Title t = new TitleSearch.Title();
                t.id = p.id;
                t.kind = p.kind;
                t.name = p.title;
                t.year = "";
                WatchOptions.Result result = WatchOptions.load(c, t);
                for (WatchOptions.Option o : result.options) if (o.service.pkg.equals(p.pkg)) intent = WatchOptions.intent(c, o);
            } catch (Exception ignored) {
            }
            if (intent == null && p.pkg != null) intent = HomeApps.launchIntent(c.getPackageManager(), p.pkg);
            final Intent ready = intent;
            handler.post(() -> {
                if (ready != null) start(ready.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else details(card);
            });
        });
    }

    /** The title page (episodes, sources, where it plays). */
    private void details(Card card) {
        if (card.kind == PICK && card.pick != null) {
            openTitle(card.pick.id, card.pick.kind, card.pick.title, null);
            return;
        }
        ContinueWatching.Entry e = card.entry;
        if (e == null) return;
        final Context c = getContext().getApplicationContext();
        WORK.execute(() -> {
            try {
                ContinueWatching.resolve(e);
            } catch (Exception ignored) {
            }
            if (e.tmdb <= 0 && e.external()) {
                try {
                    String name = normalize(e.title);
                    for (TitleSearch.Title t : TitleSearch.search(e.title)) {
                        if (!normalize(t.name).equals(name)) continue;
                        if (e.tmdb <= 0 || t.kind.equals(e.kind)) { e.tmdb = t.id; e.kind = t.kind; }
                    }
                } catch (Exception ignored) {
                }
            }
            handler.post(() -> {
                if (e.tmdb > 0 && card.kind == RELEASE) start(new Intent(activity, SourcesActivity.class)
                        .putExtra("title_id", e.tmdb).putExtra("title_kind", e.kind).putExtra("title_name", e.title)
                        .putExtra("imdb", e.imdb).putExtra("selected_video", e.video));
                else if (e.tmdb > 0) openTitle(e.tmdb, e.kind, e.title, e.imdb);
                else if (e.external()) resume(e);
                else showToast(Ui.tr("Não foi possível abrir este título. Tente de novo.", "Couldn't open this title. Try again."));
            });
        });
    }

    private void openTitle(int id, String kind, String name, String imdb) {
        if (id <= 0) return;
        if ("person".equals(kind)) { start(new Intent(activity, PersonActivity.class).putExtra("person_id",id).putExtra("person_name",name)); return; }
        Intent intent = new Intent(activity, SourcesActivity.class).putExtra("title_id", id).putExtra("title_kind", kind)
                .putExtra("title_name", name);
        if (imdb != null && !imdb.isEmpty()) intent.putExtra("imdb", imdb);
        start(intent);
    }

    // MARK: - Search

    private void startSearch() {
        if (!online) {
            showToast(Ui.tr("Sem internet: a busca volta quando a conexão voltar.", "No internet: search comes back when you're online."));
            return;
        }
        setMode(SEARCH);
        zone = Z_KEYS;
        keys.reset();
        loadRecent();
        click.open();
        changed();
    }

    private void startVoice() {
        try {
            activity.startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Fale um filme, série ou app", "Say a movie, show or app")), HomeActivity.VOICE);
        } catch (RuntimeException e) {
            showToast(Ui.tr("A busca por voz não está disponível.", "Voice search isn't available."));
        }
    }

    /** What the voice search heard (HomeActivity passes it on). */
    void voiceResult(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (mode != SEARCH) startSearch();
        setQuery(text.trim().toLowerCase(Locale.ROOT));
    }

    private void setQuery(String text) {
        if (text.length() > 60) return;
        query = text;
        handler.removeCallbacks(runSearch);
        searchToken++;
        matchApps();
        if (query.trim().length() < 2) {
            results.clear();
            searching = false;
            searchError = "";
        } else {
            searching = true;
            handler.postDelayed(runSearch, 350);
        }
        layoutSections();
        if (zone == Z_RESULTS && sections.isEmpty()) zone = Z_KEYS;
        if (zone == Z_RECENT && !query.isEmpty()) zone = Z_KEYS;
        aimRow(false);
        changed();
    }

    private Result focusedResult() {
        if (sectionIndex >= sections.size()) return null;
        Section sec = sections.get(sectionIndex);
        return sec.apps || resultIndex >= sec.titles.size() ? null : sec.titles.get(resultIndex);
    }

    private HomeApps.Item focusedApp() {
        if (sectionIndex >= sections.size()) return null;
        Section sec = sections.get(sectionIndex);
        return !sec.apps || resultIndex >= sec.items.size() ? null : sec.items.get(resultIndex);
    }

    /**
     * Results in rows: shows and movies (the row with the best match first), then apps; apps come first when the
     * query is the start of an app's name ("net" → Netflix). Focus stays on the same title when rows change.
     */
    private void layoutSections() {
        Result keepResult = focusedResult();
        HomeApps.Item keepApp = focusedApp();
        sections.clear();
        Section shows = new Section(Ui.tr("Séries", "Shows"), false), movies = new Section(Ui.tr("Filmes", "Movies"), false), people = new Section(Ui.tr("Pessoas", "People"), false);
        for (Result res : results) {
            Section sec = "person".equals(res.title.kind) ? people : "tv".equals(res.title.kind) ? shows : movies;
            if (sec.titles.size() < ROW_RESULTS) sec.titles.add(res);
        }
        Section apps = new Section(Ui.tr("Apps", "Apps"), true);
        apps.items.addAll(appResults);
        String q = normalize(query.trim());
        boolean appFirst = false;
        for (HomeApps.Item it : appResults) appFirst |= !q.isEmpty() && normalize(it.label).startsWith(q);
        if (appFirst && apps.size() > 0) sections.add(apps);
        boolean showsFirst = !shows.titles.isEmpty() && (movies.titles.isEmpty() || shows.titles.get(0).score >= movies.titles.get(0).score);
        for (Section sec : showsFirst ? new Section[] {shows, movies} : new Section[] {movies, shows}) if (sec.size() > 0) sections.add(sec);
        if (people.size() > 0) sections.add(people);
        if (!appFirst && apps.size() > 0) sections.add(apps);
        float top = RESULTS_TOP;
        for (Section sec : sections) {
            sec.top = top;
            top += sec.height();
        }
        sectionIndex = 0;
        resultIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            int at = sec.apps ? sec.items.indexOf(keepApp) : sec.titles.indexOf(keepResult);
            if (at >= 0) { sectionIndex = i; resultIndex = at; }
        }
    }

    private void matchApps() {
        appResults.clear();
        String q = normalize(query.trim());
        if (q.isEmpty()) return;
        List<HomeApps.Item> pool = new ArrayList<>(shelf);
        pool.addAll(others);
        for (HomeApps.Item it : pool) {
            if (appResults.size() >= 6) break;
            String name = normalize(it.label);
            boolean match = name.startsWith(q);
            for (String word : it.label.toLowerCase(Locale.ROOT).split("\\s+")) match |= normalize(word).startsWith(q);
            if (match) appResults.add(it);
        }
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** TMDB search, then where each of the top four plays: a service you have first, else here (Stremio add-ons). */
    private void search() {
        final String q = query.trim(); final int token = searchToken;
        WORK.execute(() -> {
            List<TitleSearch.Title> titles = new ArrayList<>(); String error = "";
            try { if (token == searchToken) titles = TitleSearch.searchBoth(q); }
            catch (Exception failure) { error = Setup.hasKey() ? Ui.tr("Não foi possível buscar. Tente novamente.", "Cannot search. Try again.")
                    : Ui.tr("Configure sua chave TMDB em Ajustes.", "Set your TMDB key in Settings."); }
            titles.sort((a,b) -> Double.compare(rank(b,q),rank(a,q)));
            List<TitleSearch.Title> ready = titles; String message = error;
            handler.post(() -> {
                if (token != searchToken) return;
                searching = false; searchError = message; results.clear();
                appendSearch(ready, token, q);
                if (zone == Z_RESULTS && sections.isEmpty()) zone = Z_KEYS;
            });
            if (token != searchToken) return;
            WORK.execute(() -> {
                if (token != searchToken) return;
                try { List<TitleSearch.Title> extra = AddonCatalogs.search(getContext(),q);
                    handler.post(() -> { if (token == searchToken) appendSearch(extra,token,q); });
                } catch (Exception ignored) {}
            });
            WORK.execute(() -> {
                if (token != searchToken) return;
                try {
                    List<TitlePage.Person> found = People.search(q);
                    handler.post(() -> {
                        if (token != searchToken) return;
                        for (TitlePage.Person p : found) {
                            Result res = new Result(); TitleSearch.Title t = new TitleSearch.Title();
                            t.id=p.id;t.kind="person";t.name=p.name;t.poster=p.photo;t.backdrop="";t.year="";
                            res.title=t;res.key="person:"+p.id;res.where=p.role;res.score=rank(t,q);
                            String photo=p.photo;res.source=()->photo;fitResult(res);results.add(res);
                        }
                        layoutSections();aimRow(false);changed();
                    });
                } catch (Exception ignored) {}
            });
        });
    }
    private void appendSearch(List<TitleSearch.Title> titles, int token, String query) {
        java.util.Set<String> seen = new java.util.HashSet<>(); for (Result r : results) seen.add(r.key);
        for (TitleSearch.Title t : titles) {
            String key="search:"+t.kind+":"+t.id; if (!seen.add(key) || results.size()>=48) continue;
            Result res=new Result();res.title=t;res.key=key;res.where=t.year;res.score=rank(t,query);
            String picture=t.backdrop.isEmpty()?t.poster:t.backdrop;res.source=()->picture;fitResult(res);results.add(res);
            WHERE.execute(() -> {
                if (token!=searchToken) return;
                Result provider=where(getContext(),t);
                handler.post(() -> {
                    if(token!=searchToken)return;
                    res.where=provider.where;res.pkg=provider.pkg;res.score=rank(t,query)+(!res.pkg.isEmpty()&&!res.pkg.equals(StremioLinks.PACKAGE)?.5:0);
                    fitResult(res);
                    if (zone!=Z_RESULTS) {results.sort((a,b)->Double.compare(b.score,a.score));layoutSections();}
                    changed();
                });
            });
        }
        if (zone!=Z_RESULTS) results.sort((a,b)->Double.compare(b.score,a.score));
        layoutSections();aimRow(false);changed();
    }

    /**
     * A title's place in the results. How well the name matches what you typed (exact, starts with it, a word starts
     * with it, contains it) and how popular it is right now (TMDB popularity and votes, log scale) both count, so a
     * famous "VisionQuest" beats four obscure films called exactly "Vision".
     */
    static double rank(TitleSearch.Title t, String typed) { return TitleSearch.rank(t, typed); }

    /** Where a title plays: a streaming service you have (My services), else here through your add-ons. */
    private static Result where(Context c, TitleSearch.Title t) {
        Result res = new Result();
        res.title = t;
        res.key = "search:" + t.kind + ":" + t.id;
        final String picture = t.backdrop.isEmpty() ? t.poster : t.backdrop;
        res.source = () -> picture;
        try {
            WatchOptions.Result options = WatchOptions.catalog(c, t);
            for (WatchOptions.Option o : options.options) {
                if (!o.offers.contains("flatrate") && !o.offers.contains("free") && !o.offers.contains("ads")) continue;
                if (!Prefs.serviceOn(c, o.service.pkg)) continue;
                res.where = Ui.tr("Abre no ", "Opens in ") + o.service.name;
                res.pkg = o.service.pkg;
                break;
            }
        } catch (Exception ignored) {
        }
        if (res.where.isEmpty()) {
            boolean here = AddonStore.connected(c);
            res.where = here ? Ui.tr("Toca aqui", "Plays here")
                    : join("tv".equals(t.kind) ? Ui.tr("Série", "Series") : Ui.tr("Filme", "Movie"), t.year);
            if (here) res.pkg = "com.stremio.one";
        }
        return res;
    }

    private void fitResult(Result res) {
        if (resultTitleP == null) return;
        res.titleFit = TextUtils.ellipsize(res.title.name, new TextPaint(resultTitleP), RESULT_W * k, TextUtils.TruncateAt.END).toString();
        res.whereFit = TextUtils.ellipsize(res.where, new TextPaint(whereP), (RESULT_W - 90) * k, TextUtils.TruncateAt.END).toString();
    }

    private void loadRecent() {
        recent.clear();
        try {
            JSONArray a = new JSONArray(ui.getString(Prefs.RECENT_SEARCHES, "[]"));
            for (int i = 0; i < a.length() && recent.size() < 5; i++) recent.add(a.getString(i));
        } catch (Exception ignored) {
        }
        fitRecent();
    }

    private void fitRecent() {
        recentFits.clear();
        if (recentP == null) return;
        TextPaint paint = new TextPaint(recentP);
        for (String value : recent) recentFits.add(TextUtils.ellipsize(value, paint,
                (RIGHT - RESULTS_LEFT - 56) * k, TextUtils.TruncateAt.END).toString());
    }

    private void remember(String q) {
        String clean = q.trim().toLowerCase(Locale.ROOT);
        if (clean.length() < 2) return;
        recent.remove(clean);
        recent.add(0, clean);
        while (recent.size() > 5) recent.remove(recent.size() - 1);
        fitRecent();
        ui.edit().putString(Prefs.RECENT_SEARCHES, new JSONArray(recent).toString()).apply();
    }

    // MARK: - Network

    private boolean hasNetwork() {
        try {
            Network n = connectivity.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : connectivity.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void setOnline(boolean value) {
        if (online == value) return;
        online = value;
        if (!online) {
            if (mode != REST) setMode(REST);
            if (zone != Z_ICONS && zone != Z_LOGOS) zone = shelf.isEmpty() ? Z_ICONS : Z_LOGOS;
        } else {
            data.refreshIfStale();
            recs.refreshIfStale();
            refreshRow();
            if (zone == Z_LOGOS || zone == Z_ICONS) zone = Z_PILL;
        }
        buildRow();
        changed();
    }

    // MARK: - Helpers

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
