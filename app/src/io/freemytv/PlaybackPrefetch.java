package io.freemytv;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Gets a video ready before the viewer asks for it: asks the add-ons for sources, checks the first ones, loads the
 * add-on subtitle list and reads the first ~1 MB of the likely first source into the video cache. Everything lands in
 * the caches the normal start already reads (AddonStreams, SourceProbe, AddonSubtitles, MediaCache), so pressing
 * Resume, or the next episode starting, skips the waiting. Used by the home Resume chip (warm) and by the player ~2
 * minutes before an episode ends (next). Discarded when the viewer doesn't continue.
 */
final class PlaybackPrefetch {
    private PlaybackPrefetch() {}

    static final long FIRST_BYTES = 1024 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static Future<?> pending;
    private static String pendingVideo = "";
    private static long pendingAt;
    /** The source whose first bytes were cached for a video, so they can be dropped if unused. */
    private static String preparedVideo = "";
    private static AddonStreams.Source preparedSource;

    /** Warm the sources for a Continue Watching entry (the home calls this when the Resume chip gets focus). */
    static void warm(Context context, ContinueWatching.Entry entry) {
        if (context == null || entry == null || entry.external() || !entry.video.matches("tt[0-9]+(?::[0-9]+:[0-9]+)?")) return;
        start(context.getApplicationContext(), entry.video, "tv".equals(entry.kind) || entry.video.contains(":"));
    }

    /** Prepare the next episode while this one ends. */
    static void next(Context context, String video) {
        if (context == null || video == null || !video.matches("tt[0-9]+:[0-9]+:[0-9]+")) return;
        start(context.getApplicationContext(), video, true);
    }

    /** True while a prefetch for this video is running or finished in the last few minutes. */
    static synchronized boolean prepared(String video) {
        return video.equals(pendingVideo) && android.os.SystemClock.elapsedRealtime() - pendingAt < 10 * 60000L;
    }

    private static synchronized void start(Context app, String video, boolean series) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (video.equals(pendingVideo) && now - pendingAt < 5 * 60000L) return;
        if (pending != null && !pending.isDone()) pending.cancel(true);
        pendingVideo = video; pendingAt = now;
        pending = worker.submit(() -> prepare(app, video, series));
    }

    /** Drops a prefetch the viewer didn't use (the cached bytes and the remembered add-on answers). */
    static void discard(Context context, String video) {
        AddonStreams.Source source;
        synchronized (PlaybackPrefetch.class) {
            if (video == null || !video.equals(pendingVideo)) return;
            if (pending != null && !pending.isDone()) pending.cancel(true);
            pendingVideo = ""; pending = null;
            source = video.equals(preparedVideo) ? preparedSource : null;
            preparedVideo = ""; preparedSource = null;
        }
        final AddonStreams.Source drop = source;
        final Context app = context.getApplicationContext();
        worker.execute(() -> { AddonStreams.forget(video); if (drop != null) { MediaCache.remove(app, drop); SourceProbe.forget(drop.url); } });
    }

    private static void prepare(Context app, String video, boolean series) {
        long started = android.os.SystemClock.elapsedRealtime();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            PlaybackMemory.init(app);
            String type = series ? "series" : "movie";
            List<AddonStreams.Addon> addons = AddonStreams.addons(StremioAccount.addons(app), type, video);
            List<Future<AddonStreams.Result>> answers = new ArrayList<>();
            for (AddonStreams.Addon addon : addons) answers.add(pool.submit(() -> AddonStreams.prefetch(addon, type, video)));
            List<AddonStreams.Source> found = new ArrayList<>();
            for (Future<AddonStreams.Result> answer : answers) {
                long left = 15000 - (android.os.SystemClock.elapsedRealtime() - started);
                if (left <= 0 || Thread.currentThread().isInterrupted()) break;
                try { found.addAll(answer.get(left, TimeUnit.MILLISECONDS).sources); } catch (Exception ignored) {}
            }
            List<AddonStreams.Source> plan = PlaybackPlan.automatic(found);
            AddonStreams.Source first = null; int checked = 0;
            for (AddonStreams.Source source : plan) {
                if (Thread.currentThread().isInterrupted() || checked >= 3) break;
                checked++;
                int state = SourceProbe.check(source);
                if (state == SourceProbe.AVAILABLE) { first = source; break; }
            }
            final AddonStreams.Source subtitleSource = first != null ? first : plan.isEmpty() ? null : plan.get(0);
            pool.submit(() -> { try { AddonSubtitles.load(app, video, subtitleSource); } catch (Exception ignored) {} });
            if (first != null && !Thread.currentThread().isInterrupted()) {
                synchronized (PlaybackPrefetch.class) { preparedVideo = video; preparedSource = first; }
                MediaCache.prefetch(app, first, FIRST_BYTES);
            }
            PlaybackLog.add(app, "Prepared " + found.size() + " sources for the next video" + (first != null ? " (" + first.shortQuality() + ", " + first.addon + ")" : ""));
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally { pool.shutdownNow(); }
    }
}
