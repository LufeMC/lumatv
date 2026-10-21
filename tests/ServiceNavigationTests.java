package io.freemytv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Leaving the title cancels an external-service opening request. */
public class ServiceNavigationTests extends TvTest {
    private SourcesActivity title;
    private SearchActivity search;
    private int checks;
    private Object value(String name)throws Exception{Field f=SourcesActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(title);}
    private void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(android.app.Application.getProcessName().equals("io.freemytv:home"))start();}
    @Override public void onStart(){
        Bundle result=new Bundle();AtomicInteger launches=new AtomicInteger();
        ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
            if("com.netflix.ninja".equals(intent.getPackage())){launches.incrementAndGet();return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;
        }};
        try{
            Ui.init(getTargetContext());getUiAutomation();addMonitor(monitor);TitleSearch.Title love=null;
            for(TitleSearch.Title item:TitleSearch.search("Love Is Blind"))if(item.series()&&item.name.equalsIgnoreCase("Love Is Blind")){love=item;break;}
            if(love==null)throw new AssertionError("Title metadata is unavailable");
            title=(SourcesActivity)startActivitySync(new Intent(getTargetContext(),SourcesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title_id",love.id).putExtra("title_kind","tv").putExtra("title_name",love.name));
            long end=SystemClock.elapsedRealtime()+25000;
            while(!(Boolean)value("linksReady")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
            check((Boolean)value("linksReady"),"service link lookup completes");
            WatchOptions.Option preferred=WatchOptions.preferred((java.util.List<WatchOptions.Option>)value("offers"));
            check(preferred!=null&&preferred.service.key.equals("netflix")&&preferred.link!=null,"Netflix has a direct title link");
            Intent direct=WatchOptions.intent(getTargetContext(),preferred);
            check(direct!=null&&Intent.ACTION_VIEW.equals(direct.getAction())&&direct.getData()!=null,"the installed Netflix app accepts the title intent");
            runOnMainSync(()->{try{
                Field ready=SourcesActivity.class.getDeclaredField("linksReady");ready.setAccessible(true);ready.setBoolean(title,false);
                Method open=SourcesActivity.class.getDeclaredMethod("openService");open.setAccessible(true);open.invoke(title);
            }catch(Exception e){throw new RuntimeException(e);}});
            check((Boolean)value("servicePending"),"the controlled lookup waits");
            search=(SearchActivity)startActivitySync(new Intent(getTargetContext(),SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(!(Boolean)value("servicePending"),"leaving the title cancels the request");
            runOnMainSync(()->search.finish());search=null;SystemClock.sleep(6500);
            check(launches.get()==0,"returning to the title does not open Netflix later");
            check(((android.view.View)value("servicePlay")).isEnabled(),"the service action is available again");
            result.putString("stream","Passed "+checks+" service navigation checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{removeMonitor(monitor);runOnMainSync(()->{if(search!=null)search.finish();if(title!=null)title.finish();});finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
