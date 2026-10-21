package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.*;
import java.util.List;

/** Verify remote typing, grid boundaries, editing, and access to live suggestions. */
public class KeyboardGridTests extends TvTest {
    private Activity home, search;
    private HomeView view;
    private int checks;
    private Object field(Object owner, String name) throws Exception { Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
    private void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);checks++; }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition c,String label) throws Exception {
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end) { boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=c.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100); }
        throw new AssertionError(label);
    }
    private void key(Activity a,int code) {
        if(a==search && code!=KeyEvent.KEYCODE_SEARCH) sendKeyDownUpSync(code);
        else runOnMainSync(()->{long t=SystemClock.uptimeMillis();a.dispatchKeyEvent(new KeyEvent(t,t,0,code,0));a.dispatchKeyEvent(new KeyEvent(t,t,1,code,0));});
        SystemClock.sleep(80);
    }
    private void repeat(Activity a,int code,int n) { for(int i=0;i<n;i++)key(a,code); }
    private void shot(String name) throws Exception {
        SystemClock.sleep(600);android.graphics.Bitmap b=getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}finally{b.recycle();}
    }
    private void query(String text) { runOnMainSync(()->{try{Method m=HomeView.class.getDeclaredMethod("setQuery",String.class);m.setAccessible(true);m.invoke(view,text);}catch(Exception e){throw new RuntimeException(e);}}); }
    @Override public void onCreate(Bundle b) { super.onCreate(b);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            getUiAutomation();Ui.init(getTargetContext());
            ActivityMonitor started=addMonitor(HomeActivity.class.getName(),null,false);
            getTargetContext().startActivity(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            home=waitForMonitorWithTimeout(started,20000);removeMonitor(started);
            if(home==null)throw new AssertionError("Home did not open");
            await(()->{HomeActivity visible=HomeActivity.visible.get();if(visible==null)return false;home=visible;view=(HomeView)field(home,"home");return true;},"the current Home activity is visible");
            SystemClock.sleep(5000);key(home,KeyEvent.KEYCODE_SEARCH);
            check(view.zone==HomeView.Z_KEYS,"Search starts on the letter grid");
            KeyRow keys=(KeyRow)field(view,"keys");
            check(keys.getHeight()>keys.getWidth()*.9 && keys.getWidth()<view.getWidth()/3,"the keyboard has a compact grid layout");
            key(home,KeyEvent.KEYCODE_DPAD_CENTER);key(home,KeyEvent.KEYCODE_DPAD_DOWN);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            key(home,KeyEvent.KEYCODE_DPAD_RIGHT);key(home,KeyEvent.KEYCODE_DPAD_CENTER);key(home,KeyEvent.KEYCODE_DPAD_UP);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("aghb".equals(field(view,"query")),"vertical movement preserves the letter column");
            repeat(home,KeyEvent.KEYCODE_DPAD_DOWN,4);key(home,KeyEvent.KEYCODE_DPAD_RIGHT);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("aghb ".equals(field(view,"query")),"Space inserts a space");
            key(home,KeyEvent.KEYCODE_DPAD_RIGHT);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("aghb".equals(field(view,"query")),"Delete removes one character");
            runOnMainSync(keys::reset);repeat(home,KeyEvent.KEYCODE_DPAD_DOWN,5);key(home,KeyEvent.KEYCODE_DPAD_RIGHT);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("".equals(field(view,"query")),"Clear removes the query");
            runOnMainSync(keys::reset);repeat(home,KeyEvent.KEYCODE_DPAD_DOWN,5);key(home,KeyEvent.KEYCODE_DPAD_CENTER);repeat(home,KeyEvent.KEYCODE_DPAD_UP,5);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("1".equals(field(view,"query")),"the number page accepts digits");
            repeat(home,KeyEvent.KEYCODE_DPAD_DOWN,5);key(home,KeyEvent.KEYCODE_DPAD_CENTER);repeat(home,KeyEvent.KEYCODE_DPAD_UP,5);key(home,KeyEvent.KEYCODE_DPAD_CENTER);
            check("1a".equals(field(view,"query")),"ABC restores letter entry");
            query("brooklyn");runOnMainSync(keys::reset);
            await(()->!((List<?>)field(view,"sections")).isEmpty(),"live suggestions load beside the grid");
            check(view.zone==HomeView.Z_KEYS,"suggestions preserve typing focus");shot("keyboard-home-grid.png");
            repeat(home,KeyEvent.KEYCODE_DPAD_RIGHT,6);
            check(view.zone==HomeView.Z_RESULTS,"Right at the grid edge selects suggestions");
            key(home,KeyEvent.KEYCODE_DPAD_LEFT);check(view.zone==HomeView.Z_KEYS,"Left from the first result returns to typing");
            key(home,KeyEvent.KEYCODE_DPAD_CENTER);check("brooklynf".equals(field(view,"query")),"returning to the keyboard preserves its selected key");
            runOnMainSync(keys::reset);key(home,KeyEvent.KEYCODE_DPAD_UP);check(view.zone==HomeView.Z_PILL,"Up from the top row selects the query");
            search=startActivitySync(new Intent(getTargetContext(),SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            KeyRow nativeKeys=(KeyRow)field(search,"keys");EditText edit=(EditText)field(search,"query");
            await(nativeKeys::hasFocus,"standalone Search starts on the same grid");
            key(search,KeyEvent.KEYCODE_DPAD_CENTER);key(search,KeyEvent.KEYCODE_DPAD_DOWN);key(search,KeyEvent.KEYCODE_DPAD_CENTER);
            check("ag".equals(edit.getText().toString()),"standalone Search uses the same row navigation");
            runOnMainSync(()->{edit.setText("abXc");edit.setSelection(1,3);nativeKeys.reset();});
            repeat(search,KeyEvent.KEYCODE_DPAD_DOWN,4);repeat(search,KeyEvent.KEYCODE_DPAD_RIGHT,3);key(search,KeyEvent.KEYCODE_DPAD_CENTER);
            check("ac".equals(edit.getText().toString()),"Delete removes selected text without removing the ending");
            runOnMainSync(()->{edit.setText("brooklyn");nativeKeys.reset();});
            await(()->((View)field(search,"content")).findViewWithTag("tv:48891")!=null,"the correct standalone suggestion is rendered");shot("keyboard-search-grid.png");
            repeat(search,KeyEvent.KEYCODE_DPAD_RIGHT,6);
            await(()->!nativeKeys.hasFocus() && ((View)field(search,"content")).hasFocus(),"Right selects a standalone suggestion");
            key(search,KeyEvent.KEYCODE_DPAD_LEFT);await(nativeKeys::hasFocus,"Left returns from a suggestion to the keyboard");
            key(search,KeyEvent.KEYCODE_SEARCH);await(()->nativeKeys.hasFocus()&&edit.getSelectionStart()==0&&edit.getSelectionEnd()==edit.length(),"Search prepares query replacement");
            key(search,KeyEvent.KEYCODE_DPAD_CENTER);check("a".equals(edit.getText().toString()),"a grid key replaces the selected query");
            runOnMainSync(()->{nativeKeys.reset();Ui.en=false;nativeKeys.invalidate();});
            repeat(search,KeyEvent.KEYCODE_DPAD_DOWN,5);key(search,KeyEvent.KEYCODE_DPAD_RIGHT);
            check(nativeKeys.description().contains("Limpar"),"the selected utility key has a localized accessible name");shot("keyboard-grid-portuguese.png");
            runOnMainSync(()->Ui.init(getTargetContext()));
            result.putString("stream","Passed "+checks+" keyboard grid and suggestion checks.\n");
        } catch(Throwable e) { result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n"); }
        finally { runOnMainSync(()->{Ui.init(getTargetContext());if(search!=null)search.finish();if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result); }
    }
}
