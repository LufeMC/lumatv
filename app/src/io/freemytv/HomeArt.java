package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures for the home screen, loaded in the background and kept small:
 *  - cards: 16:9 at the focused size (336 × 189), cropped from the title's backdrop (or poster);
 *  - hero: the backdrop behind the focused card, 1280 wide, held on the GPU; a portrait poster becomes a soft blur
 *    instead (a poster stretched across the TV looks wrong).
 * A source is a TMDB image path ("/abc.jpg"), an https address or a content:// address (the TV's Watch Next).
 */
final class HomeArt {
    interface Listener { void onArt(); }

    static final int CARD_W = 336, CARD_H = 189, HERO_W = 1280;
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Set<String> pending = new HashSet<>();
    private final LruCache<String, Bitmap> cards = new LruCache<String, Bitmap>(6 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return Math.max(1, b.getAllocationByteCount() / 1024); }
    };
    private final LruCache<String, Hero> heroes = new LruCache<>(3);
    private volatile int epoch;

    /** The backdrop behind the hero; `soft` = a blurred poster (drawn darker, edge to edge). */
    static final class Hero {
        final Bitmap image;
        final boolean soft;

        Hero(Bitmap image, boolean soft) {
            this.image = image;
            this.soft = soft;
        }
    }

    /** Finds the source in the background (for titles that only know their name or IMDb id). */
    interface Source { String find() throws Exception; }

    HomeArt(Context c, Listener listener) {
        context = c.getApplicationContext();
        this.listener = listener;
    }

    /** The card picture, or null (it loads and calls the listener). */
    Bitmap card(String key, Source source) {
        Bitmap b = cards.get(key);
        if (b != null || source == null) return b;
        final int requested = epoch;
        load("card:" + key, () -> {
            Bitmap full = fetch(context, source.find(), false);
            if (full == null) return;
            Bitmap card = crop(full, CARD_W, CARD_H);
            Bitmap gpu = card.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) card.recycle(); else gpu = card;
            final Bitmap ready = gpu;
            main.post(() -> { if (requested == epoch) cards.put(key, ready); else ready.recycle(); });
        });
        return null;
    }

    Hero hero(String key, Source source) {
        Hero h = heroes.get(key);
        if (h != null || source == null) return h;
        final int requested = epoch;
        load("hero:" + key, () -> {
            Bitmap full = fetch(context, source.find(), true);
            if (full == null) return;
            boolean soft = full.getHeight() > full.getWidth() * 0.8f;
            Bitmap out;
            if (soft) {
                out = blur(full);
            } else if (full.getWidth() > HERO_W) {
                out = Bitmap.createScaledBitmap(full, HERO_W, Math.round(full.getHeight() * (float) HERO_W / full.getWidth()), true);
            } else {
                out = full.copy(Bitmap.Config.ARGB_8888, false);
            }
            Bitmap gpu = out.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null && gpu != out) out.recycle();
            final Bitmap ready = gpu != null ? gpu : out;
            main.post(() -> { if (requested == epoch) heroes.put(key, new Hero(ready, soft)); else ready.recycle(); });
        });
        return null;
    }

    /** Leaving the home screen: memory goes back to the TV. */
    void trim() {
        epoch++;
        cards.evictAll();
        heroes.evictAll();
        synchronized (pending) {
            pending.clear();
        }
    }

    private interface Job { void run() throws Exception; }

    private void load(String key, Job job) {
        synchronized (pending) {
            if (!pending.add(key)) return;
        }
        final int requested = epoch;
        LOADER.execute(() -> {
            try {
                if (requested == epoch) job.run();
            } catch (Exception ignored) {
            } finally {
                synchronized (pending) {
                    pending.remove(key);
                }
                if (requested == epoch) main.post(listener::onArt);
            }
        });
    }

    /** Center crop to w × h. */
    static Bitmap crop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float sw = w / scale, sh = h / scale;
        // Posters: keep the top (faces and titles sit there).
        float top = src.getHeight() > src.getWidth() ? (src.getHeight() - sh) * 0.2f : (src.getHeight() - sh) / 2;
        Rect from = new Rect(Math.round((src.getWidth() - sw) / 2), Math.round(top),
                Math.round((src.getWidth() + sw) / 2), Math.round(top + sh));
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        return out;
    }

    /** A heavy blur: down to 48 px wide in halves, a box blur, then back up (the GPU smooths the rest). */
    static Bitmap blur(Bitmap src) {
        Bitmap small = crop(src, 64, 36);
        int[] px = new int[64 * 36], tmp = new int[px.length];
        small.getPixels(px, 0, 64, 0, 0, 64, 36);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, 64, 36, 2, true);
            box(tmp, px, 64, 36, 2, false);
        }
        small.setPixels(px, 0, 64, 0, 0, 64, 36);
        Bitmap out = Bitmap.createScaledBitmap(small, 320, 180, true);
        small.recycle();
        return out;
    }

    static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < n; i++) {
                int rr = 0, gg = 0, bb = 0, count = 0;
                for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                    int p = in[horizontal ? line * w + j : j * w + line];
                    rr += (p >> 16) & 255;
                    gg += (p >> 8) & 255;
                    bb += p & 255;
                    count++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (rr / count << 16) | (gg / count << 8) | bb / count;
            }
        }
    }

    /** Downloads (or reads) a picture. TMDB paths go through the shared disk cache. */
    static Bitmap fetch(Context c, String address, boolean large) throws Exception {
        if (address == null || address.isEmpty()) return null;
        if (address.startsWith("/")) return TitleArtwork.load(address, large ? "w1280" : "w780");
        if (address.startsWith("http://")) address = "https://" + address.substring(7);
        if (address.startsWith("https://image.tmdb.org/t/p/")) {
            android.net.Uri uri = android.net.Uri.parse(address);
            java.util.List<String> segments = uri.getPathSegments();
            if (segments.size() == 4) return TitleArtwork.load("/" + segments.get(3), large ? "w1280" : "w780");
        }
        if (address.startsWith("content://")) {
            try (InputStream input = c.getContentResolver().openInputStream(android.net.Uri.parse(address))) {
                return decode(input, large);
            }
        }
        return download(address, large, 0);
    }

    private static Bitmap download(String address, boolean large, int redirects) throws Exception {
        if (!MediaHttp.https(address)) return null;
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            int response = connection.getResponseCode();
            if (response >= 300 && response < 400 && redirects < 3) {
                return download(new URL(new URL(address), connection.getHeaderField("Location")).toString(), large, redirects + 1);
            }
            if (response != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                return decode(input, large);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Bitmap decode(InputStream input, boolean large) throws Exception {
        if (input == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) != -1) {
            if (output.size() + count > 4 * 1024 * 1024) return null;
            output.write(block, 0, count);
        }
        byte[] bytes = output.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 12000 || options.outHeight > 12000) return null;
        int target = large ? HERO_W : 640;
        options.inSampleSize = 1;
        while (options.outWidth / (options.inSampleSize * 2) >= target) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
