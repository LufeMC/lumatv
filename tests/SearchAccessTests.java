package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.Field;

/** Check the shortest search paths in the approved home design. */
public class SearchAccessTests extends TvTest {
    private int checks;
    private Activity home, search;
    private HomeView view;
    @Override public void onCreate(Bundle args) { super.onCreate(args); if (android.app.Application.getProcessName().equals("io.freemytv:home")) start(); }
    private Object field(Object object, String name) throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private void check(boolean value, String label) { if(!value)throw new AssertionError(label);checks++; }
    private void key(Activity activity,int code) {
        runOnMainSync(()->{long now=SystemClock.uptimeMillis();activity.dispatchKeyEvent(new KeyEvent(now,now,0,code,0));activity.dispatchKeyEvent(new KeyEvent(now,now,1,code,0));});SystemClock.sleep(200);
    }
    private boolean pill() throws Exception { return (Integer)field(view,"zone")==HomeView.Z_PILL && (Integer)field(view,"mode")==0; }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            getUiAutomation(); home=startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(5000);
            runOnMainSync(()->{try{HomeActivity visible=HomeActivity.visible.get();if(visible==null)throw new AssertionError("Home is not visible");home=visible;view=(HomeView)field(home,"home");}catch(Exception e){throw new RuntimeException(e);}});
            check(pill(),"Search has default home focus");
            key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check((Integer)field(view,"mode")==2 && (Integer)field(view,"zone")==HomeView.Z_KEYS,"one OK opens the home keyboard");
            key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check(field(view,"query").equals("a"),"the first keyboard key accepts remote input");
            key(home,KeyEvent.KEYCODE_BACK);key(home,KeyEvent.KEYCODE_BACK);
            check(pill(),"Back returns to the home Search control");
            key(home,KeyEvent.KEYCODE_SEARCH);
            check((Integer)field(view,"mode")==2,"remote Search opens inline search");
            key(home,KeyEvent.KEYCODE_BACK);
            runOnMainSync(()->view.reset(false));
            key(home,KeyEvent.KEYCODE_DPAD_DOWN);key(home,KeyEvent.KEYCODE_BACK);
            check(pill(),"Back from content restores Search focus");
            search=startActivitySync(new Intent(getTargetContext(),SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);
            long hidden=SystemClock.elapsedRealtime()+5000;
            while(field(view,"land")!=null&&SystemClock.elapsedRealtime()<hidden)SystemClock.sleep(50);
            check(field(view,"land")==null,"hidden home releases its landscape");
            View keyboard=(View)field(search,"keys");EditText query=(EditText)field(search,"query");
            boolean[] focus={false};runOnMainSync(()->focus[0]=keyboard.hasFocus());check(focus[0],"standalone Search opens with the shared keyboard ready");
            runOnMainSync(()->query.setText("test"));key(search,KeyEvent.KEYCODE_SEARCH);
            runOnMainSync(()->focus[0]=keyboard.hasFocus()&&query.getSelectionStart()==0&&query.getSelectionEnd()==query.length());
            check(focus[0],"Search key prepares replacement text");
            key(search,KeyEvent.KEYCODE_DPAD_CENTER);check(query.getText().toString().equals("a"),"typing replaces the selected query");
            runOnMainSync(()->search.finish());search=null;
            long end=SystemClock.elapsedRealtime()+5000;
            while(SystemClock.elapsedRealtime()<end){
                runOnMainSync(()->{try{HomeActivity visible=HomeActivity.visible.get();if(visible!=null){home=visible;view=(HomeView)field(home,"home");}}catch(Exception e){throw new RuntimeException(e);}});
                if(field(view,"land")!=null)break;SystemClock.sleep(50);
            }
            check(field(view,"land")!=null,"home restores its landscape after Back");
            result.putString("stream","Passed "+checks+" Search access checks.\n");
        } catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getMessage()+"\n");}
        finally {runOnMainSync(()->{if(search!=null)search.finish();if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
