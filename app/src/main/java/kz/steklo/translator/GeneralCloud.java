package kz.steklo.translator;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.icu.text.Transliterator;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public dictionary shard downloads. No full source phrase, image or user identifier is uploaded. */
final class GeneralCloud implements AutoCloseable {
    private static final String BASE="https://dwavxvkiuujonnolxqfs.supabase.co/rest/v1/steklo_dictionary";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final LruCache<String,String> results=new LruCache<>(1200);
    private volatile boolean closed;
    private boolean busy;
    private long nextAllowed;
    String lookup(String word){String s=results.get(word);return s==null||s.isEmpty()?null:s;}
    void prefetch(Collection<String> terms,Runnable updated){
        if(closed||busy||SystemClock.elapsedRealtime()<nextAllowed)return;
        LinkedHashSet<String> query=new LinkedHashSet<>();
        for(String text:terms){if(DictionaryLookup.language(text)!=null&&results.get(text)==null)query.add(text);if(query.size()>=12)break;}
        if(query.isEmpty())return;
        busy=true;nextAllowed=SystemClock.elapsedRealtime()+2000;
        io.execute(()->{
            Map<String,String> resolved=new HashMap<>();boolean ok=false;
            try{
                Transliterator traditional=null;
                try{traditional=Transliterator.getInstance("Simplified-Traditional");}catch(RuntimeException ignored){}
                Map<String,List<String>> aliases=new LinkedHashMap<>();
                Map<String,Set<String>> buckets=new HashMap<>();
                for(String word:query){
                    String lang=DictionaryLookup.language(word);
                    String normalized="en".equals(lang)?word.toLowerCase(Locale.ROOT):word;
                    LinkedHashSet<String> variants=new LinkedHashSet<>();variants.add(normalized);
                    if("zh".equals(lang)&&traditional!=null)variants.add(traditional.transliterate(normalized));
                    aliases.put(word,new ArrayList<>(variants));
                    for(String v:variants)buckets.computeIfAbsent(lang,k->new LinkedHashSet<>()).add(DictionaryLookup.bucket(v));
                }
                Map<String,String> received=new HashMap<>();
                for(Map.Entry<String,Set<String>> group:buckets.entrySet()){
                    List<String> list=new ArrayList<>(group.getValue());
                    for(int i=0;i<list.size();i+=6){
                        if(closed)return;
                        JSONArray rows=fetch(group.getKey(),list.subList(i,Math.min(i+6,list.size())));
                        if(rows.length()>=500)throw new IllegalStateException("Truncated public shard");
                        for(int j=0;j<rows.length();j++){
                            JSONObject row=rows.getJSONObject(j);
                            String key=group.getKey()+"\n"+row.getString("source_text");
                            String value=DictionaryLookup.clean(row.getString("russian_text"));
                            if(value!=null)received.put(key,value);
                        }
                    }
                }
                for(Map.Entry<String,List<String>> item:aliases.entrySet()){
                    String word=item.getKey(),lang=DictionaryLookup.language(word),answer=null;
                    for(String v:item.getValue()){
                        String found=received.get(lang+"\n"+v);
                        if(found!=null){if(answer!=null&&!answer.equals(found)){answer=null;break;}answer=found;}
                    }
                    resolved.put(word,answer==null?"":answer);
                }
                ok=true;
            }catch(Exception ignored){/* local translation always continues */}
            finally{
                boolean success=ok;
                main.post(()->{
                    busy=false;if(closed)return;
                    if(!success){nextAllowed=SystemClock.elapsedRealtime()+20000;return;}
                    boolean changed=false;
                    for(Map.Entry<String,String> e:resolved.entrySet()){
                        results.put(e.getKey(),e.getValue());changed|=!e.getValue().isEmpty();
                    }
                    if(changed)updated.run();
                });
            }
        });
    }
    private JSONArray fetch(String lang,List<String> buckets)throws Exception{
        String path=BASE+"?select=source_text,russian_text&category=eq.general&published=eq.true&alternatives=eq.1&source_lang=eq."+lang+"&lookup_bucket3=in.("+String.join(",",buckets)+")&limit=500";
        HttpsURLConnection c=(HttpsURLConnection)new URL(path).openConnection();
        try{
            c.setConnectTimeout(3500);c.setReadTimeout(3500);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("apikey",CloudGlossary.PUBLIC_KEY);c.setRequestProperty("Accept","application/json");
            if(c.getResponseCode()!=200)throw new IllegalStateException("Public dictionary HTTP "+c.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream input=c.getInputStream()){
                byte[] b=new byte[8192];int n;
                while((n=input.read(b))!=-1){if(closed||out.size()+n>512*1024)throw new IllegalStateException("Dictionary response limit");out.write(b,0,n);}
            }
            return new JSONArray(out.toString("UTF-8"));
        }finally{c.disconnect();}
    }
    @Override public void close(){closed=true;io.shutdownNow();results.evictAll();}
}
