package io.freemytv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The player's screens, one class per canvas board: controls (PlayerControls), seeking (PlayerScrub), Up next,
 * switching sources (PlayerRecover), Still watching, Waiting for the network, Subtitle timing, Subtitles synced.
 * Views only; PlayerActivity decides what shows. All sizes in 1920×1080 design px.
 */
final class PlayerScreens {
    private PlayerScreens() {}

    static final int DARK = Look.GROUND;

    static FrameLayout.LayoutParams at(Context c, int w, int h, int gravity, float left, float top, float right, float bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.leftMargin = Look.si(c, left); lp.topMargin = Look.si(c, top); lp.rightMargin = Look.si(c, right); lp.bottomMargin = Look.si(c, bottom);
        return lp;
    }

    static int px(Context c, float v) { return Look.si(c, v); }

    static FrameLayout layer(Context c, int background) {
        FrameLayout f = new FrameLayout(c);
        if (background != 0) f.setBackgroundColor(background);
        f.setVisibility(View.GONE);
        return f;
    }

    /** Title and episode line at the top left; shared by several screens. */
    static final class Header extends LinearLayout {
        final TextView title, line;
        final TextView badge;
        Header(Context c) {
            super(c); setOrientation(VERTICAL);
            title = PlayerViews.text(c, "", Look.HEADING, Look.INK, Ui.semibold);
            addView(title);
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
            line = PlayerViews.text(c, "", Look.LABEL, Look.INK_2, Ui.regular);
            row.addView(line, new LayoutParams(-2, -2));
            badge = PlayerViews.text(c, "", Look.SMALL, Look.INK_2, Ui.semibold);
            GradientDrawable border = new GradientDrawable(); border.setCornerRadius(Look.s(c, 8));
            border.setStroke(Math.max(1, Math.round(Look.s(c, 1.5f))), Look.INK_4);
            badge.setBackground(border); badge.setPadding(px(c, 10), px(c, 4), px(c, 10), px(c, 4)); badge.setLetterSpacing(0.04f);
            badge.setVisibility(GONE);
            LayoutParams bp = new LayoutParams(-2, -2); bp.leftMargin = px(c, 16);
            row.addView(badge, bp);
            LayoutParams rp = new LayoutParams(-2, -2); rp.topMargin = px(c, 8);
            addView(row, rp);
        }
        void set(String show, String episode) {
            title.setText(show);
            line.setText(episode == null ? "" : episode);
            line.setVisibility(episode == null || episode.isEmpty() ? GONE : VISIBLE);
        }
        void badge(String value) { badge.setText(value == null ? "" : value); badge.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE); }
    }

    /** PlayerControls: header, clock and "Ends at", times, the bar, and the button row. */
    static final class Controls extends FrameLayout {
        final Header header;
        final TextView clock, ends, elapsed, remaining;
        final PlayerViews.ScrubBar bar;
        final PlayerIcon pause, back10, forward10, more;
        final PlayerViews.Pill next, tracks;
        Controls(Context c, View.OnClickListener onPause, View.OnClickListener onBack, View.OnClickListener onForward,
                 View.OnClickListener onNext, View.OnClickListener onTracks, View.OnClickListener onMore) {
            super(c);
            addView(PlayerViews.shade(c, Ui.alpha(Look.PLAYER, 0.6f), 0), new LayoutParams(-1, px(c, 238), Gravity.TOP));
            addView(PlayerViews.shade(c, 0, Ui.alpha(Look.PLAYER, 0.82f)), new LayoutParams(-1, px(c, 454), Gravity.BOTTOM));
            header = new Header(c);
            addView(header, at(c, px(c, 1100), -2, Gravity.TOP | Gravity.START, 96, 56, 0, 0));
            LinearLayout right = new LinearLayout(c); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(Gravity.END);
            clock = PlayerViews.text(c, "", Look.HEADING, Look.INK, Ui.medium); clock.setFontFeatureSettings("'tnum'");
            right.addView(clock);
            ends = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2); ep.topMargin = px(c, 8);
            right.addView(ends, ep);
            addView(right, at(c, -2, -2, Gravity.TOP | Gravity.END, 0, 56, 96, 0));
            FrameLayout times = new FrameLayout(c);
            elapsed = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); elapsed.setFontFeatureSettings("'tnum'");
            remaining = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); remaining.setFontFeatureSettings("'tnum'");
            times.addView(elapsed, new LayoutParams(-2, -2, Gravity.START));
            times.addView(remaining, new LayoutParams(-2, -2, Gravity.END));
            addView(times, at(c, -1, -2, Gravity.TOP, 96, 868, 96, 0));
            bar = new PlayerViews.ScrubBar(c);
            // A taller view than the 6 px track, so the 26 px knob and its halo fit.
            addView(bar, at(c, -1, px(c, 44), Gravity.TOP, 96 - 13, 893, 96 - 13, 0));
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
            pause = new PlayerIcon(c, "pause", Ui.tr("Pausar", "Pause"), onPause).restFill(Look.RAISED);
            row.addView(pause, new LinearLayout.LayoutParams(px(c, 76), px(c, 76)));
            back10 = new PlayerIcon(c, "back10", Ui.tr("Voltar 10 segundos", "Back 10 seconds"), onBack).restFill(0);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(px(c, 76), px(c, 76)); gap.leftMargin = px(c, 12);
            row.addView(back10, gap);
            forward10 = new PlayerIcon(c, "forward10", Ui.tr("Avançar 10 segundos", "Forward 10 seconds"), onForward).restFill(0);
            row.addView(forward10, new LinearLayout.LayoutParams(gap));
            row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
            next = PlayerViews.pill(c, "next", Ui.tr("Próximo episódio", "Next episode"), onNext);
            next.setVisibility(GONE);
            row.addView(next, new LinearLayout.LayoutParams(-2, px(c, 64)));
            tracks = PlayerViews.pill(c, "subtitles", Ui.tr("Áudio e legendas", "Audio & subtitles"), onTracks);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, px(c, 64)); tp.leftMargin = px(c, 8);
            row.addView(tracks, tp);
            more = new PlayerIcon(c, "more", Ui.tr("Mais opções", "More options"), onMore).restFill(Look.RAISED);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(c, 64), px(c, 64)); mp.leftMargin = px(c, 8);
            row.addView(more, mp);
            addView(row, at(c, -1, px(c, 76), Gravity.TOP, 96, 960, 96, 0));
            // Up from the buttons goes to the bar; down from the bar to Play/Pause.
            int barId = bar.getId();
            for (View v : new View[]{pause, back10, forward10, next, tracks, more}) v.setNextFocusUpId(barId);
            bar.setNextFocusDownId(pause.getId());
        }
    }

    /** PlayerScrub: frames, the bar with the jump, and the hints. */
    static final class Scrub extends FrameLayout {
        final SeekPreview preview;
        final PlayerViews.ScrubBar bar;
        final TextView jump, countdown;
        final PlayerIcon ring;
        Scrub(Context c) {
            super(c);
            View dim = new View(c); dim.setBackgroundColor(Ui.alpha(Look.PLAYER, 0.45f));
            addView(dim, new LayoutParams(-1, -1));
            addView(PlayerViews.shade(c, 0, Ui.alpha(Look.PLAYER, 0.85f)), new LayoutParams(-1, px(c, 648), Gravity.BOTTOM));
            preview = new SeekPreview(c);
            addView(preview, at(c, -1, -2, Gravity.TOP, 0, 452, 0, 0));
            bar = new PlayerViews.ScrubBar(c); bar.setFocusable(false);
            addView(bar, at(c, -1, px(c, 44), Gravity.TOP, 96 - 13, 893, 96 - 13, 0));
            FrameLayout hints = new FrameLayout(c);
            jump = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); jump.setFontFeatureSettings("'tnum'");
            hints.addView(jump, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
            LinearLayout keys = new LinearLayout(c); keys.setGravity(Gravity.CENTER_VERTICAL);
            ring = new PlayerIcon(c, "none"); ring.colors(Look.INK, Look.INK); ring.progress(1);
            keys.addView(ring, new LinearLayout.LayoutParams(px(c, 26), px(c, 26)));
            countdown = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = px(c, 10);
            keys.addView(countdown, cp);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2); hp.leftMargin = px(c, 28);
            keys.addView(PlayerViews.hint(c, "OK", Ui.tr("Ir agora", "Jump now"), Look.INK_2), hp);
            keys.addView(PlayerViews.hint(c, Ui.tr("Voltar", "Back"), Ui.tr("Continuar assistindo", "Keep watching"), Look.INK_2), new LinearLayout.LayoutParams(hp));
            hints.addView(keys, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
            addView(hints, at(c, -1, -2, Gravity.TOP, 96, 946, 96, 0));
            setVisibility(GONE);
        }
        /** Updates the jump text and the 3 s ring (left: 0..1). */
        void update(long original, long target, long duration, float left, int seconds) {
            bar.seeking(original, target, duration);
            long delta = target - original;
            jump.setText((delta >= 0 ? "+" : "−") + SeekPreview.clock(Math.abs(delta)) + Ui.tr(" de ", " from ") + SeekPreview.clock(original));
            ring.progress(left);
            countdown.setText(Ui.tr("Vai para cá em ", "Jumps here in ") + seconds + " s");
        }
    }

    /** Skip intro / Skip recap (bottom right), with Keep … beside it. */
    static final class Skip extends LinearLayout {
        final PlayerViews.Pill keep, skip;
        Skip(Context c, View.OnClickListener onKeep, View.OnClickListener onSkip) {
            super(c); setGravity(Gravity.CENTER_VERTICAL);
            keep = new PlayerViews.Pill(c, null, false, Ui.tr("Manter abertura", "Keep intro"), 64, Look.META, Ui.alpha(Look.GROUND, 0.4f));
            keep.setOnClickListener(onKeep);
            addView(keep, new LayoutParams(-2, px(c, 64)));
            skip = new PlayerViews.Pill(c, "skip", true, Ui.tr("Pular abertura", "Skip intro"), 76, Look.LABEL, Ui.alpha(Look.GROUND, 0.4f));
            skip.setOnClickListener(onSkip);
            LayoutParams sp = new LayoutParams(-2, px(c, 76)); sp.leftMargin = px(c, 12);
            addView(skip, sp);
            setVisibility(GONE);
        }
        void labels(boolean recap) {
            keep.setText(recap ? Ui.tr("Manter resumo", "Keep recap") : Ui.tr("Manter abertura", "Keep intro"));
            skip.setText(recap ? Ui.tr("Pular resumo", "Skip recap") : Ui.tr("Pular abertura", "Skip intro"));
        }
    }

    /** PlayerUpNext: the video shrinks to the left; the next episode and its countdown on the right. */
    static final class UpNext extends FrameLayout {
        final TextView current, left, label, name, meta;
        final View progressTrack, progressFill;
        final ImageView still;
        final PlayerViews.Pill play, credits;
        UpNext(Context c, View.OnClickListener onPlay, View.OnClickListener onCredits) {
            super(c); setBackgroundColor(DARK);
            LinearLayout under = new LinearLayout(c); under.setGravity(Gravity.CENTER_VERTICAL);
            current = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular);
            under.addView(current, new LinearLayout.LayoutParams(-2, -2));
            FrameLayout track = new FrameLayout(c);
            progressTrack = track; track.setBackground(Look.round(c, Look.PROGRESS_TRACK, 3));
            progressFill = new View(c); progressFill.setBackground(Look.round(c, Look.INK_2, 3));
            track.addView(progressFill, new LayoutParams(0, -1));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, px(c, 5), 1); tp.leftMargin = px(c, 20); tp.rightMargin = px(c, 20);
            under.addView(track, tp);
            left = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); left.setFontFeatureSettings("'tnum'");
            under.addView(left);
            addView(under, at(c, px(c, 1120), -2, Gravity.TOP | Gravity.START, 96, 120 + 630 + 28, 0, 0));
            LinearLayout column = new LinearLayout(c); column.setOrientation(LinearLayout.VERTICAL);
            label = PlayerViews.text(c, Ui.tr("A seguir", "Up next"), Look.META, Look.INK_3, Ui.medium);
            column.addView(label);
            still = new ImageView(c); still.setScaleType(ImageView.ScaleType.CENTER_CROP);
            still.setBackground(Look.round(c, Look.FALLBACK_BOTTOM, 18)); still.setClipToOutline(true);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(c, 528), px(c, 297)); sp.topMargin = px(c, 20);
            column.addView(still, sp);
            name = PlayerViews.text(c, "", 34, Look.INK, Ui.semibold); name.setSingleLine(false); name.setMaxLines(2);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = px(c, 26);
            column.addView(name, np);
            meta = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = px(c, 10);
            column.addView(meta, mp);
            play = new PlayerViews.Pill(c, "play", true, Ui.tr("Assistir agora", "Play now"), 76, Look.LABEL, Look.RAISED);
            play.setOnClickListener(onPlay);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, px(c, 76)); pp.topMargin = px(c, 48);
            column.addView(play, pp);
            credits = new PlayerViews.Pill(c, null, false, Ui.tr("Ver os créditos", "Watch the credits"), 76, Look.LABEL, Look.RAISED);
            credits.setOnClickListener(onCredits);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, px(c, 76)); cp.topMargin = px(c, 14);
            column.addView(credits, cp);
            addView(column, at(c, px(c, 528), -2, Gravity.TOP | Gravity.START, 1296, 120, 0, 0));
            setVisibility(GONE);
        }
        /** Where the video goes while this shows (design px): 96,120 1120×630. */
        static int[] videoBox(Context c) { return new int[]{px(c, 96), px(c, 120), px(c, 1120), px(c, 630)}; }
        void progress(long position, long duration) {
            int w = progressTrack.getWidth();
            if (w > 0 && duration > 0) {
                int fill = (int) (w * Math.min(1, position / (double) duration));
                if (progressFill.getLayoutParams().width != fill) { progressFill.getLayoutParams().width = fill; progressFill.requestLayout(); }
            }
            left.setText("−" + SeekPreview.clock(Math.max(0, duration - position)));
        }
    }

    /** PlayerRecover: switching sources, with why and where playback picks up. Also the "nothing played" state. */
    static final class Recover extends FrameLayout {
        final Header header;
        final PlayerIcon spinner;
        final TextView title, line, count;
        final LinearLayout dashes;
        final PlayerViews.Pill choose, back;
        Recover(Context c, View.OnClickListener onChoose, View.OnClickListener onBack) {
            super(c); setBackgroundColor(DARK);
            header = new Header(c);
            addView(header, at(c, px(c, 1400), -2, Gravity.TOP | Gravity.START, 96, 56, 0, 0));
            LinearLayout column = new LinearLayout(c); column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER_HORIZONTAL);
            spinner = new PlayerIcon(c, "spinner"); spinner.colors(Look.INK, Look.INK);
            column.addView(spinner, new LinearLayout.LayoutParams(px(c, 64), px(c, 64)));
            title = PlayerViews.text(c, "", 52, Look.INK, Ui.semibold); title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.topMargin = px(c, 40);
            column.addView(title, tp);
            line = PlayerViews.text(c, "", Look.BODY, Look.INK_2, Ui.regular); line.setGravity(Gravity.CENTER);
            line.setSingleLine(false); line.setMaxLines(2); line.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(c, 1400), -2); lp.topMargin = px(c, 18);
            column.addView(line, lp);
            LinearLayout progress = new LinearLayout(c); progress.setGravity(Gravity.CENTER_VERTICAL);
            dashes = new LinearLayout(c); dashes.setGravity(Gravity.CENTER_VERTICAL);
            progress.addView(dashes);
            count = PlayerViews.text(c, "", Look.SMALL, Look.INK_3, Ui.regular);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = px(c, 14);
            progress.addView(count, cp);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.topMargin = px(c, 36);
            column.addView(progress, pp);
            LinearLayout buttons = new LinearLayout(c);
            choose = new PlayerViews.Pill(c, null, false, Ui.tr("Escolher uma fonte", "Choose a source"), 72, Look.LABEL, Look.RAISED);
            choose.setOnClickListener(onChoose);
            buttons.addView(choose, new LinearLayout.LayoutParams(-2, px(c, 72)));
            back = new PlayerViews.Pill(c, null, false, Ui.tr("Voltar aos episódios", "Back to episodes"), 72, Look.LABEL, Look.RAISED);
            back.setOnClickListener(onBack);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, px(c, 72)); bp.leftMargin = px(c, 16);
            buttons.addView(back, bp);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2); wp.topMargin = px(c, 64);
            column.addView(buttons, wp);
            addView(column, at(c, -1, -2, Gravity.TOP, 0, 330, 0, 0));
            setVisibility(GONE);
        }
        /** Source n of total: tried ones dim, the current one ink, later ones faint. */
        void sources(int current, int total, String quality) {
            Context c = getContext();
            total = Math.max(1, Math.min(12, total));
            dashes.removeAllViews();
            for (int i = 0; i < total; i++) {
                View d = new View(c);
                d.setBackground(Look.round(c, i < current ? Look.INK_4 : i == current ? Look.INK : Look.RAISED, 3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(c, 28), px(c, 6)); if (i > 0) lp.leftMargin = px(c, 8);
                dashes.addView(d, lp);
            }
            count.setText(String.format(Ui.tr("Fonte %d de %d", "Source %d of %d"), current + 1, total) + (quality == null || quality.isEmpty() ? "" : " · " + quality));
        }
    }

    /** PlayerStillWatching: a centered glass card over the blurred-dark frame. */
    static final class StillWatching extends FrameLayout {
        final Header header;
        final TextView clock, line, timer;
        final PlayerIcon ring;
        final PlayerViews.Pill keep, stop;
        StillWatching(Context c, View.OnClickListener onKeep, View.OnClickListener onStop) {
            super(c); setBackgroundColor(Ui.alpha(Look.GROUND, 0.9f));
            header = new Header(c);
            addView(header, at(c, px(c, 1400), -2, Gravity.TOP | Gravity.START, 96, 56, 0, 0));
            clock = PlayerViews.text(c, "", Look.HEADING, Look.INK, Ui.medium); clock.setFontFeatureSettings("'tnum'");
            addView(clock, at(c, -2, -2, Gravity.TOP | Gravity.END, 0, 56, 96, 0));
            LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackground(PlayerViews.glass(c, 36)); card.setPadding(px(c, 80), px(c, 64), px(c, 80), px(c, 56));
            TextView title = PlayerViews.text(c, Ui.tr("Ainda assistindo?", "Still watching?"), 52, Look.INK, Ui.semibold);
            title.setAccessibilityHeading(true);
            card.addView(title);
            line = PlayerViews.text(c, "", Look.BODY, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = px(c, 18);
            card.addView(line, lp);
            LinearLayout buttons = new LinearLayout(c);
            keep = new PlayerViews.Pill(c, null, false, Ui.tr("Continuar assistindo", "Keep watching"), 72, Look.LABEL, Look.RAISED);
            keep.setOnClickListener(onKeep);
            buttons.addView(keep, new LinearLayout.LayoutParams(-2, px(c, 72)));
            stop = new PlayerViews.Pill(c, null, false, Ui.tr("Parar por hoje", "Stop for tonight"), 72, Look.LABEL, Look.RAISED);
            stop.setOnClickListener(onStop);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, px(c, 72)); sp.leftMargin = px(c, 16);
            buttons.addView(stop, sp);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = px(c, 52);
            card.addView(buttons, bp);
            LinearLayout timerRow = new LinearLayout(c); timerRow.setGravity(Gravity.CENTER_VERTICAL);
            ring = new PlayerIcon(c, "pause"); ring.colors(Look.INK_3, Look.INK_3); ring.progress(1);
            timerRow.addView(ring, new LinearLayout.LayoutParams(px(c, 34), px(c, 34)));
            timer = PlayerViews.text(c, "", Look.META, Look.INK_3, Ui.regular); timer.setFontFeatureSettings("'tnum'");
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.leftMargin = px(c, 14);
            timerRow.addView(timer, tp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.topMargin = px(c, 40);
            card.addView(timerRow, rp);
            addView(card, new LayoutParams(-2, -2, Gravity.CENTER));
            setVisibility(GONE);
        }
    }

    /** PlayerNetwork: the pill at the bottom while the network is away. */
    static final class Network extends LinearLayout {
        final TextView line;
        Network(Context c) {
            super(c); setGravity(Gravity.CENTER_VERTICAL);
            setBackground(PlayerViews.glass(c, 54)); setPadding(px(c, 34), 0, px(c, 48), 0);
            PlayerIcon spin = new PlayerIcon(c, "spinner"); spin.colors(Look.INK, Look.INK);
            addView(spin, new LayoutParams(px(c, 40), px(c, 40)));
            LinearLayout texts = new LinearLayout(c); texts.setOrientation(VERTICAL);
            texts.addView(PlayerViews.text(c, Ui.tr("Aguardando a rede", "Waiting for the network"), Look.LABEL, Look.INK, Ui.semibold));
            line = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); line.setFontFeatureSettings("'tnum'");
            LayoutParams lp = new LayoutParams(-2, -2); lp.topMargin = px(c, 6);
            texts.addView(line, lp);
            LayoutParams tp = new LayoutParams(-2, -2); tp.leftMargin = px(c, 24);
            addView(texts, tp);
            setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            setVisibility(GONE);
        }
    }

    /** PlayerSubTiming: Left/Right move subtitles earlier or later; saved for this release. */
    static final class Timing extends FrameLayout {
        final TextView value, direction;
        final View marker, fill;
        final FrameLayout ruler;
        static final int RANGE_MS = 5000;
        Timing(Context c) {
            super(c);
            setBackground(PlayerViews.glass(c, 34));
            LinearLayout row = new LinearLayout(c); row.setPadding(px(c, 44), 0, px(c, 44), 0);
            LinearLayout left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            left.addView(PlayerViews.text(c, Ui.tr("Tempo da legenda", "Subtitle timing"), 30, Look.INK, Ui.semibold));
            LinearLayout saved = new LinearLayout(c); saved.setGravity(Gravity.CENTER_VERTICAL);
            PlayerIcon check = new PlayerIcon(c, "check"); check.colors(Look.INK_2, Look.INK_2);
            saved.addView(check, new LinearLayout.LayoutParams(px(c, 22), px(c, 22)));
            TextView note = PlayerViews.text(c, Ui.tr("Salvo para esta versão", "Saved for this release"), Look.SMALL, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.leftMargin = px(c, 10);
            saved.addView(note, np);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.topMargin = px(c, 12);
            left.addView(saved, sp);
            LinearLayout.LayoutParams leftp = new LinearLayout.LayoutParams(0, -2, 1); leftp.topMargin = px(c, 50);
            row.addView(left, leftp);
            LinearLayout middle = new LinearLayout(c); middle.setOrientation(LinearLayout.VERTICAL);
            LinearLayout spin = new LinearLayout(c); spin.setGravity(Gravity.CENTER_VERTICAL);
            spin.setBackground(Look.round(c, Look.INK, 38)); spin.setPadding(px(c, 10), 0, px(c, 10), 0);
            PlayerIcon earlier = new PlayerIcon(c, "chevron"); earlier.colors(Look.ON_INK, Look.ON_INK); earlier.setRotation(180);
            earlier.setBackground(Look.round(c, Ui.alpha(Look.ON_INK, 0.08f), 28));
            spin.addView(earlier, new LinearLayout.LayoutParams(px(c, 56), px(c, 56)));
            LinearLayout center = new LinearLayout(c); center.setGravity(Gravity.CENTER);
            value = PlayerViews.text(c, "", 32, Look.ON_INK, Ui.semibold); value.setFontFeatureSettings("'tnum'");
            center.addView(value);
            direction = PlayerViews.text(c, "", 28, Ui.alpha(Look.ON_INK, 0.62f), Ui.medium);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2); dp.leftMargin = px(c, 12);
            center.addView(direction, dp);
            spin.addView(center, new LinearLayout.LayoutParams(0, -2, 1));
            PlayerIcon later = new PlayerIcon(c, "chevron"); later.colors(Look.ON_INK, Look.ON_INK);
            later.setBackground(Look.round(c, Ui.alpha(Look.ON_INK, 0.08f), 28));
            spin.addView(later, new LinearLayout.LayoutParams(px(c, 56), px(c, 56)));
            middle.addView(spin, new LinearLayout.LayoutParams(-1, px(c, 76)));
            ruler = new FrameLayout(c);
            View track = new View(c); track.setBackground(Look.round(c, Look.PROGRESS_TRACK, 3));
            ruler.addView(track, new LayoutParams(-1, px(c, 5), Gravity.CENTER_VERTICAL));
            for (int v = -5; v <= 5; v++) {
                View tick = new View(c); tick.setBackgroundColor(v == 0 ? Look.INK_2 : Look.INK_4);
                tick.setTag(v); ruler.addView(tick, new LayoutParams(px(c, 2), px(c, v == 0 ? 26 : 12), Gravity.CENTER_VERTICAL));
            }
            fill = new View(c); fill.setBackgroundColor(Look.INK);
            ruler.addView(fill, new LayoutParams(0, px(c, 5), Gravity.CENTER_VERTICAL));
            marker = new View(c); marker.setBackground(Look.round(c, Look.INK, 11));
            ruler.addView(marker, new LayoutParams(px(c, 22), px(c, 22), Gravity.CENTER_VERTICAL));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, px(c, 26)); rp.topMargin = px(c, 22); rp.leftMargin = px(c, 38); rp.rightMargin = px(c, 38);
            middle.addView(ruler, rp);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(c, 560), -2); mp.topMargin = px(c, 30); mp.leftMargin = px(c, 44); mp.rightMargin = px(c, 44);
            row.addView(middle, mp);
            LinearLayout keys = new LinearLayout(c); keys.setOrientation(LinearLayout.VERTICAL); keys.setGravity(Gravity.END);
            LinearLayout arrows = new LinearLayout(c); arrows.setGravity(Gravity.CENTER_VERTICAL);
            arrows.addView(PlayerViews.key(c, "←"));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.leftMargin = px(c, 8);
            arrows.addView(PlayerViews.key(c, "→"), ap);
            TextView adjust = PlayerViews.text(c, Ui.tr("Ajustar", "Adjust"), Look.SMALL, Look.INK_2, Ui.regular);
            LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-2, -2); jp.leftMargin = px(c, 14);
            arrows.addView(adjust, jp);
            keys.addView(arrows);
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2); op.topMargin = px(c, 13);
            keys.addView(PlayerViews.hint(c, "OK", Ui.tr("Pronto", "Done"), Look.INK_2), op);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, -2, 1); kp.topMargin = px(c, 53);
            row.addView(keys, kp);
            addView(row, new LayoutParams(-1, -1));
            setContentDescription(Ui.tr("Tempo da legenda", "Subtitle timing"));
            setVisibility(GONE);
        }
        void set(long offsetMs) {
            double s = Math.abs(offsetMs) / 1000.0;
            String sign = offsetMs > 0 ? "+" : offsetMs < 0 ? "−" : "";
            value.setText(offsetMs == 0 ? "0.0 s" : sign + String.format(java.util.Locale.ROOT, "%.1f", s) + " s");
            direction.setText("· " + (offsetMs > 0 ? Ui.tr("depois", "later") : offsetMs < 0 ? Ui.tr("antes", "earlier") : Ui.tr("no tempo", "on time")));
            setContentDescription(offsetMs == 0 ? Ui.tr("No tempo", "On time") : String.format(java.util.Locale.ROOT, "%.1f", s) + Ui.tr(" segundos ", " seconds ")
                    + (offsetMs > 0 ? Ui.tr("depois", "later") : Ui.tr("antes", "earlier")));
            ruler.post(() -> {
                int w = ruler.getWidth(); if (w == 0) return;
                for (int i = 0; i < ruler.getChildCount(); i++) {
                    View tick = ruler.getChildAt(i);
                    if (tick.getTag() instanceof Integer) tick.setTranslationX(w / 2f + (Integer) tick.getTag() * w / 10f - tick.getWidth() / 2f);
                }
                float clamped = Math.max(-RANGE_MS, Math.min(RANGE_MS, offsetMs)) / (float) RANGE_MS;
                float x = w / 2f + clamped * w / 2f;
                marker.setTranslationX(x - marker.getWidth() / 2f);
                int width = (int) Math.abs(x - w / 2f);
                if (fill.getLayoutParams().width != width) { fill.getLayoutParams().width = width; fill.requestLayout(); }
                fill.setTranslationX(Math.min(x, w / 2f));
            });
        }
    }

    /** PlayerSubSynced: "Subtitles synced to the dialogue · Moved 2.3 s earlier · OK Undo", top right. */
    static final class Synced extends LinearLayout {
        final TextView change;
        final View remaining;
        Synced(Context c) {
            super(c); setOrientation(VERTICAL);
            setBackground(PlayerViews.glass(c, 30)); setPadding(px(c, 28), px(c, 24), px(c, 32), px(c, 28));
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
            PlayerIcon done = new PlayerIcon(c, "done"); done.colors(Look.INK, Look.INK);
            row.addView(done, new LayoutParams(px(c, 40), px(c, 40)));
            LinearLayout texts = new LinearLayout(c); texts.setOrientation(VERTICAL);
            texts.addView(PlayerViews.text(c, Ui.tr("Legendas sincronizadas com o diálogo", "Subtitles synced to the dialogue"), Look.LABEL, Look.INK, Ui.semibold));
            LinearLayout second = new LinearLayout(c); second.setGravity(Gravity.CENTER_VERTICAL);
            change = PlayerViews.text(c, "", Look.META, Look.INK_2, Ui.regular); change.setFontFeatureSettings("'tnum'");
            second.addView(change, new LayoutParams(0, -2, 1));
            LayoutParams hp = new LayoutParams(-2, -2); hp.leftMargin = px(c, 40);
            second.addView(PlayerViews.hint(c, "OK", Ui.tr("Desfazer", "Undo"), Look.INK_2), hp);
            LayoutParams sp = new LayoutParams(-1, -2); sp.topMargin = px(c, 4);
            texts.addView(second, sp);
            LayoutParams tp = new LayoutParams(-2, -2); tp.leftMargin = px(c, 24);
            row.addView(texts, tp);
            addView(row);
            FrameLayout bar = new FrameLayout(c); bar.setBackground(Look.round(c, Look.PROGRESS_TRACK, 3));
            remaining = new View(c); remaining.setBackground(Look.round(c, Look.INK_2, 3)); remaining.setPivotX(0);
            bar.addView(remaining, new FrameLayout.LayoutParams(-1, -1));
            LayoutParams bp = new LayoutParams(-1, px(c, 5)); bp.topMargin = px(c, 22);
            addView(bar, bp);
            setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            setVisibility(GONE);
        }
    }
}
