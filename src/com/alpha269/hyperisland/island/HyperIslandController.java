package com.alpha269.hyperisland.island;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Source-built controller. Android capabilities supply data and actions; Rust owns motion. */
public final class HyperIslandController implements IslandHost, AutoCloseable {
    public interface WindowAdapter {
        void attach(Context context,IslandHost host,View statusBar);
        void detach();
        void setItems(List<IslandItem> items,int hint,List<IslandItem> minimized);
        void setSuppressed(boolean suppressed);
        void setShadeExpansion(float expansion);
        void setHeight(int pixels);
        View view();
        boolean isExpanded();
        boolean isShown();
        void collapse();
        void setLaunchProgress(float alpha,float scale);
        void onFrame();
        boolean dispatchShadeTouch(MotionEvent event);
        void shadeDragStart(float x,float y,long downTime);
        default void onConfigurationChanged(){}
        default void onInsetsChanged(){}
        default void getRestingPillBounds(RectF out){}
        default Rect windowBounds(){return new Rect();}
        default void setVisualOptions(boolean blur,boolean glass){}
        default void absorb(){}
        default boolean isHealthy(){return true;}
    }
    private static final String TAG="HyperIslandController";
    private static final Uri SETTINGS=Uri.parse("content://com.alpha269.hyperisland.settings");
    private static HyperIslandController instance;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,IslandItem> notifications=new LinkedHashMap<>();
    private final Map<String,MediaSession.Token> notificationTokens=new HashMap<>();
    private final Map<MediaSession.Token,StatusBarNotification> mediaNotifications=new HashMap<>();
    private final Map<String,String> notificationNativeKeys=new HashMap<>();
    private final Map<String,String> nativeKeyAliases=new HashMap<>();
    private final Map<MediaSession.Token,MediaBinding> media=new LinkedHashMap<>();
    private final Set<String> minimized=new LinkedHashSet<>();
    private final Map<String,Long> restored=new HashMap<>();
    private List<IslandItem> visible=Collections.emptyList();
    private List<IslandItem> owned=Collections.emptyList();
    private List<IslandItem> foregroundHidden=Collections.emptyList();
    private HyperOsPresentationQueue presentation;
    private boolean presentationFailed;
    private List<StatusBarNotification> lastNotifications=Collections.emptyList();
    private final Map<String,StatusBarNotification> ongoingNotifications=new LinkedHashMap<>();
    private Map<String,Object> ongoing=new HashMap<>();
    private String foregroundPackage,optimisticForegroundPackage;
    private long optimisticForegroundUntil;
    private Set<String> pipPackages=Collections.emptySet();
    private final Set<String> previouslyHiddenByForeground=new HashSet<>();
    private final Map<String,Long> deferredFlyIn=new LinkedHashMap<>();
    private final Set<String> pendingAbsorbKeys=new LinkedHashSet<>();
    private Set<String> activeExitPackages=Collections.emptySet();
    private Object oaController;
    private java.lang.reflect.Constructor<?> chipInfoCtor;
    private String lastExitSignature;
    private final RectF exitPillBounds=new RectF();
    private AudioManager audioManager;
    private AudioManager.OnCommunicationDeviceChangedListener commDeviceListener;
    private HyperOsBluetoothSource bluetooth;
    private HyperOsTorchSource torch;
    private HyperOsChargingSource charging;
    private HyperOsMediaOutput mediaOutput;
    private MediaSessionManager sessions;
    private WindowAdapter window;
    private View statusBar;
    private Runnable presentationChanged;
    private boolean started,closed,refreshPosted,dozing,screenOff,receiverRegistered,mediaRegistered,settingsRegistered;
    private boolean enabled=true,incomingEnabled=false,bluetoothEnabled=true,blurEnabled=true,glassEnabled=false,chargingAnimEnabled=true,keyguardLocked;
    private int barState,windowState,hint;
    private float shade;
    private String pinned;
    private long version,nextSessionId;
    private IslandItem screenRecording;
    private Runnable screenStop;
    private long screenRecordingStart;
    private boolean screenRecordingKnown;
    private boolean ongoingCallActive,ongoingCallInForeground;
    private String ongoingCallKey,ongoingCallPkg,ongoingCallAppName;
    private PendingIntent ongoingCallIntent;
    private Object ongoingCallNowbarData;
    private long ongoingCallStartMs;
    private static final Set<String> TELECOM_CALL_PACKAGES=new HashSet<>(Arrays.asList(
        "com.samsung.android.incallui","com.samsung.android.dialer","com.android.server.telecom",
        "com.android.phone","com.google.android.dialer","com.android.dialer","com.android.incallui"));
    private static final long PAUSED_MEDIA_TIMEOUT_MS=60_000L;
    private static final long FLY_IN_DELAY_MS=260L;
    private final Runnable refresh=this::refreshNow;
    private final Runnable flyInReveal=this::refreshNow;
    private final Runnable pausedMediaExpiry=this::requestRefresh;
    private final Runnable clearOptimisticForeground=()->{optimisticForegroundPackage=null;optimisticForegroundUntil=0L;requestRefresh();};
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener=this::updateSessions;
    private final ContentObserver settingsObserver=new ContentObserver(main){@Override public void onChange(boolean selfChange){readSettings();}};
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            String action=intent.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(action))screenOff=true;
            else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_USER_PRESENT.equals(action)){
                screenOff=false;
                if(Intent.ACTION_USER_PRESENT.equals(action))readSettings();
            }
            requestRefresh();
        }
    };

    public HyperIslandController(Context context){
        if(context==null)throw new IllegalArgumentException("Missing controller context");
        Context app=context.getApplicationContext();this.context=app==null?context:app;
    }
    public static synchronized HyperIslandController start(Context context){
        if(instance==null||instance.closed)instance=new HyperIslandController(context);
        HyperIslandController current=instance;current.main.post(current::startSources);return current;
    }
    public static synchronized HyperIslandController get(){return instance;}
    private void startSources(){
        if(started||closed)return;started=true;
        readSettings();
        try{context.getContentResolver().registerContentObserver(SETTINGS,false,settingsObserver);settingsRegistered=true;}
        catch(RuntimeException failure){Log.w(TAG,"Settings observation unavailable",failure);}
        if(incomingEnabled)HyperOsIncomingCalls.initialize(context,this::requestRefresh);
        bluetooth=new HyperOsBluetoothSource(context,this::requestRefresh);bluetooth.start();
        torch=new HyperOsTorchSource(context,main,this::requestRefresh);torch.start();
        charging=new HyperOsChargingSource(context,main,this::requestRefresh);charging.start();
        mediaOutput=new HyperOsMediaOutput(context,this::onMediaOutputChanged);mediaOutput.start();
        try{
            sessions=context.getSystemService(MediaSessionManager.class);
            if(sessions!=null){sessions.addOnActiveSessionsChangedListener(sessionListener,null,main);mediaRegistered=true;updateSessions(sessions.getActiveSessions(null));}
        }catch(RuntimeException failure){Log.w(TAG,"Media-session observation unavailable",failure);}
        try{
            audioManager=context.getSystemService(AudioManager.class);
            if(audioManager!=null){
                commDeviceListener=device->{rebuildNotifications();requestRefresh();};
                audioManager.addOnCommunicationDeviceChangedListener(context.getMainExecutor(),commDeviceListener);
            }
        }catch(Throwable failure){Log.w(TAG,"Audio communication observation unavailable",failure);}
        IntentFilter filter=new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        try{context.registerReceiver(receiver,filter,null,main,Context.RECEIVER_NOT_EXPORTED);receiverRegistered=true;}
        catch(RuntimeException failure){Log.w(TAG,"Screen observation unavailable",failure);}
        requestRefresh();
    }
    public void setWindowAdapter(WindowAdapter adapter){onMain(()->{
        if(window!=null)window.detach();window=adapter;
        if(window!=null){
            window.setVisualOptions(blurEnabled,glassEnabled);
            if(statusBar!=null)window.attach(context,this,statusBar);
        }
        requestRefresh();
    });}
    public void setPresentationChanged(Runnable callback){onMain(()->presentationChanged=callback);}
    public void onStatusBarView(View bar){onMain(()->{
        if(closed||bar==null)return;
        if(statusBar!=bar){
            if(window!=null)window.detach();statusBar=bar;
            if(window!=null){window.attach(context,this,bar);window.setVisualOptions(blurEnabled,glassEnabled);}
        }
        requestRefresh();
    });}
    public void onStatusBarViewDetached(View bar){onMain(()->{
        if(statusBar!=bar)return;HyperOsIncomingCalls.reportHidden();
        if(window!=null)window.detach();statusBar=null;changedPresentation();
    });}
    public void onNotifications(List<StatusBarNotification> values){
        final List<StatusBarNotification> copy=values==null?Collections.emptyList():new ArrayList<>(values);
        onMain(()->{lastNotifications=copy;rebuildNotifications();requestRefresh();});
    }
    /** Exact One UI ongoing data objects, collected by the hook; no vendor object is mutated. */
    public void onOngoingActivities(List<?> values){
        final List<?> copy=values==null?Collections.emptyList():new ArrayList<>(values);
        onMain(()->{
            Map<String,Object> next=new HashMap<>();
            ongoingNotifications.clear();
            for(Object value:copy){
                Object key=read(value,"mNotiID");if(key instanceof String)next.put((String)key,value);
                Object entry=read(value,"mNotificationEntry"),raw=read(entry,"mSbn");
                if(raw instanceof StatusBarNotification){StatusBarNotification n=(StatusBarNotification)raw;ongoingNotifications.put(n.getKey(),n);next.put(n.getKey(),value);}
            }
            ongoing=next;rebuildNotifications();requestRefresh();
        });
    }
    public void setOngoingActivityController(Object controller){onMain(()->{
        if(oaController==controller)return;
        oaController=controller;lastExitSignature=null;pushExitTargets(false);
    });}
    public void onForegroundState(String fgPackage,Collection<String> pip){
        final String nextFg=fgPackage!=null&&!fgPackage.isEmpty()?fgPackage:null;
        final Set<String> nextPip=pip==null||pip.isEmpty()?Collections.emptySet():new LinkedHashSet<>(pip);
        onMain(()->{
            boolean fgChanged=!java.util.Objects.equals(foregroundPackage,nextFg);
            boolean optMatches=optimisticForegroundPackage!=null&&java.util.Objects.equals(optimisticForegroundPackage,nextFg);
            boolean clearedOpt=false;
            if(optimisticForegroundPackage!=null&&(fgChanged||optMatches)){
                optimisticForegroundPackage=null;optimisticForegroundUntil=0L;main.removeCallbacks(clearOptimisticForeground);
                clearedOpt=true;
            }
            if(!clearedOpt&&!fgChanged&&pipPackages.equals(nextPip))return;
            foregroundPackage=nextFg;pipPackages=nextPip;
            if(fgChanged&&window!=null&&window.isExpanded()&&!HyperOsActivityLaunch.isLaunching(window.view())){
                window.collapse();
            }
            refreshNow();
        });
    }
    private void rebuildNotifications(){
        List<StatusBarNotification> all=new ArrayList<>(lastNotifications);
        Set<String> keys=new LinkedHashSet<>();
        for(StatusBarNotification n:all)if(n!=null)keys.add(n.getKey());
        for(StatusBarNotification n:ongoingNotifications.values())if(n!=null&&keys.add(n.getKey()))all.add(n);
        rebuildNotifications(all);
    }
    private void rebuildNotifications(List<StatusBarNotification> values){
        Set<String> old=new LinkedHashSet<>(notifications.keySet());notifications.clear();
        notificationTokens.clear();notificationNativeKeys.clear();mediaNotifications.clear();
        for(StatusBarNotification sbn:values){
            if(sbn==null)continue;HyperOsIncomingCalls.onNotificationPosted(sbn);
            Notification notif=sbn.getNotification();
            if(notif!=null&&notif.extras!=null){
                Object mt=notif.extras.get(Notification.EXTRA_MEDIA_SESSION);
                if(mt instanceof MediaSession.Token)mediaNotifications.put((MediaSession.Token)mt,sbn);
            }
            try{
                IslandItem item=notificationItem(context,sbn,ongoing.get(sbn.getKey()));
                if(item==null)continue;item.contentVersion=++version;notifications.put(item.key,item);old.remove(item.key);
                notificationNativeKeys.put(item.key,sbn.getKey());
                Bundle extras=sbn.getNotification().extras;if(extras==null)continue;
                Object rawToken=extras.get(Notification.EXTRA_MEDIA_SESSION);
                if(rawToken instanceof MediaSession.Token)notificationTokens.put(item.key,(MediaSession.Token)rawToken);
                String focus=extras.getString("miui.focus.param_v3");if(focus==null)focus=extras.getString("miui.focus.param");
                if(focus!=null)try{JSONObject full=new JSONObject(focus);Bundle pictures=extras.getBundle("miui.focus.pics");
                    HyperOsCompactFactory.register(item.key,full,pictures);
                    HyperOsCardFactory.registerTemplate(item.key,full,pictures,extras.getBundle("miui.focus.actions"));}
                    catch(Exception failure){Log.w(TAG,"Invalid focus payload",failure);}
            }catch(RuntimeException failure){Log.w(TAG,"Notification ignored",failure);}
        }
        for(String key:old){HyperOsCardFactory.unregisterTemplate(key);HyperOsCompactFactory.unregister(key);}
        for(MediaBinding binding:media.values()){
            if(mediaNotifications.containsKey(binding.token))binding.update();
        }
    }
    public void requestRefresh(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::requestRefresh);return;}
        if(closed||refreshPosted)return;refreshPosted=true;main.post(refresh);
    }
    /** Immediately flushes any queued refresh on the main thread (eliminates multi-frame suppression lag). */
    public void flushRefresh(){
        if(closed||Looper.myLooper()!=main.getLooper())return;
        if(refreshPosted){main.removeCallbacks(refresh);refreshNow();}
    }
    private void readSettings(){try{Bundle values=context.getContentResolver().call(SETTINGS,"values",null,null);if(values!=null)onSettings(values);}
        catch(RuntimeException failure){Log.w(TAG,"Settings read unavailable",failure);}}
    public void onSettings(Bundle values){if(values==null)return;final Bundle copy=new Bundle(values);onMain(()->{
        boolean prevEnabled=enabled;
        enabled=copy.getBoolean("enabled",true);incomingEnabled=false;bluetoothEnabled=copy.getBoolean("bluetooth",true);
        blurEnabled=copy.getBoolean("island_blur",true);glassEnabled=copy.getBoolean("glass_effect",false);
        chargingAnimEnabled=copy.getBoolean("charging_anim",true);
        if(window!=null)window.setVisualOptions(blurEnabled,glassEnabled);
        if(!enabled||!incomingEnabled)HyperOsIncomingCalls.reportHidden();
        if(prevEnabled!=enabled)com.alpha269.hyperisland.xposed.HyperOsShadeIntegration.onEnabledChanged();
        requestRefresh();
    });}
    public boolean isEnabled(){return enabled;}
    public boolean isChargingAnimEnabled(){return isReplacementActive()&&chargingAnimEnabled&&charging!=null;}
    public void onBatteryPlugged(int level,int superFastType){onMain(()->{
        if(!closed&&charging!=null&&chargingAnimEnabled)charging.onPluggedEvent(level,superFastType);
    });}
    public void collapseIfExpanded(){onMain(()->{if(window!=null&&window.isExpanded())window.collapse();});}
    /** Accepts actual epoch or elapsed start time; presentation uses elapsedRealtime consistently. */
    public void onScreenRecording(boolean recording,long startEpoch,Runnable stop){onMain(()->{
        screenRecordingKnown=true;
        if(!recording){if(screenRecording!=null){screenRecording=null;screenStop=null;screenRecordingStart=0;requestRefresh();}return;}
        long sourceStart=startEpoch>0?startEpoch:screenRecording!=null?screenRecordingStart:SystemClock.elapsedRealtime();
        if(screenRecording!=null&&screenRecordingStart==sourceStart&&screenStop==stop)return;
        long base=elapsedTimestamp(sourceStart);
        IslandItem item=new IslandItem("hyperos:screenrecord");item.kind=IslandItem.Kind.RECORDING;item.recorder=true;item.packageName="com.android.systemui";
        item.title="Screen recording";item.subtitle="Recording your screen";item.overline="Screen recording";
        item.trailing=IslandItem.Trailing.CHRONOMETER;item.chronoBase=base;item.postTime=sourceStart>100000000000L?sourceStart:System.currentTimeMillis()-(SystemClock.elapsedRealtime()-base);item.contentVersion=++version;
        item.accent=0xffffffff;item.pillColor=0xff000000;
        if(stop!=null){IslandItem.Action stopAction=new IslandItem.Action("Stop",null,null,stop,true);stopAction.glyph=6;item.actions.add(stopAction);}
        screenRecording=item;screenStop=stop;screenRecordingStart=sourceStart;requestRefresh();
    });}
    public void onOngoingCallInfo(boolean active,String key,String pkg,long startMs){
        onOngoingCallInfo(active,key,pkg,startMs,false,null,null,null);
    }
    public void onOngoingCallInfo(boolean active,String key,String pkg,long startMs,boolean inForeground){
        onOngoingCallInfo(active,key,pkg,startMs,inForeground,null,null,null);
    }
    public void onOngoingCallInfo(boolean active,String key,String pkg,long startMs,boolean inForeground,String appName,PendingIntent intent,Object nowbarData){onMain(()->{
        if(ongoingCallActive==active&&ongoingCallInForeground==inForeground
            &&java.util.Objects.equals(ongoingCallKey,key)&&java.util.Objects.equals(ongoingCallPkg,pkg)
            &&ongoingCallStartMs==startMs&&java.util.Objects.equals(ongoingCallAppName,appName)
            &&ongoingCallIntent==intent&&ongoingCallNowbarData==nowbarData)return;
        ongoingCallActive=active;ongoingCallInForeground=inForeground;ongoingCallKey=key;ongoingCallPkg=pkg;
        ongoingCallStartMs=startMs;ongoingCallAppName=appName;ongoingCallIntent=intent;ongoingCallNowbarData=nowbarData;
        requestRefresh();
    });}
    private void refreshNow(){
        refreshPosted=false;if(closed)return;
        main.removeCallbacks(pausedMediaExpiry);
        main.removeCallbacks(flyInReveal);
        updateKeyguard();
        long now=SystemClock.uptimeMillis();List<IslandItem> all=new ArrayList<>(notifications.values());
        if(screenRecordingKnown)all.removeIf(item->item.kind==IslandItem.Kind.RECORDING&&("com.android.systemui".equals(item.packageName)||"com.samsung.android.app.smartcapture".equals(item.packageName)));
        if(screenRecording!=null){
            for(IslandItem notification:notifications.values())if(notification.kind==IslandItem.Kind.RECORDING&&("com.android.systemui".equals(notification.packageName)||"com.samsung.android.app.smartcapture".equals(notification.packageName))){
                screenRecording.icon=notification.icon;screenRecording.appIcon=notification.appIcon;
                if(notification.contentIntent!=null)screenRecording.contentIntent=notification.contentIntent;
                if(screenRecording.actions.isEmpty())screenRecording.actions.addAll(notification.actions);
                if(screenRecording.actions.isEmpty()&&notification.contentIntent!=null){
                    IslandItem.Action stopAction=new IslandItem.Action("Stop",null,notification.contentIntent,null,true);
                    stopAction.glyph=6;screenRecording.actions.add(stopAction);
                }
                break;
            }
            all.add(screenRecording);
        }
        MediaBinding bestMedia=selectCurrentMedia(media.values(),now);
        Set<MediaSession.Token> sessionTokens=new LinkedHashSet<>(media.keySet());
        Set<String> mediaPackages=new LinkedHashSet<>();
        for(MediaBinding binding:media.values())if(binding.item!=null&&binding.item.packageName!=null)mediaPackages.add(binding.item.packageName);
        if(bestMedia!=null&&bestMedia.item!=null){
            all.add(bestMedia.item);
            if(bestMedia.item.packageName!=null)mediaPackages.add(bestMedia.item.packageName);
            if(!bestMedia.wasPlaying&&bestMedia.lastActiveUptime>0){
                long wait=(PAUSED_MEDIA_TIMEOUT_MS-(now-bestMedia.lastActiveUptime))+50L;
                main.postDelayed(pausedMediaExpiry,Math.max(50L,wait));
            }
        }
        all.removeIf(item->!item.isMedia&&item.kind==IslandItem.Kind.MEDIA
            &&(sessionTokens.contains(notificationTokens.get(item.key))||mediaPackages.contains(item.packageName)));
        // Incoming calls stay 100% native One UI; keep only ongoing active calls.
        all.removeIf(item->item.kind==IslandItem.Kind.CALL&&HyperOsIncomingCalls.isIncoming(item));
        boolean hasActiveCall=false;
        for(IslandItem item:all)if(item.kind==IslandItem.Kind.CALL){hasActiveCall=true;break;}
        if(ongoingCallActive&&!hasActiveCall&&ongoingCallKey!=null){
            IslandItem synthesized=buildControllerOngoingCall();
            if(synthesized!=null)all.add(synthesized);
        }
        if(ongoingCallActive&&ongoingCallStartMs>0){
            for(IslandItem item:all){
                if(item.kind==IslandItem.Kind.CALL&&!HyperOsIncomingCalls.isIncoming(item)&&!item.hasChronometer()
                    &&(ongoingCallKey==null||item.key.equals("hyperos:notification:"+ongoingCallKey)||item.key.equals("hyperos:call:"+ongoingCallKey)||(ongoingCallPkg!=null&&ongoingCallPkg.equals(item.packageName)))){
                    item.trailing=IslandItem.Trailing.CHRONOMETER;
                    item.chronoBase=elapsedTimestamp(ongoingCallStartMs);
                    item.chronoCountDown=false;
                }
            }
        }
        Set<String> exitPackages=new LinkedHashSet<>();
        for(IslandItem item:all){
            if(item.packageName!=null&&!"com.android.systemui".equals(item.packageName))exitPackages.add(item.packageName);
            String creator=item.contentIntent!=null?item.contentIntent.getCreatorPackage():null;
            if(creator!=null&&!"com.android.systemui".equals(creator))exitPackages.add(creator);
        }
        Set<String> allCandidateKeys=new LinkedHashSet<>();
        for(IslandItem item:all)allCandidateKeys.add(item.key);
        deferredFlyIn.keySet().retainAll(allCandidateKeys);
        pendingAbsorbKeys.retainAll(allCandidateKeys);
        Set<String> hiddenByFgNow=new LinkedHashSet<>();
        List<IslandItem> fgHiddenItems=new ArrayList<>();
        java.util.Iterator<IslandItem> fgIter=all.iterator();
        while(fgIter.hasNext()){
            IslandItem item=fgIter.next();
            if(isHiddenByForeground(item)){
                hiddenByFgNow.add(item.key);fgHiddenItems.add(item);
                deferredFlyIn.remove(item.key);pendingAbsorbKeys.remove(item.key);
                fgIter.remove();
            }
        }
        boolean launcherExitBridgeReady=oaController!=null&&lastExitSignature!=null;
        long nextFlyInWait=Long.MAX_VALUE;
        List<IslandItem> exitedFromApp=new ArrayList<>();
        java.util.Iterator<IslandItem> returnIter=all.iterator();
        while(returnIter.hasNext()){
            IslandItem item=returnIter.next();
            boolean justExited=previouslyHiddenByForeground.contains(item.key);
            Long readyAt=deferredFlyIn.get(item.key);
            if(justExited&&readyAt==null){
                if(launcherExitBridgeReady&&!isCurrentlyVisible(item.key)){
                    readyAt=now+FLY_IN_DELAY_MS;
                    deferredFlyIn.put(item.key,readyAt);
                    pendingAbsorbKeys.add(item.key);
                }else{
                    exitedFromApp.add(item);
                    pendingAbsorbKeys.add(item.key);
                }
            }
            if(readyAt!=null){
                if(now<readyAt){
                    fgHiddenItems.add(item);
                    returnIter.remove();
                    nextFlyInWait=Math.min(nextFlyInWait,readyAt-now);
                }else{
                    deferredFlyIn.remove(item.key);
                    exitedFromApp.add(item);
                }
            }
        }
        foregroundHidden=fgHiddenItems;
        previouslyHiddenByForeground.clear();
        previouslyHiddenByForeground.addAll(hiddenByFgNow);
        if(nextFlyInWait!=Long.MAX_VALUE){
            main.postDelayed(flyInReveal,Math.max(16L,nextFlyInWait));
        }
        nativeKeyAliases.clear();
        for(IslandItem item:notifications.values()){
            String nativeKey=notificationNativeKeys.get(item.key);
            if(nativeKey!=null)nativeKeyAliases.put(nativeKey,item.key);
        }
        if(ongoingCallActive&&ongoingCallKey!=null){
            nativeKeyAliases.put(ongoingCallKey,"hyperos:call:"+ongoingCallKey);
        }
        if(bestMedia!=null&&bestMedia.item!=null){
            for(StatusBarNotification sbn:lastNotifications){
                if(sbn==null||sbn.getNotification()==null)continue;
                Bundle extras=sbn.getNotification().extras;
                Object token=extras==null?null:extras.get(Notification.EXTRA_MEDIA_SESSION);
                if(bestMedia.token.equals(token))nativeKeyAliases.put(sbn.getKey(),bestMedia.item.key);
            }
            for(Map.Entry<String,Object> entry:ongoing.entrySet()){
                Object value=entry.getValue();
                if(value==null)continue;
                Object sbnObj=read(read(value,"mNotificationEntry"),"mSbn");
                StatusBarNotification sbn=sbnObj instanceof StatusBarNotification?(StatusBarNotification)sbnObj:null;
                if(sbn!=null&&sbn.getNotification()!=null&&sbn.getNotification().extras!=null){
                    Object token=sbn.getNotification().extras.get(Notification.EXTRA_MEDIA_SESSION);
                    if(bestMedia.token.equals(token))nativeKeyAliases.put(sbn.getKey(),bestMedia.item.key);
                }
                boolean mediaData=Boolean.TRUE.equals(read(value,"mIsMediaOngoingData"))||isSyntheticMediaOngoing(sbn,value);
                if(!mediaData)continue;
                String owner=resolveOngoingMediaOwner(value);
                if(bestMedia.item.packageName!=null&&(bestMedia.item.packageName.equals(owner)||owner==null)){
                    nativeKeyAliases.put(entry.getKey(),bestMedia.item.key);
                }
            }
        }
        if(bluetooth!=null&&bluetoothEnabled)bluetooth.appendTo(all);
        List<IslandItem> chargingOnly=new ArrayList<>(1);
        if(charging!=null&&chargingAnimEnabled)charging.appendTo(chargingOnly);
        if(torch!=null) {
            if(torch.available())all.removeIf(item->HyperOsTorchSource.nativeNotificationKey(item.key));
            torch.appendTo(all,window!=null&&window.isExpanded());
        }
        all.removeIf(item->item.expiresAtUptime>0&&item.expiresAtUptime<=now);
        Set<String> present=new LinkedHashSet<>();for(IslandItem item:all)present.add(item.key);
        minimized.retainAll(present);restored.keySet().retainAll(present);
        if(pinned!=null&&!present.contains(pinned))pinned=null;
        all.sort((a,b)->{
            boolean aIn=HyperOsIncomingCalls.isIncoming(a),bIn=HyperOsIncomingCalls.isIncoming(b);
            if(aIn!=bIn)return aIn?-1:1;
            if(a.kind==IslandItem.Kind.CALL&&b.kind!=IslandItem.Kind.CALL)return -1;
            if(b.kind==IslandItem.Kind.CALL&&a.kind!=IslandItem.Kind.CALL)return 1;
            if(a.kind==IslandItem.Kind.CHARGING&&b.kind!=IslandItem.Kind.CHARGING)return -1;
            if(b.kind==IslandItem.Kind.CHARGING&&a.kind!=IslandItem.Kind.CHARGING)return 1;
            if(a.key.equals(pinned)!=b.key.equals(pinned))return a.key.equals(pinned)?-1:1;
            int boost=Long.compare(restored.getOrDefault(b.key,0L),restored.getOrDefault(a.key,0L));if(boost!=0)return boost;
            int priority=Integer.compare(b.priority(),a.priority());return priority!=0?priority:Long.compare(b.postTime,a.postTime);
        });
        List<IslandItem> hidden=new ArrayList<>(),shown=new ArrayList<>();
        for(IslandItem item:all){if(minimized.contains(item.key)&&item.kind!=IslandItem.Kind.CALL)hidden.add(item);else shown.add(item);}
        if(HyperOsNative.isLoaded()&&!presentationFailed){
            try{
                if(presentation==null){presentation=new HyperOsPresentationQueue();minimized.clear();restored.clear();pinned=null;}
                presentation.sync(all);
                // HyperOS ExitApp: when leaving an app whose activity was foreground-hidden,
                // promote that returning activity to BigIsland and shift the prior BigIsland to SmallIsland.
                if(!exitedFromApp.isEmpty()){
                    IslandItem returning=exitedFromApp.get(0);
                    if(returning!=null)presentation.focus(returning);
                }
                shown=new ArrayList<>(presentation.layout().visible);hidden=new ArrayList<>(presentation.layout().hidden);
            }catch(Throwable failure){failPresentation(failure);shown.clear();hidden.clear();}
        }
        List<IslandItem> allOwned=new ArrayList<>(shown.size()+hidden.size()+chargingOnly.size());
        allOwned.addAll(shown);allOwned.addAll(hidden);allOwned.addAll(chargingOnly);
        owned=allOwned;
        // HyperOS temp-show: while charging capsule is active (and no card is expanded),
        // temporarily hide other islands so the charging animation gets full solo capsule width.
        if(!chargingOnly.isEmpty()&&(window==null||!window.isExpanded())){
            List<IslandItem> parked=new ArrayList<>(shown.size()+hidden.size());
            parked.addAll(shown);parked.addAll(hidden);
            shown=chargingOnly;
            hidden=parked;
        }
        visible=shown;
        if(window!=null&&statusBar!=null){
            window.setSuppressed(suppressed());window.setShadeExpansion(shade);window.setItems(shown,hint,hidden);hint=0;
            boolean shouldAbsorb=false;
            if(!pendingAbsorbKeys.isEmpty()){
                for(IslandItem item:shown){if(pendingAbsorbKeys.remove(item.key))shouldAbsorb=true;}
            }
            if(shouldAbsorb&&!window.isExpanded()&&!suppressed())window.absorb();
        }
        if(suppressed()||shown.isEmpty()||window==null||!window.isShown())HyperOsIncomingCalls.reportHidden();
        if(!exitPackages.equals(activeExitPackages)){activeExitPackages=exitPackages;pushExitTargets(false);}
        changedPresentation();
    }
    private boolean isCurrentlyVisible(String key){
        if(key==null)return false;
        for(IslandItem item:visible)if(key.equals(item.key))return true;
        return false;
    }
    public boolean isHiddenByForeground(IslandItem item){
        if(item==null)return false;
        if(HyperOsIncomingCalls.isIncoming(item)||item.torch||item==screenRecording)return false;
        if(item.key.startsWith("hyperos:bluetooth:")||"com.android.systemui".equals(item.packageName))return false;
        String fg=foregroundPackage;
        if(optimisticForegroundPackage!=null&&SystemClock.uptimeMillis()<optimisticForegroundUntil)fg=optimisticForegroundPackage;
        String pkg=item.packageName;
        String creator=item.contentIntent!=null?item.contentIntent.getCreatorPackage():null;
        if(item.kind==IslandItem.Kind.CALL){
            if(ongoingCallInForeground&&(ongoingCallPkg==null||ongoingCallPkg.equals(pkg)||TELECOM_CALL_PACKAGES.contains(pkg)))return true;
            if(fg!=null&&TELECOM_CALL_PACKAGES.contains(pkg)&&TELECOM_CALL_PACKAGES.contains(fg))return true;
        }
        if(fg==null&&pipPackages.isEmpty())return false;
        if(item.isMedia)return fg!=null&&(fg.equals(pkg)||fg.equals(creator));
        if(fg!=null&&(fg.equals(pkg)||fg.equals(creator)))return true;
        return !pipPackages.isEmpty()&&((pkg!=null&&pipPackages.contains(pkg))||(creator!=null&&pipPackages.contains(creator)));
    }
    private boolean suppressed(){
        return !enabled||presentationFailed||dozing||screenOff||barState!=0||windowState==2||keyguardLocked;
    }
    private void updateKeyguard(){
        try{KeyguardManager manager=context.getSystemService(KeyguardManager.class);keyguardLocked=manager!=null&&manager.isKeyguardLocked();}
        catch(RuntimeException ignored){keyguardLocked=true;}
    }
    public void setBarState(int state){onMain(()->{barState=state;requestRefresh();});}
    public void setDozing(boolean value){onMain(()->{dozing=value;requestRefresh();});}
    public void setStatusBarWindowState(int value){onMain(()->{windowState=value;requestRefresh();});}
    public void setShadeExpansion(float value){onMain(()->{shade=Math.max(0,Math.min(1,value));requestRefresh();});}
    public boolean isShown(){return window!=null&&statusBar!=null&&!suppressed()&&shade<.01f&&window.isShown()&&!visible.isEmpty();}
    public boolean isReplacementActive(){return window!=null&&statusBar!=null&&window.isHealthy()&&!suppressed();}
    /** Hide only a native category while HyperBar owns an active or foreground-hidden replacement item for it. */
    public boolean replacesKind(IslandItem.Kind kind){
        if(!isReplacementActive())return false;
        for(IslandItem item:owned)if(item.kind==kind)return true;
        for(IslandItem item:foregroundHidden)if(item.kind==kind)return true;
        return false;
    }
    public boolean replacesOngoing(){
        if(!isReplacementActive())return false;
        for(IslandItem item:owned){
            if(notifications.containsKey(item.key)||item.isMedia||item==screenRecording||item.kind==IslandItem.Kind.CALL)return true;
        }
        for(IslandItem item:foregroundHidden){
            if(notifications.containsKey(item.key)||item.isMedia||item==screenRecording||item.kind==IslandItem.Kind.CALL)return true;
        }
        return false;
    }
    public boolean replacesScreenRecording(){
        if(!isReplacementActive())return false;
        for(IslandItem item:owned){
            if(item.kind==IslandItem.Kind.RECORDING&&("com.android.systemui".equals(item.packageName)||"com.samsung.android.app.smartcapture".equals(item.packageName)))return true;
        }
        for(IslandItem item:foregroundHidden){
            if(item.kind==IslandItem.Kind.RECORDING&&("com.android.systemui".equals(item.packageName)||"com.samsung.android.app.smartcapture".equals(item.packageName)))return true;
        }
        return false;
    }
    public boolean isNativeKeyReplaced(String nativeKey){
        if(!isReplacementActive()||nativeKey==null)return false;
        String target=nativeKeyAliases.get(nativeKey);
        String direct="hyperos:notification:"+nativeKey;
        for(IslandItem item:owned){
            String key=item.key;
            if((target!=null&&target.equals(key))||direct.equals(key))return true;
        }
        for(IslandItem item:foregroundHidden){
            String key=item.key;
            if((target!=null&&target.equals(key))||direct.equals(key))return true;
        }
        return false;
    }
    public boolean hasExitTargets(){return !activeExitPackages.isEmpty();}
    /** Shade Live Card lookup by native notification key (matches ColorOS OaSource.itemForKey policy). */
    public IslandItem shadeItemForKey(String notificationKey){
        if(!enabled||notificationKey==null)return null;
        if(torch!=null&&torch.available()&&HyperOsTorchSource.nativeNotificationKey(notificationKey)){
            List<IslandItem> list=new ArrayList<>(1);
            torch.appendTo(list,false);
            if(!list.isEmpty())return list.get(0);
        }
        Object oa=ongoing.get(notificationKey);
        if(oa!=null&&integer(read(oa,"mStyle"),0)>=2)return null;
        StatusBarNotification sbn=ongoingNotifications.get(notificationKey);
        if(sbn==null){
            for(StatusBarNotification candidate:lastNotifications){
                if(candidate!=null&&notificationKey.equals(candidate.getKey())){sbn=candidate;break;}
            }
        }
        if(sbn!=null&&sbn.getNotification()!=null&&sbn.getNotification().extras!=null
            &&integer(sbn.getNotification().extras.get("android.ongoingActivityNoti.style"),0)>=2)return null;
        IslandItem item=notifications.get("hyperos:notification:"+notificationKey);
        if(item==null)return null;
        if(item.kind==IslandItem.Kind.CALL||item.isMedia||HyperOsIncomingCalls.isIncoming(item))return null;
        return item;
    }
    /** Shade Media Player lookup by MediaSession.Token (matches ColorOS MediaSource.itemForSession). */
    public IslandItem mediaItemForSession(MediaSession.Token token){
        if(!enabled||token==null)return null;
        MediaBinding binding=media.get(token);
        return binding==null?null:binding.item;
    }
    private IslandItem buildControllerOngoingCall(){
        IslandItem item=new IslandItem("hyperos:call:"+ongoingCallKey);
        item.kind=IslandItem.Kind.CALL;
        item.packageName=ongoingCallPkg!=null?ongoingCallPkg:HyperOsIncomingCalls.APP;
        item.contentIntent=ongoingCallIntent;
        item.postTime=System.currentTimeMillis();
        item.contentVersion=++version;
        item.pillColor=0xff000000;item.accent=0xff34c759;item.iconTint=0xffffffff;item.trailingColor=0xffffffff;
        item.overline="Call";
        CharSequence caller=text(read(ongoingCallNowbarData,"mPrimaryInfo"));
        item.title=caller!=null&&caller.length()>0?caller:(ongoingCallAppName!=null&&!ongoingCallAppName.isEmpty()?ongoingCallAppName:"Ongoing call");
        item.subtitle=ongoingCallAppName!=null&&!ongoingCallAppName.isEmpty()?ongoingCallAppName:"Ongoing call";
        if(ongoingCallStartMs>0){
            item.trailing=IslandItem.Trailing.CHRONOMETER;
            item.chronoBase=elapsedTimestamp(ongoingCallStartMs);
            item.chronoCountDown=false;
        }
        Object rawActions=read(ongoingCallNowbarData,"mActions");
        if(rawActions instanceof Collection){
            for(Object value:(Collection<?>)rawActions){
                if(!(value instanceof Notification.Action))continue;
                Notification.Action action=(Notification.Action)value;
                if(action.actionIntent==null)continue;
                IslandItem.Action cap=new IslandItem.Action(action.title,load(context,action.getIcon()),action.actionIntent,null,isHangupTitle(action.title));
                item.actions.add(cap);
            }
        }
        decorateApp(context,item);
        Drawable large=load(context,read(ongoingCallNowbarData,"mNowbarIcon"));
        if(large!=null){item.cardImage=large;item.cardImageIsImage=true;}
        configureActiveCallActions(context,null,item);
        return item;
    }
    public void onWindowConfigurationChanged(){onMain(()->{if(window!=null)window.onConfigurationChanged();pushExitTargets(false);requestRefresh();});}
    public void onWindowInsetsChanged(){onMain(()->{if(window!=null)window.onInsetsChanged();pushExitTargets(false);requestRefresh();});}
    public void onExitTargetsRequested(){onMain(()->{pushExitTargets(true);onIslandFrame();});}
    private void pushExitTargets(boolean force){
        if(oaController==null||window==null)return;
        try{
            Object listener=read(oaController,"mChipInfoListener");
            Object remote=listener==null?null:read(listener,"mRemote");
            if(!(remote instanceof IBinder))return;
            Rect bounds=window.windowBounds();
            if(bounds==null||bounds.isEmpty())return;
            float density=context.getResources().getDisplayMetrics().density;
            exitPillBounds.setEmpty();
            window.getRestingPillBounds(exitPillBounds);
            float pw=Math.max(exitPillBounds.width(),density*90f);
            float ph=Math.max(exitPillBounds.height(),density*30f);
            int shortSide=Math.min(bounds.width(),bounds.height()),longSide=Math.max(bounds.width(),bounds.height());
            boolean portrait=bounds.width()<=bounds.height();
            float cy=exitPillBounds.isEmpty()?density*22f:exitPillBounds.centerY();
            float cxP=portrait&&!exitPillBounds.isEmpty()?exitPillBounds.centerX():shortSide/2f;
            float cxL=!portrait&&!exitPillBounds.isEmpty()?exitPillBounds.centerX():longSide/2f;
            Rect port=new Rect(Math.round(cxP-pw/2f),Math.round(cy-ph/2f),Math.round(cxP+pw/2f),Math.round(cy+ph/2f));
            Rect land=new Rect(Math.round(cxL-pw/2f),Math.round(cy-ph/2f),Math.round(cxL+pw/2f),Math.round(cy+ph/2f));
            String sig=activeExitPackages+"|"+port+"|"+land;
            if(!force&&sig.equals(lastExitSignature))return;
            if(chipInfoCtor==null){
                Class<?> cls=Class.forName("com.android.systemui.shared.launcher.OnGoingChipInfo",false,oaController.getClass().getClassLoader());
                chipInfoCtor=cls.getConstructor(String.class,Rect.class,Rect.class);
            }
            ArrayList<Parcelable> list=new ArrayList<>(activeExitPackages.size());
            for(String pkg:activeExitPackages)list.add((Parcelable)chipInfoCtor.newInstance(pkg,new Rect(port),new Rect(land)));
            Parcel data=Parcel.obtain();
            try{
                data.writeInterfaceToken("com.android.systemui.shared.launcher.IOnGoingChipListener");
                data.writeTypedList(list,0);
                ((IBinder)remote).transact(2,data,null,IBinder.FLAG_ONEWAY);
                lastExitSignature=sig;
            }finally{data.recycle();}
        }catch(Throwable ignored){}
    }
    private void changedPresentation(){if(presentationChanged!=null)presentationChanged.run();}
    @Override public void onIslandFrame(){if(window!=null)window.onFrame();if(presentation==null&&!presentationFailed&&HyperOsNative.isLoaded())requestRefresh();}
    @Override public boolean hasMinimized(){return presentation!=null?!presentation.layout().hidden.isEmpty():!minimized.isEmpty();}
    @Override public void onSwipe(int direction){onMain(()->{
        if(charging!=null&&chargingAnimEnabled&&charging.isActive(SystemClock.uptimeMillis())&&(window==null||!window.isExpanded())){
            charging.dismiss();hint=direction<0?1:2;refreshNow();return;
        }
        if(presentation==null)return;
        try{
            if(presentation.size()>=2)presentation.cycle(direction);
            else presentation.swipe(direction);
            hint=direction<0?1:2;refreshNow();
        }catch(Throwable failure){failPresentation(failure);}
    });}
    @Override public HyperOsPresentationQueue.Layout previewSwipe(int direction){
        if(charging!=null&&chargingAnimEnabled&&charging.isActive(SystemClock.uptimeMillis())&&(window==null||!window.isExpanded())){
            return presentation!=null?presentation.layout():new HyperOsPresentationQueue.Layout(Collections.emptyList(),Collections.emptyList(),true);
        }
        if(presentation==null)return null;
        return presentation.size()>=2?presentation.previewCycle(direction):presentation.preview(direction);
    }
    private void failPresentation(Throwable failure){
        presentationFailed=true;if(presentation!=null)try{presentation.close();}catch(Throwable ignored){}presentation=null;
        HyperOsIncomingCalls.reportHidden();if(window!=null)window.setSuppressed(true);changedPresentation();
        Log.e(TAG,"Presentation state failed",failure);
    }
    @Override public void onFocusItem(IslandItem item){
        if(item==null)return;
        onMain(()->{
            if(presentation!=null){
                try{presentation.focus(item);refreshNow();}
                catch(Throwable failure){failPresentation(failure);}
                return;
            }
            pinned=item.key;refreshNow();
        });
    }
    @Override public void onCycle(int direction){
        if(presentation!=null){List<IslandItem> candidates=new ArrayList<>(presentation.layout().visible);candidates.addAll(presentation.layout().hidden);
            if(candidates.size()<2)return;try{presentation.focus(candidates.get(Math.floorMod(direction,candidates.size())));requestRefresh();}catch(Throwable failure){failPresentation(failure);}return;}
        if(visible.size()<2)return;int index=0;if(pinned!=null)for(int i=0;i<visible.size();i++)if(visible.get(i).key.equals(pinned))index=i;
        pinned=visible.get(Math.floorMod(index+direction,visible.size())).key;hint=direction>0?1:2;requestRefresh();
    }
    @Override public void onMinimize(IslandItem item){if(item==null||item.kind==IslandItem.Kind.CALL)return;
        if(presentation!=null){try{presentation.minimize(item);hint=1;requestRefresh();}catch(Throwable failure){failPresentation(failure);}return;}
        minimized.add(item.key);restored.remove(item.key);hint=1;requestRefresh();}
    @Override public void onRestore(){if(presentation!=null){onSwipe(1);return;}if(minimized.isEmpty())return;String latest=null;for(String key:minimized)latest=key;minimized.remove(latest);restored.put(latest,SystemClock.uptimeMillis());hint=2;requestRefresh();}
    @Override public void onExpandedChanged(boolean expanded){if(!expanded)requestRefresh();onIslandFrame();}
    @Override public void onWindowHeightNeeded(int pixels){if(window!=null)window.setHeight(pixels);}
    @Override public boolean onShadeFade(float alpha,float scale,float x,float y){
        if(window==null)return false;window.setLaunchProgress(alpha,scale);return true;
    }
    @Override public void onShadeDragStart(float x,float y,long downTime){
        if(window!=null)window.shadeDragStart(x,y,downTime);
        if(statusBar!=null){MotionEvent down=MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,x,y,0);try{statusBar.dispatchTouchEvent(down);}finally{down.recycle();}}
    }
    @Override public void onShadeDragEvent(MotionEvent event){
        if(statusBar==null||event==null)return;MotionEvent copy=MotionEvent.obtain(event);try{statusBar.dispatchTouchEvent(copy);}finally{copy.recycle();}
    }
    @Override public void onOpenItem(IslandItem item,RectF from){
        openItemInternal(item,from,window!=null&&window.isExpanded());
    }
    @Override public void onOpenCapsule(IslandItem item,RectF from){
        openItemInternal(item,from,false);
    }
    private void openItemInternal(IslandItem item,RectF from,boolean fromCard){
        if(item==null)return;
        if(item.torch&&item.contentIntent==null)return;
        try{
            Bundle options=null;
            boolean remoteLaunch=false;
            View sourceView=window==null?null:window.view();
            if(sourceView!=null&&from!=null&&!from.isEmpty()){
                try{
                    options=HyperOsActivityLaunch.options(context,sourceView,from,fromCard,false);
                    if(options!=null)remoteLaunch=true;
                }
                catch(Exception unsupported){Log.w(TAG,"Remote transition unavailable; falling back to scale-up",unsupported);}
                if(options==null)options=scaleUpOptions(sourceView,from);
            }
            if(options==null)options=activityOptions();
            if(item.contentIntent!=null)item.contentIntent.send(context,0,null,null,null,null,options);
            else if(item.mediaOpen!=null)item.mediaOpen.run();
            else if(item.packageName!=null&&!"com.android.systemui".equals(item.packageName)){
                Intent intent=context.getPackageManager().getLaunchIntentForPackage(item.packageName);
                if(intent!=null){intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent,options);}
            }
            if(!remoteLaunch&&fromCard&&window!=null&&window.isExpanded())window.collapse();
            String openedPkg=item.contentIntent!=null&&item.contentIntent.getCreatorPackage()!=null?item.contentIntent.getCreatorPackage():item.packageName;
            if(openedPkg!=null&&!openedPkg.isEmpty()&&!"com.android.systemui".equals(openedPkg)
                &&!item.torch&&!HyperOsIncomingCalls.isIncoming(item)&&item!=screenRecording){
                optimisticForegroundPackage=openedPkg;
                optimisticForegroundUntil=SystemClock.uptimeMillis()+1800L;
                main.removeCallbacks(clearOptimisticForeground);
                main.postDelayed(clearOptimisticForeground,1800L);
                requestRefresh();
            }
        }catch(Exception failure){Log.w(TAG,"Open capability failed",failure);}
    }
    private static Bundle scaleUpOptions(View sourceView,RectF from){
        try{
            ActivityOptions opts=ActivityOptions.makeScaleUpAnimation(sourceView,Math.round(from.left),Math.round(from.top),Math.max(1,Math.round(from.width())),Math.max(1,Math.round(from.height())));
            opts.setPendingIntentBackgroundActivityStartMode(1);
            return opts.toBundle();
        }catch(Throwable ignored){return activityOptions();}
    }
    @Override public void onItemAction(IslandItem item,IslandItem.Action action){
        if(action==null)return;try{
            if(action.runnable!=null)action.runnable.run();else if(action.intent!=null)action.intent.send(context,0,null,null,null,null,activityOptions());
        }catch(Exception failure){Log.w(TAG,"Action capability failed",failure);}requestRefresh();
    }
    private static Bundle activityOptions(){ActivityOptions options=ActivityOptions.makeBasic();options.setPendingIntentBackgroundActivityStartMode(1);return options.toBundle();}
    private void onMediaOutputChanged(){
        if(closed)return;for(MediaBinding binding:new ArrayList<>(media.values()))binding.update();requestRefresh();
    }
    private void updateSessions(List<MediaController> controllers){
        if(Looper.myLooper()!=main.getLooper()){List<MediaController> copy=controllers==null?Collections.emptyList():new ArrayList<>(controllers);main.post(()->updateSessions(copy));return;}
        if(closed)return;Set<MediaSession.Token> activeTokens=new LinkedHashSet<>();
        if(controllers!=null)for(MediaController controller:controllers){
            if(controller==null)continue;
            MediaSession.Token token=controller.getSessionToken();
            if(token==null)continue;
            activeTokens.add(token);
            MediaBinding binding=media.get(token);
            if(binding==null){
                binding=new MediaBinding("hyperos:media:"+controller.getPackageName()+":"+(++nextSessionId),token,controller);
                media.put(token,binding);controller.registerCallback(binding,main);
            }
            binding.update();
        }
        for(MediaSession.Token token:new ArrayList<>(media.keySet()))if(!activeTokens.contains(token)){
            MediaBinding binding=media.remove(token);
            if(binding!=null){try{binding.controller.unregisterCallback(binding);}catch(RuntimeException ignored){}if(mediaOutput!=null)mediaOutput.unbind(binding.key);}
        }
        requestRefresh();
    }
    static boolean isActivePlaybackState(PlaybackState state){
        if(state==null)return false;
        int code=state.getState();
        return code==PlaybackState.STATE_PLAYING||code==PlaybackState.STATE_BUFFERING
            ||code==PlaybackState.STATE_FAST_FORWARDING||code==PlaybackState.STATE_REWINDING;
    }
    static boolean hasMediaTitle(MediaMetadata metadata){
        return metadata!=null&&firstNonBlank(metadata.getText(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))!=null;
    }
    static MediaBinding selectCurrentMedia(Collection<MediaBinding> bindings,long nowUptime){
        if(bindings==null||bindings.isEmpty())return null;
        List<MediaBinding> list=new ArrayList<>(bindings);
        boolean[] valid=new boolean[list.size()],playing=new boolean[list.size()];
        long[] active=new long[list.size()];
        for(int i=0;i<list.size();i++){
            MediaBinding binding=list.get(i);
            valid[i]=binding!=null&&binding.item!=null;
            playing[i]=binding!=null&&binding.wasPlaying;
            active[i]=binding==null?0L:binding.lastActiveUptime;
        }
        int best=selectBestMediaIndex(valid,playing,active,nowUptime);
        return best>=0?list.get(best):null;
    }
    public static int selectBestMediaIndex(boolean[] valid,boolean[] playing,long[] lastActiveUptime,long nowUptime){
        if(valid==null||playing==null||lastActiveUptime==null)return -1;
        int count=Math.min(valid.length,Math.min(playing.length,lastActiveUptime.length));
        int best=-1;
        for(int i=0;i<count;i++){
            if(!valid[i])continue;
            if(playing[i]){
                if(best<0||!playing[best]||lastActiveUptime[i]>lastActiveUptime[best])best=i;
            }else if(lastActiveUptime[i]>0&&nowUptime-lastActiveUptime[i]<PAUSED_MEDIA_TIMEOUT_MS){
                if(best<0||(!playing[best]&&lastActiveUptime[i]>lastActiveUptime[best]))best=i;
            }
        }
        return best;
    }
    private final class MediaBinding extends MediaController.Callback {
        final String key;final MediaSession.Token token;final MediaController controller;
        IslandItem item;Bitmap lastArt;long lastActiveUptime;boolean wasPlaying;
        MediaBinding(String key,MediaSession.Token token,MediaController controller){this.key=key;this.token=token;this.controller=controller;}
        @Override public void onPlaybackStateChanged(PlaybackState state){update();requestRefresh();}
        @Override public void onMetadataChanged(MediaMetadata metadata){update();requestRefresh();}
        @Override public void onSessionDestroyed(){lastArt=null;media.remove(token);try{controller.unregisterCallback(this);}catch(RuntimeException ignored){}if(mediaOutput!=null)mediaOutput.unbind(key);requestRefresh();}
        @Override public void onSessionEvent(String event,Bundle extras){update();requestRefresh();}
        @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info){update();requestRefresh();}
        void update(){
            PlaybackState state=controller.getPlaybackState();MediaMetadata metadata=controller.getMetadata();
            boolean playing=isActivePlaybackState(state);
            long now=SystemClock.uptimeMillis();
            if(playing||wasPlaying){
                lastActiveUptime=now;
            }else if(lastActiveUptime==0&&state!=null&&state.getState()==PlaybackState.STATE_PAUSED&&state.getLastPositionUpdateTime()>0){
                long age=SystemClock.elapsedRealtime()-state.getLastPositionUpdateTime();
                if(age>=0&&age<PAUSED_MEDIA_TIMEOUT_MS)lastActiveUptime=now-age;
            }
            wasPlaying=playing;
            if(state==null||state.getState()==PlaybackState.STATE_NONE||state.getState()==PlaybackState.STATE_STOPPED||state.getState()==PlaybackState.STATE_ERROR
                ||!hasMediaTitle(metadata)||"com.sec.android.app.voicenote".equals(controller.getPackageName())){
                item=null;lastArt=null;if(mediaOutput!=null)mediaOutput.unbind(key);return;
            }
            IslandItem next=new IslandItem(key);next.kind=IslandItem.Kind.MEDIA;next.isMedia=true;next.packageName=controller.getPackageName();
            next.contentVersion=++version;
            next.postTime=lastActiveUptime>0?System.currentTimeMillis()-Math.max(0L,now-lastActiveUptime):System.currentTimeMillis();
            next.contentIntent=controller.getSessionActivity();
            configureMedia(next,metadata,state);
            StatusBarNotification mediaSbn=mediaNotifications.get(token);
            if(next.art==null&&mediaSbn!=null&&mediaSbn.getNotification()!=null){
                Bitmap notifArt=bitmapFromIcon(context,mediaSbn.getNotification().getLargeIcon());
                if(notifArt!=null&&!notifArt.isRecycled())next.art=notifArt;
            }
            if(next.art!=null&&!next.art.isRecycled())lastArt=next.art;
            else if(lastArt!=null&&!lastArt.isRecycled())next.art=lastArt;
            decorateApp(context,next);
            long actions=state.getActions();final MediaController.TransportControls controls=controller.getTransportControls();
            if((actions&(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE))!=0)next.mediaPlayPause=()->{
                PlaybackState current=controller.getPlaybackState();if(current==null)return;
                if(isActivePlaybackState(current)){if((current.getActions()&(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE))!=0)controls.pause();}
                else if((current.getActions()&(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PLAY_PAUSE))!=0)controls.play();
            };
            if((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0)next.mediaPrev=controls::skipToPrevious;
            if((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0)next.mediaNext=controls::skipToNext;
            if((actions&PlaybackState.ACTION_SEEK_TO)!=0)next.mediaSeek=position->controls.seekTo(Math.max(0,next.mediaDuration>0?Math.min(next.mediaDuration,position):position));
            Notification.Action[] notifActions=mediaSbn!=null&&mediaSbn.getNotification()!=null?mediaSbn.getNotification().actions:null;
            if(state.getCustomActions()!=null)for(PlaybackState.CustomAction action:state.getCustomActions()){
                if(next.mediaCustom.size()>=2)break;
                Drawable icon=null;
                if(action.getIcon()!=0){
                    try{icon=Icon.createWithResource(next.packageName,action.getIcon()).loadDrawable(context);}catch(Exception ignored){}
                    if(icon==null)try{icon=context.createPackageContext(next.packageName,0).getDrawable(action.getIcon());}catch(Exception ignored){}
                }
                final PendingIntent matchedIntent=matchMediaNotificationAction(notifActions,action);
                final PlaybackState.CustomAction customAction=action;
                Runnable dispatch=()->{
                    boolean sent=false;
                    if(matchedIntent!=null){
                        try{matchedIntent.send(context,0,null,null,null,null,activityOptions());sent=true;}
                        catch(Exception ignored){}
                    }
                    if(!sent){
                        try{controls.sendCustomAction(customAction.getAction(),customAction.getExtras());}
                        catch(Exception ignored){
                            try{controls.sendCustomAction(customAction,customAction.getExtras());}catch(Exception ignored2){}
                        }
                    }
                    main.postDelayed(()->{
                        if(!closed&&media.get(token)==MediaBinding.this){update();requestRefresh();}
                    },250L);
                };
                next.mediaCustom.add(new IslandItem.Action(action.getName(),icon,null,dispatch,false));
            }
            if(next.art!=null){next.icon=new BitmapDrawable(context.getResources(),next.art);next.iconIsImage=true;}
            if(mediaOutput!=null)mediaOutput.bind(next,controller,context);
            item=next;
        }
    }
    private static PendingIntent matchMediaNotificationAction(Notification.Action[] actions,PlaybackState.CustomAction custom){
        if(actions==null||custom==null)return null;
        CharSequence name=custom.getName();
        String target=name==null?"":name.toString().trim();
        for(Notification.Action a:actions){
            if(a==null||a.actionIntent==null||a.title==null)continue;
            if(!target.isEmpty()&&target.equalsIgnoreCase(a.title.toString().trim()))return a.actionIntent;
        }
        int resId=custom.getIcon();
        if(resId!=0){
            for(Notification.Action a:actions){
                if(a==null||a.actionIntent==null||a.getIcon()==null)continue;
                try{if(a.getIcon().getType()==Icon.TYPE_RESOURCE&&a.getIcon().getResId()==resId)return a.actionIntent;}catch(Exception ignored){}
            }
        }
        return null;
    }
    private static Bitmap bitmapFromIcon(Context context,Icon icon){
        if(context==null||icon==null)return null;
        try{
            Drawable d=icon.loadDrawable(context);
            if(d instanceof BitmapDrawable)return ((BitmapDrawable)d).getBitmap();
        }catch(Exception ignored){}
        return null;
    }
    /** Metadata parser is independent from session ownership and never invokes controls. */
    public static void configureMedia(IslandItem item,MediaMetadata metadata,PlaybackState state){
        if(state==null)return;item.playing=isActivePlaybackState(state);
        item.mediaPosition=state.getPosition();item.mediaPositionUpdateTime=state.getLastPositionUpdateTime();item.mediaSpeed=state.getPlaybackSpeed();
        item.trailing=IslandItem.Trailing.WAVEFORM;item.accent=0xffffffff;
        if(metadata!=null){
            item.title=firstNonBlank(metadata.getText(MediaMetadata.METADATA_KEY_TITLE),metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            item.subtitle=firstNonBlank(metadata.getText(MediaMetadata.METADATA_KEY_ARTIST),metadata.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getText(MediaMetadata.METADATA_KEY_AUTHOR),metadata.getText(MediaMetadata.METADATA_KEY_WRITER),metadata.getText(MediaMetadata.METADATA_KEY_COMPOSER));
            item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);if(item.art==null)item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if(item.art==null)item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            item.mediaDuration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        if(item.title==null)item.title="Media";
    }
    public static boolean isSyntheticMediaOngoing(StatusBarNotification sbn,Object data){
        if(data!=null&&Boolean.TRUE.equals(read(data,"mIsMediaOngoingData")))
            return !"com.sec.android.app.voicenote".equals(read(data,"mPackageName"));
        if(sbn!=null&&sbn.getNotification()!=null&&"com.android.systemui".equals(sbn.getPackageName())){
            Notification n=sbn.getNotification();
            Bundle extras=n.extras;
            String channel=n.getChannelId();
            CharSequence title=extras==null?null:extras.getCharSequence(Notification.EXTRA_TITLE);
            if("MediaOngoingActivity".equals(channel)&&(sbn.getId()==12030705||(title!=null&&"MediaOngoingActivity".contentEquals(title))))
                return true;
        }
        return false;
    }
    private static String resolveOngoingMediaOwner(Object value){
        Object pkg=read(value,"mPackageName");
        if(pkg instanceof String&&!((String)pkg).isEmpty()&&!"com.android.systemui".equals(pkg))return (String)pkg;
        PendingIntent intent=capability(read(value,"mPendingIntent"));
        if(intent!=null){
            String creator=intent.getCreatorPackage();
            if(creator!=null&&!creator.isEmpty()&&!"com.android.systemui".equals(creator))return creator;
        }
        Object info=read(value,"mMediaOngoingActivityInfo");
        PendingIntent click=capability(read(info,"clickIntent"));
        if(click!=null){
            String creator=click.getCreatorPackage();
            if(creator!=null&&!creator.isEmpty()&&!"com.android.systemui".equals(creator))return creator;
        }
        return null;
    }
    /** Parse actual framework/One UI notification fields; only supplied capabilities become actions. */
    public static IslandItem notificationItem(Context context,StatusBarNotification sbn,Object data){
        if(sbn==null)return null;
        Notification n=sbn.getNotification();if(n==null)return null;
        if(isSyntheticMediaOngoing(sbn,data))return null;
        Bundle extras=n.extras==null?new Bundle():n.extras;
        String pkg=sbn.getPackageName();
        if(extras.containsKey(Notification.EXTRA_MEDIA_SESSION)&&!"com.sec.android.app.voicenote".equals(pkg))return null;
        int style=integer(extras.get("android.ongoingActivityNoti.style"),0);
        boolean focused=extras.containsKey("miui.focus.param")||extras.containsKey("miui.focus.param_v3");
        boolean promoted=extras.getBoolean("android.requestPromotedOngoing",false)||Boolean.TRUE.equals(read(read(data,"mNotificationEntry"),"isPromotedState"));
        String title=String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE,""));
        String semantic=(pkg+" "+sbn.getTag()+" "+n.getChannelId()+" "+title).toLowerCase(Locale.ROOT);
        boolean smartCaptureRecording="com.samsung.android.app.smartcapture".equals(pkg)
            &&(n.flags&Notification.FLAG_ONGOING_EVENT)!=0
            &&(semantic.contains("recording_screen")||semantic.contains("screen_recorder")
                ||semantic.contains("stop recording")||"CHANNEL_ID_RECORDING_SCREEN".equals(n.getChannelId()));
        boolean recording="com.sec.android.app.voicenote".equals(pkg)||"com.miui.screenrecorder".equals(pkg)
            ||smartCaptureRecording
            ||("com.android.systemui".equals(pkg)&&(semantic.contains("screenrecord")||semantic.contains("screen record")));
        boolean call=Notification.CATEGORY_CALL.equals(n.category)||extras.containsKey("android.callType");
        boolean incomingCall=isIncomingCallNotification(n,extras,data);
        // Incoming and screening calls remain 100% native Samsung One UI; only active ongoing calls enter HyperBar.
        if(call&&(incomingCall||extras.getInt("android.callType",0)==1||extras.getInt("android.callType",0)==3
            ||(n.flags&Notification.FLAG_ONGOING_EVENT)==0))return null;
        boolean navigation=Notification.CATEGORY_NAVIGATION.equals(n.category);
        Object nativeClock=read(data,"mChronometerView");if(!(nativeClock instanceof android.widget.RemoteViews))nativeClock=extras.get(HyperOsChronometerAdapter.EXTRA_VIEW);
        boolean timer=(pkg.contains("clock")&&(semantic.contains("timer")||extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN,false)
            ||extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER,false)||nativeClock instanceof android.widget.RemoteViews))||Notification.CATEGORY_ALARM.equals(n.category);
        boolean media=extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        boolean sourceAlarm=HyperOsActionSemantics.CLOCK.equals(pkg)&&Notification.CATEGORY_ALARM.equals(n.category)
            &&(HyperOsAlarmData.exactTime(extras,1)>0||HyperOsAlarmData.exactTime(extras,2)>0);
        if(data==null&&!focused&&!promoted&&style<1&&!sourceAlarm&&!((n.flags&Notification.FLAG_ONGOING_EVENT)!=0&&(recording||call||navigation||timer||media)))return null;
        if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0&&!sourceAlarm)return null;
        IslandItem item=new IslandItem("hyperos:notification:"+sbn.getKey());item.packageName=pkg;item.userId=sbn.getUserId();
        item.kind=call?IslandItem.Kind.CALL:navigation?IslandItem.Kind.NAVIGATION:recording?IslandItem.Kind.RECORDING:timer?IslandItem.Kind.TIMER:media?IslandItem.Kind.MEDIA:IslandItem.Kind.LIVE;
        item.contentIntent=capability(read(data,"mPendingIntent"));if(item.contentIntent==null)item.contentIntent=n.contentIntent;
        item.postTime=sbn.getPostTime();item.pillColor=0xff000000;item.accent=0xffffffff;item.iconTint=0xffffffff;item.trailingColor=0xffffffff;
        item.title=first(text(read(data,"mNowbarPrimaryInfo")),text(read(data,"mPrimaryInfo")),extras.getCharSequence(Notification.EXTRA_TITLE));
        item.subtitle=first(text(read(data,"mNowbarSecondaryInfo")),text(read(data,"mSecondaryInfo")),extras.getCharSequence(Notification.EXTRA_TEXT));
        item.overline=recording?"Recording":timer?"Timer":call?"Call":navigation?"Navigation":null;
        item.detail=first(extras.getCharSequence(Notification.EXTRA_BIG_TEXT),item.subtitle);
        if(context!=null){
            decorateApp(context,item);item.icon=load(context,read(data,"mChipIcon"));if(item.icon==null)item.icon=load(context,n.getSmallIcon());
            item.cardIcon=load(context,read(data,"mCardIcon"));item.cardImage=load(context,n.getLargeIcon());item.cardImageIsImage=item.cardImage!=null;
            item.leftImage=load(context,read(data,"mFirstIcon"));item.rightImage=load(context,read(data,"mSecondIcon"));
            if(item.icon==null){item.icon=item.appIcon;item.iconIsImage=true;}
        }
        item.trailingText=first(extras.getCharSequence("android.shortCriticalText"),text(read(data,"mExpandedChipText")));
        item.trailing=item.trailingText==null?IslandItem.Trailing.NONE:IslandItem.Trailing.TEXT;
        long nativeWhen=number(read(data,"mWhen"),n.when);
        if((extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER,false)||timer&&read(data,"mChronometerView") instanceof android.widget.RemoteViews)&&nativeWhen>0){item.trailing=IslandItem.Trailing.CHRONOMETER;item.chronoBase=elapsedTimestamp(nativeWhen);item.chronoCountDown=extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN,false);}
        CharSequence clockTag=first(text(read(data,"mChronometerTag")),extras.getCharSequence(HyperOsChronometerAdapter.EXTRA_TAG));
        if(nativeClock instanceof android.widget.RemoteViews)HyperOsChronometerAdapter.apply(context,item,(android.widget.RemoteViews)nativeClock,clockTag);
        HyperOsClockNotificationAdapter.configure(context,n,item);
        int progress=extras.getInt(Notification.EXTRA_PROGRESS,integer(read(data,"mProgress"),-1));
        int max=extras.getInt(Notification.EXTRA_PROGRESS_MAX,integer(read(data,"mProgressMax"),0));
        if(progress>=0&&max>0){item.progress=Math.max(0,Math.min(1,progress/(float)max));if(item.trailing==IslandItem.Trailing.NONE)item.trailing=IslandItem.Trailing.PROGRESS_RING;}
        item.metrics=metrics(extras.get("android.metrics"));item.criticalMetric=extras.getInt("android.metrics.criticalIndex",-1);
        if(item.metrics!=null&&item.criticalMetric>=0&&item.criticalMetric<item.metrics.size()){
            IslandItem.Metric metric=item.metrics.get(item.criticalMetric);
            if(item.trailing==IslandItem.Trailing.NONE&&metric.type==IslandItem.Metric.TIME){item.trailing=IslandItem.Trailing.CHRONOMETER;item.chronoBase=metric.zeroTime;item.chronoCountDown=metric.countDown;item.pausedMs=metric.pausedMs;}
        }
        Collection<Notification.Action> supplied=new ArrayList<>();Object nativeActions=read(data,"mActions");
        if(nativeActions instanceof Collection)for(Object value:(Collection<?>)nativeActions)if(value instanceof Notification.Action)supplied.add((Notification.Action)value);
        if(supplied.isEmpty()&&n.actions!=null)Collections.addAll(supplied,n.actions);
        for(Notification.Action action:supplied){
            if(action==null||action.actionIntent==null||action.isContextual())continue;
            int intent=action.getSemanticAction();boolean destructive=intent==Notification.Action.SEMANTIC_ACTION_DELETE||intent==Notification.Action.SEMANTIC_ACTION_ARCHIVE;
            String iconName=HyperOsActionSemantics.resourceName(context,action.getIcon(),pkg);
            if(HyperOsActionSemantics.unavailable(pkg,iconName))continue;
            int glyph=HyperOsActionSemantics.glyph(pkg,iconName,action.getExtras().getString("icon_name"));
            glyph=HyperOsClockNotificationAdapter.actionGlyph(context,item,action.title,glyph);
            IslandItem.Action capability=new IslandItem.Action(action.title,context==null?null:load(context,action.getIcon()),action.actionIntent,null,destructive||glyph==6||glyph==9||glyph==12||glyph==13);
            capability.glyph=glyph;item.actions.add(capability);
        }
        if(recording&&"com.sec.android.app.voicenote".equals(pkg))item.recorder=true;
        if(smartCaptureRecording){
            item.recorder=true;
            if(item.title==null||String.valueOf(item.title).toLowerCase(Locale.ROOT).contains("tap here")){
                item.title="Screen recording";
                item.subtitle="Tap to stop recording";
            }
            if(!item.hasChronometer()&&nativeWhen>0){
                item.trailing=IslandItem.Trailing.CHRONOMETER;
                item.chronoBase=elapsedTimestamp(nativeWhen);
                item.chronoCountDown=false;
            }
            if(item.actions.isEmpty()&&item.contentIntent!=null){
                IslandItem.Action stopAction=new IslandItem.Action("Stop",null,item.contentIntent,null,true);
                stopAction.glyph=6;
                item.actions.add(stopAction);
            }
        }
        if(call){
            if(incomingCall||hasIncomingCallActions(item.actions))return null;
            if(!item.hasChronometer()&&nativeWhen>0){
                item.trailing=IslandItem.Trailing.CHRONOMETER;
                item.chronoBase=elapsedTimestamp(nativeWhen);
                item.chronoCountDown=false;
            }
            configureActiveCallActions(context,n,item);
        }
        HyperOsClockNotificationAdapter.sourceControls(n,item);
        HyperOsClockNotificationAdapter.orderActions(item);
        return item;
    }
    public static boolean isIncomingCallNotification(Notification notification,Bundle extras,Object data){
        if(notification==null)return false;
        Bundle b=extras!=null?extras:notification.extras;
        if(b!=null){
            if(b.getInt("android.callType",0)==1)return true;
            if(b.get("android.answerIntent") instanceof PendingIntent||b.get("android.declineIntent") instanceof PendingIntent)return true;
        }
        boolean isCall=Notification.CATEGORY_CALL.equals(notification.category)||(b!=null&&b.containsKey("android.callType"));
        if(!isCall)return false;
        List<Notification.Action> actions=new ArrayList<>();
        Object nativeActions=read(data,"mActions");
        if(nativeActions instanceof Collection)for(Object value:(Collection<?>)nativeActions)if(value instanceof Notification.Action)actions.add((Notification.Action)value);
        if(actions.isEmpty()&&notification.actions!=null)Collections.addAll(actions,notification.actions);
        boolean hasAnswer=false,hasDecline=false;
        for(Notification.Action action:actions){
            if(action==null||action.actionIntent==null)continue;
            if(isAnswerTitle(action.title))hasAnswer=true;
            else if(isHangupTitle(action.title))hasDecline=true;
        }
        return hasAnswer&&hasDecline;
    }
    private static boolean hasIncomingCallActions(List<IslandItem.Action> actions){
        if(actions==null)return false;
        boolean hasAnswer=false,hasDecline=false;
        for(IslandItem.Action action:actions){
            if(action==null||(action.intent==null&&action.runnable==null))continue;
            if(isAnswerTitle(action.title))hasAnswer=true;
            else if(action.destructive||action.glyph==9||isHangupTitle(action.title))hasDecline=true;
        }
        return hasAnswer&&hasDecline;
    }
    public static void configureIncomingCallActions(Context context,Notification notification,Bundle extras,IslandItem item){
        if(item==null||item.kind!=IslandItem.Kind.CALL)return;
        Bundle b=extras!=null?extras:(notification!=null?notification.extras:null);
        PendingIntent declineIntent=b!=null&&b.get("android.declineIntent") instanceof PendingIntent?(PendingIntent)b.get("android.declineIntent"):null;
        PendingIntent answerIntent=b!=null&&b.get("android.answerIntent") instanceof PendingIntent?(PendingIntent)b.get("android.answerIntent"):null;
        CharSequence declineTitle="Decline",answerTitle="Answer";
        for(IslandItem.Action action:item.actions){
            if(action==null||action.intent==null)continue;
            if(isAnswerTitle(action.title)){
                if(answerIntent==null)answerIntent=action.intent;
                if(action.title!=null&&action.title.length()>0)answerTitle=action.title;
            }else if(action.destructive||action.glyph==9||isHangupTitle(action.title)){
                if(declineIntent==null)declineIntent=action.intent;
                if(action.title!=null&&action.title.length()>0)declineTitle=action.title;
            }
        }
        boolean telecom=item.packageName==null||TELECOM_CALL_PACKAGES.contains(item.packageName);
        final Context appContext=context==null?null:context.getApplicationContext()!=null?context.getApplicationContext():context;
        final PendingIntent finalDecline=declineIntent,finalAnswer=answerIntent;
        Runnable declineRunnable=finalDecline==null&&telecom&&appContext!=null?()->{
            try{TelecomManager tm=appContext.getSystemService(TelecomManager.class);if(tm!=null)tm.endCall();}catch(Throwable ignored){}
        }:null;
        Runnable answerRunnable=finalAnswer==null&&telecom&&appContext!=null?()->{
            try{TelecomManager tm=appContext.getSystemService(TelecomManager.class);if(tm!=null)tm.acceptRingingCall();}catch(Throwable ignored){}
        }:null;
        List<IslandItem.Action> rebuilt=new ArrayList<>(2);
        IslandItem.Action decline=new IslandItem.Action(declineTitle,null,finalDecline,declineRunnable,true);
        decline.glyph=9;
        IslandItem.Action answer=new IslandItem.Action(answerTitle,null,finalAnswer,answerRunnable,false);
        answer.glyph=11;
        rebuilt.add(decline);
        rebuilt.add(answer);
        item.actions.clear();
        item.actions.addAll(rebuilt);
    }
    private static boolean isAnswerTitle(CharSequence title){
        if(title==null)return false;String s=title.toString().toLowerCase(Locale.ROOT);
        return s.contains("answer")||s.contains("accept")||s.contains("pick up")||s.contains("pickup")
            ||s.contains("ответить")||s.contains("принять");
    }
    public static void configureActiveCallActions(Context context,Notification notification,IslandItem item){
        if(item==null||item.kind!=IslandItem.Kind.CALL||HyperOsIncomingCalls.isIncoming(item))return;
        PendingIntent hangupIntent=null,speakerIntent=null;
        for(IslandItem.Action action:item.actions){
            if(action==null||action.intent==null)continue;
            if(action.glyph==9||action.destructive||isHangupTitle(action.title)){
                if(hangupIntent==null)hangupIntent=action.intent;
            }else if(action.glyph==10||isSpeakerTitle(action.title)){
                if(speakerIntent==null)speakerIntent=action.intent;
            }
        }
        if(hangupIntent==null&&notification!=null&&notification.extras!=null){
            Object rawHangup=notification.extras.get("android.hangUpIntent");
            if(rawHangup instanceof PendingIntent)hangupIntent=(PendingIntent)rawHangup;
        }
        boolean telecom=item.packageName==null||TELECOM_CALL_PACKAGES.contains(item.packageName);
        if(hangupIntent==null&&!telecom){
            for(IslandItem.Action action:item.actions){
                if(action!=null&&action.intent!=null&&action.intent!=speakerIntent&&!isMuteTitle(action.title)){
                    hangupIntent=action.intent;break;
                }
            }
        }
        boolean speakerOn=false;
        AudioManager am=context==null?null:context.getSystemService(AudioManager.class);
        if(am!=null){
            try{
                AudioDeviceInfo device=am.getCommunicationDevice();
                speakerOn=device!=null&&device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            }catch(Throwable ignored){}
        }
        List<IslandItem.Action> rebuilt=new ArrayList<>();
        if(telecom||speakerIntent!=null){
            final PendingIntent spIntent=speakerIntent;
            final Context appContext=context==null?null:context.getApplicationContext()!=null?context.getApplicationContext():context;
            IslandItem.Action speaker=new IslandItem.Action("Speaker",null,null,()->{
                if(telecom&&appContext!=null){
                    try{appContext.sendBroadcast(new Intent("com.samsung.rcs.intent.action.SPEAKER_TOGGLE").setPackage("com.samsung.android.incallui"));}
                    catch(Throwable ignored){}
                } else if(spIntent!=null&&appContext!=null){
                    try{spIntent.send(appContext,0,null,null,null,null,activityOptions());}catch(Exception ignored){}
                }
                HyperIslandController ctrl=get();
                if(ctrl!=null)ctrl.main.postDelayed(ctrl::requestRefresh,400L);
            },false);
            speaker.glyph=10;speaker.active=speakerOn;rebuilt.add(speaker);
        }
        if(telecom||hangupIntent!=null){
            final PendingIntent endIntent=hangupIntent;
            final Context appContext=context==null?null:context.getApplicationContext()!=null?context.getApplicationContext():context;
            IslandItem.Action hangup=new IslandItem.Action("End call",null,null,()->{
                if(telecom&&appContext!=null){
                    try{TelecomManager tm=appContext.getSystemService(TelecomManager.class);if(tm!=null&&tm.endCall())return;}
                    catch(Throwable ignored){}
                }
                if(endIntent!=null&&appContext!=null){
                    try{endIntent.send(appContext,0,null,null,null,null,activityOptions());}catch(Exception ignored){}
                }
            },true);
            hangup.glyph=9;rebuilt.add(hangup);
        }
        if(!rebuilt.isEmpty()){item.actions.clear();item.actions.addAll(rebuilt);}
    }
    private static boolean isSpeakerTitle(CharSequence title){
        if(title==null)return false;String s=title.toString().toLowerCase(Locale.ROOT);
        return s.contains("speaker")||s.contains("loudspeaker")||s.contains("динамик")||s.contains("громк");
    }
    private static boolean isHangupTitle(CharSequence title){
        if(title==null)return false;String s=title.toString().toLowerCase(Locale.ROOT);
        return s.contains("hang up")||s.contains("hangup")||s.contains("end")||s.contains("decline")||s.contains("reject")
            ||s.contains("disconnect")||s.contains("заверш")||s.contains("сброс")||s.contains("отклон");
    }
    private static boolean isMuteTitle(CharSequence title){
        if(title==null)return false;String s=title.toString().toLowerCase(Locale.ROOT);
        return s.contains("mute")||s.contains("микрофон")||s.contains("звук");
    }
    private static List<IslandItem.Metric> metrics(Object raw){
        if(!(raw instanceof List))return null;List<IslandItem.Metric> result=new ArrayList<>();
        for(Object entry:(List<?>)raw){if(!(entry instanceof Bundle))continue;Bundle data=(Bundle)entry,value=data.getBundle("value");if(value==null)continue;
            IslandItem.Metric metric=new IslandItem.Metric();metric.label=data.getCharSequence("label");metric.semantic=data.getInt("semanticStyle",0);metric.type=value.getInt("_type",0);
            if(metric.type==IslandItem.Metric.TIME){
                metric.countDown=value.getBoolean("countDown",false);metric.format=value.getInt("format",2);
                if(value.containsKey("pausedDuration"))metric.pausedMs=number(value.get("pausedDuration"),-1);
                else {Object zero=value.get("zeroTime");if(!(zero instanceof Number))continue;metric.zeroTime=elapsedTimestamp(((Number)zero).longValue());}
            }else if(metric.type==IslandItem.Metric.TEXT||metric.type==IslandItem.Metric.INT||metric.type==IslandItem.Metric.FLOAT){
                Object fixed=value.get("value");if(fixed==null)continue;String unit=String.valueOf(value.getCharSequence("unit",""));metric.fixed=String.valueOf(fixed)+(unit.isEmpty()?"":" "+unit);
            }else continue;result.add(metric);
        }return result.isEmpty()?null:result;
    }
    private static void decorateApp(Context context,IslandItem item){
        try{android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(item.packageName,0);item.appName=context.getPackageManager().getApplicationLabel(info);item.appIcon=context.getPackageManager().getApplicationIcon(info);}
        catch(Exception ignored){}if(item.icon==null&&!item.isMedia){item.icon=item.appIcon;item.iconIsImage=true;}
    }
    private static Drawable load(Context context,Object value){try{return value instanceof Icon?((Icon)value).loadDrawable(context):value instanceof Drawable?(Drawable)value:null;}catch(RuntimeException ignored){return null;}}
    private static PendingIntent capability(Object value){return value instanceof PendingIntent?(PendingIntent)value:null;}
    private static CharSequence text(Object value){return value instanceof CharSequence?(CharSequence)value:null;}
    private static CharSequence first(CharSequence... values){for(CharSequence value:values)if(value!=null&&value.length()>0)return value;return null;}
    private static CharSequence firstNonBlank(CharSequence... values){
        for(CharSequence value:values){
            if(value==null)continue;
            if(!value.toString().trim().isEmpty())return value;
        }
        return null;
    }
    private static int integer(Object value,int fallback){if(value instanceof Number)return ((Number)value).intValue();try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return fallback;}}
    private static long number(Object value,long fallback){return value instanceof Number?((Number)value).longValue():fallback;}
    private static long elapsedTimestamp(long timestamp){return timestamp>100000000000L?timestamp-System.currentTimeMillis()+SystemClock.elapsedRealtime():timestamp;}
    private static Object read(Object owner,String name){
        if(owner==null)return null;
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception ignored){}
        try{Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);}catch(Exception ignored){return null;}
    }
    private void onMain(Runnable action){if(Looper.myLooper()==main.getLooper())action.run();else main.post(action);}
    @Override public void close(){onMain(()->{
        if(closed)return;closed=true;main.removeCallbacks(refresh);main.removeCallbacks(flyInReveal);main.removeCallbacks(pausedMediaExpiry);main.removeCallbacks(clearOptimisticForeground);HyperOsIncomingCalls.reportHidden();
        previouslyHiddenByForeground.clear();deferredFlyIn.clear();pendingAbsorbKeys.clear();activeExitPackages=Collections.emptySet();oaController=null;lastExitSignature=null;
        if(window!=null)window.detach();if(bluetooth!=null)bluetooth.close();
        if(torch!=null)torch.close();if(charging!=null)charging.close();
        if(presentation!=null){presentation.close();presentation=null;}
        if(mediaOutput!=null)mediaOutput.close();
        if(audioManager!=null&&commDeviceListener!=null)try{audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener);}catch(RuntimeException ignored){}
        commDeviceListener=null;audioManager=null;
        if(sessions!=null&&mediaRegistered)try{sessions.removeOnActiveSessionsChangedListener(sessionListener);}catch(RuntimeException ignored){}
        for(MediaBinding binding:media.values())try{binding.controller.unregisterCallback(binding);}catch(RuntimeException ignored){}media.clear();
        if(receiverRegistered)try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}
        if(settingsRegistered)try{context.getContentResolver().unregisterContentObserver(settingsObserver);}catch(RuntimeException ignored){}
        notifications.clear();ongoingNotifications.clear();notificationTokens.clear();mediaNotifications.clear();notificationNativeKeys.clear();nativeKeyAliases.clear();
        visible=Collections.emptyList();owned=Collections.emptyList();foregroundHidden=Collections.emptyList();statusBar=null;changedPresentation();
    });}
}
