package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/** Compile-only external framework API. */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam parameter) throws Throwable;
}
