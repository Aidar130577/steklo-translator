package kz.steklo.translator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
public final class DictionaryLookup {
    private DictionaryLookup(){}
    // A short MD5 prefix is a public data shard index, not authentication or encryption.
    public static String bucket(String s){
        try{byte[] b=MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT,"%02x%01x",b[0]&255,(b[1]&255)>>>4);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static String language(String s){
        if(s==null)return null;
        if(s.matches("\\p{IsHan}{2,10}"))return "zh";
        if(s.matches("[A-Za-z]{3,24}(?:[ -][A-Za-z]{2,24}){0,1}"))return "en";
        return null;
    }
    public static String clean(String s){
        if(s==null)return null;
        String t=s.replace("\u0301","").trim();
        // No alternatives, markup, abbreviated fragments, numerical or contextual definitions.
        if(t.length()<3||t.length()>70||!t.matches("[А-Яа-яЁё]+(?:[ -][А-Яа-яЁё]+){0,4}"))return null;
        return t;
    }
}
