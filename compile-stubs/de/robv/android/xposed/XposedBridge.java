package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Compile-only signatures. This class is excluded from the application DEX. */
public final class XposedBridge {
    private XposedBridge() {}
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> owner, String name, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Compile-only API");
    }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> owner, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Compile-only API");
    }
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Compile-only API");
    }
    public static void log(String message) { throw new UnsupportedOperationException("Compile-only API"); }
}
