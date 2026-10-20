package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.cache.SimpleCache;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import org.json.JSONObject;

/** Verify actual next-episode preparation and automatic playback with the authorized account. */
public class LiveBingeTests extends TvTest {
    private SourcesActivity title;
    private PlayerActivity player;
    private final ArrayList<PlayerActivity> opened=new ArrayList<>();
    private int checks;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private ExoPlayer video()throws Exception{return (ExoPlayer)value(player,"player");}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,int seconds,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=c.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private long cached()throws Exception{
        Field field=PlaybackPrefetch.class.getDeclaredField("preparedSource");field.setAccessible(true);AddonStreams.Source source=(AddonStreams.Source)field.get(null);
        if(source==null)return 0;
        Method method=MediaCache.class.getDeclaredMethod("cache",android.content.Context.class);method.setAccessible(true);SimpleCache cache=(SimpleCache)method.invoke(null,getTargetContext());
        return cache.getCachedBytes(MediaCache.key(source),0,PlaybackPrefetch.FIRST_BYTES);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject record=new JSONObject();ActivityMonitor next=null;
        try{
            getUiAutomation();Ui.init(getTargetContext());getTargetContext().getSharedPreferences(Prefs.PLAYBACK,0).edit().putBoolean(Prefs.AUTOPLAY,true).putBoolean(Prefs.STILL_WATCHING,false).commit();
            ActivityMonitor first=addMonitor(PlayerActivity.class.getName(),null,false);
            title=(SourcesActivity)startActivitySync(new Intent(getTargetContext(),SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title_id",1418).putExtra("title_kind","tv").putExtra("title_name","The Big Bang Theory")
                .putExtra("imdb","tt0898266").putExtra("resume_video","tt0898266:9:22").putExtra("resume_position",0L).putExtra("resume_duration",1200000L).putExtra("auto_play",true));
            player=(PlayerActivity)waitForMonitorWithTimeout(first,40000);removeMonitor(first);if(player==null)throw new AssertionError("No first episode player");opened.add(player);
            await(()->video()!=null&&(Boolean)value(player,"sourceValidated")&&video().isPlaying(),75,"the first episode has verified video and audio");
            await(()->value(player,"nextEpisode")!=null,25,"the exact next episode metadata loads");
            SeriesEpisodes.Episode episode=(SeriesEpisodes.Episode)value(player,"nextEpisode");String expected=episode.id("tt0898266");
            next=addMonitor(PlayerActivity.class.getName(),null,false);
            runOnMainSync(()->{try{video().setVolume(0);video().setPlaybackSpeed(2);video().seekTo(video().getDuration()-125000);}catch(Exception e){throw new RuntimeException(e);}});
            long preparedAt=SystemClock.elapsedRealtime();
            await(()->PlaybackPrefetch.prepared(expected),15,"playback starts preparing the exact next episode before the end");
            await(()->cached()>=PlaybackPrefetch.FIRST_BYTES,35,"one MiB of the next source is cached while the current episode plays");
            record.put("prefetch_ms",SystemClock.elapsedRealtime()-preparedAt).put("cached_bytes",cached()).put("expected_next",expected);
            PlayerActivity second=(PlayerActivity)waitForMonitorWithTimeout(next,100000);removeMonitor(next);next=null;
            if(second==null)throw new AssertionError("Automatic next episode did not open");checks++;opened.add(second);player=second;
            await(()->PlaybackSession.current!=null&&PlaybackSession.current.videoId.equals(expected),5,"automatic playback requests the exact following episode");
            await(()->video()!=null&&(Boolean)value(player,"sourceValidated")&&video().isPlaying(),75,"the next episode has verified video and audio");
            runOnMainSync(()->{try{video().setVolume(0);}catch(Exception e){throw new RuntimeException(e);}});
            long[] before={0};runOnMainSync(()->{try{before[0]=video().getCurrentPosition();}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(20000);
            await(()->video().isPlaying()&&video().getCurrentPosition()-before[0]>=18000&&(Boolean)value(player,"audioAdvancing"),5,"the next episode sustains video and advancing audio");
            record.put("next_episode_observed_ms",20000).put("checks",checks);
            result.putString("stream","Passed "+checks+" live binge checks. "+record+"\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{if(next!=null)removeMonitor(next);runOnMainSync(()->{for(PlayerActivity item:opened)item.finish();if(title!=null)title.finish();});
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"live-binge.json"))){out.write(record.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
