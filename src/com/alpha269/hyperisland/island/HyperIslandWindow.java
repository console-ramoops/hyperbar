package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Owns one Android window, a native compositor BlurLayer, and a source-built HyperIsland view. */
public final class HyperIslandWindow implements HyperIslandController.WindowAdapter {
    private WindowManager manager;
    private WindowManager.LayoutParams parameters;
    private FrameLayout root;
    private HyperOsBlurLayer blurLayer;
    private HyperIslandView island;
    private View statusBar;
    private boolean attached;
    private boolean blurEnabled=true,glassEnabled=false;
    private Object insetListener;
    private final Region touchRegion=new Region();
    private final Region scratchRegion=new Region();
    private View.OnLayoutChangeListener geometryListener;
    private int width,displayHeight;

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
            root=new FrameLayout(windowContext);
            root.setClipChildren(false);
            root.setClipToPadding(false);
            blurLayer=new HyperOsBlurLayer(windowContext);
            blurLayer.setBlurEnabled(blurEnabled);
            island=new HyperIslandView(windowContext,host);
            island.setBlurLayer(blurLayer);
            island.setVisualOptions(blurEnabled,glassEnabled);
            root.addView(blurLayer,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
            root.addView(island,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
            parameters=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,1,2017,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                |WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
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
            installInsets();manager.addView(root,parameters);attached=true;
            root.setOnApplyWindowInsetsListener((view,insets)->{configureGeometry();return insets;});
        }catch(Throwable failure){
            detach();
            android.util.Log.e("HyperIslandWindow","Window creation failed",failure);
        }
    }

    private void configureGeometry() {
        if(island==null||manager==null)return;
        Rect bounds=manager.getCurrentWindowMetrics().getBounds();
        width=bounds.width();displayHeight=bounds.height();
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
        measureStatusBarClearance();
    }

    private void measureStatusBarClearance() {
        if(island==null||statusBar==null||width<=0)return;
        float clock=-1f,battery=-1f;
        try {
            int[] barLoc=new int[2];
            statusBar.getLocationOnScreen(barLoc);
            View leftView=findStatusView(statusBar,"clock","status_bar_left_side");
            if(leftView!=null&&leftView.getVisibility()==View.VISIBLE&&leftView.getWidth()>0){
                int[] loc=new int[2];leftView.getLocationOnScreen(loc);
                int end=loc[0]+leftView.getWidth();
                if(end>0&&end<width/2)clock=end;
            }
            View rightView=findStatusView(statusBar,"system_icons","battery");
            if(rightView!=null&&rightView.getVisibility()==View.VISIBLE&&rightView.getWidth()>0){
                int[] loc=new int[2];rightView.getLocationOnScreen(loc);
                int fromRight=width-loc[0];
                if(fromRight>0&&fromRight<width/2)battery=fromRight;
            }
        } catch(Throwable ignored) {}
        island.setStatusBarClearance(clock,battery);
    }

    private static View findStatusView(View root,String... names) {
        if(root==null||root.getResources()==null)return null;
        String pkg=root.getContext().getPackageName();
        for(String name:names){
            int id=root.getResources().getIdentifier(name,"id","com.android.systemui");
            if(id==0)id=root.getResources().getIdentifier(name,"id",pkg);
            if(id!=0){View found=root.findViewById(id);if(found!=null)return found;}
        }
        return null;
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
            .invoke(root.getViewTreeObserver(),insetListener);
    }

    @Override public void detach() {
        HyperOsIncomingCalls.reportHidden();
        if(statusBar!=null&&geometryListener!=null)statusBar.removeOnLayoutChangeListener(geometryListener);
        if(blurLayer!=null)blurLayer.release();
        if(manager!=null&&root!=null&&(attached||root.isAttachedToWindow()))try{manager.removeViewImmediate(root);}
            catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Window removal failed",failure);}
        attached=false;root=null;blurLayer=null;island=null;parameters=null;manager=null;statusBar=null;insetListener=null;geometryListener=null;
        touchRegion.setEmpty();scratchRegion.setEmpty();
    }
    @Override public void setItems(List<IslandItem> items,int hint,List<IslandItem> minimized) {
        if(island!=null)island.setItems(items,hint,minimized);
    }
    @Override public void setSuppressed(boolean suppressed){if(island!=null)island.setSuppressed(suppressed);}
    @Override public void setShadeExpansion(float expansion){if(island!=null)island.setShadeFraction(expansion);}
    @Override public void setHeight(int pixels) {
        if(island==null||root==null||parameters==null)return;
        int target=pixels;
        if((island.isExpanded()||island.isLaunchActive())&&displayHeight>0)target=Math.max(target,displayHeight);
        int height=Math.max(1,target);if(parameters.height==height)return;
        parameters.height=height;
        if(attached)try{manager.updateViewLayout(root,parameters);}
            catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Window resize failed",failure);}
    }
    @Override public void getRestingPillBounds(RectF out){if(island!=null)island.getRestingPillBounds(out);}
    @Override public Rect windowBounds(){return manager!=null?manager.getCurrentWindowMetrics().getBounds():new Rect();}
    @Override public View view(){return island;}
    @Override public boolean isExpanded(){return island!=null&&island.isExpanded();}
    @Override public boolean isShown(){return attached&&island!=null&&!island.isFullyHidden();}
    @Override public boolean isHealthy(){return attached&&island!=null&&island.failure()==null;}
    @Override public void collapse(){if(island!=null)island.collapse();}
    @Override public void setVisualOptions(boolean blur,boolean glass){
        blurEnabled=blur;glassEnabled=glass;
        if(blurLayer!=null)blurLayer.setBlurEnabled(blur);
        if(island!=null)island.setVisualOptions(blur,glass);
    }
    @Override public void absorb(){if(island!=null)island.absorb();}
    @Override public void setLaunchProgress(float alpha,float scale){if(root!=null){root.setAlpha(alpha);root.setScaleX(scale);root.setScaleY(scale);}}
    @Override public void onFrame(){
        if(island==null||root==null)return;
        boolean watch=island.isExpanded();int flag=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if(parameters!=null&&((parameters.flags&flag)!=0)!=watch){
            if(watch)parameters.flags|=flag;else parameters.flags&=~flag;
            if(attached)try{manager.updateViewLayout(root,parameters);}catch(RuntimeException failure){android.util.Log.w("HyperIslandWindow","Touch policy update failed",failure);}
        }
        island.getTouchRegion(scratchRegion);
        if(!scratchRegion.equals(touchRegion)){touchRegion.set(scratchRegion);root.invalidate();}
    }
    @Override public void onConfigurationChanged(){configureGeometry();}
    @Override public void onInsetsChanged(){configureGeometry();}
    @Override public boolean dispatchShadeTouch(MotionEvent event){return false;}
    @Override public void shadeDragStart(float x,float y,long time){}
}
