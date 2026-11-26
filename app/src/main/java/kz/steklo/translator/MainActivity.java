package kz.steklo.translator;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.widget.*;
import android.graphics.Color;
import android.view.ViewGroup;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    @Override public void onCreate(Bundle b){ super.onCreate(b); prefs=getSharedPreferences("glass",MODE_PRIVATE);
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); int p=(int)(20*getResources().getDisplayMetrics().density); l.setPadding(p,p,p,p);
        TextView title=new TextView(this); title.setText("Стекло Переводчик"); title.setTextSize(32); title.setTextColor(Color.rgb(0,100,95)); l.addView(title);
        TextView info=new TextView(this); info.setText("Китайский и английский → русский. Перевод поверх Pinduoduo, Taobao, 1688 и поддерживаемых браузеров. Первый запуск требует загрузки языковых моделей. Снимки не сохраняются. Перевод может ошибаться."); info.setTextSize(16); l.addView(info);
        TextView instructions=new TextView(this); instructions.setText("1. Один раз разрешите работу службы в специальных возможностях.\n2. Откройте магазин или браузер.\n3. Нажмите красную кнопку питания в левом верхнем углу экрана — она станет зелёной. Повторное нажатие отключит перевод. Удерживайте кнопку для диагностики распознавания и загрузки моделей."); instructions.setTextSize(17); l.addView(instructions);
        Button permission=new Button(this); permission.setText("Разрешить плавающую кнопку"); permission.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); l.addView(permission);
        TextView label=new TextView(this); label.setText("Курс KZT за 1 CNY (ручной; пусто — выключено)"); l.addView(label);
        EditText rate=new EditText(this); rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); rate.setText(prefs.getString("rate","")); l.addView(rate);
        Button save=new Button(this); save.setText("Сохранить курс"); l.addView(save); save.setOnClickListener(v->{ String s=rate.getText().toString().trim(); try { if(!s.isEmpty()) s=TradeText.parseRate(s).toPlainString(); prefs.edit().putString("rate",s).apply(); Toast.makeText(this,"Сохранено",Toast.LENGTH_SHORT).show(); } catch(RuntimeException e){rate.setError("Введите положительный курс");}});
        TextView note=new TextView(this); note.setText("Android 14+: распознавание текста на изображениях. Android 10–13: только доступный текст интерфейса. Перевод при прокрутке может отставать. При переходе в другое приложение перевод выключается. Для полного отключения отключите службу в настройках Android."); l.addView(note);
        TextView credits=new TextView(this);credits.setText("Облачные словари: FreeDict / WikDict / Wiktionary, CC BY-SA 3.0. Данные нормализованы, возможны неточности. При поиске отправляется только язык и короткий индекс словарного раздела, не сам текст экрана.\nИсточники: https://freedict.org/downloads/\nЛицензия: https://creativecommons.org/licenses/by-sa/3.0/");credits.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);l.addView(credits);
        ScrollView scroll=new ScrollView(this); scroll.addView(l); setContentView(scroll);
    }
}
