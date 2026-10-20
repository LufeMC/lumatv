package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Import add-ons through Stremio's official device authorization page. */
public class ConnectSourcesActivity extends TvActivity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, link;
    private Button connect;
    private int generation;
    private long expires;
    private boolean active;
    private boolean automatic, begun, complete;
    private ImageView qr;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this);
        automatic = getIntent().getBooleanExtra("automatic", false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(28), dp(48), dp(24)); root.setBackgroundColor(0xFF151518);
        TextView heading = TvWidgets.text(this, Ui.tr("Conta Stremio", "Stremio account"), 26);
        heading.setTypeface(Ui.displaySemibold); heading.setAccessibilityHeading(true); root.addView(heading);
        root.addView(TvWidgets.text(this, Ui.tr("Uma autorização de conta é necessária. Depois, seus complementos serão carregados automaticamente.",
                "One account approval is required. Your add-ons will then load automatically."), 18));
        root.addView(TvWidgets.text(this, Ui.tr("Entre no site oficial pelo telefone. Sua senha não passa pela LumaTV.",
                "Sign in on the official website with your phone. LumaTV does not receive your password."), 16));
        root.addView(TvWidgets.text(this, Ui.tr("A TV protege sua conexão com criptografia e atualiza seus complementos automaticamente.",
                "The TV encrypts your account connection and updates your add-ons automatically."), 16));
        LinearLayout code = new LinearLayout(this); code.setGravity(android.view.Gravity.CENTER_VERTICAL);
        code.setPadding(0, dp(12), 0, dp(12));
        qr = new ImageView(this); qr.setContentDescription(Ui.tr("Código QR para autorizar o Stremio", "QR code for Stremio approval"));
        qr.setVisibility(android.view.View.GONE); code.addView(qr, new LinearLayout.LayoutParams(dp(128), dp(128)));
        link = TvWidgets.text(this, "", 24); link.setTextIsSelectable(false); link.setPadding(dp(20), 0, 0, 0);
        code.addView(link, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(code);
        status = TvWidgets.text(this, AddonStore.connected(this) ? Ui.tr("Fontes conectadas.", "Sources connected.")
                : Ui.tr("Preparando autorização…", "Preparing account approval…"), 17);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        connect = TvWidgets.button(this, Ui.tr("Conectar Stremio", "Connect Stremio"), v -> begin());
        root.addView(connect, params());
        if (automatic) connect.setVisibility(android.view.View.GONE);
        if (!automatic && AddonStore.connected(this)) root.addView(TvWidgets.button(this, Ui.tr("Desconectar conta", "Disconnect account"), v ->
                new AlertDialog.Builder(this).setTitle(Ui.tr("Desconectar fontes?", "Disconnect sources?"))
                        .setMessage(Ui.tr("Isso remove os complementos apenas da LumaTV.",
                                "This removes the add-ons from LumaTV only."))
                        .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                        .setPositiveButton(Ui.tr("Desconectar", "Disconnect"), (dialog, which) -> {
                            generation++; AddonStore.clear(this); setResult(RESULT_OK);
                            link.setText(""); status.setText(Ui.tr("Fontes desconectadas.", "Sources disconnected."));
                            connect.setEnabled(true);
                        }).show()), params());
        root.addView(TvWidgets.button(this, Ui.tr("Voltar", "Back"), v -> finish()), params());
        setContentView(root); if (!automatic) connect.requestFocus();
    }
    private void begin() {
        begun = true; int request = ++generation; connect.setEnabled(false); link.setText(""); qr.setVisibility(android.view.View.GONE);
        status.setText(Ui.tr("Obtendo código…", "Getting a code…"));
        network.execute(() -> {
            try {
                JSONObject result = MediaHttp.result(MediaHttp.json("https://link.stremio.com/api/v2/create?type=Create", null, 8000));
                String code = result.getString("code"), address = result.getString("link");
                if (!code.matches("[A-Za-z0-9]{4,12}") || !address.equals("https://link.stremio.com/" + code)) throw new Exception();
                String image = result.optString("qrcode", "");
                android.graphics.Bitmap bitmap = qrImage(image, address);
                main.post(() -> {
                    if (!current(request)) return;
                    expires = android.os.SystemClock.elapsedRealtime() + 5 * 60 * 1000;
                    link.setText(address);
                    if (bitmap != null) { qr.setImageBitmap(bitmap); qr.setVisibility(android.view.View.VISIBLE); }
                    status.setText(Ui.tr("Abra este endereço no telefone. Autorize a conexão com o Stremio.",
                            "Open this address on your phone. Authorize the Stremio connection."));
                    poll(code, request);
                });
            } catch (Exception ignored) { main.post(() -> failure(request)); }
        });
    }
    private void poll(String code, int request) {
        if (!current(request)) return;
        if (android.os.SystemClock.elapsedRealtime() >= expires) { failure(request); return; }
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
                            link.setText(""); connect.setEnabled(true); setResult(RESULT_OK); complete = true;
                                status.setText(String.format(Ui.tr("%d complementos importados. Selecione Voltar para escolher um título.",
                                        "%d add-ons imported. Select Back to choose a title."), addons.length()));
                            if (automatic && active) finish();
                        } catch (Exception ignored) { main.post(() -> failure(request)); }
                });
            } catch (Exception ignored) { main.postDelayed(() -> poll(code, request), 3000); }
        });
    }
    private void failure(int request) {
        if (!current(request)) return;
        generation++; connect.setEnabled(true); link.setText("");
        qr.setVisibility(android.view.View.GONE); connect.setVisibility(android.view.View.VISIBLE);
        connect.setText(Ui.tr("Tentar novamente", "Try again"));
        status.setText(Ui.tr("A conexão expirou ou falhou. Selecione Conectar Stremio para tentar novamente.",
                "The connection expired or failed. Select Try again."));
        connect.requestFocus();
    }
    private boolean current(int request) { return request == generation && !isFinishing() && !isDestroyed(); }
    @Override protected void onResume() {
        super.onResume(); active = true;
        if (complete && automatic) finish();
        else if (automatic && !begun) begin();
    }
    @Override protected void onPause() { active = false; super.onPause(); }
    @Override protected void onDestroy() { generation++; main.removeCallbacksAndMessages(null); network.shutdownNow(); super.onDestroy(); }
    private int dp(float value) { return TvWidgets.dp(this, value); }
    private static android.graphics.Bitmap qrImage(String image, String address) {
        java.net.HttpURLConnection connection = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(image);
            if (!"https".equals(uri.getScheme()) || !"link.stremio.com".equals(uri.getHost())
                    || !"/qr".equals(uri.getPath()) || !address.equals(uri.getQueryParameter("data"))) return null;
            connection = (java.net.HttpURLConnection) new java.net.URL(image).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 256 * 1024) return null;
            try (java.io.InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] data = new byte[4096]; int count;
                while ((count = input.read(data)) != -1) { if (output.size() + count > 256 * 1024) return null; output.write(data, 0, count); }
                byte[] encoded = output.toByteArray(); return android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            }
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(dp(300), dp(48)); value.topMargin = dp(12); return value;
    }
}
