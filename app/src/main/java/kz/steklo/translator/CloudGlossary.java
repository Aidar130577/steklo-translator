package kz.steklo.translator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Downloads public glossary only. No screen text, images, account information or user identifiers are sent. */
public final class CloudGlossary implements AutoCloseable {
    private static final String URL_STRING="https://dwavxvkiuujonnolxqfs.supabase.co/rest/v1/steklo_dictionary?select=source_text,russian_text&category=eq.trade&published=eq.true&source_id=eq.steklo_glossary_v1&order=source_lang.asc,source_text.asc&limit=500";
    static final String PUBLIC_KEY="sb_publishable_sGK1V95mWzvTBNoZxEfS4Q_nVomlReJ";
    private static final int MAX_BYTES=512*1024;
    private final SharedPreferences prefs;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile Map<String,String> words=Collections.emptyMap();
    public CloudGlossary(Context context){prefs=context.getSharedPreferences("public_trade_glossary",Context.MODE_PRIVATE);}
    public String lookup(String source){return source==null?null:words.get(source.trim());}
    public void start(Runnable updated){
        io.execute(()->{
            try{
                String old=prefs.getString("snapshot","");
                if(!old.isEmpty())apply(old,updated);
                if(System.currentTimeMillis()-prefs.getLong("fetched_at",0)<86400000L&&!words.isEmpty())return;
                HttpsURLConnection connection=(HttpsURLConnection)new URL(URL_STRING).openConnection();
                try{
                    connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("apikey",PUBLIC_KEY);
                    connection.setRequestProperty("Accept","application/json");
                    if(connection.getResponseCode()!=200)return;
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    try(InputStream stream=connection.getInputStream()){
                        byte[] data=new byte[8192];int n;
                        while((n=stream.read(data))!=-1){if(closed||buffer.size()+n>MAX_BYTES)return;buffer.write(data,0,n);}
                    }
                    String json=buffer.toString("UTF-8");
                    if(apply(json,updated)&&!closed)prefs.edit().putString("snapshot",json).putLong("fetched_at",System.currentTimeMillis()).apply();
                }finally{connection.disconnect();}
            }catch(Exception ignored){/* built-in glossary and local model stay available */}
        });
    }
    private boolean apply(String json,Runnable updated)throws Exception{
        if(closed||json.length()>MAX_BYTES)return false;
        JSONArray list=new JSONArray(json);if(list.length()==0||list.length()>500)return false;
        Map<String,String> next=new HashMap<>();
        for(int i=0;i<list.length();i++){
            JSONObject item=list.getJSONObject(i);String key=item.getString("source_text"),value=item.getString("russian_text");
            if(key.length()>1000||value.length()>4000||key.trim().isEmpty()||value.trim().isEmpty())return false;
            next.put(key.trim(),value);
        }
        if(closed)return false;words=Collections.unmodifiableMap(next);
        main.post(()->{if(!closed)updated.run();});return true;
    }
    @Override public void close(){closed=true;io.shutdownNow();}
}