package io.freemytv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The LumaTV playback data without the player: source order (codec support, episode tags, what worked per show),
 * buffer sizing, subtitle parsing, drift and dialogue sync, IntroDB recap and credits, Up next and watched history,
 * removed cards, one card per show, Stremio library items, the playback log and interrupted sessions.
 * Writes only to "test-lumatv-" copies of the settings and a temporary folder.
 */
public class PlaybackDataTests extends TvTest {
    private int checks;
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv")) start(); }

    private AddonStreams.Source source(String name, int quality, String addon) {
        AddonStreams.Source s = new AddonStreams.Source(); s.url = "https://example.invalid/" + name; s.name = name; s.detail = "";
        s.addon = addon; s.quality = quality; return s;
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        java.io.File folder = new java.io.File(getTargetContext().getCacheDir(), "test-lumatv-files");
        android.content.Context isolated = new android.content.ContextWrapper(getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("test-lumatv-" + name, mode); }
            @Override public java.io.File getFilesDir() { folder.mkdirs(); return folder; }
        };
        try {
            Ui.init(getTargetContext());
            // Episode tags in file names (reorder only), never resolutions.
            check(AddonStreams.otherEpisode("Show.S01E03.1080p.WEB.mkv", "tt1:1:2"), "another episode in the file name");
            check(!AddonStreams.otherEpisode("Show.S01E02.1080p.WEB.mkv", "tt1:1:2"), "the right episode");
            check(!AddonStreams.otherEpisode("show s1e2 720p", "tt1:1:2"), "short tag");
            check(AddonStreams.otherEpisode("Show.S01E02E03.mkv", "tt1:1:2"), "two episodes in one file");
            check(AddonStreams.otherEpisode("Show.S01E02-E03.mkv", "tt1:1:2"), "two episodes with a dash");
            check(AddonStreams.otherEpisode("Show 1x03 HDTV", "tt1:1:2"), "1x03 style tag");
            check(!AddonStreams.otherEpisode("Show 1920x1080 x264", "tt1:1:2"), "a resolution is not an episode");
            check(!AddonStreams.otherEpisode("Show complete season 1", "tt1:1:2"), "no tag, no change");
            check(!AddonStreams.otherEpisode("Movie.2019.S01E05.mkv", "tt1"), "movies are never reordered");

            // Codec support against a TV without Dolby Vision, AV1 or Main10.
            CodecSupport.Device plain = new CodecSupport.Device(); plain.hevc = true; plain.hevc4k = true;
            check(CodecSupport.DOLBY_VISION.equals(CodecSupport.unsupported("Movie 2160p DV WEB-DL", 2160, plain)), "Dolby Vision without a decoder");
            check(CodecSupport.DOLBY_VISION.equals(CodecSupport.unsupported("Movie 2160p DV HDR10 HEVC", 2160, plain)), "DV with HDR10 needs Main10");
            check(CodecSupport.AV1.equals(CodecSupport.unsupported("Movie 1080p AV1", 1080, plain)), "AV1 without a decoder");
            check(CodecSupport.HEVC_10.equals(CodecSupport.unsupported("Movie 1080p x265 10bit", 1080, plain)), "10-bit HEVC without Main10");
            check(CodecSupport.unsupported("Movie 1080p x264 DVDRip", 1080, plain) == null, "H.264 plays; DVDRip is not DV");
            check(CodecSupport.AVC.equals(CodecSupport.unsupported("Movie 2160p x264", 2160, plain)), "4K AVC needs a matching decoder");
            plain.avc4k = true;
            CodecSupport.Device no4k = new CodecSupport.Device(); no4k.hevc = true;
            check("4K".equals(CodecSupport.unsupported("2160p WEB", 2160, no4k)), "unknown-codec 4K is rejected when no 4K decoder exists");
            check(CodecSupport.unsupported("Movie 2160p x264", 2160, plain) == null, "a supported 4K AVC source remains eligible");
            CodecSupport.Device rich = new CodecSupport.Device(); rich.hevc = rich.hevcMain10 = rich.hevc4k = rich.av1 = true;
            check(CodecSupport.unsupported("Movie 2160p DV HDR10 HEVC", 2160, rich) == null, "DV profile 8 falls back to HDR10");
            check(CodecSupport.AV1.equals(CodecSupport.unsupported("Movie 2160p AV1", 2160, rich)), "4K AV1 needs a hardware decoder");
            check(CodecSupport.codec("Show.S01E01.1080p.x265.10bit").equals(CodecSupport.HEVC_10) && CodecSupport.codec("x264").equals(CodecSupport.AVC), "codec labels");

            // The automatic queue.
            List<AddonStreams.Source> input = new ArrayList<>();
            AddonStreams.Source dv = source("dv", 2160, "A"); dv.unsupported = CodecSupport.DOLBY_VISION; input.add(dv);
            AddonStreams.Source wrong = source("wrong", 1080, "A"); wrong.otherEpisode = true; input.add(wrong);
            input.add(source("hd1", 1080, "A")); input.add(source("hd2", 1080, "B")); input.add(source("sd", 720, "B"));
            List<AddonStreams.Source> plan = PlaybackPlan.automatic(input);
            check(!plan.contains(dv), "skip what the TV can't decode while others exist");
            check(plan.get(plan.size() - 1) == wrong && plan.size() == 4, "another episode goes to the back, still queued");
            List<AddonStreams.Source> only = PlaybackPlan.automatic(Collections.singletonList(dv));
            check(only.size() == 1, "keep an unsupported source when it is the only one");
            List<AddonStreams.Source> providers = new ArrayList<>();
            for (int i = 0; i < 15; i++) providers.add(source("provider-a-" + i, 1080, "A"));
            providers.add(source("provider-b", 1080, "B")); providers.add(source("lower", 720, "B"));
            List<AddonStreams.Source> diverse = PlaybackPlan.automatic(providers);
            check(diverse.get(0).addon.equals("A") && diverse.get(1).addon.equals("B") && diverse.get(1).quality == 1080,
                    "one provider cannot occupy the start of a quality group");
            // What worked for this show comes first.
            PlaybackMemory.init(getTargetContext());
            AddonStreams.Source remembered = source("binge", 720, "B"); remembered.bingeGroup = "B|720p|group"; remembered.videoId = "tt9999901:1:2";
            for (AddonStreams.Source s : input) s.videoId = "tt9999901:1:2";
            PlaybackMemory.rememberSource(getTargetContext(), "tt9999901:1:1", remembered);
            SystemClock.sleep(100);
            List<AddonStreams.Source> withMemory = new ArrayList<>(input); withMemory.add(remembered);
            plan = PlaybackPlan.automatic(withMemory);
            check(plan.get(0).quality == 1080 && plan.indexOf(remembered) < plan.indexOf(input.get(4)), "quality comes first and the remembered release leads its quality");
            AddonStreams.Source hdB = null; for (AddonStreams.Source s : plan) if (s.name.equals("hd2")) hdB = s;
            check(plan.indexOf(hdB) < plan.indexOf(input.get(2)), "the same add-on first within a resolution");
            getTargetContext().getSharedPreferences(PlaybackMemory.FILE, 0).edit().remove("source:tt9999901").commit();

            // Back buffer from the bitrate (TP3): never more than a quarter of the 20 MB limit.
            check(PlaybackPlan.backBufferMs(0) == 15000, "unknown bitrate keeps 15 s");
            check(PlaybackPlan.backBufferMs(5_000_000) == 8388, "5 Mbps keeps about 8 s");
            check(PlaybackPlan.backBufferMs(40_000_000) == 1048, "a 40 Mbps remux keeps about 1 s");
            check(PlaybackPlan.backBufferMs(80_000_000) == 524, "80 Mbps stays within the rewind memory limit");
            for (long rate = 1_000_000; rate <= 200_000_000; rate += 1_000_000)
                check(PlaybackPlan.backBufferMs(rate) * rate / 8000 <= PlaybackPlan.TARGET_BUFFER_BYTES / 4, "quarter of the limit at " + rate);
            AddonStreams.Source sized = source("sized", 2160, "A"); sized.videoSize = 15_000_000_000L;
            check(PlaybackPlan.estimateBitrate(sized, 2 * 3600_000L) == 16_666_666L, "bitrate from size and runtime");
            sized.videoSize = 275_000_000L;
            check(PlaybackPlan.estimateBitrate(sized, 40000) == 55_000_000L, "short known clips use their actual bitrate");
            sized.videoSize = Long.MAX_VALUE;
            check(PlaybackPlan.estimateBitrate(sized, 1) == Long.MAX_VALUE, "an excessive size cannot overflow into a low bitrate");
            check(MediaCache.limit(10L << 30) == MediaCache.LARGE && MediaCache.limit(1L << 30) == MediaCache.SMALL, "disk cache size");

            // Subtitle files.
            String srt = "1\n00:00:01,000 --> 00:00:02,500\n<i>Hello</i> there\n\n2\n00:00:03,000 --> 00:00:04,000\n{\\an8}Second\nline\n\n";
            SubtitleCues cues = SubtitleCues.parse(srt);
            check(SubtitleCues.parse("1\n00:00:00,000 --> 01:00:00,000\nSomething went wrong, please try again. Contact support@subtito.com if this issue persists.\n") == null,
                    "the reported subtitle-service error cannot become a subtitle");
            check(SubtitleCues.parse("1\n00:00:01,000 --> 00:00:03,000\nSomething went wrong, please try again.\n") != null,
                    "ordinary dialogue remains valid");
            check(cues != null && cues.size() == 2 && cues.text[0].equals("Hello there") && cues.text[1].equals("Second\nline"), "SRT without tags");
            List<String> lines = new ArrayList<>();
            cues.visible(1500, lines); check(lines.size() == 1 && lines.get(0).equals("Hello there"), "cue showing");
            cues.visible(2700, lines); check(lines.isEmpty(), "gap between cues");
            check(cues.nextChange(1500) == 2500 && cues.nextChange(2700) == 3000, "next change");
            cues.offset = 1000; cues.visible(2200, lines); check(lines.size() == 1, "offset moves cues later");
            cues.offset = 0; cues.scale = 2; check(cues.toMedia(1000) == 2000 && cues.toFile(2000) == 1000, "scale");
            SubtitleCues vtt = SubtitleCues.parse("WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nOne\n\n01:00:00.000 --> 01:00:01.500\n<v Bob>Two</v>\n");
            check(vtt != null && vtt.size() == 2 && vtt.start[1] == 3600000 && vtt.text[1].equals("Two"), "WebVTT");
            String ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                    + "Dialogue: 0,0:00:05.00,0:00:06.50,Default,,0,0,0,,{\\i1}Hi,{\\b1} you\\Nthere\n";
            SubtitleCues a = SubtitleCues.parse(ass);
            check(a != null && a.size() == 1 && a.start[0] == 5000 && a.end[0] == 6500 && a.text[0].equals("Hi, you\nthere"), "ASS without styling");
            check(SubtitleCues.decode(new byte[]{'C', 'a', 'f', (byte) 0xE9}).equals("Café"), "Windows-1252 subtitles");
            check(SubtitleCues.decode("Olá".getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals("Olá"), "UTF-8 subtitles");
            check(Math.abs(SubtitleCues.driftScale(2_700_000, 2_600_000) - 1 / SubtitleCues.PAL) < 1e-9, "lines past the end: speed fix");
            check(SubtitleCues.driftScale(2_400_000, 2_600_000) == 1, "lines ending early are normal");

            // Dialogue sync on generated speech: subtitles 2.3 s early.
            List<long[]> times = new ArrayList<>(); List<String> texts = new ArrayList<>();
            java.util.Random random = new java.util.Random(7);
            byte[] speech = new byte[SubtitleSync.FRAMES];
            Arrays.fill(speech, 0, 3000, (byte) -1);
            long t = 4000;
            while (t < 290000) {
                long length = 800 + random.nextInt(2600), gap = 400 + random.nextInt(4000);
                times.add(new long[]{t, t + length}); texts.add("line");
                for (long f = (t + 2300) / 100; f < (t + 2300 + length) / 100 && f < 3000; f++) speech[(int) f] = 1;
                t += length + gap;
            }
            SubtitleCues spoken = new SubtitleCues(times, texts);
            SubtitleSync.Result sync = SubtitleSync.solve(spoken, speech);
            check(sync.confident && Math.abs(sync.offset - 2300) <= 100 && sync.scale == 1, "finds a 2.3 s shift: " + sync.offset + " " + sync.score + "/" + sync.runnerUp);
            byte[] noise = new byte[SubtitleSync.FRAMES];
            for (int i = 0; i < 3000; i++) noise[i] = (byte) (random.nextBoolean() ? 1 : -1);
            check(!SubtitleSync.solve(spoken, noise).confident, "no shift without a clear match");
            byte[] quiet = new byte[SubtitleSync.FRAMES];
            check(!SubtitleSync.solve(spoken, quiet).confident, "no shift without audio");

            // Subtitle ranking: made for this file first.
            AddonSubtitles.Subtitle plainSub = new AddonSubtitles.Subtitle(); plainSub.url = "https://s.invalid/1"; plainSub.language = "pt"; plainSub.release = "other.release.720p";
            AddonSubtitles.Subtitle sameRelease = new AddonSubtitles.Subtitle(); sameRelease.url = "https://s.invalid/2"; sameRelease.language = "pt"; sameRelease.release = "show.s01e02.1080p.web-dl.ddp5.1.ntb";
            AddonSubtitles.Subtitle hash = new AddonSubtitles.Subtitle(); hash.url = "https://s.invalid/3"; hash.language = "pt"; hash.hashMatch = true; hash.order = 5;
            AddonStreams.Source file = source("file", 1080, "A"); file.filename = "Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTB.mkv";
            List<AddonSubtitles.Subtitle> ranked = new ArrayList<>(Arrays.asList(plainSub, sameRelease, hash));
            AddonSubtitles.rank(ranked, file);
            check(ranked.get(0) == hash && ranked.get(1) == sameRelease && sameRelease.matched, "hash match, then same release");
            file.videoId = "tt9999901:1:2"; hash.release = "Show.S01E03.1080p.WEB-DL.NTB.srt";
            AddonSubtitles.rank(ranked, file);
            check(hash.otherEpisode && ranked.get(ranked.size() - 1) == hash,
                    "a subtitle labeled for another episode cannot lead automatic selection");
            check(AddonSubtitles.sameLanguage("pt-BR", "por") && AddonSubtitles.sameLanguage("eng", "en") && !AddonSubtitles.sameLanguage("und", "und"), "language codes");
            JSONObject flagged = new JSONObject().put("url", "https://s.invalid/sub.srt").put("lang", "pob").put("hashMatch", true);
            check(AddonSubtitles.parse(flagged, "OpenSubtitles").matched, "add-on hash flag");

            // IntroDB recap and credits.
            JSONObject segments = new JSONObject("{\"imdb_id\":\"tt0898266\",\"media_type\":\"tv\",\"season\":9,\"episode\":24,"
                    + "\"intro\":{\"start_ms\":60000,\"end_ms\":90000,\"confidence\":0.9},\"recap\":{\"start_ms\":0,\"end_ms\":35000,\"confidence\":0.95},"
                    + "\"outro\":{\"start_ms\":1150000,\"end_ms\":1200000,\"confidence\":0.9}}");
            IntroMarkers.Segments s = IntroMarkers.parseSegments(segments, "tt0898266", 9, 24);
            check(s.intro != null && s.recap != null && s.recap.end == 35000, "recap segment");
            check(s.credits(1200000) == 1150000, "credits in the last 20%");
            check(s.credits(2400000) == -1, "credits too early for this runtime are ignored");
            segments.put("no_recap", true); segments.getJSONObject("outro").put("confidence", 0.5);
            s = IntroMarkers.parseSegments(segments, "tt0898266", 9, 24);
            check(s.recap == null && s.credits(1200000) == -1, "no recap; uncertain credits ignored");

            // Up next, watched history, removed cards.
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().commit();
            check(ContinueWatching.finished(1140000, 1200000) && ContinueWatching.finished(1090000, 1200000) && !ContinueWatching.finished(1000000, 1200000), "95% or 2 minutes left");
            check(!ContinueWatching.finished(100000, 300000), "short videos need 95%");
            ContinueWatching.Entry episode = new ContinueWatching.Entry();
            episode.id = "local:tt9999902:1:4"; episode.video = "tt9999902:1:4"; episode.imdb = "tt9999902"; episode.title = "Test show";
            episode.kind = "tv"; episode.season = 1; episode.episode = 4; episode.subtitle = "Four";
            ContinueWatching.record(isolated, episode, 600000, 1200000, false);
            SeriesEpisodes.Episode following = new SeriesEpisodes.Episode(); following.season = 1; following.number = 5; following.name = "Five"; following.minutes = 21;
            ContinueWatching.Entry card = ContinueWatching.finished(isolated, episode, following);
            List<ContinueWatching.Entry> row = ContinueWatching.cached(isolated);
            check(row.size() == 1 && row.get(0).upNext && row.get(0).video.equals("tt9999902:1:5") && row.get(0).episodeName.equals("Five"), "the card becomes Up next");
            check(card.detail().equals(Ui.tr("A seguir · T1 E5", "Up next · S1 E5")), "Up next label");
            check(ContinueWatching.Entry.parse(card.json()).upNext, "Up next is saved");
            check(ContinueWatching.watched(isolated, "tt9999902").contains("1:4"), "the episode counts as watched");
            ContinueWatching.markWatched(isolated, episode, false);
            check(!ContinueWatching.watched(isolated, "tt9999902").contains("1:4"), "unmark watched");
            check(ContinueWatching.internalIntent(isolated, card).getLongExtra("resume_position", -1) == 0, "Up next starts at the beginning");
            ContinueWatching.Entry last = ContinueWatching.Entry.parse(episode.json()); last.video = "tt9999902:2:1"; last.season = 2; last.episode = 1;
            ContinueWatching.finished(isolated, last, null);
            check(ContinueWatching.cached(isolated).isEmpty(), "no next episode: the card goes away");
            ContinueWatching.Entry movie = new ContinueWatching.Entry(); movie.id = "local:tt9999903"; movie.video = "tt9999903"; movie.imdb = "tt9999903"; movie.title = "Test movie";
            ContinueWatching.record(isolated, movie, 600000, 6000000, false);
            ContinueWatching.Entry cloud = ContinueWatching.Entry.parse(movie.json()); cloud.id = "stremio:tt9999903"; cloud.updated = movie.updated - 60000;
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().putString("stremio", new JSONArray().put(cloud.json()).toString()).commit();
            ContinueWatching.remove(isolated, movie);
            check(ContinueWatching.cached(isolated).isEmpty(), "a removed card doesn't come back from the library");
            SystemClock.sleep(5);
            ContinueWatching.record(isolated, movie, 700000, 6000000, false);
            check(ContinueWatching.cached(isolated).size() == 1, "watching again brings it back");
            isolated.getSharedPreferences(ContinueWatching.PREFS, 0).edit().clear().commit();

            // One card per show across services.
            ContinueWatching.Entry stremio = new ContinueWatching.Entry(); stremio.imdb = "tt9999904"; stremio.video = "tt9999904:1:1"; stremio.title = "The Diplomat"; stremio.updated = 100;
            ContinueWatching.Entry netflix = new ContinueWatching.Entry(); netflix.pkg = "com.netflix.ninja"; netflix.id = "tv:1"; netflix.title = "The Diplomat"; netflix.updated = 200;
            List<ContinueWatching.Entry> merged = ContinueWatching.merge(Collections.singletonList(stremio), Collections.singletonList(netflix));
            check(merged.size() == 1 && merged.get(0) == netflix, "one card per show, the most recent");
            merged = ContinueWatching.merge(Collections.singletonList(stremio), Collections.singletonList(netflix), Collections.singleton("com.netflix.ninja"));
            check(merged.size() == 1 && merged.get(0) == stremio, "a service the viewer doesn't pay for loses");

            // Stremio library items (stremio-core LibraryItem shapes).
            JSONObject item = LibrarySync.item("tt9999905", "tv", "Test show", "", false);
            check(item.getBoolean("removed") && item.getBoolean("temp") && item.getString("type").equals("series"), "played-only items are temporary");
            LibrarySync.progress(item, "tt9999905:1:2", 300000, 1200000, 290000, false, null);
            JSONObject state = item.getJSONObject("state");
            check(state.getLong("timeOffset") == 300000 && state.getString("video_id").equals("tt9999905:1:2") && state.getLong("timeWatched") == 290000, "progress fields");
            LibrarySync.progress(item, "tt9999905:1:2", 0, 1200000, 900000, true, "tt9999905:1:3");
            check(state.getString("video_id").equals("tt9999905:1:3") && state.getLong("timeOffset") == 1 && state.getInt("timesWatched") == 1, "finishing moves on to the next episode");
            check(ContinueWatching.stremio(new JSONArray().put(item.put("name", "Test show"))).get(0).upNext, "Stremio's next-episode pointer reads as Up next");
            check(!ContinueWatching.inWatchlist(isolated, "tt9999906"), "not saved yet");
            ContinueWatching.setWatchlist(isolated, "tt9999906", "movie", "Saved movie", "", true);
            check(ContinueWatching.inWatchlist(isolated, "tt9999906"), "saved to the watchlist");
            check(ContinueWatching.watchlist(isolated).size() == 1 && ContinueWatching.watchlist(isolated).get(0).title.equals("Saved movie"), "watchlist entries");
            ContinueWatching.setWatchlist(isolated, "tt9999906", "movie", "Saved movie", "", false);
            check(!ContinueWatching.inWatchlist(isolated, "tt9999906") && ContinueWatching.watchlist(isolated).isEmpty(), "removed from the watchlist");

            // The playback log keeps no addresses or keys.
            String scrubbed = PlaybackLog.scrub("Trying https://real-debrid.example/d/ABCDEF?token=secret123 key=abcdefghijklmnopqrstuvwxyz0123");
            check(!scrubbed.contains("http") && !scrubbed.contains("secret") && !scrubbed.contains("abcdefghijklmnopqrstuvwxyz"), "log without addresses or keys: " + scrubbed);

            // Interrupted session.
            PlaybackSession.current = null;
            PlaybackSession session = new PlaybackSession("tt9999902:1:4", "Test", Collections.singletonList(source("a", 1080, "A")), -1);
            session.entry = episode;
            PlaybackSession.persist(isolated, session, 420000);
            ContinueWatching.Entry interrupted = PlaybackSession.interrupted(isolated);
            check(interrupted != null && interrupted.video.equals("tt9999902:1:4") && interrupted.position == 420000, "pick up where you left off");
            PlaybackSession.current = session;
            check(PlaybackSession.interrupted(isolated) == null, "not while the player is alive");
            PlaybackSession.current = null;
            PlaybackSession.clearInterrupted(isolated);
            check(PlaybackSession.interrupted(isolated) == null, "cleared");
            AddonStreams.Source chosen = source("chosen", 720, "B");
            session.next(); session.choose(chosen);
            check(session.next() == chosen, "a chosen source plays next");

            // Smaller rules.
            check(PlaybackMemory.stillWatchingDue(3, 0, true) && !PlaybackMemory.stillWatchingDue(3, 22, true) && !PlaybackMemory.stillWatchingDue(2, 1, true)
                    && !PlaybackMemory.stillWatchingDue(5, 1, false), "Still watching after 3 in a row between 00:00 and 05:00");
            check(PlayerActivity.multiple(24f, 23.976f) && PlayerActivity.multiple(48f, 24f) && !PlayerActivity.multiple(60f, 23.976f) && PlayerActivity.multiple(50f, 25f), "refresh rate multiples");
            QuickPanel.set(QuickPanel.EPISODE);
            check(QuickPanel.consumeEndOfEpisode() && !QuickPanel.consumeEndOfEpisode(), "End of this episode stops once");
            output.putString("stream", "Passed " + checks + " playback data checks.\n");
        } catch (Throwable failure) {
            output.putString("stream", "FAILED after " + checks + " checks: " + failure.getClass().getSimpleName() + (failure.getMessage() != null ? ": " + failure.getMessage() : "") + "\n");
        } finally {
            try {
                for (String name : new String[]{ContinueWatching.PREFS, PlaybackSession.STATE})
                    isolated.getSharedPreferences(name, 0).edit().clear().commit();
                java.io.File[] files = folder.listFiles(); if (files != null) for (java.io.File f : files) f.delete(); folder.delete();
            } catch (Exception ignored) {}
            finish(output.getString("stream", "").startsWith("Passed") ? -1 : 0, output);
        }
    }
    private static final class SystemClock { static void sleep(long ms) { android.os.SystemClock.sleep(ms); } }
}
