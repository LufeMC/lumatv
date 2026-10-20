package io.freemytv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lines add-on subtitles up with the dialogue, like ffsubsync and alass, at a fraction of their cost. The audio
 * output (PlaybackAudio) feeds decoded PCM here during the first minutes; each 100 ms gets an energy value after a
 * simple high-pass that favours speech over rumble and music beds. Then every shift between -15 s and +15 s, at the
 * normal speed and at the 25/23.976 speed ratios, is scored by how much of the subtitle time falls on loud (speech)
 * frames minus quiet ones. A shift is applied only when its score clearly beats every shift more than 1.5 s away.
 * Memory: two arrays of 6,000 entries; CPU: a pass over every other sample frame while collecting.
 */
final class SubtitleSync {
    static final int FRAME_MS = 100, FRAMES = 6000;   // 10 minutes
    static final int RANGE_MS = 15000, STEP_MS = 100;
    static final int NEEDED_FRAMES = 1500;            // 2.5 minutes of heard audio

    private final float[] energy = new float[FRAMES];
    private final short[] count = new short[FRAMES];
    private volatile boolean active;
    private int encoding = -1, channels = 2, rate = 48000;
    private long streamOffsetUs, lastPts = Long.MIN_VALUE;
    private float previous;

    static final class Result {
        long offset; double scale = 1; boolean confident; float score, runnerUp;
    }

    void start() { active = true; }
    void stop() { active = false; }
    boolean active() { return active; }

    void reset() {
        synchronized (this) { java.util.Arrays.fill(energy, 0); java.util.Arrays.fill(count, (short) 0); lastPts = Long.MIN_VALUE; }
    }

    // Called on the playback thread.
    void configure(int pcmEncoding, int channelCount, int sampleRate) { encoding = pcmEncoding; channels = Math.max(1, channelCount); rate = Math.max(8000, sampleRate); }
    void streamOffset(long us) { streamOffsetUs = us; }

    /** Reads (without consuming) one PCM buffer whose first sample plays at presentationTimeUs. */
    void feed(ByteBuffer buffer, long presentationTimeUs) {
        if (!active || buffer == null || presentationTimeUs == lastPts) return;
        lastPts = presentationTimeUs;
        long mediaUs = presentationTimeUs - streamOffsetUs;
        if (mediaUs < 0 || mediaUs >= FRAMES * (long) FRAME_MS * 1000) return;
        int bytesPerSample = encoding == androidx.media3.common.C.ENCODING_PCM_16BIT ? 2 : encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT ? 4 : 0;
        if (bytesPerSample == 0) return;
        int frameBytes = bytesPerSample * channels, start = buffer.position(), end = buffer.limit();
        ByteOrder order = buffer.order(); buffer.order(ByteOrder.nativeOrder());
        try {
            int frames = (end - start) / frameBytes;
            double usPerFrame = 1e6 / rate;
            float prev = previous;
            int slot = -1; float sum = 0; int n = 0;
            for (int f = 0; f < frames; f += 2) {
                int at = start + f * frameBytes;
                float x = 0;
                for (int c = 0; c < channels && c < 2; c++) {
                    x += bytesPerSample == 2 ? buffer.getShort(at + c * 2) / 32768f : buffer.getFloat(at + c * 4);
                }
                float d = x - prev; prev = x;
                int s = (int) ((mediaUs + (long) (f * usPerFrame)) / (FRAME_MS * 1000L));
                if (s != slot) { flush(slot, sum, n); slot = s; sum = 0; n = 0; }
                sum += d * d; n++;
            }
            flush(slot, sum, n);
            previous = prev;
        } catch (Exception ignored) {
        } finally { buffer.order(order); }
    }

    private void flush(int slot, float sum, int n) {
        if (slot < 0 || slot >= FRAMES || n == 0) return;
        energy[slot] += sum; count[slot] = (short) Math.min(Short.MAX_VALUE, count[slot] + n);
    }

    /** Frames with audio heard so far. */
    int heard() { int h = 0; for (short c : count) if (c > 0) h++; return h; }

    /** Speech (+1), quiet (-1) or not heard (0) per 100 ms, from this run's energies. */
    byte[] speech() {
        float[] level = new float[FRAMES]; int known = 0;
        for (int i = 0; i < FRAMES; i++) if (count[i] > 0) { level[i] = (float) Math.log10(1e-9 + energy[i] / count[i]); known++; }
        byte[] out = new byte[FRAMES];
        if (known == 0) return out;
        float[] sorted = new float[known]; int k = 0;
        for (int i = 0; i < FRAMES; i++) if (count[i] > 0) sorted[k++] = level[i];
        java.util.Arrays.sort(sorted);
        float low = sorted[known / 5], high = sorted[Math.min(known - 1, known * 9 / 10)];
        float threshold = low + (high - low) * 0.45f;
        for (int i = 0; i < FRAMES; i++) if (count[i] > 0) out[i] = (byte) (level[i] > threshold ? 1 : -1);
        return out;
    }

    /** Best shift and speed for these cues against the speech frames (works on file times; ignores cues.offset). */
    static Result solve(SubtitleCues cues, byte[] speech) {
        Result result = new Result();
        int[] prefix = new int[speech.length + 1], heard = new int[speech.length + 1];
        for (int i = 0; i < speech.length; i++) { prefix[i + 1] = prefix[i] + speech[i]; heard[i + 1] = heard[i] + (speech[i] != 0 ? 1 : 0); }
        double[] scales = {1, SubtitleCues.PAL, 1 / SubtitleCues.PAL};
        int steps = RANGE_MS / STEP_MS;
        float best = -Float.MAX_VALUE; int bestScale = 0, bestStep = 0;
        float[][] scores = new float[scales.length][2 * steps + 1];
        for (int k = 0; k < scales.length; k++) {
            for (int o = -steps; o <= steps; o++) {
                long shift = (long) o * STEP_MS; int sum = 0, covered = 0;
                for (int i = 0; i < cues.size(); i++) {
                    int a = (int) ((cues.start[i] * scales[k] + shift) / FRAME_MS), b = (int) ((cues.end[i] * scales[k] + shift) / FRAME_MS);
                    if (b <= 0 || a >= speech.length) continue;
                    a = Math.max(0, a); b = Math.min(speech.length, b);
                    sum += prefix[b] - prefix[a]; covered += heard[b] - heard[a];
                }
                float score = covered < 300 ? -Float.MAX_VALUE : (float) sum / covered;
                scores[k][o + steps] = score;
                if (score > best) { best = score; bestScale = k; bestStep = o; }
            }
        }
        float runnerUp = -Float.MAX_VALUE;
        for (int k = 0; k < scales.length; k++) for (int o = -steps; o <= steps; o++)
            if ((k != bestScale || Math.abs(o - bestStep) * STEP_MS > 1500) && scores[k][o + steps] > runnerUp) runnerUp = scores[k][o + steps];
        result.offset = (long) bestStep * STEP_MS; result.scale = scales[bestScale]; result.score = best; result.runnerUp = runnerUp;
        result.confident = best > 0.15f && best - runnerUp > 0.1f;
        return result;
    }
}
