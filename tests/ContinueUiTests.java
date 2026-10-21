package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Verify the Home resume chip and its episode arguments with isolated progress. */
public class ContinueUiTests extends TvTest {
    private Activity home;
    private HomeView view;
    private int checks;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition condition,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};Throwable[] error={null};runOnMainSync(()->{try{ready[0]=condition.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ready[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private void key(int code){runOnMainSync(()->{long t=SystemClock.uptimeMillis();home.dispatchKeyEvent(new KeyEvent(t,t,0,code,0));home.dispatchKeyEvent(new KeyEvent(t,t,1,code,0));});SystemClock.sleep(180);}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();AtomicReference<Intent> resumed=new AtomicReference<>();
        ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
            if(intent.getComponent()!=null&&intent.getComponent().getClassName().equals(SourcesActivity.class.getName())){resumed.set(new Intent(intent));return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;
        }};
        try{
            getUiAutomation();Ui.init(getTargetContext());
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS,0).edit().clear().putBoolean("migrated",true).putLong("cloud-at",System.currentTimeMillis()).commit();
            ContinueWatching.Entry entry=new ContinueWatching.Entry();entry.title="Brooklyn Nine-Nine";entry.imdb="tt2467372";entry.tmdb=48891;entry.kind="tv";
            entry.season=2;entry.episode=3;entry.video="tt2467372:2:3";entry.id="test-resume";entry.art="https://image.tmdb.org/t/p/w780"+TitleSearch.get("/tv/48891").optString("backdrop_path");
            ContinueWatching.record(getTargetContext(),entry,90000,1260000,false);
            home=startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            view=(HomeView)value(home,"home");
            await(()->!((List<?>)value(view,"entries")).isEmpty()&&((ContinueWatching.Entry)((List<?>)value(view,"entries")).get(0)).video.equals(entry.video),"the newest progress becomes the resume chip");
            SystemClock.sleep(4500);key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->(Integer)value(view,"zone")==HomeView.Z_CHIP,"one Down selects Resume");
            await(()->PlaybackPrefetch.prepared(entry.video),"Resume focus prepares that episode before selection");
            key(KeyEvent.KEYCODE_DPAD_DOWN);await(()->(Integer)value(view,"zone")==HomeView.Z_ROW,"Down selects the Continue Watching row");
            await(()->((String)value(((List<?>)value(view,"row")).get(0),"title")).equals(entry.title),"the latest show is the first card");
            await(()->((Float)value(((List<?>)value(view,"row")).get(0),"progress"))>0,"the card has saved progress");
            SystemClock.sleep(1600);Bitmap bitmap=getUiAutomation().takeScreenshot();
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"continue-home-test.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
            key(KeyEvent.KEYCODE_DPAD_LEFT);await(()->(Integer)value(view,"rowIndex")==0,"Left retains the first card");
            key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);await(()->(Integer)value(view,"zone")==HomeView.Z_PILL,"Back returns focus to Search");
            key(KeyEvent.KEYCODE_DPAD_DOWN);await(()->(Integer)value(view,"zone")==HomeView.Z_CHIP,"Resume remains available");
            addMonitor(monitor);key(KeyEvent.KEYCODE_DPAD_CENTER);await(()->resumed.get()!=null,"Resume opens the title playback request");
            await(()->entry.video.equals(resumed.get().getStringExtra("resume_video")),"Resume requests the saved episode");
            await(()->resumed.get().getLongExtra("resume_position",0)==90000&&resumed.get().getBooleanExtra("auto_play",false),"Resume requests the saved position automatically");
            result.putString("stream","Passed "+checks+" Continue Watching UI checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{removeMonitor(monitor);runOnMainSync(()->{if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
