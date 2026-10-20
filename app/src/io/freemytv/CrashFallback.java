package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * What the home screen shows after it stopped 3 times in 5 minutes (Watchdog), instead of trying to draw again:
 * the Flora mark, what happened, and three ways forward. Built from plain views, nothing that could fail the same way.
 * HomeActivity: {@code if (Watchdog.crashedTooOften(this)) { HomeMode.requestRevertHome(this);
 * setContentView(CrashFallback.create(this)); return; }}
 */
final class CrashFallback {
    private CrashFallback() {}

    static View create(Activity a) {
        // No Ui.init here: it loads caches, and this screen must not depend on anything that could have failed.
        if (Ui.regular == null) {
            Ui.regular = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false);
            Ui.medium = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false);
            Ui.displaySemibold = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false);
        }
        Kit.Theme t = Kit.Theme.night();
        long since = System.currentTimeMillis() - 5 * 60_000L;
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(t.ground);

        FloraMark mark = new FloraMark(a, t.ink);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(Kit.px(a, 128), Kit.px(a, 100));
        mp.leftMargin = Kit.px(a, 92);
        mp.topMargin = Kit.px(a, 61);
        root.addView(mark, mp);

        LinearLayout content = Kit.row(a, LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        cp.leftMargin = cp.rightMargin = Kit.px(a, 96);
        root.addView(content, cp);

        LinearLayout words = Kit.row(a, LinearLayout.VERTICAL);
        content.addView(words, new LinearLayout.LayoutParams(Kit.px(a, 760), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = Kit.text(a, Ui.tr("A LumaTV parou algumas vezes", "LumaTV stopped a few times"), 56, Ui.displaySemibold, t.ink);
        title.setLetterSpacing(-0.025f);
        words.addView(title);
        TextView line = Kit.text(a, "", 30, Ui.regular, t.ink2);
        line.setLineSpacing(0, 1.3f);
        words.addView(line, Kit.top(a, Kit.fill(), 20));
        TextView note = Kit.text(a, Ui.tr("Nada foi desinstalado.", "Nothing was uninstalled."), 24, Ui.regular, t.ink3);
        words.addView(note, Kit.top(a, Kit.fill(), 24));

        View gap = new View(a);
        content.addView(gap, Kit.weight());
        LinearLayout buttons = Kit.row(a, LinearLayout.VERTICAL);
        content.addView(buttons, new LinearLayout.LayoutParams(Kit.px(a, 600), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView again = action(a, t, Ui.tr("Tentar a LumaTV de novo", "Try LumaTV again"), v -> {
            Watchdog.clear(a);
            HomeMode.request(a, HomeMode.FREE);   // puts LumaTV's home screen back (and checks the rest)
            a.recreate();
        });
        buttons.addView(again, Kit.fill());
        buttons.addView(action(a, t, Ui.tr("Ajustes da LumaTV", "LumaTV settings"),
                v -> a.startActivity(new Intent(a, SettingsActivity.class))), Kit.top(a, Kit.fill(), 12));
        buttons.addView(action(a, t, Ui.tr("Ajustes da TV", "TV settings"),
                v -> a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))),
                Kit.top(a, Kit.fill(), 12));

        // What the main process did about it: switched back, still switching, or couldn't.
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable poll = new Runnable() {
            @Override public void run() {
                JSONObject revert = HomeMode.state(a).optJSONObject("revert");
                boolean known = revert != null && revert.optLong("at") >= since;
                if (known && revert.optBoolean("ok")) {
                    line.setText(Ui.tr("Para a TV continuar fácil de usar, ela voltou ao Início original por enquanto.",
                            "To keep your TV usable, it switched back to the original home screen for now."));
                } else if (known) {
                    line.setText(Ui.tr("Não deu para voltar ao Início original sozinha. Escolha o que fazer.",
                            "It couldn't switch back to the original home screen by itself. Choose what to do."));
                } else {
                    line.setText(Ui.tr("Para a TV continuar fácil de usar, ela está voltando ao Início original.",
                            "To keep your TV usable, it's switching back to the original home screen."));
                }
                if (root.isAttachedToWindow() || !known) handler.postDelayed(this, 1000);
            }
        };
        poll.run();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { again.requestFocus(); }
            @Override public void onViewDetachedFromWindow(View v) { handler.removeCallbacksAndMessages(null); }
        });
        return root;
    }

    private static TextView action(Context c, Kit.Theme t, String label, View.OnClickListener click) {
        TextView b = Kit.text(c, label, 28, Ui.medium, t.text());
        b.setDuplicateParentStateEnabled(false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Kit.px(c, 24);
        b.setPadding(pad, 0, pad, 0);
        b.setMinHeight(Kit.px(c, 76));
        b.setBackground(Kit.fill(c, 0x14FFFFFF, t.focus, 18));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    /** The Flora line mark (the board's SVG, viewBox 40 85 385 300), stroked at 2 px whatever its size. */
    static final class FloraMark extends View {
        private static final String[] STROKES = {
            "M137.1 370.4 Q114.9 376.7 111.8 365.9 Q86.0 369.5 82.6 358.1 Q63.7 353.7 68.3 337.9 Q52.6 330.2 65.7 313.1 Q52.8 303.2 62.2 289.4 Q50.9 278.0 66.1 265.7 Q54.2 252.9 71.5 243.1 Q63.4 229.3 77.2 221.0 Q71.3 206.5 90.0 201.9 Q84.0 185.8 103.1 184.2 Q101.4 169.1 119.0 169.2 Q120.6 155.8 137.4 158.1 Q138.4 141.5 151.0 140.9 Q152.9 121.1 167.8 125.9 Q173.9 110.3 188.6 118.2 Q196.8 102.3 209.8 109.2 Q220.5 97.0 232.6 105.6 Q244.9 94.2 255.4 109.2 Q268.3 103.0 276.6 118.2 Q291.9 108.6 297.4 125.9 Q312.0 121.7 314.2 140.9 Q327.8 140.0 327.8 158.1 Q344.2 156.2 346.2 169.2 Q365.2 168.1 362.1 184.2 Q379.9 186.6 375.2 201.9 Q393.0 206.9 388.0 221.0 Q401.4 229.4 393.7 243.1 Q411.3 252.8 399.1 265.7 Q415.2 278.0 403.0 289.4 Q413.8 303.5 399.5 313.1 Q409.5 329.2 396.9 337.9 Q404.1 355.0 382.6 358.1 Q382.8 371.9 353.4 365.9 Q350.9 377.2 328.2 370.4",
            "M142.9 176.3 Q148.8 184.8 139.0 193.3 Q144.9 201.7 135.1 210.2 Q141.0 218.7 131.2 227.2 Q137.1 235.7 127.3 244.1 Q133.2 252.6 123.4 261.1 Q132.4 269.9 125.4 278.8 Q134.3 287.6 127.3 296.4 Q136.3 305.2 129.3 314.1 Q138.2 322.9 131.2 331.7 Q140.2 340.5 133.2 349.4 Q142.1 358.2 135.1 367.0",
            "M322.3 168.4 Q316.4 176.9 326.2 185.4 Q320.3 193.9 330.1 202.4 Q324.2 210.8 334.0 219.3 Q328.1 227.8 337.9 236.3 Q332.0 244.8 341.8 253.2 Q332.2 262.7 339.8 272.2 Q330.3 281.7 337.9 291.2 Q328.3 300.6 335.9 310.1 Q326.4 319.6 334.0 329.1 Q324.4 338.6 332.0 348.0 Q322.5 357.5 330.1 367.0",
            "M211 305 Q222 315 233 307 Q244 315 255 305",
            "M178 243 Q190 230 202 243",
            "M264 243 Q276 230 288 243",
        };
        /** The four curls: start points of "a12.7 12.7 0 1 1 17.7 11.4". */
        private static final float[][] CURLS = {{166.3f, 159.2f}, {205.3f, 176.8f}, {244.3f, 159.2f}, {278.4f, 176.8f}};
        private static final String NOSE = "M219 276 Q233 269 247 276 Q250 285 240 292 Q233 297 226 292 Q216 285 219 276 Z";
        private static final String TONGUE = "M226 309 Q233 330 240 309 Z";

        private final Path lines = new Path(), nose = parse(NOSE), tongue = parse(TONGUE);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final Path scaled = new Path();
        private final int ink;

        FloraMark(Context c, int ink) {
            super(c);
            this.ink = ink;
            for (String s : STROKES) lines.addPath(parse(s));
            RectF oval = new RectF();
            for (float[] p : CURLS) {
                // Circle of r 12.7 through the start and start + (17.7, 11.4), the long way round, clockwise.
                float cx = p[0] + 12.7f, cy = p[1] - 0.27f;
                oval.set(cx - 12.7f, cy - 12.7f, cx + 12.7f, cy + 12.7f);
                Path curl = new Path();
                curl.arcTo(oval, 178.8f, 248f, true);
                lines.addPath(curl);
            }
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(ink);
            stroke.setStrokeWidth(Math.max(2f, Look.s(c, 2)));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float k = Math.min(w / 385f, h / 300f);
            matrix.reset();
            matrix.postTranslate(-40, -85);
            matrix.postScale(k, k);
            matrix.postTranslate((w - 385 * k) / 2f, (h - 300 * k) / 2f);
        }

        @Override protected void onDraw(Canvas canvas) {
            scaled.set(lines);
            scaled.transform(matrix);
            canvas.drawPath(scaled, stroke);
            scaled.set(nose);
            scaled.transform(matrix);
            fill.setColor(ink);
            canvas.drawPath(scaled, fill);
            scaled.set(tongue);
            scaled.transform(matrix);
            fill.setColor(Look.TONGUE);
            canvas.drawPath(scaled, fill);
        }

        /** M, Q and Z with absolute coordinates: all the mark needs. */
        static Path parse(String d) {
            Path p = new Path();
            String[] t = d.trim().split("\\s+");
            int i = 0;
            while (i < t.length) {
                String cmd = t[i];
                char op = cmd.charAt(0);
                float[] n = new float[4];
                if (op == 'Z') { p.close(); i++; continue; }
                int count = op == 'Q' ? 4 : 2;
                n[0] = Float.parseFloat(cmd.substring(1));
                for (int j = 1; j < count; j++) n[j] = Float.parseFloat(t[i + j]);
                if (op == 'M') p.moveTo(n[0], n[1]); else p.quadTo(n[0], n[1], n[2], n[3]);
                i += count;
            }
            return p;
        }
    }
}
