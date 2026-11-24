package com.alpha269.hyperisland.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Focused read-only One UI observers. Only visibly replaced chips receive presentation changes. */
public final class OneUiHooks {
    private static final String UI="com.android.systemui";
    private static final String ONGOING=UI+".statusbar.phone.ongoingactivity.";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<View,ChipState> hidden=new WeakHashMap<>();
    private static WeakReference<View> statusBar=new WeakReference<>(null);
    private static WeakReference<View> oaParentView=new WeakReference<>(null);
    private static WeakReference<Object> collection=new WeakReference<>(null),recording=new WeakReference<>(null),recordChip=new WeakReference<>(null),callController=new WeakReference<>(null),oaController=new WeakReference<>(null),secPowerUi=new WeakReference<>(null);
    private static volatile String liveForegroundPackage;
    private static volatile boolean liveForegroundKnown;
    private static int idCapsule,idCapsuleContainer,idChipsContainer,idCastChip,idChip,idChipPrimary,idChipSecondary,idCallChip,idSamsungCallChip,idScreenRecordChip,idScreenRecordingChip;
    private static boolean idsResolved;
    private static Class<?> ongoingHelper;
    private static HyperIslandController.WindowAdapter adapter;
    private static boolean installed,pending,inSnapshot,recordingActive;
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
            Object oaCtrl=oaController.get();if(oaCtrl!=null)controller.setOngoingActivityController(oaCtrl);
            if(!liveForegroundKnown){String top=resolveTopTaskPackage();if(top!=null){liveForegroundPackage=top;liveForegroundKnown=true;}}
            controller.setBarState(barState);controller.setDozing(dozing);controller.setStatusBarWindowState(windowState);controller.setShadeExpansion(shade);
            View bar=statusBar.get();if(bar!=null&&bar.isAttachedToWindow())controller.onStatusBarView(bar);
            scheduleSnapshot();main.removeCallbacks(periodic);main.post(periodic);
        }));
        observeStatusBar(loader);observeNotifications(loader);observeOngoing(loader);observeRecording(loader);observeSignals(loader);observeMediaOutput(loader);observeCharging(loader);
        HyperOsShadeIntegration.install(loader);
    }
    private static void observeCharging(ClassLoader loader){
        constructors(loader,UI+".power.SecPowerUI",param->secPowerUi=new WeakReference<>(param.thisObject));
        hook(loader,UI+".statusbar.events.SystemStatusAnimationSchedulerImpl","onStatusEvent",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    if(param.args.length==0||param.args[0]==null)return;
                    Object event=param.args[0];
                    if(!"com.android.systemui.statusbar.events.BatteryEvent".equals(event.getClass().getName()))return;
                    HyperIslandController controller=HyperIslandController.get();
                    if(controller==null||!controller.isChargingAnimEnabled())return;
                    int[] parsed=readBatteryEvent(event,secPowerUi.get());
                    controller.onBatteryPlugged(parsed[0],parsed[1]);
                    if(param.method instanceof Method&&((Method)param.method).getReturnType()==boolean.class)param.setResult(Boolean.FALSE);
                    else param.setResult(null);
                }catch(Throwable failure){XposedBridge.log("HyperIsland BatteryEvent replace: "+failure.getClass().getSimpleName());}
            }
        });
    }
    /**
     * Maps Samsung SecPowerUI / ChargerAnimationUtils constants to normalized speed tiers:
     * 0 = normal (charger_type 0/1/2 without HV, normal wireless 6, slow 8),
     * 1 = fast (AFC 15W hv_charger / type 2, Fast Wireless type 7 / 520, SFC 25W sfc 3),
     * 2 = turbo (SFC 2.0 45W+ sfc 4 / 5).
     */
    public static int resolveSamsungChargingTier(int superFastCharger,int batteryChargingType,boolean hvCharger,boolean slowCharger){
        if(slowCharger||batteryChargingType==8)return 0;
        if(superFastCharger==4||superFastCharger==5||batteryChargingType==4||batteryChargingType==5)return 2;
        if(superFastCharger==3||superFastCharger==520||batteryChargingType==2||batteryChargingType==3
            ||batteryChargingType==7||batteryChargingType==520||hvCharger)return 1;
        return 0;
    }
    private static int[] readBatteryEvent(Object event,Object pui){
        int level=readIntField(event,-1,"batteryLevel","mBatteryLevel","level");
        int sfc=readIntField(event,-1,"chargingType","mChargingType","superFastCharger","fastChargingType");
        Object creator=read(event,"viewCreator");
        if(sfc<0&&creator!=null){
            sfc=readIntField(creator,-1,"f$1","chargingType","superFastCharger");
            if(sfc<0){
                for(Field f:creator.getClass().getDeclaredFields()){
                    if(f.getType()!=int.class||java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
                    try{f.setAccessible(true);sfc=f.getInt(creator);break;}catch(Throwable ignored){}
                }
            }
        }
        if(level<0){
            for(Field f:event.getClass().getDeclaredFields()){
                if(f.getType()!=int.class||java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
                try{f.setAccessible(true);int v=f.getInt(event);if(v>=0&&v<=100){level=v;break;}}catch(Throwable ignored){}
            }
        }
        if(level<0)level=readIntField(pui,-1,"mBatteryLevel");
        int puiSfc=readIntField(pui,0,"mSuperFastCharger");
        if(puiSfc>sfc)sfc=puiSfc;
        int bct=readIntField(pui,0,"mBatteryChargingType");
        boolean hvc=Boolean.TRUE.equals(read(pui,"mBatteryHighVoltageCharger"));
        boolean slow=Boolean.TRUE.equals(read(pui,"mBatterySlowCharger"));
        return new int[]{level,resolveSamsungChargingTier(Math.max(0,sfc),bct,hvc,slow)};
    }
    private static int readIntField(Object owner,int fallback,String... names){
        if(owner==null)return fallback;
        for(String name:names){
            Object raw=read(owner,name);
            if(raw instanceof Number)return ((Number)raw).intValue();
        }
        return fallback;
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
            "notifyOaDataListChangedObservers","notifyUpdateObservers","recreateOngoingActivity","reinflateOngoingActivity","addPipEnabledComponentName","removePipEnabledComponentName"})
            hook(loader,ONGOING+"OngoingActivityDataHelper",method,param->scheduleSnapshot());
        hook(loader,ONGOING+"OngoingActivityDataHelper","setBaseActivityComponentName",param->{
            if(param.args.length>0&&param.args[0] instanceof ComponentName){
                String pkg=((ComponentName)param.args[0]).getPackageName();
                if(pkg!=null&&!pkg.isEmpty()){liveForegroundPackage=pkg;liveForegroundKnown=true;}
            }
            scheduleSnapshot();
        });
        constructors(loader,ONGOING+"OngoingActivityController",param->{
            oaController=new WeakReference<>(param.thisObject);
            HyperIslandController c=HyperIslandController.get();if(c!=null)c.setOngoingActivityController(param.thisObject);
            scheduleSnapshot();
        });
        for(String method:new String[]{"onAsyncInflationFinished","OaDataListChanged","add$1","update","update$1$1","remove","updateMediaChipData","removeOngoingActivityData","killGhost"})
            hook(loader,ONGOING+"OngoingActivityController",method,param->{oaController=new WeakReference<>(param.thisObject);scheduleSnapshot();});
        // Samsung skips setBaseActivityComponentName when mOngoingActivityLists is empty ("OA data is empty").
        // Resolve the focused task directly so foreground-app hiding and 2nd-activity promotion always work.
        hook(loader,ONGOING+"OngoingActivityController$start$2","onTaskFocusChanged",param->{
            if(param.args.length<2||!Boolean.TRUE.equals(param.args[1]))return;
            int taskId=((Number)param.args[0]).intValue();
            Object ctrl=read(param.thisObject,"this$0");
            HyperIslandController c=HyperIslandController.get();
            if(ctrl!=null){
                oaController=new WeakReference<>(ctrl);
                if(c!=null)c.setOngoingActivityController(ctrl);
            }
            String pkg=resolveTaskPackage(ctrl,taskId);
            if(pkg!=null&&!pkg.isEmpty()){
                liveForegroundPackage=pkg;liveForegroundKnown=true;
                if(c!=null){c.onForegroundState(pkg,readPipPackages());applyChipSuppression();}
                scheduleSnapshot();
            }
        });
        for(String method:new String[]{"onActivityPinned","onActivityUnpinned"})
            hook(loader,ONGOING+"OngoingActivityController$start$2",method,param->scheduleSnapshot());
        hook(loader,ONGOING+"OngoingActivityController","sendChipInfoForAppExitAnimation",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    oaController=new WeakReference<>(param.thisObject);
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();
                    if(controller!=null){
                        controller.setOngoingActivityController(param.thisObject);
                        if(controller.isReplacementActive()&&(controller.isShown()||controller.hasExitTargets())){
                            controller.onExitTargetsRequested();param.setResult(null);
                        }
                    }
                }catch(Throwable failure){XposedBridge.log("HyperIsland OngoingActivityController.sendChipInfoForAppExitAnimation: "+failure.getClass().getSimpleName());}
            }
        });
        // Match ColorOS SystemUiHooks: when HyperBar owns any active or foreground-hidden ongoing
        // activity, suppress mParentView + chips containers + showingOAChip immediately (0-frame lag)
        // to prevent any Now Bar flash on app exit.
        hook(loader,ONGOING+"OngoingActivityController","updateParentViewVisibility",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    Object parent=read(param.thisObject,"mParentView");
                    if(parent instanceof View)oaParentView=new WeakReference<>((View)parent);
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();
                    if(controller!=null&&controller.replacesOngoing()&&hasNativeOngoingActivities()){
                        if(parent instanceof View){
                            View view=(View)parent;
                            recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);
                        }
                        Object flow=read(param.thisObject,"showingOAChip");
                        if(flow!=null)invoke2(flow,"updateState",null,Boolean.FALSE);
                        View root=statusBar.get();
                        if(root!=null&&root.isAttachedToWindow()){
                            applyOngoingSuppression(root,true);
                        }
                        param.setResult(null);
                        scheduleSnapshot();
                    }
                }catch(Throwable failure){XposedBridge.log("HyperIsland updateParentViewVisibility before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland updateParentViewVisibility: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,UI+".statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl","adjustVisibility",before("HomeStatusBarViewBinderImpl.adjustVisibility",param->{
            if(param.args.length==0||!(param.args[0] instanceof View))return;
            syncSnapshotIfNeeded();
            HyperIslandController controller=HyperIslandController.get();View root=statusBar.get();View view=(View)param.args[0];
            if(!isViewReplaced(controller,root,view))return;
            boolean showing=!(param.args.length>1&&param.args[1] instanceof Boolean&&!((Boolean)param.args[1]))
                &&!(param.args.length>1&&param.args[1] instanceof Number&&((Number)param.args[1]).intValue()!=View.VISIBLE);
            if(showing){recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);param.setResult(null);}
            else{int vis=param.args.length>1&&param.args[1] instanceof Number?((Number)param.args[1]).intValue():View.GONE;recordNativeDesiredVisibility(view,vis);}
        }));
        String fragment=UI+".statusbar.phone.fragment.CollapsedStatusBarFragment";
        hook(loader,fragment,"animateShow",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    if(param.args.length==0||!(param.args[0] instanceof View))return;
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();View view=(View)param.args[0];
                    if(isViewReplaced(controller,statusBar.get(),view)){recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);param.setResult(null);}
                }catch(Throwable failure){XposedBridge.log("HyperIsland CollapsedStatusBarFragment.animateShow before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland CollapsedStatusBarFragment.animateShow: "+failure.getClass().getSimpleName());}
            }
        });
        for(String method:new String[]{"updateStatusBarVisibilities","updateStatusBarVisibilitiesInner","updateNotificationIconAreaAndOngoingActivityChip","animateHiddenState"})
            hook(loader,fragment,method,param->applyChipSuppression());
        String chip=ONGOING+"ChipAnimationController";
        hook(loader,chip,"showView",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    if(param.args.length==0||!(param.args[0] instanceof View))return;
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();View view=(View)param.args[0];
                    if(isViewReplaced(controller,statusBar.get(),view)){recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);param.setResult(null);}
                }catch(Throwable failure){XposedBridge.log("HyperIsland ChipAnimationController.showView before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{if(param.args.length>0)nativeChipVisibility(param.args[0],View.VISIBLE);applyChipSuppression();}
                catch(Throwable failure){XposedBridge.log("HyperIsland ChipAnimationController.showView: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,chip,"hideView",param->{if(param.args.length>1&&param.args[1] instanceof Number)nativeChipVisibility(param.args[0],((Number)param.args[1]).intValue());applyChipSuppression();});
        hook(loader,chip,"animateChipShow",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    if(param.args.length==0||!(param.args[0] instanceof View))return;
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();View view=(View)param.args[0];
                    if(isViewReplaced(controller,statusBar.get(),view)){recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);param.setResult(null);}
                }catch(Throwable failure){XposedBridge.log("HyperIsland animateChipShow before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland animateChipShow: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,chip,"handleOnGoingActivityChipAnimation",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();
                    if(controller!=null&&controller.replacesOngoing()&&!(param.args.length>0&&Boolean.FALSE.equals(param.args[0]))){
                        param.setResult(null);applyChipSuppression();
                    }
                }catch(Throwable failure){XposedBridge.log("HyperIsland handleOnGoingActivityChipAnimation before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland handleOnGoingActivityChipAnimation: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,chip,"clockViewNudgeAnimation",before("ChipAnimationController.clockViewNudgeAnimation",param->{
            syncSnapshotIfNeeded();
            HyperIslandController controller=HyperIslandController.get();
            if(controller!=null&&controller.replacesOngoing())param.setResult(null);
        }));
        hook(loader,chip,"handleOnGoingCallChipAnimation",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    syncSnapshotIfNeeded();
                    HyperIslandController controller=HyperIslandController.get();
                    if(controller!=null&&controller.replacesKind(IslandItem.Kind.CALL)&&!(param.args.length>0&&Boolean.FALSE.equals(param.args[0]))){
                        param.setResult(null);applyChipSuppression();
                    }
                }catch(Throwable failure){XposedBridge.log("HyperIsland handleOnGoingCallChipAnimation before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland handleOnGoingCallChipAnimation: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,chip,"animateChipHide",param->applyChipSuppression());
        hook(loader,chip,"startChipTransitionAnimation$default",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam param){
                try{
                    if(param.args.length>2&&param.args[2] instanceof View&&Boolean.TRUE.equals(param.args[1])){
                        syncSnapshotIfNeeded();
                        HyperIslandController controller=HyperIslandController.get();View view=(View)param.args[2];
                        if(isViewReplaced(controller,statusBar.get(),view)){recordNativeDesiredVisibility(view,View.VISIBLE);hideReplacedView(view);param.setResult(null);}
                    }
                }catch(Throwable failure){XposedBridge.log("HyperIsland startChipTransitionAnimation$default before: "+failure.getClass().getSimpleName());}
            }
            @Override protected void afterHookedMethod(MethodHookParam param){
                try{applyChipSuppression();}catch(Throwable failure){XposedBridge.log("HyperIsland startChipTransitionAnimation$default: "+failure.getClass().getSimpleName());}
            }
        });
        hook(loader,ONGOING+"OngoingActivityController","isChipExpandCondition",before("OngoingActivityController.isChipExpandCondition",param->{
            syncSnapshotIfNeeded();
            HyperIslandController controller=HyperIslandController.get();
            if(controller!=null&&controller.replacesOngoing())param.setResult(Boolean.FALSE);
        }));
        for(String cls:new String[]{UI+".statusbar.phone.ongoingcall.OngoingCallController",UI+".statusbar.phone.ongoingcall.SamsungOngoingCallController"}){
            constructors(loader,cls,param->{callController=new WeakReference<>(param.thisObject);scheduleSnapshot();});
            for(String method:new String[]{"updateChip","sendStateChangeEvent","removeChipInfo"})
                hook(loader,cls,method,param->{callController=new WeakReference<>(param.thisObject);scheduleSnapshot();});
        }
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
        String shadeManager=UI+".shade.ShadeExpansionStateManager";
        constructors(loader,shadeManager,param->signals.bindShade(param.thisObject));
        hook(loader,shadeManager,"updateStateInternal",param->{
            if(param.args.length==1&&param.args[0] instanceof Number&&((Number)param.args[0]).intValue()==0)signals.onShadeClosed();
        });
    }
    private static final Runnable periodic=new Runnable(){@Override public void run(){
        if(HyperIslandController.get()==null)return;
        snapshotRecording();main.postDelayed(this,1000);
    }};
    private static void scheduleSnapshot(){
        if(Looper.myLooper()!=main.getLooper()){main.post(OneUiHooks::scheduleSnapshot);return;}
        if(pending)return;pending=true;main.post(()->{if(!pending)return;pending=false;snapshot();});
    }
    static void syncSnapshotIfNeeded(){
        if(Looper.myLooper()!=main.getLooper()||inSnapshot)return;
        HyperIslandController controller=HyperIslandController.get();
        if(controller==null)return;
        boolean nativeOngoing=false;
        if(!pending&&!controller.replacesOngoing()&&ongoingHelper!=null){
            Object shown=read(ongoingHelper,"mOngoingActivityLists");
            Object hiddenList=read(ongoingHelper,"hiddenOngoingActivityDataList");
            nativeOngoing=(shown instanceof Collection&&!((Collection<?>)shown).isEmpty())
                ||(hiddenList instanceof Collection&&!((Collection<?>)hiddenList).isEmpty());
        }
        if(pending||nativeOngoing){pending=false;snapshot();}
        else controller.flushRefresh();
    }
    private static void snapshot(){
        if(inSnapshot)return;
        HyperIslandController controller=HyperIslandController.get();if(controller==null)return;
        inSnapshot=true;
        try{
            Object owner=collection.get(),all=invoke(owner,"getAllNotifs");
            if(!(all instanceof Collection)){Object map=read(owner,"mNotificationSet");if(map instanceof Map)all=((Map<?,?>)map).values();}
            if(all instanceof Collection){List<StatusBarNotification> values=new ArrayList<>();
                for(Object entry:(Collection<?>)all){Object raw=read(entry,"mSbn");if(raw==null)raw=invoke(entry,"getSbn");if(raw instanceof StatusBarNotification)values.add((StatusBarNotification)raw);}
                controller.onNotifications(values);
            }
            if(ongoingHelper!=null){List<Object> values=new ArrayList<>();
                append(values,read(ongoingHelper,"mOngoingActivityLists"));append(values,read(ongoingHelper,"hiddenOngoingActivityDataList"));
                controller.onOngoingActivities(values);
                Object baseCn=read(ongoingHelper,"baseActivityComponentName");
                String helperFg=baseCn instanceof ComponentName?((ComponentName)baseCn).getPackageName():null;
                String fgPkg=liveForegroundKnown?liveForegroundPackage:helperFg;
                Set<String> pipPkgs=readPipPackages();
                controller.onForegroundState(fgPkg,pipPkgs);
            }
            Object callCtrl=callController.get();
            Object callInfo=read(callCtrl,"callNotificationInfo");
            String callKey=callInfo==null?null:read(callInfo,"key") instanceof String?(String)read(callInfo,"key"):null;
            String callPkg=callInfo==null?null:read(callInfo,"packageName") instanceof String?(String)read(callInfo,"packageName"):null;
            String callAppName=callInfo==null?null:read(callInfo,"appName") instanceof String?(String)read(callInfo,"appName"):null;
            Object rawCallIntent=callInfo==null?null:read(callInfo,"intent");
            android.app.PendingIntent callIntent=rawCallIntent instanceof android.app.PendingIntent?(android.app.PendingIntent)rawCallIntent:null;
            Object callStartRaw=read(callInfo,"callStartTime");
            long callStartMs=callStartRaw instanceof Number?((Number)callStartRaw).longValue():0L;
            boolean callActive=Boolean.TRUE.equals(read(callInfo,"isOngoing"));
            boolean callAppInFg=callActive&&Boolean.FALSE.equals(invoke(callCtrl,"hasOngoingCall"));
            Object nowbarCallData=null;
            if(ongoingHelper!=null&&callKey!=null){
                Object map=read(ongoingHelper,"onlyShownNowbarItemMap");
                if(map instanceof Map)nowbarCallData=((Map<?,?>)map).get(callKey);
            }
            controller.onOngoingCallInfo(callActive,callKey,callPkg,callStartMs,callAppInFg,callAppName,callIntent,nowbarCallData);
            snapshotRecording();
            controller.flushRefresh();
            applyChipSuppression();
        }finally{inSnapshot=false;}
    }
    private static Set<String> readPipPackages(){
        Set<String> pipPkgs=new LinkedHashSet<>();
        if(ongoingHelper==null)return pipPkgs;
        Object pipRaw=read(ongoingHelper,"pipEnabledComponentNameList");
        if(pipRaw instanceof Collection)for(Object entry:(Collection<?>)pipRaw){
            if(entry instanceof ComponentName)pipPkgs.add(((ComponentName)entry).getPackageName());
        }
        return pipPkgs;
    }
    private static String resolveTaskPackage(Object ctrl,int taskId){
        if(ctrl!=null){
            try{
                Method m=ctrl.getClass().getDeclaredMethod("access$getTaskInfo",ctrl.getClass(),int.class);
                m.setAccessible(true);
                Object task=m.invoke(null,ctrl,taskId);
                String pkg=packageFromTaskInfo(task);
                if(pkg!=null)return pkg;
            }catch(Throwable ignored){}
        }
        return resolveTopTaskPackage();
    }
    private static String resolveTopTaskPackage(){
        try{
            Class<?> atm=Class.forName("android.app.ActivityTaskManager");
            Object svc=atm.getMethod("getService").invoke(null);
            if(svc!=null){
                Object tasks=null;
                try{tasks=svc.getClass().getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(svc,1,false,false,0);}
                catch(NoSuchMethodException ignored){
                    try{tasks=svc.getClass().getMethod("getTasks",int.class).invoke(svc,1);}catch(Throwable ignored2){}
                }
                if(tasks instanceof List&&!((List<?>)tasks).isEmpty()){
                    return packageFromTaskInfo(((List<?>)tasks).get(0));
                }
            }
        }catch(Throwable ignored){}
        return null;
    }
    private static String packageFromTaskInfo(Object task){
        if(!(task instanceof android.app.ActivityManager.RunningTaskInfo))return null;
        android.app.ActivityManager.RunningTaskInfo info=(android.app.ActivityManager.RunningTaskInfo)task;
        if(info.topActivity!=null&&info.topActivity.getPackageName()!=null&&!info.topActivity.getPackageName().isEmpty())
            return info.topActivity.getPackageName();
        if(info.baseActivity!=null&&info.baseActivity.getPackageName()!=null&&!info.baseActivity.getPackageName().isEmpty())
            return info.baseActivity.getPackageName();
        return null;
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
        if(controller==null||root==null||!root.isAttachedToWindow()||!controller.isReplacementActive()){restoreAll();return;}
        applySuppression(root,controller.replacesOngoing(),controller.replacesKind(IslandItem.Kind.CALL),controller.replacesScreenRecording());
    }
    public static void applySuppressionForTest(View root,boolean replaceOngoing,boolean replaceCall,boolean replaceRecording){
        if(root==null){restoreAll();return;}
        resolveIds(root);
        // Unit-test path runs without live OngoingActivityDataHelper; allow restore so GONE->VISIBLE roundtrip tests work.
        boolean allowOngoing=ongoingHelper==null||hasNativeOngoingActivities();
        boolean allowCall=callController.get()==null||hasNativeOngoingCall();
        boolean allowRec=recording.get()==null||recordingActive;
        suppressById(root,idCapsule,replaceOngoing,allowOngoing);
        suppressById(root,idChip,replaceOngoing,allowOngoing);
        suppressById(root,idCapsuleContainer,replaceOngoing,allowOngoing);
        suppressById(root,idChipsContainer,replaceOngoing,allowOngoing);
        suppressById(root,idCastChip,replaceOngoing,allowOngoing);
        suppressById(root,idChipPrimary,replaceOngoing,allowOngoing);
        suppressById(root,idChipSecondary,replaceOngoing,allowOngoing);
        suppressById(root,idCallChip,replaceCall,allowCall);
        suppressById(root,idSamsungCallChip,replaceCall,allowCall);
        suppressById(root,idScreenRecordChip,replaceRecording,allowRec);
        suppressById(root,idScreenRecordingChip,replaceRecording,allowRec);
    }
    public static void restoreAllForTest(){restoreAll();}
    private static boolean hasNativeOngoingActivities(){
        if(ongoingHelper==null)return false;
        Object shown=read(ongoingHelper,"mOngoingActivityLists");
        return shown instanceof Collection&&!((Collection<?>)shown).isEmpty();
    }
    private static boolean hasNativeOngoingCall(){
        Object callCtrl=callController.get();
        return callCtrl!=null&&Boolean.TRUE.equals(invoke(callCtrl,"hasOngoingCall"));
    }
    private static void applyOngoingSuppression(View root,boolean replaceOngoing){
        resolveIds(root);
        suppressById(root,idCapsule,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idChip,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idCapsuleContainer,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idChipsContainer,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idCastChip,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idChipPrimary,replaceOngoing,hasNativeOngoingActivities());
        suppressById(root,idChipSecondary,replaceOngoing,hasNativeOngoingActivities());
    }
    private static void applySuppression(View root,boolean replaceOngoing,boolean replaceCall,boolean replaceRecording){
        resolveIds(root);
        boolean nativeOngoing=hasNativeOngoingActivities();
        suppressById(root,idCapsule,replaceOngoing,nativeOngoing);
        suppressById(root,idChip,replaceOngoing,nativeOngoing);
        suppressById(root,idCapsuleContainer,replaceOngoing,nativeOngoing);
        suppressById(root,idChipsContainer,replaceOngoing,nativeOngoing);
        suppressById(root,idCastChip,replaceOngoing,nativeOngoing);
        suppressById(root,idChipPrimary,replaceOngoing,nativeOngoing);
        suppressById(root,idChipSecondary,replaceOngoing,nativeOngoing);
        View oaParent=oaParentView.get();
        if(oaParent!=null&&oaParent!=root)suppressView(oaParent,replaceOngoing,nativeOngoing);
        boolean nativeCall=hasNativeOngoingCall();
        suppressById(root,idCallChip,replaceCall,nativeCall);
        suppressById(root,idSamsungCallChip,replaceCall,nativeCall);
        suppressById(root,idScreenRecordChip,replaceRecording,recordingActive);
        suppressById(root,idScreenRecordingChip,replaceRecording,recordingActive);
    }
    private static void suppressById(View root,int id,boolean replace,boolean allowRestoreVisible){
        if(id==0)return;
        suppressView(root.findViewById(id),replace,allowRestoreVisible);
    }
    private static void suppressView(View view,boolean replace,boolean allowRestoreVisible){
        if(view==null)return;
        ChipState state=hidden.get(view);
        if(!replace){
            if(state!=null){
                if(allowRestoreVisible||state.visibility!=View.VISIBLE)state.restore(view);
                hidden.remove(view);
            }
            return;
        }
        if(state==null){if(view.getVisibility()==View.GONE)return;state=new ChipState(view);hidden.put(view,state);}
        else if(view.getVisibility()!=View.GONE)state.visibility=view.getVisibility();
        hideReplacedView(view);
    }
    private static void hideReplacedView(View view){
        if(view==null)return;
        view.animate().cancel();
        if(view.getVisibility()!=View.GONE)view.setVisibility(View.GONE);
    }
    private static void recordNativeDesiredVisibility(View view,int visibility){
        if(view==null)return;
        ChipState state=hidden.get(view);
        if(state==null){state=new ChipState(visibility);hidden.put(view,state);}
        else state.visibility=visibility;
    }
    private static void resolveIds(View view){
        if(idsResolved||view==null)return;
        android.content.res.Resources res=view.getResources();
        if(res==null)return;
        idCapsule=res.getIdentifier("ongoing_activity_capsule","id",UI);
        idChip=res.getIdentifier("ongoing_activity_chip","id",UI);
        idCapsuleContainer=res.getIdentifier("samsung_ongoing_activity_chip_container","id",UI);
        idChipsContainer=res.getIdentifier("samsung_status_bar_chips_container","id",UI);
        idCastChip=res.getIdentifier("samsung_ongoing_cast_chip","id",UI);
        idChipPrimary=res.getIdentifier("ongoing_activity_chip_primary","id",UI);
        idChipSecondary=res.getIdentifier("ongoing_activity_chip_secondary","id",UI);
        idCallChip=res.getIdentifier("ongoing_call_chip","id",UI);
        idSamsungCallChip=res.getIdentifier("samsung_ongoing_call_chip","id",UI);
        idScreenRecordChip=res.getIdentifier("screen_record_chip","id",UI);
        idScreenRecordingChip=res.getIdentifier("screen_recording_chip","id",UI);
        idsResolved=true;
    }
    private static boolean isViewReplaced(HyperIslandController controller,View root,View view){
        if(controller==null||view==null||!controller.isReplacementActive())return false;
        if(view==oaParentView.get())return controller.replacesOngoing();
        resolveIds(root!=null?root:view);
        int id=view.getId();
        if(id==View.NO_ID)return false;
        if(id==idCapsule||id==idChip||id==idCapsuleContainer||id==idChipsContainer||id==idCastChip||id==idChipPrimary||id==idChipSecondary)return controller.replacesOngoing();
        if(id==idCallChip||id==idSamsungCallChip)return controller.replacesKind(IslandItem.Kind.CALL);
        if(id==idScreenRecordChip||id==idScreenRecordingChip)return controller.replacesScreenRecording();
        return false;
    }
    private static void nativeChipVisibility(Object object,int visibility){
        if(!(object instanceof View))return;onMain(()->{ChipState state=hidden.get((View)object);if(state!=null)state.visibility=visibility;});
    }
    private static void onMain(Runnable action){if(Looper.myLooper()==main.getLooper())action.run();else main.post(action);}
    private static boolean canRestoreVisible(View v){
        if(v==null)return false;
        int id=v.getId();
        if(id!=View.NO_ID){
            if(id==idScreenRecordChip||id==idScreenRecordingChip)return recording.get()==null||recordingActive;
            if(id==idCallChip||id==idSamsungCallChip)return callController.get()==null||hasNativeOngoingCall();
        }
        return ongoingHelper==null||hasNativeOngoingActivities();
    }
    private static void restoreAll(){
        for(Map.Entry<View,ChipState> entry:new ArrayList<>(hidden.entrySet())){
            View v=entry.getKey();
            if(v!=null&&(canRestoreVisible(v)||entry.getValue().visibility!=View.VISIBLE))entry.getValue().restore(v);
        }
        hidden.clear();
    }
    private static final class ChipState {
        int visibility;
        ChipState(View view){visibility=view.getVisibility();}
        ChipState(int visibility){this.visibility=visibility;}
        void restore(View view){if(view!=null&&view.getVisibility()==View.GONE)view.setVisibility(visibility);}
    }
    private interface Body {void run(XC_MethodHook.MethodHookParam param)throws Throwable;}
    private static XC_MethodHook before(String scope,Body body){return new XC_MethodHook(){
        @Override protected void beforeHookedMethod(MethodHookParam param){try{body.run(param);}catch(Throwable failure){XposedBridge.log("HyperIsland "+scope+": "+failure.getClass().getSimpleName());}}
    };}
    private static XC_MethodHook after(String scope,Body body){return new XC_MethodHook(){
        @Override protected void afterHookedMethod(MethodHookParam param){try{body.run(param);}catch(Throwable failure){XposedBridge.log("HyperIsland "+scope+": "+failure.getClass().getSimpleName());}}
    };}
    private static Class<?> find(ClassLoader loader,String name){try{return Class.forName(name,false,loader);}catch(ClassNotFoundException ignored){return null;}}
    private static void hook(ClassLoader loader,String name,String method,Body body){hook(loader,name,method,after(name+"."+method,body));}
    private static void hook(ClassLoader loader,String name,String method,XC_MethodHook hook){Class<?> type=find(loader,name);if(type==null)return;
        try{XposedBridge.hookAllMethods(type,method,hook);}catch(Throwable failure){XposedBridge.log("HyperIsland hook unavailable: "+name+"."+method);}}
    private static void constructors(ClassLoader loader,String name,Body body){Class<?> type=find(loader,name);if(type==null)return;
        try{XposedBridge.hookAllConstructors(type,after(name+" constructor",body));}catch(Throwable failure){XposedBridge.log("HyperIsland constructor observer unavailable: "+name);}}
    private static Object read(Object owner,String name){
        if(owner==null)return null;Class<?> type=owner instanceof Class?(Class<?>)owner:owner.getClass();
        for(;type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner instanceof Class?null:owner);}catch(Exception ignored){}
        return null;
    }
    private static Object invoke(Object owner,String name){if(owner==null)return null;try{Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);}catch(Exception ignored){return null;}}
    private static Object invoke2(Object owner,String name,Object a,Object b){
        if(owner==null)return null;
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()){
            for(Method method:type.getDeclaredMethods()){
                if(name.equals(method.getName())&&method.getParameterTypes().length==2){
                    try{method.setAccessible(true);return method.invoke(owner,a,b);}catch(Throwable ignored){}
                }
            }
        }
        return null;
    }
}
