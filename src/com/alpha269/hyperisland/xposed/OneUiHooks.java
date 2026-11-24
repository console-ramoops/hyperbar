package com.alpha269.hyperisland.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import com.alpha269.hyperisland.island.HyperIslandController;
import com.alpha269.hyperisland.island.IslandItem;
import com.alpha269.hyperisland.island.HyperOsMediaOutput;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Focused read-only One UI observers. Only visibly replaced chips receive presentation changes. */
public final class OneUiHooks {
    private static final String UI="com.android.systemui";
    private static final String ONGOING=UI+".statusbar.phone.ongoingactivity.";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<View,ChipState> hidden=new WeakHashMap<>();
    private static WeakReference<View> statusBar=new WeakReference<>(null);
    private static WeakReference<Object> collection=new WeakReference<>(null),recording=new WeakReference<>(null),recordChip=new WeakReference<>(null);
    private static Class<?> ongoingHelper;
    private static HyperIslandController.WindowAdapter adapter;
    private static boolean installed,pending,recordingActive;
    private static long recordingBase,nativeTimerStart;
    private static int barState,windowState;
    private static boolean dozing;
    private static float shade;
    private static final OneUiSignalAdapter signals=new OneUiSignalAdapter(new OneUiSignalAdapter.Observer(){
        public void state(int value){onMain(()->{barState=value;HyperIslandController c=HyperIslandController.get();if(c!=null)c.setBarState(value);});}
        public void dozing(boolean value){onMain(()->{dozing=value;HyperIslandController c=HyperIslandController.get();if(c!=null)c.setDozing(value);});}
        public void shade(float value){onMain(()->{shade=value;HyperIslandController c=HyperIslandController.get();if(c!=null)c.setShadeExpansion(value);});}
        public void window(int value){onMain(()->{windowState=value;HyperIslandController c=HyperIslandController.get();if(c!=null)c.setStatusBarWindowState(value);});}
    });
    private OneUiHooks(){}

