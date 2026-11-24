package com.alpha269.hyperisland.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.alpha269.hyperisland.island.HyperIslandWindow;

/** Source-built module entry for the selected SystemUI and Clock scopes (incoming calls stay native One UI). */
public final class Entry implements IXposedHookLoadPackage {
    private static boolean installed;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam parameter) {
        if(installed)return;
        try {
            if("com.android.systemui".equals(parameter.packageName)
                &&parameter.packageName.equals(parameter.processName)) {
                OneUiHooks.install(parameter.classLoader,new HyperIslandWindow());installed=true;
            }else if(HyperOsAlarmHooks.APP.equals(parameter.packageName)) {
                HyperOsAlarmHooks.install(parameter.classLoader);installed=true;
            }
        }catch(Throwable failure){android.util.Log.e("HyperIsland","Module setup failed",failure);}
    }
}
