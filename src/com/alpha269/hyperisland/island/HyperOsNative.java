package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.pm.ApplicationInfo;

/** Android boundary for the source-backed HyperOS Rust core. */
public final class HyperOsNative {
    private static boolean loaded;
    private HyperOsNative() {}

    public static synchronized void load(Context context) throws Exception {
        if (loaded) return;
        ApplicationInfo module = context.getPackageManager()
            .getApplicationInfo("com.alpha269.hyperisland", 0);
        loadPath(module.sourceDir + "!/lib/arm64-v8a/libhyperos_island.so");
    }

    /** Explicit path only for isolated validation of the built native library. */
    public static synchronized void loadPath(String path) {
        if (loaded) return;
        System.load(path);
        loaded = true;
    }
    public static boolean isLoaded(){return loaded;}
    public static native long nativeCreatePresentation();
    public static native void nativeDestroyPresentation(long handle);
    public static native void nativeSyncPresentation(long handle,long[] ids,long preferred);
    public static native void nativePresentationCommand(long handle,int command,long item);
    /** Output: big, small, hidden count, hidden keys; zero is Empty. */
    public static native void nativePresentationSnapshot(long handle,long[] out);
    public static native void nativePresentationPreview(long handle,int direction,long[] out);

    /**
     * dimensions: left,right,cutout,screenWidth,screenHeight,small,gap,minBig,margin.
     * limits: maxWidth,clockWidth,batteryWidth.
     * out: singleWidth,left,right,x,margin,pairBigWidth,pairLeft,pairRight,pairX.
     */
    public static native void nativeCapsule(int[] dimensions, float[] limits,
        boolean rtl, boolean folded, int[] out);

    /** Input8 per channel: value,target,velocity,damping,response,mass,threshold,first. */
    public static native boolean nativeSpringBatch(double[] input, int count,
        double dtSeconds, double[] out);

    public static native void nativeState(int kind, double[] metrics,
        int background, double[] out);

    public static native void nativeChannels(int transition, float animationScale,
        boolean transparent, double[] out);

    public static native long nativeCreateAnimator();
    public static native void nativeDestroyAnimator(long handle);
    public static native void nativeSnapAnimator(long handle,int kind,double[] metrics,int background);
    public static native void nativePressAnimator(long handle,int kind,double[] metrics,int background,float animationScale,boolean pressed);
    public static native boolean nativeSwipeExpanded(long handle,double[] metrics,float dx,float dy);
    public static native void nativeResetSwipe(long handle,double[] metrics);
    public static native void nativeSwipeCapsule(long handle,double[] metrics,int transition,float dx);
    public static native void nativeResetCapsule(long handle,double[] metrics,int kind);
    public static native void nativeTransition(long handle, int transition,
        double[] metrics, int background, float scale, boolean transparent);
    /** Output: 28 values, 28 velocities, 28 targets; return active channel mask. */
    public static native long nativeAnimatorFrame(long handle, long deltaNanos,
        long predictedPeriodNanos, float ratio, double[] out);

    public static native void nativeBlurArgb(int[] pixels, int width, int height, int radius);
    public static native void nativeInitializePalette(String exactNativeLibrary);
    /** Output: source seed, gradient top, gradient bottom, native seed fallback flag. */
    public static native void nativeArtworkPalette(int[] pixels, int width, int height, int[] out);
    public static native long nativeCreateNormalCache();
    public static native void nativeDestroyNormalCache(long cache);
    /** Output: isCircle, radius, edgeTenths, powerHundredths, quarterImageSide. */
    public static native void nativeNormalKey(float radius,float edge,float power,boolean circle,int[] out);
    public static native long nativeNormalCacheGet(long cache,int[] key);
    public static native int nativeNormalCachePut(long cache,int[] key,long image,long[] retired);
    public static native int nativeNormalCacheContext(long cache,long context,long[] retired);
    public static native void nativeCornerMips(byte[] rgba,int[] argb);
    public static native void nativeGlassUniforms(float width,float height,float angle,float canvasScale,float textureScale,float sdfScale,boolean mask,float[] out);
    public static native void nativeClockCardPlan(int style,boolean countdown,boolean paused,boolean hasTime,int[] out);
    public static native long nativeCreateGlow();
    public static native long nativeCreateMusicBackground();
    public static native void nativeDestroyMusicBackground(long handle);
    /** Output: shader time followed by interpolated RGBA. */
    public static native boolean nativeMusicBackgroundFrame(long handle,long nowMs,int event,int color,float[] out);
    public static native long nativeCreateFlashlight(String keyframes);
    public static native void nativeSetFlashlightVisible(long handle,boolean visible,long nowNanos);
    /** Packet: progress, then beam1/5/6, each PointF2,LineY,E,Highband,RGB3,Alpha,ScaleX,Shift,Dispersion. */
    public static native boolean nativeFlashlightFrame(long handle,long nowNanos,long frameNanos,float[] out);
    public static native void nativeDestroyFlashlight(long handle);
    public static native long nativeCreateWave();
    /** Events: 0 frame, 1 source ShaderDriver.init, 2 stop. */
    public static native float nativeWaveFrame(long handle,long nowMs,int event);
    public static native void nativeDestroyWave(long handle);
    public static native void nativeDestroyGlow(long handle);
    public static native boolean nativeGlowFrame(long handle, long nowMs, int event, float animationScale, float[] out);
    public static ClockCardPlan clockCardPlan(IslandItem item){
        int[] values=new int[6];nativeClockCardPlan(item.clockStyle,item.chronoCountDown,item.pausedMs>=0,
            item.clockStyle==0?item.hasChronometer():item.clockText!=null&&item.clockText.length()>0,values);
        return new ClockCardPlan(values);
    }
    public static final class ClockCardPlan {
        public final String expanded,left,small;public final int type,grammar;public final boolean autoplay;
        private ClockCardPlan(int[] values){
            expanded=new String[]{"hourglass_big","stopwatch_big","alarmBig"}[values[0]];
            left=new String[]{"hourglass","stopwatch_big","alarmBig"}[values[1]];
            small=new String[]{"hourglass","stopwatch","alarmSmall"}[values[2]];
            type=values[3];autoplay=values[4]!=0;grammar=values[5];
        }
    }
}
