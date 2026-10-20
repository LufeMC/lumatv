package io.freemytv;

/** Count only active playback time within a verified intro marker. */
final class IntroSkip {
    static final long COUNTDOWN_MS = 5000;
    IntroMarkers.Marker marker;
    long remaining = COUNTDOWN_MS, last;
    boolean visible, kept, skipped, wasPlaying;
    boolean update(long position, long duration, boolean playing, long now) {
        boolean inside = marker != null && marker.fits(duration) && position >= marker.start && position < marker.end && !kept && !skipped;
        if (!inside) { visible = false; remaining = COUNTDOWN_MS; last = now; wasPlaying = false; return false; }
        if (visible && playing && wasPlaying) remaining = Math.max(0, remaining - Math.min(1000, Math.max(0, now - last)));
        visible = true; last = now; wasPlaying = playing;
        return remaining == 0;
    }
    void keep() { kept = true; visible = false; }
    void skip() { skipped = true; visible = false; }
    int seconds() { return (int) ((remaining + 999) / 1000); }
}
