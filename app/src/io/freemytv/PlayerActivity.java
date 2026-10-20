package io.freemytv;

import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player owns a finite source queue for one exact movie or episode. Screens follow the canvas boards (see
 * PlayerScreens). Besides playing, it: retries the same source through network drops, keeps the screen awake only
 * while something moves, matches the display's refresh rate, skips recaps and intros, starts Up next when the
 * credits begin (asking "Still watching?" late at night), prepares the next episode in the background, shows add-on
 * subtitles with its own scheduler (timing, drift fix, sync to the dialogue) and saves progress to the Stremio library.
 */
public class PlayerActivity extends TvActivity implements QuickPanel.Host {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final List<AddonSubtitles.Subtitle> externalSubtitles = new ArrayList<>();
    private PlaybackSession session;
    private final java.util.Set<Integer> codecRetries = new java.util.HashSet<>();
    private ExoPlayer player;
    private FrameLayout root;
    private SurfaceView surface;
    private android.widget.ImageView frozenVideo;
    private SubtitleView subtitles;
    private PlayerScreens.Controls overlay;
    private PlayerScreens.Scrub scrubLayer;
    private PlayerScreens.Skip introActions;
    private PlayerScreens.UpNext upNext;
    private PlayerScreens.Recover recover;
    private PlayerScreens.StillWatching stillWatching;
    private PlayerScreens.Network networkPill;
    private PlayerScreens.Timing timing;
    private PlayerScreens.Synced synced;
    private TextView recapLabel;
    private View loadingCover;
    private PlayerIcon buffering;
    private PlayerViews.ScrubBar seek;
    private PlayerIcon pause;
    private PlayerViews.Pill nextEpisodeButton, cancelAutoplay, skipIntro, keepIntro;
    private final IntroSkip intro = new IntroSkip(), recap = new IntroSkip();
    private IntroMarkers.Segments segments;
    private android.media.session.MediaSession mediaSession;
    private long publishedDuration = -1;
    private boolean activityVisible;
    private SeriesEpisodes.Episode nextEpisode;
    private boolean nextLoaded, playbackEnded, autoNextCancelled, advancingEpisode, videoRendered, audioAdvancing;
    private boolean creditsHandled, finishedRecorded, nextPrefetched, upNextShown, stillShown, stillPaused;
    private long nextAt, stillUntil;
    private final Runnable advanceEpisode = () -> {
        if (this.started && (playbackEnded || upNextShown) && !autoNextCancelled && !advancingEpisode && nextEpisode != null) {
            PlaybackMemory.setAutoplayChain(this, PlaybackMemory.autoplayChain(this) + 1);
            playNextEpisode(true);
        }
    };
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
    private final Runnable scrubTick = new Runnable() {
        @Override public void run() {
            if (!scrub.active) return;
            long left = Math.max(0, scrub.confirmAt - SystemClock.elapsedRealtime());
            scrubLayer.update(scrub.original, scrub.target, scrub.duration, left / (float) SeekState.AUTO_CONFIRM_MS, (int) ((left + 999) / 1000));
            main.postDelayed(this, 100);
        }
    };
    private SharedPreferences preferences;
    private long bufferingSince, hideAt, lastSave, sourceOpenedAt, watchedMs, lastTick;
    private int generation, attempts;
    private boolean started, wanted = true, choosing, userSeeking, exhausted, waitingForSources, sourceValidated, placeholder;
    private String failure = "", showTitle = "", episodeLine = "";

    // Network drops: retry the same source that already played (1, 2, 4… s, about 30 s), pausing switch timers offline.
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean online = true;
    private String playedUrl = "";
    private long networkFailSince;
    private int networkTries;
    private boolean waitingForNetwork;
    private final Runnable networkRetry = () -> retrySameSource();

