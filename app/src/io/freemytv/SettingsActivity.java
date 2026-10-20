package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * LumaTV's settings (the app's icon, "All Apps", or the gear on the home screen): the sections on the left, the
 * chosen one on the right, as on the canvas boards. Moving through the list shows each section; Right (or OK) goes
 * into it, Left or Back comes out. Native views throughout (Kit), so the remote's focus moves the way Android's does.
 * Night or day look from Prefs.LOOK (and the sunset at the TV's location).
 * <p>
 * The LumaTV / Original switch runs in the main process (HomeMode); this screen only asks for it and follows the
 * state file. From ADB: {@code am start -n io.freemytv/.SettingsActivity --ez screensaver true} opens the
 * screensaver preview, {@code --ei section 4} a section.
 */
public class SettingsActivity extends TvActivity {
    static final String EXTRA_SECTION = "section";
    static final int HOME = 0, SERVICES = 1, SCREENSAVER = 2, APPS = 3, REMOTE = 4, PLAYBACK = 5, SUBTITLES = 6,
            STREMIO = 7, TVS = 8, TV_SETTINGS = 9, ABOUT = 10;

    private Kit.Theme t;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private LinearLayout navList;
    private final List<TextView> nav = new ArrayList<>();
    private ScrollView scroll;
    private LinearLayout pane;
    private int section = -1, generation;
    private Page page;
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private DreamView preview;
    private boolean resumed, backDown, hiddenByPause;
    private Runnable pendingSwitch;
    private long pendingAt;
    private String pendingMode;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            Page p = current();
            if (p != null) p.refresh();
            handler.postDelayed(this, 500);
        }
    };

    // MARK: - Activity

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        Setup.migrateConfig(this);
        TvSync.start(this);
        ModeJob.scheduleDaily(this);
        build(getIntent().getIntExtra(EXTRA_SECTION, HOME), null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Kit.Theme now = Kit.Theme.current(this);
        if (now.day != t.day) build(section, null);
        else if (hiddenByPause && current() != null) { current().shown(); current().refresh(); }
        hiddenByPause = false;
        handler.removeCallbacks(poll);
        handler.post(poll);
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_SECTION)) showSection(intent.getIntExtra(EXTRA_SECTION, HOME), true);
    }

    @Override protected void onPause() {
        closePreview();
        resumed = false;
        handler.removeCallbacks(poll);
        if (current() != null) current().hidden();
        hiddenByPause = true;
        TvSync.syncSoon(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        work.shutdownNow();
        super.onDestroy();
    }

    // MARK: - Layout: the section list and the pane

    /** (Re)builds everything in the current look, showing `show` and focusing the view tagged `focusTag` if any. */
    private void build(int show, String focusTag) {
        t = Kit.Theme.current(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.ground);
        getWindow().getDecorView().setBackgroundColor(t.ground);

        LinearLayout side = Kit.row(this, LinearLayout.VERTICAL);
        side.setBackgroundColor(t.nav);
        side.setPadding(px(96), px(88), px(40), 0);
        root.addView(side, new FrameLayout.LayoutParams(px(600), ViewGroup.LayoutParams.MATCH_PARENT));
        View line = new View(this);
        line.setBackgroundColor(t.navLine);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, px(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = px(600) - Math.max(1, px(1));
        root.addView(line, lp);

        TextView heading = Kit.text(this, Ui.tr("Ajustes", "Settings"), 26, Ui.medium, t.ink3);
        LinearLayout.LayoutParams hp = Kit.wrap();
        hp.leftMargin = px(24);
        hp.bottomMargin = px(32);
        side.addView(heading, hp);
        navList = Kit.row(this, LinearLayout.VERTICAL);
        side.addView(navList, Kit.fill());
        nav.clear();
        String[] labels = {Ui.tr("Início", "Home screen"), Ui.tr("Meus serviços", "My services"),
                Ui.tr("Tela de descanso", "Screensaver"), Ui.tr("Apps no Início", "Apps on Home"),
                Ui.tr("Botões do controle", "Remote buttons"), Ui.tr("Reprodução", "Playback"),
                Ui.tr("Legendas e áudio", "Subtitles & audio"), Ui.tr("Conta Stremio", "Stremio account"),
                Ui.tr("Suas TVs", "Your TVs"), Ui.tr("Ajustes da TV", "TV settings"), Ui.tr("Sobre", "About")};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = Kit.line(Kit.text(this, labels[i], 28, Ui.regular, Kit.states(t.navInk, t.onFocus)));
            item.setDuplicateParentStateEnabled(false);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(px(24), 0, px(24), 0);
            item.setFocusable(true);
            item.setClickable(true);
            item.setTag("nav-" + i);
            item.setOnClickListener(v -> { if (section != index) showSection(index, false); focusPane(); });
            item.setOnFocusChangeListener((v, focused) -> {
                styleNav();
                if (!focused || index == section) return;
                if (pendingSwitch != null) handler.removeCallbacks(pendingSwitch);
                pendingSwitch = () -> { if (nav.get(index).isFocused()) showSection(index, false); };
                handler.postDelayed(pendingSwitch, 140);
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(68));
            ip.bottomMargin = px(4);
            navList.addView(item, ip);
            nav.add(item);
        }

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(120));
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(88), 0, px(64));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(px(1184), ViewGroup.LayoutParams.MATCH_PARENT);
        sp.leftMargin = px(668);
        root.addView(scroll, sp);
        pane = Kit.row(this, LinearLayout.VERTICAL);
        scroll.addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (preview == null) setContentView(root);
        rootView = root;
        stack.clear();
        if (page != null) page.hidden();
        page = null;
        section = -1;
        showSection(Math.max(0, Math.min(ABOUT, show)), false);
        View target = focusTag == null ? null : root.findViewWithTag(focusTag);
        if (target != null) target.requestFocus(); else nav.get(section).requestFocus();
    }

    private FrameLayout rootView;

    private void styleNav() {
        for (int i = 0; i < nav.size(); i++) {
            TextView item = nav.get(i);
            boolean on = i == section, focused = item.isFocused();
            item.setTypeface(on || focused ? Ui.semibold : Ui.regular);
            item.setTextColor(Kit.states(on ? t.ink : t.navInk, t.onFocus));
            item.setBackground(Kit.fill(this, on ? t.current : 0x00000000, t.focus, 18));
        }
    }

    private void showSection(int s, boolean focusPane) {
        if (pendingSwitch != null) handler.removeCallbacks(pendingSwitch);
        while (!stack.isEmpty()) stack.pop().hidden();
        if (page != null) page.hidden();
        section = s;
        generation++;
        page = create(s);
        setPane(page);
        styleNav();
        page.shown();
        page.refresh();
        if (focusPane) focusPane();
    }

    private Page create(int s) {
        switch (s) {
            case SERVICES: return servicesPage();
            case SCREENSAVER: return screensaverPage();
            case APPS: return appsPage();
            case REMOTE: return remotePage();
            case PLAYBACK: return playbackPage();
            case SUBTITLES: return subtitlesPage();
            case STREMIO: return stremioPage();
            case TVS: return tvsPage();
            case TV_SETTINGS: return tvSettingsPage();
            case ABOUT: return aboutPage();
            default: return homePage();
        }
    }

    private void setPane(Page p) {
        pane.removeAllViews();
        if (p.body.getParent() != null) ((ViewGroup) p.body.getParent()).removeView(p.body);
        pane.addView(p.body, Kit.fill());
        scroll.scrollTo(0, 0);
    }

    private Page current() {
        return stack.isEmpty() ? page : stack.peek();
    }

    /** A page over the section (a list to choose from, a code, the phone QR); Back returns to the row it came from. */
    private void push(Page sub) {
        Page under = current();
        if (under != null) {
            under.hidden();
            under.returnFocus = getCurrentFocus();
            under.scrollY = scroll.getScrollY();
        }
        stack.push(sub);
        setPane(sub);
        sub.shown();
        sub.refresh();
        focusPane();
    }

    private void pop() {
        if (stack.isEmpty()) return;
        stack.pop().hidden();
        Page under = current();
        setPane(under);
        under.shown();
        under.refresh();
        final int y = under.scrollY;
        final View back = under.returnFocus;
        scroll.post(() -> {
            scroll.scrollTo(0, y);
            if (back != null && back.isAttachedToWindow()) back.requestFocus(); else focusPane();
        });
    }

    private void focusPane() {
        View first = FocusFinder.getInstance().findNextFocus(scroll, null, View.FOCUS_DOWN);
        if (first != null) first.requestFocus();
    }

    private boolean inPane(View v) {
        for (ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) if (p == scroll) return true;
        return false;
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (e.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }
        Page cur = current();
        if (cur != null && cur.key(e)) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        View f = getCurrentFocus();
        boolean inNav = f != null && f.getParent() == navList, inPane = inPane(f);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                    return true;
                }
                if (backDown && !e.isCanceled()) {
                    backDown = false;
                    if (!stack.isEmpty()) pop();
                    else if (inPane) nav.get(section).requestFocus();
                    else finish();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && inPane && FocusFinder.getInstance().findNextFocus(scroll, f, View.FOCUS_LEFT) == null) {
                    nav.get(section).requestFocus();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && inNav) {
                    if (nav.indexOf(f) != section) showSection(nav.indexOf(f), false);
                    focusPane();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
                if (down && inPane && FocusFinder.getInstance().findNextFocus(scroll, f, dir) == null) {
                    // Nothing more to select that way: show the rest of the text (notices) instead of leaving.
                    if (scroll.canScrollVertically(dir == View.FOCUS_UP ? -1 : 1)) scroll.smoothScrollBy(0, dir == View.FOCUS_UP ? -px(300) : px(300));
                    return true;
                }
                if (down && inNav) {
                    int i = nav.indexOf(f);
                    if (dir == View.FOCUS_UP && i == 0 || dir == View.FOCUS_DOWN && i == nav.size() - 1) return true;
                }
                break;
            }
            default:
                break;
        }
        return super.dispatchKeyEvent(e);
    }

    // MARK: - Screensaver preview

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(rootView);
        View back = current() == null ? null : current().returnFocus;
        if (back != null && back.isAttachedToWindow()) back.requestFocus();
    }

    // MARK: - Pages

    /** One pane's content. refresh() runs every half second while it's showing. */
    abstract class Page {
        final LinearLayout body = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
        View returnFocus;
        int scrollY;
        void refresh() {}
        void shown() {}
        void hidden() {}
        boolean key(KeyEvent e) { return false; }
    }

    private Page homePage() {
        return new Page() {
            final Card free, original;
            final TextView hint;
            final LinearLayout chips;
            final Row picks, city, clock;
            String chipKey = "";

            {
                title(body, Ui.tr("Início", "Home screen"));
                LinearLayout cards = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                cards.setGravity(Gravity.TOP);
                cards.setBaselineAligned(false);
                free = new Card("LumaTV", "", "mode-free");
                original = new Card("Original", Ui.tr("O Início de fábrica da TV. Tudo volta como era.",
                        "The TV’s factory home screen. Everything comes back as it was."), "mode-original");
                LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                b.leftMargin = px(24);
                cards.addView(free.view, a);
                cards.addView(original.view, b);
                body.addView(cards, side(Kit.top(SettingsActivity.this, Kit.fill(), 48)));
                free.view.setOnClickListener(v -> choose(true));
                original.view.setOnClickListener(v -> choose(false));
                View.OnFocusChangeListener f = (v, focused) -> refreshHint();
                free.view.setOnFocusChangeListener(f);
                original.view.setOnFocusChangeListener(f);
                // The hint keeps its place (invisible) so moving between the cards never shifts the rows below.
                hint = small(body, " ");
                hint.setSingleLine(true);
                hint.setEllipsize(TextUtils.TruncateAt.END);
                hint.setVisibility(View.INVISIBLE);
                chips = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
                body.addView(chips, side(Kit.fill()));

                spacer(body, 16);
                segmented(body, Ui.tr("Aparência", "Look"), new String[]{Ui.tr("Seguir o pôr do sol", "Follow the sunset"),
                        Ui.tr("Sempre dia", "Always day"), Ui.tr("Sempre noite", "Always night")},
                        Arrays.asList("sunset", "day", "night").indexOf(Prefs.look(SettingsActivity.this)), "look", i -> {
                            String v = i == 1 ? "day" : i == 2 ? "night" : "sunset";
                            Prefs.get(SettingsActivity.this, Prefs.HOME_UI).edit().putString(Prefs.LOOK, v).commit();
                            if (Kit.Theme.current(SettingsActivity.this).day != t.day) handler.post(() -> build(HOME, "look-" + i));
                        });
                info(body, Ui.tr("Fundo", "Background"), Ui.tr("Paisagens", "Landscapes"));
                picks = row(body, Ui.tr("Sugestões", "Picks"), "", true, () -> push(setupPage()));
                city = row(body, Ui.tr("Cidade", "City"), "", true, () -> push(setupPage()));
                clock = row(body, Ui.tr("Relógio", "Clock"), "", true, () -> openSystem(Settings.ACTION_DATE_SETTINGS));
                footer(body);
            }

            @Override void refresh() {
                JSONObject s = HomeMode.state(SettingsActivity.this);
                boolean freeOn = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
                String busyText = s.optString("busy");
                boolean busy = !busyText.isEmpty() || pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000;
                if (!busyText.isEmpty()) pendingAt = 0;
                free.set(freeOn, Ui.tr("Sem anúncios. Busca em destaque.", "No ads. Easy search.") + memory(s));
                original.set(!freeOn, original.desc.getText().toString());
                String target = pendingMode != null ? pendingMode : freeOn ? HomeMode.ORIGINAL : HomeMode.FREE;
                String shown = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                free.busy(busy && HomeMode.FREE.equals(target) ? shown : null);
                original.busy(busy && HomeMode.ORIGINAL.equals(target) ? shown : null);
                if (!busy) pendingMode = null;

                String error = s.optString("error");
                String chip = "";
                if (busy && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                    chip = Ui.tr("Na TV: marque Sempre permitir e aperte OK.", "On the TV: tick Always allow and press OK.");
                } else if (!busy && !error.isEmpty()) {
                    chip = error;
                } else if (!busy && s.optBoolean("approvalMissing")) {
                    chip = Ui.tr("A TV esqueceu a permissão de depuração. Escolha o modo atual de novo para renová-la.",
                            "The TV forgot the debugging permission. Choose the current mode again to renew it.");
                }
                if (!chip.equals(chipKey)) {
                    chipKey = chip;
                    chips.removeAllViews();
                    if (!chip.isEmpty()) chips.addView(amberChip(chip), Kit.top(SettingsActivity.this, Kit.wrap(), 24));
                }
                refreshHint();

                JSONObject recs = Recs.status(SettingsActivity.this);
                String err = recs.optString("error");
                int count = recs.optInt("count");
                picks.value.setText(!Setup.hasKey() ? Ui.tr("Precisa da chave do TMDB", "Needs a TMDB key")
                        : !err.isEmpty() ? Ui.tr("Erro: ", "Error: ") + err
                        : count > 0 ? Ui.tr("3 novas a cada hora, dos seus apps", "3 new every hour, from your apps")
                        : Ui.tr("Buscando os títulos…", "Fetching titles…"));
                String c = Setup.city();
                city.value.setText(c.isEmpty() ? Ui.tr("Não definida", "Not set") : c);
                clock.value.setText(Ui.tr("Segue a TV · ", "Follow the TV · ") + Ui.clock(System.currentTimeMillis()));
            }

            void refreshHint() {
                boolean f = free.view.isFocused(), o = original.view.isFocused();
                boolean busy = free.busy || original.busy;
                if (!(f || o) || busy) {
                    hint.setVisibility(View.INVISIBLE);
                    return;
                }
                boolean onCurrent = f ? free.view.isActivated() : original.view.isActivated();
                hint.setText(onCurrent ? Ui.tr("Escolher de novo renova a permissão de depuração da TV e confere tudo.",
                        "Choosing it again renews the TV’s debugging permission and checks everything.")
                        : Ui.tr("A troca leva uns segundos; a tela pisca enquanto mede.",
                        "Switching takes a few seconds; the screen blinks while it measures."));
                hint.setVisibility(View.VISIBLE);
            }

            void choose(boolean wantFree) {
                if (free.busy || original.busy) return;
                JSONObject s = HomeMode.state(SettingsActivity.this);
                boolean freeOn = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
                if (wantFree == freeOn) {
                    if (!s.optBoolean("adbAuthorized")) return;   // never switched: nothing to renew
                    HomeMode.requestReapprove(SettingsActivity.this);
                    pendingMode = freeOn ? HomeMode.FREE : HomeMode.ORIGINAL;
                } else {
                    HomeMode.request(SettingsActivity.this, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingMode = wantFree ? HomeMode.FREE : HomeMode.ORIGINAL;
                }
                pendingAt = SystemClock.uptimeMillis();
                refresh();
            }
        };
    }

    /** "Uses 96 MB instead of 242 MB", from the measurements HomeMode saved. */
    private static String memory(JSONObject s) {
        JSONObject m = s.optJSONObject("metrics");
        JSONObject a = m == null ? null : m.optJSONObject(HomeMode.FREE), b = m == null ? null : m.optJSONObject(HomeMode.ORIGINAL);
        if (a == null || b == null || !a.has("homePssMB") || !b.has("homePssMB")) return "";
        return Ui.tr(" Usa " + a.optLong("homePssMB") + " MB em vez de " + b.optLong("homePssMB") + " MB.",
                " Uses " + a.optLong("homePssMB") + " MB instead of " + b.optLong("homePssMB") + " MB.");
    }

    /** Finish setup from the phone: the QR for PhoneLink's setup page. */
    private Page setupPage() {
        return new Page() {
            final ImageView qr = new ImageView(SettingsActivity.this);
            final TextView address, status;
            String seen;

            {
                title(body, Ui.tr("Configurar pelo telefone", "Set up from your phone"));
                LinearLayout card = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                FrameLayout box = new FrameLayout(SettingsActivity.this);
                box.setBackground(Kit.round(SettingsActivity.this, 0xFFFFFFFF, 16));
                int pad = px(20);
                box.setPadding(pad, pad, pad, pad);
                box.addView(qr, new FrameLayout.LayoutParams(px(290), px(290)));
                card.addView(box, new LinearLayout.LayoutParams(px(330), px(330)));
                LinearLayout words = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
                LinearLayout.LayoutParams wp = Kit.weight();
                wp.leftMargin = px(56);
                card.addView(words, wp);
                words.addView(Kit.text(SettingsActivity.this, Ui.tr("Escaneie com o telefone", "Scan with your phone"), 36, Ui.semibold, t.ink));
                TextView p = Kit.text(SettingsActivity.this, Ui.tr("Para colocar a chave do TMDB e a cidade. Ficam salvas nesta TV, criptografadas.",
                        "To add your TMDB key and city. They’re saved on this TV, encrypted."), 26, Ui.regular, t.ink2);
                p.setLineSpacing(0, 1.35f);
                words.addView(p, Kit.top(SettingsActivity.this, Kit.fill(), 14));
                address = Kit.text(SettingsActivity.this, "", 22, Ui.regular, t.ink3);
                words.addView(address, Kit.top(SettingsActivity.this, Kit.fill(), 20));
                status = Kit.text(SettingsActivity.this, Ui.tr("Esperando o telefone…", "Waiting for your phone…"), 24, Ui.medium, t.ink);
                words.addView(status, Kit.top(SettingsActivity.this, Kit.fill(), 28));
                body.addView(card, side(Kit.top(SettingsActivity.this, Kit.fill(), 48)));
                TextView done = Kit.button(SettingsActivity.this, t, Ui.tr("Pronto", "Done"), v -> pop());
                body.addView(done, side(Kit.top(SettingsActivity.this, Kit.wrap(), 48)));
                JSONObject v = Setup.publicView();
                seen = v.optBoolean("hasKey") + "|" + v.optString("city");
                load(() -> {
                    String url = PhoneLink.setupUrl(SettingsActivity.this);
                    return url == null ? null : new Object[]{url, Qr.bitmap(url, px(290))};
                }, r -> {
                    if (r == null) {
                        address.setText(Ui.tr("Conecte a TV à rede primeiro.", "Connect the TV to your network first."));
                        status.setVisibility(View.GONE);
                        return;
                    }
                    Object[] o = (Object[]) r;
                    address.setText(String.valueOf(o[0]).replaceFirst("\\?k=.*", ""));
                    qr.setImageBitmap((Bitmap) o[1]);
                });
            }

            @Override void refresh() {
                JSONObject v = Setup.publicView();
                String now = v.optBoolean("hasKey") + "|" + v.optString("city");
                if (!now.equals(seen)) {
                    status.setText(Ui.tr("Salvo", "Saved") + (v.optString("city").isEmpty() ? "" : " · " + v.optString("city")));
                }
            }
        };
    }

    private Page servicesPage() {
        return new Page() {
            {
                title(body, Ui.tr("Meus serviços", "My services"));
                para(body, Ui.tr("Títulos nestes abrem primeiro no app deles. O resto toca aqui quando dá.",
                        "Titles on these open in their app first. Everything else plays here when it can."));
                spacer(body, 32);
                load(() -> {
                    List<Object[]> out = new ArrayList<>();
                    for (WatchOptions.Service s : WatchOptions.SERVICES) {
                        if (HomeApps.launchIntent(getPackageManager(), s.pkg) == null) continue;
                        out.add(new Object[]{s, logo(s.key, s.pkg)});
                    }
                    return out;
                }, r -> {
                    @SuppressWarnings("unchecked") List<Object[]> list = (List<Object[]>) r;
                    if (list.isEmpty()) {
                        para(body, Ui.tr("Nenhum app de streaming instalado.", "No streaming apps are installed."));
                        return;
                    }
                    for (Object[] o : list) {
                        WatchOptions.Service s = (WatchOptions.Service) o[0];
                        serviceRow(body, s, (Bitmap) o[1]);
                    }
                });
            }
        };
    }

    /** The white logo HomeApps lifted from the app's TV art (cached on disk), or null. */
    private Bitmap logo(String key, String pkg) {
        File[] files = new File(getCacheDir(), "logos").listFiles();
        File best = null;
        for (int i = 0; files != null && i < files.length; i++) {
            String n = files[i].getName();
            if (!n.endsWith(".png") || !(n.startsWith(key + "-") || n.startsWith(pkg + "-"))) continue;
            if (best == null || files[i].lastModified() > best.lastModified()) best = files[i];
        }
        if (best == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        return BitmapFactory.decodeFile(best.getPath(), o);
    }

    private void serviceRow(LinearLayout parent, WatchOptions.Service s, Bitmap logo) {
        boolean on = Prefs.serviceOn(this, s.pkg);
        LinearLayout r = focusRow(100);
        r.setContentDescription(s.name);
        if (logo != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(logo);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setImageTintList(t.text());
            image.setImageTintMode(PorterDuff.Mode.SRC_IN);
            image.setDuplicateParentStateEnabled(true);
            image.setMaxWidth(px(300));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            r.addView(image, lp);
            r.addView(new View(this), Kit.weight());
        } else {
            r.addView(Kit.line(Kit.text(this, s.name, 30, Ui.semibold, t.text())), Kit.weight());
        }
        TextView state = Kit.text(this, onOff(on), 26, Ui.regular, t.text2());
        r.addView(state);
        Kit.Switch sw = new Kit.Switch(this, t, on);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(80), px(46));
        sp.leftMargin = px(18);
        r.addView(sw, sp);
        r.setOnClickListener(v -> {
            boolean next = !Prefs.serviceOn(this, s.pkg);
            Prefs.setServiceOn(this, s.pkg, next);
            sw.set(next);
            state.setText(onOff(next));
        });
        parent.addView(r, rowParams());
    }

    private Page screensaverPage() {
        return new Page() {
            final TextView use;
            {
                title(body, Ui.tr("Tela de descanso", "Screensaver"));
                para(body, Ui.tr("A paisagem, a hora e o tempo enquanto a TV descansa.",
                        "The landscape, the time and the weather while the TV rests."));
                spacer(body, 32);
                row(body, Ui.tr("Ver agora", "Preview"), "", true, this::open);
                row(body, Ui.tr("Quando começa", "When it starts"), Ui.tr("Ajustes da TV", "TV settings"), true,
                        () -> openSystem(Settings.ACTION_DREAM_SETTINGS));
                use = info(body, Ui.tr("Em uso", "In use"), "").value;
            }

            void open() {
                returnFocus = getCurrentFocus();
                showPreview();
            }

            @Override void refresh() {
                JSONObject s = HomeMode.state(SettingsActivity.this);
                boolean freeOn = HomeMode.FREE.equals(s.optString("mode"));
                use.setText(freeOn ? Ui.tr("Sim, no modo LumaTV", "Yes, in LumaTV mode") : Ui.tr("Só no modo LumaTV", "Only in LumaTV mode"));
            }
        };
    }

    private Page appsPage() {
        return new Page() {
            final Row edit;
            {
                title(body, Ui.tr("Apps no Início", "Apps on Home"));
                para(body, Ui.tr("Até 8 apps, na ordem que você quiser.", "Up to 8 apps, in the order you like."));
                spacer(body, 32);
                edit = row(body, Ui.tr("Editar apps", "Edit apps"), "", true,
                        () -> startActivity(new Intent(SettingsActivity.this, HomeActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(HomeActivity.EDIT_APPS, true)));
            }

            @Override void shown() {
                load(() -> HomeApps.shelfPackages(SettingsActivity.this).size(),
                        n -> edit.value.setText(Ui.tr(n + " apps", n + " apps")));
            }
        };
    }

    // MARK: Remote buttons

    private Page remotePage() {
        return new Page() {
            final LinearLayout list = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            final TextView mode;
            long armedAt;
            BroadcastReceiver receiver;

            {
                title(body, Ui.tr("Botões do controle", "Remote buttons"));
                para(body, Ui.tr("Escolha o que cada botão do controle abre.", "Choose what each button on your remote opens."));
                mode = para(body, "");
                spacer(body, 32);
                body.addView(list, Kit.fill());
                fill();
                TextView press = small(body, Ui.tr("Aperte um botão agora para encontrá-lo.", "Press a button now to find it."));
                ((LinearLayout.LayoutParams) press.getLayoutParams()).topMargin = px(48);
            }

            void fill() {
                list.removeAllViews();
                JSONObject map = Prefs.remoteMap(SettingsActivity.this);
                List<Integer> codes = new ArrayList<>();
                for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                    try { codes.add(Integer.parseInt(it.next())); } catch (NumberFormatException ignored) {}
                }
                java.util.Collections.sort(codes);
                if (codes.isEmpty()) {
                    small(list, Ui.tr("Nenhum botão ainda.", "No buttons yet."));
                    return;
                }
                for (int code : codes) keyRow(list, code, map.optString(String.valueOf(code)), () -> learned(code));
            }

            void learned(int code) {
                if (current() != this || code <= 0) return;
                SettingsActivity.this.<List<String[]>>load(() -> targets(code), options -> {
                    if (current() != this) return;
                    push(choice(RemoteKeys.label(code), options, Prefs.remoteMap(SettingsActivity.this).optString(String.valueOf(code)), target -> {
                        boolean first = Prefs.remoteMap(SettingsActivity.this).length() == 0;
                        RemoteKeys.map(SettingsActivity.this, code, target);
                        if (first && !target.isEmpty()) HomeMode.requestRemoteKeys(SettingsActivity.this);
                        fill();
                    }));
                });
            }

            @Override boolean key(KeyEvent e) {
                // Buttons that reach this screen directly (the service isn't on yet, or this TV lets them through).
                if (current() != this || !RemoteKeys.isLearnable(e.getKeyCode())) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) learned(e.getKeyCode());
                return true;
            }

            @Override void shown() {
                RemoteKeys.setLearning(SettingsActivity.this, true);
                armedAt = SystemClock.uptimeMillis();
                if (receiver != null) return;
                receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) { learned(i.getIntExtra("code", 0)); }
                };
                registerReceiver(receiver, new IntentFilter(RemoteKeys.ACTION_LEARNED), RemoteKeys.PERMISSION, handler);
            }

            @Override void hidden() {
                RemoteKeys.setLearning(SettingsActivity.this, false);
                if (receiver != null) {
                    try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
                    receiver = null;
                }
            }

            @Override void refresh() {
                if (SystemClock.uptimeMillis() - armedAt > 20_000) {
                    RemoteKeys.setLearning(SettingsActivity.this, true);
                    armedAt = SystemClock.uptimeMillis();
                }
                JSONObject s = HomeMode.state(SettingsActivity.this);
                boolean freeOn = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
                String text = freeOn ? "" : Ui.tr("Os botões funcionam enquanto a LumaTV é o Início da TV.",
                        "Buttons work while LumaTV is your home screen.");
                if (!text.equals(mode.getText().toString())) mode.setText(text);
                mode.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            }
        };
    }

    /** Where a button can go: the home screen, the inputs, search, any app; and "as before" to remove it. */
    private List<String[]> targets(int code) {
        List<String[]> out = new ArrayList<>();
        if (Prefs.remoteMap(this).has(String.valueOf(code))) out.add(new String[]{"", Ui.tr("Como antes (remover)", "As before (remove)")});
        out.add(new String[]{RemoteKeys.TARGET_HOME, Ui.tr("Início", "Home screen")});
        out.add(new String[]{RemoteKeys.TARGET_INPUTS, Ui.tr("Entradas", "Inputs")});
        out.add(new String[]{RemoteKeys.TARGET_SEARCH, Ui.tr("Busca", "Search")});
        for (HomeApps.Item it : HomeApps.editableApps(this)) {
            if (it.pkg != null && !it.pkg.equals(getPackageName())) out.add(new String[]{it.pkg, it.label});
        }
        return out;
    }

    private String targetName(String target) {
        switch (target) {
            case RemoteKeys.TARGET_HOME: return Ui.tr("Início", "Home screen");
            case RemoteKeys.TARGET_INPUTS: return Ui.tr("Entradas", "Inputs");
            case RemoteKeys.TARGET_SEARCH: return Ui.tr("Busca", "Search");
            default: return HomeApps.appLabel(this, target);
        }
    }

    private void keyRow(LinearLayout parent, int code, String target, Runnable click) {
        LinearLayout r = focusRow(100);
        TextView cap = Kit.line(Kit.text(this, RemoteKeys.cap(code), 22, Ui.semibold, Kit.states(0xD1000000 | (t.ink & 0xFFFFFF), t.onFocus)));
        cap.setGravity(Gravity.CENTER);
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[]{android.R.attr.state_focused}, stroke(t.day ? 0x80F2EFE8 : 0x800A0B0D));
        ring.addState(new int[]{}, stroke(t.day ? 0x661C1B19 : 0x5CF5F3EF));
        cap.setBackground(ring);
        cap.setPadding(px(10), 0, px(10), 0);
        r.addView(cap, new LinearLayout.LayoutParams(px(176), px(56)));
        TextView label = Kit.line(Kit.text(this, RemoteKeys.label(code), 28, Ui.regular, t.text()));
        LinearLayout.LayoutParams lp = Kit.weight();
        lp.leftMargin = px(32);
        r.addView(label, lp);
        r.setOnFocusChangeListener((v, f) -> label.setTypeface(f ? Ui.semibold : Ui.regular));
        TextView value = Kit.line(Kit.text(this, targetName(target), 26, Ui.regular, t.text2()));
        value.setMaxWidth(px(420));
        r.addView(value);
        r.addView(chevron(), chevronParams());
        r.setOnClickListener(v -> click.run());
        parent.addView(r, rowParams());
    }

    private GradientDrawable stroke(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Look.s(this, 16));
        d.setStroke(Math.max(1, px(2)), color);
        return d;
    }

    private Page playbackPage() {
        return new Page() {
            {
                title(body, Ui.tr("Reprodução", "Playback"));
                spacer(body, 32);
                flag(body, Ui.tr("Próximo episódio automático", "Autoplay next episode"), null, Prefs.AUTOPLAY);
                flag(body, Ui.tr("Igualar a taxa de quadros da TV", "Match the TV’s refresh rate"),
                        Ui.tr("Filmes a 24 quadros sem trancos.", "Use a matching rate when the TV supports it."), Prefs.MATCH_FRAME_RATE);
                flag(body, Ui.tr("Perguntar “Ainda está assistindo?” depois da meia-noite", "Ask “Still watching?” after midnight"),
                        Ui.tr("Depois de 3 episódios seguidos.", "After 3 episodes in a row."), Prefs.STILL_WATCHING);
            }
        };
    }

    private void flag(LinearLayout parent, String label, String sub, String key) {
        SharedPreferences p = Prefs.get(this, Prefs.PLAYBACK);
        toggle(parent, label, sub, p.getBoolean(key, true), on -> p.edit().putBoolean(key, on).apply());
    }

    // MARK: Subtitles & audio

    private static final String[][] LANGUAGES = {{"en", "English"}, {"pt-BR", "Português (Brasil)"}, {"es", "Español"},
            {"fr", "Français"}, {"de", "Deutsch"}, {"it", "Italiano"}, {"ja", "日本語"}, {"ko", "한국어"}, {"hi", "हिन्दी"},
            {"ta", "தமிழ்"}};

    private Page subtitlesPage() {
        return new Page() {
            final SharedPreferences p = Prefs.get(SettingsActivity.this, Prefs.PLAYBACK);
            final Row audio, subtitle, look;
            {
                title(body, Ui.tr("Legendas e áudio", "Subtitles & audio"));
                spacer(body, 32);
                audio = row(body, Ui.tr("Idioma do áudio", "Audio language"), "", true, () -> push(choice(
                        Ui.tr("Idioma do áudio", "Audio language"), languages(Ui.tr("Idioma original", "Original language")),
                        p.getString(Prefs.AUDIO_LANGUAGE, "en"), v -> p.edit().putString(Prefs.AUDIO_LANGUAGE, v).apply())));
                subtitle = row(body, Ui.tr("Idioma da legenda", "Subtitle language"), "", true, () -> push(choice(
                        Ui.tr("Idioma da legenda", "Subtitle language"), languages(Ui.tr("Nenhum", "None")),
                        p.getString(Prefs.SUBTITLE_LANGUAGE, ""), v -> p.edit().putString(Prefs.SUBTITLE_LANGUAGE, v).apply())));
                String[] modes = {"foreign", "always", "forced"};
                segmented(body, Ui.tr("Mostrar legendas", "Show subtitles"), new String[]{
                        Ui.tr("Quando não é meu idioma", "When it’s not my language"), Ui.tr("Sempre", "Always"),
                        Ui.tr("Só placas e músicas", "Only signs and songs")},
                        Math.max(0, Arrays.asList(modes).indexOf(p.getString(Prefs.SUBTITLE_MODE, "foreign"))), "subtitle-mode",
                        i -> p.edit().putString(Prefs.SUBTITLE_MODE, modes[i]).apply());
                look = row(body, Ui.tr("Aparência da legenda", "Subtitle look"), "", true, () -> {
                    List<String[]> options = new ArrayList<>();
                    for (String size : new String[]{"small", "medium", "large"}) {
                        for (String bg : new String[]{"shadow", "box"}) options.add(new String[]{size + "|" + bg, lookLabel(size, bg)});
                    }
                    push(choice(Ui.tr("Aparência da legenda", "Subtitle look"), options,
                            p.getString(Prefs.SUBTITLE_SIZE, "medium") + "|" + p.getString(Prefs.SUBTITLE_BACKGROUND, "shadow"), v -> {
                                String[] parts = v.split("\\|");
                                p.edit().putString(Prefs.SUBTITLE_SIZE, parts[0]).putString(Prefs.SUBTITLE_BACKGROUND, parts[1]).apply();
                            }));
                });
                toggle(body, Ui.tr("Corrigir o tempo da legenda sozinho", "Fix subtitle timing automatically"),
                        Ui.tr("Só quando tem certeza. Dá para desfazer.", "Only when it’s sure. You can undo it."),
                        p.getBoolean(Prefs.AUTO_SYNC, true), on -> p.edit().putBoolean(Prefs.AUTO_SYNC, on).apply());
            }

            @Override void refresh() {
                audio.value.setText(languageName(p.getString(Prefs.AUDIO_LANGUAGE, "en"), Ui.tr("Idioma original", "Original language")));
                subtitle.value.setText(languageName(p.getString(Prefs.SUBTITLE_LANGUAGE, ""), Ui.tr("Nenhum", "None")));
                look.value.setText(lookLabel(p.getString(Prefs.SUBTITLE_SIZE, "medium"), p.getString(Prefs.SUBTITLE_BACKGROUND, "shadow")));
            }
        };
    }

    private static List<String[]> languages(String none) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"", none});
        out.addAll(Arrays.asList(LANGUAGES));
        return out;
    }

    private static String languageName(String code, String none) {
        if (code == null || code.isEmpty()) return none;
        for (String[] l : LANGUAGES) if (l[0].equalsIgnoreCase(code)) return l[1];
        java.util.Locale locale = java.util.Locale.forLanguageTag(code);
        String name = locale.getDisplayName(locale);
        return name.isEmpty() ? code : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String lookLabel(String size, String bg) {
        String s = "small".equals(size) ? Ui.tr("Pequena", "Small") : "large".equals(size) ? Ui.tr("Grande", "Large") : Ui.tr("Média", "Medium");
        return s + " · " + ("box".equals(bg) ? Ui.tr("Caixa", "Box") : Ui.tr("Sombra", "Shadow"));
    }

    // MARK: Stremio account

    private Page stremioPage() {
        return new Page() {
            final TextView status;
            final LinearLayout actions = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
            boolean confirming, connected;

            {
                title(body, Ui.tr("Conta Stremio", "Stremio account"));
                para(body, Ui.tr("A LumaTV toca o que os seus complementos do Stremio encontram, aqui mesmo.",
                        "LumaTV plays what your Stremio add-ons find, right here."));
                status = Kit.text(SettingsActivity.this, "", 28, Ui.medium, t.ink);
                body.addView(status, side(Kit.top(SettingsActivity.this, Kit.fill(), 40)));
                body.addView(actions, side(Kit.top(SettingsActivity.this, Kit.fill(), 40)));
            }

            @Override void shown() {
                connected = AddonStore.connected(SettingsActivity.this);
                confirming = false;
                status.setText(connected ? Ui.tr("Conectada", "Connected") : Ui.tr("Não conectada", "Not connected"));
                buttons();
                if (connected) load(() -> AddonStore.load(SettingsActivity.this).length(), n -> status.setText(
                        Ui.tr("Conectada · " + n + " complementos", "Connected · " + n + " add-ons")));
            }

            void buttons() {
                actions.removeAllViews();
                if (!connected) {
                    actions.addView(Kit.button(SettingsActivity.this, t, Ui.tr("Conectar o Stremio", "Connect Stremio"), v -> connect()));
                    return;
                }
                actions.addView(Kit.button(SettingsActivity.this, t, Ui.tr("Conectar de novo", "Connect again"), v -> connect()));
                TextView off = Kit.button(SettingsActivity.this, t, confirming ? Ui.tr("Aperte de novo para desconectar", "Press again to disconnect")
                        : Ui.tr("Desconectar", "Disconnect"), v -> {
                    if (!confirming) {
                        confirming = true;
                        buttons();
                        actions.getChildAt(1).requestFocus();
                        handler.postDelayed(() -> { if (confirming) { confirming = false; boolean f = actions.getChildAt(1) != null && actions.getChildAt(1).isFocused(); buttons(); if (f) actions.getChildAt(1).requestFocus(); } }, 4000);
                        return;
                    }
                    confirming = false;
                    AddonStore.clear(SettingsActivity.this);
                    shown();
                    if (actions.getChildAt(0) != null) actions.getChildAt(0).requestFocus();
                });
                LinearLayout.LayoutParams lp = Kit.wrap();
                lp.leftMargin = px(16);
                actions.addView(off, lp);
            }

            void connect() {
                returnFocus = getCurrentFocus();
                startActivity(new Intent(SettingsActivity.this, ConnectSourcesActivity.class));
            }
        };
    }

    // MARK: Your TVs

    private Page tvsPage() {
        return new Page() {
            final LinearLayout grid = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            final LinearLayout code = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            final LinearLayout rest = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            final TextView status;
            List<TvSync.Found> found = new ArrayList<>();
            String codeKey = "", gridKey;

            {
                title(body, Ui.tr("Suas TVs", "Your TVs"));
                body.addView(grid, Kit.top(SettingsActivity.this, Kit.fill(), 48));
                body.addView(code, Kit.fill());
                body.addView(rest, Kit.fill());
                status = small(body, "");
                status.setVisibility(View.GONE);
                fillRest();
            }

            @Override void shown() {
                TvSync.openPairing(SettingsActivity.this);
                TvSync.discover(SettingsActivity.this, list -> { found = list; fillGrid(); });
                fillGrid();
            }

            @Override void hidden() {
                TvSync.stopDiscovery(SettingsActivity.this);
                TvSync.closePairing(SettingsActivity.this);
            }

            void fillGrid() {
                List<TvSync.Peer> peers = TvSync.peers(SettingsActivity.this);
                StringBuilder key = new StringBuilder();
                for (TvSync.Peer p : peers) key.append(p.id).append(p.name).append(ago(p.syncedAt)).append(';');
                for (TvSync.Found f : found) key.append(f.id).append(f.name).append(',');
                if (key.toString().equals(gridKey)) return;
                gridKey = key.toString();
                View focused = getCurrentFocus();
                Object focusTag = focused != null && inPane(focused) ? focused.getTag() : null;
                grid.removeAllViews();
                List<View> cards = new ArrayList<>();
                cards.add(tvCard(TvSync.name(SettingsActivity.this), Ui.tr("Esta TV", "This TV"), null, null));
                for (TvSync.Peer p : peers) {
                    String when = p.syncedAt == 0 ? Ui.tr("Ainda não sincronizada", "Not synced yet")
                            : Ui.tr("Sincronizada " + ago(p.syncedAt), "Synced " + ago(p.syncedAt));
                    cards.add(tvCard(p.name, when, "peer-" + p.id, () -> push(choice(p.name, Arrays.asList(
                            new String[]{"sync", Ui.tr("Sincronizar agora", "Sync now")},
                            new String[]{"forget", Ui.tr("Esquecer esta TV", "Forget this TV")}), null, v -> {
                                if (v.equals("sync")) syncNow(); else forget(p);
                            }))));
                }
                for (TvSync.Found f : found) {
                    boolean paired = false;
                    for (TvSync.Peer p : peers) paired |= p.id.equals(f.id);
                    if (paired) continue;
                    cards.add(tvCard(f.name, Ui.tr("Aperte OK para parear", "Press OK to pair"), "found-" + f.id, () -> pair(f)));
                }
                for (int i = 0; i < cards.size(); i += 2) {
                    LinearLayout line = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                    line.setBaselineAligned(false);
                    LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    line.addView(cards.get(i), a);
                    LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    b.leftMargin = px(24);
                    if (i + 1 < cards.size()) line.addView(cards.get(i + 1), b); else line.addView(new View(SettingsActivity.this), b);
                    grid.addView(line, side(i == 0 ? Kit.fill() : Kit.top(SettingsActivity.this, Kit.fill(), 24)));
                }
                if (peers.isEmpty() && found.isEmpty()) {
                    para(grid, Ui.tr("Abra Ajustes → Suas TVs na sua outra TV com a LumaTV para encontrá-la aqui.",
                            "Open Settings → Your TVs on your other LumaTV TV to find it here."));
                }
                if (focusTag != null) {
                    View again = grid.findViewWithTag(focusTag);
                    if (again != null) again.requestFocus(); else if (focused != null && !focused.isAttachedToWindow()) focusPane();
                }
                fillRest();
            }

            String restKey = "";

            void fillRest() {
                boolean paired = !TvSync.peers(SettingsActivity.this).isEmpty();
                if (String.valueOf(paired).equals(restKey)) return;
                restKey = String.valueOf(paired);
                rest.removeAllViews();
                if (!paired) return;
                TextView h = h2(rest, Ui.tr("Manter igual nas duas", "Keep the same on both"));
                ((LinearLayout.LayoutParams) h.getLayoutParams()).bottomMargin = px(12);
                String[][] cats = {{TvSync.APPS, Ui.tr("Apps no Início", "Apps on Home")}, {TvSync.SETTINGS, Ui.tr("Ajustes", "Settings")},
                        {TvSync.ADDONS, Ui.tr("Complementos do Stremio", "Stremio add-ons")}, {TvSync.SERVICES, Ui.tr("Meus serviços", "My services")}};
                for (String[] cat : cats) {
                    toggle(rest, cat[1], null, TvSync.category(SettingsActivity.this, cat[0]),
                            on -> TvSync.setCategory(SettingsActivity.this, cat[0], on));
                }
                para(rest, Ui.tr("O Continuar assistindo segue a sua conta Stremio.", "Continue Watching follows your Stremio account."));
                TextView sync = Kit.button(SettingsActivity.this, t, Ui.tr("Sincronizar agora", "Sync now"), v -> syncNow());
                sync.setTag("sync-now");
                rest.addView(sync, side(Kit.top(SettingsActivity.this, Kit.wrap(), 36)));
            }

            void showCode() {
                String[] pin = TvSync.pairingCode();
                String key = pin == null ? "" : pin[0] + pin[1];
                if (key.equals(codeKey)) return;
                codeKey = key;
                code.removeAllViews();
                if (pin == null) return;
                LinearLayout card = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
                card.setBackground(Kit.round(SettingsActivity.this, t.card, 24));
                int pad = px(32);
                card.setPadding(pad, pad, pad, pad);
                card.addView(Kit.text(SettingsActivity.this, Ui.tr("Código para parear", "Pairing code"), 26, Ui.medium, t.ink2));
                TextView digits = Kit.text(SettingsActivity.this, pin[0].replace("", " ").trim(), 84, Ui.displaySemibold, t.ink);
                digits.setLetterSpacing(0.08f);
                card.addView(digits, Kit.top(SettingsActivity.this, Kit.wrap(), 12));
                card.addView(Kit.text(SettingsActivity.this, Ui.tr("Digite na " + pin[1] + ".", "Enter it on " + pin[1] + "."),
                        26, Ui.regular, t.ink2), Kit.top(SettingsActivity.this, Kit.wrap(), 12));
                code.addView(card, side(Kit.top(SettingsActivity.this, Kit.fill(), 32)));
            }

            @Override void refresh() {
                showCode();
                String paired = TvSync.takeJustPaired();
                if (paired != null) say(Ui.tr("Pareada com " + paired + ".", "Paired with " + paired + "."));
                fillGrid();
            }

            void say(String text) {
                status.setText(text);
                status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            }

            void pair(TvSync.Found f) {
                say(Ui.tr("Pedindo um código à " + f.name + "…", "Asking " + f.name + " for a code…"));
                SettingsActivity.this.<Object>load(() -> {
                    try { return TvSync.pairStart(SettingsActivity.this, f); } catch (Exception e) { return e; }
                }, r -> {
                    if (r instanceof Exception) { say(((Exception) r).getMessage()); return; }
                    say("");
                    push(codePage(f, String.valueOf(r), this::said));
                });
            }

            void said(String text) {
                say(text);
                gridKey = "";
                restKey = "";
                fillGrid();
                fillRest();
            }

            void syncNow() {
                say(Ui.tr("Sincronizando…", "Syncing…"));
                load(() -> {
                    try { TvSync.syncNow(SettingsActivity.this); return ""; } catch (Exception e) { return String.valueOf(e.getMessage()); }
                }, r -> {
                    say(String.valueOf(r).isEmpty() ? Ui.tr("Sincronizado agora.", "Synced just now.") : String.valueOf(r));
                    gridKey = "";
                    fillGrid();
                    TvSync.discover(SettingsActivity.this, list -> { found = list; fillGrid(); });
                });
            }

            void forget(TvSync.Peer p) {
                load(() -> { TvSync.forget(SettingsActivity.this, p.id); return ""; }, r -> said(
                        Ui.tr(p.name + " foi esquecida.", p.name + " was forgotten.")));
            }
        };
    }

    private View tvCard(String name, String status, String tag, Runnable click) {
        LinearLayout card = Kit.row(this, LinearLayout.HORIZONTAL);
        int pad = px(32);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(click == null ? Kit.round(this, t.card, 24) : Kit.fill(this, t.card, t.focus, 24));
        Kit.Glyph tv = new Kit.Glyph(this, Kit.Glyph.TV, Kit.states(0xCC000000 | (t.ink & 0xFFFFFF), t.onFocus));
        card.addView(tv, new LinearLayout.LayoutParams(px(48), px(48)));
        LinearLayout words = Kit.row(this, LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams wp = Kit.weight();
        wp.leftMargin = px(28);
        card.addView(words, wp);
        words.addView(Kit.line(Kit.text(this, name, 32, Ui.semibold, t.text())));
        words.addView(Kit.line(Kit.text(this, status, 24, Ui.regular, t.text2())), Kit.top(this, Kit.fill(), 10));
        if (click != null) {
            card.setFocusable(true);
            card.setClickable(true);
            card.setTag(tag);
            card.setOnClickListener(v -> click.run());
        }
        return card;
    }

    /** Typing the other TV's code with the remote: Up/Down change a digit, number keys type it, OK pairs. */
    private Page codePage(TvSync.Found tv, String name, Consumer<String> done) {
        return new Page() {
            final TextView[] digits = new TextView[4];
            final int[] values = new int[4];
            final TextView status;
            boolean working;

            {
                title(body, Ui.tr("Digite o código da " + name, "Enter the code shown on " + name));
                LinearLayout line = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                for (int i = 0; i < 4; i++) {
                    TextView d = Kit.text(SettingsActivity.this, "0", 64, Ui.displaySemibold, t.text());
                    d.setDuplicateParentStateEnabled(false);
                    d.setGravity(Gravity.CENTER);
                    d.setBackground(Kit.fill(SettingsActivity.this, t.card, t.focus, 20));
                    d.setFocusable(true);
                    d.setClickable(true);
                    d.setOnClickListener(v -> submit());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(112), px(136));
                    if (i > 0) lp.leftMargin = px(16);
                    line.addView(d, lp);
                    digits[i] = d;
                }
                body.addView(line, side(Kit.top(SettingsActivity.this, Kit.wrap(), 48)));
                small(body, Ui.tr("Cima e baixo mudam o número; OK pareia.", "Up and Down change a digit; OK pairs."));
                status = para(body, "");
                status.setVisibility(View.GONE);
            }

            int focusedDigit() {
                for (int i = 0; i < 4; i++) if (digits[i].isFocused()) return i;
                return -1;
            }

            @Override boolean key(KeyEvent e) {
                int i = focusedDigit();
                if (i < 0 || working) return false;
                int code = e.getKeyCode();
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (down) {
                        values[i] = (values[i] + (code == KeyEvent.KEYCODE_DPAD_UP ? 1 : 9)) % 10;
                        digits[i].setText(String.valueOf(values[i]));
                    }
                    return true;
                }
                int n = code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 ? code - KeyEvent.KEYCODE_0
                        : code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9 ? code - KeyEvent.KEYCODE_NUMPAD_0 : -1;
                if (n >= 0) {
                    if (down) {
                        values[i] = n;
                        digits[i].setText(String.valueOf(n));
                        if (i < 3) digits[i + 1].requestFocus(); else submit();
                    }
                    return true;
                }
                return false;
            }

            void submit() {
                if (working) return;
                working = true;
                StringBuilder pin = new StringBuilder();
                for (int v : values) pin.append(v);
                status.setText(Ui.tr("Pareando…", "Pairing…"));
                status.setVisibility(View.VISIBLE);
                load(() -> {
                    try { TvSync.pairFinish(SettingsActivity.this, tv, pin.toString()); return ""; }
                    catch (Exception e) { return String.valueOf(e.getMessage()); }
                }, r -> {
                    working = false;
                    if (String.valueOf(r).isEmpty()) {
                        pop();
                        done.accept(Ui.tr("Pareada com " + name + ". Agora aperte Sincronizar agora.", "Paired with " + name + ". Now press Sync now."));
                    } else {
                        status.setText(String.valueOf(r));
                        digits[0].requestFocus();
                    }
                });
            }
        };
    }

    private Page tvSettingsPage() {
        return new Page() {
            {
                title(body, Ui.tr("Ajustes da TV", "TV settings"));
                para(body, Ui.tr("Imagem, som, rede e contas ficam nos ajustes da própria TV.",
                        "Picture, sound, network and accounts stay in the TV’s own settings."));
                spacer(body, 32);
                row(body, Ui.tr("Abrir os ajustes da TV", "Open TV settings"), "", true, () -> openSystem(Settings.ACTION_SETTINGS));
            }
        };
    }

    // MARK: About

    private Page aboutPage() {
        return new Page() {
            final LinearLayout update = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            final LinearLayout notices = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
            Row check;
            final TextView problem;
            String updateKey = "", noticeKey = "";

            {
                title(body, Ui.tr("Sobre", "About"));
                body.addView(update, Kit.fill());
                spacer(body, 32);
                check = row(body, Ui.tr("Procurar atualização", "Check for updates"), "", false, () -> {
                    Updater.requestCheck(SettingsActivity.this);
                    check.value.setText(Ui.tr("Procurando…", "Checking…"));
                });
                toggle(body, Ui.tr("Atualizações automáticas", "Automatic updates"),
                        Ui.tr("Instala de madrugada, com a TV em repouso.", "Installs at night while the TV rests."),
                        Updater.automaticOn(SettingsActivity.this), on -> Updater.setAutomatic(SettingsActivity.this, on));
                info(body, Ui.tr("Versão", "Version"), Config.VERSION_NAME + " · " + Config.COMMIT);
                problem = info(body, Ui.tr("Registro de reprodução", "Playback log"), "").value;
                JSONObject m = HomeMode.state(SettingsActivity.this).optJSONObject("metrics");
                String speed = compare(m, "homeMs", " ms"), mem = compare(m, "homePssMB", " MB");
                if (!speed.isEmpty()) info(body, Ui.tr("Botão Início", "Home button"), speed);
                if (!mem.isEmpty()) info(body, Ui.tr("Memória do Início", "Home screen memory"), mem);
                h2(body, Ui.tr("Avisos", "Notices"));
                body.addView(notices, Kit.fill());
                footer(body);
            }

            @Override void refresh() {
                String last = PlaybackLog.last(SettingsActivity.this);
                problem.setText(last.isEmpty() ? Ui.tr("Nenhum", "None") : last);
                fillUpdate();
                fillNotices();
            }

            void fillUpdate() {
                JSONObject s = Updater.state(SettingsActivity.this);
                JSONObject r = Updater.available(SettingsActivity.this);
                long later = Prefs.get(SettingsActivity.this, Prefs.UPDATES).getLong("later", 0);
                String phase = s.optString("phase");
                boolean show = r != null && (r.optLong("versionCode") != later || !phase.isEmpty());
                String error = s.optString("error");
                check.value.setText(!phase.isEmpty() && r == null ? Ui.tr("Procurando…", "Checking…")
                        : !error.isEmpty() ? Ui.tr("Erro: ", "Error: ") + error
                        : r != null ? Ui.tr("LumaTV " + r.optString("versionName") + " disponível", "LumaTV " + r.optString("versionName") + " available")
                        : s.optLong("checkedAt") > 0 ? Ui.tr("Em dia · ", "Up to date · ") + ago(s.optLong("checkedAt"))
                        : "");
                String key = show + "|" + phase + "|" + (r == null ? "" : r.toString());
                if (key.equals(updateKey)) return;
                updateKey = key;
                View focused = getCurrentFocus();
                boolean hadFocus = focused != null && isChild(update, focused);
                update.removeAllViews();
                if (!show) {
                    if (hadFocus) check.view.requestFocus();
                    return;
                }
                LinearLayout card = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
                card.setBackground(Kit.round(SettingsActivity.this, t.card, 24));
                int pad = px(40);
                card.setPadding(pad, pad, pad, pad);
                TextView h = Kit.text(SettingsActivity.this, Ui.tr("Atualização disponível · LumaTV ", "Update available · LumaTV ")
                        + r.optString("versionName"), 36, Ui.semibold, t.ink);
                h.setLetterSpacing(-0.015f);
                card.addView(h);
                JSONArray notes = r.optJSONArray("notes");
                for (int i = 0; notes != null && i < notes.length(); i++) {
                    LinearLayout li = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                    li.addView(new Kit.Glyph(SettingsActivity.this, Kit.Glyph.BULLET, ColorStateList.valueOf(t.ink3)),
                            new LinearLayout.LayoutParams(px(16), px(16)));
                    TextView n = Kit.text(SettingsActivity.this, notes.optString(i), 28, Ui.regular, t.ink2);
                    LinearLayout.LayoutParams np = Kit.weight();
                    np.leftMargin = px(20);
                    li.addView(n, np);
                    card.addView(li, Kit.top(SettingsActivity.this, Kit.fill(), i == 0 ? 24 : 14));
                }
                TextView keep = Kit.text(SettingsActivity.this, Ui.tr("Suas séries, ajustes e a conexão com o Stremio continuam como estão.",
                        "Your shows, settings and Stremio connection stay as they are."), 24, Ui.regular, t.ink2);
                card.addView(keep, Kit.top(SettingsActivity.this, Kit.fill(), 32));
                if (phase.isEmpty()) {
                    LinearLayout buttons = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                    TextView now = Kit.button(SettingsActivity.this, t, Ui.tr("Atualizar agora", "Update now"), v -> {
                        Updater.requestInstall(SettingsActivity.this);
                        updateKey = "";
                    });
                    now.setTag("update-now");
                    buttons.addView(now);
                    LinearLayout.LayoutParams lp = Kit.wrap();
                    lp.leftMargin = px(16);
                    buttons.addView(Kit.button(SettingsActivity.this, t, Ui.tr("Depois", "Later"), v -> {
                        Prefs.get(SettingsActivity.this, Prefs.UPDATES).edit().putLong("later", r.optLong("versionCode")).apply();
                        updateKey = "";
                        fillUpdate();
                        check.view.requestFocus();
                    }), lp);
                    card.addView(buttons, Kit.top(SettingsActivity.this, Kit.fill(), 40));
                    if (hadFocus) now.requestFocus();
                } else {
                    LinearLayout st = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
                    st.addView(new Kit.Glyph(SettingsActivity.this, Kit.Glyph.SPINNER, ColorStateList.valueOf(t.ink)),
                            new LinearLayout.LayoutParams(px(28), px(28)));
                    String text = "approval".equals(phase) ? Ui.tr("Na TV: marque Sempre permitir e aperte OK.", "On the TV: tick Always allow and press OK.")
                            : "downloading".equals(phase) ? Ui.tr("Baixando…", "Downloading…")
                            : "installing".equals(phase) ? Ui.tr("Instalando. A LumaTV reabre quando terminar.", "Installing. LumaTV reopens when it’s done.")
                            : Ui.tr("Procurando…", "Checking…");
                    TextView tx = Kit.text(SettingsActivity.this, text, 26, Ui.medium, t.ink);
                    LinearLayout.LayoutParams tp = Kit.weight();
                    tp.leftMargin = px(16);
                    st.addView(tx, tp);
                    card.addView(st, Kit.top(SettingsActivity.this, Kit.fill(), 40));
                    if (hadFocus) check.view.requestFocus();
                }
                update.addView(card, side(Kit.top(SettingsActivity.this, Kit.fill(), 48)));
            }

            void fillNotices() {
                JSONArray list = HomeMode.state(SettingsActivity.this).optJSONArray("notices");
                StringBuilder key = new StringBuilder();
                for (int i = 0; list != null && i < list.length() && i < 4; i++) key.append(list.optJSONObject(i)).append(';');
                key.append(System.currentTimeMillis() / 60_000);
                if (key.toString().equals(noticeKey)) return;
                noticeKey = key.toString();
                notices.removeAllViews();
                int shown = 0;
                for (int i = 0; list != null && i < list.length() && shown < 4; i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    TextView n = Kit.text(SettingsActivity.this, ago(o.optLong("at")) + " · " + o.optString("text"), 24, Ui.regular, t.ink2);
                    n.setMaxLines(2);
                    n.setEllipsize(TextUtils.TruncateAt.END);
                    n.setLineSpacing(0, 1.3f);
                    notices.addView(n, side(Kit.top(SettingsActivity.this, Kit.fill(), shown == 0 ? 20 : 16)));
                    shown++;
                }
                if (shown == 0) notices.addView(Kit.text(SettingsActivity.this, Ui.tr("Nenhum ainda.", "None yet."), 24, Ui.regular, t.ink2),
                        side(Kit.top(SettingsActivity.this, Kit.fill(), 20)));
            }
        };
    }

    /** "LumaTV 412 ms · Original 846 ms" from HomeMode's measurements, or "". */
    private static String compare(JSONObject metrics, String key, String unit) {
        JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
        JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
        String out = "";
        if (a != null && a.has(key)) out = "LumaTV " + a.optLong(key) + unit;
        if (b != null && b.has(key)) out += (out.isEmpty() ? "" : " · ") + "Original " + b.optLong(key) + unit;
        return out;
    }

    /** A list to pick one from (languages, a button's target, a paired TV's actions), with a check on the current one. */
    private Page choice(String heading, List<String[]> options, String selected, Consumer<String> picked) {
        return new Page() {
            {
                title(body, heading);
                spacer(body, 32);
                for (String[] o : options) {
                    boolean on = selected != null && o[0].equals(selected);
                    LinearLayout r = focusRow(84);
                    TextView label = Kit.line(Kit.text(SettingsActivity.this, o[1], 28, on ? Ui.semibold : Ui.regular, t.text()));
                    r.addView(label, Kit.weight());
                    if (on) {
                        Kit.Glyph check = new Kit.Glyph(SettingsActivity.this, Kit.Glyph.CHECK, t.text()).tick(Kit.states(t.ground, t.focus));
                        r.addView(check, new LinearLayout.LayoutParams(px(30), px(30)));
                    }
                    r.setOnClickListener(v -> {
                        pop();
                        picked.accept(o[0]);
                        if (current() != null) current().refresh();
                    });
                    body.addView(r, rowParams());
                }
            }

            @Override void shown() {
                // Start on the current choice.
                handler.post(() -> {
                    for (int i = 0; i < options.size(); i++) {
                        if (selected != null && options.get(i)[0].equals(selected)) {
                            View v = body.getChildAt(2 + i);
                            if (v != null) v.requestFocus();
                        }
                    }
                });
            }
        };
    }

    // MARK: - Building blocks (sizes in design px; see the Settings boards)

    private int px(float design) {
        return Kit.px(this, design);
    }

    /** Content that isn't a focusable row sits 28 px in, aligned with the rows' text. */
    private LinearLayout.LayoutParams side(LinearLayout.LayoutParams p) {
        p.leftMargin += px(28);
        p.rightMargin += px(28);
        return p;
    }

    private void title(LinearLayout parent, String text) {
        TextView h = Kit.text(this, text, 56, Ui.displaySemibold, t.ink);
        h.setLetterSpacing(-0.025f);
        parent.addView(h, side(Kit.fill()));
    }

    private TextView para(LinearLayout parent, String text) {
        TextView p = Kit.text(this, text, 26, Ui.regular, t.ink2);
        p.setLineSpacing(0, 1.35f);
        parent.addView(p, side(Kit.top(this, Kit.fill(), 16)));
        return p;
    }

    private TextView small(LinearLayout parent, String text) {
        TextView p = Kit.text(this, text, 24, Ui.regular, t.ink3);
        p.setLineSpacing(0, 1.3f);
        parent.addView(p, side(Kit.top(this, Kit.fill(), 20)));
        return p;
    }

    private TextView h2(LinearLayout parent, String text) {
        TextView h = Kit.text(this, text, 26, Ui.medium, t.ink3);
        parent.addView(h, side(Kit.top(this, Kit.fill(), 56)));
        return h;
    }

    private void spacer(LinearLayout parent, float design) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, px(design)));
    }

    private void footer(LinearLayout parent) {
        TextView f = Kit.text(this, "LumaTV " + Config.VERSION_NAME + Ui.tr(" da Madfer Operations · Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                " by Madfer Operations · Uses the TMDB API but is not endorsed or certified by TMDB."), 22, Ui.regular, t.ink3);
        f.setLineSpacing(0, 1.3f);
        parent.addView(f, side(Kit.top(this, Kit.fill(), 64)));
    }

    private View amberChip(String text) {
        LinearLayout chip = Kit.row(this, LinearLayout.HORIZONTAL);
        chip.setBackground(Kit.round(this, Look.AMBER_FILL, 28));
        chip.setPadding(px(20), px(14), px(28), px(14));
        chip.setMinimumHeight(px(56));
        chip.addView(new Kit.Glyph(this, Kit.Glyph.INFO, ColorStateList.valueOf(Look.AMBER_INK)), new LinearLayout.LayoutParams(px(26), px(26)));
        TextView tx = Kit.text(this, text, 24, Ui.medium, Look.AMBER_INK);
        tx.setMaxWidth(px(1000));
        tx.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams lp = Kit.wrap();
        lp.leftMargin = px(14);
        chip.addView(tx, lp);
        LinearLayout wrap = Kit.row(this, LinearLayout.HORIZONTAL);
        wrap.addView(chip, side(Kit.wrap()));
        return wrap;
    }

    /** A focusable row: transparent at rest, a solid ink pill when focused (the boards' list focus). */
    private LinearLayout focusRow(float minHeight) {
        LinearLayout r = Kit.row(this, LinearLayout.HORIZONTAL);
        r.setMinimumHeight(px(minHeight));
        r.setPadding(px(28), px(14), px(28), px(14));
        r.setBackground(Kit.fill(this, 0x00000000, t.focus, 20));
        r.setFocusable(true);
        r.setClickable(true);
        return r;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = Kit.fill();
        p.bottomMargin = px(4);
        return p;
    }

    private Kit.Glyph chevron() {
        return new Kit.Glyph(this, Kit.Glyph.CHEVRON, t.text2());
    }

    private LinearLayout.LayoutParams chevronParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(24), px(24));
        p.leftMargin = px(14);
        return p;
    }

    /** A row's label and value, for updating. */
    static final class Row {
        LinearLayout view;
        TextView label, value;
    }

    private Row row(LinearLayout parent, String label, String value, boolean chevron, Runnable click) {
        Row row = new Row();
        LinearLayout r = focusRow(84);
        row.view = r;
        row.label = Kit.line(Kit.text(this, label, 28, Ui.regular, t.text()));
        r.addView(row.label, Kit.weight());
        row.value = Kit.line(Kit.text(this, value, 26, Ui.regular, t.text2()));
        row.value.setMaxWidth(px(640));
        LinearLayout.LayoutParams vp = Kit.wrap();
        vp.leftMargin = px(24);
        r.addView(row.value, vp);
        if (chevron) r.addView(chevron(), chevronParams());
        r.setOnFocusChangeListener((v, f) -> row.label.setTypeface(f ? Ui.semibold : Ui.regular));
        r.setOnClickListener(v -> click.run());
        parent.addView(r, rowParams());
        return row;
    }

    /** A line of information: not focusable, so the remote skips it. */
    private Row info(LinearLayout parent, String label, String value) {
        Row row = new Row();
        LinearLayout r = Kit.row(this, LinearLayout.HORIZONTAL);
        r.setMinimumHeight(px(84));
        r.setPadding(px(28), px(14), px(28), px(14));
        row.view = r;
        row.label = Kit.text(this, label, 28, Ui.regular, t.ink);
        r.addView(row.label, Kit.wrap());
        row.value = Kit.text(this, value, 26, Ui.regular, t.ink2);
        row.value.setGravity(Gravity.END);
        row.value.setMaxLines(2);
        row.value.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vp = Kit.weight();
        vp.leftMargin = px(48);
        r.addView(row.value, vp);
        parent.addView(r, rowParams());
        return row;
    }

    private static String onOff(boolean on) {
        return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
    }

    private void toggle(LinearLayout parent, String label, String sub, boolean on, Consumer<Boolean> change) {
        LinearLayout r = focusRow(84);
        LinearLayout words = Kit.row(this, LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        TextView l = Kit.text(this, label, 28, Ui.regular, t.text());
        words.addView(l);
        if (sub != null) words.addView(Kit.text(this, sub, 24, Ui.regular, t.text2()), Kit.top(this, Kit.wrap(), 6));
        r.addView(words, Kit.weight());
        TextView state = Kit.text(this, onOff(on), 26, Ui.regular, t.text2());
        LinearLayout.LayoutParams stp = Kit.wrap();
        stp.leftMargin = px(24);
        r.addView(state, stp);
        Kit.Switch sw = new Kit.Switch(this, t, on);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(80), px(46));
        sp.leftMargin = px(18);
        r.addView(sw, sp);
        boolean[] value = {on};
        r.setOnFocusChangeListener((v, f) -> l.setTypeface(f ? Ui.semibold : Ui.regular));
        r.setOnClickListener(v -> {
            value[0] = !value[0];
            sw.set(value[0]);
            state.setText(onOff(value[0]));
            change.accept(value[0]);
        });
        parent.addView(r, rowParams());
    }

    /** A label with a segmented control: the focused segment is solid ink, the chosen one a soft fill. */
    private void segmented(LinearLayout parent, String label, String[] options, int checked, String tag, java.util.function.IntConsumer pick) {
        LinearLayout r = Kit.row(this, LinearLayout.HORIZONTAL);
        r.setMinimumHeight(px(84));
        r.addView(Kit.line(Kit.text(this, label, 28, Ui.regular, t.ink)), Kit.weight());
        LinearLayout group = Kit.row(this, LinearLayout.HORIZONTAL);
        group.setBackground(Kit.round(this, t.segment, 40));
        int pad = px(6);
        group.setPadding(pad, pad, pad, pad);
        TextView[] pills = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView pill = Kit.text(this, options[i], 24, Ui.regular, t.ink);
            pill.setDuplicateParentStateEnabled(false);
            pill.setSingleLine(true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(px(28), 0, px(28), 0);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, Kit.round(this, t.focus, 30));
            bg.addState(new int[]{android.R.attr.state_activated}, Kit.round(this, t.chosen, 30));
            bg.addState(new int[]{}, Kit.round(this, 0x00000000, 30));
            pill.setBackground(bg);
            pill.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_activated}, {}},
                    new int[]{t.onFocus, t.ink, t.ink2}));
            pill.setFocusable(true);
            pill.setClickable(true);
            pill.setTag(tag + "-" + i);
            pill.setActivated(i == checked);
            pill.setTypeface(i == checked ? Ui.semibold : Ui.regular);
            pill.setOnFocusChangeListener((v, f) -> ((TextView) v).setTypeface(f || v.isActivated() ? Ui.semibold : Ui.regular));
            pill.setOnClickListener(v -> {
                for (int j = 0; j < pills.length; j++) {
                    pills[j].setActivated(j == index);
                    pills[j].setTypeface(j == index || pills[j].isFocused() ? Ui.semibold : Ui.regular);
                }
                pick.accept(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60));
            if (i > 0) lp.leftMargin = px(6);
            group.addView(pill, lp);
            pills[i] = pill;
        }
        r.addView(group, Kit.wrap());
        parent.addView(r, side(rowParams()));
    }

    /** The LumaTV / Original cards. */
    private final class Card {
        final LinearLayout view = Kit.row(SettingsActivity.this, LinearLayout.VERTICAL);
        final TextView title, desc, busyText;
        final Kit.Glyph check;
        final LinearLayout busyRow;
        boolean busy;

        Card(String name, String description, String tag) {
            int pad = px(32);
            view.setPadding(pad, pad, pad, pad);
            view.setTag(tag);
            view.setFocusable(true);
            view.setClickable(true);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, Kit.round(SettingsActivity.this, t.focus, 24));
            GradientDrawable chosen = Kit.round(SettingsActivity.this, t.card, 24);
            chosen.setStroke(Math.max(1, px(2)), t.ring);
            bg.addState(new int[]{android.R.attr.state_activated}, chosen);
            bg.addState(new int[]{}, Kit.round(SettingsActivity.this, t.cardSoft, 24));
            view.setBackground(bg);
            LinearLayout top = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
            top.setDuplicateParentStateEnabled(true);
            title = Kit.text(SettingsActivity.this, name, 32, Ui.semibold, t.text());
            top.addView(title, Kit.weight());
            check = new Kit.Glyph(SettingsActivity.this, Kit.Glyph.CHECK, t.text()).tick(Kit.states(t.day ? t.card | 0xFF000000 : t.ground, t.focus));
            top.addView(check, new LinearLayout.LayoutParams(px(30), px(30)));
            view.addView(top, Kit.fill());
            desc = Kit.text(SettingsActivity.this, description, 24, Ui.regular, t.text2());
            desc.setLineSpacing(0, 1.35f);
            view.addView(desc, Kit.top(SettingsActivity.this, Kit.fill(), 10));
            busyRow = Kit.row(SettingsActivity.this, LinearLayout.HORIZONTAL);
            busyRow.setDuplicateParentStateEnabled(true);
            busyRow.addView(new Kit.Glyph(SettingsActivity.this, Kit.Glyph.SPINNER, t.text()), new LinearLayout.LayoutParams(px(28), px(28)));
            busyText = Kit.text(SettingsActivity.this, "", 24, Ui.regular, t.text2());
            busyText.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams bp = Kit.weight();
            bp.leftMargin = px(14);
            busyRow.addView(busyText, bp);
            busyRow.setVisibility(View.GONE);
            view.addView(busyRow, Kit.top(SettingsActivity.this, Kit.fill(), 10));
        }

        void set(boolean chosen, String description) {
            view.setActivated(chosen);
            check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            if (!description.equals(desc.getText().toString())) desc.setText(description);
        }

        void busy(String text) {
            busy = text != null;
            desc.setVisibility(busy ? View.GONE : View.VISIBLE);
            busyRow.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy && !text.equals(busyText.getText().toString())) busyText.setText(text);
        }
    }

    // MARK: - Helpers

    /** Work off the main thread; the result comes back to this page only if it's still the one showing. */
    private <T> void load(Callable<T> job, Consumer<T> done) {
        final int gen = generation;
        // Page constructors finish before the callback owner is selected.
        handler.post(() -> {
            if (isDestroyed() || gen != generation) return;
            final Page owner = current();
            work.execute(() -> {
                T result;
                try {
                    result = job.call();
                } catch (Exception e) {
                    AppLog.w("settings: " + e.getClass().getSimpleName());
                    return;
                }
                final T r = result;
                handler.post(() -> {
                    if (isDestroyed() || gen != generation) return;
                    if (owner != null && !stack.contains(owner) && owner != page) return;
                    done.accept(r);
                });
            });
        });
    }

    private static boolean isChild(ViewGroup parent, View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private void openSystem(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    static String ago(long at) {
        if (at <= 0) return "";
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("agora", "now");
        if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
        if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
        return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
    }
}
