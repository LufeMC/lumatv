package io.freemytv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Check the screensaver layout, idle work, and resource release without changing TV settings. */
public class ScreensaverTests extends TvTest {
    private SettingsActivity settings;
    private int checks;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            getUiAutomation();settings=(SettingsActivity)startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("screensaver",true));
            DreamView dream=(DreamView)value(settings,"preview");check(dream!=null,"screensaver preview opens");
            long end=SystemClock.elapsedRealtime()+10000;
            while(value(dream,"land")==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
            check(value(dream,"land")!=null,"the approved landscape loads");
            check(!((String)value(dream,"time")).isEmpty()&&!((String)value(dream,"detail")).isEmpty(),"clock and date are visible");
            SystemClock.sleep(1000);long[] before={0},after={0};runOnMainSync(()->before[0]=dream.drawCount);
            long cpu=android.os.Process.getElapsedCpuTime();SystemClock.sleep(4000);cpu=android.os.Process.getElapsedCpuTime()-cpu;
            runOnMainSync(()->after[0]=dream.drawCount);check(after[0]-before[0]<=3,"the idle screensaver does not draw continuously");
            Bitmap screen=getUiAutomation().takeScreenshot();File dir=new File(getTargetContext().getExternalFilesDir(null),"design-review");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"screensaver.png"))){screen.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screen.recycle();}
            runOnMainSync(()->{long n=SystemClock.uptimeMillis();settings.dispatchKeyEvent(new KeyEvent(n,n,0,KeyEvent.KEYCODE_BACK,0));settings.dispatchKeyEvent(new KeyEvent(n,n,1,KeyEvent.KEYCODE_BACK,0));});
            check(value(settings,"preview")==null,"Back closes the screensaver preview");
            check(!(Boolean)value(dream,"running")&&value(dream,"land")==null,"closing the preview releases its artwork");
            result.putString("stream","Passed "+checks+" screensaver checks. Idle draws in four seconds: "+(after[0]-before[0])+"; process CPU: "+cpu+" ms.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{if(settings!=null)runOnMainSync(()->settings.finish());finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
