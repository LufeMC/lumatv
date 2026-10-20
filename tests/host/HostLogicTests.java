package io.freemytv;

import java.util.*;
import org.json.*;

/** Run production parsing and state logic without an Android device. */
public class HostLogicTests {
    private static int checks;
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static AddonStreams.Source source(int number, int quality) {
        AddonStreams.Source s = new AddonStreams.Source(); s.url = "https://example.invalid/" + number;
        s.name = "Source " + number; s.detail = ""; s.addon = "Test"; s.quality = quality; return s;
    }
    public static void main(String[] args) throws Exception {
        SeekState seek = new SeekState(); seek.begin(60000, 120000, true); seek.move(1,0); seek.input(100);
        check(seek.target == 70000 && !seek.due(3099) && seek.due(3100), "seek confirms after three seconds");
        check(seek.finish(false) == 60000, "Back cancels a seek");
        seek.begin(115000,120000,false); seek.move(1,0); check(seek.target == 119000 && !seek.playing, "seek clamps and retains pause");
        check(seek.finish(true) == 119000, "confirmed position is retained");
        check(PlaybackValidation.shortPlaceholder(1200000,30000), "error clip cannot replace an episode");
        check(!PlaybackValidation.shortPlaceholder(1200000,1200000), "full runtime remains valid");
        check(!PlaybackValidation.shortPlaceholder(30000,30000), "short content remains valid");
        check(AddonStreams.otherEpisode("show.S02E03.mkv", "tt1:2:2"), "wrong episode is detected");
        check(AddonStreams.otherEpisode("show.S02E02E03.mkv", "tt1:2:2"), "combined episode is detected");
        check(!AddonStreams.otherEpisode("show.1920x1080.mkv", "tt1:2:2"), "resolution is not an episode tag");
        CodecSupport.Device device = new CodecSupport.Device(); device.hevc = true;
        check(CodecSupport.AV1.equals(CodecSupport.unsupported("AV1 1080p",1080,device)), "AV1 without a decoder is rejected");
        check(CodecSupport.unsupported("x264 DVDRip",1080,device) == null, "DVD label is not Dolby Vision");
        check(PlaybackPlan.backBufferMs(80_000_000) <= 1000, "high-bitrate back buffer stays bounded");
        check(PlaybackPlan.backBufferMs(2_000_000) == 15000, "low-bitrate rewind remains available");
        List<AddonStreams.Source> candidates = new ArrayList<>();
        for (int i=0;i<26;i++) candidates.add(source(i,i<13 ? 2160 : 1080));
        check(PlaybackPlan.automatic(candidates).size() == 12, "automatic source batch is bounded");
        PlaybackSession session = new PlaybackSession("tt1", "Test", PlaybackPlan.automatic(candidates), -1);
        session.updateSources(candidates,false); Set<String> first = new HashSet<>(); for (AddonStreams.Source s : session.sources) first.add(s.url);
        session.index = session.sources.size();
        check(session.hasMore() && session.queueMore(), "recovery can request another source batch");
        check(session.next() != null && !first.contains(session.sources.get(session.index).url), "next batch skips attempted addresses");
        check(session.queueMore(), "last partial batch remains available");
        check(!session.hasMore() && !session.queueMore(), "exhausted batches stop");
        SubtitleCues cues = SubtitleCues.parse("1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n");
        check(cues != null && cues.size()==2 && cues.at(1000)==0 && cues.at(2500)==-1, "SRT boundaries are correct");
        cues.offset=500; check(cues.at(1499)==-1 && cues.at(1500)==0, "positive subtitle offset moves text later");
        cues.scale=1.01; check(Math.abs(cues.toFile(cues.toMedia(10000))-10000)<=1, "subtitle timing conversion is reversible");
        check(SubtitleCues.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nLine\n").size()==1, "WebVTT is parsed");
        check(SubtitleCues.parse("invalid")==null, "invalid subtitle file is rejected");
        byte[] silence = new byte[2000]; check(!SubtitleSync.solve(cues,silence).confident, "silence cannot trigger subtitle correction");
        JSONObject response = new JSONObject("{\"imdb_id\":\"tt1\",\"media_type\":\"tv\",\"season\":1,\"episode\":2,\"intro\":{\"start_ms\":60000,\"end_ms\":90000,\"confidence\":0.9},\"outro\":{\"start_ms\":1150000,\"confidence\":0.9}}");
        IntroMarkers.Segments markers = IntroMarkers.parseSegments(response,"tt1",1,2);
        check(markers.intro != null && markers.credits(1200000)==1150000, "verified intro and credits are available");
        check(markers.credits(2400000)==-1, "early credits marker is rejected");
        check(IntroMarkers.parseSegments(response,"tt1",1,3)==null, "another episode cannot supply intro timing");
        IntroSkip intro = new IntroSkip(); intro.marker=markers.intro; intro.update(61000,1200000,true,1000);
        intro.update(62000,1200000,true,2000); long remaining=intro.remaining;
        intro.update(62000,1200000,false,3000); intro.update(62000,1200000,false,9000);
        check(intro.remaining==remaining, "pause stops intro countdown"); intro.keep(); check(!intro.visible, "Keep intro dismisses countdown");
        SeriesEpisodes.Episode episode = new SeriesEpisodes.Episode(); episode.season=2; episode.number=3; episode.name="A New Beginning"; episode.date="2020-01-02";
        JSONArray videos = new JSONArray().put(new JSONObject().put("id","tt1:1:13").put("title",episode.name).put("released","2020-01-02"));
        check(EpisodeMap.map("tt1",episode,videos).equals("tt1:1:13"), "matching identity corrects source numbering");
        videos.put(new JSONObject().put("id","tt1:4:2").put("title",episode.name).put("released",episode.date));
        check(EpisodeMap.map("tt1",episode,videos).equals("tt1:2:3"), "ambiguous episode identity is not guessed");
        check(EpisodeMap.map("tt1",episode,null).equals("tt1:2:3"), "missing metadata preserves numbering");
        JSONArray metadata = new JSONArray().put(new JSONObject().put("media_type","tv").put("id",12).put("name","Show").put("adult",false))
                .put(new JSONObject().put("media_type","tv").put("id",12).put("name","Duplicate"))
                .put(new JSONObject().put("media_type","person").put("id",13).put("name","Person"));
        check(TitleSearch.parse(metadata).size()==1, "title parser excludes people and duplicates");
        TitleSearch.Title obscure = new TitleSearch.Title(); obscure.name="Vision"; obscure.votes=1; obscure.popularity=1;
        TitleSearch.Title known = new TitleSearch.Title(); known.name="Vision Quest"; known.votes=5000; known.popularity=100;
        check(TitleSearch.rank(known,"vision")>TitleSearch.rank(obscure,"vision"), "known matching shows can outrank obscure exact names");
        check(TitleSearch.normalized("Série Á").equals("seriea"), "ranking handles accents");
        check(PlaybackMemory.stillWatchingDue(3,0,true) && !PlaybackMemory.stillWatchingDue(3,12,true), "still-watching prompt is limited to late hours");
        check(!PlaybackMemory.stillWatchingDue(2,1,true) && !PlaybackMemory.stillWatchingDue(3,1,false), "episode count and preference control the prompt");
        check(ContinueWatching.finished(1140000,1200000) && !ContinueWatching.finished(600000,1200000), "completion threshold preserves unfinished episodes");
        ContinueWatching.Entry local = new ContinueWatching.Entry(); local.title="Test show"; local.imdb="tt2"; local.video="tt2:1:2"; local.updated=1;
        ContinueWatching.Entry external = new ContinueWatching.Entry(); external.title="Test show"; external.pkg="com.netflix.ninja"; external.id="watch-next:2"; external.updated=2;
        check(ContinueWatching.merge(Collections.singletonList(local),Collections.singletonList(external)).size()==1, "one show has one Continue Watching card");
        check(ContinueWatching.merge(Collections.singletonList(local),Collections.singletonList(external),Collections.singleton(external.pkg)).get(0)==local, "disabled subscription loses priority");
        check(Watchdog.counts("io.freemytv.HomeActivity") && !Watchdog.counts("io.freemytv.PlayerActivity"), "player crashes do not count as launcher crashes");
        String sha="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(Updater.parse(new JSONObject().put("versionCode",1000).put("versionName","test").put("sha256",sha)).optLong("versionCode")==1000, "release manifest retains version");
        boolean rejected=false; try { Updater.parse(new JSONObject().put("versionCode",1000).put("sha256","bad")); } catch(Exception expected) { rejected=true; }
        check(rejected,"malformed update checksum is rejected");
        check(!Updater.allowed(new java.net.URL("https://github.com.evil.invalid/x.apk"),null),"update host lookalike is rejected");
        check(!Updater.allowed(new java.net.URL("http://github.com/x.apk"),null),"insecure update address is rejected");
        check(Updater.allowed(new java.net.URL("https://release-assets.githubusercontent.com/x.apk"),null),"official release file host is accepted");
        Pake.Party a=new Pake.Party(true,"4831","tv-a","tv-b"), b=new Pake.Party(false,"4831","tv-a","tv-b");
        check(Pake.valid(a.mine) && Pake.valid(b.mine) && a.finish(b.mine) && b.finish(a.mine),"pairing accepts valid group elements");
        check(Arrays.equals(a.sharedSecret(),b.sharedSecret()) && Arrays.equals(a.confirm("B"),b.confirm("B")),"matching PIN produces matching confirmed secret");
        Pake.Party wrong=new Pake.Party(true,"9999","tv-a","tv-b"), other=new Pake.Party(false,"4831","tv-a","tv-b");
        wrong.finish(other.mine);other.finish(wrong.mine);
        check(!Arrays.equals(wrong.confirm("B"),other.confirm("B")),"wrong PIN cannot confirm pairing");
        check(!Pake.valid(java.math.BigInteger.ONE) && !Pake.valid(java.math.BigInteger.ZERO),"trivial pairing elements are rejected");
        byte[] key=a.sharedSecret(), message="settings".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sealed=SyncCipher.seal(key,"a>b",message);
        check(Arrays.equals(message,SyncCipher.open(key,"a>b",sealed)),"sync message authenticates and decrypts");
        check(!Arrays.equals(sealed,SyncCipher.seal(key,"a>b",message)),"repeated sync messages use fresh nonces");
        rejected=false;try {SyncCipher.open(key,"b>a",sealed);}catch(Exception expected){rejected=true;}
        check(rejected,"opposite sync direction rejects replay");
        sealed[sealed.length-1]^=1;rejected=false;try {SyncCipher.open(key,"a>b",sealed);}catch(Exception expected){rejected=true;}
        check(rejected,"modified sync ciphertext is rejected");
        List<long[]> times=new ArrayList<>();List<String> dialogue=new ArrayList<>();Random random=new Random(7);
        byte[] speech=new byte[SubtitleSync.FRAMES];Arrays.fill(speech,0,3000,(byte)-1);
        long at=4000;while(at<290000){long length=800+random.nextInt(2600),gap=400+random.nextInt(4000);
            times.add(new long[]{at,at+length});dialogue.add("line");
            for(long f=(at+2300)/100;f<(at+2300+length)/100 && f<3000;f++)speech[(int)f]=1;at+=length+gap;}
        SubtitleSync.Result sync=SubtitleSync.solve(new SubtitleCues(times,dialogue),speech);
        check(sync.confident && Math.abs(sync.offset-2300)<=100,"generated dialogue recovers a 2.3-second subtitle offset");
        byte[] noise=new byte[SubtitleSync.FRAMES];for(int i=0;i<3000;i++)noise[i]=(byte)(random.nextBoolean()?1:-1);
        check(!SubtitleSync.solve(new SubtitleCues(times,dialogue),noise).confident,"unrelated audio does not move subtitles");
        System.out.println("Passed " + checks + " local logic checks.");
    }
}
