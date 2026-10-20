package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the remote Inputs action, quick settings, and the actual Home app editor. */
public class HomeControlsTests extends TvTest {
    private Activity home;
    private HomeView view;
    private int checks;
    private Object value(String name) throws Exception { Field f=HomeView.class.getDeclaredField(name);f.setAccessible(true);return f.get(view); }
    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition,String label) throws Exception {
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=condition.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ok[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private void key(int code) { press(code,0); }
    private void press(int code,long duration) {
        long time=SystemClock.uptimeMillis();runOnMainSync(()->home.dispatchKeyEvent(new KeyEvent(time,time,0,code,0)));
        if(duration>0)SystemClock.sleep(duration);
        runOnMainSync(()->home.dispatchKeyEvent(new KeyEvent(time,SystemClock.uptimeMillis(),1,code,0)));SystemClock.sleep(180);
    }
    private boolean label(AccessibilityNodeInfo node,String text) {
        if(node==null)return false;if(text.contentEquals(node.getText()==null?"":node.getText()))return true;
        for(int i=0;i<node.getChildCount();i++)if(label(node.getChild(i),text))return true;return false;
    }
    private List<String> packages(){List<String> list=new ArrayList<>();for(HomeApps.Item item:view.shelf)list.add(item.pkg);return list;}
    private void shot(String name)throws Exception{
        SystemClock.sleep(500);Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("Missing screenshot");
        File folder=new File(getTargetContext().getExternalFilesDir(null),"design-review");folder.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();AtomicInteger launches=new AtomicInteger();
        ActivityMonitor guard=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){launches.incrementAndGet();return new ActivityResult(Activity.RESULT_CANCELED,null);}};
        try{
            getUiAutomation();home=startActivitySync(new Intent(getTargetContext(),HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(RemoteKeys.EXTRA_OPEN,RemoteKeys.TARGET_INPUTS));
            Field f=HomeActivity.class.getDeclaredField("home");f.setAccessible(true);view=(HomeView)f.get(home);
            await(()->(Boolean)value("inputsReady")&&(Integer)value("overlayKind")==1&&value("menuCard")==null,"remote Inputs opens Glance after inventory loads");
            await(()->value("land")!=null&&!view.shelf.isEmpty(),"Glance has landscape and apps");shot("home-glance");
            key(KeyEvent.KEYCODE_BACK);await(()->(Integer)value("overlayKind")==0,"Back closes Glance");
            press(KeyEvent.KEYCODE_SETTINGS,800);
            await(()->label(getUiAutomation().getRootInActiveWindow(),"Quick settings"),"holding Settings opens quick settings");shot("home-quick-settings");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await(()->!label(getUiAutomation().getRootInActiveWindow(),"Quick settings"),"Back closes quick settings");
            for(int i=0;i<7&&(Integer)value("zone")!=HomeView.Z_LOGOS;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
            await(()->(Integer)value("zone")==HomeView.Z_LOGOS&&view.shelf.size()>1,"remote reaches app row");
            List<String> before=packages();addMonitor(guard);
            press(KeyEvent.KEYCODE_DPAD_CENTER,850);await(()->(Boolean)value("editing"),"holding OK starts app editing");
            key(KeyEvent.KEYCODE_DPAD_RIGHT);List<String> moved=packages();
            await(()->!moved.equals(before)&&moved.get(1).equals(before.get(0)),"Right moves the selected app");shot("home-edit-apps");
            key(KeyEvent.KEYCODE_DPAD_CENTER);await(()->!(Boolean)value("editing"),"OK finishes editing");
            await(()->HomeApps.shelfPackages(getTargetContext()).equals(moved),"the edited order is saved in isolated preferences");
            await(()->launches.get()==0,"finishing editing does not open an app");
            press(KeyEvent.KEYCODE_DPAD_CENTER,850);await(()->(Boolean)value("editing"),"editing can start again");
            key(KeyEvent.KEYCODE_DPAD_DOWN);await(()->view.shelf.size()==before.size()-1,"Down removes one app from Home");
            key(KeyEvent.KEYCODE_BACK);await(()->!(Boolean)value("editing"),"Back finishes editing");
            result.putString("stream","Passed "+checks+" Home control checks. Saved Glance, quick settings, and app editor captures.\n");
        }catch(Throwable error){result.putString("stream","FAILED after "+checks+": "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");}
        finally{removeMonitor(guard);runOnMainSync(()->{if(home!=null)home.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
