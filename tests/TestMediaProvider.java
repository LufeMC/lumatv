package io.freemytv;

/** This test APK exposes only the generated test video and subtitle files. */
public class TestMediaProvider extends android.content.ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public android.content.res.AssetFileDescriptor openAssetFile(android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
        String path = uri.getLastPathSegment();
        if ("denied.mkv".equals(path)) throw new SecurityException("Test access denied");
        if (!"r".equals(mode) || !("sample.mkv".equals(path) || "corrupt.mkv".equals(path) || "verified.mkv".equals(path) || "video-only.mkv".equals(path) || "audio-only.mkv".equals(path) || "placeholder.mkv".equals(path) || "two-minute-error.mkv".equals(path) || "truncated.mkv".equals(path) || "subtitle-en.srt".equals(path) || "subtitle-es.srt".equals(path)))
            throw new java.io.FileNotFoundException();
        try { return getContext().getAssets().openFd(path); }
        catch (java.io.IOException e) { throw new java.io.FileNotFoundException(); }
    }
    @Override public String getType(android.net.Uri uri) { return "sample.mkv".equals(uri.getLastPathSegment()) ? "video/x-matroska" : "application/x-subrip"; }
    @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(android.net.Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
