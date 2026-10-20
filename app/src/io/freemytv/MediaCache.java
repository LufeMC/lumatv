package io.freemytv;

import android.content.Context;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Keep a bounded video cache with opaque keys in private application storage. */
final class MediaCache {
    private static SimpleCache cache;
    static synchronized DataSource.Factory factory(Context context, DataSource.Factory upstream, AddonStreams.Source source) {
        if (!MediaHttp.https(source.url)) return upstream;
        try {
            if (cache == null) cache = new SimpleCache(new File(context.getCacheDir(), "video"),
                    new LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), new StandaloneDatabaseProvider(context));
            StringBuilder headers = new StringBuilder();
            for (Map.Entry<String, String> header : new TreeMap<>(source.headers).entrySet()) headers.append(header.getKey()).append(':').append(header.getValue()).append('\n');
            final String identity = headers.toString();
            return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(spec -> digest(spec.uri.toString() + '\n' + identity))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        } catch (Exception ignored) { return upstream; }
    }
    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte b : bytes) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hash.toString();
        } catch (Exception impossible) { throw new IllegalStateException("digest"); }
    }
}
