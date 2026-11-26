import kz.steklo.translator.TranslationSession;
public class TranslationSessionTest {
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("Session test "+(n+1));n++;}
    public static void main(String[] args){
        var s=new TranslationSession();
        check(!s.enabled());check(!s.start());
        check(s.updateForeground("com.xunmeng.pinduoduo"));check(s.supportedForeground());
        check(!s.enabled());check(s.start());check(s.enabled());
        check(!s.updateForeground("com.xunmeng.pinduoduo"));check(s.enabled());
        s.stop();check(!s.enabled());check(s.start());
        check(s.updateForeground("com.android.chrome"));check(!s.enabled());check(s.isCurrent("com.android.chrome"));
        check(s.start());check(s.updateForeground("com.android.settings"));check(!s.enabled());check(!s.supportedForeground());
        check(!s.start());check(s.updateForeground("com.android.chrome"));check(!s.enabled());
        check(s.start());check(s.updateForeground(null));check(!s.enabled());
        System.out.println("PASS: "+n+" session checks");
    }
}
