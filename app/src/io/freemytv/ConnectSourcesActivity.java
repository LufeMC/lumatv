package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connect Stremio (the Connect board): a QR for Stremio's official device link, three steps, and the wait. The
 * password never passes through LumaTV: the phone signs in on link.stremio.com and allows this TV, and the TV then
 * reads the account's add-ons and keeps them encrypted (AddonStore). A new code comes by itself when one expires.
 * Extra "automatic": opened by playback with nothing connected; it closes itself once connected.
 */
public class ConnectSourcesActivity extends TvActivity {
    private static final long CODE_LIFE = 5 * 60_000L;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Kit.Theme t;
    private FrameLayout qrCard;
    private ImageView qr;
    private TextView heading, line, status;
    private Kit.Glyph spinner;
    private LinearLayout steps, buttons;
    private int generation;
    private long expires;
    private boolean active, automatic, begun, complete, confirming;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (expires == 0) return;
            long left = expires - SystemClock.elapsedRealtime();
            if (left <= 0) {
                if (active) begin(); else expires = 0;   // a fresh code by itself
                return;
            }
            long s = left / 1000;
            status.setText(Ui.tr("Esperando o telefone · novo código em ", "Waiting for your phone · new code in ")
                    + String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60));
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Watchdog.install(this);
        automatic = getIntent().getBooleanExtra("automatic", false);
        t = Kit.Theme.night();
        setContentView(layout());
        if (AddonStore.connected(this) && !automatic) showConnected(); else showWaiting();
    }

    private int px(float d) { return Kit.px(this, d); }

    private View layout() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF141B2E, Look.GROUND_NIGHT});
        root.setBackground(sky);

        qrCard = new FrameLayout(this);
        qrCard.setBackground(Kit.round(this, 0xFFFFFFFF, 36));
        qrCard.setElevation(px(24));
        int pad = px(44);
        qrCard.setPadding(pad, pad, pad, pad);
        qr = new ImageView(this);
        qr.setContentDescription(Ui.tr("Código QR para link.stremio.com", "QR code for link.stremio.com"));
        qrCard.addView(qr, new FrameLayout.LayoutParams(px(452), px(452)));
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(px(540), px(540));
        qp.leftMargin = px(216);
        qp.topMargin = px(270);
        root.addView(qrCard, qp);

        LinearLayout words = Kit.row(this, LinearLayout.VERTICAL);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(px(880), ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.leftMargin = px(896);
        wp.topMargin = px(260);
        root.addView(words, wp);
        words.addView(brand(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
        heading = Kit.text(this, "", 68, Ui.displaySemibold, t.ink);
        heading.setLetterSpacing(-0.03f);
        words.addView(heading, Kit.top(this, Kit.fill(), 36));
        line = Kit.text(this, "", 30, Ui.regular, 0xC2F5F3EF);
        line.setLineSpacing(0, 1.35f);
        words.addView(line, Kit.top(this, Kit.fill(), 20));
        steps = Kit.row(this, LinearLayout.VERTICAL);
        words.addView(steps, Kit.top(this, Kit.fill(), 44));
        String[] list = {Ui.tr("Escaneie o código com o telefone", "Scan the code with your phone"),
                Ui.tr("Entre no Stremio e toque em Permitir", "Sign in to Stremio and tap Allow"),
                Ui.tr("A reprodução começa sozinha", "Playback starts on its own")};
        for (int i = 0; i < list.length; i++) {
            LinearLayout li = Kit.row(this, LinearLayout.HORIZONTAL);
            li.setGravity(Gravity.TOP);
            li.addView(Kit.text(this, String.valueOf(i + 1), 28, Ui.semibold, 0x80F5F3EF), new LinearLayout.LayoutParams(px(60), ViewGroup.LayoutParams.WRAP_CONTENT));
            li.addView(Kit.text(this, list[i], 28, Ui.regular, t.ink), Kit.weight());
            steps.addView(li, Kit.top(this, Kit.fill(), i == 0 ? 0 : 18));
        }
        LinearLayout st = Kit.row(this, LinearLayout.HORIZONTAL);
        spinner = new Kit.Glyph(this, Kit.Glyph.SPINNER, android.content.res.ColorStateList.valueOf(t.ink));
        st.addView(spinner, new LinearLayout.LayoutParams(px(28), px(28)));
        status = Kit.text(this, "", 24, Ui.regular, t.ink2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams sp = Kit.weight();
        sp.leftMargin = px(16);
        st.addView(status, sp);
        words.addView(st, Kit.top(this, Kit.fill(), 48));
        buttons = Kit.row(this, LinearLayout.HORIZONTAL);
        words.addView(buttons, Kit.top(this, Kit.fill(), 40));
        return root;
    }

    /** Stremio's white logo (lifted by the home screen from the app's TV art), or its name. */
    private View brand() {
        File[] files = new File(getCacheDir(), "logos").listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            if (!files[i].getName().startsWith("stremio-") || !files[i].getName().endsWith(".png")) continue;
            Bitmap b = BitmapFactory.decodeFile(files[i].getPath());
            if (b == null) break;
            ImageView v = new ImageView(this);
            v.setImageBitmap(b);
            v.setAdjustViewBounds(true);
            v.setColorFilter(0xE6F5F3EF, PorterDuff.Mode.SRC_IN);
            v.setContentDescription("Stremio");
            return v;
        }
        TextView name = Kit.text(this, "Stremio", 32, Ui.semibold, 0xE6F5F3EF);
        name.setGravity(Gravity.CENTER_VERTICAL);
        return name;
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = Kit.button(this, t, label, click);
        b.setMinHeight(px(68));
        Look.textSize(b, 24);
        b.setTypeface(Ui.medium);
        b.setPadding(px(34), 0, px(34), 0);
        LinearLayout.LayoutParams lp = Kit.wrap();
        if (buttons.getChildCount() > 0) lp.leftMargin = px(16);
        buttons.addView(b, lp);
        return b;
    }

    // MARK: - States

    private void showWaiting() {
        heading.setText(Ui.tr("Conecte seus complementos", "Connect your add-ons"));
        line.setText(Ui.tr("A LumaTV toca aqui o que os seus complementos do Stremio encontram. Um scan, e ela lembra.",
                "LumaTV plays what your Stremio add-ons find, right here. One scan, and it remembers."));
        steps.setVisibility(View.VISIBLE);
        qrCard.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.VISIBLE);
        status.setText(Ui.tr("Pegando um código…", "Getting a code…"));
        buttons.removeAllViews();
        button(Ui.tr("Agora não", "Not now"), v -> finish()).requestFocus();
    }

    private void showConnected() {
        expires = 0;
        heading.setText(Ui.tr("Complementos conectados", "Your add-ons are connected"));
        line.setText(Ui.tr("A LumaTV toca aqui o que os seus complementos do Stremio encontram.",
                "LumaTV plays what your Stremio add-ons find, right here."));
        steps.setVisibility(View.GONE);
        qrCard.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.GONE);
        status.setText(Ui.tr("Conectada", "Connected"));
        network.execute(() -> {
            try {
                int n = AddonStore.load(this).length();
                main.post(() -> { if (!complete && expires == 0) status.setText(Ui.tr("Conectada · " + n + " complementos", "Connected · " + n + " add-ons")); });
            } catch (Exception ignored) {}
        });
        confirming = false;
        connectedButtons();
    }

    private void connectedButtons() {
        buttons.removeAllViews();
        TextView done = button(Ui.tr("Pronto", "Done"), v -> finish());
        button(Ui.tr("Conectar de novo", "Connect again"), v -> { showWaiting(); begin(); });
        button(confirming ? Ui.tr("Aperte de novo para desconectar", "Press again to disconnect") : Ui.tr("Desconectar", "Disconnect"), v -> {
            if (!confirming) {
                confirming = true;
                connectedButtons();
                buttons.getChildAt(2).requestFocus();
                return;
            }
            generation++;
            AddonStore.clear(this);
            setResult(RESULT_OK);
            showWaiting();
            status.setText(Ui.tr("Complementos desconectados.", "Add-ons disconnected."));
            spinner.setVisibility(View.GONE);
            buttons.removeAllViews();
            button(Ui.tr("Conectar o Stremio", "Connect Stremio"), x -> { showWaiting(); begin(); }).requestFocus();
            button(Ui.tr("Agora não", "Not now"), x -> finish());
        });
        if (!confirming) done.requestFocus();
    }

    // MARK: - Stremio's device link

    private void begin() {
        begun = true;
        int request = ++generation;
        expires = 0;
        main.removeCallbacks(tick);
        qrCard.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.VISIBLE);
        status.setText(Ui.tr("Pegando um código…", "Getting a code…"));
        int size = px(452);
        network.execute(() -> {
            try {
                JSONObject result = MediaHttp.result(MediaHttp.json("https://link.stremio.com/api/v2/create?type=Create", null, 8000));
                String code = result.getString("code"), address = result.getString("link");
                if (!code.matches("[A-Za-z0-9]{4,12}") || !address.equals("https://link.stremio.com/" + code)) throw new Exception();
                Bitmap image = Qr.bitmap(address, size);
                main.post(() -> {
                    if (!current(request)) return;
                    expires = SystemClock.elapsedRealtime() + CODE_LIFE;
                    if (image != null) qr.setImageBitmap(image);
                    qrCard.setVisibility(View.VISIBLE);
                    main.removeCallbacks(tick);
                    tick.run();
                    poll(code, request);
                });
            } catch (Exception ignored) {
                main.post(() -> failure(request));
            }
        });
    }

    private void poll(String code, int request) {
        if (!current(request) || expires == 0) return;
        network.execute(() -> {
            try {
                JSONObject response = MediaHttp.json("https://link.stremio.com/api/v2/read?type=Read&code=" + code, null, 8000);
                JSONObject result = response.optJSONObject("result");
                String auth = result == null ? "" : result.optString("authKey", "");
                if (auth.isEmpty()) { main.postDelayed(() -> poll(code, request), 3000); return; }
                JSONArray addons = StremioAccount.fetch(auth);
                String[] sealed = AddonStore.sealAccount(addons, auth);
                main.post(() -> {
                    if (!current(request)) return;
                    try {
                        AddonStore.commit(this, sealed);
                        setResult(RESULT_OK);
                        complete = true;
                        expires = 0;
                        main.removeCallbacks(tick);
                        if (automatic && active) { finish(); return; }
                        showConnected();
                        status.setText(String.format(Ui.tr("%d complementos importados.", "%d add-ons imported."), addons.length()));
                    } catch (Exception e) {
                        failure(request);
                    }
                });
            } catch (Exception ignored) {
                main.postDelayed(() -> poll(code, request), 3000);
            }
        });
    }

    private void failure(int request) {
        if (!current(request)) return;
        generation++;
        expires = 0;
        main.removeCallbacks(tick);
        qrCard.setVisibility(View.INVISIBLE);
        spinner.setVisibility(View.GONE);
        status.setText(Ui.tr("Não deu para falar com o Stremio. Confira a internet da TV.", "Couldn’t reach Stremio. Check the TV’s internet."));
        buttons.removeAllViews();
        button(Ui.tr("Tentar de novo", "Try again"), v -> begin()).requestFocus();
        button(Ui.tr("Agora não", "Not now"), v -> finish());
    }

    private boolean current(int request) { return request == generation && !isFinishing() && !isDestroyed(); }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        if (complete && automatic) finish();
        else if (!begun && (automatic || !AddonStore.connected(this))) begin();
        else if (expires != 0 && SystemClock.elapsedRealtime() >= expires) begin();
        else if (expires != 0) { main.removeCallbacks(tick); tick.run(); }
    }

    @Override protected void onPause() { active = false; main.removeCallbacks(tick); super.onPause(); }

    @Override protected void onDestroy() {
        generation++;
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }
}
