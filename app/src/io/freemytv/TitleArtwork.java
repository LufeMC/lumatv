package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Cache public TMDB images without retaining account addresses. */
final class TitleArtwork {
    private static File directory;
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(4096) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getAllocationByteCount() / 1024); }
    };
    static void trimMemory() { memory.evictAll(); }
    static void init(Context context) { directory = new File(context.getCacheDir(), "title-art"); directory.mkdirs(); }
    static Bitmap load(String path, String size) {
        if (path == null || !path.matches("/[A-Za-z0-9_.-]+") || !("w300".equals(size) || "w342".equals(size) || "w780".equals(size) || "w1280".equals(size))) return null;
        String key = size + path; Bitmap cached = memory.get(key); if (cached != null) return cached;
        File file = directory == null ? null : new File(directory, size + "-" + path.substring(1));
        if (file != null && file.isFile()) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
            if (bitmap != null) { file.setLastModified(System.currentTimeMillis()); memory.put(key, bitmap); return bitmap; }
            file.delete();
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/" + size + path).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted() || output.size() + count > 3 * 1024 * 1024) return null;
                    output.write(block, 0, count);
                }
                byte[] bytes = output.toByteArray(); Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return null;
                memory.put(key, bitmap);
                if (file != null) try {
                    File part = new File(file.getPath() + "." + Thread.currentThread().getId() + ".part");
                    try (java.io.FileOutputStream stream = new java.io.FileOutputStream(part)) { stream.write(bytes); }
                    if (!part.renameTo(file)) part.delete(); prune();
                } catch (Exception ignored) {}
                return bitmap;
            }
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static synchronized void prune() {
        File[] files = directory == null ? null : directory.listFiles(); if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified())); long bytes = 0;
        for (File file : files) { if (file.getName().endsWith(".part")) continue; bytes += file.length(); if (bytes > 16L * 1024 * 1024) file.delete(); }
    }
}
