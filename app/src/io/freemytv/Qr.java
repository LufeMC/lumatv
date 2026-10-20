package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** QR codes for phones (ZXing core): black modules on white, whole pixels per module, a 4-module quiet zone. */
final class Qr {
    private Qr() {}

    static final int QUIET = 4;

    /** A square bitmap of `sizePx`; modules are snapped to whole pixels and the code is centered. Null if it can't fit. */
    static Bitmap bitmap(String text, int sizePx) {
        boolean[][] m = modules(text);
        if (m == null) return null;
        int n = m.length, cells = n + 2 * QUIET;
        int unit = sizePx / cells;
        if (unit < 1) return null;
        int offset = (sizePx - unit * n) / 2;
        int[] row = new int[sizePx];
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        for (int y = 0; y < sizePx; y++) {
            int my = (y - offset) < 0 ? -1 : (y - offset) / unit;
            for (int x = 0; x < sizePx; x++) {
                int mx = (x - offset) < 0 ? -1 : (x - offset) / unit;
                boolean dark = my >= 0 && my < n && mx >= 0 && mx < n && m[my][mx];
                row[x] = dark ? Color.BLACK : Color.WHITE;
            }
            b.setPixels(row, 0, sizePx, 0, y, sizePx, 1);
        }
        return b;
    }

    /** The bare module grid (no quiet zone), [y][x], true = dark. Null when the text is empty or too long. */
    static boolean[][] modules(String text) {
        if (text == null || text.isEmpty()) return null;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = matrix.getWidth();
            boolean[][] out = new boolean[n][n];
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) out[y][x] = matrix.get(x, y);
            return out;
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }
}
