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
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
    }
    private static final String TAG="HyperIslandController";
    private static final Uri SETTINGS=Uri.parse("content://com.alpha269.hyperisland.settings");
    private static HyperIslandController instance;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,IslandItem> notifications=new LinkedHashMap<>();
    private final Map<String,MediaBinding> media=new LinkedHashMap<>();
    private final Set<String> minimized=new LinkedHashSet<>();
    private final Map<String,Long> restored=new HashMap<>();
    private List<IslandItem> visible=Collections.emptyList();
    private HyperOsPresentationQueue presentation;
    private boolean presentationFailed;
    private List<StatusBarNotification> lastNotifications=Collections.emptyList();
    private Map<String,Object> ongoing=new HashMap<>();
    private HyperOsBluetoothSource bluetooth;
    private HyperOsTorchSource torch;
    private HyperOsMediaOutput mediaOutput;
    private MediaSessionManager sessions;
    private WindowAdapter window;
    private View statusBar;
    private Runnable presentationChanged;
    private boolean started,closed,refreshPosted,dozing,screenOff,receiverRegistered,mediaRegistered,settingsRegistered;
    private boolean enabled=true,incomingEnabled=true,bluetoothEnabled=true,keyguardLocked;
    private int barState,windowState,hint;
    private float shade;
    private String pinned;
    private long version;
    private IslandItem screenRecording;
    private Runnable screenStop;
    private long screenRecordingStart;
    private boolean screenRecordingKnown;
    private final Runnable refresh=this::refreshNow;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener=this::updateSessions;
    private final ContentObserver settingsObserver=new ContentObserver(main){@Override public void onChange(boolean selfChange){readSettings();}};
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            String action=intent.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(action))screenOff=true;
            else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_USER_PRESENT.equals(action))screenOff=false;
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
        HyperOsIncomingCalls.initialize(context,this::requestRefresh);
        bluetooth=new HyperOsBluetoothSource(context,this::requestRefresh);bluetooth.start();
        torch=new HyperOsTorchSource(context,main,this::requestRefresh);torch.start();
        mediaOutput=new HyperOsMediaOutput(context,this::onMediaOutputChanged);mediaOutput.start();
        try{
            sessions=context.getSystemService(MediaSessionManager.class);
            if(sessions!=null){sessions.addOnActiveSessionsChangedListener(sessionListener,null,main);mediaRegistered=true;updateSessions(sessions.getActiveSessions(null));}
        }catch(RuntimeException failure){Log.w(TAG,"Media-session observation unavailable",failure);}
        IntentFilter filter=new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        try{context.registerReceiver(receiver,filter,null,main,Context.RECEIVER_NOT_EXPORTED);receiverRegistered=true;}
        catch(RuntimeException failure){Log.w(TAG,"Screen observation unavailable",failure);}
        requestRefresh();
    }
    public void setWindowAdapter(WindowAdapter adapter){onMain(()->{
        if(window!=null)window.detach();window=adapter;
        if(window!=null&&statusBar!=null)window.attach(context,this,statusBar);requestRefresh();
    });}
    public void setPresentationChanged(Runnable callback){onMain(()->presentationChanged=callback);}
    public void onStatusBarView(View bar){onMain(()->{
        if(closed||bar==null)return;
        if(statusBar!=bar){if(window!=null)window.detach();statusBar=bar;if(window!=null)window.attach(context,this,bar);}
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
            Map<String,Object> next=new HashMap<>();List<StatusBarNotification> all=new ArrayList<>(lastNotifications);
            Set<String> keys=new LinkedHashSet<>();for(StatusBarNotification n:all)keys.add(n.getKey());
            for(Object value:copy){
                Object key=read(value,"mNotiID");if(key instanceof String)next.put((String)key,value);
                Object entry=read(value,"mNotificationEntry"),raw=read(entry,"mSbn");
                if(raw instanceof StatusBarNotification){StatusBarNotification n=(StatusBarNotification)raw;if(keys.add(n.getKey()))all.add(n);next.put(n.getKey(),value);}
            }
            ongoing=next;rebuildNotifications(all);requestRefresh();
        });
    }
    private void rebuildNotifications(){
        rebuildNotifications(lastNotifications);
    }
    private void rebuildNotifications(List<StatusBarNotification> values){
        Set<String> old=new LinkedHashSet<>(notifications.keySet());notifications.clear();
        for(StatusBarNotification sbn:values){
            if(sbn==null)continue;HyperOsIncomingCalls.onNotificationPosted(sbn);
            try{
                IslandItem item=notificationItem(context,sbn,ongoing.get(sbn.getKey()));
                if(item==null)continue;item.contentVersion=++version;notifications.put(item.key,item);old.remove(item.key);
                Bundle extras=sbn.getNotification().extras;if(extras==null)continue;
                String focus=extras.getString("miui.focus.param_v3");if(focus==null)focus=extras.getString("miui.focus.param");
                if(focus!=null)try{JSONObject full=new JSONObject(focus);Bundle pictures=extras.getBundle("miui.focus.pics");
                    HyperOsCompactFactory.register(item.key,full,pictures);
                    HyperOsCardFactory.registerTemplate(item.key,full,pictures,extras.getBundle("miui.focus.actions"));}
                    catch(Exception failure){Log.w(TAG,"Invalid focus payload",failure);}
            }catch(RuntimeException failure){Log.w(TAG,"Notification ignored",failure);}
        }
        for(String key:old){HyperOsCardFactory.unregisterTemplate(key);HyperOsCompactFactory.unregister(key);}
    }
    public void requestRefresh(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::requestRefresh);return;}
        if(closed||refreshPosted)return;refreshPosted=true;main.post(refresh);
    }
    private void readSettings(){try{Bundle values=context.getContentResolver().call(SETTINGS,"values",null,null);if(values!=null)onSettings(values);}
        catch(RuntimeException failure){Log.w(TAG,"Settings read unavailable",failure);}}
    public void onSettings(Bundle values){if(values==null)return;final Bundle copy=new Bundle(values);onMain(()->{
        enabled=copy.getBoolean("enabled",true);incomingEnabled=copy.getBoolean("incoming_calls",true);bluetoothEnabled=copy.getBoolean("bluetooth",true);
        if(!enabled||!incomingEnabled)HyperOsIncomingCalls.reportHidden();requestRefresh();
    });}
    /** Accepts actual epoch or elapsed start time; presentation uses elapsedRealtime consistently. */
    public void onScreenRecording(boolean recording,long startEpoch,Runnable stop){onMain(()->{
        screenRecordingKnown=true;
        if(!recording){if(screenRecording!=null){screenRecording=null;screenStop=null;screenRecordingStart=0;requestRefresh();}return;}
        long sourceStart=startEpoch>0?startEpoch:screenRecording!=null?screenRecordingStart:SystemClock.elapsedRealtime();
        if(screenRecording!=null&&screenRecordingStart==sourceStart&&screenStop==stop)return;
        long base=elapsedTimestamp(sourceStart);
        IslandItem item=new IslandItem("hyperos:screenrecord");item.kind=IslandItem.Kind.RECORDING;item.packageName="com.android.systemui";
        item.title="Screen recording";item.subtitle="Recording your screen";item.overline="Screen recording";
        item.trailing=IslandItem.Trailing.CHRONOMETER;item.chronoBase=base;item.postTime=sourceStart>100000000000L?sourceStart:System.currentTimeMillis()-(SystemClock.elapsedRealtime()-base);item.contentVersion=++version;
        item.accent=0xffffffff;item.pillColor=0xff000000;
        if(stop!=null)item.actions.add(new IslandItem.Action("Stop",null,null,stop,true));
        screenRecording=item;screenStop=stop;screenRecordingStart=sourceStart;requestRefresh();
    });}
    private void refreshNow(){
        refreshPosted=false;if(closed)return;
        updateKeyguard();
        long now=SystemClock.uptimeMillis();List<IslandItem> all=new ArrayList<>(notifications.values());
        if(screenRecordingKnown)all.removeIf(item->item.kind==IslandItem.Kind.RECORDING&&"com.android.systemui".equals(item.packageName));
        if(screenRecording!=null){
            for(IslandItem notification:notifications.values())if(notification.kind==IslandItem.Kind.RECORDING&&"com.android.systemui".equals(notification.packageName)){
                screenRecording.icon=notification.icon;screenRecording.appIcon=notification.appIcon;screenRecording.contentIntent=notification.contentIntent;
                if(screenRecording.actions.isEmpty())screenRecording.actions.addAll(notification.actions);break;
            }
            all.add(screenRecording);
        }
        Set<String> mediaPackages=new LinkedHashSet<>();
        for(MediaBinding binding:media.values())if(binding.item!=null){all.add(binding.item);mediaPackages.add(binding.item.packageName);}
        all.removeIf(item->!item.isMedia&&item.kind==IslandItem.Kind.MEDIA&&mediaPackages.contains(item.packageName));
        IslandItem incoming=incomingEnabled?HyperOsIncomingCalls.currentItem():null;
        if(incoming!=null){all.removeIf(item->item.kind==IslandItem.Kind.CALL&&incoming.packageName.equals(item.packageName));all.add(incoming);}
        if(bluetooth!=null&&bluetoothEnabled)bluetooth.appendTo(all);
        if(torch!=null) {
            if(torch.available())all.removeIf(item->HyperOsTorchSource.nativeNotificationKey(item.key));
            torch.appendTo(all,window!=null&&window.isExpanded());
        }
        all.removeIf(item->item.expiresAtUptime>0&&item.expiresAtUptime<=now);
        Set<String> present=new LinkedHashSet<>();for(IslandItem item:all)present.add(item.key);
        minimized.retainAll(present);restored.keySet().retainAll(present);
        if(pinned!=null&&!present.contains(pinned))pinned=null;
        all.sort((a,b)->{
            if(a.kind==IslandItem.Kind.CALL&&b.kind!=IslandItem.Kind.CALL)return -1;
            if(b.kind==IslandItem.Kind.CALL&&a.kind!=IslandItem.Kind.CALL)return 1;
            if(a.key.equals(pinned)!=b.key.equals(pinned))return a.key.equals(pinned)?-1:1;
            int boost=Long.compare(restored.getOrDefault(b.key,0L),restored.getOrDefault(a.key,0L));if(boost!=0)return boost;
            int priority=Integer.compare(b.priority(),a.priority());return priority!=0?priority:Long.compare(b.postTime,a.postTime);
        });
        List<IslandItem> hidden=new ArrayList<>(),shown=new ArrayList<>();
        for(IslandItem item:all){if(minimized.contains(item.key)&&item.kind!=IslandItem.Kind.CALL)hidden.add(item);else shown.add(item);}
        if(HyperOsNative.isLoaded()&&!presentationFailed){
            try{
                if(presentation==null){presentation=new HyperOsPresentationQueue();minimized.clear();restored.clear();pinned=null;}
                presentation.sync(all);shown=new ArrayList<>(presentation.layout().visible);hidden=new ArrayList<>(presentation.layout().hidden);
            }catch(Throwable failure){failPresentation(failure);shown.clear();hidden.clear();}
        }
        visible=shown;
        if(window!=null&&statusBar!=null){
            window.setSuppressed(suppressed());window.setShadeExpansion(shade);window.setItems(shown,hint,hidden);hint=0;
        }
        if(suppressed()||shown.isEmpty()||window==null||!window.isShown())HyperOsIncomingCalls.reportHidden();
        changedPresentation();
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
    /** Hide only a native category for which one of the two drawn replacement slots exists. */
    public boolean replacesKind(IslandItem.Kind kind){
        if(!isShown())return false;
        for(int i=0;i<Math.min(2,visible.size());i++)if(visible.get(i).kind==kind)return true;
        return false;
    }
    public boolean replacesOngoing(){
        if(!isShown())return false;
        for(int i=0;i<Math.min(2,visible.size());i++){
            IslandItem item=visible.get(i);if(notifications.containsKey(item.key)||item.isMedia||item==screenRecording)return true;
        }return false;
    }
    public boolean replacesScreenRecording(){
        if(!isShown())return false;
        for(int i=0;i<Math.min(2,visible.size());i++){
            IslandItem item=visible.get(i);if(item.kind==IslandItem.Kind.RECORDING&&"com.android.systemui".equals(item.packageName))return true;
        }return false;
    }
    public void onWindowConfigurationChanged(){onMain(()->{if(window!=null)window.onConfigurationChanged();requestRefresh();});}
    public void onWindowInsetsChanged(){onMain(()->{if(window!=null)window.onInsetsChanged();requestRefresh();});}
    public void onExitTargetsRequested(){onIslandFrame();}
    private void changedPresentation(){if(presentationChanged!=null)presentationChanged.run();}
    @Override public void onIslandFrame(){if(window!=null)window.onFrame();if(presentation==null&&!presentationFailed&&HyperOsNative.isLoaded())requestRefresh();changedPresentation();}
    @Override public boolean hasMinimized(){return presentation!=null?!presentation.layout().hidden.isEmpty():!minimized.isEmpty();}
    @Override public void onSwipe(int direction){onMain(()->{
        if(presentation==null)return;try{presentation.swipe(direction);hint=direction<0?1:2;requestRefresh();}catch(Throwable failure){failPresentation(failure);}
    });}
    @Override public HyperOsPresentationQueue.Layout previewSwipe(int direction){
        if(presentation==null)return null;
        return presentation.preview(direction);
    }
    private void failPresentation(Throwable failure){
        presentationFailed=true;if(presentation!=null)try{presentation.close();}catch(Throwable ignored){}presentation=null;
        HyperOsIncomingCalls.reportHidden();if(window!=null)window.setSuppressed(true);changedPresentation();
        Log.e(TAG,"Presentation state failed",failure);
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
        if(item==null)return;
        try{
            Bundle options=activityOptions();
            if(window!=null&&window.view()!=null&&from!=null&&!from.isEmpty())try{
                options=HyperOsActivityLaunch.options(context,window.view(),from,window.isExpanded(),false);
            }catch(Exception unsupported){Log.w(TAG,"Remote transition unavailable",unsupported);}
            if(item.contentIntent!=null)item.contentIntent.send(context,0,null,null,null,null,options);
            else if(item.mediaOpen!=null)item.mediaOpen.run();
            else if(item.packageName!=null){Intent intent=context.getPackageManager().getLaunchIntentForPackage(item.packageName);if(intent!=null){intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent,options);}}
        }catch(Exception failure){Log.w(TAG,"Open capability failed",failure);}
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
        if(closed)return;Set<String> keys=new LinkedHashSet<>();
        if(controllers!=null)for(MediaController controller:controllers){
            String key="hyperos:media:"+controller.getPackageName()+":"+controller.getSessionToken().hashCode();keys.add(key);
            MediaBinding binding=media.get(key);if(binding==null){binding=new MediaBinding(key,controller);media.put(key,binding);controller.registerCallback(binding,main);}binding.update();
        }
        for(String key:new ArrayList<>(media.keySet()))if(!keys.contains(key)){MediaBinding binding=media.remove(key);binding.controller.unregisterCallback(binding);if(mediaOutput!=null)mediaOutput.unbind(key);}
        requestRefresh();
    }
    private final class MediaBinding extends MediaController.Callback {
        final String key;final MediaController controller;IslandItem item;
        MediaBinding(String key,MediaController controller){this.key=key;this.controller=controller;}
        @Override public void onPlaybackStateChanged(PlaybackState state){update();requestRefresh();}
        @Override public void onMetadataChanged(MediaMetadata metadata){update();requestRefresh();}
        @Override public void onSessionDestroyed(){media.remove(key);controller.unregisterCallback(this);if(mediaOutput!=null)mediaOutput.unbind(key);requestRefresh();}
        @Override public void onSessionEvent(String event,Bundle extras){update();requestRefresh();}
        @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info){update();requestRefresh();}
        void update(){
            PlaybackState state=controller.getPlaybackState();MediaMetadata metadata=controller.getMetadata();
            if(state==null||state.getState()==PlaybackState.STATE_NONE||state.getState()==PlaybackState.STATE_STOPPED||state.getState()==PlaybackState.STATE_ERROR){item=null;if(mediaOutput!=null)mediaOutput.unbind(key);return;}
            IslandItem next=new IslandItem(key);next.kind=IslandItem.Kind.MEDIA;next.isMedia=true;next.packageName=controller.getPackageName();
            next.contentVersion=++version;next.postTime=System.currentTimeMillis();next.contentIntent=controller.getSessionActivity();
            configureMedia(next,metadata,state);decorateApp(context,next);
            long actions=state.getActions();final MediaController.TransportControls controls=controller.getTransportControls();
            if((actions&(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE))!=0)next.mediaPlayPause=()->{
                PlaybackState current=controller.getPlaybackState();if(current==null)return;
                if(current.getState()==PlaybackState.STATE_PLAYING){if((current.getActions()&(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE))!=0)controls.pause();}
                else if((current.getActions()&(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PLAY_PAUSE))!=0)controls.play();
            };
            if((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0)next.mediaPrev=controls::skipToPrevious;
            if((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0)next.mediaNext=controls::skipToNext;
            if((actions&PlaybackState.ACTION_SEEK_TO)!=0)next.mediaSeek=position->controls.seekTo(Math.max(0,next.mediaDuration>0?Math.min(next.mediaDuration,position):position));
            if(state.getCustomActions()!=null)for(PlaybackState.CustomAction action:state.getCustomActions()){
                Drawable icon=null;try{icon=context.createPackageContext(next.packageName,0).getDrawable(action.getIcon());}catch(Exception ignored){}
                next.mediaCustom.add(new IslandItem.Action(action.getName(),icon,null,()->controls.sendCustomAction(action,action.getExtras()),false));
            }
            if(next.art!=null){next.icon=new BitmapDrawable(context.getResources(),next.art);next.iconIsImage=true;}
            if(mediaOutput!=null)mediaOutput.bind(next,controller,context);
            item=next;
        }
    }
    /** Metadata parser is independent from session ownership and never invokes controls. */
    public static void configureMedia(IslandItem item,MediaMetadata metadata,PlaybackState state){
        if(state==null)return;item.playing=state.getState()==PlaybackState.STATE_PLAYING;
        item.mediaPosition=state.getPosition();item.mediaPositionUpdateTime=state.getLastPositionUpdateTime();item.mediaSpeed=state.getPlaybackSpeed();
        item.trailing=IslandItem.Trailing.WAVEFORM;item.accent=0xffffffff;
        if(metadata!=null){
            item.title=first(metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),metadata.getText(MediaMetadata.METADATA_KEY_TITLE));
            item.subtitle=first(metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),metadata.getText(MediaMetadata.METADATA_KEY_ARTIST));
            item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);if(item.art==null)item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if(item.art==null)item.art=metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            item.mediaDuration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        if(item.title==null)item.title="Media";
    }
    /** Parse actual framework/One UI notification fields; only supplied capabilities become actions. */
    public static IslandItem notificationItem(Context context,StatusBarNotification sbn,Object data){
        Notification n=sbn.getNotification();if(n==null)return null;Bundle extras=n.extras==null?new Bundle():n.extras;
        String pkg=sbn.getPackageName();int style=integer(extras.get("android.ongoingActivityNoti.style"),0);
        boolean focused=extras.containsKey("miui.focus.param")||extras.containsKey("miui.focus.param_v3");
        boolean promoted=extras.getBoolean("android.requestPromotedOngoing",false)||Boolean.TRUE.equals(read(read(data,"mNotificationEntry"),"isPromotedState"));
        String title=String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE,""));
        String semantic=(pkg+" "+sbn.getTag()+" "+n.getChannelId()+" "+title).toLowerCase(Locale.ROOT);
        boolean recording="com.sec.android.app.voicenote".equals(pkg)||"com.miui.screenrecorder".equals(pkg)
            ||("com.android.systemui".equals(pkg)&&(semantic.contains("screenrecord")||semantic.contains("screen record")));
        boolean call=Notification.CATEGORY_CALL.equals(n.category)||extras.containsKey("android.callType");
        // Samsung ringing cards require the verified popup bridge's ready answer/decline binding.
        if(HyperOsIncomingCalls.APP.equals(pkg)&&extras.getInt("android.callType",0)==1)return null;
        boolean navigation=Notification.CATEGORY_NAVIGATION.equals(n.category);
        Object nativeClock=read(data,"mChronometerView");if(!(nativeClock instanceof android.widget.RemoteViews))nativeClock=extras.get(HyperOsChronometerAdapter.EXTRA_VIEW);
        boolean timer=(pkg.contains("clock")&&(semantic.contains("timer")||extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN,false)
            ||extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER,false)||nativeClock instanceof android.widget.RemoteViews))||Notification.CATEGORY_ALARM.equals(n.category);
        boolean media=extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        if(data==null&&!focused&&!promoted&&style<1&&!((n.flags&Notification.FLAG_ONGOING_EVENT)!=0&&(recording||call||navigation||timer||media)))return null;
        if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return null;
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
        HyperOsClockNotificationAdapter.orderActions(item);
        return item;
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
        catch(Exception ignored){}if(item.icon==null){item.icon=item.appIcon;item.iconIsImage=true;}
    }
    private static Drawable load(Context context,Object value){try{return value instanceof Icon?((Icon)value).loadDrawable(context):value instanceof Drawable?(Drawable)value:null;}catch(RuntimeException ignored){return null;}}
    private static PendingIntent capability(Object value){return value instanceof PendingIntent?(PendingIntent)value:null;}
    private static CharSequence text(Object value){return value instanceof CharSequence?(CharSequence)value:null;}
    private static CharSequence first(CharSequence... values){for(CharSequence value:values)if(value!=null&&value.length()>0)return value;return null;}
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
        if(closed)return;closed=true;main.removeCallbacks(refresh);HyperOsIncomingCalls.reportHidden();
        if(window!=null)window.detach();if(bluetooth!=null)bluetooth.close();
        if(torch!=null)torch.close();
        if(presentation!=null){presentation.close();presentation=null;}
        if(mediaOutput!=null)mediaOutput.close();
        if(sessions!=null&&mediaRegistered)try{sessions.removeOnActiveSessionsChangedListener(sessionListener);}catch(RuntimeException ignored){}
        for(MediaBinding binding:media.values())binding.controller.unregisterCallback(binding);media.clear();
        if(receiverRegistered)try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}
        if(settingsRegistered)try{context.getContentResolver().unregisterContentObserver(settingsObserver);}catch(RuntimeException ignored){}
        notifications.clear();visible=Collections.emptyList();statusBar=null;changedPresentation();
    });}
}
