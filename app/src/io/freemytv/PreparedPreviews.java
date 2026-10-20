package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Prepare small preview images while the hardware decoder continues normal playback. */
final class PreparedPreviews implements AutoCloseable {
    interface Result { void frame(long position, Bitmap bitmap); }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run();
    }, "seek-previews"));
    private final Context context;
    private final AddonStreams.Source source;
    private final Handler main;
    private final Result result;
    private final File directory;
    private final String identity;
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Map<Long, Long> missing = new java.util.HashMap<>();
    private MediaExtractor extractor;
    private volatile PreviewReader reader;
    private MediaFormat format;
    private AvcPreview decoder;
    private String codec;
    private volatile boolean closed, unsupported;
    volatile boolean ready;
    volatile String state = "waiting";
    volatile int prepared, diskHits;
    volatile long networkBytes, readBytes, retryAt;
    private long completedNetwork, completedReads;
    private final long duration;
    private long lastCollection;
    private final PreviewReader.Budget budget = new PreviewReader.Budget();
    PreparedPreviews(Context context, AddonStreams.Source source, long duration, Handler main, Result result) {
        this.context = context.getApplicationContext(); this.source = source; this.duration = duration; this.main = main; this.result = result;
        directory = new File(context.getCacheDir(), "seek-previews"); directory.mkdirs();
        StringBuilder key = new StringBuilder(source.url);
        for (java.util.Map.Entry<String, String> entry : new java.util.TreeMap<>(source.headers).entrySet()) key.append('\n').append(entry.getKey()).append(':').append(entry.getValue());
        identity = MediaCache.digest(key.toString());
    }
    boolean blocked(long position) {
        Long failed = missing.get(SeekPreview.bucket(position)); long now = SystemClock.elapsedRealtime();
        return unsupported || now < retryAt || failed != null && now - failed < 30000;
    }
    boolean request(long position) {
        long key = SeekPreview.bucket(position), now = SystemClock.elapsedRealtime();
        if (closed || unsupported || now < retryAt || position < 0 || position >= duration - 1000) return true;
        Long failed = missing.get(key); if (failed != null && now - failed < 30000) return false;
        if (!busy.compareAndSet(false, true)) return true;
        WORK.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (closed) return;
                bitmap = read(key);
                if (bitmap != null) { diskHits++; ready = true; state = "ready"; }
                else {
                    android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
                    ((android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
                    if (!memoryAvailable(info.availMem) && SystemClock.elapsedRealtime() - lastCollection >= 10000) {
                        lastCollection = SystemClock.elapsedRealtime(); Runtime.getRuntime().gc();
                        ((android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
                    }
                    if (!memoryAvailable(info.availMem)) { state = "memory_pause"; retryAt = SystemClock.elapsedRealtime() + 2000; return; }
                    if (extractor == null) initialize();
                    if (closed) return;
                    bitmap = decode(key); reader.verify();
                    if (bitmap != null) { prepared++; write(key, bitmap); }
                }
                if (reader != null) { networkBytes = completedNetwork + reader.networkBytes; readBytes = completedReads + reader.readBytes; }
                if (bitmap == null) { main.post(() -> missing.put(key, SystemClock.elapsedRealtime())); return; }
                final Bitmap frame = bitmap; bitmap = null;
                main.post(() -> { if (closed) frame.recycle(); else result.frame(key, frame); });
            } catch (Exception | AssertionError error) {
                if (reader != null) { networkBytes = completedNetwork + reader.networkBytes; readBytes = completedReads + reader.readBytes; }
                String reason = reader == null ? "" : reader.reason;
                if (reason.equals("memory") || reason.equals("network_budget") || reason.equals("deadline")) {
                    state = reason + "_pause"; retryAt = SystemClock.elapsedRealtime() + (reason.equals("network_budget") ? 60000 : 5000);
                } else { state = "unsupported"; unsupported = true; }
                ready = false; release();
            } finally { if (bitmap != null) bitmap.recycle(); busy.set(false); }
        });
        return true;
    }
    private boolean memoryAvailable(long available) {
        return available >= (extractor == null ? 480L : 470L) * 1024 * 1024
                || decoder != null && available >= 450L * 1024 * 1024 && Runtime.getRuntime().freeMemory() >= 12L * 1024 * 1024;
    }
    private void initialize() throws Exception {
        state = "initializing"; format = null; codec = null; reader = new PreviewReader(context, source, budget); extractor = new MediaExtractor(); extractor.setDataSource(reader);
        if (extractor.getDrmInitData() != null) throw new java.io.IOException("protected_media");
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat item = extractor.getTrackFormat(i); String mime = item.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { format = item; extractor.selectTrack(i); break; }
        }
        if (format == null || format.getInteger(MediaFormat.KEY_WIDTH) * (long) format.getInteger(MediaFormat.KEY_HEIGHT) > 1920L * 1080
                || format.containsKey(MediaFormat.KEY_DURATION) && Math.abs(format.getLong(MediaFormat.KEY_DURATION) / 1000 - duration) > 3000)
            throw new java.io.IOException("preview_format");
        if (!"video/avc".equals(format.getString(MediaFormat.KEY_MIME))) throw new java.io.IOException("preview_codec");
        decoder = new AvcPreview(format); codec = "jcodec-avc"; ready = true; state = "ready";
    }
    private Bitmap decode(long position) throws Exception {
        reader.deadline = SystemClock.elapsedRealtime() + 6000; extractor.seekTo(position * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        long stamp = extractor.getSampleTime(); if (stamp < 0) return null;
        java.nio.ByteBuffer sample;
        long distanceFromCue = Math.abs(stamp / 1000 - position), size = reader.getSize();
        boolean smallScan = size > 0 && size / Math.max(1, duration / 1000) * ((distanceFromCue + 6000) / 1000 + 1) <= 512 * 1024;
        if (distanceFromCue <= 10000 && !(distanceFromCue > 6000 && smallScan)) sample = sample();
        else {
            // Some Matroska extractors seek to a cluster instead of its nearest keyframe.
            sample = null; long closest = 10001;
            for (int i = 0; i < 512 && !closed; i++) {
                stamp = extractor.getSampleTime(); long time = stamp / 1000;
                if (stamp < 0 || time < position - 20000 || time > position + 10000) break;
                boolean sync = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                long distance = Math.abs(time - position);
                if (sync && distance < closest) { sample = sample(); closest = distance; }
                if (sync && time >= position || !extractor.advance()) break;
            }
        }
        if (closed || sample == null) return null;
        return decoder.decode(sample);
    }
    private java.nio.ByteBuffer sample() throws Exception {
        long size = extractor.getSampleSize(); if (size <= 0 || size > 2 * 1024 * 1024) throw new java.io.IOException("preview_sample");
        java.nio.ByteBuffer sample = java.nio.ByteBuffer.allocate((int) size);
        int count = extractor.readSampleData(sample, 0); if (count <= 0) return null;
        sample.position(0); sample.limit(count); return sample;
    }
    private File file(long position) { return new File(directory, identity + "_" + position + ".jpg"); }
    private Bitmap read(long position) {
        File item = file(position); if (!item.isFile() || item.length() > 256 * 1024) return null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(item.getPath(), bounds); if (bounds.outWidth > 320 || bounds.outHeight > 240) return null;
        Bitmap frame = android.graphics.BitmapFactory.decodeFile(item.getPath()); if (frame != null) item.setLastModified(System.currentTimeMillis()); return frame;
    }
    private void write(long position, Bitmap bitmap) {
        File item = file(position), part = new File(item.getPath() + ".part");
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(part)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output); }
            if (!part.renameTo(item)) return;
            File[] files = directory.listFiles(); if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified())); long bytes = 0;
            for (int i = 0; i < files.length; i++) { bytes += files[i].length(); if (i >= 768 || bytes > 16L * 1024 * 1024) files[i].delete(); }
        } catch (Exception ignored) {} finally { part.delete(); }
    }
    private void release() {
        decoder = null;
        if (extractor != null) { extractor.release(); extractor = null; }
        if (reader != null) { completedNetwork += reader.networkBytes; completedReads += reader.readBytes; networkBytes = completedNetwork; readBytes = completedReads; try { reader.close(); } catch (Exception ignored) {} reader = null; }
        ready = false;
    }
    @Override public void close() {
        closed = true; state = "closed"; if (reader != null) reader.stopped = true;
        WORK.execute(this::release);
    }
}
