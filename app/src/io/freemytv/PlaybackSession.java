package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/** Source addresses stay in memory instead of activity intents. */
final class PlaybackSession {
    static PlaybackSession current;
    final String videoId, title;
    final List<AddonStreams.Source> sources;
    final List<String> failureNotes = new ArrayList<>();
    void failure(String reason) { if (failureNotes.size() < 12) failureNotes.add(index + ":" + reason); }
    final int quality;
    int index = -1;
    boolean discovering;
    Runnable sourcesChanged;
    long position, expectedDuration;
    String audioLanguage;
    long resumePosition = -1;
    ContinueWatching.Entry entry;
    PlaybackSession(String videoId, String title, List<AddonStreams.Source> sources, int quality) {
        this.videoId = videoId; this.title = title; this.sources = new ArrayList<>(sources); this.quality = quality;
    }
    void updateSources(List<AddonStreams.Source> available, boolean pending) {
        java.util.Set<String> tried = new java.util.HashSet<>();
        int keep = Math.min(index + 1, sources.size());
        for (int i = 0; i < keep; i++) tried.add(sources.get(i).url);
        List<AddonStreams.Source> candidates = new ArrayList<>();
        for (AddonStreams.Source source : available) if (!tried.contains(source.url)) candidates.add(source);
        while (sources.size() > keep) sources.remove(sources.size() - 1);
        for (AddonStreams.Source source : PlaybackPlan.automatic(candidates)) if (sources.size() < 12) sources.add(source);
        discovering = pending;
        if (sourcesChanged != null) sourcesChanged.run();
    }
    AddonStreams.Source next() {
        if (index + 1 < sources.size()) return sources.get(++index);
        if (!discovering) index = sources.size();
        return null;
    }
}
