package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/** Verify automatic subtitle correction through the actual decoded-audio pipeline. */
public class SubtitlePipelineTests extends TvTest {
    private PlayerActivity activity;
    private HomeActivity home;
    private int checks;
    private Object value(String name)throws Exception{Field f=PlayerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private ExoPlayer video()throws Exception{return (ExoPlayer)value("player");}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,int seconds,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<end){boolean[] pass={false};Throwable[] error={null};runOnMainSync(()->{try{pass[0]=c.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(pass[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private void call(String method,Class<?>[] types,Object...args)throws Exception{Method m=PlayerActivity.class.getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(activity,args);}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            getUiAutomation();Ui.init(getTargetContext());
            getTargetContext().getSharedPreferences(Prefs.PLAYBACK,0).edit().putBoolean(Prefs.AUTO_SYNC,true).commit();
            File folder=new File(getTargetContext().getCacheDir(),"subtitle-pipeline");folder.mkdirs();
            for(String name:new String[]{"sync-pattern.mkv","sync-offset.srt"})try(java.io.InputStream in=getContext().getAssets().open(name)){java.nio.file.Files.copy(in,new File(folder,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            home=(HomeActivity)startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AddonStreams.Source source=new AddonStreams.Source();source.url=android.net.Uri.fromFile(new File(folder,"sync-pattern.mkv")).toString();source.filename="Sync.Pattern.Test.mkv";source.addon="Generated fixture";source.quality=160;
            ContinueWatching.Entry entry=new ContinueWatching.Entry();entry.title="Subtitle synchronization test";entry.video="tt0000031";entry.imdb="tt0000031";entry.kind="movie";
            PlaybackSession.current=new PlaybackSession(entry.video,entry.title,Collections.singletonList(source),160);PlaybackSession.current.entry=entry;
            activity=(PlayerActivity)startActivitySync(new Intent(getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->video()!=null&&(Boolean)value("sourceValidated")&&video().isPlaying(),20,"generated video and audio are verified");
            runOnMainSync(()->{try{
                video().setVolume(0);video().seekTo(0);video().setPlaybackSpeed(3);
                AddonSubtitles.Subtitle subtitle=new AddonSubtitles.Subtitle();subtitle.url=android.net.Uri.fromFile(new File(folder,"sync-offset.srt")).toString();subtitle.language="en";subtitle.name="Known 2.3-second offset";
                call("useExternal",new Class<?>[]{AddonSubtitles.Subtitle.class,boolean.class},subtitle,false);
            }catch(Exception e){throw new RuntimeException(e);}});
            await(()->value("externalCues")!=null&&((SubtitleSync)value("sync")).active(),10,"subtitle loading activates decoded-audio collection");
            long start=SystemClock.elapsedRealtime();
            await(()->Math.abs(((SubtitleCues)value("externalCues")).offset+2300)<=200,100,"automatic correction removes the known 2.3-second timing error");
            long elapsed=SystemClock.elapsedRealtime()-start;
            await(()->((View)value("synced")).isShown(),3,"the automatic correction offers Undo");
            await(()->video().isPlaying()&&(Boolean)value("audioAdvancing"),3,"correction preserves video and advancing audio");
            long offset=((SubtitleCues)value("externalCues")).offset;
            runOnMainSync(()->{try{call("undoSync",new Class<?>[0]);}catch(Exception e){throw new RuntimeException(e);}});
            await(()->((SubtitleCues)value("externalCues")).offset==0,3,"Undo restores the original cue timing");
            result.putString("stream","Passed "+checks+" decoded-audio subtitle checks. Correction: "+offset+" ms; collection and correction: "+elapsed+" ms.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{runOnMainSync(()->{if(activity!=null)activity.finish();if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
