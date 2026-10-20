package io.freemytv;

import android.os.Bundle;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verify pairing and encrypted synchronization through the real server with an isolated test peer. */
public class SyncTransportTests extends TvTest {
    private URI address;
    private int checks;
    private static final class Reply{int status;byte[] body;}
    private void check(boolean yes,String label){if(!yes)throw new AssertionError(label);checks++;}
    private Reply send(String path,String from,byte[] body)throws Exception{
        try(Socket socket=new Socket(address.getHost(),address.getPort())){
            socket.setSoTimeout(15000);String head="POST "+path+" HTTP/1.1\r\nHost: "+address.getHost()+":"+address.getPort()+"\r\nX-Luma-From: "+from+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            java.io.ByteArrayOutputStream all=new java.io.ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=socket.getInputStream().read(block))!=-1){if(all.size()+n>2*1024*1024)throw new AssertionError("Response limit");all.write(block,0,n);}
            byte[] bytes=all.toByteArray();int split=-1;for(int i=0;i+3<bytes.length;i++)if(bytes[i]==13&&bytes[i+1]==10&&bytes[i+2]==13&&bytes[i+3]==10){split=i+4;break;}
            if(split<0)throw new AssertionError("Invalid HTTP response");Reply out=new Reply();out.status=Integer.parseInt(new String(bytes,0,split,StandardCharsets.US_ASCII).split(" ")[1]);out.body=java.util.Arrays.copyOfRange(bytes,split,bytes.length);return out;
        }
    }
    private JSONObject post(String path,JSONObject body)throws Exception{return new JSONObject(new String(send(path,"",body.toString().getBytes(StandardCharsets.UTF_8)).body,StandardCharsets.UTF_8));}
    private byte[] message(byte[] secret,String from,String to,String op,JSONObject data)throws Exception{return TvSync.seal(secret,from+">"+to,new JSONObject().put("ts",System.currentTimeMillis()).put("nonce",UUID.randomUUID().toString()).put("op",op).put("data",data).toString().getBytes(StandardCharsets.UTF_8));}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            Ui.init(getTargetContext());Prefs.get(getTargetContext(),Prefs.SYNC).edit().clear().commit();
            address=new URI(PhoneLink.typingUrl(getTargetContext()));TvSync.openPairing(getTargetContext());
            String peer=UUID.randomUUID().toString(),me=TvSync.id(getTargetContext());
            JSONObject hello=post("/sync/pair/hello",new JSONObject().put("id",peer).put("name","Isolated test peer"));check(hello.optString("id").equals(me),"the real pairing server returns its identity");
            String code=TvSync.pairingCode()[0];Pake.Party party=new Pake.Party(true,code,peer,me);
            JSONObject exchange=post("/sync/pair/x",new JSONObject().put("id",peer).put("x",party.mine.toString(16)));
            check(party.finish(new java.math.BigInteger(exchange.getString("y"),16)),"the peer completes key agreement over HTTP");
            check(java.security.MessageDigest.isEqual(party.confirm("B"),TvSync.unhex(exchange.getString("mac"))),"the server proves knowledge of the displayed PIN");
            JSONObject confirmed=post("/sync/pair/confirm",new JSONObject().put("id",peer).put("mac",TvSync.hex(party.confirm("A"))));check(confirmed.optBoolean("ok"),"the server confirms the paired peer");
            check(TvSync.peers(getTargetContext()).size()==1,"the paired secret is retained in encrypted test preferences");
            byte[] secret=party.sharedSecret(),state=message(secret,peer,me,"state",new JSONObject());Reply snapshot=send("/sync/msg",peer,state);
            JSONObject before=new JSONObject(new String(TvSync.open(secret,me+">"+peer,snapshot.body),StandardCharsets.UTF_8));check(snapshot.status==200&&before.has("categories"),"the paired peer reads authenticated encrypted state");
            check(send("/sync/msg",peer,state).status==403,"a repeated encrypted request is rejected");
            byte[] tampered=message(secret,peer,me,"state",new JSONObject());tampered[tampered.length-1]^=1;check(send("/sync/msg",peer,tampered).status==400,"modified ciphertext is rejected");
            check(send("/sync/msg",UUID.randomUUID().toString(),state).status==403,"an unknown peer cannot read state");
            List<String> apps=new ArrayList<>(HomeApps.shelfPackages(getTargetContext()));Collections.reverse(apps);
            JSONObject settings=new JSONObject().put("playback",new JSONObject().put(Prefs.AUDIO_LANGUAGE,"pt-BR").put(Prefs.AUTOPLAY,false)).put("look","night").put("remote",new JSONObject());
            JSONArray addons=new JSONArray().put(new JSONObject().put("transportUrl","https://example.invalid/manifest.json").put("manifest",new JSONObject().put("id","isolated.fixture").put("name","Test fixture")));
            JSONObject categories=new JSONObject();long now=System.currentTimeMillis();
            categories.put(TvSync.APPS,new JSONObject().put("changed",now).put("data",new JSONObject().put("packages",new JSONArray(apps))));
            categories.put(TvSync.SETTINGS,new JSONObject().put("changed",now).put("data",settings));
            categories.put(TvSync.SERVICES,new JSONObject().put("changed",now).put("data",new JSONObject().put("off",new JSONArray().put("com.netflix.ninja"))));
            categories.put(TvSync.ADDONS,new JSONObject().put("changed",now).put("data",new JSONObject().put("addons",addons).put("account","isolated-test-account")));
            Reply applied=send("/sync/msg",peer,message(secret,peer,me,"apply",new JSONObject().put("categories",categories)));
            check(new JSONObject(new String(TvSync.open(secret,me+">"+peer,applied.body),StandardCharsets.UTF_8)).optBoolean("ok"),"the encrypted apply request is acknowledged");
            check(HomeApps.shelfPackages(getTargetContext()).equals(apps),"application order transfers through encrypted HTTP");
            check(Prefs.get(getTargetContext(),Prefs.PLAYBACK).getString(Prefs.AUDIO_LANGUAGE,"").equals("pt-BR")&&!Prefs.flag(getTargetContext(),Prefs.PLAYBACK,Prefs.AUTOPLAY,true),"typed playback settings transfer");
            check(!Prefs.serviceOn(getTargetContext(),"com.netflix.ninja"),"service selections transfer");
            check(AddonStore.load(getTargetContext()).length()==1&&AddonStore.account(getTargetContext()).equals("isolated-test-account"),"add-ons and account authorization transfer into encrypted test storage");
            Reply forgotten=send("/sync/msg",peer,message(secret,peer,me,"forget",new JSONObject()));check(forgotten.status==200&&TvSync.peers(getTargetContext()).isEmpty(),"forgetting the paired peer removes its authorization");
            check(send("/sync/msg",peer,message(secret,peer,me,"state",new JSONObject())).status==403,"a forgotten peer cannot read state");
            result.putString("stream","Passed "+checks+" pairing and encrypted HTTP synchronization checks with an isolated peer.\n");
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+": "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");}
        finally{TvSync.closePairing(getTargetContext());PhoneLink.stop();finish(result.getString("stream","").startsWith("Passed")?-1:0,result);}
    }
}
