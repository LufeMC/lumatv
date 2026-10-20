package io.freemytv;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Keep public title metadata in a bounded private disk cache. */
final class MetadataCache {
    private static File directory;
    static synchronized void init(Context context) {
        if (directory == null) { directory = new File(context.getCacheDir(), "title-metadata"); directory.mkdirs(); }
    }
    static long lifetime(String path) {
        if (path.startsWith("/search/")) return 5 * 60000L;
        if (path.startsWith("/find/")) return 7 * 24 * 60 * 60000L;
        if (path.contains("/watch/providers")) return 60 * 60000L;
        if (path.contains("/season/")) return 6 * 60 * 60000L;
        return 24 * 60 * 60000L;
    }
    private static File file(String path) throws Exception {
        if (directory == null) return null;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(); for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new File(directory, name + ".json");
    }
    static String read(String path, long now) {
        try {
            File file = file(path); if (file == null || !file.isFile() || file.length() > 2 * 1024 * 1024) return null;
            long age = now - file.lastModified(); if (age < 0 || age >= lifetime(path)) return null;
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            new JSONObject(value); return value;
        } catch (Exception ignored) { return null; }
    }
    static synchronized void write(String path, String value) {
        File part = null;
        try {
            File file = file(path); if (file == null) return;
            part = new File(file.getPath() + ".part"); Files.write(part.toPath(), value.getBytes(StandardCharsets.UTF_8));
            if (!part.renameTo(file)) return;
            file.setLastModified(System.currentTimeMillis());
            File[] files = directory.listFiles(); if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long bytes = 0; int count = 0;
            for (File item : files) {
                bytes += item.length(); if (++count > 80 || bytes > 4 * 1024 * 1024) item.delete();
            }
        } catch (Exception ignored) {} finally { if (part != null) part.delete(); }
    }
}
