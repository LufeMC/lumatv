package io.freemytv;

/** Give higher resolutions a short opportunity without waiting for every add-on. */
final class SourceGate {
    private long first = -1;
    long delay(long now, int remaining, java.util.List<AddonStreams.Source> sources) {
        if (sources.isEmpty()) return -1;
        if (first < 0) first = now;
        if (remaining == 0) return 0;
        for (AddonStreams.Source source : sources) if (source.quality >= 2160) return 0;
        return Math.max(0, 800 - (now - first));
    }
}
