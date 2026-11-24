package com.alpha269.hyperisland.island;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;
import android.widget.ImageView;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Original HyperOS Lottie parser/drawable/clock behind an Android-only view boundary. */
public final class HyperOsLottieView extends ImageView implements HyperOsCardFactory.AnimationTarget {
    private static final String APP_ATTRIBUTES="http://schemas.android.com/apk/res-auto";
    private static EngineApi api;
    private static String runtimePath;
    private Drawable engine;
    private ValueAnimator clock;
    private Choreographer.FrameCallback callback;
    private ImageView resourceRelay;
    private Object gradientCallback;
    private boolean autoplay,released,ownerVisible=true,resumeWhenVisible;
    private boolean chargingFast;
    private long nowDuringStep=-1,lastInvalidated=-1,lastStep=-1;
    private int invalidateDelay=30,renderMode=2;
    private int topColor=0xffffffff,bottomColor=0xffffffff;
    private final Runnable deferredInvalidate=()->{
        if(engine!=null&&!released){lastInvalidated=SystemClock.elapsedRealtime();invalidate();}
    };

    /** Call once during module asset preparation, before advertising animated cards. */
    public static void install(Context context)throws Exception {
        configureRuntime(HyperOsAssets.asset(context,"lottie-runtime.dex"));
        HyperOsCardFactory.setAnimationProvider(HyperOsLottieView::new);
    }

    /** Explicit owned asset file also supports offscreen validation without installation. */
    public static synchronized void configureRuntime(File dex)throws Exception {
        if(dex==null||!dex.isFile()||!dex.getName().endsWith(".dex"))throw new IOException("Missing original Lottie runtime DEX");
        String path=dex.getCanonicalPath();if(api!=null&&path.equals(runtimePath))return;
        // Android34+ requires dynamically loaded owned code files to be readonly.
        // This is the module's verified private asset copy, never a donor input.
        if(!dex.setReadOnly())throw new IOException("Could not protect Lottie runtime asset");
        File optimized=new File(dex.getParentFile(),"lottie-opt");if(!optimized.isDirectory()&&!optimized.mkdirs())throw new IOException("Could not create Lottie code cache");
        EngineApi ready=new EngineApi(new LibraryLoader(path,optimized));
        api=ready;runtimePath=path;
    }

    public HyperOsLottieView(Context context){this(context,null);}
    public HyperOsLottieView(Context context,AttributeSet attributes){
        super(context,attributes);
        if(attributes!=null){
            int cadence=attributes.getAttributeIntValue(APP_ATTRIBUTES,"frameRate",-1);
            invalidateDelay=cadence==2?6:14;
            renderMode=attributes.getAttributeIntValue(APP_ATTRIBUTES,"lottie_renderMode",2);
        }
        // ImageView's default is FIT_CENTER; its constructor also preserves any
        // explicit original android:scaleType attribute.
    }

    @Override public void setAnimation(HyperOsResources resources,String source,boolean autoplay,boolean loop,int number){
        release();released=false;chargingFast=false;
        try{
            EngineApi current=api;if(current==null)throw new IllegalStateException("Original Lottie runtime is not configured");
            String raw=rawName(source);if(raw==null)throw new IllegalArgumentException("Missing animation source");
            Object composition=current.composition(resources,raw);
            Drawable drawable=(Drawable)current.drawable.newInstance();
            current.setComposition.invoke(drawable,composition);
            current.renderMode.set(drawable,current.renderModes[Math.max(0,Math.min(2,renderMode))]);current.selectMode.invoke(drawable);
            current.imageFolder.set(drawable,"images/");
            engine=drawable;clock=(ValueAnimator)current.animator.get(drawable);callback=(Choreographer.FrameCallback)clock;
            // Card/V3 original branch: positive number is repeatCount itself,
            // otherwise infinite. loop belongs to video and is ignored here.
            clock.setRepeatCount(number>0?number:-1);clock.setRepeatMode(ValueAnimator.RESTART);
            current.setFrame.invoke(drawable,(int)current.startFrame.getFloat(composition));
            this.autoplay=autoplay;resumeWhenVisible=autoplay&&!ownerVisible;
            current.running.setBoolean(clock,autoplay&&ownerVisible);current.lastFrameNanos.setLong(clock,0L);
            setImageDrawable(drawable);
            // Original image/font managers require a View callback's resource
            // Context. This unattached relay owns no activity or window state.
            resourceRelay=new ImageView(resources){
                @Override public void invalidateDrawable(Drawable who){HyperOsLottieView.this.invalidateDrawable(who);}
                @Override public void scheduleDrawable(Drawable who,Runnable task,long when){HyperOsLottieView.this.scheduleDrawable(who,task,when);}
                @Override public void unscheduleDrawable(Drawable who,Runnable task){HyperOsLottieView.this.unscheduleDrawable(who,task);}
            };
            drawable.setCallback(resourceRelay);
            if("music_wave".equals(raw)||"music_pause".equals(raw))setGradient(topColor,bottomColor);
            lastStep=-1;lastInvalidated=-1;invalidate();
        }catch(Exception failure){release();throw new IllegalStateException("Could not render original HyperOS animation "+source,failure);}
    }

