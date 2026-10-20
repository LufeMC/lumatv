package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.util.List;

/** Verify suggestions, all seasons, and return to the unchanged search query. */
public class DiscoveryUiTests extends TvTest {
    private SearchActivity search;
    private SourcesActivity title;
    private int checks;
    private Object value(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private interface Condition{boolean ready()throws Exception;}
    private void await(Condition condition,String label)throws Exception{
        long end=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};Throwable[] error={null};runOnMainSync(()->{try{ready[0]=condition.ready();}catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new AssertionError(label,error[0]);if(ready[0]){checks++;return;}SystemClock.sleep(100);}
        throw new AssertionError(label);
    }
    private View find(View root,String prefix){if(root.getContentDescription()!=null&&root.getContentDescription().toString().startsWith(prefix))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private void click(String prefix)throws Exception{
        await(()->find(title.getWindow().getDecorView(),prefix)!=null,"visible control: "+prefix);
        runOnMainSync(()->find(title.getWindow().getDecorView(),prefix).performClick());
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();ActivityMonitor monitor=null;
        try{
            getUiAutomation();search=(SearchActivity)startActivitySync(new Intent(getTargetContext(),SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            EditText query=(EditText)value(search,"query");View keyboard=(View)value(search,"keys");
            runOnMainSync(()->query.setText("brooklyn"));
            await(()->!((List<?>)value(search,"titles")).isEmpty(),"typing produces search suggestions");
            await(()->keyboard.hasFocus(),"suggestions preserve keyboard focus");
            TitleSearch.Title match=null;for(Object item:(List<?>)value(search,"titles")){TitleSearch.Title t=(TitleSearch.Title)item;if(t.name.equals("Brooklyn Nine-Nine")&&t.series()){match=t;break;}}
            if(match==null)throw new AssertionError("Expected series suggestion");checks++;
            String tag=match.kind+":"+match.id;
            await(()->((View)value(search,"content")).findViewWithTag(tag)!=null,"the series suggestion has a poster card");
            monitor=addMonitor(SourcesActivity.class.getName(),null,false);
            runOnMainSync(()->{try{((View)value(search,"content")).findViewWithTag(tag).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            title=(SourcesActivity)waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);monitor=null;
            if(title==null)throw new AssertionError("Title page did not open");checks++;
            await(()->!((List<?>)value(title,"episodeList")).isEmpty(),"the episode list loads");
            for(int season=1;season<=8;season++){final int s=season;await(()->find(title.getWindow().getDecorView(),"Season "+s)!=null,"season "+s+" is available");}
            click("Season 8");await(()->find(title.getWindow().getDecorView(),"S8 E1.")!=null,"the final season has episode cards");
            click("Season 1");await(()->find(title.getWindow().getDecorView(),"S1 E1. Pilot")!=null,"the first episode retains its canonical name");
            runOnMainSync(()->title.finish());title=null;
            await(()->query.getText().toString().equals("brooklyn")&&((View)value(search,"content")).findViewWithTag(tag)!=null,"Back retains the query and result card");
            result.putString("stream","Passed "+checks+" discovery UI checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{if(monitor!=null)removeMonitor(monitor);runOnMainSync(()->{if(title!=null)title.finish();if(search!=null)search.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
