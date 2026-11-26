package kz.steklo.translator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.List;

public final class TradeText {
    private TradeText() {}
    private static final Map<String,String> TERMS = new java.util.HashMap<>();
    static {
        TERMS.put("包邮","Бесплатная доставка");
        TERMS.put("颜色款式","Цвет / модель");
        TERMS.put("颜色\n款式","Цвет / модель");
        TERMS.put("快抢光","Почти раскуплено");
        TERMS.put("先用后付","Сначала товар, потом оплата");
        TERMS.put("先用后付 | 支持0元下单，确认收货后再付款","Заказ за 0 юаней, оплата после получения");
        TERMS.put("无需等待","Без ожидания");
        TERMS.put("直接拼成","Купить совместно сразу");
        TERMS.put("一次下单多款","Несколько моделей в заказе");
        TERMS.put("48小时内发货","Отправка за 48 ч");
        TERMS.put("查看全部","Смотреть все");
        TERMS.put("店铺","Магазин");
        TERMS.put("收藏","Избранное");
        TERMS.put("客服","Поддержка");
        TERMS.put("免拼购买","Купить без группы");
        TERMS.put("店铺回购率超95%同行，这些人已拼","Доля повторных покупок выше, чем у 95% аналогичных магазинов. Эти покупатели уже участвуют.");
        TERMS.put("畅销榜","Бестселлеры");
        TERMS.put("7天无理由退货 · 48小时内发货 · 全场包邮 · 先用后付","Возврат 7 дней · Отправка за 48 ч · Бесплатная доставка · Оплата после получения");
        TERMS.put("Free shipping","Бесплатная доставка");
        TERMS.put("Buy now","Купить сейчас");
        TERMS.put("Add to cart","В корзину");
        TERMS.put("Reviews","Отзывы");
        TERMS.put("View all","Посмотреть все");
        TERMS.put("In stock","В наличии");
        TERMS.put("Shop","Магазин");
        TERMS.put("退货包运费","Доставка при возврате покрывается");
        TERMS.put("7天无理由退货","Возврат за 7 дней без объяснения причин");
        TERMS.put("24小时内发货","Отправка в течение 24 часов");
        TERMS.put("全场包邮","Бесплатная доставка всех товаров");
        TERMS.put("商品评价","Отзывы о товаре");
        TERMS.put("服务好","Хорошее обслуживание");
        TERMS.put("质量好","Хорошее качество");
        TERMS.put("价格实惠","Выгодная цена");
        TERMS.put("效果不错","Хороший результат");
        TERMS.put("包装不错","Хорошая упаковка");
        TERMS.put("做工精细","Аккуратное исполнение");
        TERMS.put("尺寸合适","Подходящий размер");
        TERMS.put("颜色正","Цвет соответствует");
        TERMS.put("进店","В магазин");
        TERMS.put("起批量","Минимальная партия");
        TERMS.put("一件代发","Отправка от 1 шт. (дропшиппинг)");
        TERMS.put("拼单价","Цена совместной покупки");
        TERMS.put("单独购买","Купить отдельно");
        TERMS.put("现货","В наличии");
        TERMS.put("预售","Предзаказ");
        TERMS.put("不含税","Без налога");
        TERMS.put("运费到付","Доставка за счёт получателя");
        TERMS.put("定金","Задаток");
        TERMS.put("尾款","Остаток к оплате");
        TERMS.put("立即购买","Купить сейчас");
        TERMS.put("搜索商品","Поиск товаров");
        TERMS.put("加入购物车","В корзину");
        TERMS.put("确认订单","Подтвердить заказ");
        TERMS.put("退款","Возврат денег");
        TERMS.put("颜色分类","Цвет");
        TERMS.put("尺码","Размер");
        TERMS.put("元","юань");
        TERMS.put("首页","Главная"); TERMS.put("搜索","Поиск"); TERMS.put("购物车","Корзина");
        TERMS.put("我的订单","Мои заказы"); TERMS.put("待付款","Ожидает оплаты");
        TERMS.put("待发货","Ожидает отправки"); TERMS.put("待收货","Ожидает получения");
        TERMS.put("已发货","Отправлено"); TERMS.put("联系客服","Связаться с поддержкой");
        TERMS.put("商品详情","Описание товара"); TERMS.put("规格","Характеристики");
        TERMS.put("库存","Остаток"); TERMS.put("运费","Стоимость доставки");
        TERMS.put("满减","Скидка от суммы заказа"); TERMS.put("优惠券","Купон");
        TERMS.put("拼单","Совместная покупка"); TERMS.put("发货地","Место отправки");
        TERMS.put("七天无理由退货","Возврат за 7 дней без объяснения причин");
    }
    private static final Pattern HAN=Pattern.compile("\\p{IsHan}");
    private static final String AMOUNT="[0-9]+(?:\\.[0-9]{1,2})?";
    private static final Pattern PRICE=Pattern.compile("^(?:[¥￥]\\s*("+AMOUNT+")|("+AMOUNT+")\\s*元)$");
    private static final Pattern NUM=Pattern.compile("[0-9]+(?:[.,][0-9]+)?");
    public static boolean chinese(String s) { return s!=null && HAN.matcher(s).find(); }
    public static boolean english(String s){return s!=null&&!chinese(s)&&!s.matches("(?s).*\\p{IsCyrillic}.*")&&s.matches("(?s).*[A-Za-z]{2,}.*")&&!s.matches("^(?:[A-Z]{2,5}|[0-9]+\\s*(?i:cm|mm|kg|gb|mb|mah|w|v))$");}
    public static boolean candidate(String s){return s!=null&&(chinese(s)||english(s)||PRICE.matcher(s.trim()).matches());}
    public static String exact(String s) {
        if(s==null)return null;
        String key=s.trim();String direct=TERMS.get(key);if(direct!=null)return direct;
        Matcher m=Pattern.compile("^商品评价\\(([0-9,]+)\\)$").matcher(key);
        if(m.matches())return "Отзывы ("+m.group(1)+")";
        m=Pattern.compile("^库存剩([0-9]+)件\\s*免拼购买$").matcher(key);
        if(m.matches())return "Ост. "+m.group(1)+" шт. · Купить";
        m=Pattern.compile("^库存剩([0-9]+)件$").matcher(key);
        if(m.matches())return "Осталось "+m.group(1)+" шт.";
        m=Pattern.compile("^差([0-9]+)人$").matcher(key);
        if(m.matches())return "Нужно ещё "+m.group(1)+" чел.";
        m=Pattern.compile("^([0-9]+)人好评$").matcher(key);
        if(m.matches())return m.group(1)+" хороших отзывов";
        m=Pattern.compile("^([0-9]+)人收藏$").matcher(key);
        if(m.matches())return "В избранном у "+m.group(1);
        m=Pattern.compile("^全店总售([0-9]+(?:\\.[0-9]+)?)万(\\+?)件$").matcher(key);
        if(m.matches())return "Продано "+new BigDecimal(m.group(1)).multiply(new BigDecimal("10000")).stripTrailingZeros().toPlainString()+m.group(2)+" шт.";
        m=Pattern.compile("^一周内([0-9]+)(\\+?)人买过$").matcher(key);
        if(m.matches())return "За неделю купили "+m.group(1)+m.group(2);
        return null;
    }
    public static boolean acceptTranslation(String source,String value){
        return value!=null&&!value.trim().isEmpty()&&!chinese(value)&&numbersPreserved(source,value);
    }
    public static BigDecimal parseRate(String s) {
        if(s==null||!s.trim().matches("[0-9]{1,6}([.,][0-9]{1,6})?"))throw new IllegalArgumentException("Некорректный формат курса");
        BigDecimal r=new BigDecimal(s.trim().replace(',','.'));
        if(r.signum()<=0 || r.compareTo(new BigDecimal("100000"))>0 || r.scale()>6) throw new IllegalArgumentException("Курс вне диапазона");
        return r;
    }
    public static String convert(String s, BigDecimal rate) {
        if(s==null||rate==null||rate.signum()<=0||rate.compareTo(new BigDecimal("100000"))>0) return null;
        Matcher m=PRICE.matcher(s.trim()); if(!m.matches()) return null;
        String a=m.group(1)!=null?m.group(1):m.group(2);
        BigDecimal v=new BigDecimal(a).multiply(rate).setScale(0,RoundingMode.HALF_UP);
        return "¥"+a+" ≈ "+v.toPlainString()+" ₸";
    }
    private static List<String> numbers(String s) { List<String> out=new ArrayList<>(); Matcher m=NUM.matcher(s); while(m.find()) out.add(m.group()); return out; }
    public static boolean numbersPreserved(String original,String translated) { return numbers(original).equals(numbers(translated)); }
}
