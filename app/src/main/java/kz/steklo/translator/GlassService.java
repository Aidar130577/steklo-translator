package kz.steklo.translator;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.view.accessibility.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.math.BigDecimal;
import java.util.*;

/** Read-only accessibility service. No gesture injection, no screenshots on disk. */
public final class GlassService extends AccessibilityService {
    private final TranslationSession session=new TranslationSession();
    private android.widget.ImageView power;
    private final Runnable unlockRefresh=()->{if(this.alive){syncForeground();}};
    private android.content.BroadcastReceiver screenReceiver;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final LruCache<String,String> cache=new LruCache<>(600);
    private final Runnable scanTask=()->{scheduledAt=Long.MAX_VALUE;scan();};
    private final Runnable modelRetry=this::loadModel;
    private SharedPreferences prefs;
    private CloudGlossary cloudGlossary;
    private GeneralCloud generalCloud;
    private WindowManager wm;
    private Layer layer;
    private Translator translator, englishTranslator;
    private boolean englishReady, englishLoading, englishError;
    private int nodeCount, ocrCount, shownCount;
    private String ocrStatus="не запускался";
    private TextRecognizer recognizer;
    private boolean ready, loading, busy, alive;
    private long generation, lastOcr;
    private String lastPackage="";
    private int lastWindow=-1;
    private boolean pending;
    private String lastFingerprint="";
    private String ocrFingerprint="";
    private List<Item> ocrItems=Collections.emptyList();
    private boolean modelError;
    private long scheduledAt = Long.MAX_VALUE;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static final class Item {
        final String source; final Rect rect; final String result;
        Item(String source, Rect rect, String result){this.source=source;this.rect=new Rect(rect);this.result=result;}
    }
    @Override protected void onServiceConnected(){
        super.onServiceConnected(); alive=true; prefs=getSharedPreferences("glass",MODE_PRIVATE); wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        englishTranslator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
        recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        // Old versions persisted "enabled". Always require an explicit tap in the current app.
        prefs.edit().remove("enabled").apply();
        listener=(p,k)->{if("rate".equals(k))h.post(()->{invalidatePage(); if(enabled())schedule(0);});}; prefs.registerOnSharedPreferenceChangeListener(listener);
        screenReceiver=new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context context,android.content.Intent intent){
                if(android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){session.updateForeground("");stopWork();removePower();}
                else {h.removeCallbacks(unlockRefresh);h.postDelayed(unlockRefresh,300);}
            }
        };
        android.content.IntentFilter filter=new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);filter.addAction(android.content.Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,filter,android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver,filter);
        generalCloud=new GeneralCloud();
        cloudGlossary=new CloudGlossary(this);cloudGlossary.start(()->{if(alive&&enabled()){invalidatePage();schedule(0);}});
        syncForeground();
    }
    private boolean unlocked(){
        android.app.KeyguardManager km=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        return (km==null||!km.isKeyguardLocked())&&(pm==null||pm.isInteractive());
    }
    private boolean enabled(){return alive&&session.enabled()&&unlocked();}
    private String foreground(){
        if(!alive||!unlocked())return "";
        AccessibilityNodeInfo root=null;
        try{root=appRoot();return root==null?"":String.valueOf(root.getPackageName());}
        catch(RuntimeException e){return "";}finally{if(root!=null)root.recycle();}
    }
    private AccessibilityNodeInfo appRoot(){
        AccessibilityNodeInfo r=getRootInActiveWindow();
        if(r!=null&&!getPackageName().contentEquals(r.getPackageName()==null?"":r.getPackageName()))return r;
        if(r!=null)r.recycle();
        AccessibilityNodeInfo found=null;
        for(AccessibilityWindowInfo w:getWindows()){
            try{if(found==null&&w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&(w.isActive()||w.isFocused()))found=w.getRoot();}
            finally{w.recycle();}
        }
        return found;
    }
    private void stopWork(){
        generation++;lastFingerprint="";ocrFingerprint="";ocrItems=Collections.emptyList();nodeCount=0;ocrCount=0;shownCount=0;ocrStatus="не запускался";pending=false;h.removeCallbacks(scanTask);scheduledAt=Long.MAX_VALUE;
        h.removeCallbacks(modelRetry);clear();
    }
    private void syncForeground(){
        String pkg=foreground();
        if(session.updateForeground(pkg))stopWork();
        if(session.supportedForeground()){
            try{ensureLayer();ensurePower();updatePower();}catch(RuntimeException e){session.stop();stopWork();removePower();}
        }else {clear();removePower();}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void ensurePower(){
        if(power!=null)return;
        android.widget.ImageView v=new android.widget.ImageView(this);
        v.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);v.setPadding(dp(11),dp(11),dp(11),dp(11));
        v.setClickable(true);v.setFocusable(false);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setOnClickListener(w->{
            String pkg=foreground();
            if(!session.isCurrent(pkg)||!session.supportedForeground()){syncForeground();return;}
            if(session.enabled()){session.stop();stopWork();updatePower();}
            else {session.start();stopWork();updatePower();loadModel();schedule(0);
                if(!ready)android.widget.Toast.makeText(this,"Подготовка языковой модели. Первый запуск требует интернет.",android.widget.Toast.LENGTH_SHORT).show();}
        });
        v.setOnLongClickListener(w->{android.widget.Toast.makeText(this,"Китайский: "+(ready?"готов":loading?"загрузка":modelError?"ошибка":"не загружен")+"; английский: "+(englishReady?"готов":englishLoading?"загрузка":englishError?"ошибка":"не загружен")+"\nТекст: "+nodeCount+"; OCR: "+ocrCount+" ("+ocrStatus+"); показано: "+shownCount,android.widget.Toast.LENGTH_LONG).show();return true;});
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(40),dp(40),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=dp(6);p.y=dp(6);wm.addView(v,p);power=v;
    }
    private void updatePower(){
        if(power==null)return;
        boolean on=enabled();
        boolean allReady=ready&&englishReady;
        String state=!on?"off":allReady?"ready":(modelError||englishError)?"error":"loading";
        if(state.equals(power.getTag()))return;
        power.setTag(state);power.setImageResource(!on||allReady?R.drawable.ic_power:(modelError||englishError)?android.R.drawable.ic_dialog_alert:android.R.drawable.ic_popup_sync);power.setColorFilter(Color.WHITE);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(!on?Color.rgb(190,30,45):allReady?Color.rgb(22,163,74):Color.rgb(190,120,0));bg.setStroke(dp(2),Color.WHITE);
        power.setBackground(bg);power.setContentDescription(!on?"Начать перевод":allReady?"Остановить перевод":(modelError||englishError)?"Ошибка загрузки модели. Нажмите, чтобы остановить.":"Загрузка модели. Нажмите, чтобы остановить.");
    }
    private void removePower(){
        if(power!=null){try{wm.removeViewImmediate(power);}catch(RuntimeException ignored){}power=null;}
    }
    private void loadModel(){
        loadChineseModel();
        if(!enabled()||englishReady||englishLoading||englishTranslator==null)return;
        englishLoading=true;englishError=false;updatePower();
        try{englishTranslator.downloadModelIfNeeded().addOnCompleteListener(t->{
            englishLoading=false;if(!alive)return;englishReady=t.isSuccessful();englishError=!englishReady;updatePower();
            if(enabled()){if(englishReady)schedule(0);else {h.removeCallbacks(modelRetry);h.postDelayed(modelRetry,30000);}}
        });}catch(RuntimeException e){englishLoading=false;englishError=true;updatePower();if(enabled()){h.removeCallbacks(modelRetry);h.postDelayed(modelRetry,30000);}}
    }
    private void loadChineseModel(){
        if(!enabled()||ready||loading||translator==null)return; loading=true;modelError=false;updatePower();
        try{translator.downloadModelIfNeeded().addOnCompleteListener(t->{loading=false;if(!alive)return;ready=t.isSuccessful();modelError=!ready;updatePower();if(ready)schedule(0);else if(enabled()){android.widget.Toast.makeText(this,"Не удалось загрузить модель перевода. Проверьте интернет. Повтор через 30 секунд.",android.widget.Toast.LENGTH_LONG).show();h.removeCallbacks(modelRetry);h.postDelayed(modelRetry,30000);}});}catch(RuntimeException e){loading=false;modelError=true;updatePower();h.postDelayed(modelRetry,30000);}
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        int type=e.getEventType();
        boolean own=getPackageName().contentEquals(e.getPackageName()==null?"":e.getPackageName());
        if(own && type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type!=AccessibilityEvent.TYPE_WINDOWS_CHANGED)return;
        syncForeground();
        if(!enabled()){clear();return;}
        if(own)return;
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED||type==AccessibilityEvent.TYPE_VIEW_SCROLLED||type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED){
            refreshKnown(type==AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
        schedule(type==AccessibilityEvent.TYPE_VIEW_SCROLLED?100:60);
    }
    private void invalidatePage(){generation++;lastFingerprint="";ocrFingerprint="";ocrItems=Collections.emptyList();clear();}
    private void schedule(long delay){if(!enabled())return;long at=SystemClock.uptimeMillis()+delay;
        if(at>=scheduledAt)return;h.removeCallbacks(scanTask);scheduledAt=at;h.postAtTime(scanTask,at);}
    private void refreshKnown(boolean scrolled){
        AccessibilityNodeInfo root=null;
        try{root=appRoot();
            if(root==null||!session.isCurrent(String.valueOf(root.getPackageName()))){clear();return;}
            List<Item> items=new ArrayList<>();boolean[] sensitive={false};collect(root,items,sensitive,new int[]{0});
            if(sensitive[0]){invalidatePage();return;}
            String fp=fingerprint(root.getWindowId(),items);
            if(!scrolled&&fp.equals(lastFingerprint))return;
            generation++;lastFingerprint=fp;ocrFingerprint="";ocrItems=Collections.emptyList();
            BigDecimal rate=currentRate();List<Item> known=new ArrayList<>();
            for(Item i:items){String text=knownText(i.source,rate);if(text!=null)known.add(new Item(i.source,i.rect,text));}
            publish(known,generation);
        }catch(RuntimeException e){clear();}finally{if(root!=null)root.recycle();}
    }
    private String fingerprint(int win,List<Item> items){
        StringBuilder b=new StringBuilder().append(win).append('|');
        for(Item i:items)b.append(i.source.length()).append(':').append(i.source).append('@').append(i.rect.toShortString()).append('|');
        return b.toString();
    }
    private BigDecimal currentRate(){try{return TradeText.parseRate(prefs.getString("rate",""));}catch(RuntimeException e){return null;}}
    private String knownText(String source,BigDecimal rate){String text=TradeText.convert(source,rate);if(text==null&&cloudGlossary!=null)text=cloudGlossary.lookup(source);if(text==null)text=TradeText.exact(source);if(text==null&&generalCloud!=null)text=generalCloud.lookup(source);if(text==null)text=cache.get(source);return text;}
    private void clear(){if(layer!=null){layer.items=Collections.emptyList();layer.invalidate();}}
    private void ensureLayer(){
        if(layer!=null)return; Layer candidate=new Layer();
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT; wm.addView(candidate,p); layer=candidate;
    }
    private void scan(){
        if(!enabled()){clear();return;} if(busy){pending=true;return;}
        AccessibilityNodeInfo root=null;
        try{
            root=appRoot(); if(root==null){invalidatePage();return;}
            String pkg=String.valueOf(root.getPackageName()); int win=root.getWindowId();
            if(!session.isCurrent(pkg)){invalidatePage();lastPackage="";lastWindow=-1;return;}
            if(!pkg.equals(lastPackage)||win!=lastWindow){invalidatePage();lastPackage=pkg;lastWindow=win;}
            List<Item> items=new ArrayList<>(); boolean[] sensitive={false}; collect(root,items,sensitive,new int[]{0});
            if(sensitive[0]){invalidatePage();return;}
            nodeCount=items.size();
            String fp=fingerprint(win,items);
            if(!fp.equals(lastFingerprint)){lastFingerprint=fp;ocrFingerprint="";ocrItems=Collections.emptyList();}
            long token=++generation; busy=true; pending=false;
            // Render dictionary/cache immediately, without waiting for OCR.
            List<Item> known=new ArrayList<>();BigDecimal rate=currentRate();
            for(Item i:items){String value=knownText(i.source,rate);if(value!=null)known.add(new Item(i.source,i.rect,value));}
            if(fp.equals(ocrFingerprint))for(Item i:ocrItems){String value=knownText(i.source,rate);if(value!=null)known.add(new Item(i.source,i.rect,value));}
            publish(known,token);
            if(Build.VERSION.SDK_INT>=34&&!hasFocusedEditor(root)&&SystemClock.uptimeMillis()-lastOcr>=1300){
                Rect bounds=windowBounds(win); if(bounds!=null){lastOcr=SystemClock.uptimeMillis();ocrStatus="считывание"; capture(win,bounds,items,token);return;}
            }
            if(fp.equals(ocrFingerprint))items.addAll(ocrItems);
            resolve(items,token);
        }catch(RuntimeException e){clear();if(busy)finish();}
        finally{if(root!=null)root.recycle();}
    }
    private boolean hasFocusedEditor(AccessibilityNodeInfo root){
        AccessibilityNodeInfo focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(focused==null)return false;try{return focused.isEditable()||focused.isPassword();}finally{focused.recycle();}
    }
    private Rect windowBounds(int id){
        List<AccessibilityWindowInfo> ws=getWindows();Rect found=null;
        for(AccessibilityWindowInfo w:ws){try{if(w.getId()==id&&w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){found=new Rect();w.getBoundsInScreen(found);}}finally{w.recycle();}}
        return found;
    }
    private void collect(AccessibilityNodeInfo n,List<Item> out,boolean[] sensitive,int[] count){
        if(++count[0]>850||out.size()>=110||!n.isVisibleToUser())return;
        if(n.isPassword()){sensitive[0]=true;return;}
        if(n.isEditable())return;
        CharSequence cs=n.getText(); String s=cs==null?"":cs.toString().trim();
        if(s.contains("支付密码")||s.contains("银行卡号")||s.contains("验证码")||s.contains("身份证号")){sensitive[0]=true;return;}
        if(!s.isEmpty()&&s.length()<=1000&&TradeText.candidate(s)){Rect r=new Rect();n.getBoundsInScreen(r);if(r.width()>4&&r.height()>4)out.add(new Item(s,r,null));}
        for(int i=0;i<n.getChildCount()&&!sensitive[0]&&count[0]<850&&out.size()<110;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){try{collect(c,out,sensitive,count);}finally{c.recycle();}}}
    }
    @android.annotation.TargetApi(34)
    private void capture(int win,Rect bounds,List<Item> items,long token){
        try{takeScreenshotOfWindow(win,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onFailure(int error){ocrStatus="код "+error;if(token!=generation){finish();return;}if(error==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW){invalidatePage();finish();}else resolve(items,token);}
            @Override public void onSuccess(ScreenshotResult result){
                Bitmap bmp=null;
                try{if(!alive||token!=generation){finish();return;}
                    Bitmap hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                    if(hardware!=null){try{bmp=hardware.copy(Bitmap.Config.ARGB_8888,false);}finally{hardware.recycle();}}
                }catch(RuntimeException e){if(bmp!=null)bmp.recycle();clear();finish();return;}
                finally{result.getHardwareBuffer().close();}
                if(bmp==null){resolve(items,token);return;}
                final Bitmap image=bmp;
                try{recognizer.process(InputImage.fromBitmap(image,0)).addOnCompleteListener(t->{
                    try{if(!alive||token!=generation){finish();return;}
                        ocrStatus=t.isSuccessful()?"готов":"ошибка";ocrCount=0;
                        if(t.isSuccessful()){
                            float sx=(float)bounds.width()/image.getWidth(),sy=(float)bounds.height()/image.getHeight();
                            boolean sensitive=false;List<Item> freshOcr=new ArrayList<>();
                            for(Text.TextBlock block:t.getResult().getTextBlocks()){
                                String s=block.getText();if(s.contains("支付密码")||s.contains("银行卡号")||s.contains("验证码")||s.contains("身份证号")){sensitive=true;break;}
                                Rect r=block.getBoundingBox();if(r==null||s.length()>1000||items.size()>=150||!TradeText.candidate(s))continue;
                                Rect screen=new Rect(bounds.left+Math.round(r.left*sx),bounds.top+Math.round(r.top*sy),bounds.left+Math.round(r.right*sx),bounds.top+Math.round(r.bottom*sy));
                                boolean overlap=false;
                                for(Item i:items){Rect intersection=new Rect(i.rect);
                                    if(intersection.intersect(screen)&&i.source.replaceAll("\\s+","").equals(s.replaceAll("\\s+",""))&&((long)intersection.width()*intersection.height()*100L)>=65L*screen.width()*screen.height()){overlap=true;break;}}
                                if(!overlap){Item found=new Item(s,screen,null);items.add(found);freshOcr.add(found);ocrCount++;}
                            }
                            if(sensitive){invalidatePage();finish();return;}
                            ocrItems=freshOcr;ocrFingerprint=lastFingerprint;
                        }
                        resolve(items,token);
                    }catch(RuntimeException e){clear();finish();}finally{image.recycle();}
                });}catch(RuntimeException e){image.recycle();clear();finish();}
            }
        });}catch(RuntimeException e){clear();finish();}
    }
    private void resolve(List<Item> items,long token){
        if(!alive||token!=generation){finish();return;}
        BigDecimal rate=currentRate();
        if(generalCloud!=null){
            Set<String> words=new LinkedHashSet<>();
            for(Item i:items)if(TradeText.exact(i.source)==null&&(cloudGlossary==null||cloudGlossary.lookup(i.source)==null))words.add(i.source);
            generalCloud.prefetch(words,()->{if(alive&&enabled())schedule(0);});
        }
        List<Item> done=new ArrayList<>();List<Item> missing=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Item i:items){if(!seen.add(i.source+"@"+i.rect.toShortString()))continue;
            String s=knownText(i.source,rate);
            if(s!=null)done.add(new Item(i.source,i.rect,s));else if((ready&&TradeText.chinese(i.source))||(englishReady&&TradeText.english(i.source)))missing.add(i);}
        publish(done,token);
        // Keep only unique source strings in a bounded translation queue.
        Map<String,List<Item>> groups=new LinkedHashMap<>();
        for(Item i:missing)groups.computeIfAbsent(i.source,k->new ArrayList<>()).add(i);
        List<List<Item>> jobs=new ArrayList<>(groups.values());
        if(jobs.isEmpty()){finish();return;}
        new Batch(jobs,done,token).pump();
    }
    private final class Batch {
        final List<List<Item>> jobs;final List<Item> done;final long token;
        int next,inFlight;boolean finished;
        Batch(List<List<Item>> jobs,List<Item> done,long token){this.jobs=jobs;this.done=done;this.token=token;}
        void pump(){
            if(finished)return;
            while(alive&&enabled()&&token==generation&&inFlight<3&&next<jobs.size()){
                List<Item> group=jobs.get(next++);String source=group.get(0).source;
                String existing=cache.get(source);
                if(existing!=null){add(group,existing);continue;}
                inFlight++;
                try{
                    Translator engine=TradeText.chinese(source)?translator:englishTranslator;
                    engine.translate(source).addOnCompleteListener(t->{
                        try{if(alive&&t.isSuccessful()){
                            String value=t.getResult();
                            if(TradeText.acceptTranslation(source,value)){
                                cache.put(source,value);
                                if(enabled()&&token==generation)add(group,value);
                            }
                        }}finally{inFlight--;h.post(this::pump);}
                    });
                }catch(RuntimeException e){inFlight--;}
            }
            if(inFlight==0&&(!alive||!enabled()||token!=generation||next>=jobs.size())){finished=true;finish();}
        }
        void add(List<Item> group,String value){
            for(Item i:group)done.add(new Item(i.source,i.rect,value));
            publish(done,token);
        }
    }
    private void publish(List<Item> done,long token){
        if(!enabled()||token!=generation||!session.isCurrent(foreground()))return;
        try{ensureLayer();layer.items=new ArrayList<>(done);shownCount=done.size();layer.invalidate();}catch(RuntimeException e){clear();}
    }
    private void finish(){busy=false;if(!alive)return;if(pending){pending=false;schedule(60);}else if(enabled())schedule(1500);}
    @Override public void onInterrupt(){session.stop();stopWork();updatePower();}
    @Override public void onDestroy(){alive=false;generation++;h.removeCallbacksAndMessages(null);if(prefs!=null&&listener!=null)prefs.unregisterOnSharedPreferenceChangeListener(listener);
        if(cloudGlossary!=null)cloudGlossary.close();if(generalCloud!=null)generalCloud.close();session.stop();removePower();if(screenReceiver!=null){try{unregisterReceiver(screenReceiver);}catch(RuntimeException ignored){}screenReceiver=null;}
        if(layer!=null){try{wm.removeViewImmediate(layer);}catch(RuntimeException ignored){}layer=null;}
        if(translator!=null)translator.close();if(englishTranslator!=null)englishTranslator.close();if(recognizer!=null)recognizer.close();cache.evictAll();super.onDestroy();}
    private final class Layer extends View {
        List<Item> items=Collections.emptyList(); final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); final int[] offset=new int[2];
        Layer(){super(GlassService.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);getLocationOnScreen(offset);
            for(Item i:items){Rect r=new Rect(i.rect);r.offset(-offset[0],-offset[1]);if(!r.intersect(0,0,getWidth(),getHeight()))continue;
                float max=Math.min(18*getResources().getDisplayMetrics().scaledDensity,r.height()*.8f);
                float min=8*getResources().getDisplayMetrics().scaledDensity;
                int width=r.width()-4;if(width<8||max<min)continue;
                android.text.TextPaint tp=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(Color.rgb(26,35,40));android.text.StaticLayout layout=null;
                for(float size=max;size>=min;size-=.5f){
                    tp.setTextSize(size);
                    android.text.StaticLayout candidate=android.text.StaticLayout.Builder.obtain(i.result,0,i.result.length(),tp,width)
                        .setIncludePad(false).setLineSpacing(0,1f).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build();
                    if(candidate.getHeight()<=r.height()){layout=candidate;break;}
                }
                if(layout==null)continue; // Keep the original rather than truncate numbers or words.
                paint.setColor(Color.rgb(250,250,250));c.drawRect(r,paint);
                c.save();c.clipRect(r);c.translate(r.left+2,r.top+(r.height()-layout.getHeight())/2f);layout.draw(c);c.restore();
            }
        }
    }
}
