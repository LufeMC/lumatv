package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The approved screensaver: a dark landscape, clock, date, and weather. */
final class DreamView extends View implements HomeData.Listener {
    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint();
    private final Paint clockPaint = Ui.numbers(Ui.thin, 240, Look.INK);
    private final Paint detailPaint = Ui.text(Ui.regular, 32, 0xBDF5F3EF);
    private final RectF screen = new RectF();
    private Landscape land;
    private boolean running, loading;
    private int generation;
    private String time = "", detail = "";
    private float scale = 1, driftX, driftY;
    long drawCount;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateText(); loadLandscape(); data.refreshWeatherIfStale(); invalidate();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    DreamView(Context context) {
        super(context); data = HomeData.get(context);
        setBackgroundColor(Look.GROUND_NIGHT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        clockPaint.setLetterSpacing(-0.05f);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        data.addListener(this); tick.run();
    }

    void stop() {
        running = false; generation++; loading = false;
        handler.removeCallbacks(tick); data.removeListener(this); land = null;
        worker.shutdownNow(); invalidate();
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    private void loadLandscape() {
        if (land != null || loading) return;
        loading = true; final int request = generation;
        worker.execute(() -> {
            Landscape loaded = Landscape.load(getContext().getApplicationContext(), Landscape.NIGHT);
            handler.post(() -> {
                if (request != generation || !running) return;
                loading = false; land = loaded; invalidate();
            });
        });
    }

    private void updateText() {
        long now = System.currentTimeMillis();
        time = Ui.clock(now);
        String date = new SimpleDateFormat(Ui.en ? "EEEE, MMMM d" : "EEEE, d 'de' MMMM", Ui.en ? Locale.US : Ui.PT).format(new Date(now));
        if (!date.isEmpty()) date = date.substring(0, 1).toUpperCase(Ui.en ? Locale.US : Ui.PT) + date.substring(1);
        HomeData.Weather weather = data.weather;
        detail = date + (Double.isNaN(weather.temp) ? "" : " · " + HomeData.degrees(weather.temp) + " " + HomeData.condition(weather.code, weather.isDay));
        long minute = now / 60_000;
        driftX = (float) Math.sin(minute * Math.PI / 12) * 12;
        driftY = (float) Math.cos(minute * Math.PI / 12) * 8;
        setContentDescription(time + ". " + detail);
    }

    @Override public void onData() { if (running) { updateText(); invalidate(); } }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        scale = width / 1920f; screen.set(0, 0, width, height);
        clockPaint.setTextSize(240 * scale); detailPaint.setTextSize(32 * scale);
        shade.setShader(new LinearGradient(0, height, 0, height * 0.55f, 0x8C04060A, 0x0004060A, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        drawCount++;
        if (land != null) canvas.drawBitmap(land.image, null, screen, image);
        canvas.drawRect(screen, shade);
        float x = (120 + driftX) * scale, baseline = getHeight() - (104 - driftY) * scale;
        canvas.drawText(time, x, baseline - 60 * scale, clockPaint);
        canvas.drawText(detail, x + 10 * scale, baseline, detailPaint);
    }
}
