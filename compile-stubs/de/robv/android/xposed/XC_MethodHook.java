package de.robv.android.xposed;

/** Compile-only signatures. LSPosed supplies the implementation at runtime. */
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { throw new UnsupportedOperationException("Compile-only API"); }
        public void setResult(Object result) { throw new UnsupportedOperationException("Compile-only API"); }
        public boolean hasThrowable() { throw new UnsupportedOperationException("Compile-only API"); }
    }
    public final class Unhook {
        public void unhook() { throw new UnsupportedOperationException("Compile-only API"); }
    }
    protected void beforeHookedMethod(MethodHookParam parameter) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam parameter) throws Throwable {}
}