    /** Compact source branch counts total plays, unlike Card/V3's repeat count. */
    public void setCompactNumber(int number){if(clock!=null)clock.setRepeatCount(number>0?number-1:-1);}
    /** Original ChargerView starts NORMAL at frame zero and plays the same asset in both directions. */
    public void setChargerType(boolean fast){
        if(engine==null||clock==null||chargingFast==fast)return;
        chargingFast=fast;
        try{
            if(clock.isRunning()){clock.cancel();api.setProgress.invoke(engine,1f);}
            api.setSpeed.invoke(engine,fast?1f:-1f);
            clock.setRepeatCount(0);autoplay=true;api.play.invoke(engine);
            Choreographer.getInstance().removeFrameCallback(callback);
            if(!ownerVisible){api.unschedule.invoke(clock,true);resumeWhenVisible=true;}
            lastStep=-1;lastInvalidated=-1;invalidate();
        }catch(Exception failure){throw new IllegalStateException("Original charger morph failed",failure);}
    }

    /** Pause without losing the original frame, repeat count or completion state. */
    @Override public void setOwnerVisible(boolean visible){
        if(ownerVisible==visible)return;ownerVisible=visible;
        if(released||clock==null)return;
        try{
            if(!visible){
                resumeWhenVisible=autoplay&&clock.isRunning();
                api.unschedule.invoke(clock,true);removeCallbacks(deferredInvalidate);
            }else{
                if(resumeWhenVisible&&autoplay){
                    api.lastFrameNanos.setLong(clock,0L);api.running.setBoolean(clock,true);
                    lastStep=-1;lastInvalidated=-1;invalidate();
                }
                resumeWhenVisible=false;
            }
        }catch(Exception failure){throw new IllegalStateException("Original Lottie visibility clock failed",failure);}
    }

    /** Only music's original Gradient Fill1 is replaced when the artwork palette arrives. */
    public void setGradient(int top,int bottom){
        topColor=top;bottomColor=bottom;if(engine==null)return;
        try{
            EngineApi current=api;
            if(gradientCallback==null){
                gradientCallback=current.gradientConstructor.newInstance((Object)new Integer[]{top,bottom});
                current.addCallback.invoke(engine,current.gradientKey,current.gradientProperty,gradientCallback);
            }else current.gradientColors.invoke(gradientCallback,(Object)new Integer[]{top,bottom});
            engine.invalidateSelf();
        }catch(Exception failure){throw new IllegalStateException("Could not update original music gradient",failure);}
    }

    /** ABSOLUTE SystemClock.elapsedRealtime milliseconds; original float clock/repeats remain. */
    @Override public void step(long nowElapsed){
        if(released||!ownerVisible||!autoplay||clock==null||!clock.isRunning())return;
        try{
            if(lastStep>=0&&nowElapsed<lastStep)api.lastFrameNanos.setLong(clock,0L);
            lastStep=nowElapsed;nowDuringStep=nowElapsed;
            callback.doFrame(Math.max(0,nowElapsed)*1_000_000L+1L);
        }catch(Exception failure){throw new IllegalStateException("Original Lottie clock failed",failure);}
        finally{
            nowDuringStep=-1;
            // doFrame posts itself first. The island's frame owner is the only
            // scheduler, so remove that post while retaining original state.
            Choreographer.getInstance().removeFrameCallback(callback);
        }
    }
    @Override public boolean active(){return !released&&ownerVisible&&autoplay&&clock!=null&&clock.isRunning();}

