package de.robv.android.xposed.callbacks;
/** Compile-only external framework API. */
public abstract class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName,processName;
        public ClassLoader classLoader;
    }
}
