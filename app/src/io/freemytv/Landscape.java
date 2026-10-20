package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;

import java.io.InputStream;
import java.util.Calendar;

/**
 * The home screen's landscape for the time of day (assets/landscapes, drawn by tools/make_landscapes.py): day, dusk
 * around sunrise and sunset, night. Settings → Home screen → Look can pin it to day or night. The full picture lives on
 * the GPU; a small blurred copy backs the frosted search bar and the dimmed screens (search, all apps, menus).
 */
final class Landscape {
    static final int DAY = 0, DUSK = 1, NIGHT = 2;
    private static final String[] FILES = {"landscapes/day.jpg", "landscapes/dusk.jpg", "landscapes/night.jpg"};

    final int kind;
    final Bitmap image;     // 1920 × 1080, on the GPU
    final Bitmap blurred;   // 320 × 180, soft

    private Landscape(int kind, Bitmap image, Bitmap blurred) {
        this.kind = kind;
        this.image = image;
        this.blurred = blurred;
    }

    /** Which one fits now: the Look setting first, then the sun (dusk = an hour either side of sunrise and sunset). */
    static int now(Context c, HomeData.Weather wx, long now) {
        String look = Prefs.look(c);
        if ("day".equals(look)) return DAY;
        if ("night".equals(look)) return NIGHT;
        long rise = wx.sunrise, set = wx.sunset;
        if (rise == 0 || set == 0 || Math.abs(now - rise) > 36 * 3600_000L) {
            // No weather yet: a typical day, 6:30 to 18:30.
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            cal.set(Calendar.HOUR_OF_DAY, 6);
            cal.set(Calendar.MINUTE, 30);
            rise = cal.getTimeInMillis();
            set = rise + 12 * 3600_000L;
        } else {
            // Today's times, shifted to the day of `now`.
            long day = 24 * 3600_000L;
            while (now - rise > day) { rise += day; set += day; }
            while (rise - now > day) { rise -= day; set -= day; }
        }
        long hour = 3600_000L;
        if (Math.abs(now - rise) < hour || Math.abs(now - set) < hour) return DUSK;
        return now > rise && now < set ? DAY : NIGHT;
    }

    /** Is the day look on (dark ink on the light landscape)? */
    boolean day() {
        return kind == DAY;
    }

    static Landscape load(Context c, int kind) {
        try {
            Bitmap image;
            try {
                image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getAssets(), FILES[kind]),
                        (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE));
            } catch (Exception e) {
                try (InputStream in = c.getAssets().open(FILES[kind])) {
                    image = BitmapFactory.decodeStream(in);
                }
            }
            BitmapFactory.Options small = new BitmapFactory.Options();
            small.inSampleSize = 4;
            Bitmap quarter;
            try (InputStream in = c.getAssets().open(FILES[kind])) {
                quarter = BitmapFactory.decodeStream(in, null, small);
            }
            Bitmap blurred = HomeArt.blur(quarter);
            quarter.recycle();
            return new Landscape(kind, image, blurred);
        } catch (Exception e) {
            AppLog.w("landscape: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
