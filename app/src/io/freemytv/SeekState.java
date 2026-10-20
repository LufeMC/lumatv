package io.freemytv;

/** Keep the selected position separate from the saved playback position. */
final class SeekState {
    static final long AUTO_CONFIRM_MS = 3000;
    boolean active, playing;
    long confirmAt;
    long original, target, duration;
    void begin(long position, long duration, boolean playing) {
        if (active) return;
        this.original = this.target = Math.max(0, position); this.duration = Math.max(0, duration); this.playing = playing; active = true;
    }
    void move(int direction, int repeat) { select(target + direction * (repeat >= 30 ? 60000 : repeat >= 12 ? 30000 : 10000)); }
    void select(long position) { target = Math.max(0, Math.min(Math.max(0, duration - 1000), position)); }
    void input(long now) { confirmAt = now + AUTO_CONFIRM_MS; }
    boolean due(long now) { return active && confirmAt > 0 && now >= confirmAt; }
    long finish(boolean confirmed) { active = false; return confirmed ? target : original; }
}
