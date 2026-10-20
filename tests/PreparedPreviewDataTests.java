package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Bitmap;
import java.io.File;
import java.util.*;

/** Check random-read integrity and actual preview colors at different video positions. */
public class PreparedPreviewDataTests extends TvTest {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); PreparedPreviews prepared = null; PreviewReader reader = null;
        File binary = new File(getTargetContext().getCacheDir(), "preview-random.bin"), video = new File(getTargetContext().getCacheDir(), "preview-colors.mkv");
        List<Bitmap> images = new ArrayList<>();
        try {
            byte[] expected = new byte[700003]; new Random(97).nextBytes(expected); java.nio.file.Files.write(binary.toPath(), expected);
            AddonStreams.Source bytes = new AddonStreams.Source(); bytes.url = android.net.Uri.fromFile(binary).toString(); reader = new PreviewReader(getTargetContext(), bytes);
            check(reader.getSize() == expected.length, "random reader reports exact file size");
            for (int offset : new int[]{0, 131070, 262140, 655000, 19, 699990, 131072, 600000}) {
                int wanted = Math.min(300001, expected.length - offset), total = 0; byte[] actual = new byte[wanted];
                while (total < wanted) { int count = reader.readAt(offset + total, actual, total, wanted - total); if (count <= 0) throw new AssertionError("Early end of random read"); total += count; }
                check(java.util.Arrays.equals(actual, java.util.Arrays.copyOfRange(expected, offset, offset + wanted)), "random reads remain correct across cached page boundaries");
            }
            check(reader.readAt(expected.length, new byte[1], 0, 1) == -1, "reader handles end of file");
            reader.stopped = true; boolean cancelled = false; try { reader.readAt(0, new byte[1], 0, 1); } catch (java.io.IOException correct) { cancelled = true; }
            check(cancelled, "cancelled reader stops new reads"); reader.close(); reader = null;
            PreviewReader.Budget budget = new PreviewReader.Budget(); budget.transferred(17 * 1024 * 1024);
            check(budget.exceeded(SystemClock.elapsedRealtime()), "network budget rejects excess reads");
            boolean bounded = false; try { new PreviewReader(getTargetContext(), bytes, budget); } catch (java.io.IOException correct) { bounded = true; }
            check(bounded, "reader replacement retains the network budget");
            budget.started -= 60001;
            check(!budget.exceeded(SystemClock.elapsedRealtime()), "network budget permits a new time window");
            try (java.io.InputStream input = getContext().getAssets().open("preview-colors.mkv")) { java.nio.file.Files.copy(input, video.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            AddonStreams.Source source = new AddonStreams.Source(); source.url = android.net.Uri.fromFile(video).toString();
            Map<Long, Bitmap> frames = new java.util.concurrent.ConcurrentHashMap<>();
            prepared = new PreparedPreviews(getTargetContext(), source, 30000, new android.os.Handler(android.os.Looper.getMainLooper()), frames::put);
            for (long position : new long[]{0,10000,20000}) {
                long end = SystemClock.elapsedRealtime() + 20000;
                while (!frames.containsKey(position) && SystemClock.elapsedRealtime() < end) { prepared.request(position); SystemClock.sleep(100); }
                Bitmap image = frames.get(position); check(image != null, "thumbnail is prepared for each color interval"); images.add(image);
                int color = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
                int r = android.graphics.Color.red(color), g = android.graphics.Color.green(color), b = android.graphics.Color.blue(color);
                check(position == 0 ? r > g + 70 && r > b + 70 : position == 10000 ? g > r + 60 && g > b + 60 : b > r + 70 && b > g + 70, "prepared image matches the requested video interval");
                check(image.getWidth() <= 320 && image.getHeight() <= 240, "thumbnail dimensions remain bounded");
            }
            prepared.close(); SystemClock.sleep(300);
            Map<Long, Bitmap> cached = new java.util.concurrent.ConcurrentHashMap<>();
            prepared = new PreparedPreviews(getTargetContext(), source, 30000, new android.os.Handler(android.os.Looper.getMainLooper()), cached::put);
            prepared.request(10000); long end = SystemClock.elapsedRealtime() + 3000;
            while (!cached.containsKey(10000L) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(5);
            check(cached.containsKey(10000L) && prepared.diskHits == 1 && prepared.prepared == 0, "saved thumbnail loads without video decoding");
            images.addAll(cached.values()); check(prepared.networkBytes == 0, "local cached previews require no network bytes");
            prepared.close(); SystemClock.sleep(300);
            source.headers.put("X-Preview-Test", "separate-source");
            Map<Long, Bitmap> isolated = new java.util.concurrent.ConcurrentHashMap<>();
            prepared = new PreparedPreviews(getTargetContext(), source, 30000, new android.os.Handler(android.os.Looper.getMainLooper()), isolated::put);
            prepared.request(10000); end = SystemClock.elapsedRealtime() + 10000;
            while (!isolated.containsKey(10000L) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(5);
            images.addAll(isolated.values());
            check(isolated.containsKey(10000L) && prepared.prepared == 1 && prepared.diskHits == 0, "source credentials have separate preview cache identities");
            prepared.close(); SystemClock.sleep(300);
            Map<Long, Bitmap> cancelledFrames = new java.util.concurrent.ConcurrentHashMap<>();
            prepared = new PreparedPreviews(getTargetContext(), source, 30000, new android.os.Handler(android.os.Looper.getMainLooper()), cancelledFrames::put);
            prepared.request(10000); prepared.close(); SystemClock.sleep(500);
            check(cancelledFrames.isEmpty(), "source cancellation prevents a late preview callback");
            result.putString("stream", "Passed " + checks + " prepared preview data checks.\n");
        } catch (Throwable error) { result.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"); }
        finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {} if (prepared != null) prepared.close();
            SystemClock.sleep(500); for (Bitmap image : images) if (!image.isRecycled()) image.recycle();
            String identity = MediaCache.digest(android.net.Uri.fromFile(video).toString()); File[] files = new File(getTargetContext().getCacheDir(), "seek-previews").listFiles();
            if (files != null) for (File file : files) if (file.getName().startsWith(identity + "_") || file.getName().startsWith(MediaCache.digest(android.net.Uri.fromFile(video).toString() + "\nX-Preview-Test:separate-source") + "_")) file.delete(); binary.delete(); video.delete();
            finish(result.getString("stream", "").startsWith("Passed") ? -1 : 0, result);
        }
    }
}
