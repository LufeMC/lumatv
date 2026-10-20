package io.freemytv;

import android.os.Bundle;
import org.json.JSONObject;

/** Test encrypted setup in the isolated preference copy. */
public class SetupDataTests extends TvTest {
    private int checks;
    private void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Ui.init(getTargetContext());
            check(Setup.save(getTargetContext(),new JSONObject().put("tmdb_key","test-token").put("city","Test city").put("latitude","1").put("longitude","2").put("temperature","C")),"setup saves");
            check(Setup.onTv()&&Setup.tmdbKey().equals("test-token"),"encrypted setup remains available");
            String sealed=Prefs.get(getTargetContext(),"setup").getString("data","");
            check(!sealed.isEmpty()&&!sealed.contains("test-token")&&!sealed.contains("Test city"),"saved setup does not contain cleartext values");
            check(Setup.hasLocation()&&Setup.temperature().equals("C"),"valid coordinates and Celsius are retained");
            for(String[] value:new String[][]{{"91","2"},{"1","181"},{"NaN","2"},{"1","invalid"}}){
                Setup.save(getTargetContext(),new JSONObject().put("latitude",value[0]).put("longitude",value[1]));
                check(!Setup.hasLocation(),"invalid coordinates cannot start weather requests");
            }
            Setup.migrateConfig(getTargetContext());
            check(Setup.tmdbKey().equals("test-token"),"test setup does not migrate household configuration");
            result.putString("stream","Passed "+checks+" encrypted setup checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+"\n");}
        finish(result.getString("stream","").startsWith("Passed")?-1:0,result);
    }
}
