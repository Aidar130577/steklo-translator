package kz.steklo.translator;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
/** Ephemeral, explicitly activated translation for one foreground app. */
public final class TranslationSession {
    private static final Set<String> SUPPORTED=new HashSet<>(Arrays.asList(
        "com.xunmeng.pinduoduo","com.taobao.taobao","com.alibaba.wireless",
        "com.alibaba.aliexpresshd","com.eg.android.AlipayGphone.shopping",
        "com.android.chrome","org.mozilla.firefox","com.sec.android.app.sbrowser",
        "com.mi.globalbrowser","com.UCMobile","com.microsoft.emmx","com.opera.browser"));
    private String foreground="";
    private boolean active=false;
    public boolean updateForeground(String pkg){
        String next=pkg!=null&&SUPPORTED.contains(pkg)?pkg:"";
        if(next.equals(foreground))return false;
        foreground=next;active=false;return true;
    }
    public boolean supportedForeground(){return !foreground.isEmpty();}
    public boolean isCurrent(String pkg){return supportedForeground()&&foreground.equals(pkg);}
    public boolean start(){active=supportedForeground();return active;}
    public void stop(){active=false;}
    public boolean enabled(){return active&&supportedForeground();}
}