    // Subtitles: what the viewer wants (null = off, forced lines only), and our own add-on cue scheduler.
    private String wantText;
    private boolean textDecided, foreignMode, autoSubtitleDone;
    private AddonSubtitles.Subtitle external;
    private SubtitleCues externalCues;
    private Cue[] cueCache;
    private final List<String> visibleLines = new ArrayList<>();
    private String shownLines = "";
    private String release = "";
    private long replayUntil = -1;
    private final SubtitleSync sync = new SubtitleSync();
    private boolean syncSolving, syncDone, syncDrift;
    private long undoOffset; private double undoScale = 1;
    private final Runnable cueTick = new Runnable() { @Override public void run() { renderCues(); } };
    private final Runnable hideSynced = () -> { if (synced != null) synced.setVisibility(View.GONE); };
    private int displayMode;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long now = SystemClock.elapsedRealtime();
            long elapsed = lastTick == 0 ? 0 : Math.min(1000, now - lastTick); lastTick = now;
            updateSkips(now); updateMediaSession(); updateClock();
            if (upNextShown && nextAt > 0 && !autoNextCancelled && nextEpisode != null) updateNextCountdown(now);
            if (stillShown) updateStillWatching(now);
            if (player != null) {
                if (!online) { bufferingSince = bufferingSince == 0 ? 0 : now; sourceOpenedAt = now; }
                if (!sourceValidated && !scrub.active && player.getPlayWhenReady() && online && now - sourceOpenedAt > 8000) {
                    session.failure("output_timeout:video=" + videoRendered + ",audio=" + audioAdvancing);
                    failure = videoRendered ? "silent" : "picture"; retry(); main.postDelayed(this, 500); return;
                }
                boolean loading = player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady();
                buffering.setVisibility(loading && recover.getVisibility() != View.VISIBLE && !upNextShown ? View.VISIBLE : View.GONE);
                networkPill.setVisibility(loading && !online || waitingForNetwork ? View.VISIBLE : View.GONE);
                long duration = player.getDuration(), position = Math.max(0, player.getCurrentPosition());
                if (player.isPlaying()) watchedMs += elapsed;
                if (!userSeeking && !scrub.active) seek.set(position, player.getBufferedPosition(), duration);
                updateTimes(position, duration);
                if (loading) {
                    if (bufferingSince == 0) bufferingSince = now;
                    if (online && now - bufferingSince > (session.position > 0 ? 12000 : 8000)) { failure = "slow"; retry(); }
                } else bufferingSince = 0;
                if (sourceValidated && player.isPlaying() && !scrub.active && position / 10000 != lastWarmPreview) warmPlayedFrame(position);
                if (sourceValidated && player.getPlaybackState() == Player.STATE_READY) {
                    prepareNearbyPreviews(scrub.active ? scrub.target : position, duration);
                    if (scrub.active && !preview.available(scrub.target) && !previewCaptureActive && !copyingPreview
                            && preparedPreviews != null && preparedPreviews.blocked(scrub.target)) decodePreviewAt(scrub.target);
                }
                if (sourceValidated && duration > 0 && !scrub.active) checkEnding(position, duration);
                if (replayUntil >= 0 && position >= replayUntil) { replayUntil = -1; applyTextParameters(); }
                if (externalCues != null && syncDrift == false && duration > 0) { syncDrift = true; applyDrift(duration); }
                if (sync.active() && now - lastSave > 4000) checkSync(position);
                if (!scrub.active && now - lastSave > 5000) { savePosition(); lastSave = now; }
                if (overlay.getVisibility() == View.VISIBLE && !choosing && !userSeeking && !scrub.active
                        && player.isPlaying() && now > hideAt) hideControls();
                if (upNextShown) upNext.progress(position, duration);
            }
            updateKeepAwake();
            main.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this); PlaybackMemory.init(this);
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        session = PlaybackSession.current;
        if (session == null || session.sources.isEmpty()) { finish(); return; }
        preferences = getSharedPreferences(Prefs.PLAYBACK, 0);
        if (session.audioLanguage == null) session.audioLanguage = defaultAudio();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        connectivity = getSystemService(ConnectivityManager.class);
        ContinueWatching.Entry entry = session.entry;
        showTitle = entry != null && !entry.title.isEmpty() ? entry.title : session.title;
        episodeLine = entry != null && entry.episode > 0 ? entry.code() + (entry.subtitle.isEmpty() ? "" : " · " + entry.subtitle) : "";
        if (entry == null && session.title.contains(" · ")) { showTitle = session.title.substring(0, session.title.indexOf(" · ")); episodeLine = session.title.substring(session.title.indexOf(" · ") + 3); }
        buildViews();
        createMediaSession(); loadIntro();
        if (state != null) { session.position = state.getLong("position", session.position); wanted = state.getBoolean("wanted", true); }
        else session.position = session.resumePosition >= 0 ? session.resumePosition : getSharedPreferences("playback-progress", 0).getLong(session.videoId, 0);
        decideSubtitles();
        loadNextEpisode();
        loadSubtitles(session.sources.get(0));
        PlaybackLog.add(this, "Playing " + session.videoId + " · " + session.sources.size() + " sources queued");
    }

    private void buildViews() {
        root = new FrameLayout(this); root.setBackgroundColor(Look.PLAYER);
        surface = new SurfaceView(this); root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        frozenVideo = new android.widget.ImageView(this); frozenVideo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        frozenVideo.setBackgroundColor(Look.PLAYER); frozenVideo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frozenVideo.setVisibility(View.GONE); root.addView(frozenVideo, new FrameLayout.LayoutParams(-1, -1));
        loadingCover = new View(this); loadingCover.setBackgroundColor(Look.PLAYER);
        loadingCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); root.addView(loadingCover, new FrameLayout.LayoutParams(-1, -1));
        subtitles = new SubtitleView(this); styleSubtitles();
        root.addView(subtitles, new FrameLayout.LayoutParams(-1, -1));
        buffering = new PlayerIcon(this, "spinner"); buffering.colors(Look.INK, Look.INK);
        buffering.setContentDescription(Ui.tr("Carregando vídeo", "Loading video"));
        root.addView(buffering, new FrameLayout.LayoutParams(Look.si(this, 64), Look.si(this, 64), Gravity.CENTER));
        overlay = new PlayerScreens.Controls(this, v -> togglePause(), v -> jump(-10000), v -> jump(10000),
                v -> playNextEpisode(false), v -> chooseTracks(C.TRACK_TYPE_TEXT), v -> playbackOptions());
        overlay.header.set(showTitle, episodeLine);
        pause = overlay.pause; seek = overlay.bar; nextEpisodeButton = overlay.next;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        scrubLayer = new PlayerScreens.Scrub(this); preview = scrubLayer.preview;
        root.addView(scrubLayer, new FrameLayout.LayoutParams(-1, -1));
        recapLabel = PlayerViews.text(this, "", Look.META, Look.INK_2, Ui.medium); recapLabel.setVisibility(View.GONE);
        root.addView(recapLabel, PlayerScreens.at(this, -2, -2, Gravity.TOP | Gravity.START, 96, 56, 0, 0));
        introActions = new PlayerScreens.Skip(this, v -> keepIntro(), v -> skipIntro());
        skipIntro = introActions.skip; keepIntro = introActions.keep;
        root.addView(introActions, PlayerScreens.at(this, -2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, 96, 96));
        upNext = new PlayerScreens.UpNext(this, v -> playNextEpisode(false), v -> watchCredits());
        cancelAutoplay = upNext.credits;
        root.addView(upNext, 0, new FrameLayout.LayoutParams(-1, -1));
        recover = new PlayerScreens.Recover(this, v -> chooseSource(), v -> finish());
        recover.header.set(showTitle, episodeLine);
        if (session.entry == null || !"tv".equals(session.entry.kind)) recover.back.setText(Ui.tr("Voltar", "Back"));
        root.addView(recover, new FrameLayout.LayoutParams(-1, -1));
        stillWatching = new PlayerScreens.StillWatching(this, v -> keepWatching(), v -> stopForTonight());
        root.addView(stillWatching, new FrameLayout.LayoutParams(-1, -1));
        networkPill = new PlayerScreens.Network(this);
        root.addView(networkPill, PlayerScreens.at(this, -2, Look.si(this, 108), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 96));
        timing = new PlayerScreens.Timing(this);
        root.addView(timing, PlayerScreens.at(this, Look.si(this, 1280), Look.si(this, 184), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 56));
        synced = new PlayerScreens.Synced(this);
        root.addView(synced, PlayerScreens.at(this, -2, -2, Gravity.TOP | Gravity.END, 0, 56, 96, 0));
        setContentView(root); pause.requestFocus(); showControls();
    }

    private String defaultAudio() {
        String title = preferences.getString(audioPreference(), null);
        if (title != null) return title;
        if (!preferences.contains(Prefs.AUDIO_LANGUAGE)) return "en";
        String value = preferences.getString(Prefs.AUDIO_LANGUAGE, "");
        return value.isEmpty() ? null : value;
    }

    @Override protected void onStart() {
        super.onStart(); if (session == null) return;
        started = true; activityVisible = true; mediaSession.setActive(true); attempts = 0; lastTick = 0;
        QuickPanel.attach(this);
        registerNetwork();
        session.sourcesChanged = () -> { if (started && waitingForSources) next(); };
        if (upNextShown) closeUpNext();
        if (waitingForSources) next();
        else if (session.index < 0) next(); else if (session.index < session.sources.size()) open(session.sources.get(session.index));
        main.post(tick);
    }
    @Override protected void onStop() {
        activityVisible = false; if (mediaSession != null) mediaSession.setActive(false);
        QuickPanel.detach(this);
        unregisterNetwork();
        started = false; previewCaptureActive = false; if (session != null) session.sourcesChanged = null; generation++;
        main.removeCallbacks(tick); main.removeCallbacks(advanceEpisode); main.removeCallbacks(confirmSeek); main.removeCallbacks(networkRetry);
        main.removeCallbacks(cueTick); main.removeCallbacks(scrubTick);
        savePosition(); syncLibrary();
        if (scrub.active) { session.position = scrub.finish(false); scrubLayer.setVisibility(View.GONE); previewRequest++; main.removeCallbacks(decodePreview); }
        // Back from standby or the screensaver: paused, never replaying a finished episode from the start.
        wanted = false;
        if (frozenVideo != null) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); }
        release(); restoreDisplayMode();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }
    @Override protected void onDestroy() {
        network.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        if (preview != null) preview.clear();
        if (isFinishing() && session != null) {
            PlaybackSession.clearInterrupted(this);
            if (!advancingEpisode && nextEpisode != null && session.entry != null) PlaybackPrefetch.discard(this, nextEpisode.id(session.entry.imdb));
        }
        if (isFinishing() && PlaybackSession.current == session) PlaybackSession.current = null;
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        savePosition(); out.putLong("position", session == null ? 0 : session.position); out.putBoolean("wanted", wanted);
        super.onSaveInstanceState(out);
    }

    // --- Media session (system play/pause/seek/next)

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
            @Override public void onSkipToNext() { if (activityVisible) playNextEpisode(false); }
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

    // --- Intro, recap and credits (IntroDB)

    private void loadIntro() {
        final String video = session.videoId;
        network.execute(() -> {
            IntroMarkers.Segments found = null;
            try { found = IntroMarkers.loadSegments(this, video); } catch (Exception ignored) {}
            final IntroMarkers.Segments result = found;
            main.post(() -> {
                if (isDestroyed() || isFinishing() || result == null) return;
                segments = result; intro.marker = result.intro; recap.marker = result.recap;
            });
        });
    }
    private void updateSkips(long now) {
        if (player == null) { introActions.setVisibility(View.GONE); recapLabel.setVisibility(View.GONE); return; }
        boolean allowed = sourceValidated && !scrub.active && !choosing && !playbackEnded && !upNextShown && !stillShown;
        boolean playing = allowed && player.isPlaying();
        long position = player.getCurrentPosition(), duration = player.getDuration();
        boolean skipRecap = recap.update(position, duration, playing && !intro.visible, now);
        boolean skipIntroNow = intro.update(position, duration, playing && !recap.visible, now);
        IntroSkip active = recap.visible ? recap : intro.visible ? intro : null;
        boolean visible = active != null && allowed;
        boolean appeared = visible && introActions.getVisibility() != View.VISIBLE;
        boolean focused = introActions.hasFocus();
        introActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        recapLabel.setVisibility(visible && active == recap && overlay.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        if (visible) {
            introActions.labels(active == recap);
            skipIntro.icon.progress(active.remaining / (float) IntroSkip.COUNTDOWN_MS);
            String what = active == recap ? Ui.tr("Pular resumo", "Skip recap") : Ui.tr("Pular abertura", "Skip intro");
            skipIntro.setContentDescription(what + Ui.tr(" em ", " in ") + active.seconds() + Ui.tr(" segundos", " seconds"));
            if (active == recap) recapLabel.setText((episodeLine.isEmpty() ? "" : episodeLine.split(" · ")[0] + " · ") + Ui.tr("Anteriormente", "Previously on"));
            if (appeared && overlay.getVisibility() != View.VISIBLE) skipIntro.requestFocus();
        } else if (focused) pause.requestFocus();
        if (skipRecap && recap.visible && allowed) skipIntro();
        else if (skipIntroNow && intro.visible && allowed) skipIntro();
    }
    private void skipIntro() {
        if (player == null) return;
        IntroSkip active = recap.visible ? recap : intro;
        if (active.marker == null || !active.marker.fits(player.getDuration())) return;
        active.skip(); player.seekTo(active.marker.end); introActions.setVisibility(View.GONE); recapLabel.setVisibility(View.GONE);
        sourceOpenedAt = SystemClock.elapsedRealtime(); pause.requestFocus(); updateMediaSession();
    }
    private void keepIntro() {
        if (recap.visible) recap.keep(); else intro.keep();
        introActions.setVisibility(View.GONE); recapLabel.setVisibility(View.GONE); pause.requestFocus();
    }

    /** Credits start (IntroDB, last 20%), 95% / 2 minutes left, and the end: count as watched and offer Up next. */
    private void checkEnding(long position, long duration) {
        long credits = segments == null ? -1 : segments.credits(duration);
        if (!finishedRecorded && ContinueWatching.finished(position, duration)) episodeFinished();
        if (!nextPrefetched && nextEpisode != null && session.entry != null
                && (duration - position <= 120000 || credits > 0 && credits - position <= 30000)) {
            nextPrefetched = true; if (remoteSource()) PlaybackPrefetch.next(this, nextEpisode.id(session.entry.imdb));
        }
        if (credits > 0 && position >= credits && !creditsHandled && !playbackEnded) {
            creditsHandled = true; episodeFinished(); startUpNext(false);
        }
    }

    private void episodeFinished() {
        if (finishedRecorded || session.entry == null || placeholder) return;
        finishedRecorded = true;
        getSharedPreferences("playback-progress", 0).edit().remove(session.videoId).apply();
        String nextId = nextEpisode != null && session.entry.imdb.matches("tt[0-9]+") ? nextEpisode.id(session.entry.imdb) : null;
        ContinueWatching.finished(this, session.entry, nextEpisode);
        long duration = player == null ? session.entry.duration : player.getDuration();
        if (remoteSource()) ContinueWatching.syncProgress(this, session.entry, 0, duration, watchedMs, true, nextId);
        watchedMs = 0;
        PlaybackSession.clearInterrupted(this);
    }

    // --- Sources

    private void next() {
        AddonStreams.Source source = session.next();
        if (source == null && session.discovering) {
            waitingForSources = true; exhausted = false; buffering.setVisibility(View.VISIBLE); return;
        }
        waitingForSources = false;
        if (source == null) {
            buffering.setVisibility(View.GONE);
            exhausted = true; showExhausted(); return;
        }
        attempts++; open(source);
    }
    private void open(AddonStreams.Source source) {
        int request = ++generation;
        release(); sourceValidated = false; videoRendered = false; audioAdvancing = false; placeholder = false; playbackEnded = false; nextAt = 0;
        main.removeCallbacks(advanceEpisode); surface.setAlpha(1); loadingCover.setVisibility(View.VISIBLE); subtitles.setVisibility(View.VISIBLE);
        frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); exhausted = false; autoSubtitleDone = false;
        bufferingSince = SystemClock.elapsedRealtime(); sourceOpenedAt = bufferingSince;
        release = source.release(); sync.reset(); syncSolving = false;
        if (externalCues != null) { externalCues.offset = Prefs.subtitleOffset(this, release); syncDrift = false; }
        session.outcome.put(source.url, "trying");
        PlaybackLog.add(this, "Trying source " + (session.index + 1) + "/" + session.sources.size() + " · " + source.shortQuality() + " · " + source.addon);
        androidx.media3.datasource.DataSource.Factory data = MediaDataSource.factory(this, source, null);
        int backBuffer = PlaybackPlan.backBufferMs(PlaybackPlan.estimateBitrate(source, session.expectedDuration));
        player = new ExoPlayer.Builder(this, new PlaybackAudio(this, sync)).setReleaseTimeoutMs(2000)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data).setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0)))
                .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15000, 45000, 1000, 1500)
                        .setTargetBufferBytes(PlaybackPlan.TARGET_BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false).setBackBuffer(backBuffer, true).build()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true); player.setVideoSurfaceView(surface);
        TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(session.audioLanguage)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true);
        textParameters(parameters);
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
                if (networkError(error.errorCode) && source.url.equals(playedUrl)) { main.post(() -> { if (request == generation && started) networkFailure(source); }); return; }
                failure = error.errorCode >= 4000 && error.errorCode < 5000 ? "decoder" : error.errorCode >= 2000 && error.errorCode < 3000 ? "network" : "error";
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
                    if (scrub.active) {
                        final int capture = previewRequest, sequence = previewDecodeSequence;
                        main.postDelayed(() -> capturePreview(capture, sequence), 16);
                    } else {
                        main.postDelayed(() -> { if (request == generation && !scrub.active) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); } }, 150);
                    }
                } else if (state == Player.STATE_ENDED && !scrub.active) {
                    if (!sourceValidated || placeholder) { failure = "short"; main.post(() -> { if (request == generation && started) retry(); }); return; }
                    session.position = 0; episodeFinished();
                    if (!finishedRecorded) ContinueWatching.record(PlayerActivity.this, session.entry, 0, 0, true);
                    wanted = false; playbackEnded = true; buffering.setVisibility(View.GONE);
                    pause.symbol("play"); pause.setText(Ui.tr("Reproduzir", "Play"));
                    if (!upNextShown) startUpNext(true); else showControls();
                }
                updateKeepAwake();
            }
            @Override public void onCues(CueGroup cues) { if (request == generation && external == null) subtitles.setCues(cues.cues); }
            @Override public void onTracksChanged(Tracks tracks) {
                if (request != generation || player == null || placeholder || tracks.getGroups().isEmpty() || player.getCurrentTracks().getGroups().isEmpty()) return;
                if (rejectPlaceholder(request)) return;
                if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build());
                }
                for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected())
                    for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) {
                        Format format = group.getTrackFormat(i);
                        matchFrameRate(format.frameRate, format.width, format.height);
                        overlay.header.badge(badge(format.height));
                    }
                decideForeign();
                autoSubtitle();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (request == generation && playing) { hideAt = SystemClock.elapsedRealtime() + 4500; updateAspect(); }
                updatePauseIcon(); updateKeepAwake(); if (external != null) renderCues();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (external != null) renderCues();
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                updateAspect();
                if (size.height > 0) overlay.header.badge(badge(size.height));
            }
        });
        player.setMediaItem(MediaItem.fromUri(source.url), session.position); player.prepare(); player.setPlayWhenReady(wanted);
        updatePauseIcon(); showControls();
    }
    private static String badge(int height) {
        return height >= 2000 ? "4K" : height >= 1000 ? "1080P" : height >= 700 ? "720P" : height > 0 ? "SD" : "";
    }
    private void validateSource(int request, AddonStreams.Source source) {
        if (request != generation || player == null || placeholder || !videoRendered || !audioAdvancing) return;
        if (rejectPlaceholder(request)) return;
        if (!sourceValidated) {
            sourceValidated = true; failure = "";
            if (!source.url.equals(playedUrl)) {
                playedUrl = source.url;
                PlaybackMemory.rememberSource(this, session.videoId, source);
                PlaybackLog.add(this, "Playing source " + (session.index + 1) + " · " + source.shortQuality() + " · " + source.addon);
            }
            session.outcome.put(source.url, "playing");
            networkTries = 0; networkFailSince = 0; waitingForNetwork = false;
            recover.setVisibility(View.GONE); networkPill.setVisibility(View.GONE);
            if (overlay.getVisibility() == View.VISIBLE && getCurrentFocus() == null) pause.requestFocus();
            if (externalSubtitles.isEmpty()) loadSubtitles(source); else AddonSubtitles.rank(externalSubtitles, source);
            if (external != null) startSync();
        }
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
        session.failure("audio_error:" + reason); audioAdvancing = false; sourceValidated = false; failure = "silent";
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
            failure = !video ? "picture" : !sound ? "silent" : "short";
            placeholder = true; sourceValidated = false; loadingCover.setVisibility(View.VISIBLE);
            main.post(() -> { if (request == generation && started) retry(); });
        }
        return true;
    }
    private String audioPreference() { return "audio-title:" + session.videoId.split(":")[0]; }
    private void updateAspect() {
        if (player == null || root == null) return;
        androidx.media3.common.VideoSize size = player.getVideoSize();
        int left = 0, top = 0, width = root.getWidth(), height = root.getHeight();
        if (upNextShown) { int[] box = PlayerScreens.UpNext.videoBox(this); left = box[0]; top = box[1]; width = box[2]; height = box[3]; }
        if (size.width <= 0 || size.height <= 0 || width == 0 || height == 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        int w = width, h = Math.round(width / ratio);
        if (h > height) { h = height; w = Math.round(height * ratio); }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, upNextShown ? Gravity.TOP | Gravity.START : Gravity.CENTER);
        if (upNextShown) { lp.leftMargin = left + (width - w) / 2; lp.topMargin = top + (height - h) / 2; }
        surface.setLayoutParams(lp);
    }
    /** Switch to the next source after a failure (or the viewer's request). */
    private void retry() {
        if (!started || exhausted) return;
        boolean played = sourceValidated;
        savePosition();
        if (session.index >= 0 && session.index < session.sources.size()) {
            AddonStreams.Source current = session.sources.get(session.index);
            session.outcome.put(current.url, played && player != null ? "stopped:" + player.getCurrentPosition() : "failed:" + failure);
            if (!"user".equals(failure)) PlaybackLog.add(this, "Source " + (session.index + 1) + " didn't play: " + reasonCode());
        }
        if (scrub.active) { session.position = scrub.finish(false); scrubLayer.setVisibility(View.GONE); previewRequest++; main.removeCallbacks(decodePreview); }
        if (played) session.position = Math.max(0, session.position - 3000); generation++; release();
        main.removeCallbacks(networkRetry); waitingForNetwork = false;
        showRecover();
        next();
    }
    private String reasonCode() { return failure.isEmpty() ? "unknown" : failure; }
    private void retryDecoder(int request, AddonStreams.Source source) {
        if (request != generation || !started) return;
        savePosition(); int recovery = ++generation; release();
        buffering.setVisibility(View.VISIBLE);
        main.postDelayed(() -> { if (started && generation == recovery) open(source); }, 300);
    }

    // --- Network drops

    private static boolean networkError(int code) {
        return code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED || code == PlaybackException.ERROR_CODE_TIMEOUT;
    }
    /** The source that was playing lost the network: wait for it and retry the same source instead of switching. */
    private void networkFailure(AddonStreams.Source source) {
        savePosition();
        if (player != null) session.position = Math.max(0, player.getCurrentPosition());
        generation++; release();
        long now = SystemClock.elapsedRealtime();
        if (networkFailSince == 0) networkFailSince = now;
        waitingForNetwork = true; updateNetworkLine(); networkPill.setVisibility(View.VISIBLE); buffering.setVisibility(View.GONE);
        if (!online) { PlaybackLog.add(this, "Network lost; waiting to resume the same source"); return; }   // onAvailable retries
        if (now - networkFailSince > 30000) { waitingForNetwork = false; failure = "network"; retry(); return; }
        long delay = Math.min(8000, 1000L << Math.min(3, networkTries++));
        main.removeCallbacks(networkRetry); main.postDelayed(networkRetry, delay);
    }
    private void retrySameSource() {
        if (!started || session.index < 0 || session.index >= session.sources.size()) return;
        main.removeCallbacks(networkRetry);
        open(session.sources.get(session.index));
    }
    private void updateNetworkLine() {
        networkPill.line.setText(Ui.tr("A reprodução volta sozinha em ", "Playback picks up on its own at ") + SeekPreview.clock(session.position));
    }
    private void registerNetwork() {
        if (connectivity == null || networkCallback != null) return;
        online = connectivity.getActiveNetwork() != null;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network value) { main.post(() -> onNetwork(true)); }
            @Override public void onLost(Network value) { main.post(() -> onNetwork(connectivity.getActiveNetwork() != null)); }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); } catch (Exception ignored) { networkCallback = null; }
    }
    private void unregisterNetwork() {
        if (connectivity != null && networkCallback != null) try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        networkCallback = null;
    }
    private void onNetwork(boolean available) {
        boolean was = online; online = available;
        if (!started) return;
        long now = SystemClock.elapsedRealtime();
        bufferingSince = bufferingSince == 0 ? 0 : now; sourceOpenedAt = now;   // offline time doesn't count against a source
        if (available && !was) {
            networkFailSince = now; networkTries = 0;
            if (waitingForNetwork && player == null) retrySameSource();
        }
        if (!available) { updateNetworkLine(); }
    }

    // --- Ended, Up next, Still watching

    private void startUpNext(boolean atEnd) {
        if (QuickPanel.consumeEndOfEpisode()) { sleep(); return; }
        if (!nextLoaded) { main.postDelayed(() -> { if (started && (playbackEnded || creditsHandled) && !upNextShown) startUpNext(atEnd); }, 500); return; }
        if (nextEpisode == null) { if (atEnd) showControls(); return; }
        boolean autoplay = Prefs.flag(this, Prefs.PLAYBACK, Prefs.AUTOPLAY, true) && !autoNextCancelled;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (autoplay && PlaybackMemory.stillWatchingDue(PlaybackMemory.autoplayChain(this), hour, Prefs.flag(this, Prefs.PLAYBACK, Prefs.STILL_WATCHING, true))) {
            showStillWatching(); return;
        }
        showUpNext(autoplay);
    }
    private void showUpNext(boolean countdown) {
        if (nextEpisode == null) return;
        upNextShown = true; hideControls(); introActions.setVisibility(View.GONE);
        upNext.current.setText(episodeLine.isEmpty() ? showTitle : episodeLine);
        upNext.name.setText(nextEpisode.name);
        upNext.meta.setText(Ui.tr("T", "S") + nextEpisode.season + " E" + nextEpisode.number + (nextEpisode.minutes > 0 ? " · " + nextEpisode.minutes + " min" : ""));
        upNext.still.setImageDrawable(null);
        if (nextEpisode.still != null && !nextEpisode.still.isEmpty()) {
            final String path = nextEpisode.still;
            network.execute(() -> { android.graphics.Bitmap art = TitleArtwork.load(path, "w780"); if (art != null) main.post(() -> { if (upNextShown) upNext.still.setImageBitmap(art); }); });
        }
        upNext.setVisibility(View.VISIBLE); loadingCover.setVisibility(View.GONE);
        subtitles.setVisibility(View.GONE);
        updateAspect();
        if (countdown) {
            nextAt = SystemClock.elapsedRealtime() + 10000;
            main.removeCallbacks(advanceEpisode); main.postDelayed(advanceEpisode, 10000);
            updateNextCountdown(SystemClock.elapsedRealtime());
        } else { nextAt = 0; upNext.play.icon.progress(-1); upNext.play.trailing(""); }
        upNext.play.requestFocus();
        updateKeepAwake();
    }
    private void updateNextCountdown(long now) {
        long left = Math.max(0, nextAt - now), seconds = (left + 999) / 1000;
        upNext.play.icon.progress(left / 10000f);
        upNext.play.trailing(seconds + " s");
        upNext.play.setContentDescription(Ui.tr("Assistir agora", "Play now") + ". " + nextEpisode.code() + ". " + nextEpisode.name + ". "
                + String.format(Ui.tr("Começa em %d segundos", "Starts in %d seconds"), seconds));
    }
    private void closeUpNext() {
        upNextShown = false; upNext.setVisibility(View.GONE); subtitles.setVisibility(View.VISIBLE);
        updateAspect();
    }
    /** "Watch the credits" (and Back on Up next): no autoplay, the video goes back to full screen. */
    private void watchCredits() { cancelAutoNext(); closeUpNext(); showControls(); }
    private void cancelAutoNext() {
        autoNextCancelled = true; nextAt = 0; main.removeCallbacks(advanceEpisode);
        if (upNext != null) { upNext.play.icon.progress(-1); upNext.play.trailing(""); }
        PlaybackMemory.setAutoplayChain(this, 0);
        if (nextEpisode != null && !upNextShown) nextEpisodeButton.requestFocus();
        updateKeepAwake();
    }
    private void showStillWatching() {
        stillShown = true; stillPaused = false; stillUntil = SystemClock.elapsedRealtime() + 60000;
        hideControls(); introActions.setVisibility(View.GONE);
        stillWatching.header.set(showTitle, nextEpisode == null ? "" : Ui.tr("A seguir · ", "Up next · ") + nextEpisode.code() + " · " + nextEpisode.name);
        stillWatching.line.setText(String.format(Ui.tr("Você assistiu %d episódios seguidos.", "You’ve watched %d episodes in a row."), PlaybackMemory.autoplayChain(this)));
        stillWatching.setVisibility(View.VISIBLE); stillWatching.keep.requestFocus();
        updateStillWatching(SystemClock.elapsedRealtime());
    }
    private void updateStillWatching(long now) {
        stillWatching.clock.setText(Ui.clock(System.currentTimeMillis()));
        long left = Math.max(0, stillUntil - now);
        if (!stillPaused && left == 0) {
            stillPaused = true;
            if (player != null) { wanted = false; player.setPlayWhenReady(false); }
            updateKeepAwake();
        }
        stillWatching.ring.progress(left / 60000f);
        stillWatching.timer.setText(stillPaused ? Ui.tr("Pausado", "Paused") : Ui.tr("Pausa em ", "Pauses in ") + SeekPreview.clock(left + 999));
    }
    private void keepWatching() {
        stillShown = false; stillWatching.setVisibility(View.GONE);
        PlaybackMemory.setAutoplayChain(this, 0);
        playNextEpisode(false);
    }
    private void stopForTonight() {
        PlaybackMemory.setAutoplayChain(this, 0);
        stillShown = false; finish();
    }
    private void playNextEpisode(boolean automatic) {
        if (nextEpisode == null || advancingEpisode) return;
        if (!automatic) PlaybackMemory.setAutoplayChain(this, 0);
        advancingEpisode = true; main.removeCallbacks(advanceEpisode);
        if (!finishedRecorded) savePosition();
        syncLibrary();
        setResult(RESULT_OK, new android.content.Intent().putExtra("next_episode", true)
                .putExtra("season", nextEpisode.season).putExtra("episode", nextEpisode.number));
        finish();
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
            });
        });
    }

    // --- Sleep timer (QuickPanel)

    @Override public long endsAt() {
        if (player == null || player.getDuration() <= 0) return 0;
        long end = segments == null ? -1 : segments.credits(player.getDuration());
        return System.currentTimeMillis() + Math.max(0, (end > 0 ? end : player.getDuration()) - player.getCurrentPosition());
    }
    @Override public void sleep() {
        cancelAutoNext();
        if (player != null) { wanted = false; player.setPlayWhenReady(false); }
        if (upNextShown) closeUpNext();
        showControls(); updatePauseIcon();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // --- Saving

    private void release() {
        if (preparedPreviews != null) { preparedPreviews.close(); preparedPreviews = null; }
        if (preview != null) preview.clear(); lastWarmPreview = -1;
        if (player != null) { player.release(); player = null; }
        if (subtitles != null && external == null) subtitles.setCues(Collections.emptyList());
        main.removeCallbacks(cueTick);
    }
    private void savePosition() {
        if (player == null || session == null || !sourceValidated || placeholder) return;
        if (player.getPlaybackState() == Player.STATE_ENDED && !scrub.active) return;
        long position = scrub.active ? scrub.original : player.getCurrentPosition();
        if (position > 0) session.position = position;
        if (finishedRecorded) return;   // the credits don't make a "2 min left" card
        getSharedPreferences("playback-progress", 0).edit().putLong(session.videoId, session.position).apply();
        ContinueWatching.record(this, session.entry, session.position, player.getDuration(), false);
        if (session.position > 5000) PlaybackSession.persist(this, session, session.position);
    }
    /** Add-on sources are HTTPS; local test media never reach the Stremio account or prefetching. */
    private boolean remoteSource() {
        return session.index >= 0 && session.index < session.sources.size() && MediaHttp.https(session.sources.get(session.index).url);
    }
    /** Progress to the Stremio library when playback stops. */
    private void syncLibrary() {
        if (session == null || session.entry == null || finishedRecorded || session.position < 5000 || watchedMs < 1000 || !remoteSource()) return;
        long duration = player != null && player.getDuration() > 0 ? player.getDuration() : session.entry.duration;
        ContinueWatching.syncProgress(this, session.entry, session.position, duration, watchedMs, false, null);
        watchedMs = 0;
    }
    private void togglePause() {
        if (player == null) return;
        boolean replay = playbackEnded;
        if (replay) { cancelAutoNext(); playbackEnded = false; player.seekTo(0); if (upNextShown) closeUpNext(); }
        wanted = replay || !player.getPlayWhenReady(); if (wanted) sourceOpenedAt = SystemClock.elapsedRealtime(); player.setPlayWhenReady(wanted);
        updatePauseIcon(); bufferingSince = 0; showControls(); updateMediaSession(); updateKeepAwake();
    }
    private void updatePauseIcon() {
        boolean playing = player != null && player.getPlayWhenReady() && !playbackEnded;
        pause.symbol(playing ? "pause" : "play"); pause.setText(playing ? Ui.tr("Pausar", "Pause") : Ui.tr("Reproduzir", "Play"));
    }
    private void jump(long delta) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long from = player.getCurrentPosition(), to = Math.max(0, Math.min(Math.max(0, player.getDuration() - 1000), from + delta));
        player.seekTo(to); sourceOpenedAt = SystemClock.elapsedRealtime(); showControls();
        if (delta < 0) replaySubtitles(from);
    }
    /** Keeps the screen on only while video plays, loads, or counts down to the next episode. */
    private void updateKeepAwake() {
        boolean awake = started && !stillPaused && (player == null && !exhausted
                || player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED
                || upNextShown && nextAt > 0 || stillShown);
        if (awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // --- Refresh rate (#10)

    private void matchFrameRate(float fps, int width, int height) {
        if (fps <= 0 || !Prefs.flag(this, Prefs.PLAYBACK, Prefs.MATCH_FRAME_RATE, true)) return;
        Display display = getWindow().getDecorView().getDisplay();
        if (display == null) return;
        int mode = pickDisplayMode(display.getMode(), display.getSupportedModes(), fps);
        if (mode == 0 || mode == displayMode) return;
        displayMode = mode;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = mode; getWindow().setAttributes(attributes);
        PlaybackLog.add(this, String.format(java.util.Locale.ROOT, "Display set to match %.3f fps", fps));
    }
    /** A mode with the current resolution whose refresh is a whole multiple of fps (lowest such), or 0 to stay. */
    static int pickDisplayMode(Display.Mode current, Display.Mode[] modes, float fps) {
        if (current == null || modes == null || fps < 10) return 0;
        if (multiple(current.getRefreshRate(), fps)) return 0;
        Display.Mode best = null;
        for (Display.Mode m : modes) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth() || m.getPhysicalHeight() != current.getPhysicalHeight()) continue;
            if (!multiple(m.getRefreshRate(), fps)) continue;
            if (best == null || m.getRefreshRate() < best.getRefreshRate()) best = m;
        }
        return best == null ? 0 : best.getModeId();
    }
    static boolean multiple(float refresh, float fps) {
        double ratio = refresh / fps; long whole = Math.round(ratio);
        return whole >= 1 && Math.abs(ratio - whole) < 0.004 * whole;
    }
    private void restoreDisplayMode() {
        if (displayMode == 0) return;
        displayMode = 0;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = 0; getWindow().setAttributes(attributes);
    }

    // --- Subtitles

    private void styleSubtitles() {
        String size = preferences.getString(Prefs.SUBTITLE_SIZE, "medium");
        boolean box = "box".equals(preferences.getString(Prefs.SUBTITLE_BACKGROUND, "shadow"));
        float px = "small".equals(size) ? 40 : "large".equals(size) ? 60 : 48;
        subtitles.setApplyEmbeddedStyles(false); subtitles.setApplyEmbeddedFontSizes(false);
        subtitles.setStyle(new CaptionStyleCompat(Look.INK, box ? Look.SCRIM : 0, 0,
                box ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Look.PLAYER, Ui.medium));
        subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, Look.s(this, px));
        subtitles.setBottomPaddingFraction(0.08f);
    }
    /** The viewer's default or this show's pick: null = off (forced lines only), else a language. */
    private void decideSubtitles() {
        String pick = PlaybackMemory.subtitles(this, session.videoId);
        String language = preferences.getString(Prefs.SUBTITLE_LANGUAGE, "");
        if (language.isEmpty()) language = Ui.en ? "en" : "pt";
        textDecided = true; foreignMode = false;
        if (pick != null) {
            wantText = pick.equals("off") ? null : pick.substring(pick.indexOf(':') + 1);
            return;
        }
        String mode = preferences.getString(Prefs.SUBTITLE_MODE, preferences.getBoolean("subtitles", false) ? "always" : "foreign");
        if ("always".equals(mode)) wantText = language;
        else if ("forced".equals(mode)) wantText = null;
        else { wantText = language; foreignMode = true; textDecided = false; }
    }
    /** "Foreign" mode: subtitles only when the audio isn't in the viewer's language (decided once tracks are known). */
    private void decideForeign() {
        if (textDecided || player == null) return;
        String audio = selectedLanguage(C.TRACK_TYPE_AUDIO);
        if (audio == null) return;
        textDecided = true;
        if (AddonSubtitles.sameLanguage(audio, wantText)) wantText = null;
        applyTextParameters();
    }
    private String selectedLanguage(int type) {
        if (player == null) return null;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) if (group.getType() == type)
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return group.getTrackFormat(i).language == null ? "und" : group.getTrackFormat(i).language;
        return null;
    }
    private void textParameters(TrackSelectionParameters.Builder b) {
        if (external != null) { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); return; }
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setSelectUndeterminedTextLanguage(false)
                // A "default" flag alone must not turn on a full subtitle track: only the language or a forced flag.
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT);
        if (wantText != null && textDecided) b.setPreferredTextLanguage(wantText); else b.setPreferredTextLanguages();
    }
    private void applyTextParameters() {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        textParameters(b);
        player.setTrackSelectionParameters(b.build());
    }
    /** If the file has no subtitle in the wanted language, turn on the best add-on one (once per source). */
    private void autoSubtitle() {
        if (autoSubtitleDone || player == null || wantText == null || !textDecided || external != null || player.getCurrentTracks().getGroups().isEmpty()) return;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT)
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i) && AddonSubtitles.sameLanguage(group.getTrackFormat(i).language, wantText)
                    && (group.getTrackFormat(i).selectionFlags & C.SELECTION_FLAG_FORCED) == 0) { autoSubtitleDone = true; return; }
        if (externalSubtitles.isEmpty()) return;   // tried again when the list arrives
        autoSubtitleDone = true;
        for (AddonSubtitles.Subtitle s : externalSubtitles) if (AddonSubtitles.sameLanguage(s.language, wantText)) { useExternal(s, false); return; }
    }
    private void loadSubtitles(AddonStreams.Source source) {
        final String video = session.videoId;
        network.execute(() -> {
            List<AddonSubtitles.Subtitle> loaded = new ArrayList<>();
            try { loaded = AddonSubtitles.load(this, video, source); } catch (Exception ignored) {}
            final List<AddonSubtitles.Subtitle> result = loaded;
            main.post(() -> {
                if (isDestroyed() || result.isEmpty()) return;
                java.util.Set<String> seen = new java.util.HashSet<>(); for (AddonSubtitles.Subtitle s : externalSubtitles) seen.add(s.url);
                for (AddonSubtitles.Subtitle s : result) if (seen.add(s.url)) externalSubtitles.add(s);
                autoSubtitle();
            });
        });
    }
    /** Shows an add-on subtitle with our own scheduler; the player isn't touched beyond hiding embedded text. */
    private void useExternal(AddonSubtitles.Subtitle subtitle, boolean chosen) {
        external = subtitle; externalCues = null; cueCache = null; shownLines = ""; syncDone = false; syncDrift = false; sync.stop(); sync.reset();
        subtitles.setCues(Collections.emptyList());
        applyTextParameters();
        if (chosen) PlaybackMemory.rememberSubtitles(this, session.videoId, "addon:" + subtitle.language);
        final String address = subtitle.url;
        network.execute(() -> {
            SubtitleCues cues = null;
            try { cues = SubtitleCues.load(this, address); } catch (Exception ignored) {}
            final SubtitleCues result = cues;
            main.post(() -> {
                if (isDestroyed() || external != subtitle) return;
                if (result == null) { PlaybackLog.add(this, "Add-on subtitle could not be read"); external = null; applyTextParameters(); return; }
                result.offset = Prefs.subtitleOffset(this, release);
                externalCues = result; cueCache = new Cue[result.size()];
                if (sourceValidated) startSync();
                renderCues();
            });
        });
    }
    private void subtitlesOff(boolean remember) {
        external = null; externalCues = null; cueCache = null; sync.stop(); main.removeCallbacks(cueTick);
        subtitles.setCues(Collections.emptyList()); wantText = null; textDecided = true; replayUntil = -1;
        if (remember) { PlaybackMemory.rememberSubtitles(this, session.videoId, "off"); preferences.edit().putBoolean("subtitles", false).apply(); }
        applyTextParameters();
    }
    /** Draws the add-on cues showing now and schedules the next change (no work between changes). */
    private void renderCues() {
        main.removeCallbacks(cueTick);
        if (externalCues == null || player == null || scrub.active) return;
        long position = player.getCurrentPosition();
        externalCues.visible(position, visibleLines);
        String key = visibleLines.isEmpty() ? "" : String.join("\n", visibleLines);
        if (!key.equals(shownLines)) {
            shownLines = key;
            if (visibleLines.isEmpty()) subtitles.setCues(Collections.emptyList());
            else {
                List<Cue> cues = new ArrayList<>(visibleLines.size());
                for (String line : visibleLines) cues.add(new Cue.Builder().setText(line).build());
                subtitles.setCues(cues);
            }
        }
        if (!player.isPlaying()) { main.postDelayed(cueTick, 250); return; }
        long nextChange = externalCues.nextChange(position);
        long delay = nextChange == Long.MAX_VALUE ? 1000 : Math.max(15, Math.min(1000, nextChange - position));
        main.postDelayed(cueTick, delay);
    }
    /** S3: subtitles timed for another frame rate drift; fix the speed once the duration is known. */
    private void applyDrift(long duration) {
        if (externalCues == null) return;
        double scale = SubtitleCues.driftScale(externalCues.lastEnd(), duration);
        if (scale != 1 && externalCues.scale == 1) { externalCues.scale = scale; renderCues(); PlaybackLog.add(this, "Subtitle speed adjusted for frame rate"); }
    }
    /** S4: listen to the first minutes and line the cues up with the dialogue, unless the viewer set the timing. */
    private void startSync() {
        if (syncDone || externalCues == null || !preferences.getBoolean(Prefs.AUTO_SYNC, true) || Prefs.subtitleOffset(this, release) != 0) return;
        if (player != null && player.getCurrentPosition() > SubtitleSync.FRAMES * (long) SubtitleSync.FRAME_MS - 120000) return;
        sync.start();
    }
    private void checkSync(long position) {
        if (syncSolving || externalCues == null) return;
        if (position > SubtitleSync.FRAMES * (long) SubtitleSync.FRAME_MS) { sync.stop(); syncDone = true; return; }
        if (sync.heard() < SubtitleSync.NEEDED_FRAMES) return;
        syncSolving = true; sync.stop();
        final SubtitleCues cues = externalCues; final byte[] speech = sync.speech();
        network.execute(() -> {
            SubtitleSync.Result result = SubtitleSync.solve(cues, speech);
            main.post(() -> {
                syncSolving = false; syncDone = true;
                if (isDestroyed() || externalCues != cues || player == null || !result.confident) {
                    if (!result.confident) PlaybackLog.add(this, "Subtitle sync not confident; timing unchanged");
                    return;
                }
                long at = cues.toFile(player.getCurrentPosition());
                long change = Math.round(at * result.scale + result.offset) - cues.toMedia(at);
                if (Math.abs(change) < 300) return;
                undoOffset = cues.offset; undoScale = cues.scale;
                cues.offset = result.offset; cues.scale = result.scale; renderCues();
                if (result.scale == 1) Prefs.setSubtitleOffset(this, release, result.offset);
                PlaybackLog.add(this, String.format(java.util.Locale.ROOT, "Subtitles synced: %+.1f s", change / 1000.0));
                showSynced(change);
            });
        });
    }
    private void showSynced(long change) {
        synced.change.setText(String.format(Ui.tr("Movidas %s s %s", "Moved %s s %s"), String.format(java.util.Locale.ROOT, "%.1f", Math.abs(change) / 1000.0),
                change < 0 ? Ui.tr("antes", "earlier") : Ui.tr("depois", "later")));
        synced.setVisibility(View.VISIBLE);
        synced.remaining.setScaleX(1); synced.remaining.animate().scaleX(0).setDuration(6000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
        main.removeCallbacks(hideSynced); main.postDelayed(hideSynced, 6000);
    }
    private void undoSync() {
        if (externalCues == null) return;
        externalCues.offset = undoOffset; externalCues.scale = undoScale;
        Prefs.setSubtitleOffset(this, release, undoOffset);
        synced.setVisibility(View.GONE); renderCues();
    }
    /** "What did they say?": after a rewind of 30 s or less with subtitles off, show built-in ones until caught up. */
    private void replaySubtitles(long from) {
        if (player == null || external != null || wantText != null || from - player.getCurrentPosition() > 30000) return;
        String audio = selectedLanguage(C.TRACK_TYPE_AUDIO);
        List<String> languages = new ArrayList<>();
        if (audio != null && !audio.equals("und")) languages.add(audio);
        String own = preferences.getString(Prefs.SUBTITLE_LANGUAGE, ""); if (!own.isEmpty()) languages.add(own);
        if (languages.isEmpty()) return;
        replayUntil = from;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setIgnoredTextSelectionFlags(0).setSelectUndeterminedTextLanguage(true)
                .setPreferredTextLanguages(languages.toArray(new String[0])).build());
    }
    private void showTiming() {
        if (externalCues == null) return;
        hideControls(); timing.set(externalCues.offset); timing.setVisibility(View.VISIBLE);
    }
    private void adjustTiming(int direction, int repeat) {
        if (externalCues == null) return;
        long step = repeat >= 10 ? 500 : 100;
        externalCues.offset = Math.max(-60000, Math.min(60000, externalCues.offset + direction * step));
        timing.set(externalCues.offset); renderCues();
    }
    private void closeTiming() {
        timing.setVisibility(View.GONE);
        if (externalCues != null) { Prefs.setSubtitleOffset(this, release, externalCues.offset); sync.stop(); syncDone = true; }
        showControls();
    }

    // --- Sheets

    private void chooseTracks(int focusType) {
        if (player == null) return;
        PlayerSheet sheet = new PlayerSheet(this, Ui.tr("Áudio e legendas", "Audio & subtitles"), null, 940);
        sheet.body.setPadding(sheet.px(40), sheet.px(56), sheet.px(40), sheet.px(48));
        LinearLayout columns = new LinearLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1); cp.topMargin = sheet.px(40);
        sheet.body.addView(columns, cp);
        LinearLayout audioColumn = new LinearLayout(this), textColumn = new LinearLayout(this);
        audioColumn.setOrientation(LinearLayout.VERTICAL); textColumn.setOrientation(LinearLayout.VERTICAL);
        columns.addView(audioColumn, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1); tp.leftMargin = sheet.px(40);
        columns.addView(textColumn, tp);
        sheet.section(audioColumn, Ui.tr("Áudio", "Audio"));
        LinearLayout audioList = sheet.list(audioColumn, 0);
        sheet.section(textColumn, Ui.tr("Legendas", "Subtitles"));
        LinearLayout textList = sheet.list(textColumn, 0);
        List<PlayerSheet.Row> audioRows = new ArrayList<>(), textRows = new ArrayList<>();
        View[] focus = {null};
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                final int track = i; final Format format = group.getTrackFormat(i);
                PlayerSheet.Row row = sheet.row(audioList, AddonSubtitles.language(format.language), audioNote(format), group.isTrackSelected(i), true, null);
                row.setOnClickListener(v -> {
                    preferences.edit().putString(audioPreference(), format.language == null ? "und" : format.language).apply();
                    session.audioLanguage = format.language;
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setPreferredAudioLanguage(format.language).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), track)).build());
                    for (PlayerSheet.Row r : audioRows) r.checked(r == v);
                });
                audioRows.add(row);
                if (focusType == C.TRACK_TYPE_AUDIO && (focus[0] == null || group.isTrackSelected(i))) focus[0] = row;
            }
        }
        boolean textOn = false;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) textOn = true;
        PlayerSheet.Row off = sheet.row(textList, Ui.tr("Desativadas", "Off"), null, external == null && (!textOn || wantText == null && replayUntil < 0), true, null);
        off.setOnClickListener(v -> { subtitlesOff(true); for (PlayerSheet.Row r : textRows) r.checked(r == v); });
        textRows.add(off);
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                final int track = i; final Format format = group.getTrackFormat(i);
                boolean forced = (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0;
                String note = forced ? Ui.tr("Forçada · na TV", "Forced · Built in") : format.label != null && !format.label.isEmpty()
                        ? AddonStreams.clean(format.label, 40) + " · " + Ui.tr("Na TV", "Built in") : Ui.tr("Na TV", "Built in");
                PlayerSheet.Row row = sheet.row(textList, AddonSubtitles.language(format.language), note, external == null && group.isTrackSelected(i) && wantText != null, true, null);
                row.setOnClickListener(v -> {
                    external = null; externalCues = null; sync.stop(); main.removeCallbacks(cueTick); subtitles.setCues(Collections.emptyList());
                    wantText = format.language; textDecided = true; replayUntil = -1;
                    PlaybackMemory.rememberSubtitles(this, session.videoId, "embedded:" + (format.language == null ? "und" : format.language));
                    preferences.edit().putBoolean("subtitles", true).apply();
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), track)).build());
                    for (PlayerSheet.Row r : textRows) r.checked(r == v);
                });
                textRows.add(row);
            }
        }
        // Add-on subtitles: the viewer's language first, then the rest, each in ranked order.
        List<AddonSubtitles.Subtitle> ordered = new ArrayList<>();
        String language = wantText != null ? wantText : preferences.getString(Prefs.SUBTITLE_LANGUAGE, Ui.en ? "en" : "pt");
        for (AddonSubtitles.Subtitle s : externalSubtitles) if (AddonSubtitles.sameLanguage(s.language, language)) ordered.add(s);
        for (AddonSubtitles.Subtitle s : externalSubtitles) if (!ordered.contains(s) && ordered.size() < 40) ordered.add(s);
        for (AddonSubtitles.Subtitle subtitle : ordered) {
            String label = subtitle.label.isEmpty() || subtitle.label.equalsIgnoreCase(AddonSubtitles.language(subtitle.language)) ? "" : subtitle.label + " · ";
            String note = (subtitle.matched ? Ui.tr("Feita para este arquivo · ", "Matches this file · ") : label) + subtitle.addon;
            PlayerSheet.Row row = sheet.row(textList, AddonSubtitles.language(subtitle.language), note, external != null && external.url.equals(subtitle.url), true, null);
            row.setContentDescription(subtitle.name + ", " + note);
            row.setOnClickListener(v -> {
                wantText = subtitle.language; textDecided = true; replayUntil = -1;
                preferences.edit().putBoolean("subtitles", true).apply();
                useExternal(subtitle, true);
                for (PlayerSheet.Row r : textRows) r.checked(r == v);
            });
            textRows.add(row);
        }
        if (focusType == C.TRACK_TYPE_TEXT) for (PlayerSheet.Row r : textRows) if (focus[0] == null || r.trailing.getVisibility() == View.VISIBLE && r.isActivated()) focus[0] = r;
        if (focus[0] == null) for (PlayerSheet.Row r : textRows) if (r.trailing.getVisibility() == View.VISIBLE) focus[0] = r;
        // Subtitle look: size, background, timing.
        LinearLayout look = new LinearLayout(this); look.setOrientation(LinearLayout.VERTICAL);
        look.setPadding(0, sheet.px(28), 0, 0);
        View line = new View(this); line.setBackgroundColor(Look.RAISED_2);
        look.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, sheet.px(1))));
        TextView lookLabel = PlayerViews.text(this, Ui.tr("Aparência da legenda", "Subtitle look"), Look.SMALL, Look.INK_3, Ui.medium);
        lookLabel.setPadding(sheet.px(20), sheet.px(28), 0, sheet.px(12));
        look.addView(lookLabel);
        LinearLayout tiles = new LinearLayout(this);
        String size = preferences.getString(Prefs.SUBTITLE_SIZE, "medium");
        LinearLayout sizeTile = tile(tiles, Ui.tr("Tamanho", "Size"), sizeLabel(size), false);
        sizeTile.setOnClickListener(v -> {
            String now = preferences.getString(Prefs.SUBTITLE_SIZE, "medium");
            String value = "small".equals(now) ? "medium" : "medium".equals(now) ? "large" : "small";
            preferences.edit().putString(Prefs.SUBTITLE_SIZE, value).apply(); styleSubtitles();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(sizeLabel(value));
        });
        LinearLayout backgroundTile = tile(tiles, Ui.tr("Fundo", "Background"), backgroundLabel(preferences.getString(Prefs.SUBTITLE_BACKGROUND, "shadow")), true);
        backgroundTile.setOnClickListener(v -> {
            String value = "box".equals(preferences.getString(Prefs.SUBTITLE_BACKGROUND, "shadow")) ? "shadow" : "box";
            preferences.edit().putString(Prefs.SUBTITLE_BACKGROUND, value).apply(); styleSubtitles();
            ((TextView) ((LinearLayout) v).getChildAt(1)).setText(backgroundLabel(value));
        });
        long offset = externalCues == null ? 0 : externalCues.offset;
        LinearLayout timingTile = tile(tiles, Ui.tr("Sincronia", "Timing"), offset == 0 ? Ui.tr("No tempo", "On time")
                : String.format(java.util.Locale.ROOT, "%+.1f s", offset / 1000.0), true);
        timingTile.setEnabled(externalCues != null); timingTile.setFocusable(externalCues != null);
        timingTile.setOnClickListener(v -> { sheet.dismiss(); showTiming(); });
        look.addView(tiles);
        sheet.body.addView(look);
        choosing = true; showControls();
        sheet.onDismiss(() -> { choosing = false; if (timing.getVisibility() != View.VISIBLE) showControls(); });
        sheet.show(focus[0]);
    }
    private LinearLayout tile(LinearLayout row, String label, String value, boolean gap) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setFocusable(true); tile.setClickable(true); tile.setId(View.generateViewId());
        tile.setBackground(PlayerViews.states(this, Look.RAISED_2, 18));
        tile.setPadding(Look.si(this, 20), 0, Look.si(this, 20), 0);
        TextView top = PlayerViews.text(this, label, Look.SMALL, Look.INK_3, Ui.regular);
        top.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {-android.R.attr.state_enabled}, {}}, new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_4, Look.INK_3}));
        top.setDuplicateParentStateEnabled(true);
        TextView bottom = PlayerViews.text(this, value, Look.LABEL, Look.INK, Ui.medium);
        bottom.setTextColor(PlayerViews.ink(Look.INK)); bottom.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = Look.si(this, 4);
        tile.addView(top); tile.addView(bottom, bp);
        tile.setContentDescription(label + ", " + value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Look.si(this, 88), 1); if (gap) lp.leftMargin = Look.si(this, 12);
        row.addView(tile, lp);
        return tile;
    }
    private static String sizeLabel(String value) {
        return "small".equals(value) ? Ui.tr("Pequeno", "Small") : "large".equals(value) ? Ui.tr("Grande", "Large") : Ui.tr("Médio", "Medium");
    }
    private static String backgroundLabel(String value) { return "box".equals(value) ? Ui.tr("Caixa", "Box") : Ui.tr("Sombra", "Shadow"); }
    private String audioNote(Format format) {
        List<String> parts = new ArrayList<>();
        if (format.channelCount > 2) parts.add(format.channelCount == 6 ? "5.1" : format.channelCount == 8 ? "7.1" : format.channelCount + " " + Ui.tr("canais", "channels"));
        else if (format.channelCount == 2) parts.add(Ui.tr("Estéreo", "Stereo"));
        else if (format.channelCount == 1) parts.add("Mono");
        if (format.label != null && !format.label.isEmpty() && !format.label.equalsIgnoreCase(AddonSubtitles.language(format.language))) parts.add(AddonStreams.clean(format.label, 40));
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) parts.add(Ui.tr("Audiodescrição", "Audio description"));
        return String.join(" · ", parts);
    }
    private void chooseSource() {
        PlayerSheet sheet = new PlayerSheet(this, Ui.tr("Escolher uma fonte", "Choose a source"),
                Ui.tr("O LumaTV escolhe uma para você. Escolha aqui para trocar.", "LumaTV picks one for you. Choose here to override it."), 940);
        sheet.dim(0.6f);
        LinearLayout list = sheet.list(sheet.body, 36);
        List<AddonStreams.Source> all = new ArrayList<>(session.all);
        all.sort((a, b) -> Integer.compare(b.quality, a.quality));
        AddonStreams.Source current = session.index >= 0 && session.index < session.sources.size() ? session.sources.get(session.index) : null;
        View focus = null; int shown = 0;
        for (AddonStreams.Source source : all) {
            if (shown++ >= 40) break;
            String status = sourceStatus(source, current);
            boolean enabled = source.unsupported == null;
            LinearLayout row = sourceRow(list, source, status, enabled);
            row.setOnClickListener(v -> { sheet.dismiss(); useSource(source); });
            if (current != null && source.url.equals(current.url) || focus == null && enabled) focus = row;
        }
        if (shown == 0) sheet.row(list, Ui.tr("Nenhuma fonte encontrada", "No sources found"), null, false, false, null);
        choosing = true;
        sheet.onDismiss(() -> { choosing = false; if (recover.getVisibility() != View.VISIBLE) showControls(); });
        sheet.show(focus);
    }
    private String sourceStatus(AddonStreams.Source source, AddonStreams.Source current) {
        if (source.unsupported != null) return Ui.tr("Não funciona nesta TV", "Not supported on this TV");
        String outcome = session.outcome.get(source.url);
        if (current != null && source.url.equals(current.url)) return sourceValidated ? Ui.tr("Reproduzindo", "Playing") : Ui.tr("Tentando agora", "Trying now");
        if (outcome != null && outcome.startsWith("stopped:")) {
            try { return Ui.tr("Parou em ", "Stopped at ") + SeekPreview.clock(Long.parseLong(outcome.substring(8))); } catch (Exception ignored) {}
        }
        if (outcome != null && outcome.startsWith("failed")) return Ui.tr("Não reproduziu", "Didn’t play");
        if (source.otherEpisode) return Ui.tr("Outro episódio", "Another episode");
        return "";
    }
    private LinearLayout sourceRow(LinearLayout list, AddonStreams.Source source, String status, boolean enabled) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(enabled); row.setClickable(enabled); row.setEnabled(enabled); row.setId(View.generateViewId());
        row.setBackground(PlayerViews.states(this, 0, 20)); row.setMinimumHeight(Look.si(this, 96));
        row.setPadding(Look.si(this, 24), Look.si(this, 8), Look.si(this, 24), Look.si(this, 8));
        if (!enabled) row.setAlpha(0.45f);
        TextView quality = PlayerViews.text(this, source.shortQuality(), 30, Look.INK, Ui.semibold);
        quality.setTextColor(PlayerViews.ink(Look.INK)); quality.setDuplicateParentStateEnabled(true);
        row.addView(quality, new LinearLayout.LayoutParams(Look.si(this, 120), -2));
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL); texts.setDuplicateParentStateEnabled(true);
        TextView addon = PlayerViews.text(this, source.addon == null ? "" : source.addon, Look.LABEL, Look.INK, Ui.medium);
        addon.setTextColor(PlayerViews.ink(Look.INK)); addon.setDuplicateParentStateEnabled(true);
        texts.addView(addon);
        String meta = source.meta();
        if (meta.isEmpty()) meta = AddonStreams.clean(source.name == null ? "" : source.name.replace('\n', ' '), 60);
        TextView detail = PlayerViews.text(this, meta, Look.SMALL, Look.INK_3, Ui.regular);
        detail.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_3}));
        detail.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2); dp.topMargin = Look.si(this, 4);
        texts.addView(detail, dp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1); tp.leftMargin = Look.si(this, 20);
        row.addView(texts, tp);
        TextView state = PlayerViews.text(this, status, Look.SMALL, Look.INK_3, Ui.regular);
        state.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Look.ON_INK, 0.6f), Look.INK_3}));
        state.setDuplicateParentStateEnabled(true); state.setGravity(Gravity.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.leftMargin = Look.si(this, 20);
        row.addView(state, sp);
        row.setContentDescription(source.shortQuality() + ", " + source.addon + ", " + meta + (status.isEmpty() ? "" : ", " + status));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = Look.si(this, 6);
        list.addView(row, lp);
        return row;
    }
    /** The viewer picked a source: play it next, keeping the position. */
    private void useSource(AddonStreams.Source source) {
        if (!started) return;
        if (session.index >= 0 && session.index < session.sources.size() && session.sources.get(session.index).url.equals(source.url) && player != null && sourceValidated) return;
        session.choose(source); exhausted = false; attempts = 0; failure = "user";
        recover.setVisibility(View.GONE);
        boolean played = sourceValidated; savePosition();
        if (session.index >= 0 && session.index < session.sources.size() && player != null)
            session.outcome.put(session.sources.get(session.index).url, played ? "stopped:" + player.getCurrentPosition() : "failed:user");
        generation++; release(); next();
    }
    private void playbackOptions() {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add(Ui.tr("Escolher uma fonte", "Choose a source")); actions.add(() -> main.post(this::chooseSource));
        labels.add(Ui.tr("Tentar outra fonte", "Try another source")); actions.add(() -> { attempts = 0; failure = "user"; retry(); });
        labels.add(Ui.tr("Começar do início", "Start over")); actions.add(() -> { if (player != null) { cancelAutoNext(); playbackEnded = false; if (upNextShown) closeUpNext(); player.seekTo(0); player.setPlayWhenReady(true); wanted = true; session.position = 0; updatePauseIcon(); } });
        labels.add(Ui.tr("Ajustes rápidos", "Quick settings")); actions.add(() -> main.post(() -> QuickPanel.show(this)));
        choosing = true;
        PlayerSheet.show(this, Ui.tr("Mais", "More"), labels, -1, actions, () -> { choosing = false; showControls(); });
    }

    // --- Recover screen

    private String reasonLine() {
        switch (failure) {
            case "silent": return Ui.tr("Esta tocou sem som.", "This one played without sound.");
            case "picture": return Ui.tr("Esta não mostrou imagem.", "This one showed no picture.");
            case "short": return Ui.tr("Esta era um trecho curto, não o vídeo inteiro.", "This one was a short clip, not the full video.");
            case "decoder": return Ui.tr("Esta TV não consegue tocar esta.", "This TV can’t play this one.");
            case "network": return Ui.tr("Esta parou de carregar.", "This one stopped loading.");
            case "slow": return Ui.tr("Esta estava carregando devagar demais.", "This one was loading too slowly.");
            case "user": return "";
            default: return Ui.tr("Esta não reproduziu.", "This one didn’t play.");
        }
    }
    private void showRecover() {
        if (session.index + 1 >= session.sources.size() && !session.discovering) return;   // showExhausted follows
        hideControls(); introActions.setVisibility(View.GONE);
        if (upNextShown) closeUpNext();
        recover.title.setText(Ui.tr("Trocando de fonte", "Switching to another source"));
        String reason = reasonLine();
        String resume = session.position > 5000 ? String.format(Ui.tr("Você continua em %s.", "You’ll pick up at %s."), SeekPreview.clock(session.position)) : "";
        recover.line.setText((reason + " " + resume).trim());
        recover.line.setVisibility(recover.line.getText().length() == 0 ? View.GONE : View.VISIBLE);
        recover.spinner.setVisibility(View.VISIBLE); ((View) recover.dashes.getParent()).setVisibility(View.VISIBLE);
        int nextIndex = Math.min(session.index + 1, session.sources.size() - 1);
        recover.sources(nextIndex, session.sources.size(), nextIndex >= 0 ? session.sources.get(nextIndex).shortQuality() : "");
        boolean wasVisible = recover.getVisibility() == View.VISIBLE;
        recover.setVisibility(View.VISIBLE); buffering.setVisibility(View.GONE);
        if (!wasVisible || !recover.hasFocus()) recover.choose.requestFocus();
    }
    private void showExhausted() {
        hideControls(); introActions.setVisibility(View.GONE);
        if (upNextShown) closeUpNext();
        recover.title.setText(Ui.tr("Nenhuma fonte reproduziu", "None of the sources played"));
        recover.line.setText(String.format(Ui.tr("%d tentadas. Escolha uma ou tente de novo mais tarde.", "Tried %d. Choose one, or try again later."), Math.max(1, session.sources.size())));
        recover.line.setVisibility(View.VISIBLE);
        recover.spinner.setVisibility(View.GONE); ((View) recover.dashes.getParent()).setVisibility(View.GONE);
        recover.setVisibility(View.VISIBLE); recover.choose.requestFocus();
        PlaybackLog.add(this, "No source played (" + session.sources.size() + " tried)");
        updateKeepAwake();
    }

    // --- Seeking (unchanged logic, new screen)

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
        if (!surface.getHolder().getSurface().isValid() || upNextShown) return;
        lastWarmPreview = position / 10000;
        final int source = generation;
        android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
        try { android.view.PixelCopy.request(surface, frame, result -> {
            if (result == android.view.PixelCopy.SUCCESS && started && !scrub.active && source == generation) preview.frame(position, frame);
            else frame.recycle();
        }, main); } catch (Exception ignored) { frame.recycle(); }
    }
    private boolean beginScrub() {
        if (player == null || !player.isCurrentMediaItemSeekable() || player.getDuration() <= 0 || upNextShown || stillShown) return false;
        if (!scrub.active) {
            scrub.begin(player.getCurrentPosition(), player.getDuration(), player.getPlayWhenReady());
            player.setPlayWhenReady(false); player.setScrubbingModeParameters(new androidx.media3.exoplayer.ScrubbingModeParameters.Builder()
                    .setDisabledTrackTypes(Collections.singleton(C.TRACK_TYPE_TEXT)).build());
            previewMovedPlayer = false; updatePauseIcon();
            hideControls(); introActions.setVisibility(View.GONE); scrubLayer.setVisibility(View.VISIBLE);
            int source = generation;
            android.graphics.Bitmap frame = preparedPreviews == null || !preparedPreviews.ready ? android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888) : null;
            try { if (frame != null) android.view.PixelCopy.request(surface, frame, result -> {
                if (result == android.view.PixelCopy.SUCCESS && started && scrub.active && source == generation) {
                    frozenVideo.setImageBitmap(frame); frozenVideo.setVisibility(View.VISIBLE);
                } else frame.recycle();
            }, main); } catch (Exception ignored) { if (frame != null) frame.recycle(); }
            subtitles.setVisibility(View.GONE);
            main.removeCallbacks(scrubTick); main.post(scrubTick);
        }
        return true;
    }
    private void updateScrub() {
        if (!scrub.active) return;
        previewRequest++; copyingPreview = false; previewCaptureActive = false; main.removeCallbacks(decodePreview);
        scrub.input(SystemClock.elapsedRealtime()); main.removeCallbacks(confirmSeek); main.postDelayed(confirmSeek, SeekState.AUTO_CONFIRM_MS);
        previewDirection = scrub.target < scrub.original ? -1 : 1;
        seek.seeking(scrub.original, scrub.target, scrub.duration);
        seek.setContentDescription(Ui.tr("Posição de reprodução ", "Playback position ") + SeekPreview.clock(scrub.target));
        scrubLayer.update(scrub.original, scrub.target, scrub.duration, 1, (int) (SeekState.AUTO_CONFIRM_MS / 1000));
        boolean available = preview.select(scrub.target);
        prepareNearbyPreviews(scrub.target, scrub.duration);
        if (!available && (preparedPreviews == null || !preparedPreviews.ready)) main.postDelayed(decodePreview, 35);
        else if (preparedPreviews == null) { int request = previewRequest; main.post(() -> prefetchPreview(request)); }
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
        boolean playing = scrub.playing; long from = scrub.original; long position = scrub.finish(confirmed);
        previewRequest++; previewCaptureActive = false; main.removeCallbacks(decodePreview); main.removeCallbacks(confirmSeek); main.removeCallbacks(scrubTick);
        scrubLayer.setVisibility(View.GONE); subtitles.setVisibility(View.VISIBLE);
        if (player != null) {
            player.setScrubbingModeEnabled(false);
            sourceOpenedAt = SystemClock.elapsedRealtime();
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT);
            if (confirmed || previewMovedPlayer) player.seekTo(position);
            wanted = playing; player.setPlayWhenReady(playing); bufferingSince = 0;
            updatePauseIcon();
            showControls(); seek.requestFocus();
            if (confirmed && position < from) replaySubtitles(from);
            int request = generation;
            main.postDelayed(() -> { if (request == generation && !scrub.active) { frozenVideo.setVisibility(View.GONE); frozenVideo.setImageDrawable(null); } }, 500);
            if (external != null) renderCues();
        }
    }

    // --- Controls

    private void updateClock() {
        String now = Ui.clock(System.currentTimeMillis());
        if (!now.contentEquals(overlay.clock.getText())) overlay.clock.setText(now);
    }
    private void updateTimes(long position, long duration) {
        if (overlay.getVisibility() != View.VISIBLE) return;
        overlay.elapsed.setText(SeekPreview.clock(position));
        overlay.remaining.setText(duration > 0 ? "−" + SeekPreview.clock(Math.max(0, duration - position)) : "");
        if (duration > 0) overlay.ends.setText(Ui.tr("Termina às ", "Ends at ") + Ui.clock(System.currentTimeMillis() + Math.max(0, duration - position)));
    }
    private void showControls() {
        if (scrub.active || upNextShown || stillShown || recover.getVisibility() == View.VISIBLE || timing.getVisibility() == View.VISIBLE) {
            hideAt = SystemClock.elapsedRealtime() + 4500; return;
        }
        boolean appeared = overlay.getVisibility() != View.VISIBLE;
        overlay.setVisibility(View.VISIBLE); hideAt = SystemClock.elapsedRealtime() + 4500;
        recapLabel.setVisibility(View.GONE);
        subtitles.setBottomPaddingFraction(0.26f);
        if (appeared) { updateClock(); if (player != null) updateTimes(Math.max(0, player.getCurrentPosition()), player.getDuration()); }
        if (getCurrentFocus() == null || appeared && !introActions.hasFocus()) pause.requestFocus();
    }
    private void hideControls() { overlay.setVisibility(View.GONE); subtitles.setBottomPaddingFraction(0.08f); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() == KeyEvent.ACTION_UP && key == swallowedKey) { swallowedKey = 0; return true; }
        if (down && event.getRepeatCount() == 0 && PlaybackMemory.autoplayChain(this) > 0 && !stillShown) PlaybackMemory.setAutoplayChain(this, 0);
        // Long press of the settings or menu button: quick settings.
        if (key == KeyEvent.KEYCODE_SETTINGS || key == KeyEvent.KEYCODE_MENU) {
            if (down && event.getRepeatCount() == 1) { QuickPanel.show(this); swallowedKey = key; return true; }
            if (down) return true;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (key == KeyEvent.KEYCODE_MENU) playbackOptions();
                else if (!QuickPanel.open(this, android.provider.Settings.ACTION_SETTINGS)) QuickPanel.show(this);
            }
            return true;
        }
        if (down && key == KeyEvent.KEYCODE_CAPTIONS) { chooseTracks(C.TRACK_TYPE_TEXT); return true; }
        if (down && key == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) { chooseTracks(C.TRACK_TYPE_AUDIO); return true; }
        if (down && key == KeyEvent.KEYCODE_MEDIA_NEXT) { if (nextEpisode != null) playNextEpisode(false); return true; }
        if (down && synced.getVisibility() == View.VISIBLE && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            undoSync(); swallowedKey = key; return true;
        }
        if (timing.getVisibility() == View.VISIBLE) {
            if (down && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) adjustTiming(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, event.getRepeatCount());
            else if (down && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK)) { closeTiming(); swallowedKey = key; }
            return true;
        }
        if (down && stillShown && key == KeyEvent.KEYCODE_BACK) { stillShown = false; stillWatching.setVisibility(View.GONE); cancelAutoNext(); showControls(); swallowedKey = key; return true; }
        if (down && upNextShown && key == KeyEvent.KEYCODE_BACK) { watchCredits(); swallowedKey = key; return true; }
        if (down && recover.getVisibility() == View.VISIBLE && key == KeyEvent.KEYCODE_BACK) { finish(); swallowedKey = key; return true; }
        if (down && introActions.getVisibility() == View.VISIBLE && introActions.hasFocus()) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event);
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
                showControls(); pause.requestFocus(); swallowedKey = key; return true;
            }
        }
        if (down && !choosing && scrub.active) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                scrub.move(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, event.getRepeatCount()); updateScrub(); swallowedKey = key; return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                finishScrub(key != KeyEvent.KEYCODE_BACK); swallowedKey = key; return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) { finishScrub(false); pause.requestFocus(); swallowedKey = key; return true; }
        }
        if (down && !choosing) {
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || player != null && player.getPlayWhenReady() != (key == KeyEvent.KEYCODE_MEDIA_PLAY)) togglePause();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                // The first Back hides the controls while the video plays; the next one returns to the episodes.
                if (overlay.getVisibility() == View.VISIBLE && sourceValidated && !playbackEnded && player != null) hideControls();
                else finish();
                swallowedKey = key; return true;
            }
            boolean overlayShown = overlay.getVisibility() == View.VISIBLE;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                boolean media = key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                if ((!overlayShown && !introActions.hasFocus() || seek.hasFocus() || media) && !upNextShown && !stillShown
                        && recover.getVisibility() != View.VISIBLE && beginScrub()) {
                    scrub.move(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, event.getRepeatCount());
                    updateScrub(); swallowedKey = key; return true;
                }
                hideAt = SystemClock.elapsedRealtime() + 4500;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!overlayShown && !upNextShown && !stillShown && recover.getVisibility() != View.VISIBLE && !introActions.hasFocus()) {
                    showControls(); pause.requestFocus(); swallowedKey = key; return true;
                }
                hideAt = SystemClock.elapsedRealtime() + 4500;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
