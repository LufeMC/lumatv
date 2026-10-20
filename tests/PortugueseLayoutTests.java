package io.freemytv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/** Check the longest Portuguese controls in the actual series and player layouts. */
public class PortugueseLayoutTests extends TvTest {
    private SourcesActivity title;
    private PlayerActivity player;
    private int checks;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void set(String name,Object value)throws Exception{Field f=PlayerActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(player,value);}
    private void call(String name,Class<?>[] types,Object...args)throws Exception{Method m=PlayerActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(player,args);}
    private interface Action{void run()throws Exception;}
    private void main(Action action){runOnMainSync(()->{try{action.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,String label)throws Exception{long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=c.ready();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}throw new AssertionError(label);}
    private void shot(String name)throws Exception{SystemClock.sleep(400);Bitmap b=getUiAutomation().takeScreenshot();File folder=new File(getTargetContext().getExternalFilesDir(null),"design-review");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();boolean language=Ui.en;
        try{
            Ui.en=false;Ui.init(getTargetContext());getUiAutomation();getTargetContext().getSharedPreferences("series-ui",0).edit().putInt("season:1418",10).putInt("episode:1418",1).commit();
            title=(SourcesActivity)startActivitySync(new Intent(getTargetContext(),SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title_id",1418).putExtra("title_kind","tv").putExtra("title_name","The Big Bang Theory"));
            await(()->!((java.util.List<?>)value(title,"episodeList")).isEmpty(),"Portuguese series layout loads");shot("title-portuguese");
            File fixture=new File(getTargetContext().getCacheDir(),"portuguese-layout.mkv");try(java.io.InputStream in=getContext().getAssets().open("verified.mkv")){java.nio.file.Files.copy(in,fixture.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            AddonStreams.Source source=new AddonStreams.Source();source.url=android.net.Uri.fromFile(fixture).toString();source.quality=360;source.name="Generated fixture";
            PlaybackSession.current=new PlaybackSession("tt0000033","Teste de reprodução",Collections.singletonList(source),360);
            player=(PlayerActivity)startActivitySync(new Intent(getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->value(player,"player")!=null&&(Boolean)value(player,"sourceValidated"),"Portuguese playback controls load");
            main(()->{ExoPlayer video=(ExoPlayer)value(player,"player");video.setVolume(0);SeriesEpisodes.Episode next=new SeriesEpisodes.Episode();next.season=10;next.number=2;next.name="The Military Miniaturization";next.minutes=22;set("nextEpisode",next);set("nextLoaded",true);((View)value(player,"nextEpisodeButton")).setVisibility(View.VISIBLE);call("showControls",new Class<?>[0]);});
            shot("player-controls-portuguese");
            await(()->((PlayerViews.Pill)value(player,"nextEpisodeButton")).label.getLineCount()==1&&((PlayerScreens.Controls)value(player,"overlay")).tracks.label.getLineCount()==1,"Portuguese control labels stay on one line");
            main(()->call("chooseTracks",new Class<?>[]{int.class},androidx.media3.common.C.TRACK_TYPE_AUDIO));shot("player-tracks-portuguese");sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            main(()->call("showUpNext",new Class<?>[]{boolean.class},false));shot("player-up-next-portuguese");
            await(()->((PlayerViews.Pill)value(player,"cancelAutoplay")).label.getLineCount()==1,"the Portuguese credits action stays on one line");
            result.putString("stream","Passed "+checks+" Portuguese layout checks. Saved four native captures.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{Ui.en=language;main(()->{if(player!=null)player.finish();if(title!=null)title.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
