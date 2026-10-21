package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Verify local removal through the remote menu without changing the connected account. */
public class WatchingRemovalTests extends TvTest {
    private Activity home;
    private HomeView view;
    private int checks;
    private Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] err={null};runOnMainSync(()->{try{ok[0]=c.ready();}catch(Throwable e){err[0]=e;}});
            if(err[0]!=null)throw new AssertionError(label,err[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private void key(int code){press(code,0);}
    private void press(int code,long hold){long t=SystemClock.uptimeMillis();runOnMainSync(()->home.dispatchKeyEvent(new KeyEvent(t,t,0,code,0)));if(hold>0)SystemClock.sleep(hold);runOnMainSync(()->home.dispatchKeyEvent(new KeyEvent(t,SystemClock.uptimeMillis(),1,code,0)));SystemClock.sleep(150);}
    private ContinueWatching.Entry entry(String title,String imdb,String kind){ContinueWatching.Entry e=new ContinueWatching.Entry();e.title=title;e.imdb=imdb;e.kind=kind;e.id=imdb;e.video=imdb+(kind.equals("tv")?":1:1":"");e.tmdb=1418;if(kind.equals("tv")){e.season=e.episode=1;}return e;}
    private void refresh(){runOnMainSync(()->{try{Method m=HomeView.class.getDeclaredMethod("refreshRow");m.setAccessible(true);m.invoke(view);}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean has(String imdb)throws Exception{for(Object c:(List<?>)field(view,"row")){ContinueWatching.Entry e=(ContinueWatching.Entry)field(c,"entry");if(e!=null&&imdb.equals(e.imdb))return true;}return false;}
    private void shot(String name)throws Exception{SystemClock.sleep(500);android.graphics.Bitmap b=getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}finally{b.recycle();}}
    private void removeMenu(String label)throws Exception{
        press(KeyEvent.KEYCODE_DPAD_CENTER,850);await(()->(Integer)field(view,"overlayKind")==1,"holding OK opens title actions");
        List<?> menu=(List<?>)field(view,"menu");int at=-1;for(int i=0;i<menu.size();i++)if(label.equals(field(menu.get(i),"label")))at=i;
        check(at>=0,"the removal action is available");for(int i=0;i<at;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
        check(view.getContentDescription().toString().equals(label),"the selected removal action has an accessible name");shot("watching-remove-menu.png");key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            getUiAutomation();Ui.init(getTargetContext());
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS,0).edit().clear().putBoolean("migrated",true).putLong("cloud-at",System.currentTimeMillis()).commit();
            ContinueWatching.Entry series=entry("Watching removal test","tt0898266","tv"),movie=entry("Movie removal test","tt0133093","movie");
            ContinueWatching.record(getTargetContext(),movie,60000,7200000,false);SystemClock.sleep(5);ContinueWatching.record(getTargetContext(),series,90000,1200000,false);
            JSONObject libraryItem=LibrarySync.item(series.imdb,"tv",series.title,"",true);libraryItem.getJSONObject("state").put("video_id",series.video).put("timeOffset",90000).put("duration",1200000).put("lastWatched","2026-10-19T10:00:00Z");
            LibrarySync.store(getTargetContext(),new JSONArray().put(libraryItem));String libraryBefore=LibrarySync.items(getTargetContext()).toString();
            ContinueWatching.Entry copy=ContinueWatching.Entry.parse(series.json());copy.updated=System.currentTimeMillis()-60000;
            getTargetContext().getSharedPreferences(ContinueWatching.PREFS,0).edit().putString("stremio",new JSONArray().put(copy.json()).toString()).commit();
            ActivityMonitor started=addMonitor(HomeActivity.class.getName(),null,false);
            getTargetContext().startActivity(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            home=waitForMonitorWithTimeout(started,20000);removeMonitor(started);
            if(home==null)throw new AssertionError("Home did not open");
            await(()->{HomeActivity visible=HomeActivity.visible.get();if(visible==null)return false;home=visible;view=(HomeView)field(home,"home");return true;},"the current Home activity is visible");SystemClock.sleep(5000);
            await(()->has(series.imdb)&&has(movie.imdb),"movie and series are visible before removal");key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(view.zone==HomeView.Z_CHIP,"Down selects the Resume card");check(view.getContentDescription().toString().contains("Hold OK"),"Resume explains how to open its actions");
            removeMenu("Remove from Continue Watching");await(()->!has(series.imdb),"removing Resume also removes the series card");check(has(movie.imdb),"removing a series preserves the other movie");
            refresh();SystemClock.sleep(1000);check(!has(series.imdb),"refresh cannot restore older imported progress");
            check(NewEpisodes.followed(getTargetContext(),ContinueWatching.cached(getTargetContext())).stream().noneMatch(e->e.imdb.equals(series.imdb)),"the full Stremio library cannot restore a removed series");
            check(libraryBefore.equals(LibrarySync.items(getTargetContext()).toString()),"removal does not change the account library");
            key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->view.zone==HomeView.Z_ROW,"the movie is accessible in Continue Watching");removeMenu("Remove from Continue Watching");await(()->!has(movie.imdb),"the movie can also be removed");
            ContinueWatching.Entry external=entry("Same title","","tv");external.pkg="com.netflix.ninja";external.id="netflix-test";external.updated=System.currentTimeMillis()-60000;
            ContinueWatching.remove(getTargetContext(),external);ContinueWatching.Entry internal=entry("Same title","tt10001","tv");internal.updated=external.updated;
            ContinueWatching.Entry sameNameMovie=entry("Same title","tt10002","movie");sameNameMovie.updated=external.updated;
            List<ContinueWatching.Entry> filtered=ContinueWatching.visible(getTargetContext(),Arrays.asList(internal,sameNameMovie));
            check(filtered.size()==1&&filtered.get(0)==sameNameMovie,"removal hides another service's series without hiding a same-name movie");
            ContinueWatching.Entry waiting=entry("Waiting removal test","tt2467372","tv");ContinueWatching.record(getTargetContext(),waiting,0,1200000,true);
            long now=System.currentTimeMillis();JSONObject latest=new JSONObject().put("season",1).put("episode",1).put("id",waiting.video).put("released",now-60000);
            getTargetContext().getSharedPreferences(NewEpisodes.PREFS,0).edit().putString("timeline:"+waiting.imdb,new JSONObject().put("latest",latest).put("ended",false).toString()).commit();
            refresh();await(()->has(waiting.imdb),"a completed ongoing series appears under Your series");
            runOnMainSync(()->{try{Method m=HomeView.class.getDeclaredMethod("toRow");m.setAccessible(true);m.invoke(view);List<?> cards=(List<?>)field(view,"row");for(int i=0;i<cards.size();i++)if(("waiting:"+waiting.imdb).equals(field(cards.get(i),"key"))){Field f=HomeView.class.getDeclaredField("rowIndex");f.setAccessible(true);f.set(view,i);}}catch(Exception e){throw new RuntimeException(e);}});
            removeMenu("Remove from Your series");await(()->!has(waiting.imdb),"a waiting series can be removed");refresh();SystemClock.sleep(1000);check(!has(waiting.imdb),"the waiting series stays removed after refresh");
            SystemClock.sleep(10);ContinueWatching.record(getTargetContext(),series,100000,1200000,false);refresh();await(()->has(series.imdb),"new playback can return a deliberately resumed title");
            result.putString("stream","Passed "+checks+" watching removal checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{runOnMainSync(()->{if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
