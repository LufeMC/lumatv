package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/**
 * Order sources by supported resolution, then alternate providers within each resolution.
 * Prefer the remembered release within its resolution.
 * Put files for another episode at the end.
 */
final class PlaybackPlan {
    static final int LIMIT = 12;

    static List<AddonStreams.Source> automatic(List<AddonStreams.Source> input) {
        List<AddonStreams.Source> playable = new ArrayList<>(), unsupported = new ArrayList<>();
        for (AddonStreams.Source source : input) {
            if (source.probeState == SourceProbe.FAILED) continue;
            (source.unsupported == null ? playable : unsupported).add(source);
        }
        List<AddonStreams.Source> candidates = playable.isEmpty() ? unsupported : playable;
        List<AddonStreams.Source> sorted = AddonStreams.select(candidates, -1), result = new ArrayList<>();
        String videoId = "";
        for (AddonStreams.Source source : sorted) if (source.videoId != null && !source.videoId.isEmpty()) { videoId = source.videoId; break; }
        String[] memory = videoId.isEmpty() ? null : PlaybackMemory.source(videoId);
        if (memory != null) {
            final String addon = memory[0];
            // Same add-on first within each resolution (the sort is stable).
            if (!addon.isEmpty()) sorted.sort((a, b) -> a.quality != b.quality ? Integer.compare(b.quality, a.quality)
                    : Integer.compare(addon.equals(a.addon) ? 0 : 1, addon.equals(b.addon) ? 0 : 1));
            if (!memory[1].isEmpty()) {
                int count = 0;
                for (AddonStreams.Source source : sorted)
                    if (memory[1].equals(source.bingeGroup) && !source.otherEpisode && count++ < 2) result.add(source);
            }
        }
        for (int quality : new int[]{2160, 1080, 720, 480, 360, 0}) {
            int count = 0;
            for (AddonStreams.Source source : sorted)
                if (source.quality == quality && !result.contains(source) && count++ < 2) result.add(source);
        }
        for (AddonStreams.Source source : sorted) if (!result.contains(source)) result.add(source);
        // Reorder only: a file naming another episode still gets its turn after the rest.
        List<AddonStreams.Source> ordered = new ArrayList<>(result.size());
        for (AddonStreams.Source source : result) if (!source.otherEpisode) ordered.add(source);
        for (AddonStreams.Source source : result) if (source.otherEpisode) ordered.add(source);
        ordered.sort((a, b) -> a.otherEpisode != b.otherEpisode ? Boolean.compare(a.otherEpisode, b.otherEpisode)
                : Integer.compare(b.quality, a.quality));
        List<AddonStreams.Source> diverse = new ArrayList<>();
        for (int start = 0; start < ordered.size();) {
            AddonStreams.Source first = ordered.get(start); int end = start;
            java.util.Map<String, java.util.ArrayDeque<AddonStreams.Source>> providers = new java.util.LinkedHashMap<>();
            while (end < ordered.size() && ordered.get(end).quality == first.quality && ordered.get(end).otherEpisode == first.otherEpisode) {
                AddonStreams.Source source = ordered.get(end++);
                providers.computeIfAbsent(source.addon, key -> new java.util.ArrayDeque<>()).add(source);
            }
            boolean added;
            do {
                added = false;
                for (java.util.ArrayDeque<AddonStreams.Source> provider : providers.values())
                    if (!provider.isEmpty()) { diverse.add(provider.removeFirst()); added = true; }
            } while (added);
            start = end;
        }
        return new ArrayList<>(diverse.subList(0, Math.min(LIMIT, diverse.size())));
    }

    // Memory for rewinding shares the player's 20 MB limit with the video loaded ahead. Keep it to a quarter.
    static final int TARGET_BUFFER_BYTES = 20 * 1024 * 1024;
    static final int BACK_BUFFER_MS = 15000;

    /** Back buffer for a source of this bitrate (bits per second): at most 15 s, and at most a quarter of the limit. */
    static int backBufferMs(long bitsPerSecond) {
        if (bitsPerSecond <= 0) return BACK_BUFFER_MS;
        long ms = (TARGET_BUFFER_BYTES / 4L) * 8L * 1000L / bitsPerSecond;
        return (int) Math.min(BACK_BUFFER_MS, ms);
    }

    /** The source's bitrate from its size and the expected runtime, else a cautious guess from its resolution. */
    static long estimateBitrate(AddonStreams.Source source, long expectedDurationMs) {
        if (source != null && source.videoSize > 0 && expectedDurationMs > 0)
            return Math.max(1L, (long) (source.videoSize * (8000.0 / expectedDurationMs)));
        int quality = source == null ? 0 : source.quality;
        return quality >= 2160 ? 25_000_000L : quality >= 1080 ? 10_000_000L : quality >= 720 ? 5_000_000L : 8_000_000L;
    }
}
