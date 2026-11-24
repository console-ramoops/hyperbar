package com.alpha269.hyperisland;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(20*getResources().getDisplayMetrics().density);page.setPadding(padding,padding,padding,padding);
        TextView title=new TextView(this);title.setText("HyperBar 0.1.0");title.setTextSize(24);page.addView(title);
        TextView setup=new TextView(this);setup.setText("Enable HyperBar for System UI and Samsung InCall UI in LSPosed, then reboot.");
        setup.setPadding(0,padding,0,padding);page.addView(setup);
        toggle(page,"Enable HyperBar","enabled");
        toggle(page,"Incoming-call controls","incoming_calls");
        toggle(page,"Bluetooth device cards","bluetooth");
        setContentView(page);
    }
    private void toggle(LinearLayout page,String title,String key) {
        Switch control=new Switch(this);control.setText(title);
        android.content.SharedPreferences prefs=getSharedPreferences(SettingsProvider.PREFS,0);
        control.setChecked(prefs.getBoolean(key,true));
        control.setOnCheckedChangeListener((button,checked)->{
            prefs.edit().putBoolean(key,checked).apply();getContentResolver().notifyChange(SettingsProvider.URI,null);
        });page.addView(control,new LinearLayout.LayoutParams(-1,-2));
    }
}
