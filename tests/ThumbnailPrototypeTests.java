package io.freemytv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.media.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import org.json.*;

/** Measure software thumbnail extraction while the main decoder plays an actual episode. */
public class ThumbnailPrototypeTests extends Instrumentation {
    private SourcesActivity title;
    private PlayerActivity activity;
    private Object value(String name) throws Exception { Field f = PlayerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private ExoPlayer player() throws Exception { return (ExoPlayer) value("player"); }
    static final class Reader extends android.media.MediaDataSource {
        final DataSource.Factory factory; final String uri; long size = -1, cursor = -1, bytes; DataSource input;
        final long until = SystemClock.elapsedRealtime() + 45000;
        Reader(android.content.Context context, AddonStreams.Source source) throws Exception {
            uri = source.url;
            factory = MediaCache.factory(context, new DefaultDataSource.Factory(context, new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(3000).setReadTimeoutMs(3000).setDefaultRequestProperties(source.headers)), source);
            open(0);
        }
        private void open(long position) throws java.io.IOException {
            if (input != null) input.close(); input = factory.createDataSource();
            long length = input.open(new DataSpec.Builder().setUri(uri).setPosition(position).build()); cursor = position;
            if (position == 0 && length >= 0) size = length;
        }
        private final android.util.LruCache<Long, byte[]> pages = new android.util.LruCache<>(12);
        @Override public synchronized int readAt(long position, byte[] data, int offset, int length) throws java.io.IOException {
            if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > until || bytes > 24 * 1024 * 1024) throw new java.io.IOException("preview_budget");
            if (length == 0) return 0; if (size >= 0 && position >= size) return -1;
            long page = position / 131072 * 131072;
            byte[] block = pages.get(page);
            if (block == null) {
                if (cursor != page) open(page);
                int wanted = size < 0 ? 131072 : (int) Math.min(131072, size - page); block = new byte[wanted]; int total = 0;
                while (total < wanted) { int count = input.read(block, total, wanted - total); if (count < 0) break; total += count; cursor += count; bytes += count; }
                if (total <= 0) return -1;
                if (total < wanted) block = java.util.Arrays.copyOf(block, total); pages.put(page, block);
            }
            int from = (int) (position - page), count = Math.min(length, block.length - from); if (count <= 0) return -1;
            System.arraycopy(block, from, data, offset, count); return count;
        }
        @Override public synchronized long getSize() { return size; }
        @Override public synchronized void close() throws java.io.IOException { if (input != null) { input.close(); input = null; } }
    }
    static Bitmap bitmap(Image image) {
        android.graphics.Rect crop = image.getCropRect(); int width = 320, height = Math.max(1, 320 * crop.height() / crop.width());
        int[] pixels = new int[width * height]; Image.Plane[] planes = image.getPlanes();
        java.nio.ByteBuffer yb = planes[0].getBuffer(), ub = planes[1].getBuffer(), vb = planes[2].getBuffer();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx = crop.left + x * crop.width() / width, sy = crop.top + y * crop.height() / height;
            int l = (yb.get(sy * planes[0].getRowStride() + sx * planes[0].getPixelStride()) & 255) - 16;
            int u = (ub.get(sy / 2 * planes[1].getRowStride() + sx / 2 * planes[1].getPixelStride()) & 255) - 128;
            int v = (vb.get(sy / 2 * planes[2].getRowStride() + sx / 2 * planes[2].getPixelStride()) & 255) - 128;
            int r = Math.max(0, Math.min(255, (298 * l + 409 * v + 128) >> 8));
            int g = Math.max(0, Math.min(255, (298 * l - 100 * u - 208 * v + 128) >> 8));
            int b = Math.max(0, Math.min(255, (298 * l + 516 * u + 128) >> 8));
            pixels[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    private void awaitReady() throws Exception {
        long end = SystemClock.elapsedRealtime() + 65000;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] good = {false}; runOnMainSync(() -> { try { good[0] = player() != null && player().isPlaying() && (Boolean) value("sourceValidated"); } catch (Exception e) { throw new RuntimeException(e); } });
            if (good[0]) return; SystemClock.sleep(100);
        } throw new AssertionError("No verified playback");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); MediaExtractor extractor = null; MediaCodec decoder = null; Reader reader = null;
        try {
            getUiAutomation(); WatchSnapshot.capture(getTargetContext());
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            title = (SourcesActivity) startActivitySync(new Intent(getTargetContext(), SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("title_id", 1418).putExtra("title_kind", "tv").putExtra("title_name", "The Big Bang Theory").putExtra("imdb", "tt0898266")
                    .putExtra("resume_video", "tt0898266:9:22").putExtra("resume_position", 0L).putExtra("resume_duration", 1200000L).putExtra("auto_play", true));
            activity = (PlayerActivity) waitForMonitorWithTimeout(monitor, 25000); removeMonitor(monitor); if (activity == null) throw new AssertionError("No player"); awaitReady();
            AddonStreams.Source source = PlaybackSession.current.sources.get(PlaybackSession.current.index);
            long setup = SystemClock.elapsedRealtime(); reader = new Reader(getTargetContext(), source);
            extractor = new MediaExtractor(); extractor.setDataSource(reader); MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) { MediaFormat f = extractor.getTrackFormat(i); if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) { extractor.selectTrack(i); format = f; break; } }
            if (format == null) throw new AssertionError("No video track");
            String codecName = null; JSONArray choices = new JSONArray();
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) if (!info.isEncoder() && info.isSoftwareOnly())
                for (String type : info.getSupportedTypes()) if (type.equals(format.getString(MediaFormat.KEY_MIME))) { choices.put(info.getName()); if (codecName == null) codecName = info.getName(); }
            if (codecName == null) throw new AssertionError("No software decoder");
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger("android._num-input-buffers", 1); format.setInteger("android._num-output-buffers", 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 1);
            decoder = MediaCodec.createByCodecName(codecName); decoder.configure(format, null, null, 0); decoder.start();
            JSONObject report = new JSONObject().put("codec", codecName).put("choices", choices).put("width", format.getInteger(MediaFormat.KEY_WIDTH)).put("height", format.getInteger(MediaFormat.KEY_HEIGHT)).put("setup_ms", SystemClock.elapsedRealtime() - setup);
            Bundle setupStatus = new Bundle(); setupStatus.putString("stream", "Extractor setup: " + report + "\n"); sendStatus(0, setupStatus);
            JSONArray frames = new JSONArray(); report.put("frames", frames);
            for (long position : new long[]{10000, 20000, 30000, 40000}) {
                long start = SystemClock.elapsedRealtime(); extractor.seekTo(position * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC); decoder.flush();
                boolean eos = false, done = false; MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); long deadline = start + 10000;
                while (!done && SystemClock.elapsedRealtime() < deadline) {
                    if (!eos) { int index = decoder.dequeueInputBuffer(10000); if (index >= 0) {
                        int count = extractor.readSampleData(decoder.getInputBuffer(index), 0);
                        long stamp = count < 0 ? 0 : extractor.getSampleTime();
                        eos = true; decoder.queueInputBuffer(index, 0, Math.max(0, count), stamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } }
                    int index = decoder.dequeueOutputBuffer(info, 10000);
                    if (index >= 0) { Image image = decoder.getOutputImage(index);
                        if (image != null) { Bitmap frame = bitmap(image); image.close(); frame.recycle(); frames.put(new JSONObject().put("target_ms", position).put("frame_ms", info.presentationTimeUs / 1000).put("elapsed_ms", SystemClock.elapsedRealtime() - start)); done = true; }
                        if (image != null && !done) image.close(); decoder.releaseOutputBuffer(index, false);
                    }
                }
                if (!done) throw new AssertionError("No thumbnail output");
            }
            report.put("read_bytes", reader.bytes);
            boolean[] healthy = {false}; runOnMainSync(() -> { try { healthy[0] = player().isPlaying() && (Boolean) value("sourceValidated"); } catch (Exception e) { throw new RuntimeException(e); } });
            report.put("playback_healthy", healthy[0]);
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "thumbnail-prototype.json"))) { file.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            output.putString("stream", "Prototype: " + report + "\n");
        } catch (Throwable error) { output.putString("stream", "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            if (decoder != null) try { decoder.release(); } catch (Exception ignored) {}
            if (extractor != null) extractor.release(); if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            runOnMainSync(() -> { if (activity != null) activity.finish(); if (title != null) title.finish(); }); waitForIdleSync(); SystemClock.sleep(700);
            try { WatchSnapshot.restore(getTargetContext()); } catch (Exception ignored) {}
            finish(output.getString("stream", "").startsWith("Prototype:") ? -1 : 0, output);
        }
    }
}
