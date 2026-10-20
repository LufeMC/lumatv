package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.SubtitleView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The player owns a finite source queue for one exact movie or episode. */
public class PlayerActivity extends TvActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final List<AddonSubtitles.Subtitle> externalSubtitles = new ArrayList<>();
    private PlaybackSession session;
    private final java.util.Set<Integer> codecRetries = new java.util.HashSet<>();
    private ExoPlayer player;
    private SurfaceView surface;
    private android.widget.ImageView frozenVideo;
    private SubtitleView subtitles;
    private FrameLayout overlay;
    private View loadingCover;
    private TextView status, time;
    private SeekBar seek;
    private Button pause, audio, nextEpisodeButton, cancelAutoplay, skipIntro, keepIntro;
    private LinearLayout introActions;
    private final IntroSkip intro = new IntroSkip();
    private android.media.session.MediaSession mediaSession;
    private long publishedDuration = -1;
    private boolean activityVisible;
    private SeriesEpisodes.Episode nextEpisode;
    private boolean nextLoaded, playbackEnded, autoNextCancelled, advancingEpisode, videoRendered, audioAdvancing;
    private long nextAt;
    private final Runnable advanceEpisode = () -> { if (this.started && playbackEnded && !autoNextCancelled && !advancingEpisode && nextEpisode != null) playNextEpisode(); };
    private final SeekState scrub = new SeekState();
    private SeekPreview preview;
    private PreparedPreviews preparedPreviews;
    private boolean previewMovedPlayer;
    private int previewRequest, swallowedKey, previewDecodeSequence;
    private long previewDecodePosition;
    private boolean copyingPreview;
    private volatile boolean previewCaptureActive;
    private long lastWarmPreview = -1;
    private int previewRenderedSequence = -1, previewDirection = 1;
    private final Runnable confirmSeek = () -> { if (scrub.due(SystemClock.elapsedRealtime()) && !this.userSeeking) finishScrub(true); };
    private final Runnable decodePreview = () -> decodePreviewFrame();
    private android.widget.ProgressBar buffering;
    private SharedPreferences preferences;
    private long bufferingSince, hideAt, lastSave, sourceOpenedAt;
    private int generation, attempts;
    private boolean started, wanted = true, choosing, userSeeking, exhausted, subtitlesLoading, externalSelected, waitingForSources, sourceValidated, placeholder;
    private AddonSubtitles.Subtitle external;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long now = SystemClock.elapsedRealtime();
            updateIntro(now); updateMediaSession();
            if (playbackEnded && nextAt > 0 && !autoNextCancelled && nextEpisode != null) updateNextCountdown(now);
            if (player != null) {
                if (!sourceValidated && !scrub.active && player.getPlayWhenReady() && now - sourceOpenedAt > 8000) { session.failure("output_timeout:video=" + videoRendered + ",audio=" + audioAdvancing); retry(); main.postDelayed(this, 500); return; }
                buffering.setVisibility(player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady() ? View.VISIBLE : View.GONE);
                long duration = player.getDuration(), position = Math.max(0, player.getCurrentPosition());
                if (!userSeeking && duration > 0) seek.setProgress((int) Math.min(1000, (scrub.active ? scrub.target : position) * 1000 / duration));
                time.setText(clock(scrub.active ? scrub.target : position) + " / " + (duration > 0 ? clock(duration) : "--:--"));
                seek.setEnabled(duration > 0 && player.isCurrentMediaItemSeekable());
                if (player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                    if (bufferingSince == 0) bufferingSince = now;
                    if (now - bufferingSince > (session.position > 0 ? 12000 : 8000)) retry();
                } else bufferingSince = 0;
                if (sourceValidated && player.isPlaying() && !scrub.active && position / 10000 != lastWarmPreview) warmPlayedFrame(position);
                if (sourceValidated && player.getPlaybackState() == Player.STATE_READY) {
                    prepareNearbyPreviews(scrub.active ? scrub.target : position, duration);
                    if (scrub.active && !preview.available(scrub.target) && !previewCaptureActive && !copyingPreview
                            && preparedPreviews != null && preparedPreviews.blocked(scrub.target)) decodePreviewAt(scrub.target);
                }
                if (!scrub.active && now - lastSave > 5000) { savePosition(); lastSave = now; }
                if (overlay.getVisibility() == View.VISIBLE && !choosing && !userSeeking && !scrub.active
                        && player.isPlaying() && now > hideAt) hideControls();
            }
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this);
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        session = PlaybackSession.current;
        if (session == null || session.sources.isEmpty()) { finish(); return; }
        preferences = getSharedPreferences("playback-settings", 0);
        if (session.audioLanguage == null) session.audioLanguage = preferences.getString(audioPreference(), preferences.getString("audio-language", "en"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this); root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        frozenVideo = new android.widget.ImageView(this); frozenVideo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        frozenVideo.setBackgroundColor(0xFF000000); frozenVideo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frozenVideo.setVisibility(View.GONE); root.addView(frozenVideo, new FrameLayout.LayoutParams(-1, -1));
        loadingCover = new View(this); loadingCover.setBackgroundColor(0xFF000000);
        loadingCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); root.addView(loadingCover, new FrameLayout.LayoutParams(-1, -1));
        subtitles = new SubtitleView(this); subtitles.setUserDefaultStyle(); subtitles.setUserDefaultTextSize();
        subtitles.setBottomPaddingFraction(0.12f);
        root.addView(subtitles, new FrameLayout.LayoutParams(-1, -1));
        buffering = new android.widget.ProgressBar(this);
        buffering.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFF5F5F7));
        buffering.setContentDescription(Ui.tr("Carregando vídeo", "Loading video"));
        root.addView(buffering, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        overlay = new FrameLayout(this);
        View topShade = new View(this); topShade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xC0000000, 0x00000000}));
        overlay.addView(topShade, new FrameLayout.LayoutParams(-1, dp(135), Gravity.TOP));
        View bottomShade = new View(this); bottomShade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xF2000000}));
        overlay.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(165), Gravity.BOTTOM));
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(dp(24), dp(16), dp(24), 0);
        nav.addView(new PlayerIcon(this, "back", Ui.tr("Voltar", "Back"), v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = TvWidgets.text(this, session.title, 21); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(Ui.medium); title.setAccessibilityHeading(true); title.setPadding(dp(10), 0, 0, 0);
        nav.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); overlay.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(32), 0, dp(32), dp(20));
        status = TvWidgets.text(this, Ui.tr("Preparando reprodução…", "Preparing playback…"), 13);
        status.setTextColor(0xFFCCCCD4); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); bottom.addView(status);
        seek = new SeekBar(this); seek.setMax(1000); seek.setContentDescription(Ui.tr("Posição de reprodução", "Playback position"));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF5F5F7)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF5F5F7));
        bottom.addView(seek, new LinearLayout.LayoutParams(-1, dp(30)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    beginScrub(); scrub.select(player.getDuration() * value / 1000); updateScrub();
                }
            }
            public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; beginScrub(); }
            public void onStopTrackingTouch(SeekBar bar) { userSeeking = false; finishScrub(true); }
        });
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER_VERTICAL);
        pause = new PlayerIcon(this, "play", Ui.tr("Pausar", "Pause"), v -> togglePause());
        buttons.addView(pause, new LinearLayout.LayoutParams(dp(48), dp(48)));
        time = TvWidgets.text(this, "00:00 / --:--", 13); time.setPadding(dp(12), 0, 0, 0); buttons.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        nextEpisodeButton = button(buttons, Ui.tr("Próximo episódio", "Next episode"), v -> playNextEpisode());
        nextEpisodeButton.setVisibility(View.GONE);
        nextEpisodeButton.setLayoutParams(new LinearLayout.LayoutParams(dp(150), dp(48)));
        cancelAutoplay = button(buttons, Ui.tr("Cancelar reprodução automática", "Cancel autoplay"), v -> cancelAutoNext());
        cancelAutoplay.setVisibility(View.GONE); cancelAutoplay.setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(48)));
        audio = button(buttons, Ui.tr("Áudio", "Audio"), v -> chooseAudio());
        button(buttons, Ui.tr("Legendas", "Subtitles"), v -> chooseSubtitles());
        buttons.addView(new PlayerIcon(this, "options", Ui.tr("Opções de reprodução", "Playback options"), v -> playbackOptions()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bottom.addView(buttons); overlay.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        preview = new SeekPreview(this); preview.setVisibility(View.GONE);
        FrameLayout.LayoutParams previewSize = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); previewSize.bottomMargin = dp(135);
        overlay.addView(preview, previewSize);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        introActions = new LinearLayout(this); introActions.setGravity(Gravity.CENTER_VERTICAL);
        introActions.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable introPanel = new android.graphics.drawable.GradientDrawable();
        introPanel.setColor(0xED111115); introPanel.setCornerRadius(dp(16)); introActions.setBackground(introPanel);
        skipIntro = button(introActions, Ui.tr("Pular abertura", "Skip intro"), v -> skipIntro());
        skipIntro.setLayoutParams(new LinearLayout.LayoutParams(dp(180), dp(48)));
        keepIntro = button(introActions, Ui.tr("Manter abertura", "Keep intro"), v -> keepIntro());
        keepIntro.setLayoutParams(new LinearLayout.LayoutParams(dp(130), dp(48)));
        FrameLayout.LayoutParams introSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        introSize.rightMargin = dp(32); introSize.bottomMargin = dp(150);
        introActions.setVisibility(View.GONE); root.addView(introActions, introSize);
        setContentView(root); pause.requestFocus(); showControls();
        createMediaSession(); loadIntro();
        if (state != null) { session.position = state.getLong("position", session.position); wanted = state.getBoolean("wanted", true); }
        else session.position = session.resumePosition >= 0 ? session.resumePosition : getSharedPreferences("playback-progress", 0).getLong(session.videoId, 0);
        loadNextEpisode();
    }
    @Override protected void onStart() {
        super.onStart(); if (session == null) return;
        started = true; activityVisible = true; mediaSession.setActive(true); attempts = 0;
        session.sourcesChanged = () -> { if (started && waitingForSources) next(); };
        if (waitingForSources) next();
        else if (session.index < 0) next(); else if (session.index < session.sources.size()) open(session.sources.get(session.index));
        main.post(tick);
    }
    @Override protected void onStop() {
        activityVisible = false; if (mediaSession != null) mediaSession.setActive(false);
        started = false; previewCaptureActive = false; if (session != null) session.sourcesChanged = null; generation++; main.removeCallbacks(tick); main.removeCallbacks(advanceEpisode); main.removeCallbacks(confirmSeek); savePosition();
        if (scrub.active) { session.position = scrub.finish(false); preview.setVisibility(View.GONE); previewRequest++; main.removeCallbacks(decodePreview); }
        if (frozenVideo != null) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); } release(); super.onStop();
    }
    @Override protected void onDestroy() {
        network.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        if (preview != null) preview.clear();
        if (isFinishing() && PlaybackSession.current == session) PlaybackSession.current = null;
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        savePosition(); out.putLong("position", session == null ? 0 : session.position); out.putBoolean("wanted", wanted);
        super.onSaveInstanceState(out);
    }
    private void createMediaSession() {
        mediaSession = new android.media.session.MediaSession(this, "FreeMyTV");
        mediaSession.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMediaButtonReceiver(null);
        mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { if (activityVisible) setPlaying(true); }
            @Override public void onPause() { if (activityVisible) setPlaying(false); }
            @Override public void onStop() { if (activityVisible) setPlaying(false); }
            @Override public void onSeekTo(long position) {
                if (!activityVisible || player == null || !player.isCurrentMediaItemSeekable()) return;
                if (scrub.active) finishScrub(false);
                player.seekTo(Math.max(0, Math.min(Math.max(0, player.getDuration() - 1000), position)));
                sourceOpenedAt = SystemClock.elapsedRealtime(); updateMediaSession();
            }
            @Override public void onFastForward() { mediaSeek(1); }
            @Override public void onRewind() { mediaSeek(-1); }
            @Override public void onSkipToNext() { if (activityVisible) playNextEpisode(); }
            @Override public void onSkipToPrevious() { mediaSeek(-1); }
        }, main);
    }
    private void setPlaying(boolean playing) {
        if (player == null) return;
        if (scrub.active) finishScrub(true);
        if (player.getPlayWhenReady() != playing || playing && playbackEnded) togglePause();
        updateMediaSession();
    }
    private void mediaSeek(int direction) {
        if (!activityVisible || choosing || !beginScrub()) return;
        scrub.move(direction, 0); updateScrub(); updateMediaSession();
    }
    private void updateMediaSession() {
        if (mediaSession == null || !activityVisible || session == null) return;
        long actions = android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE
                | android.media.session.PlaybackState.ACTION_PLAY_PAUSE | android.media.session.PlaybackState.ACTION_STOP;
        int state = android.media.session.PlaybackState.STATE_BUFFERING; long position = session.position; float speed = 0;
        if (player != null) {
            position = scrub.active ? scrub.target : Math.max(0, player.getCurrentPosition());
            if (player.isCurrentMediaItemSeekable()) actions |= android.media.session.PlaybackState.ACTION_SEEK_TO
                    | android.media.session.PlaybackState.ACTION_FAST_FORWARD | android.media.session.PlaybackState.ACTION_REWIND;
            if (player.isPlaying()) { state = android.media.session.PlaybackState.STATE_PLAYING; speed = 1; }
            else if (playbackEnded) state = android.media.session.PlaybackState.STATE_STOPPED;
            else if (scrub.active || !player.getPlayWhenReady()) state = android.media.session.PlaybackState.STATE_PAUSED;
            long duration = Math.max(0, player.getDuration());
            if (duration != publishedDuration) {
                publishedDuration = duration;
                mediaSession.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, session.title)
                        .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration).build());
            }
        } else if (exhausted) state = android.media.session.PlaybackState.STATE_ERROR;
        if (nextEpisode != null) actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT;
        mediaSession.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(state, position, speed).build());
    }
    private void loadIntro() {
        final String video = session.videoId;
        network.execute(() -> {
            IntroMarkers.Marker marker = null;
            try { marker = IntroMarkers.load(this, video); } catch (Exception ignored) {}
            final IntroMarkers.Marker result = marker;
            main.post(() -> { if (!isDestroyed() && !isFinishing()) intro.marker = result; });
        });
    }
    private void updateIntro(long now) {
        if (player == null) { introActions.setVisibility(View.GONE); return; }
        boolean skip = intro.update(player.getCurrentPosition(), player.getDuration(), sourceValidated && player.isPlaying() && !scrub.active && !choosing, now);
        boolean visible = intro.visible && sourceValidated && !scrub.active && !choosing && !playbackEnded;
        boolean appeared = visible && introActions.getVisibility() != View.VISIBLE;
        boolean focused = introActions.hasFocus();
        introActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            skipIntro.setText(Ui.tr("Pular abertura", "Skip intro") + " · " + intro.seconds() + " s");
            skipIntro.setContentDescription(Ui.tr("Pular abertura em ", "Skip intro in ") + intro.seconds() + Ui.tr(" segundos", " seconds"));
            if (appeared && overlay.getVisibility() != View.VISIBLE) skipIntro.requestFocus();
        } else if (focused) pause.requestFocus();
        if (skip && visible) skipIntro();
    }
    private void skipIntro() {
        if (player == null || intro.marker == null || !intro.marker.fits(player.getDuration())) return;
        intro.skip(); player.seekTo(intro.marker.end); introActions.setVisibility(View.GONE);
        sourceOpenedAt = SystemClock.elapsedRealtime(); pause.requestFocus(); updateMediaSession();
    }
    private void keepIntro() { intro.keep(); introActions.setVisibility(View.GONE); pause.requestFocus(); }
    private void next() {
        AddonStreams.Source source = session.next();
        if (source == null && session.discovering) {
            waitingForSources = true; exhausted = false; buffering.setVisibility(View.VISIBLE);
            status.setText(Ui.tr("Encontrando outra fonte…", "Finding another source…")); return;
        }
        waitingForSources = false;
        if (source == null) {
            buffering.setVisibility(View.GONE);
            exhausted = true; status.setText(Ui.tr("Todas as fontes falharam. Volte para atualizar as fontes ou escolher outra qualidade.",
                    "Playback is unavailable. Go back and try again later."));
            showControls(); return;
        }
        attempts++; open(source);
    }
    private void open(AddonStreams.Source source) {
        int request = ++generation;
        release(); sourceValidated = false; videoRendered = false; audioAdvancing = false; placeholder = false; playbackEnded = false; nextAt = 0; main.removeCallbacks(advanceEpisode); cancelAutoplay.setVisibility(View.GONE); surface.setAlpha(1); loadingCover.setVisibility(View.VISIBLE); subtitles.setVisibility(View.VISIBLE); frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); exhausted = false; externalSelected = false; bufferingSince = SystemClock.elapsedRealtime(); sourceOpenedAt = bufferingSince;
        androidx.media3.datasource.DataSource.Factory data = MediaDataSource.factory(this, source, external == null ? null : external.url);
        player = new ExoPlayer.Builder(this, new PlaybackAudio(this)).setReleaseTimeoutMs(2000)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data).setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0)))
                .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15000, 45000, 1000, 1500)
                        .setTargetBufferBytes(20 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).setBackBuffer(15000, true).build()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true); player.setVideoSurfaceView(surface);
        TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(session.audioLanguage)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setPreferredTextLanguage(preferences.getString("subtitle-language", null))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !preferences.getBoolean("subtitles", false));
        if (session.quality > 0) parameters.setMaxVideoSize(Integer.MAX_VALUE, session.quality);
        player.setTrackSelectionParameters(parameters.build());
        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
            if (!previewCaptureActive) return;
            main.post(() -> {
                if (request != generation || !started || !scrub.active || !previewCaptureActive
                        || Math.abs(presentationTimeUs / 1000 - previewDecodePosition) > 6000 || previewRenderedSequence == previewDecodeSequence) return;
                previewRenderedSequence = previewDecodeSequence;
                int capture = previewRequest, sequence = previewDecodeSequence;
                long wait = releaseTimeNs == C.TIME_UNSET ? 32 : Math.max(16, Math.min(100, (releaseTimeNs - System.nanoTime()) / 1000000 + 32));
                main.postDelayed(() -> capturePreview(capture, sequence), wait);
            });
        });
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
                if (request != generation || player == null) return;
                audioAdvancing = true; validateSource(request, source);
            }
            @Override public void onAudioDisabled(EventTime eventTime, androidx.media3.exoplayer.DecoderCounters counters) {
                if (request != generation || player == null || scrub.active) return;
                audioAdvancing = false; sourceValidated = false; sourceOpenedAt = SystemClock.elapsedRealtime();
            }
            @Override public void onAudioSinkError(EventTime eventTime, Exception error) { audioFailure(request, error.getClass().getSimpleName()); }
            @Override public void onAudioCodecError(EventTime eventTime, Exception error) { audioFailure(request, error.getClass().getSimpleName()); }
        });
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (request != generation || player == null || placeholder) return;
                if (scrub.active) return;
                videoRendered = true; validateSource(request, source);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (request != generation || !started) return;
                session.failure("player_error:" + error.errorCode);
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && codecRetries.add(session.index)) {
                    main.post(() -> retryDecoder(request, source));
                } else main.post(() -> { if (request == generation && started) retry(); });
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (request != generation || player == null || state != player.getPlaybackState()) return;
                if (state == Player.STATE_READY) {
                    if (rejectPlaceholder(request)) return;
                    validateSource(request, source);
                    bufferingSince = 0;
                    status.setText(source.qualityLabel());
                    if (scrub.active) {
                        final int capture = previewRequest, sequence = previewDecodeSequence;
                        main.postDelayed(() -> capturePreview(capture, sequence), 16);
                    } else {
                        main.postDelayed(() -> { if (request == generation && !scrub.active) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); } }, 150);
                    }
                } else if (state == Player.STATE_ENDED && !scrub.active) {
                    if (!sourceValidated || placeholder) { main.post(() -> { if (request == generation && started) retry(); }); return; }
                    session.position = 0; getSharedPreferences("playback-progress", 0).edit().remove(session.videoId).apply();
                    ContinueWatching.record(PlayerActivity.this, session.entry, 0, 0, true);
                    pause.setText(Ui.tr("Reproduzir", "Play")); pause.setContentDescription(pause.getText());
                    playbackEnded = true; buffering.setVisibility(View.GONE);
                    status.setText(Ui.tr("Reprodução concluída.", "Playback complete.")); showControls(); scheduleNextEpisode();
                }
            }
            @Override public void onCues(CueGroup cues) { if (request == generation) subtitles.setCues(cues.cues); }
            @Override public void onTracksChanged(Tracks tracks) {
                if (request != generation || player == null || placeholder || tracks.getGroups().isEmpty() || player.getCurrentTracks().getGroups().isEmpty()) return;
                if (rejectPlaceholder(request)) return;
                if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build());
                }
                if (request != generation || external == null || externalSelected || !preferences.getBoolean("subtitles", false)) return;
                for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT)
                    for (int i = 0; i < group.length; i++) if (external.name.equals(group.getTrackFormat(i).label) && group.isTrackSupported(i)) {
                        externalSelected = true;
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i)).build());
                        return;
                    }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (request == generation && playing) { hideAt = SystemClock.elapsedRealtime() + 4500; updateAspect(); }
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) { updateAspect(); }
        });
        status.setText(session.index == 0 ? Ui.tr("Carregando…", "Loading…") : Ui.tr("Tentando outra fonte…", "Trying another source…"));
        player.setMediaItem(mediaItem(source), session.position); player.prepare(); player.setPlayWhenReady(wanted);
        pause.setText(wanted ? Ui.tr("Pausar", "Pause") : Ui.tr("Reproduzir", "Play")); pause.setContentDescription(pause.getText()); showControls();
    }
    private void validateSource(int request, AddonStreams.Source source) {
        if (request != generation || player == null || placeholder || !videoRendered || !audioAdvancing) return;
        if (rejectPlaceholder(request)) return;
        if (!sourceValidated) { sourceValidated = true; loadSubtitles(source); }
        if (preparedPreviews == null && player.getDuration() > 0) {
            final int owner = generation;
            preparedPreviews = new PreparedPreviews(this, source, player.getDuration(), main, (position, bitmap) -> {
                if (!started || owner != generation) { bitmap.recycle(); return; }
                preview.frame(position, bitmap);
            });
        }
        surface.setAlpha(1); loadingCover.setVisibility(View.GONE);
    }
    private void audioFailure(int request, String reason) {
        if (request != generation || player == null || placeholder) return;
        session.failure("audio_error:" + reason); audioAdvancing = false; sourceValidated = false;
        main.post(() -> { if (request == generation && started) retry(); });
    }
    private boolean rejectPlaceholder(int request) {
        // Media replacement can clear tracks before a queued event reaches this listener.
        if (player == null || player.getCurrentTracks().getGroups().isEmpty()) return false;
        boolean video = false, sound = false;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i)) {
                if (group.getType() == C.TRACK_TYPE_VIDEO) video = true;
                if (group.getType() == C.TRACK_TYPE_AUDIO && group.isTrackSelected(i)) sound = true;
            }
        }
        if (video && sound && !PlaybackValidation.shortPlaceholder(session.expectedDuration, player.getDuration())) return false;
        if (!placeholder) {
            session.failure("invalid_media:video=" + video + ",audio=" + sound + ",duration=" + player.getDuration());
            placeholder = true; sourceValidated = false; loadingCover.setVisibility(View.VISIBLE);
            status.setText(Ui.tr("Tentando outra fonte…", "Trying another source…"));
            main.post(() -> { if (request == generation && started) retry(); });
        }
        return true;
    }
    private String audioPreference() { return "audio-title:" + session.videoId.split(":")[0]; }
    private MediaItem mediaItem(AddonStreams.Source source) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.url);
        if (external != null) item.setSubtitleConfigurations(Collections.singletonList(
                new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(external.url)).setMimeType(external.mime)
                        .setLanguage(external.language).setLabel(external.name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
        return item.build();
    }
    private void updateAspect() {
        if (player == null || surface.getParent() == null) return;
        androidx.media3.common.VideoSize size = player.getVideoSize();
        FrameLayout root = (FrameLayout) surface.getParent();
        int width = root.getWidth(), height = root.getHeight();
        if (size.width <= 0 || size.height <= 0 || width == 0 || height == 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        int w = width, h = Math.round(width / ratio);
        if (h > height) { h = height; w = Math.round(height * ratio); }
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }
    private void retry() {
        if (!started || exhausted) return;
        boolean played = sourceValidated;
        savePosition();
        if (scrub.active) { session.position = scrub.finish(false); preview.setVisibility(View.GONE); previewRequest++; main.removeCallbacks(decodePreview); }
        if (played) session.position = Math.max(0, session.position - 3000); generation++; release();
        next();
    }
    private void retryDecoder(int request, AddonStreams.Source source) {
        if (request != generation || !started) return;
        savePosition(); int recovery = ++generation; release();
        status.setText(Ui.tr("Preparando o decodificador…", "Preparing the decoder…")); buffering.setVisibility(View.VISIBLE);
        main.postDelayed(() -> { if (started && generation == recovery) open(source); }, 300);
    }
    private void release() {
        if (preparedPreviews != null) { preparedPreviews.close(); preparedPreviews = null; }
        if (preview != null) preview.clear(); lastWarmPreview = -1;
        if (player != null) { player.release(); player = null; }
        if (subtitles != null) subtitles.setCues(Collections.emptyList());
    }
    private void savePosition() {
        if (player == null || session == null || !sourceValidated || placeholder) return;
        if (player.getPlaybackState() == Player.STATE_ENDED && !scrub.active) return;
        long position = scrub.active ? scrub.original : player.getCurrentPosition();
        if (position > 0) session.position = position;
        getSharedPreferences("playback-progress", 0).edit().putLong(session.videoId, session.position).apply();
        ContinueWatching.record(this, session.entry, session.position, player.getDuration(), false);
    }
    private void togglePause() {
        if (player == null) return;
        boolean replay = playbackEnded;
        if (replay) { cancelAutoNext(); playbackEnded = false; player.seekTo(0); }
        wanted = replay || !player.getPlayWhenReady(); if (wanted) sourceOpenedAt = SystemClock.elapsedRealtime(); player.setPlayWhenReady(wanted);
        pause.setText(wanted ? Ui.tr("Pausar", "Pause") : Ui.tr("Reproduzir", "Play")); pause.setContentDescription(pause.getText()); bufferingSince = 0; showControls(); updateMediaSession();
    }
    private void loadSubtitles(AddonStreams.Source source) {
        int request = generation;
        subtitlesLoading = true;
        network.execute(() -> {
            List<AddonSubtitles.Subtitle> loaded = new ArrayList<>();
            try { loaded = AddonSubtitles.load(this, session.videoId, source); } catch (Exception ignored) {}
            final List<AddonSubtitles.Subtitle> result = loaded;
            main.post(() -> { if (!isDestroyed() && request == generation) { externalSubtitles.clear(); externalSubtitles.addAll(result); subtitlesLoading = false; } });
        });
    }
    private void chooseAudio() { chooseTracks(C.TRACK_TYPE_AUDIO); }
    private void chooseSubtitles() { chooseTracks(C.TRACK_TYPE_TEXT); }
    private void chooseTracks(int type) {
        if (player == null) return;
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>(); int checked = -1;
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add(Ui.tr("Desativadas", "Off")); actions.add(() -> {
                preferences.edit().putBoolean("subtitles", false).apply(); external = null;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type)
                        .setTrackTypeDisabled(type, true).build());
            });
            if (player.getTrackSelectionParameters().disabledTrackTypes.contains(type)) checked = 0;
        } else {
            labels.add(Ui.tr("Automático", "Automatic")); actions.add(() -> {
                preferences.edit().remove(audioPreference()).apply(); session.audioLanguage = preferences.getString("audio-language", "en");
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type)
                        .setPreferredAudioLanguage(session.audioLanguage).build());
            });
            if (!preferences.contains(audioPreference())) checked = 0;
        }
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                int track = i; Format format = group.getTrackFormat(i);
                if (type == C.TRACK_TYPE_TEXT && external != null && external.name.equals(format.label)) continue;
                String label = trackLabel(format, i);
                if (group.isTrackSelected(i) && (type != C.TRACK_TYPE_TEXT || preferences.getBoolean("subtitles", false))) checked = labels.size();
                labels.add(label); actions.add(() -> {
                    String language = format.language;
                    SharedPreferences.Editor edit = preferences.edit();
                    if (type == C.TRACK_TYPE_TEXT) { external = null; externalSelected = false; edit.putBoolean("subtitles", true).putString("subtitle-language", language); }
                    else { edit.putString(audioPreference(), language); session.audioLanguage = language; }
                    edit.apply();
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), track)).build());
                    if (type == C.TRACK_TYPE_TEXT && session.index < session.sources.size()) {
                        long position = player.getCurrentPosition();
                        player.setMediaItem(mediaItem(session.sources.get(session.index)), position); player.prepare(); player.setPlayWhenReady(wanted);
                    }
                });
            }
        }
        if (type == C.TRACK_TYPE_TEXT) for (AddonSubtitles.Subtitle subtitle : externalSubtitles) {
            if (external != null && external.url.equals(subtitle.url)) checked = labels.size();
            labels.add(subtitle.name + " · " + Ui.tr("Complemento", "Add-on")); actions.add(() -> {
                savePosition(); external = subtitle;
                preferences.edit().putBoolean("subtitles", true).putString("subtitle-language", subtitle.language).apply();
                if (session.index < session.sources.size()) open(session.sources.get(session.index));
            });
        }
        choosing = true; showControls();
        PlayerSheet.show(this, type == C.TRACK_TYPE_AUDIO ? Ui.tr("Áudio", "Audio") : Ui.tr("Legendas", "Subtitles"),
                labels, checked, actions, () -> { choosing = false; showControls(); });
    }
    private void prepareNearbyPreviews(long position, long duration) {
        if (preparedPreviews == null || duration <= 0) return;
        long base = SeekPreview.bucket(position);
        int[] offsets = scrub.active ? new int[]{0, previewDirection, -previewDirection, 2 * previewDirection, -2 * previewDirection, 3 * previewDirection, 4 * previewDirection, 5 * previewDirection, 6 * previewDirection}
                : new int[]{1, 2, 3, -1, -2, -3};
        for (int offset : offsets) {
            long target = base + offset * 10000L;
            if (target >= 0 && target < duration - 1000 && !preview.available(target) && preparedPreviews.request(target)) return;
        }
    }
    private void warmPlayedFrame(long position) {
        if (!surface.getHolder().getSurface().isValid()) return;
        lastWarmPreview = position / 10000;
        final int source = generation;
        android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
        try { android.view.PixelCopy.request(surface, frame, result -> {
            if (result == android.view.PixelCopy.SUCCESS && started && !scrub.active && source == generation) preview.frame(position, frame);
            else frame.recycle();
        }, main); } catch (Exception ignored) { frame.recycle(); }
    }
    private boolean beginScrub() {
        if (player == null || !player.isCurrentMediaItemSeekable() || player.getDuration() <= 0) return false;
        if (!scrub.active) {
            scrub.begin(player.getCurrentPosition(), player.getDuration(), player.getPlayWhenReady());
            player.setPlayWhenReady(false); player.setScrubbingModeParameters(new androidx.media3.exoplayer.ScrubbingModeParameters.Builder()
                    .setDisabledTrackTypes(Collections.singleton(C.TRACK_TYPE_TEXT)).build());
            previewMovedPlayer = false; pause.setText(Ui.tr("Reproduzir", "Play")); pause.setContentDescription(pause.getText());
            preview.setVisibility(View.VISIBLE); seek.requestFocus(); showControls();
            int source = generation;
            android.graphics.Bitmap frame = preparedPreviews == null || !preparedPreviews.ready ? android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888) : null;
            try { if (frame != null) android.view.PixelCopy.request(surface, frame, result -> {
                if (result == android.view.PixelCopy.SUCCESS && started && scrub.active && source == generation) {
                    frozenVideo.setImageBitmap(frame); frozenVideo.setVisibility(View.VISIBLE);
                } else frame.recycle();
            }, main); } catch (Exception ignored) { if (frame != null) frame.recycle(); }
            subtitles.setVisibility(View.GONE);
        }
        return true;
    }
    private void updateScrub() {
        if (!scrub.active) return;
        previewRequest++; copyingPreview = false; previewCaptureActive = false; main.removeCallbacks(decodePreview);
        scrub.input(SystemClock.elapsedRealtime()); main.removeCallbacks(confirmSeek); main.postDelayed(confirmSeek, SeekState.AUTO_CONFIRM_MS);
        previewDirection = scrub.target < scrub.original ? -1 : 1;
        seek.setProgress((int) (scrub.target * 1000 / Math.max(1, scrub.duration)));
        time.setText(clock(scrub.target) + " / " + clock(scrub.duration));
        seek.setContentDescription(Ui.tr("Posição de reprodução ", "Playback position ") + clock(scrub.target));
        boolean available = preview.select(scrub.target);
        prepareNearbyPreviews(scrub.target, scrub.duration);
        if (!available && (preparedPreviews == null || !preparedPreviews.ready)) main.postDelayed(decodePreview, 35);
        else if (preparedPreviews == null) { int request = previewRequest; main.post(() -> prefetchPreview(request)); }
        showControls();
    }
    private void decodePreviewFrame() { if (scrub.active) decodePreviewAt(scrub.target); }
    private void decodePreviewAt(long position) {
        if (!started || player == null || !scrub.active) return;
        previewMovedPlayer = true; player.setScrubbingModeEnabled(true);
        int request = previewRequest, sequence = ++previewDecodeSequence; previewDecodePosition = position; previewRenderedSequence = -1; previewCaptureActive = true;
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC); player.seekTo(position);
        main.postDelayed(() -> { if (scrub.active && request == previewRequest && sequence == previewDecodeSequence) preview.unavailable(); }, 7000);
    }
    private void prefetchPreview(int request) {
        if (!started || player == null || !scrub.active || request != previewRequest || copyingPreview) return;
        long ahead = scrub.target + previewDirection * 10000, behind = scrub.target - previewDirection * 10000;
        if (ahead >= 0 && ahead < scrub.duration - 1000 && !preview.available(ahead)) decodePreviewAt(ahead);
        else if (behind >= 0 && behind < scrub.duration - 1000 && !preview.available(behind)) decodePreviewAt(behind);
    }
    private void capturePreview(int request, int sequence) {
        if (!started || player == null || !scrub.active || request != previewRequest || sequence != previewDecodeSequence || copyingPreview
                || previewRenderedSequence != sequence || player.getPlaybackState() != Player.STATE_READY || !surface.getHolder().getSurface().isValid()) return;
        copyingPreview = true; long position = previewDecodePosition;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(surface, bitmap, result -> {
                if (request != previewRequest || sequence != previewDecodeSequence || !scrub.active || !started) { bitmap.recycle(); return; }
                copyingPreview = false;
                if (result == android.view.PixelCopy.SUCCESS) { previewCaptureActive = false; preview.frame(position, bitmap); main.post(() -> prefetchPreview(request)); }
                else { bitmap.recycle(); preview.unavailable(); }
            }, main);
        } catch (Exception ignored) { copyingPreview = false; bitmap.recycle(); preview.unavailable(); }
    }
    private void finishScrub(boolean confirmed) {
        if (!scrub.active) return;
        boolean playing = scrub.playing; long position = scrub.finish(confirmed);
        previewRequest++; previewCaptureActive = false; main.removeCallbacks(decodePreview); main.removeCallbacks(confirmSeek); preview.setVisibility(View.GONE); subtitles.setVisibility(View.VISIBLE);
        if (player != null) {
            player.setScrubbingModeEnabled(false);
            sourceOpenedAt = SystemClock.elapsedRealtime();
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT);
            if (confirmed || previewMovedPlayer) player.seekTo(position);
            wanted = playing; player.setPlayWhenReady(playing); bufferingSince = 0;
            pause.setText(playing ? Ui.tr("Pausar", "Pause") : Ui.tr("Reproduzir", "Play")); pause.setContentDescription(pause.getText());
            pause.requestFocus(); showControls();
            int request = generation;
            main.postDelayed(() -> { if (request == generation && !scrub.active) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); } }, 500);
        }
    }
    private void loadNextEpisode() {
        if (session.entry == null || !"tv".equals(session.entry.kind)) { nextLoaded = true; return; }
        final ContinueWatching.Entry current = session.entry;
        network.execute(() -> {
            SeriesEpisodes.Episode following = null;
            try { following = EpisodeSequence.next(current); } catch (Exception ignored) {}
            final SeriesEpisodes.Episode result = following;
            main.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                nextLoaded = true; nextEpisode = result;
                nextEpisodeButton.setVisibility(result == null ? View.GONE : View.VISIBLE);
                if (result != null) nextEpisodeButton.setContentDescription(Ui.tr("Próximo episódio", "Next episode") + ". " + result.code() + ". " + result.name);
                if (playbackEnded) scheduleNextEpisode();
            });
        });
    }
    private void scheduleNextEpisode() {
        if (!started || !playbackEnded || !nextLoaded || nextEpisode == null || autoNextCancelled || advancingEpisode || nextAt > 0) return;
        nextAt = SystemClock.elapsedRealtime() + 10000;
        cancelAutoplay.setVisibility(View.VISIBLE); showControls(); nextEpisodeButton.requestFocus(); updateNextCountdown(SystemClock.elapsedRealtime());
        main.postDelayed(advanceEpisode, 10000);
    }
    private void updateNextCountdown(long now) {
        long seconds = Math.max(0, (nextAt - now + 999) / 1000);
        status.setText(String.format(Ui.tr("A seguir: %s · %s. Começa em %d s.", "Next: %s · %s. Playback starts in %d s."), nextEpisode.code(), nextEpisode.name, seconds));
    }
    private void cancelAutoNext() {
        autoNextCancelled = true; nextAt = 0; main.removeCallbacks(advanceEpisode); cancelAutoplay.setVisibility(View.GONE);
        if (playbackEnded) status.setText(Ui.tr("Reprodução concluída.", "Playback complete."));
        if (nextEpisode != null) nextEpisodeButton.requestFocus();
    }
    private void playNextEpisode() {
        if (nextEpisode == null || advancingEpisode) return;
        advancingEpisode = true; main.removeCallbacks(advanceEpisode); savePosition();
        setResult(RESULT_OK, new android.content.Intent().putExtra("next_episode", true)
                .putExtra("season", nextEpisode.season).putExtra("episode", nextEpisode.number));
        finish();
    }
    private void playbackOptions() {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add(Ui.tr("Tentar outra fonte", "Try another source")); actions.add(() -> { attempts = 0; retry(); });
        labels.add(Ui.tr("Reiniciar", "Restart")); actions.add(() -> { if (player != null) { cancelAutoNext(); playbackEnded = false; player.seekTo(0); player.setPlayWhenReady(true); wanted = true; session.position = 0; } });
        choosing = true;
        PlayerSheet.show(this, Ui.tr("Opções de reprodução", "Playback options"), labels, -1, actions,
                () -> { choosing = false; showControls(); });
    }
    private String trackLabel(Format format, int index) {
        String label = AddonSubtitles.language(format.language);
        if (format.label != null && !format.label.isEmpty()) label += " · " + AddonStreams.clean(format.label, 60);
        if (format.channelCount > 0) label += " · " + format.channelCount + " " + Ui.tr("canais", "channels");
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) label += " · " + Ui.tr("Audiodescrição", "Audio description");
        if ((format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) label += " · " + Ui.tr("Forçada", "Forced");
        return label + " · " + (index + 1);
    }
    private void showControls() {
        overlay.setVisibility(View.VISIBLE); hideAt = SystemClock.elapsedRealtime() + 4500;
        subtitles.setBottomPaddingFraction(0.26f);
        if (getCurrentFocus() == null) pause.requestFocus();
    }
    private void hideControls() { overlay.setVisibility(View.GONE); subtitles.setBottomPaddingFraction(0.08f); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && key == swallowedKey) { swallowedKey = 0; return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && introActions != null && introActions.getVisibility() == View.VISIBLE && introActions.hasFocus()) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event);
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
                showControls(); pause.requestFocus(); swallowedKey = key; return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !choosing && scrub.active) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                scrub.move(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, event.getRepeatCount()); updateScrub(); swallowedKey = key; return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                finishScrub(key != KeyEvent.KEYCODE_BACK); swallowedKey = key; return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) { finishScrub(false); audio.requestFocus(); swallowedKey = key; return true; }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !choosing) {
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || player != null && player.getPlayWhenReady() != (key == KeyEvent.KEYCODE_MEDIA_PLAY)) togglePause();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) { finish(); swallowedKey = key; return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if ((overlay.getVisibility() != View.VISIBLE || seek.hasFocus() || pause.hasFocus()
                        || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) && beginScrub()) {
                    scrub.move(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, event.getRepeatCount());
                    updateScrub(); swallowedKey = key; return true;
                }
                hideAt = SystemClock.elapsedRealtime() + 4500;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && pause.hasFocus()) { audio.requestFocus(); swallowedKey = key; showControls(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (overlay.getVisibility() != View.VISIBLE) { showControls(); pause.requestFocus(); return true; }
                hideAt = SystemClock.elapsedRealtime() + 4500;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private Button button(LinearLayout row, String label, View.OnClickListener action) {
        Button value = TvWidgets.button(this, label, action);
        value.setTextSize(15); value.setTextColor(TvWidgets.textColors()); value.setBackground(TvWidgets.background(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(100), dp(48)); params.rightMargin = dp(8); row.addView(value, params); return value;
    }
    private int dp(float value) { return TvWidgets.dp(this, value); }
    private static String clock(long value) {
        long seconds = value / 1000;
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
