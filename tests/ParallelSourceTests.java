package io.freemytv;

import android.os.Bundle;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Verify media checks and the worker limit without playback decoders. */
public class ParallelSourceTests extends TvTest {
    private int checks;
    private void check(boolean pass, String name) { if (!pass) throw new AssertionError(name); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private AddonStreams.Source source(String url) { AddonStreams.Source s = new AddonStreams.Source(); s.url = url; return s; }
    @Override public void onStart() {
        Bundle output = new Bundle(); ParallelSourceChecks pool = null;
        try {
            File folder = new File(getTargetContext().getCacheDir(), "media-check-fixtures"); folder.mkdirs();
            for (String name : new String[]{"two-minute-error.mkv", "verified.mkv", "audio-only.mkv", "video-only.mkv"}) {
                File target = new File(folder, name);
                try (java.io.InputStream in = getContext().getAssets().open(name); java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = in.read(bytes)) >= 0) out.write(bytes, 0, count);
                }
                AddonStreams.Source media = source(android.net.Uri.fromFile(target).toString());
                ParallelSourceChecks.Result result = ParallelSourceChecks.inspectMedia(getTargetContext(), media, 1140000);
                if (name.equals("verified.mkv")) check(result.state == SourceProbe.AVAILABLE, "a full video with supported audio passes metadata checks");
                else check(result.state == SourceProbe.FAILED, "invalid media is rejected: " + name + " (" + result.reason + ")");
                if (name.equals("two-minute-error.mkv")) check(result.reason.equals("short"), "the reported error duration is rejected before playback");
            }
            byte[] original = java.nio.file.Files.readAllBytes(new File(folder, "verified.mkv").toPath());
            int cluster = -1, segment = -1;
            for (int i = 0; i + 4 < original.length; i++) {
                if (original[i] == 0x18 && original[i + 1] == 0x53 && original[i + 2] == (byte) 0x80 && original[i + 3] == 0x67) segment = i;
                if (original[i] == 0x1f && original[i + 1] == 0x43 && original[i + 2] == (byte) 0xb6 && original[i + 3] == 0x75) { cluster = i; break; }
            }
            check(segment >= 0 && cluster > segment, "the padding fixture has a Matroska segment and cluster");
            int sizeAt = segment + 4, width = 1;
            while (width < 8 && (original[sizeAt] & (0x80 >> (width - 1))) == 0) width++;
            original[sizeAt] = (byte) ((1 << (9 - width)) - 1);
            for (int i = 1; i < width; i++) original[sizeAt + i] = (byte) 0xff;
            File padded = new File(folder, "padded-header.mkv");
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(padded)) {
                file.write(original, 0, cluster); file.write(new byte[]{(byte) 0xec, 0x10, 0x40, 0, 0});
                file.write(new byte[4 * 1024 * 1024]); file.write(original, cluster, original.length - cluster);
            }
            try (PreviewReader reader = new PreviewReader(getTargetContext(), source(android.net.Uri.fromFile(padded).toString()))) {
                SourceHeader header = SourceHeader.read(reader);
                check(header.duration > 600000 && header.formats.size() >= 2, "track and runtime checks finish before large padding or attachments");
                check(reader.readBytes <= 65536, "Matroska metadata needs only the first page");
            }
            AddonStreams.Source https = source("https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4");
            ParallelSourceChecks.Result network = ParallelSourceChecks.inspect(getTargetContext(), https, 0);
            check(network.state == SourceProbe.AVAILABLE, "an HTTPS MP4 passes bounded metadata checks: " + network.reason);
            AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(ParallelSourceChecks.WORKERS), release = new CountDownLatch(1);
            pool = new ParallelSourceChecks((media, expected) -> {
                int now = running.incrementAndGet(); peak.accumulateAndGet(now, Math::max); started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { running.decrementAndGet(); }
                return new ParallelSourceChecks.Result(SourceProbe.AVAILABLE, "test");
            });
            AddonStreams.Source one = source("https://example.invalid/one"), two = source("https://example.invalid/two"), three = source("https://example.invalid/three");
            Future<ParallelSourceChecks.Result> a = pool.prepare(one, 1140000), b = pool.prepare(two, 1140000), c = pool.prepare(three, 1140000);
            Future<ParallelSourceChecks.Result> d = pool.prepare(source("https://example.invalid/four"), 1140000), e = pool.prepare(source("https://example.invalid/five"), 1140000);
            check(started.await(2, TimeUnit.SECONDS) && running.get() == ParallelSourceChecks.WORKERS, "four candidates are checked at the same time");
            check(peak.get() == ParallelSourceChecks.WORKERS && !e.isDone(), "an additional candidate waits for a worker");
            check(pool.prepare(one, 1140000) == a, "repeated checks reuse their task");
            java.util.concurrent.CompletableFuture<ParallelSourceChecks.Result> pending = new java.util.concurrent.CompletableFuture<>();
            check(!ParallelSourceChecks.ready(pending), "an incomplete check cannot start playback");
            pending.complete(new ParallelSourceChecks.Result(SourceProbe.AVAILABLE, "test"));
            check(ParallelSourceChecks.ready(pending), "a completed alternative can start without waiting for the first check");
            release.countDown();
            check(a.get(2, TimeUnit.SECONDS).state == SourceProbe.AVAILABLE && b.get(2, TimeUnit.SECONDS).state == SourceProbe.AVAILABLE
                    && c.get(2, TimeUnit.SECONDS).state == SourceProbe.AVAILABLE && d.get(2, TimeUnit.SECONDS).state == SourceProbe.AVAILABLE
                    && e.get(2, TimeUnit.SECONDS).state == SourceProbe.AVAILABLE && peak.get() <= ParallelSourceChecks.WORKERS,
                    "the queue completes within its worker limit");
            pool.close(); pool = null;
            output.putString("stream", "Passed " + checks + " parallel media checks.\n");
        } catch (Throwable error) {
            output.putString("stream", "FAILED after " + checks + ": " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
        } finally { if (pool != null) pool.close(); finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output); }
    }
}
