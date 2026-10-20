package io.freemytv;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A subtitle provider must not receive the video source's request credentials. */
final class MediaDataSource implements DataSource {
    private final DataSource.Factory media, text;
    private final String subtitle;
    private final List<TransferListener> listeners = new ArrayList<>();
    private DataSource current;
    static DataSource.Factory factory(Context context, AddonStreams.Source source, String subtitle) {
        DefaultHttpDataSource.Factory http = http().setDefaultRequestProperties(source.headers);
        DataSource.Factory media = MediaCache.factory(context, new DefaultDataSource.Factory(context, http), source);
        DataSource.Factory text = new DefaultDataSource.Factory(context, http());
        return () -> new MediaDataSource(media, text, subtitle);
    }
    private static DefaultHttpDataSource.Factory http() {
        return new DefaultHttpDataSource.Factory().setUserAgent("FreeMyTV/1.0").setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000).setAllowCrossProtocolRedirects(false);
    }
    private MediaDataSource(DataSource.Factory media, DataSource.Factory text, String subtitle) {
        this.media = media; this.text = text; this.subtitle = subtitle;
    }
    @Override public void addTransferListener(TransferListener listener) {
        listeners.add(listener); if (current != null) current.addTransferListener(listener);
    }
    @Override public long open(DataSpec spec) throws IOException {
        current = (subtitle != null && subtitle.equals(spec.uri.toString()) ? text : media).createDataSource();
        for (TransferListener listener : listeners) current.addTransferListener(listener);
        return current.open(spec);
    }
    @Override public int read(byte[] data, int offset, int length) throws IOException { return current.read(data, offset, length); }
    @Override public Uri getUri() { return current == null ? null : current.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return current == null ? Collections.emptyMap() : current.getResponseHeaders(); }
    @Override public void close() throws IOException { if (current != null) { try { current.close(); } finally { current = null; } } }
}
