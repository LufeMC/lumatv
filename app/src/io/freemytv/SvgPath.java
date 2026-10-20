package io.freemytv;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * The small part of SVG path data the app's drawings use (Flora's line mark and the line icons): M, L, H, V, Q, C,
 * A and Z, absolute and relative. Shapes are drawn once into a Path and then scaled with a Matrix.
 */
final class SvgPath {
    private SvgPath() {}

    static Path parse(String d) {
        Path p = new Path();
        Reader r = new Reader(d);
        float x = 0, y = 0, startX = 0, startY = 0;
        char cmd = 'M';
        while (r.more()) {
            if (r.letter()) cmd = r.next();
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + r.num(); y = oy + r.num();
                    p.moveTo(x, y);
                    startX = x; startY = y;
                    cmd = rel ? 'l' : 'L';     // more pairs after a move are lines
                    break;
                case 'L':
                    x = ox + r.num(); y = oy + r.num();
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + r.num();
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + r.num();
                    p.lineTo(x, y);
                    break;
                case 'Q': {
                    float x1 = ox + r.num(), y1 = oy + r.num();
                    x = ox + r.num(); y = oy + r.num();
                    p.quadTo(x1, y1, x, y);
                    break;
                }
                case 'C': {
                    float x1 = ox + r.num(), y1 = oy + r.num(), x2 = ox + r.num(), y2 = oy + r.num();
                    x = ox + r.num(); y = oy + r.num();
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    break;
                }
                case 'A': {
                    float rx = r.num(), ry = r.num(), rotation = r.num();
                    boolean large = r.flag(), sweep = r.flag();
                    float ex = ox + r.num(), ey = oy + r.num();
                    arc(p, x, y, ex, ey, rx, ry, rotation, large, sweep);
                    x = ex; y = ey;
                    break;
                }
                case 'Z':
                    p.close();
                    x = startX; y = startY;
                    break;
                default:
                    return p;
            }
        }
        return p;
    }

    /** SVG's endpoint arc as an Android arc (the icons only use circular arcs without rotation). */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry, float rotation,
                            boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) {
            p.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(rotation), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double rxs = Math.abs(rx), rys = Math.abs(ry);
        double lambda = (x1p * x1p) / (rxs * rxs) + (y1p * y1p) / (rys * rys);
        if (lambda > 1) {
            rxs *= Math.sqrt(lambda);
            rys *= Math.sqrt(lambda);
        }
        double num = rxs * rxs * rys * rys - rxs * rxs * y1p * y1p - rys * rys * x1p * x1p;
        double den = rxs * rxs * y1p * y1p + rys * rys * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rxs * y1p / rys, cyp = -coef * rys * x1p / rxs;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double start = Math.atan2((y1p - cyp) / rys, (x1p - cxp) / rxs);
        double end = Math.atan2((-y1p - cyp) / rys, (-x1p - cxp) / rxs);
        double delta = end - start;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        RectF oval = new RectF((float) (cx - rxs), (float) (cy - rys), (float) (cx + rxs), (float) (cy + rys));
        p.arcTo(oval, (float) Math.toDegrees(start), (float) Math.toDegrees(delta), false);
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) { this.s = s; }

        private void skip() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t')) i++;
        }

        boolean more() {
            skip();
            return i < s.length();
        }

        boolean letter() {
            skip();
            return i < s.length() && Character.isLetter(s.charAt(i)) && s.charAt(i) != 'e' && s.charAt(i) != 'E';
        }

        char next() { return s.charAt(i++); }

        boolean flag() {
            skip();
            return s.charAt(i++) == '1';
        }

        float num() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (Character.isDigit(ch)) i++;
                else if (ch == '.' && !dot) { dot = true; i++; }
                else if ((ch == 'e' || ch == 'E') && i + 1 < s.length()) {
                    i++;
                    if (s.charAt(i) == '-' || s.charAt(i) == '+') i++;
                } else break;
            }
            return Float.parseFloat(s.substring(start, i));
        }
    }
}
