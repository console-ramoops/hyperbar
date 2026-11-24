package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.TextView;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Reads isolated donor resource tables; never loads a donor APK's classes. */
public final class HyperOsResources extends ContextThemeWrapper {
    private final Resources resources;
    private final String packageName;
    private final File fonts;
    private final Map<String,Typeface> typefaces = new HashMap<>();
    private LayoutInflater inflater;

    public HyperOsResources(Context base, String apk, String packageName,
        File fonts, boolean dark) throws Exception {
        super(base, android.R.style.Theme_Material_NoActionBar);
        this.packageName = packageName;
        this.fonts = fonts;
        AssetManager manager = AssetManager.class.getConstructor().newInstance();
        Object cookie = AssetManager.class.getMethod("addAssetPath",String.class)
            .invoke(manager,apk);
        if (((Number)cookie).intValue() == 0) {
            manager.close();
            throw new IllegalArgumentException("Could not open HyperOS resource table: " + apk);
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        resources = new Resources(manager,base.getResources().getDisplayMetrics(),config);
    }

    @Override public Resources getResources() {
        return resources == null ? super.getResources() : resources;
    }
    @Override public AssetManager getAssets() { return getResources().getAssets(); }
    @Override public String getPackageName() {
        return packageName == null ? super.getPackageName() : packageName;
    }

    public int id(String type,String name) {
        int id = resources.getIdentifier(name,type,packageName);
        if (id == 0) throw new IllegalArgumentException("Missing donor " + type + "/" + name);
        return id;
    }

    public View inflate(String layout,ViewGroup parent) {
        if (inflater == null) {
            inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            inflater.setFactory2(new LayoutInflater.Factory2() {
                @Override public View onCreateView(String name,Context context,AttributeSet attrs) {
                    return onCreateView(null,name,context,attrs);
                }
                @Override public View onCreateView(View parent,String name,Context context,
                    AttributeSet attrs) {
                    View result = null;
                    if (name.indexOf('.')>=0) {
                        try {
                            Class<?> factory=Class.forName("com.alpha269.hyperisland.island.HyperOsCardFactory");
                            result=(View)factory.getMethod("createVendorView",String.class,Context.class,AttributeSet.class)
                                .invoke(null,name,context,attrs);
                        } catch(ClassNotFoundException standaloneResourceProbe) {
                            // Resource-only probes do not load the application controller.
                        } catch(ReflectiveOperationException failure) {
                            throw new IllegalStateException("Could not create HyperOS view "+name,failure);
                        }
                    }
                    // Framework counterparts for text and timer plumbing. Native
                    // transition/text effects are bound by the card controller.
                    if (result==null && ("TextView".equals(name) || name.endsWith(".TimerTextEffectView")))
                        result = new TextView(context,attrs);
                    else if (result==null && ("Chronometer".equals(name) || name.endsWith(".HyperChronometer")
                        || name.endsWith(".HyperChronometerPlugin")))
                        result = new Chronometer(context,attrs);
                    if (result instanceof TextView) applyFont((TextView)result,attrs);
                    return result;
                }
            });
        }
        return inflater.inflate(resources.getLayout(id("layout",layout)),parent,false);
    }

    private void applyFont(TextView view,AttributeSet attrs) {
        TypedArray font = obtainStyledAttributes(attrs,new int[]{android.R.attr.fontFamily});
        String family;
        try { family = font.getString(0); } finally { font.recycle(); }
        applyFont(view,family);
    }

    public void applyFont(TextView view,String family) {
        if(family!=null&&fonts!=null)HyperOsFonts.apply(view,fonts,family);
    }
}
