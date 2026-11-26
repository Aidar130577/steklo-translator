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
        TextView info=new TextView(this); info.setText("Китайский → русский. Перевод поверх Pinduoduo, Taobao и 1688. Первый запуск требует загрузки языковых моделей. Снимки не сохраняются. Перевод может ошибаться."); info.setTextSize(16); l.addView(info);
        Switch active=new Switch(this); active.setText("Перевод включён"); active.setChecked(prefs.getBoolean("enabled",false)); l.addView(active);
        active.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("enabled",on).apply());
        Button permission=new Button(this); permission.setText("Открыть специальные возможности"); permission.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); l.addView(permission);
        TextView label=new TextView(this); label.setText("Курс KZT за 1 CNY (ручной; пусто — выключено)"); l.addView(label);
        EditText rate=new EditText(this); rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); rate.setText(prefs.getString("rate","")); l.addView(rate);
        Button save=new Button(this); save.setText("Сохранить курс"); l.addView(save); save.setOnClickListener(v->{ String s=rate.getText().toString().trim(); try { if(!s.isEmpty()) s=TradeText.parseRate(s).toPlainString(); prefs.edit().putString("rate",s).apply(); Toast.makeText(this,"Сохранено",Toast.LENGTH_SHORT).show(); } catch(RuntimeException e){rate.setError("Введите положительный курс");}});
        TextView note=new TextView(this); note.setText("Android 14+: распознавание текста на изображениях. Android 10–13: только доступный текст интерфейса. Перевод при прокрутке может отставать. Для отключения выключите переключатель или службу в настройках Android."); l.addView(note);
        ScrollView scroll=new ScrollView(this); scroll.addView(l); setContentView(scroll);
    }
}
