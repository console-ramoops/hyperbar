package com.alpha269.hyperisland.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.LinearInterpolator;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Task-leash remote transition; no donor library or vendor framework loading. */
public final class HyperOsActivityLaunch {
    private static final String TAG="HyperOsActivityLaunch",DESCRIPTOR="android.window.IRemoteTransition";
    private static final Map<View,Runner> LAUNCHES=Collections.synchronizedMap(new WeakHashMap<View,Runner>());
    private static volatile SourceCallbacks callbacks;
    private static volatile Method mSetMatrix,mSetCornerRadius,mSetFrameTimelineVsync,mRemoveSurface;
    private static volatile boolean txMethodsResolved,rtMethodsResolved,rtAvailable;
    private static volatile Class<?> rtParamsClass;
    private static volatile java.lang.reflect.Constructor<?> rtApplierCtor,rtBuilderCtor;
    private static volatile Method rtGetViewRoot,rtWithMatrix,rtWithWindowCrop,rtWithAlpha,rtWithCornerRadius,rtWithVisibility,rtBuild,rtScheduleApply;
    private HyperOsActivityLaunch(){}

    private static void ensureTxMethods() {
        if(!txMethodsResolved) {
            try {
                mSetMatrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,Matrix.class,float[].class);
                mSetMatrix.setAccessible(true);
            } catch(Throwable ignored) {}
            try {
                mSetCornerRadius=SurfaceControl.Transaction.class.getDeclaredMethod("setCornerRadius",SurfaceControl.class,float.class);
                mSetCornerRadius.setAccessible(true);
            } catch(Throwable ignored) {}
            try {
                mSetFrameTimelineVsync=SurfaceControl.Transaction.class.getDeclaredMethod("setFrameTimelineVsync",long.class);
                mSetFrameTimelineVsync.setAccessible(true);
            } catch(Throwable ignored) {}
            try {
                mRemoveSurface=SurfaceControl.Transaction.class.getDeclaredMethod("remove",SurfaceControl.class);
                mRemoveSurface.setAccessible(true);
            } catch(Throwable ignored) {}
            txMethodsResolved=true;
        }
        if(!rtMethodsResolved) {
            try {
                Class<?> applierClass=Class.forName("android.view.SyncRtSurfaceTransactionApplier");
                Class<?> paramsClass=Class.forName("android.view.SyncRtSurfaceTransactionApplier$SurfaceParams");
                Class<?> builderClass=Class.forName("android.view.SyncRtSurfaceTransactionApplier$SurfaceParams$Builder");
                rtGetViewRoot=View.class.getDeclaredMethod("getViewRootImpl");rtGetViewRoot.setAccessible(true);
                rtApplierCtor=applierClass.getConstructor(View.class);
                rtBuilderCtor=builderClass.getConstructor(SurfaceControl.class);
                rtWithMatrix=builderClass.getMethod("withMatrix",Matrix.class);
                rtWithWindowCrop=builderClass.getMethod("withWindowCrop",Rect.class);
                rtWithAlpha=builderClass.getMethod("withAlpha",float.class);
                rtWithCornerRadius=builderClass.getMethod("withCornerRadius",float.class);
                rtWithVisibility=builderClass.getMethod("withVisibility",boolean.class);
                rtBuild=builderClass.getMethod("build");
                rtScheduleApply=applierClass.getMethod("scheduleApply",Array.newInstance(paramsClass,0).getClass());
                rtParamsClass=paramsClass;
                rtAvailable=true;
            } catch(Throwable ignored) { rtAvailable=false; }
            rtMethodsResolved=true;
        }
    }

    /** Source layer owns the island/card crossfade; task leashes are handled here. */
    public interface SourceCallbacks {
        void onPrepare(View source,RectF localBounds,boolean card);
        void onStart(View source,HyperOsActivityMotion motion);
        void onFrame(View source,HyperOsActivityMotion.State state);
        void onFinish(View source,boolean cancelled);
    }
    public static void setSourceCallbacks(SourceCallbacks source){callbacks=source;}
    public static boolean deferCollapse(Object view){return view instanceof View && isLaunching((View)view);}
    public static boolean isLaunching(View view){Runner r=LAUNCHES.get(view);return r!=null && !r.finished.get();}
    public static HyperOsActivityMotion.State currentState(View view){Runner r=LAUNCHES.get(view);return r==null?null:r.state;}
    public static HyperOsActivityMotion currentMotion(View view){Runner r=LAUNCHES.get(view);return r==null?null:r.motion;}
    public static RectF sourceBounds(View view){Runner r=LAUNCHES.get(view);return r==null?null:new RectF(r.source);}
    public static boolean isCard(View view){Runner r=LAUNCHES.get(view);return r!=null && r.card;}

    /** Return null on unsupported setup so the caller retains its old options. */
    public static Bundle launchOptions(Object controller,RectF localBounds) {
        try {
            View view=(View)field(controller,"view");Context context=(Context)field(controller,"ctx");
            if(view==null || localBounds==null || localBounds.isEmpty())return null;
            boolean card=Boolean.TRUE.equals(invoke(view,"isExpanded",new Class<?>[0]));
            return options(context,view,localBounds,card,false);
        }catch(Throwable error){Log.e(TAG,"Remote launch setup failed; retaining existing launch",error);return null;}
    }

    public static Bundle options(Context context,View view,RectF localBounds,boolean card,boolean fastCard)throws Exception {
        if(view==null || localBounds==null || localBounds.isEmpty())throw new IllegalArgumentException("Launch source bounds are empty");
        RectF screen=new RectF(localBounds);int[] location=new int[2];view.getLocationOnScreen(location);screen.offset(location[0],location[1]);
        Runner previous=LAUNCHES.get(view);if(previous!=null)previous.finish(true);
        Runner runner=new Runner(context,view,screen,card,fastCard);
        Class<?> remoteInterface=Class.forName("android.window.IRemoteTransition");
        Object proxy=Proxy.newProxyInstance(HyperOsActivityLaunch.class.getClassLoader(),new Class<?>[]{remoteInterface},(object,method,args)->{
            if("asBinder".equals(method.getName()))return runner;
            if("toString".equals(method.getName()))return "HyperIsland activity remote transition";
            if("hashCode".equals(method.getName()))return System.identityHashCode(object);
            if("equals".equals(method.getName()))return object==args[0];
            runner.local(method.getName(),args);return null;
        });
        runner.attach((IInterface)proxy);
        Class<?> remoteClass=Class.forName("android.window.RemoteTransition");
        Object remote=remoteClass.getConstructor(remoteInterface,String.class).newInstance(proxy,"HyperIslandLaunch");
        ActivityOptions options=(ActivityOptions)ActivityOptions.class.getMethod("makeRemoteTransition",remoteClass).invoke(null,remote);
        options.setPendingIntentBackgroundActivityStartMode(1);
        try {ActivityOptions.class.getMethod("setSplashScreenStyle",int.class).invoke(options,0);}catch(NoSuchMethodException absent){}
        if(view.getDisplay()!=null)options.setLaunchDisplayId(view.getDisplay().getDisplayId());
        LAUNCHES.put(view,runner);
        try {
            sourcePrepare(view,new RectF(localBounds),card);
            runner.main.postDelayed(runner.timeout,1000);
            return options.toBundle();
        }catch(Throwable failure){runner.finish(true);if(failure instanceof Exception)throw (Exception)failure;throw new IllegalStateException("Source preparation failed",failure);}
    }

    private static final class Runner extends Binder {
        final Context context;final WeakReference<View> view;final RectF source;final boolean card,fastCard;
        final Handler main=new Handler(Looper.getMainLooper());final AtomicBoolean finished=new AtomicBoolean();
        final Runnable timeout=()->{Log.w(TAG,"No remote transition start within1000ms");finish(true);};
        final Runnable maxTimeout=()->finish(true);
        final List<Target> targets=new ArrayList<>();
        final SurfaceControl.Transaction frameTx=new SurfaceControl.Transaction();
        final Matrix tmpMatrix=new Matrix(),tmpInverse=new Matrix();
        final RectF tmpCrop=new RectF();
        final Rect tmpWindowCrop=new Rect();
        final float[] tmpMatrixValues=new float[9];
        Object info,completion;SurfaceControl.Transaction initial;Target opening;ValueAnimator animator;
        Object frameApplier;boolean schedulerFailed;
        volatile HyperOsActivityMotion motion;volatile HyperOsActivityMotion.State state;
        int codeStart,codeMerge,codeTakeover,codeConsumed;
        Runner(Context context,View view,RectF source,boolean card,boolean fastCard)throws Exception {
            ensureTxMethods();
            this.context=context;this.view=new WeakReference<>(view);this.source=source;this.card=card;this.fastCard=fastCard;
            Class<?> stub=Class.forName("android.window.IRemoteTransition$Stub");
            codeStart=integerField(stub,"TRANSACTION_startAnimation");codeMerge=integerField(stub,"TRANSACTION_mergeAnimation");
            codeTakeover=integerField(stub,"TRANSACTION_takeOverAnimation");codeConsumed=integerField(stub,"TRANSACTION_onTransitionConsumed");
        }
        void attach(IInterface proxy){attachInterface(proxy,DESCRIPTOR);}
        void local(String name,Object[] arguments) {
            if("startAnimation".equals(name)){main.post(()->start((IBinder)arguments[0],arguments[1],(SurfaceControl.Transaction)arguments[2],arguments[3]));return;}
            if("mergeAnimation".equals(name) || "takeOverAnimation".equals(name)){
                Object incoming=arguments[1];SurfaceControl.Transaction transaction=(SurfaceControl.Transaction)arguments[2];
                Object done=arguments["mergeAnimation".equals(name)?4:3];
                main.post(()->{finish(true);finishUnanimated(incoming,transaction,done);});return;
            }
            if("onTransitionConsumed".equals(name)){main.post(()->finish((Boolean)arguments[1]));return;}
            throw new UnsupportedOperationException(name);
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(DESCRIPTOR);return true;}
            try {
                data.enforceInterface(DESCRIPTOR);
                if(code==codeConsumed){data.readStrongBinder();boolean aborted=data.readBoolean();main.post(()->finish(aborted));return true;}
                if(code!=codeStart && code!=codeMerge && code!=codeTakeover)return super.onTransact(code,data,reply,flags);
                IBinder token=data.readStrongBinder();
                Object incoming=typed(data,Class.forName("android.window.TransitionInfo"));
                SurfaceControl.Transaction transaction=(SurfaceControl.Transaction)typed(data,SurfaceControl.Transaction.class);
                if(code==codeMerge)data.readStrongBinder();
                IBinder callback=data.readStrongBinder();Object done=finishedInterface(callback);
                if(code==codeStart)main.post(()->start(token,incoming,transaction,done));
                else main.post(()->{finish(true);finishUnanimated(incoming,transaction,done);});
                return true;
            }catch(Throwable error){Log.e(TAG,"Remote transition parcel failed",error);main.post(()->finish(true));return false;}
        }
        void start(IBinder unused,Object info,SurfaceControl.Transaction initial,Object completion) {
            if(finished.get()){finishUnanimated(info,initial,completion);return;}
            main.removeCallbacks(timeout);this.info=info;this.initial=initial;this.completion=completion;
            try {
                if(info==null || initial==null)throw new IllegalStateException("Remote transition has no targets");
                List<?> changes=(List<?>)invoke(info,"getChanges",new Class<?>[0]);
                Target selected=null;
                int rootCount=((Number)invoke(info,"getRootCount",new Class<?>[0])).intValue();
                Object root=invoke(info,"getRoot",new Class<?>[]{int.class},0);
                SurfaceControl rootLeash=(SurfaceControl)invoke(root,"getLeash",new Class<?>[0]);
                Point offset=(Point)invoke(root,"getOffset",new Class<?>[0]);
                if(rootCount!=1)throw new IllegalStateException("Multi-display remote transitions require root matching");
                int order=changes.size();
                for(Object change:changes){
                    Target target=new Target(change,info,rootLeash,offset,order--);targets.add(target);
                    if((target.mode==1 || target.mode==3 || target.mode==6) && target.task!=null && target.independent && !target.pinned){
                        if(selected==null || target.bounds.width()*target.bounds.height()>selected.bounds.width()*selected.bounds.height())selected=target;
                    }
                }
                if(selected==null)throw new IllegalStateException("No independent opening app task");
                opening=selected;
                View attached=view.get();if(attached==null)throw new IllegalStateException("Launch source was detached");
                float density=attached.getResources().getDisplayMetrics().density;
                RectF start=card?new RectF(source):HyperOsActivityMotion.capsuleStart(source,density);
                float sourceCorner=card?Math.round(26*density):Math.min(start.height()/2f,Math.round(15*density));
                motion=new HyperOsActivityMotion(start,new RectF(selected.bounds),density,sourceCorner,windowCorner(context),card,fastCard);
                selected.createWrapper(initial);
                state=motion.sampleFraction(0);apply(initial,state);initial.apply();initial.close();this.initial=null;
                SourceCallbacks sourceCallbacks=callbacks;if(sourceCallbacks!=null)sourceCallbacks.onStart(attached,motion);
                else sourceStart(attached);
                sourceFrame(attached,state);
                animator=ValueAnimator.ofFloat(0,1);animator.setDuration(motion.duration);animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(value->{
                    if(finished.get())return;
                    try {
                        state=motion.sampleFraction(value.getAnimatedFraction());
                        if(!scheduleWithSource(state)){
                            apply(frameTx,state);
                            frameTx.apply();
                        }
                        View sourceView=view.get();
                        if(sourceView!=null)sourceFrame(sourceView,state);
                    }catch(Throwable failure){Log.e(TAG,"App morph frame failed",failure);finish(true);}
                });
                animator.addListener(new AnimatorListenerAdapter(){
                    boolean cancelled;
                    @Override public void onAnimationCancel(Animator animation){cancelled=true;}
                    @Override public void onAnimationEnd(Animator animation){finish(cancelled);}
                });
                animator.start();main.postDelayed(maxTimeout,motion.duration+1500);
            }catch(Throwable error){Log.e(TAG,"App morph start failed; finishing visible tasks",error);finish(true);}
        }
        void apply(SurfaceControl.Transaction frame,HyperOsActivityMotion.State state)throws Exception {
            Target target=opening;if(target==null || target.wrapper==null || !target.wrapper.isValid())throw new IllegalStateException("Opening task leash was released");
            HyperOsActivityMotion.SurfaceState nativeState=HyperOsActivityMotion.surface(state,target.bounds);
            // Wrapper local coordinates begin at the task's top-left, while the
            // recovered native matrix is expressed in screen-space coordinates.
            tmpMatrix.set(nativeState.matrix);tmpMatrix.preTranslate(target.bounds.left,target.bounds.top);tmpMatrix.postTranslate(-target.rootOffset.x,-target.rootOffset.y);
            tmpCrop.set(state.bounds);tmpCrop.offset(-target.rootOffset.x,-target.rootOffset.y);
            tmpMatrix.invert(tmpInverse);tmpInverse.mapRect(tmpCrop);
            tmpWindowCrop.set(Math.round(tmpCrop.left),Math.round(tmpCrop.top),Math.round(tmpCrop.right),Math.round(tmpCrop.bottom));
            setMatrixFast(frame,target.wrapper,tmpMatrix,tmpMatrixValues);frame.setCrop(target.wrapper,tmpWindowCrop);frame.setAlpha(target.wrapper,state.alpha);frame.setVisibility(target.wrapper,true);
            setCornerRadiusFast(frame,target.wrapper,nativeState.corner);
            for(Target behind:targets)if(behind!=target && behind.mode==2 && behind.task!=null && behind.independent && behind.leash.isValid())frame.setAlpha(behind.leash,state.behindAlpha);
        }
        boolean scheduleWithSource(HyperOsActivityMotion.State state) {
            if(!rtAvailable || schedulerFailed)return false;
            View source=view.get();
            Target target=opening;
            if(source==null || !source.isAttachedToWindow() || target==null || target.wrapper==null || !target.wrapper.isValid())return false;
            try {
                if(rtGetViewRoot.invoke(source)==null)return false;
                if(frameApplier==null)frameApplier=rtApplierCtor.newInstance(source);
                HyperOsActivityMotion.SurfaceState nativeState=HyperOsActivityMotion.surface(state,target.bounds);
                tmpMatrix.set(nativeState.matrix);tmpMatrix.preTranslate(target.bounds.left,target.bounds.top);tmpMatrix.postTranslate(-target.rootOffset.x,-target.rootOffset.y);
                tmpCrop.set(state.bounds);tmpCrop.offset(-target.rootOffset.x,-target.rootOffset.y);
                tmpMatrix.invert(tmpInverse);tmpInverse.mapRect(tmpCrop);
                Rect windowCrop=new Rect(Math.round(tmpCrop.left),Math.round(tmpCrop.top),Math.round(tmpCrop.right),Math.round(tmpCrop.bottom));
                Matrix matrixCopy=new Matrix(tmpMatrix);
                List<Object> params=new ArrayList<>(2);
                Object builder=rtBuilderCtor.newInstance(copySurface(target.wrapper));
                rtWithMatrix.invoke(builder,matrixCopy);
                rtWithWindowCrop.invoke(builder,windowCrop);
                rtWithAlpha.invoke(builder,state.alpha);
                rtWithCornerRadius.invoke(builder,nativeState.corner);
                rtWithVisibility.invoke(builder,Boolean.TRUE);
                params.add(rtBuild.invoke(builder));
                for(Target behind:targets)if(behind!=target && behind.mode==2 && behind.task!=null && behind.independent && behind.leash.isValid()){
                    Object back=rtBuilderCtor.newInstance(copySurface(behind.leash));
                    rtWithAlpha.invoke(back,state.behindAlpha);
                    params.add(rtBuild.invoke(back));
                }
                Object array=Array.newInstance(rtParamsClass,params.size());
                for(int i=0;i<params.size();i++)Array.set(array,i,params.get(i));
                rtScheduleApply.invoke(frameApplier,array);
                return true;
            } catch(Throwable error) {
                schedulerFailed=true;
                Log.w(TAG,"Source RT scheduling unavailable; applying task frames directly",error);
                return false;
            }
        }
        void finish(boolean cancelled) {
            if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->finish(cancelled));return;}
            if(!finished.compareAndSet(false,true))return;
            main.removeCallbacks(timeout);main.removeCallbacks(maxTimeout);
            if(animator!=null && animator.isRunning())animator.cancel();
            SurfaceControl.Transaction cleanup=new SurfaceControl.Transaction();
            try {
                if(initial!=null){initial.apply();initial.close();initial=null;}
                for(Target target:targets)try{target.restore(cleanup);}catch(Throwable error){Log.e(TAG,"Task restoration failed",error);}
            }catch(Throwable error){Log.e(TAG,"Task leash cleanup failed",error);}
            finally {
                try{cleanup.apply();}catch(Throwable error){Log.e(TAG,"Cleanup transaction apply failed",error);}
                try{finishCallback(completion,null);}catch(Throwable error){Log.e(TAG,"Transition finish callback failed",error);}
                cleanup.close();
                try{frameTx.close();}catch(Throwable ignored){}
                if(info!=null)try{invoke(info,"releaseAllSurfaces",new Class<?>[0]);}catch(Throwable ignored){}
                for(Target target:targets)if(target.wrapper!=null)target.wrapper.release();
                View source=view.get();if(source!=null && LAUNCHES.get(source)==this){LAUNCHES.remove(source);try{sourceFinish(source,cancelled);}catch(Throwable error){Log.e(TAG,"Source cleanup failed",error);}}
            }
        }
    }

    private static final class Target {
        final SurfaceControl leash,rootLeash,parent;final Rect bounds;final Point rootOffset,restorePosition;
        final int mode,order;final Object task;final boolean independent,pinned;
        SurfaceControl wrapper;
        Target(Object change,Object info,SurfaceControl root,Point offset,int order)throws Exception {
            this.order=order;rootLeash=root;rootOffset=new Point(offset);
            leash=(SurfaceControl)invoke(change,"getLeash",new Class<?>[0]);bounds=new Rect((Rect)invoke(change,"getEndAbsBounds",new Class<?>[0]));
            mode=((Number)invoke(change,"getMode",new Class<?>[0])).intValue();task=invoke(change,"getTaskInfo",new Class<?>[0]);
            Class<?> infoClass=Class.forName("android.window.TransitionInfo");
            independent=Boolean.TRUE.equals(infoClass.getMethod("isIndependent",Class.forName("android.window.TransitionInfo$Change"),infoClass).invoke(null,change,info));
            boolean pin=false;
            if(task!=null)try{Object config=field(task,"configuration");Object window=field(config,"windowConfiguration");int windowMode=((Number)invoke(window,"getWindowingMode",new Class<?>[0])).intValue();pin=windowMode==2 || windowMode==5 || windowMode==100;}catch(Throwable ignored){}
            pinned=pin;
            Object parentToken=invoke(change,"getParent",new Class<?>[0]),parentChange=null;
            if(parentToken!=null)parentChange=invoke(info,"getChange",new Class<?>[]{Class.forName("android.window.WindowContainerToken")},parentToken);
            if(parentChange==null){parent=root;restorePosition=new Point(bounds.left-offset.x,bounds.top-offset.y);}
            else {parent=(SurfaceControl)invoke(parentChange,"getLeash",new Class<?>[0]);restorePosition=new Point((Point)invoke(change,"getEndRelOffset",new Class<?>[0]));}
        }
        void createWrapper(SurfaceControl.Transaction initial)throws Exception {
            SurfaceControl.Builder builder=new SurfaceControl.Builder().setName("ColorOS island task morph").setParent(rootLeash).setHidden(false);
            invoke(builder,"setContainerLayer",new Class<?>[0]);wrapper=builder.build();
            initial.setLayer(wrapper,order+100);initial.reparent(leash,wrapper);initial.setPosition(leash,0,0);initial.setAlpha(leash,1);initial.setVisibility(leash,true);
            setMatrix(initial,leash,new Matrix());initial.setCrop(leash,null);
        }
        void restore(SurfaceControl.Transaction cleanup)throws Exception {
            if(leash!=null && leash.isValid()){
                if(wrapper!=null){cleanup.reparent(leash,parent);cleanup.setPosition(leash,restorePosition.x,restorePosition.y);setMatrix(cleanup,leash,new Matrix());cleanup.setCrop(leash,null);invoke(cleanup,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},leash,0f);}
                cleanup.setAlpha(leash,1);cleanup.setVisibility(leash,true);
            }
            if(wrapper!=null && wrapper.isValid())invoke(cleanup,"remove",new Class<?>[]{SurfaceControl.class},wrapper);
        }
    }

    private static void finishUnanimated(Object info,SurfaceControl.Transaction transaction,Object callback) {
        try {
            if(transaction!=null){
                if(info!=null)for(Object change:(List<?>)invoke(info,"getChanges",new Class<?>[0])){
                    int mode=((Number)invoke(change,"getMode",new Class<?>[0])).intValue();
                    SurfaceControl leash=(SurfaceControl)invoke(change,"getLeash",new Class<?>[0]);
                    if((mode==1 || mode==3 || mode==6) && leash!=null && leash.isValid()){transaction.setAlpha(leash,1);transaction.setVisibility(leash,true);}
                }
                transaction.apply();
            }
        }catch(Throwable error){Log.e(TAG,"Unanimated transition cleanup failed",error);}
        finally{
            try{finishCallback(callback,null);}catch(Throwable error){Log.e(TAG,"Unanimated finish callback failed",error);}
            if(transaction!=null)transaction.close();
            if(info!=null)try{invoke(info,"releaseAllSurfaces",new Class<?>[0]);}catch(Throwable ignored){}
        }
    }
    private static Object typed(Parcel parcel,Class<?> type)throws Exception{return parcel.readTypedObject((Parcelable.Creator<?>)type.getField("CREATOR").get(null));}
    private static Object finishedInterface(IBinder binder)throws Exception{return Class.forName("android.window.IRemoteTransitionFinishedCallback$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);}
    private static void finishCallback(Object callback,SurfaceControl.Transaction transaction)throws Exception {
        if(callback==null)return;
        Class<?> contract=Class.forName("android.window.IRemoteTransitionFinishedCallback"),wct=Class.forName("android.window.WindowContainerTransaction");
        contract.getMethod("onTransitionFinished",wct,SurfaceControl.Transaction.class).invoke(callback,null,transaction);
    }
    private static void setMatrixFast(SurfaceControl.Transaction t,SurfaceControl leash,Matrix matrix,float[] values)throws Exception {
        if(mSetMatrix!=null)mSetMatrix.invoke(t,leash,matrix,values);
        else invoke(t,"setMatrix",new Class<?>[]{SurfaceControl.class,Matrix.class,float[].class},leash,matrix,values);
    }
    private static void setCornerRadiusFast(SurfaceControl.Transaction t,SurfaceControl leash,float radius)throws Exception {
        if(mSetCornerRadius!=null)mSetCornerRadius.invoke(t,leash,radius);
        else invoke(t,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},leash,radius);
    }
    private static void setMatrix(SurfaceControl.Transaction t,SurfaceControl leash,Matrix matrix)throws Exception {setMatrixFast(t,leash,matrix,new float[9]);}
    private static SurfaceControl copySurface(SurfaceControl surface){Parcel parcel=Parcel.obtain();try{parcel.writeTypedObject(surface,0);parcel.setDataPosition(0);return parcel.readTypedObject(SurfaceControl.CREATOR);}finally{parcel.recycle();}}
    private static void sourcePrepare(View view,RectF local,boolean card)throws Exception {
        SourceCallbacks handler=callbacks;if(handler!=null){handler.onPrepare(view,local,card);return;}
        sourceMethod("prepare",new Class<?>[]{Object.class,RectF.class,boolean.class},view,local,card);
    }
    private static void sourceStart(View view)throws Exception {
        try{sourceMethod("start",new Class<?>[]{Object.class},view);}catch(NoSuchMethodException optional){}
    }
    private static void sourceFrame(View view,HyperOsActivityMotion.State state)throws Exception {
        SourceCallbacks handler=callbacks;if(handler!=null){handler.onFrame(view,state);return;}
        sourceMethod("update",new Class<?>[]{Object.class,RectF.class,float.class,boolean.class},view,new RectF(state.bounds),state.sourceAlpha,state.sourceTracksApp);
    }
    private static void sourceFinish(View view,boolean cancelled)throws Exception {
        SourceCallbacks handler=callbacks;if(handler!=null){handler.onFinish(view,cancelled);return;}
        sourceMethod("finish",new Class<?>[]{Object.class,boolean.class},view,cancelled);
    }
    private static void sourceMethod(String name,Class<?>[] types,Object...arguments)throws Exception {
        Class.forName("com.alpha269.hyperisland.island.HyperOsActivitySource").getMethod(name,types).invoke(null,arguments);
    }
    private static float windowCorner(Context context){try{return ((Number)Class.forName("com.android.internal.policy.ScreenDecorationsUtils").getMethod("getWindowCornerRadius",Context.class).invoke(null,context)).floatValue();}catch(Throwable unavailable){return 0;}}
    private static int integerField(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.getInt(null);}
    private static Object field(Object instance,String name)throws Exception{for(Class<?> type=instance.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}
    private static Object invoke(Object instance,String name,Class<?>[] types,Object...arguments)throws Exception {
        Method method=null;for(Class<?> owner=instance.getClass();owner!=null;owner=owner.getSuperclass())try{method=owner.getDeclaredMethod(name,types);method.setAccessible(true);break;}catch(NoSuchMethodException absent){}
        if(method==null)throw new NoSuchMethodException(instance.getClass().getName()+"."+name);return method.invoke(instance,arguments);
    }
}
