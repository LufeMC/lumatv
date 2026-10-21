package io.freemytv;

import android.content.Context;
import android.os.SystemClock;
import androidx.media3.datasource.*;
import java.io.IOException;

/** Supply bounded random reads without exposing stream credentials to another service. */
final class PreviewReader extends android.media.MediaDataSource {
    private static final int PAGE = 64 * 1024;
    private final DataSource.Factory factory;
    private final String uri;
    private final android.app.ActivityManager memory;
    private final android.util.LruCache<Long, byte[]> pages = new android.util.LruCache<>(16);
    private DataSource input;
    private long size = -1, cursor = -1, memoryAt;
    static final class Budget {
        final long limit;
        final long memoryFloor;
        Budget() { this(16L * 1024 * 1024); }
        Budget(long limit) { this(limit, 445L * 1024 * 1024); }
        Budget(long limit, long memoryFloor) { this.limit = limit; this.memoryFloor = memoryFloor; }
        long started = SystemClock.elapsedRealtime(), bytes;
        synchronized boolean exceeded(long now) {
            if (now - started >= 60000) { started = now; bytes = 0; }
            return bytes > limit;
        }
        synchronized void transferred(int count) { bytes += count; }
    }
    private final Budget budget;
    volatile long deadline, readBytes, networkBytes;
    volatile boolean stopped;
    volatile String reason = "";
    PreviewReader(Context context, AddonStreams.Source source) throws IOException {
        this(context, source, new Budget());
    }
    PreviewReader(Context context, AddonStreams.Source source, Budget budget) throws IOException {
        this.budget = budget; uri = source.url; memory = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        deadline = SystemClock.elapsedRealtime() + 20000;
        factory = MediaCache.factory(context, new DefaultDataSource.Factory(context, new DefaultHttpDataSource.Factory()
                .setUserAgent("FreeMyTV/1.0").setConnectTimeoutMs(3000).setReadTimeoutMs(3000)
                .setAllowCrossProtocolRedirects(false).setDefaultRequestProperties(source.headers)), source);
        open(0);
    }
    private void check() throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (Thread.currentThread().isInterrupted()) fail("closed");
        if (stopped) fail("closed");
        if (now > deadline) fail("deadline");
        if (budget.exceeded(now)) fail("network_budget");
        if (now - memoryAt > 1000) {
            memoryAt = now; android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo(); memory.getMemoryInfo(info);
            if (info.availMem < budget.memoryFloor) fail("memory");
        }
    }
    void verify() throws IOException { if (!reason.isEmpty()) throw new IOException("preview_" + reason); if (stopped) fail("closed"); }
    private void fail(String value) throws IOException { reason = value; throw new IOException("preview_" + value); }
    private void open(long position) throws IOException {
        check(); if (input != null) input.close(); input = factory.createDataSource();
        input.addTransferListener(new TransferListener() {
            public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
            public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
            public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
            public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
                if (network) { networkBytes += count; budget.transferred(count); }
                try { check(); } catch (IOException error) { throw new BudgetStop(); }
            }
        });
        try {
            long length = input.open(new DataSpec.Builder().setUri(uri).setPosition(position).build()); cursor = position;
            if (position == 0 && length >= 0) size = length;
        } catch (BudgetStop error) { throw new IOException("preview_budget"); }
    }
    private static final class BudgetStop extends RuntimeException {}
    @Override public synchronized int readAt(long position, byte[] data, int offset, int length) throws IOException {
        check(); if (length == 0) return 0; if (position < 0 || size >= 0 && position >= size) return -1;
        long page = position / PAGE * PAGE; byte[] block = pages.get(page);
        if (block == null) {
            if (cursor != page) open(page);
            int wanted = size < 0 ? PAGE : (int) Math.min(PAGE, size - page); block = new byte[wanted]; int total = 0;
            try { while (total < wanted) { check(); int count = input.read(block, total, wanted - total); if (count < 0) break; total += count; cursor += count; readBytes += count; } }
            catch (BudgetStop error) { throw new IOException("preview_budget"); }
            if (total <= 0) return -1;
            if (total < wanted) block = java.util.Arrays.copyOf(block, total); pages.put(page, block);
        }
        int from = (int) (position - page), count = Math.min(length, block.length - from); if (count <= 0) return -1;
        System.arraycopy(block, from, data, offset, count); return count;
    }
    @Override public synchronized long getSize() { return size; }
    @Override public synchronized void close() throws IOException { if (input != null) { try { input.close(); } finally { input = null; pages.evictAll(); } } }
}
