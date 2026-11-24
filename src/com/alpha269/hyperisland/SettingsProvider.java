package com.alpha269.hyperisland;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Read-only settings boundary; writes stay in the app's own settings screen. */
public final class SettingsProvider extends ContentProvider {
    public static final Uri URI=Uri.parse("content://com.alpha269.hyperisland.settings");
    public static final String PREFS="hyperisland";
    public static final String KEY_ENABLED="enabled";
    public static final String KEY_ISLAND_BLUR="island_blur";
    public static final String KEY_GLASS_EFFECT="glass_effect";
    public static final String KEY_BLUETOOTH="bluetooth";
    public static final String KEY_CHARGING_ANIM="charging_anim";
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String argument,Bundle extras) {
        if(!"values".equals(method))return Bundle.EMPTY;
        android.content.SharedPreferences prefs=getContext().getSharedPreferences(PREFS,0);
        Bundle result=new Bundle();
        result.putBoolean(KEY_ENABLED,prefs.getBoolean(KEY_ENABLED,true));
        result.putBoolean("incoming_calls",false);
        result.putBoolean(KEY_ISLAND_BLUR,prefs.getBoolean(KEY_ISLAND_BLUR,true));
        result.putBoolean(KEY_GLASS_EFFECT,prefs.getBoolean(KEY_GLASS_EFFECT,false));
        result.putBoolean(KEY_BLUETOOTH,prefs.getBoolean(KEY_BLUETOOTH,true));
        result.putBoolean(KEY_CHARGING_ANIM,prefs.getBoolean(KEY_CHARGING_ANIM,true));
        return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){return null;}
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/hyperisland-settings";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only settings");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read-only settings");}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read-only settings");}
}
