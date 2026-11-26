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
    private android.widget.TextView power;
    private final Runnable unlockRefresh=()->{if(alive){syncForeground();}};
    private android.content.BroadcastReceiver screenReceiver;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final LruCache<String,String> cache=new LruCache<>(600);
    private final Runnable scanTask=()->{scheduledAt=Long.MAX_VALUE;scan();};
    private final Runnable modelRetry=this::loadModel;
    private SharedPreferences prefs;
    private WindowManager wm;
    private Layer layer;
    private Translator translator;
    private TextRecognizer recognizer;
    private boolean ready, loading, busy, alive;
    private long generation, lastOcr;
    private String lastPackage="";
    private int lastWindow=-1;
    private boolean pending;
    private long scheduledAt = Long.MAX_VALUE;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static final class Item {
        final String source; final Rect rect; final String result;
        Item(String source, Rect rect, String result){this.source=source;this.rect=new Rect(rect);this.result=result;}
    }
    @Override protected void onServiceConnected(){
        super.onServiceConnected(); alive=true; prefs=getSharedPreferences("glass",MODE_PRIVATE); wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.RUSSIAN).build());
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
        try{root=getRootInActiveWindow();return root==null?"":String.valueOf(root.getPackageName());}
        catch(RuntimeException e){return "";}finally{if(root!=null)root.recycle();}
    }
    private void stopWork(){
        generation++;pending=false;h.removeCallbacks(scanTask);scheduledAt=Long.MAX_VALUE;
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
        android.widget.TextView v=new android.widget.TextView(this);
        v.setText("⏻");v.setTextSize(25);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER);
        v.setClickable(true);v.setFocusable(false);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setOnClickListener(w->{
            String pkg=foreground();
            if(!session.isCurrent(pkg)||!session.supportedForeground()){syncForeground();return;}
            if(session.enabled()){session.stop();stopWork();updatePower();}
            else {session.start();stopWork();updatePower();loadModel();schedule(0);
                if(!ready)android.widget.Toast.makeText(this,"Подготовка языковой модели. Первый запуск требует интернет.",android.widget.Toast.LENGTH_SHORT).show();}
        });
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(44),dp(44),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=dp(6);p.y=dp(6);wm.addView(v,p);power=v;
    }
    private void updatePower(){
        if(power==null)return;
        boolean on=enabled();
        if(Boolean.valueOf(on).equals(power.getTag()))return;
        power.setTag(on);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(on?Color.rgb(22,163,74):Color.rgb(190,30,45));bg.setStroke(dp(2),Color.WHITE);
        power.setBackground(bg);power.setContentDescription(on?"Остановить перевод":"Начать перевод");
    }
    private void removePower(){
        if(power!=null){try{wm.removeViewImmediate(power);}catch(RuntimeException ignored){}power=null;}
    }
    private void loadModel(){
        if(!enabled()||ready||loading||translator==null)return; loading=true;
        translator.downloadModelIfNeeded().addOnCompleteListener(t->{loading=false;if(!alive)return;ready=t.isSuccessful();if(ready)schedule(0);else if(enabled()){h.removeCallbacks(modelRetry);h.postDelayed(modelRetry,30000);}});
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        int type=e.getEventType();
        boolean own=getPackageName().contentEquals(e.getPackageName()==null?"":e.getPackageName());
        if(own && type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type!=AccessibilityEvent.TYPE_WINDOWS_CHANGED)return;
        syncForeground();
        if(!enabled()){clear();return;}
        if(own)return;
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED||type==AccessibilityEvent.TYPE_VIEW_SCROLLED||type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED){
            generation++;
            refreshKnown();
        }
        schedule(type==AccessibilityEvent.TYPE_VIEW_SCROLLED?100:60);
    }
    private void invalidatePage(){generation++; clear();}
    private void schedule(long delay){if(!enabled())return;long at=SystemClock.uptimeMillis()+delay;
        if(at>=scheduledAt)return;h.removeCallbacks(scanTask);scheduledAt=at;h.postAtTime(scanTask,at);}
    private void refreshKnown(){
        AccessibilityNodeInfo root=null;
        try{root=getRootInActiveWindow();
            if(root==null||!session.isCurrent(String.valueOf(root.getPackageName()))){clear();return;}
            List<Item> items=new ArrayList<>();boolean[] sensitive={false};collect(root,items,sensitive,new int[]{0});
            if(sensitive[0]){clear();return;}
            BigDecimal rate=currentRate();List<Item> known=new ArrayList<>();
            for(Item i:items){String text=knownText(i.source,rate);if(text!=null)known.add(new Item(i.source,i.rect,text));}
            publish(known,generation);
        }catch(RuntimeException e){clear();}finally{if(root!=null)root.recycle();}
    }
    private BigDecimal currentRate(){try{return TradeText.parseRate(prefs.getString("rate",""));}catch(RuntimeException e){return null;}}
    private String knownText(String source,BigDecimal rate){String text=TradeText.convert(source,rate);if(text==null)text=TradeText.exact(source);if(text==null)text=cache.get(source);return text;}
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
            root=getRootInActiveWindow(); if(root==null){invalidatePage();return;}
            String pkg=String.valueOf(root.getPackageName()); int win=root.getWindowId();
            if(!session.isCurrent(pkg)){invalidatePage();lastPackage="";lastWindow=-1;return;}
            if(!pkg.equals(lastPackage)||win!=lastWindow){invalidatePage();lastPackage=pkg;lastWindow=win;}
            List<Item> items=new ArrayList<>(); boolean[] sensitive={false}; collect(root,items,sensitive,new int[]{0});
            if(sensitive[0]){invalidatePage();return;}
            long token=++generation; busy=true; pending=false;
            if(Build.VERSION.SDK_INT>=34&&!hasFocusedEditor(root)&&SystemClock.uptimeMillis()-lastOcr>=1300){
                Rect bounds=windowBounds(win); if(bounds!=null){lastOcr=SystemClock.uptimeMillis(); capture(win,bounds,items,token);return;}
            }
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
        if(++count[0]>450||out.size()>=65||!n.isVisibleToUser())return;
        if(n.isPassword()){sensitive[0]=true;return;}
        if(n.isEditable())return;
        CharSequence cs=n.getText(); String s=cs==null?"":cs.toString().trim();
        if(s.contains("支付密码")||s.contains("银行卡号")||s.contains("验证码")||s.contains("身份证号")){sensitive[0]=true;return;}
        if(!s.isEmpty()&&s.length()<=240&&TradeText.candidate(s)){Rect r=new Rect();n.getBoundsInScreen(r);if(r.width()>4&&r.height()>4)out.add(new Item(s,r,null));}
        for(int i=0;i<n.getChildCount()&&!sensitive[0]&&count[0]<450&&out.size()<65;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){try{collect(c,out,sensitive,count);}finally{c.recycle();}}}
    }
    @android.annotation.TargetApi(34)
    private void capture(int win,Rect bounds,List<Item> items,long token){
        try{takeScreenshotOfWindow(win,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onFailure(int error){if(token!=generation){finish();return;}if(error==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW){invalidatePage();finish();}else resolve(items,token);}
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
                        if(t.isSuccessful()){
                            float sx=(float)bounds.width()/image.getWidth(),sy=(float)bounds.height()/image.getHeight();
                            boolean sensitive=false;
                            for(Text.TextBlock block:t.getResult().getTextBlocks()){
                                String s=block.getText();if(s.contains("支付密码")||s.contains("银行卡号")||s.contains("验证码")||s.contains("身份证号")){sensitive=true;break;}
                                Rect r=block.getBoundingBox();if(r==null||s.length()>240||items.size()>=80||!TradeText.candidate(s))continue;
                                Rect screen=new Rect(bounds.left+Math.round(r.left*sx),bounds.top+Math.round(r.top*sy),bounds.left+Math.round(r.right*sx),bounds.top+Math.round(r.bottom*sy));
                                boolean overlap=false;for(Item i:items)if(Rect.intersects(i.rect,screen)){overlap=true;break;}
                                if(!overlap)items.add(new Item(s,screen,null));
                            }
                            if(sensitive){invalidatePage();finish();return;}
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
        List<Item> done=new ArrayList<>();List<Item> missing=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Item i:items){if(!seen.add(i.source+"@"+i.rect.toShortString()))continue;
            String s=knownText(i.source,rate);
            if(s!=null)done.add(new Item(i.source,i.rect,s));else if(ready&&TradeText.chinese(i.source))missing.add(i);}
        publish(done,token); translateNext(missing,0,done,token);
    }
    private void translateNext(List<Item> missing,int index,List<Item> done,long token){
        if(!alive||token!=generation||index>=missing.size()){finish();return;}
        Item i=missing.get(index); String cached=cache.get(i.source);
        if(cached!=null){done.add(new Item(i.source,i.rect,cached));publish(done,token);h.post(()->translateNext(missing,index+1,done,token));return;}
        try{translator.translate(i.source).addOnCompleteListener(t->{
            if(!alive){finish();return;}
            if(t.isSuccessful()){String s=t.getResult();if(s!=null&&!s.trim().isEmpty()&&TradeText.numbersPreserved(i.source,s)){cache.put(i.source,s);if(token==generation){done.add(new Item(i.source,i.rect,s));publish(done,token);}}}
            if(token!=generation){finish();return;}
            h.post(()->translateNext(missing,index+1,done,token));
        });}catch(RuntimeException e){finish();}
    }
    private void publish(List<Item> done,long token){
        if(!enabled()||token!=generation||!session.isCurrent(foreground()))return;
        try{ensureLayer();layer.items=new ArrayList<>(done);layer.invalidate();}catch(RuntimeException e){clear();}
    }
    private void finish(){busy=false;if(!alive)return;if(pending){pending=false;schedule(60);}else if(enabled())schedule(1500);}
    @Override public void onInterrupt(){session.stop();stopWork();updatePower();}
    @Override public void onDestroy(){alive=false;generation++;h.removeCallbacksAndMessages(null);if(prefs!=null&&listener!=null)prefs.unregisterOnSharedPreferenceChangeListener(listener);
        session.stop();removePower();if(screenReceiver!=null){try{unregisterReceiver(screenReceiver);}catch(RuntimeException ignored){}screenReceiver=null;}
        if(layer!=null){try{wm.removeViewImmediate(layer);}catch(RuntimeException ignored){}layer=null;}
        if(translator!=null)translator.close();if(recognizer!=null)recognizer.close();cache.evictAll();super.onDestroy();}
    private final class Layer extends View {
        List<Item> items=Collections.emptyList(); final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); final int[] offset=new int[2];
        Layer(){super(GlassService.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);getLocationOnScreen(offset);
            for(Item i:items){Rect r=new Rect(i.rect);r.offset(-offset[0],-offset[1]);if(!r.intersect(0,0,getWidth(),getHeight()))continue;
                float size=Math.min(18*getResources().getDisplayMetrics().scaledDensity,r.height()*.75f);float min=9*getResources().getDisplayMetrics().scaledDensity;
                paint.setTextSize(size);String text=i.result.replace('\n',' ');while(size>min&&paint.measureText(text)>r.width()-4){size-=.5f;paint.setTextSize(size);}
                if(size<min||paint.measureText(text)>r.width()-4)continue;
                paint.setColor(Color.rgb(250,250,250));c.drawRect(r,paint);paint.setColor(Color.rgb(26,35,40));
                Paint.FontMetrics f=paint.getFontMetrics();c.drawText(text,r.left+2,r.exactCenterY()-(f.ascent+f.descent)/2,paint);
            }
        }
    }
}
