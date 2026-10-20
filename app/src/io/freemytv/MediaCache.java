package io.freemytv;

import android.content.Context;
import android.net.Uri;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keep a bounded video cache with opaque keys in private application storage. It lives on disk, so a larger cache
 * costs storage, not memory: rewinds beyond the small in-memory back buffer come from here.
 */
final class MediaCache {
    private static SimpleCache cache;
    static final long SMALL = 64L * 1024 * 1024, LARGE = 192L * 1024 * 1024;

    /** 192 MiB when the TV has room for it (over 2 GiB free), else 64 MiB. */
    static long limit(long usableBytes) { return usableBytes > 2L * 1024 * 1024 * 1024 ? LARGE : SMALL; }

    private static synchronized SimpleCache cache(Context context) {
        if (cache == null) {
            File dir = new File(context.getCacheDir(), "video");
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(limit(context.getCacheDir().getUsableSpace())),
                    new StandaloneDatabaseProvider(context.getApplicationContext()));
        }
        return cache;
    }

    static synchronized DataSource.Factory factory(Context context, DataSource.Factory upstream, AddonStreams.Source source) {
        if (!MediaHttp.https(source.url)) return upstream;
        try {
            final String identity = identity(source);
            return new CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(spec -> digest(spec.uri.toString() + '\n' + identity))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        } catch (Exception ignored) { return upstream; }
    }

    private static String identity(AddonStreams.Source source) {
        StringBuilder headers = new StringBuilder();
        for (Map.Entry<String, String> header : new TreeMap<>(source.headers).entrySet()) headers.append(header.getKey()).append(':').append(header.getValue()).append('\n');
        return headers.toString();
    }

    static String key(AddonStreams.Source source) { return digest(source.url + '\n' + identity(source)); }

    /** Reads the first bytes of a source into the cache (blocking; call off the UI thread). Returns the bytes cached. */
    static long prefetch(Context context, AddonStreams.Source source, long bytes) {
        if (source == null || !MediaHttp.https(source.url)) return 0;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("FreeMyTV/1.0")
                    .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(false)
                    .setDefaultRequestProperties(source.headers);
            DataSource data = factory(context, new DefaultDataSource.Factory(context, http), source).createDataSource();
            if (!(data instanceof CacheDataSource)) return 0;
            CacheWriter writer = new CacheWriter((CacheDataSource) data, new DataSpec.Builder().setUri(Uri.parse(source.url))
                    .setPosition(0).setLength(bytes).build(), null, null);
            writer.cache();
            return cache(context).getCachedBytes(key(source), 0, bytes);
        } catch (Exception ignored) { return 0; }
    }

    /** Drops what was prefetched for a source the viewer didn't play. */
    static void remove(Context context, AddonStreams.Source source) {
        if (source == null || !MediaHttp.https(source.url)) return;
        try { cache(context).removeResource(key(source)); } catch (Exception ignored) {}
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte b : bytes) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hash.toString();
        } catch (Exception impossible) { throw new IllegalStateException("digest"); }
    }
}
