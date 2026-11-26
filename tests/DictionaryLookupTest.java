import kz.steklo.translator.DictionaryLookup;
public class DictionaryLookupTest {
 static int n;
 static void check(boolean ok){if(!ok)throw new AssertionError("dictionary "+(n+1));n++;}
 public static void main(String[] args){
    check(DictionaryLookup.bucket("price").equals("78a"));
    check(DictionaryLookup.bucket("cotton").equals("a45"));
    check(DictionaryLookup.bucket("material").equals("eec"));
    check(DictionaryLookup.language("價格").equals("zh"));
    check(DictionaryLookup.language("cotton").equals("en"));
    check(DictionaryLookup.language("Free shipping").equals("en"));
    check(DictionaryLookup.language("¥10.50")==null);
    check(DictionaryLookup.language("12345")==null);
    check(DictionaryLookup.language("a@b.com")==null);
    check(DictionaryLookup.language("русский")==null);
    check(DictionaryLookup.language("这是非常长的中文完整句子") == null);
    check(DictionaryLookup.clean("оде́жда").equals("одежда"));
    check(DictionaryLookup.clean("цена; стоимость")==null);
    check(DictionaryLookup.clean("[[хлопок]]")==null);
    check(DictionaryLookup.clean("эк.")==null);
    check(DictionaryLookup.clean("10 рублей")==null);
    check(DictionaryLookup.clean("хлопок").equals("хлопок"));
    System.out.println("PASS: "+n+" dictionary checks");
 }
}