    public static synchronized void install(ClassLoader loader){install(loader,null);}
    public static synchronized void install(ClassLoader loader,HyperIslandController.WindowAdapter window){
        if(window!=null)adapter=window;if(installed)return;installed=true;
        XposedBridge.hookAllMethods(Instrumentation.class,"callApplicationOnCreate",after("SystemUI initialization",param->{
            if(param.args.length==0||!(param.args[0] instanceof Application))return;
            Application app=(Application)param.args[0];if(!UI.equals(app.getPackageName()))return;
            HyperIslandController controller=HyperIslandController.start(app);
            if(adapter!=null)controller.setWindowAdapter(adapter);
            controller.setPresentationChanged(OneUiHooks::applyChipSuppression);
            controller.setBarState(barState);controller.setDozing(dozing);controller.setStatusBarWindowState(windowState);controller.setShadeExpansion(shade);
            View bar=statusBar.get();if(bar!=null&&bar.isAttachedToWindow())controller.onStatusBarView(bar);
            scheduleSnapshot();main.removeCallbacks(periodic);main.post(periodic);
        }));
        observeStatusBar(loader);observeNotifications(loader);observeOngoing(loader);observeRecording(loader);observeSignals(loader);observeMediaOutput(loader);
    }
    private static void observeMediaOutput(ClassLoader loader){
        HyperOsMediaOutput.register(loader);
        constructors(loader,HyperOsMediaOutput.MANAGER,param->HyperOsMediaOutput.captureManager(param.thisObject));
        constructors(loader,HyperOsMediaOutput.DEVICE_ENTRY,param->HyperOsMediaOutput.captureDeviceEntry(param.thisObject));
        hook(loader,HyperOsMediaOutput.DEVICE_ENTRY,"updateCurrent",param->HyperOsMediaOutput.captureDeviceEntry(param.thisObject));
        hook(loader,HyperOsMediaOutput.DEVICE_ENTRY+"$updateCurrent$1","run",param->HyperOsMediaOutput.captureDeviceEntry(read(param.thisObject,"this$1")));
        hook(loader,HyperOsMediaOutput.DEVICE_ENTRY,"stop",param->HyperOsMediaOutput.releaseDeviceEntry(param.thisObject));
    }
    private static void observeStatusBar(ClassLoader loader){
        hook(loader,UI+".statusbar.phone.PhoneStatusBarViewController","onViewAttached",param->{attach(read(param.thisObject,"mView"));});
        hook(loader,UI+".statusbar.phone.PhoneStatusBarViewController","onViewDetached",param->{detach(read(param.thisObject,"mView"));});
        hook(loader,UI+".statusbar.phone.PhoneStatusBarView","onAttachedToWindow",param->attach(param.thisObject));
        hook(loader,UI+".statusbar.phone.PhoneStatusBarView","onDetachedFromWindow",param->detach(param.thisObject));
        hook(loader,UI+".statusbar.phone.PhoneStatusBarView","onConfigurationChanged",param->{HyperIslandController c=HyperIslandController.get();if(c!=null)c.onWindowConfigurationChanged();});
        hook(loader,UI+".statusbar.phone.PhoneStatusBarView","onApplyWindowInsets",param->{HyperIslandController c=HyperIslandController.get();if(c!=null)c.onWindowInsetsChanged();});
    }
    private static void attach(Object object){
        if(!(object instanceof View))return;View bar=(View)object;
        View previous=statusBar.get();if(previous!=bar)restoreAll();statusBar=new WeakReference<>(bar);
        HyperIslandController c=HyperIslandController.get();if(c!=null)c.onStatusBarView(bar);scheduleSnapshot();
    }
    private static void detach(Object object){
        if(!(object instanceof View))return;View bar=(View)object;
        HyperIslandController c=HyperIslandController.get();if(c!=null)c.onStatusBarViewDetached(bar);
        if(statusBar.get()==bar){restoreAll();statusBar=new WeakReference<>(null);}
    }
    private static void observeNotifications(ClassLoader loader){
        String name=UI+".statusbar.notification.collection.NotifCollection";
        constructors(loader,name,param->{collection=new WeakReference<>(param.thisObject);scheduleSnapshot();});
        for(String method:new String[]{"dispatchEventsAndRebuildList","postNotification","postNotificationFromInternalUpdater","tryRemoveNotification","dismissNotifications","onRankingUpdate"})
            hook(loader,name,method,param->{collection=new WeakReference<>(param.thisObject);scheduleSnapshot();});
    }
    private static void observeOngoing(ClassLoader loader){
        ongoingHelper=find(loader,ONGOING+"OngoingActivityDataHelper");
        for(String method:new String[]{"removeOngoingActivityByKey","updateOngoingList","notifyUpdateItemNowbarObservers","notifyRemoveItemNowbarObservers","notifyRankingStateChanged",
            "notifyOaDataListChangedObservers","notifyUpdateObservers","recreateOngoingActivity","reinflateOngoingActivity"})
            hook(loader,ONGOING+"OngoingActivityDataHelper",method,param->scheduleSnapshot());
        for(String method:new String[]{"onAsyncInflationFinished","OaDataListChanged","add$1","update","update$1$1","remove","updateMediaChipData","removeOngoingActivityData","killGhost"})
            hook(loader,ONGOING+"OngoingActivityController",method,param->scheduleSnapshot());
        // Let One UI calculate its own visibility, then suppress only replacement-owned views.
        hook(loader,ONGOING+"OngoingActivityController","updateParentViewVisibility",param->applyChipSuppression());
        String fragment=UI+".statusbar.phone.fragment.CollapsedStatusBarFragment";
        for(String method:new String[]{"updateStatusBarVisibilities","updateStatusBarVisibilitiesInner","updateNotificationIconAreaAndOngoingActivityChip","animateShow","animateHiddenState"})
            hook(loader,fragment,method,param->applyChipSuppression());
        String chip=ONGOING+"ChipAnimationController";
        hook(loader,chip,"showView",param->{if(param.args.length>0)nativeChipVisibility(param.args[0],View.VISIBLE);applyChipSuppression();});
        hook(loader,chip,"hideView",param->{if(param.args.length>1&&param.args[1] instanceof Number)nativeChipVisibility(param.args[0],((Number)param.args[1]).intValue());applyChipSuppression();});
        for(String method:new String[]{"animateChipShow","animateChipHide","startChipTransitionAnimation$default","handleOnGoingActivityChipAnimation","handleOnGoingCallChipAnimation"})
            hook(loader,chip,method,param->applyChipSuppression());
        hook(loader,UI+".statusbar.phone.ongoingcall.OngoingCallController","updateChip",param->scheduleSnapshot());
        hook(loader,UI+".statusbar.phone.ongoingcall.SamsungOngoingCallController","updateChip",param->scheduleSnapshot());
    }
    private static void observeRecording(ClassLoader loader){
        constructors(loader,UI+".screenrecord.RecordingController",param->{recording=new WeakReference<>(param.thisObject);scheduleSnapshot();});
        hook(loader,UI+".screenrecord.RecordingController","updateState",param->{
            recording=new WeakReference<>(param.thisObject);boolean active=Boolean.TRUE.equals(read(param.thisObject,"mIsRecording"));
            if(active&&!recordingActive)recordingBase=System.currentTimeMillis();if(!active){recordingBase=0;nativeTimerStart=0;}recordingActive=active;scheduleSnapshot();
        });
        constructors(loader,UI+".statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel",param->{recordChip=new WeakReference<>(param.thisObject);scheduleSnapshot();});
    }
    private static void observeSignals(ClassLoader loader){
        String state=UI+".statusbar.StatusBarStateControllerImpl";
        constructors(loader,state,param->signals.bindStatus(param.thisObject));
        hook(loader,state,"start",param->signals.bindStatus(param.thisObject));
        hook(loader,state,"setState",param->signals.snapshotStatus(param.thisObject));
        String window=UI+".statusbar.window.StatusBarWindowStateController";
        constructors(loader,window,param->signals.snapshotWindow(param.thisObject));
        hook(loader,window+"$commandQueueCallback$1","setWindowState",param->signals.snapshotWindow(read(param.thisObject,"this$0")));
        constructors(loader,UI+".shade.ShadeExpansionStateManager",param->signals.bindShade(param.thisObject));
    }
    private static final Runnable periodic=new Runnable(){@Override public void run(){
        if(HyperIslandController.get()==null)return;
        snapshotRecording();main.postDelayed(this,1000);
    }};
    private static void scheduleSnapshot(){
        if(Looper.myLooper()!=main.getLooper()){main.post(OneUiHooks::scheduleSnapshot);return;}
        if(pending)return;pending=true;main.post(()->{pending=false;snapshot();});
    }
    private static void snapshot(){
        HyperIslandController controller=HyperIslandController.get();if(controller==null)return;
        Object owner=collection.get(),all=invoke(owner,"getAllNotifs");
        if(!(all instanceof Collection)){Object map=read(owner,"mNotificationSet");if(map instanceof Map)all=((Map<?,?>)map).values();}
        if(all instanceof Collection){List<StatusBarNotification> values=new ArrayList<>();
            for(Object entry:(Collection<?>)all){Object raw=read(entry,"mSbn");if(raw==null)raw=invoke(entry,"getSbn");if(raw instanceof StatusBarNotification)values.add((StatusBarNotification)raw);}
            controller.onNotifications(values);
        }
        if(ongoingHelper!=null){List<Object> values=new ArrayList<>();
            append(values,read(ongoingHelper,"mOngoingActivityLists"));append(values,read(ongoingHelper,"hiddenOngoingActivityDataList"));
            controller.onOngoingActivities(values);
        }
        snapshotRecording();applyChipSuppression();
    }
    private static void append(List<Object> out,Object value){if(value instanceof Collection)out.addAll((Collection<?>)value);}
    private static void snapshotRecording(){
        HyperIslandController controller=HyperIslandController.get();Object owner=recording.get();if(controller==null||owner==null)return;
        boolean active=Boolean.TRUE.equals(read(owner,"mIsRecording"));long base=recordingBase;
        if(active){
            Object flow=read(recordChip.get(),"chipWithConsistentTimer"),model=invoke(flow,"getValue"),content=read(model,"content");
            Object start=read(content,"startTimeMs");if(start instanceof Number){long elapsed=((Number)start).longValue();if(elapsed>0&&elapsed<=SystemClock.elapsedRealtime()&&elapsed!=nativeTimerStart){base=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-elapsed);nativeTimerStart=elapsed;}}
            if(base<=0)base=System.currentTimeMillis();recordingBase=base;
        }else{recordingBase=0;nativeTimerStart=0;}
        recordingActive=active;Object capability=read(owner,"mStop");Runnable stop=capability instanceof Runnable?(Runnable)capability:null;
        controller.onScreenRecording(active,base,stop);
    }
    private static void applyChipSuppression(){
        if(Looper.myLooper()!=main.getLooper()){main.post(OneUiHooks::applyChipSuppression);return;}
        HyperIslandController controller=HyperIslandController.get();View root=statusBar.get();
        if(controller==null||root==null||!root.isAttachedToWindow()||!controller.isShown()){restoreAll();return;}
        suppress(root,"ongoing_activity_capsule",controller.replacesOngoing());
        suppress(root,"ongoing_call_chip",controller.replacesKind(IslandItem.Kind.CALL));
        suppress(root,"screen_record_chip",controller.replacesScreenRecording());
        suppress(root,"screen_recording_chip",controller.replacesScreenRecording());
    }
    private static void suppress(View root,String name,boolean replace){
        int id=root.getResources().getIdentifier(name,"id",UI);if(id==0)return;View view=root.findViewById(id);if(view==null)return;
        ChipState state=hidden.get(view);
        if(!replace){if(state!=null){state.restore(view);hidden.remove(view);}return;}
        if(state==null){if(view.getVisibility()!=View.VISIBLE)return;state=new ChipState(view);hidden.put(view,state);}
        else if(view.getVisibility()!=View.INVISIBLE)state.visibility=view.getVisibility();
        if(view.getVisibility()==View.VISIBLE)view.setVisibility(View.INVISIBLE);
    }
    private static void nativeChipVisibility(Object object,int visibility){
        if(!(object instanceof View))return;onMain(()->{ChipState state=hidden.get((View)object);if(state!=null)state.visibility=visibility;});
    }
    private static void onMain(Runnable action){if(Looper.myLooper()==main.getLooper())action.run();else main.post(action);}
    private static void restoreAll(){for(Map.Entry<View,ChipState> entry:new ArrayList<>(hidden.entrySet()))entry.getValue().restore(entry.getKey());hidden.clear();}
    private static final class ChipState {
        int visibility;
        ChipState(View view){visibility=view.getVisibility();}
        void restore(View view){if(view!=null&&view.getVisibility()==View.INVISIBLE)view.setVisibility(visibility);}
    }
    private interface Body {void run(XC_MethodHook.MethodHookParam param)throws Throwable;}
    private static XC_MethodHook after(String scope,Body body){return new XC_MethodHook(){
        @Override protected void afterHookedMethod(MethodHookParam param){try{body.run(param);}catch(Throwable failure){XposedBridge.log("HyperIsland "+scope+": "+failure.getClass().getSimpleName());}}
    };}
    private static Class<?> find(ClassLoader loader,String name){try{return Class.forName(name,false,loader);}catch(ClassNotFoundException ignored){return null;}}
    private static void hook(ClassLoader loader,String name,String method,Body body){Class<?> type=find(loader,name);if(type==null)return;
        try{XposedBridge.hookAllMethods(type,method,after(name+"."+method,body));}catch(Throwable failure){XposedBridge.log("HyperIsland hook unavailable: "+name+"."+method);}}
    private static void constructors(ClassLoader loader,String name,Body body){Class<?> type=find(loader,name);if(type==null)return;
        try{XposedBridge.hookAllConstructors(type,after(name+" constructor",body));}catch(Throwable failure){XposedBridge.log("HyperIsland constructor observer unavailable: "+name);}}
    private static Object read(Object owner,String name){
        if(owner==null)return null;Class<?> type=owner instanceof Class?(Class<?>)owner:owner.getClass();
        for(;type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner instanceof Class?null:owner);}catch(Exception ignored){}
        return null;
    }
    private static Object invoke(Object owner,String name){if(owner==null)return null;try{Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);}catch(Exception ignored){return null;}}
}
