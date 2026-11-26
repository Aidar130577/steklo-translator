import kz.steklo.translator.TradeText;
public class TradeTextTest {
    private static int count;
    private static void check(boolean ok){count++;if(!ok)throw new AssertionError("test "+count);}
    public static void main(String[] args){
        var rate=TradeText.parseRate("70,25");
        check(TradeText.convert("¥129.00",rate).equals("¥129.00 ≈ 9062 ₸"));
        check(TradeText.convert("￥85",rate).equals("¥85 ≈ 5971 ₸"));
        check(TradeText.convert("10元",rate).equals("¥10 ≈ 703 ₸"));
        check(TradeText.convert("¥10-20",rate)==null);
        check(TradeText.convert("¥10",null)==null);
        check(TradeText.convert("10",rate)==null);
        check(TradeText.exact("包邮").equals("Бесплатная доставка"));
        check(TradeText.exact("不含税").equals("Без налога"));
        check(TradeText.exact("未知") == null);
        check(TradeText.chinese("无线耳机"));
        check(!TradeText.chinese("Bluetooth"));
        check(TradeText.numbersPreserved("12件 30cm","12 шт. 30 см"));
        check(!TradeText.numbersPreserved("12件","21 шт."));
        check(!TradeText.numbersPreserved("12.50元","1250 юаней"));
        for(String invalid:new String[]{"0","-1","","NaN","100001","1.1234567"}){boolean rejected=false;try{TradeText.parseRate(invalid);}catch(RuntimeException e){rejected=true;}check(rejected);}
        check(TradeText.candidate("¥88.80"));check(TradeText.candidate("￥10"));check(TradeText.candidate("15元"));check(!TradeText.candidate("$100"));check(!TradeText.candidate("129"));check(!TradeText.candidate(null));
        check(TradeText.exact(null)==null);check(TradeText.exact("待发货").equals("Ожидает отправки"));check(TradeText.exact("拼单").equals("Совместная покупка"));
        check(TradeText.convert(null,rate)==null);check(TradeText.convert("¥10",java.math.BigDecimal.ZERO)==null);
        check(TradeText.convert("¥0.01",rate).equals("¥0.01 ≈ 1 ₸"));check(TradeText.convert("¥10.001",rate)==null);
        for(String invalid:new String[]{"1e2","+1","1E+999999999","9999999","1,2,3",null}){boolean rejected=false;try{TradeText.parseRate(invalid);}catch(RuntimeException e){rejected=true;}check(rejected);}
        check(TradeText.english("Free shipping"));
        check(TradeText.candidate("Customer reviews"));
        check(!TradeText.english("Отзывы покупателей"));
        check(!TradeText.english("中国商品"));
        check(!TradeText.english("12345"));
        check(!TradeText.english("12 cm"));
        check(TradeText.english("Shop"));
        check(TradeText.exact("Buy now").equals("Купить сейчас"));
        check(TradeText.exact("全店总售79.3万+件").equals("Продано 793000+ шт."));
        check(TradeText.exact("库存剩8件 免拼购买").equals("Ост. 8 шт. · Купить"));
        check(TradeText.exact("商品评价(255)").equals("Отзывы (255)"));
        check(TradeText.exact("差10人").equals("Нужно ещё 10 чел."));
        check(TradeText.exact("一周内100+人买过").equals("За неделю купили 100+"));
        check(TradeText.acceptTranslation("库存8件","Осталось 8 шт."));
        check(!TradeText.acceptTranslation("库存8件","Осталось 9 шт."));
        check(!TradeText.acceptTranslation("衣服","衣服 одежда"));
        check(!TradeText.acceptTranslation("衣服",null));
        System.out.println("PASS: "+count+" checks");
    }
}
