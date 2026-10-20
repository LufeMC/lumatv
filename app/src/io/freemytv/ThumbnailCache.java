package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Cache service thumbnails with opaque filenames and a fixed storage limit. */
final class ThumbnailCache {
    private static File directory;
    static synchronized void init(Context context) { if (directory == null) { directory = new File(context.getCacheDir(), "service-art"); directory.mkdirs(); } }
    private static File file(String key) throws Exception {
        if (directory == null) return null;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(); for (byte value : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new File(directory, name + ".jpg");
    }
    static Bitmap read(String key) {
        try {
            File file = file(key); if (file == null || !file.isFile() || file.length() > 1024 * 1024) return null;
            long age = System.currentTimeMillis() - file.lastModified(); if (age < 0 || age > 24 * 60 * 60000L) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 640 || bounds.outHeight > 640) { file.delete(); return null; }
            return BitmapFactory.decodeFile(file.getPath());
        } catch (Exception ignored) { return null; }
    }
    static synchronized void write(String key, Bitmap bitmap) {
        File part = null; Bitmap small = null;
        try {
            File file = file(key); if (file == null) return;
            float scale = Math.min(1f, 640f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            part = new File(file.getPath() + ".part");
            try (FileOutputStream output = new FileOutputStream(part)) { small.compress(Bitmap.CompressFormat.JPEG, 88, output); }
            if (!part.renameTo(file)) return;
            File[] files = directory.listFiles(); if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long total = 0; int count = 0;
            for (File image : files) { total += image.length(); if (++count > 80 || total > 8 * 1024 * 1024) image.delete(); }
        } catch (Exception ignored) {} finally { if (part != null) part.delete(); if (small != null && small != bitmap) small.recycle(); }
    }
}
