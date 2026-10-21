package io.freemytv;

import android.content.Context;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Check bounded media headers without allocating playback decoders. */
final class ParallelSourceChecks implements AutoCloseable {
    static final int WORKERS = 4;
    static final long TIMEOUT_MS = 6500;
    static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> results = new java.util.concurrent.ConcurrentHashMap<>();
    interface Inspector { Result inspect(AddonStreams.Source source, long expected); }
    static final class Result {
        final int state;
        final String reason;
        Result(int state, String reason) { this.state = state; this.reason = reason; }
    }
    private final Context context;
    private final Inspector inspector;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final Map<String, Future<Result>> checks = new LinkedHashMap<>();
    ParallelSourceChecks(Context context) { this.context = context.getApplicationContext(); inspector = (source, expected) -> inspect(this.context, source, expected); }
    ParallelSourceChecks(Inspector inspector) { this.context = null; this.inspector = inspector; }
    Future<Result> prepare(AddonStreams.Source source, long expected) {
        String key = MediaCache.key(source) + ':' + expected;
        Future<Result> saved = checks.get(key);
        if (saved != null) return saved;
        Future<Result> task = workers.submit(() -> {
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            try {
                Result result = inspector.inspect(source, expected);
                results.computeIfAbsent(result.reason, keyName -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                return result;
            } finally { active.decrementAndGet(); }
        });
        checks.put(key, task);
        if (checks.size() > 48) {
            java.util.Iterator<Map.Entry<String, Future<Result>>> items = checks.entrySet().iterator();
            while (items.hasNext() && checks.size() > 48) {
                Map.Entry<String, Future<Result>> item = items.next();
                if (item.getValue().isDone()) items.remove();
            }
        }
        return task;
    }
    static boolean ready(Future<Result> task) {
        if (task == null || !task.isDone()) return false;
        try { return task.get().state == SourceProbe.AVAILABLE; } catch (Exception ignored) { return false; }
    }
    boolean ready(AddonStreams.Source source, long expected) { return ready(checks.get(MediaCache.key(source) + ':' + expected)); }
    void cancelPending() {
        java.util.Iterator<Future<Result>> tasks = checks.values().iterator();
        while (tasks.hasNext()) {
            Future<Result> task = tasks.next();
            if (!task.isDone()) { task.cancel(true); tasks.remove(); }
        }
    }
    static Result inspect(Context context, AddonStreams.Source source, long expected) {
        if (source.unsupported != null) return new Result(SourceProbe.FAILED, "codec");
        if (!MediaHttp.https(source.url)) return new Result(SourceProbe.UNCERTAIN, "local");
        return inspectMedia(context, source, expected);
    }
    static Result inspectMedia(Context context, AddonStreams.Source source, long expected) {
        PreviewReader reader = null;
        try {
            reader = new PreviewReader(context, source, new PreviewReader.Budget(2L * 1024 * 1024, 128L * 1024 * 1024));
            reader.deadline = SystemClock.elapsedRealtime() + 4500;
            byte[] prefix = new byte[512]; int length = reader.readAt(0, prefix, 0, prefix.length);
            if (length <= 0) return new Result(SourceProbe.FAILED, "response");
            String text = new String(prefix, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1).trim().toLowerCase(java.util.Locale.ROOT);
            if (text.startsWith("<html") || text.startsWith("<!doctype html") || text.startsWith("<error")
                    || text.startsWith("{\"") || text.startsWith("torrent download failed")) return new Result(SourceProbe.FAILED, "response");
            SourceHeader header = SourceHeader.read(reader);
            reader.verify();
            if (PlaybackValidation.shortPlaceholder(expected, header.duration)) return new Result(SourceProbe.FAILED, "short");
            boolean video = false, audio = false, supportedVideo = false, supportedAudio = false, possibleVideo = false, possibleAudio = false;
            for (androidx.media3.common.Format track : header.formats) {
                String type = track.sampleMimeType;
                if (type == null) continue;
                if (type.startsWith("video/")) { video = true; supportedVideo |= supported(track, false); possibleVideo |= supported(track, true); }
                if (type.startsWith("audio/")) { audio = true; supportedAudio |= supported(track, false); possibleAudio |= supported(track, true); }
            }
            if (!video || !audio) return new Result(SourceProbe.FAILED, !video ? "picture" : "silent");
            if (!possibleVideo || !possibleAudio) return new Result(SourceProbe.FAILED, "codec");
            if (!supportedVideo || !supportedAudio) return new Result(SourceProbe.UNCERTAIN, "codec_limits");
            return new Result(SourceProbe.AVAILABLE, "media");
        } catch (Exception error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                        && ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode != 416
                        || cause instanceof androidx.media3.datasource.HttpDataSource.InvalidContentTypeException)
                    return new Result(SourceProbe.FAILED, "response");
            }
            return new Result(SourceProbe.UNCERTAIN, reader != null && !reader.reason.isEmpty() ? reader.reason : error.getClass().getSimpleName());
        }
        finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
    }
    private static boolean supported(androidx.media3.common.Format format, boolean allowExceeds) throws Exception {
        if (androidx.media3.common.MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) return true;
        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoder : androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfosSoftMatch(
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT, format, false, false))
            if (allowExceeds ? decoder.isFormatFunctionallySupported(format) : decoder.isFormatSupported(format)) return true;
        return false;
    }
    @Override public void close() {
        for (Future<Result> task : checks.values()) task.cancel(true);
        workers.shutdownNow(); checks.clear();
    }
}
