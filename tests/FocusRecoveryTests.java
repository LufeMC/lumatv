package io.freemytv;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Keep the adjacent card selected when a watched title leaves Home. */
public class FocusRecoveryTests extends TvTest {
    private HomeActivity activity;
    private HomeView home;
    private int checks;
    private Object get(String name)throws Exception{Field f=HomeView.class.getDeclaredField(name);f.setAccessible(true);return f.get(home);}
    private void set(String name,Object value)throws Exception{Field f=HomeView.class.getDeclaredField(name);f.setAccessible(true);f.set(home,value);}
    private void rebuild()throws Exception{Method m=HomeView.class.getDeclaredMethod("buildRow");m.setAccessible(true);m.invoke(home);}
    private ContinueWatching.Entry entry(String id){ContinueWatching.Entry e=new ContinueWatching.Entry();e.title=id;e.imdb=id;e.video=id;return e;}
    private String selected()throws Exception{Object card=((List<?>)get("row")).get((Integer)get("rowIndex"));Field field=card.getClass().getDeclaredField("title");field.setAccessible(true);return (String)field.get(card);}
    private void check(boolean yes,String label){if(!yes)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Ui.init(getTargetContext());getUiAutomation();activity=(HomeActivity)startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Field f=HomeActivity.class.getDeclaredField("home");f.setAccessible(true);home=(HomeView)f.get(activity);SystemClock.sleep(5000);
        ContinueWatching.Entry a=entry("tt0000041"),b=entry("tt0000042"),c=entry("tt0000043"),d=entry("tt0000044");Throwable[] error={null};
        runOnMainSync(()->{try{
            set("entries",Arrays.asList(a,b,c));rebuild();set("zone",HomeView.Z_ROW);set("rowIndex",1);
            set("entries",Arrays.asList(a,c));rebuild();check(selected().equals(c.title),"removing the focused card selects its next neighbor");
            set("entries",Arrays.asList(d,a,c));rebuild();check(selected().equals(c.title),"inserting an earlier card preserves title identity");
            set("entries",Arrays.asList(d,a));rebuild();check((Integer)get("rowIndex")<((List<?>)get("row")).size(),"removing the last focused title leaves a valid selection");
        }catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw error[0];
        result.putString("stream","Passed "+checks+" Home focus recovery checks.\n");
    }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getMessage()+"\n");}
    finally{if(activity!=null)runOnMainSync(activity::finish);finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}}
}
