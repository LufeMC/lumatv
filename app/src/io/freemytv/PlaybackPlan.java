package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/** Try two sources at each resolution before spending more time at that resolution. */
final class PlaybackPlan {
    static List<AddonStreams.Source> automatic(List<AddonStreams.Source> input) {
        List<AddonStreams.Source> candidates = new ArrayList<>();
        for (AddonStreams.Source source : input) if (source.probeState != SourceProbe.FAILED) candidates.add(source);
        List<AddonStreams.Source> sorted = AddonStreams.select(candidates, -1), result = new ArrayList<>();
        for (int quality : new int[]{2160, 1080, 720, 480, 360, 0}) {
            int count = 0;
            for (AddonStreams.Source source : sorted) if (source.quality == quality && count++ < 2) result.add(source);
        }
        for (AddonStreams.Source source : sorted) if (!result.contains(source)) result.add(source);
        return new ArrayList<>(result.subList(0, Math.min(12, result.size())));
    }
}
