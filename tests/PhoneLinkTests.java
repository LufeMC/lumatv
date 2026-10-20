package io.freemytv;

import android.os.Bundle;
import android.os.SystemClock;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Test the phone server through real local sockets. Do not report link tokens. */
public class PhoneLinkTests extends TvTest {
    private int checks;
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private String request(URI uri,String method,String path,String token,String host,String body)throws Exception{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket(uri.getHost(),uri.getPort())){
            socket.setSoTimeout(20000);
            String head=method+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\nX-Luma-Key: "+token+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] block=new byte[8192];int n;
            while((n=socket.getInputStream().read(block))!=-1){if(out.size()+n>100000)throw new AssertionError("Response limit");out.write(block,0,n);}
            return out.toString("UTF-8");
        }
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Ui.init(getTargetContext());URI uri=new URI(PhoneLink.typingUrl(getTargetContext()));String token=uri.getQuery().substring(2),host=uri.getHost()+":"+uri.getPort();
            String page=request(uri,"GET",uri.getRawPath()+"?"+uri.getRawQuery(),"",host,"");
            check(page.startsWith("HTTP/1.1 200")&&page.contains("Search on "),"phone page loads");
            check(page.contains("Cache-Control: no-store")&&page.contains("Referrer-Policy: no-referrer"),"phone page does not cache link tokens");
            check(request(uri,"GET","/t?k=wrong","",host,"").startsWith("HTTP/1.1 403"),"wrong link token is rejected");
            check(request(uri,"POST","/api/type","wrong",host,"{}").startsWith("HTTP/1.1 403"),"wrong API token is rejected");
            check(request(uri,"GET",uri.getRawPath()+"?"+uri.getRawQuery(),"","other.invalid","").startsWith("HTTP/1.1 403"),"unexpected Host is rejected");
            check(request(uri,"GET","/api/setup",token,host,"").startsWith("HTTP/1.1 403"),"typing token cannot access setup");
            AtomicReference<String> typed=new AtomicReference<>();
            PhoneLink.setTypingListener(new PhoneLink.Listener(){public void onText(String value){typed.set(value);}public void onOpen(String id){}});
            check(request(uri,"POST","/api/type",token,host,"{\"text\":\"\"}").startsWith("HTTP/1.1 200"),"authorized typing is accepted");
            long end=SystemClock.elapsedRealtime()+2000;while(typed.get()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);
            check("".equals(typed.get()),"phone input reaches the TV listener");
            URI setup=new URI(PhoneLink.setupUrl(getTargetContext()));String setupKey=setup.getQuery().substring(2);
            String view=request(setup,"GET","/api/setup",setupKey,host,"");
            check(view.startsWith("HTTP/1.1 200")&&view.contains("hasKey")&&!view.contains(Setup.tmdbKey()),"setup response does not return the TMDB key");
            result.putString("stream","Passed "+checks+" phone link checks.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+"\n");}
        finally{PhoneLink.setTypingListener(null);PhoneLink.stop();finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
