package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import org.json.JSONObject;

/** Exercise recovery, unavailable states, and secondary approved layouts with isolated data. */
public class DesignStatesTests extends TvTest {
    private Activity active;
    private HomeActivity home;
    private HomeView view;
    private int checks,shots;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private interface Action{void run()throws Exception;}
    private void main(Action a){runOnMainSync(()->{try{a.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition c,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=c.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private void key(int code){main(()->{long t=SystemClock.uptimeMillis();active.dispatchKeyEvent(new KeyEvent(t,t,0,code,0));active.dispatchKeyEvent(new KeyEvent(t,t,1,code,0));});SystemClock.sleep(150);}
    private void shot(String name)throws Exception{SystemClock.sleep(1000);Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("No screenshot");
        File dir=new File(getTargetContext().getExternalFilesDir(null),"design-review");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}shots++;}
    private View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View item=find(((ViewGroup)root).getChildAt(i),text);if(item!=null)return item;}return null;}
    private void close(){if(active!=null){Activity old=active;runOnMainSync(old::finish);active=null;waitForIdleSync();}}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();String token="";boolean language=Ui.en;
        try{
            getUiAutomation();Ui.init(getTargetContext());token=Setup.tmdbKey();PlaybackSession.current=null;
            ContinueWatching.Entry e=new ContinueWatching.Entry();e.title="The Big Bang Theory";e.tmdb=1418;e.imdb="tt0898266";e.kind="tv";e.video="tt0898266:9:23";e.season=9;e.episode=23;e.duration=1200000;e.position=708000;
            e.art="https://image.tmdb.org/t/p/w780"+TitleSearch.get("/tv/1418").optString("backdrop_path");
            PlaybackSession interrupted=new PlaybackSession(e.video,e.title,Collections.emptyList(),-1);interrupted.entry=e;PlaybackSession.persist(getTargetContext(),interrupted,e.position);
            ContinueWatching.record(getTargetContext(),e,e.position,e.duration,false);
            active=startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));home=(HomeActivity)active;view=(HomeView)value(home,"home");
            await(()->value(view,"resumePrompt")!=null&&((ResumePrompt)value(view,"resumePrompt")).dialog.isShowing(),"interrupted playback opens the centered prompt");
            ResumePrompt prompt=(ResumePrompt)value(view,"resumePrompt");await(()->prompt.resume.hasFocus(),"Resume is the only initial dialog focus");
            await(()->prompt.artwork.image()!=null,"the prompt restores saved title artwork");shot("resume-prompt");
            main(()->prompt.later.performClick());await(()->PlaybackSession.interrupted(getTargetContext())==null,"Not now clears only the interruption prompt");
            main(()->view.openAppEditor());await(()->(Boolean)value(view,"editing"),"the Home edit request enters the inline editor");key(KeyEvent.KEYCODE_BACK);
            main(()->call(view,"openAllApps",new Class<?>[0]));await(()->(Integer)value(view,"overlayKind")==2,"All apps opens as the approved grid");shot("all-apps");key(KeyEvent.KEYCODE_BACK);
            key(KeyEvent.KEYCODE_BACK);for(int i=0;i<4&&(Integer)value(view,"zone")!=HomeView.Z_ROW;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->(Integer)value(view,"zone")==HomeView.Z_ROW,"remote reaches the content row");key(KeyEvent.KEYCODE_MENU);
            await(()->value(view,"menuCard")!=null,"the content action menu opens");shot("home-card-menu");key(KeyEvent.KEYCODE_BACK);
            key(KeyEvent.KEYCODE_DPAD_RIGHT);SystemClock.sleep(2000);shot("home-focused-pick");
            SeriesEpisodes.Episode following=new SeriesEpisodes.Episode();following.season=9;following.number=24;following.minutes=20;
            following.name=TitleSearch.get("/tv/1418/season/9/episode/24").optString("name");ContinueWatching.finished(getTargetContext(),e,following);
            main(()->{call(view,"refreshRow",new Class<?>[0]);view.reset(false);});
            await(()->!((java.util.List<?>)value(view,"entries")).isEmpty()&&((ContinueWatching.Entry)((java.util.List<?>)value(view,"entries")).get(0)).upNext,"a finished episode produces the Home Up next card");
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);shot("home-up-next");
            main(()->call(view,"setOnline",new Class<?>[]{boolean.class},false));shot("home-offline");
            main(()->call(view,"setOnline",new Class<?>[]{boolean.class},true));
            Setup.save(getTargetContext(),new JSONObject().put("tmdb_key",""));main(()->{view.reset(true);call(view,"refreshPhoneCodes",new Class<?>[0]);});
            await(()->value(view,"setupCode")!=null&&(Integer)value(view,"zone")==HomeView.Z_SETUP,"the setup QR and its focused action are available");shot("home-no-key");
            Setup.save(getTargetContext(),new JSONObject().put("tmdb_key",token));
            SettingsActivity settings=(SettingsActivity)startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SettingsActivity.EXTRA_SECTION,SettingsActivity.APPS));active=settings;
            await(()->find(settings.getWindow().getDecorView(),"Edit apps")!=null,"Settings exposes the editor action");
            main(()->{View action=find(settings.getWindow().getDecorView(),"Edit apps");while(!action.isClickable())action=(View)action.getParent();action.performClick();});active=home;
            await(()->(Boolean)value(view,"editing")&&(Boolean)value(view,"running"),"Settings opens the canonical inline editor");shot("settings-app-editor");key(KeyEvent.KEYCODE_BACK);
            main(settings::finish);
            main(()->view.playOpening());SystemClock.sleep(1400);shot("opening-line");key(KeyEvent.KEYCODE_DPAD_CENTER);
            Prefs.get(getTargetContext(),Prefs.HOME_UI).edit().putString(Prefs.LOOK,"day").commit();
            main(()->{view.reset(false);call(view,"checkLandscape",new Class<?>[]{boolean.class},false);});
            await(()->value(view,"land")!=null&&((Landscape)value(view,"land")).kind==Landscape.DAY,"the optional day landscape loads");shot("home-day");
            active=startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));shot("settings-day");close();
            Prefs.get(getTargetContext(),Prefs.HOME_UI).edit().putString(Prefs.LOOK,"night").commit();
            JSONObject available=new JSONObject().put("release",new JSONObject().put("versionCode",Updater.installedCode(getTargetContext())+1).put("versionName","Next test version").put("notes",new org.json.JSONArray().put("Automatic source recovery").put("Prepared seek previews")));
            try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"update-state.json"))){out.write(available.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            active=startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SettingsActivity.EXTRA_SECTION,SettingsActivity.ABOUT));shot("settings-update-available");close();
            TvSync.Peer peer=new TvSync.Peer();peer.id=java.util.UUID.randomUUID().toString();peer.name="Living room TV";peer.host="example.invalid";peer.port=48613;peer.secret=new byte[32];peer.syncedAt=System.currentTimeMillis();
            Method paired=TvSync.class.getDeclaredMethod("putPeer",android.content.Context.class,TvSync.Peer.class);paired.setAccessible(true);paired.invoke(null,getTargetContext(),peer);
            active=startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SettingsActivity.EXTRA_SECTION,SettingsActivity.TVS));
            await(()->find(active.getWindow().getDecorView(),"Living room TV")!=null,"the paired-TV card appears");shot("settings-paired-tvs");close();
            active=startActivitySync(new Intent(getTargetContext(),ConnectSourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));shot("connect-account");
            ConnectSourcesActivity connect=(ConnectSourcesActivity)active;main(()->{call(connect,"showWaiting",new Class<?>[0]);call(connect,"begin",new Class<?>[0]);});
            await(()->((View)value(connect,"qrCard")).getVisibility()==View.VISIBLE,"the official connection code renders");shot("connect-qr");close();
            TitleSearch.Title movie=null;for(TitleSearch.Title item:TitleSearch.search("Brooklyn"))if(!item.series()&&item.name.equals("Brooklyn")&&item.year.equals("2015")){movie=item;break;}
            if(movie==null)throw new AssertionError("Movie metadata unavailable");
            active=startActivitySync(new Intent(getTargetContext(),SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title_id",movie.id).putExtra("title_kind",movie.kind).putExtra("title_name",movie.name));
            SourcesActivity title=(SourcesActivity)active;await(()->(Boolean)value(title,"linksReady"),"movie service metadata loads");shot("title-movie");
            TitlePage page=(TitlePage)value(title,"page");SystemClock.sleep(1500);main(()->((android.widget.ScrollView)value(page,"scroll")).fullScroll(View.FOCUS_DOWN));shot("title-cast-related");
            main(()->{((android.widget.ScrollView)value(page,"scroll")).scrollTo(0,0);page.deadEnd("Cannot play this title",java.util.Arrays.asList("2 of 5 add-ons did not respond.","No supported video address was available."),new String[]{"Try again","Open in Stremio"},new View.OnClickListener[]{v->{},v->{}});});shot("title-no-sources");close();
            active=home;main(()->home.setContentView(CrashFallback.create(home)));
            await(()->{View heading=find(home.getWindow().getDecorView(),"LumaTV stopped a few times");int[] xy=new int[2];if(heading==null)return false;heading.getLocationOnScreen(xy);return xy[1]>home.getWindow().getDecorView().getHeight()/4;},"the recovery content is centered below the brand mark");
            shot("crash-fallback");
            result.putString("stream","Passed "+checks+" secondary design checks. Saved "+shots+" native state captures.\n");
        }catch(Throwable error){result.putString("stream","FAILED after "+checks+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");}
        finally{Ui.en=language;if(!token.isEmpty())try{Setup.save(getTargetContext(),new JSONObject().put("tmdb_key",token));}catch(Exception ignored){}close();if(home!=null)runOnMainSync(home::finish);finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
