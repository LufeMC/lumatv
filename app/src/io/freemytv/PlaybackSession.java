package io.freemytv;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Source addresses stay in memory instead of activity intents. */
final class PlaybackSession {
    static PlaybackSession current;
    final String videoId, title;
    /** The automatic queue (at most 12). */
    final List<AddonStreams.Source> sources;
    /** Every source found for this video, for "Choose a source" (including ones this TV can't play). */
    final List<AddonStreams.Source> all = new ArrayList<>();
    /** What happened to each source (by url): "playing", "failed:<reason>", "stopped:<ms>". */
    final Map<String, String> outcome = new HashMap<>();
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
        addAll(sources); addAll(AddonStreams.discovered(videoId));
    }
    private void addAll(List<AddonStreams.Source> found) {
        Set<String> seen = new HashSet<>(); for (AddonStreams.Source s : all) seen.add(s.url);
        for (AddonStreams.Source s : found) if (all.size() < 400 && seen.add(s.url)) all.add(s);
    }
    void updateSources(List<AddonStreams.Source> available, boolean pending) {
        java.util.Set<String> tried = new java.util.HashSet<>();
        int keep = Math.min(index + 1, sources.size());
        for (int i = 0; i < keep; i++) tried.add(sources.get(i).url);
        List<AddonStreams.Source> candidates = new ArrayList<>();
        for (AddonStreams.Source source : available) if (!tried.contains(source.url)) candidates.add(source);
        while (sources.size() > keep) sources.remove(sources.size() - 1);
        for (AddonStreams.Source source : PlaybackPlan.automatic(candidates)) if (sources.size() < PlaybackPlan.LIMIT) sources.add(source);
        addAll(available);
        discovering = pending;
        if (sourcesChanged != null) sourcesChanged.run();
    }
    AddonStreams.Source next() {
        if (index + 1 < sources.size()) return sources.get(++index);
        if (!discovering) index = sources.size();
        return null;
    }
    /** Puts a source the viewer chose next in the queue; next() returns it. */
    void choose(AddonStreams.Source source) {
        int at = Math.min(Math.max(0, index + 1), sources.size());
        for (int i = sources.size() - 1; i > index && i >= 0; i--) if (sources.get(i).url.equals(source.url)) sources.remove(i);
        sources.add(at, source);
        if (index >= sources.size()) index = sources.size() - 1;
    }

    // --- Pick up after the TV stops the app mid-episode

    static final String STATE = "player-state";

    /** Saves what's playing (no source addresses), so the home can offer to pick up if the app is stopped. */
    static void persist(Context context, PlaybackSession session, long position) {
        if (context == null || session == null || session.entry == null || session.entry.video.isEmpty()) return;
        try {
            ContinueWatching.Entry e = ContinueWatching.Entry.parse(session.entry.json());
            e.position = position; e.updated = System.currentTimeMillis();
            context.getSharedPreferences(STATE, 0).edit().putString("interrupted", e.json().toString()).apply();
        } catch (Exception ignored) {}
    }

    /** The episode or movie that was playing when the TV stopped the app, or null. */
    static ContinueWatching.Entry interrupted(Context context) {
        if (current != null) return null;   // the player is still alive in this process
        try {
            String raw = context.getSharedPreferences(STATE, 0).getString("interrupted", null);
            if (raw == null) return null;
            ContinueWatching.Entry e = ContinueWatching.Entry.parse(new JSONObject(raw));
            // A day later it isn't "where you left off" anymore; Continue Watching still has it.
            if (e.video.isEmpty() || e.title.isEmpty() || System.currentTimeMillis() - e.updated > 24 * 3600000L) return null;
            return e;
        } catch (Exception ignored) { return null; }
    }

    static void clearInterrupted(Context context) {
        if (context != null) context.getSharedPreferences(STATE, 0).edit().remove("interrupted").apply();
    }
}
