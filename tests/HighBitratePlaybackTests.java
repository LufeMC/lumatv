package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import org.json.JSONObject;

/** Verify high-bitrate playback and automatic rejection of unsupported 4K codecs. */
public class HighBitratePlaybackTests extends TvTest {
    private PlayerActivity activity;
    private HomeActivity home;
    private int checks;
    private String lastPlayerState = "none";
    private Object value(String name)throws Exception{Field f=PlayerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private ExoPlayer video()throws Exception{return (ExoPlayer)value("player");}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,int seconds,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{
            ExoPlayer p = video();
            if (p != null) lastPlayerState = "state=" + p.getPlaybackState() + ", loading=" + p.isLoading()
                    + ", position=" + p.getCurrentPosition() + ", buffered=" + p.getBufferedPosition()
                    + ", video=" + p.getVideoFormat() + ", audio=" + p.getAudioFormat()
                    + ", video_disabled=" + p.getTrackSelectionParameters().disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    + ", groups=" + p.getCurrentTracks().getGroups().size();
            ok[0]=c.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();
        try{
            getUiAutomation();Ui.init(getTargetContext());File fixture=new File(getTargetContext().getExternalFilesDir(null),"high-bitrate-1080.mkv");
            report.put("avc_4k_supported",CodecSupport.device().avc4k).put("hevc_4k_supported",CodecSupport.device().hevc4k);
            if(!fixture.isFile())throw new AssertionError("The generated high-bitrate fixture is missing");
            home=(HomeActivity)startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AddonStreams.Source source=new AddonStreams.Source();source.url=android.net.Uri.fromFile(fixture).toString();source.filename="Generated.1080p.x264.24fps.mkv";source.quality=1080;source.addon="Generated fixture";source.videoSize=fixture.length();
            ContinueWatching.Entry entry=new ContinueWatching.Entry();entry.title="High-bitrate playback verification";entry.video="tt0000032";entry.imdb=entry.video;entry.duration=40000;
            ParallelSourceChecks.Result header = ParallelSourceChecks.inspectMedia(getTargetContext(), source, 40000);
            if (header.state == SourceProbe.FAILED) throw new AssertionError("Supported high-bitrate dimensions failed metadata checks: " + header.reason); checks++;
            AddonStreams.Source unsupportedAvc=new AddonStreams.Source();unsupportedAvc.url="https://example.invalid/4k-avc.mkv";unsupportedAvc.quality=2160;unsupportedAvc.unsupported=CodecSupport.unsupported("2160p x264",2160);
            AddonStreams.Source unsupportedHevc=new AddonStreams.Source();unsupportedHevc.url="https://example.invalid/4k-hevc.mkv";unsupportedHevc.quality=2160;unsupportedHevc.unsupported=CodecSupport.unsupported("2160p x265",2160);
            if(unsupportedAvc.unsupported==null||unsupportedHevc.unsupported==null)throw new AssertionError("This fixture targets a TV without 4K AVC and HEVC");checks++;
            PlaybackSession.current=new PlaybackSession(entry.video,entry.title,PlaybackPlan.automatic(java.util.Arrays.asList(unsupportedAvc,unsupportedHevc,source)),1080);PlaybackSession.current.entry=entry;PlaybackSession.current.expectedDuration=40000;
            if(PlaybackSession.current.sources.size()!=1||PlaybackSession.current.sources.get(0)!=source)throw new AssertionError("Unsupported 4K sources entered the automatic queue");checks++;
            activity=(PlayerActivity)startActivitySync(new Intent(getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->video()!=null&&(Boolean)value("sourceValidated")&&video().isPlaying(),25,"high-bitrate playback has verified video and audio");
            await(()->video().getVideoFormat()!=null&&video().getVideoFormat().width==1920&&video().getVideoFormat().height==1080,5,"the decoder receives the supported full-HD format");
            runOnMainSync(()->{try{video().setVolume(0);}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(3000);
            int[] dropped={0};long[] position={0};Display.Mode[] initial={null};
            runOnMainSync(()->{try{dropped[0]=video().getVideoDecoderCounters().droppedBufferCount;position[0]=video().getCurrentPosition();initial[0]=activity.getWindow().getDecorView().getDisplay().getMode();}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(20000);
            await(()->video().isPlaying()&&video().getCurrentPosition()-position[0]>=18000&&(Boolean)value("audioAdvancing"),5,"high-bitrate playback sustains video and audio for twenty seconds");
            int[] lost={0};runOnMainSync(()->{try{lost[0]=video().getVideoDecoderCounters().droppedBufferCount-dropped[0];}catch(Exception e){throw new RuntimeException(e);}});
            if(lost[0]>2)throw new AssertionError("Excessive dropped frames: "+lost[0]);checks++;
            Display display=activity.getWindow().getDecorView().getDisplay();int expected=PlayerActivity.pickDisplayMode(display.getMode(),display.getSupportedModes(),24);
            boolean supported=PlayerActivity.multiple(display.getMode().getRefreshRate(),24)||expected!=0;
            await(()->!supported||PlayerActivity.multiple(display.getMode().getRefreshRate(),24)||activity.getWindow().getAttributes().preferredDisplayModeId==expected,5,"the player requests a matching supported display mode");
            long rate=PlaybackPlan.estimateBitrate(source,40000);int back=PlaybackPlan.backBufferMs(rate);
            if(back*rate/8000>PlaybackPlan.TARGET_BUFFER_BYTES/4)throw new AssertionError("The backward buffer exceeds its encoded-data target");checks++;
            report.put("decoded_width",1920).put("decoded_height",1080).put("bitrate",rate).put("back_buffer_ms",back).put("dropped_frames",lost[0]).put("observed_ms",20000).put("display_refresh_hz",display.getMode().getRefreshRate()).put("matching_display_mode_supported",supported).put("requested_mode",activity.getWindow().getAttributes().preferredDisplayModeId);
            result.putString("stream","Passed "+checks+" high-bitrate and 4K fallback checks. "+report+"\n");
        }catch(Throwable e){
            String[] state={""};runOnMainSync(()->{try{ExoPlayer p=video();state[0]="; player="+(p==null?"none":p.getPlaybackState()+", playing="+p.isPlaying()+", format="+p.getVideoFormat())+"; rendered="+value("videoRendered")+"; audio="+value("audioAdvancing")+"; failures="+PlaybackSession.current.outcome.values();}catch(Exception ignored){}});
            result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+state[0]+"; notes="+PlaybackSession.current.failureNotes+"; last="+lastPlayerState+"; capabilities="+report+"\n");}
        finally{runOnMainSync(()->{if(activity!=null)activity.finish();if(home!=null)home.finish();});
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"high-bitrate-playback.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
