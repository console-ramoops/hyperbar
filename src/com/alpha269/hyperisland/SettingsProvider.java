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
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String argument,Bundle extras) {
        if(!"values".equals(method))return Bundle.EMPTY;
        android.content.SharedPreferences prefs=getContext().getSharedPreferences(PREFS,0);
        Bundle result=new Bundle();
        result.putBoolean("enabled",prefs.getBoolean("enabled",true));
        result.putBoolean("incoming_calls",prefs.getBoolean("incoming_calls",true));
        result.putBoolean("bluetooth",prefs.getBoolean("bluetooth",true));
        return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){return null;}
    @Override public String getType(Uri uri){return "vnd.android.cursor.item/hyperisland-settings";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only settings");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("Read-only settings");}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("Read-only settings");}
}