    @Override public void release(){
        released=true;resumeWhenVisible=false;removeCallbacks(deferredInvalidate);
        if(callback!=null)Choreographer.getInstance().removeFrameCallback(callback);
        if(clock!=null&&api!=null)try{api.unschedule.invoke(clock,true);}catch(Exception failure){throw new IllegalStateException("Could not stop original Lottie clock",failure);}
        if(engine!=null)engine.setCallback(null);
        setImageDrawable(null);engine=null;clock=null;callback=null;resourceRelay=null;gradientCallback=null;lastStep=-1;
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();setOwnerVisible(isShown()&&getWindowVisibility()==View.VISIBLE);}
    @Override protected void onDetachedFromWindow(){setOwnerVisible(false);super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(isAttachedToWindow())setOwnerVisible(isShown()&&getWindowVisibility()==View.VISIBLE);}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(isAttachedToWindow())setOwnerVisible(isShown()&&visibility==View.VISIBLE);}

    @Override public void invalidateDrawable(Drawable who){
        if(who!=engine||clock==null||!clock.isRunning()){super.invalidateDrawable(who);return;}
        long now=nowDuringStep>=0?nowDuringStep:SystemClock.elapsedRealtime();long age=lastInvalidated<0?Long.MAX_VALUE:now-lastInvalidated;
        if(age>=invalidateDelay){lastInvalidated=now;super.invalidateDrawable(who);}
        else{removeCallbacks(deferredInvalidate);postOnAnimationDelayed(deferredInvalidate,invalidateDelay-age);}
    }

    /** Source frame exposure is for finite offscreen comparisons, not product UI. */
    public float sourceFrame(){if(clock==null)return 0;try{return api.frame.getFloat(clock);}catch(Exception failure){throw new IllegalStateException(failure);}}
    public void seekSourceFrame(int frame){if(engine!=null)try{api.setFrame.invoke(engine,frame);invalidate();}catch(Exception failure){throw new IllegalStateException(failure);}}

    private static String rawName(String value){
        if(value==null||value.length()==0)return null;
        switch(value){
            case "musicWave":return "music_wave";case "musicPause":return "music_pause";
            case "voiceWaveBig":return "voice_wave_big";case "voiceWaveSmall":return "voice_wave_small";
            case "alarmBig":return "alarm_big";case "alarmSmall":return "alarm_small";
            case "stopwatch_big":return "mark_timing_big";case "aon_scene":return "aon";
            case "disturb_mode_on":return "lottie_disturb_mode_on";case "disturb_mode_off":return "lottie_disturb_mode_off";
            case "silent_mode_on":return "lottie_silent_mode_on";case "silent_mode_off":return "lottie_silent_mode_off";
            case "quick_scan_code":return "lottie_quick_scan_code";default:return value;
        }
    }

    private static final class LibraryLoader extends DexClassLoader {
        LibraryLoader(String dex,File optimized){super(dex,optimized.getPath(),null,ClassLoader.getSystemClassLoader().getParent());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            if(name.startsWith("miui.systemui.")||name.startsWith("com.android.systemui.")||name.startsWith("systemui.plugin.")||name.startsWith("com.miui.systemui.")
                ||name.startsWith("com.alpha269.hyperisland.")&&!name.startsWith("com.alpha269.hyperisland.runtime."))
                throw new ClassNotFoundException("Application code is outside original Lottie boundary: "+name);
            return super.loadClass(name,resolve);
        }
    }

    private static final class EngineApi {
        final Method parse,setComposition,setFrame,selectMode,addCallback,gradientColors,unschedule,play,setSpeed,setProgress;
        final Constructor<?> drawable,gradientConstructor;
        final Field animator,running,lastFrameNanos,frame,startFrame,renderMode,imageFolder;
        final Object[] renderModes;final Object gradientKey,gradientProperty;
        final Map<String,Object> compositions=new LinkedHashMap<String,Object>(16,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Object> entry){return size()>32;}};
        EngineApi(ClassLoader loader)throws Exception {
            Class<?> factory=loader.loadClass("d.p"),drawableClass=loader.loadClass("d.A"),composition=loader.loadClass("d.k"),timer=loader.loadClass("p.c");
            parse=factory.getDeclaredMethod("c",InputStream.class,String.class);drawable=drawableClass.getConstructor();
            setComposition=drawableClass.getDeclaredMethod("H",composition);setFrame=drawableClass.getDeclaredMethod("I",int.class);selectMode=drawableClass.getDeclaredMethod("j");
            play=drawableClass.getDeclaredMethod("x");setSpeed=drawableClass.getDeclaredMethod("V",float.class);setProgress=drawableClass.getDeclaredMethod("S",float.class);
            animator=accessible(drawableClass,"animator");renderMode=accessible(drawableClass,"r");imageFolder=accessible(drawableClass,"h");
            running=accessible(timer,"k");lastFrameNanos=accessible(timer,"e");frame=accessible(timer,"f");startFrame=accessible(composition,"k");unschedule=timer.getDeclaredMethod("g",boolean.class);
            Class<?> modes=loader.loadClass("d.K");renderModes=new Object[]{modes.getField("a").get(null),modes.getField("b").get(null),modes.getField("c").get(null)};
            Class<?> keyType=loader.loadClass("i.e");gradientKey=keyType.getConstructor(String[].class).newInstance((Object)new String[]{"**","Gradient Fill 1"});gradientProperty=loader.loadClass("d.E").getField("G").get(null);
            Class<?> constant=loader.loadClass("com.alpha269.hyperisland.runtime.GradientCallback");gradientConstructor=constant.getConstructor(Object.class);gradientColors=constant.getDeclaredMethod("setColors",Object.class);
            addCallback=drawableClass.getDeclaredMethod("e",keyType,Object.class,loader.loadClass("q.c"));
        }
        synchronized Object composition(HyperOsResources source,String raw)throws Exception {
            String key=System.identityHashCode(source.getResources())+":"+source.getResources().getDisplayMetrics().densityDpi+":"+raw;
            Object value=compositions.get(key);if(value!=null)return value;
            InputStream json;
            try{json=source.getResources().openRawResource(source.id("raw",raw));}
            catch(IllegalArgumentException noRawResource){
                if(!raw.matches("[A-Za-z0-9_-]+"))throw noRawResource;
                json=source.getAssets().open(raw+".json");
            }
            Object result=parse.invoke(null,json,null);
            value=accessible(result.getClass(),"a").get(result);
            if(value==null)throw new IOException("Original Lottie JSON parse failed: "+raw,(Throwable)accessible(result.getClass(),"b").get(result));
            compositions.put(key,value);return value;
        }
        static Field accessible(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    }
}
