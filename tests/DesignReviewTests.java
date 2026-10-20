package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;

/** Capture the approved layouts with real metadata and remote focus. */
public class DesignReviewTests extends TvTest {
    private Activity active;
    private int checks;
    private Object value(Object owner, String name) throws Exception { Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition, String label) throws Exception {
        long end=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<end) {
            boolean[] ready={false}; Throwable[] failure={null};
            runOnMainSync(()->{try{ready[0]=condition.ready();}catch(Throwable e){failure[0]=e;}});
            if(failure[0]!=null)throw new AssertionError(label+": "+failure[0].getClass().getSimpleName());
            if(ready[0]){checks++;return;}SystemClock.sleep(100);
        }
        throw new AssertionError(label);
    }
    private void key(int code) { runOnMainSync(()->{long n=SystemClock.uptimeMillis();active.dispatchKeyEvent(new KeyEvent(n,n,0,code,0));active.dispatchKeyEvent(new KeyEvent(n,n,1,code,0));});SystemClock.sleep(250); }
    private void shot(String name) throws Exception {
        SystemClock.sleep(600);
        Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("No screenshot: "+name);
        File dir=new File(getTargetContext().getExternalFilesDir(null),"design-review");dir.mkdirs();
        try(FileOutputStream file=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}finally{bitmap.recycle();}
    }
    private void close(){if(active!=null){runOnMainSync(()->active.finish());active=null;waitForIdleSync();}}
    private boolean label(View root,String label) {
        if(label.equals(String.valueOf(root.getContentDescription()))||root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return true;
        if(root instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)root).getChildCount();i++)if(label(((android.view.ViewGroup)root).getChildAt(i),label))return true;
        return false;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            getUiAutomation();
            active=startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            HomeView home=(HomeView)value(active,"home");
            await(()->value(home,"land")!=null&&!home.shelf.isEmpty(),"home artwork and shelf load");
            SystemClock.sleep(4500);shot("home-dark");
            for(int i=0;i<3&&(Integer)value(home,"mode")!=1;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->(Integer)value(home,"mode")==1,"Down opens home content");shot("home-content");
            runOnMainSync(()->home.voiceResult("brooklyn"));
            await(()->!((List<?>)value(home,"results")).isEmpty(),"inline search returns suggestions");shot("home-search");close();
            active=startActivitySync(new Intent(getTargetContext(),SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1800);shot("search-phone");
            SearchActivity search=(SearchActivity)active;
            long searchAt=SystemClock.elapsedRealtime();
            runOnMainSync(()->{try{((EditText)value(search,"query")).setText("brooklyn");}catch(Exception e){throw new RuntimeException(e);}});
            await(()->((List<?>)value(search,"titles")).size()>5,"search results load");long metadataMs=SystemClock.elapsedRealtime()-searchAt;
            await(()->{
                TitleSearch.Title first=(TitleSearch.Title)((List<?>)value(search,"titles")).get(0);
                android.widget.LinearLayout card=((View)value(search,"content")).findViewWithTag(first.kind+":"+first.id);
                return card!=null&&((ArtTile)card.getChildAt(0)).image()!=null;
            },"the first search poster loads");long artworkMs=SystemClock.elapsedRealtime()-searchAt;
            shot("search-results");close();
            active=startActivitySync(new Intent(getTargetContext(),SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title_id",1418).putExtra("title_kind","tv").putExtra("title_name","The Big Bang Theory"));
            SourcesActivity title=(SourcesActivity)active;
            await(()->!((List<?>)value(title,"episodeList")).isEmpty(),"series episodes load");
            await(()->!((SeriesEpisodes.Episode)((List<?>)value(title,"episodeList")).get(0)).name.matches("Episode [0-9]+"),"canonical episode names remain available");
            SystemClock.sleep(1800);shot("title-series");close();
            List<TitlePage.Person> people=People.search("Saoirse Ronan");if(people.isEmpty())throw new AssertionError("Person metadata is unavailable");
            TitlePage.Person person=people.get(0);
            active=startActivitySync(new Intent(getTargetContext(),PersonActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("person_id",person.id).putExtra("person_name",person.name));
            PersonActivity personPage=(PersonActivity)active;
            await(()->((TitlePage)value(personPage,"page")).summary.length()>0,"person biography loads");SystemClock.sleep(1500);shot("person");close();
            active=startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SettingsActivity settings=(SettingsActivity)active;
            List<TextView> nav=(List<TextView>)value(settings,"nav");
            for(int i=0;i<nav.size();i++){
                final int section=i;runOnMainSync(()->nav.get(section).requestFocus());
                await(()->(Integer)value(settings,"section")==section,"settings section "+section);
                if(section==SettingsActivity.SERVICES)await(()->label((View)value(settings,"pane"),"Netflix"),"installed subscription services appear");
                if(section==SettingsActivity.TVS)await(()->label((View)value(settings,"pane"),"This TV"),"the current TV appears without paired TVs");
                SystemClock.sleep(650);shot("settings-"+i);
            }
            result.putString("stream","Passed "+checks+" design navigation checks. Saved 18 screen captures. Search metadata: "+metadataMs+" ms; first poster: "+artworkMs+" ms.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{close();finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
