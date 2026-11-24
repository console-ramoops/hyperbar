package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Owns one Android window and a source-built HyperIsland view. */
public final class HyperIslandWindow implements HyperIslandController.WindowAdapter {
    private WindowManager manager;
    private WindowManager.LayoutParams parameters;
    private HyperIslandView island;
    private View statusBar;
    private boolean attached;
    private Object insetListener;
    private final Region touchRegion=new Region();
    private final Region scratchRegion=new Region();
    private View.OnLayoutChangeListener geometryListener;
    private int width;

    @Override public void attach(Context context,IslandHost host,View bar) {
        if(attached && statusBar==bar)return;
        detach();statusBar=bar;
        try {
            Display display=context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            Context windowContext;
            try{windowContext=context.createWindowContext(display,2017,null);}
            catch(RuntimeException unavailable){windowContext=context.createDisplayContext(display);}
            manager=windowContext.getSystemService(WindowManager.class);
            width=manager.getCurrentWindowMetrics().getBounds().width();
            island=new HyperIslandView(windowContext,host);
            parameters=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,1,2017,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                |WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
            parameters.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
            parameters.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            parameters.setFitInsetsTypes(0);parameters.setTitle("HyperIsland");
            try {
                Field flags=WindowManager.LayoutParams.class.getField("privateFlags");
                flags.setInt(parameters,flags.getInt(parameters)|0x20000050);
            } catch(ReflectiveOperationException ignored) {}
            configureGeometry();
            geometryListener=(view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->configureGeometry();
            bar.addOnLayoutChangeListener(geometryListener);
            installInsets();manager.addView(island,parameters);attached=true;
            island.setOnApplyWindowInsetsListener((view,insets)->{configureGeometry();return insets;});
        }catch(Throwable failure){
            detach();
            android.util.Log.e("HyperIslandWindow","Window creation failed",failure);
        }
    }

    private void configureGeometry() {
        if(island==null||manager==null)return;
        width=manager.getCurrentWindowMetrics().getBounds().width();
        int[] position=new int[2];if(statusBar!=null)statusBar.getLocationOnScreen(position);
        float center=statusBar==null?island.getResources().getDisplayMetrics().density*14
            :position[1]+statusBar.getHeight()/2f;
        float hole=0;
        if(statusBar!=null&&statusBar.getRootWindowInsets()!=null
            &&statusBar.getRootWindowInsets().getDisplayCutout()!=null) {
            Rect cutout=statusBar.getRootWindowInsets().getDisplayCutout().getBoundingRectTop();
            if(!cutout.isEmpty()){center=cutout.exactCenterY();hole=Math.min(cutout.width(),cutout.height())*.78f;}
        }
        island.setGeometry(width,width/2f,center,hole,false,Float.NaN,false,Float.NaN);
    }

    private void installInsets()throws Exception {
        Class<?> listener=Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
        insetListener=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
            if("onComputeInternalInsets".equals(method.getName())) {
                Object info=args[0];island.getTouchRegion(touchRegion);
                Method setter=info.getClass().getMethod("setTouchableInsets",int.class);setter.invoke(info,3);
                ((Region)info.getClass().getField("touchableRegion").get(info)).set(touchRegion);
                return null;
            }
            if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
            if("equals".equals(method.getName()))return proxy==args[0];
            return "HyperIsland touch region";
        });
        ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener",listener)
            .invoke(island.getViewTreeObserver(),insetListener);
    }

    @Override public void detach() {
        HyperOsIncomingCalls.reportHidden();
        if(statusBar!=null&&geometryListener!=null)statusBar.removeOnLayoutChangeListener(geometryListener);
        if(manager!=null&&island!=null&&(attached||island.isAttachedToWindow()))try{manager.removeViewImmediate(island);}
            catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Window removal failed",failure);}
        attached=false;island=null;parameters=null;manager=null;statusBar=null;insetListener=null;geometryListener=null;
        touchRegion.setEmpty();scratchRegion.setEmpty();
    }
    @Override public void setItems(List<IslandItem> items,int hint,List<IslandItem> minimized) {
        if(island!=null)island.setItems(items,hint,minimized);
    }
    @Override public void setSuppressed(boolean suppressed){if(island!=null)island.setSuppressed(suppressed);}
    @Override public void setShadeExpansion(float expansion){if(island!=null)island.setShadeFraction(expansion);}
    @Override public void setHeight(int pixels) {
        if(island==null||parameters==null)return;
        int height=Math.max(1,pixels);if(parameters.height==height)return;
        parameters.height=height;
        if(attached)try{manager.updateViewLayout(island,parameters);}
            catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Window resize failed",failure);}
    }
    @Override public View view(){return island;}
    @Override public boolean isExpanded(){return island!=null&&island.isExpanded();}
    @Override public boolean isShown(){return attached&&island!=null&&!island.isFullyHidden();}
    @Override public void collapse(){if(island!=null)island.collapse();}
    @Override public void setLaunchProgress(float alpha,float scale){if(island!=null){island.setAlpha(alpha);island.setScaleX(scale);island.setScaleY(scale);}}
    @Override public void onFrame(){
        if(island==null)return;
        boolean watch=island.isExpanded();int flag=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if(parameters!=null&&((parameters.flags&flag)!=0)!=watch){
            if(watch)parameters.flags|=flag;else parameters.flags&=~flag;
            if(attached)try{manager.updateViewLayout(island,parameters);}catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Touch policy update failed",failure);}
        }
        island.getTouchRegion(scratchRegion);
        if(!scratchRegion.equals(touchRegion)){touchRegion.set(scratchRegion);island.invalidate();}
    }
    @Override public void onConfigurationChanged(){configureGeometry();}
    @Override public void onInsetsChanged(){configureGeometry();}
    @Override public boolean dispatchShadeTouch(MotionEvent event){return false;}
    @Override public void shadeDragStart(float x,float y,long time){}
}
