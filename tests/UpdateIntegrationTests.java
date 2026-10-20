package io.freemytv;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.json.JSONObject;

/** Exercise the real HTTPS downloader and installer against an explicitly supplied local release. */
public class UpdateIntegrationTests extends TvTest {
    private Bundle args;
    private int checks;
    private void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
    private void checkpoint(Context c,JSONObject value)throws Exception{
        File file=new File(c.getExternalFilesDir(null),"update-integration.json");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        try{
            Context c=getTargetContext();Ui.init(c);
            String base=args==null?"":args.getString("base","");
            check(base.matches("https://127\\.0\\.0\\.1:[0-9]+/"),"the test server is restricted to loopback");
            byte[] der=android.util.Base64.decode(args.getString("certificate",""),android.util.Base64.DEFAULT);
            java.security.cert.Certificate cert=CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
            KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null);roots.setCertificateEntry("local-test",cert);
            TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(roots);
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            Prefs.get(c,Prefs.UPDATES).edit().putString(Updater.BASE,base+"bad/").commit();
            JSONObject bad=Updater.check(c);check(bad!=null,"the mock release is newer than the installed app");
            boolean rejected=false;try{Updater.download(c,bad);}catch(java.io.IOException expected){rejected=true;}
            check(rejected,"the actual downloader rejects a mismatched APK hash");
            check(!new File(c.getFilesDir(),"update/LumaTV.apk.part").exists(),"a mismatched partial APK is removed");
            Prefs.get(c,Prefs.UPDATES).edit().putString(Updater.BASE,base).commit();
            JSONObject release=Updater.check(c);check(release!=null,"the valid release manifest loads over HTTPS");
            File apk=Updater.download(c,release);
            check(apk.isFile()&&Updater.sha256(apk).equals(release.getString("sha256")),"the downloaded APK has the expected hash");
            Updater.verify(c,apk,release.getLong("versionCode"));checks++;
            rejected=false;try{Updater.verify(c,apk,release.getLong("versionCode")+1);}catch(java.io.IOException expected){rejected=true;}
            check(rejected,"the real archive verifier rejects a different version");
            Context originalKey=new ContextWrapper(c){@Override public File getFilesDir(){return new File(getApplicationInfo().dataDir,"files");}};
            check(new File(originalKey.getFilesDir(),"adbkey").isFile()&&new File(originalKey.getFilesDir(),"adbkey.pub").isFile(),"the application already has a debugging key");
            try(Adb adb=Adb.connectQuiet(originalKey);FileInputStream input=new FileInputStream(apk)){
                checks++;
                checkpoint(c,new JSONObject().put("stage","verified-before-install").put("checks",checks)
                    .put("oldVersionCode",Updater.installedCode(c)).put("versionCode",release.getLong("versionCode")).put("sha256",release.getString("sha256")));
                String response=adb.install(input,apk.length());
                check(response.contains("Success"),"the application installer accepts the verified update");
                result.putString("stream","Passed "+checks+" updater integration checks. The package observer must verify installation.\n");
            }
        }catch(Throwable error){result.putString("stream","FAILED after "+checks+": "+error.getClass().getSimpleName()+"\n");}
        finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
