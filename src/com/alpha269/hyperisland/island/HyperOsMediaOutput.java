package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Actual session output state and a capability for One UI's own route chooser. */
public final class HyperOsMediaOutput implements AutoCloseable {
    public static final String MANAGER="com.android.systemui.media.dialog.MediaOutputDialogManager";
    public static final String DEVICE_ENTRY="com.android.systemui.media.controls.domain.pipeline.MediaDeviceManager$Entry";
    private static final String TAG="HyperOsMediaOutput";
    private static final Object registryLock=new Object();
    private static WeakReference<Object> nativeManager=new WeakReference<>(null);
    private static Method showMethod;
    private static final Map<MediaSession.Token,WeakReference<Object>> nativeDevices=new LinkedHashMap<>();
    private static final List<WeakReference<HyperOsMediaOutput>> observers=new ArrayList<>();
    private final Context context;
    private final Runnable changed;
    private final Provider provider;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Long> bindings=new LinkedHashMap<>();
    private boolean started,closed,pending;
    private long generation;
    private final Runnable signal=this::signalChanged;
    private void signalChanged(){pending=false;if(!closed&&started&&changed!=null)changed.run();}

    public interface Provider extends AutoCloseable {
        void start(Runnable changed)throws Exception;
        Output read(MediaController controller,Context context)throws Exception;
        Runnable chooser(MediaController controller,String packageName)throws Exception;
        @Override void close();
    }
    public static final class Output {
        public final CharSequence name;
        public final Drawable icon;
        public final boolean local,selectable;
        public Output(CharSequence name,Drawable icon,boolean local,boolean selectable){this.name=name;this.icon=icon;this.local=local;this.selectable=selectable;}
    }
    /** Typed active output evidence, also usable by owned tests without an AudioDeviceInfo. */
    public static final class Device {
        public final int type;
        public final CharSequence productName;
        public Device(int type,CharSequence name){this.type=type;productName=name;}
    }
    public HyperOsMediaOutput(Context context,Runnable changed){this(context,changed,new SystemProvider(context));}
    public HyperOsMediaOutput(Context context,Runnable changed,Provider provider){
        if(context==null||provider==null)throw new IllegalArgumentException("Missing media-output source");
        this.context=context;this.changed=changed;this.provider=provider;
    }
    public void start(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::start);return;}
        if(started||closed)return;started=true;
        synchronized(registryLock){observers.add(new WeakReference<>(this));}
        try{provider.start(this::refresh);}catch(Exception failure){Log.w(TAG,"Output observation unavailable",failure);}
        refresh();
    }
    public void refresh(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::refresh);return;}
        if(closed||!started||pending)return;pending=true;main.post(signal);
    }
    /** Bind only the real supplied session; the callback itself runs solely on a user press. */
    public void bind(IslandItem item,MediaController controller,Context caller){
        if(item==null||closed)return;
        Output output=null;Runnable chooser=null;
        try{output=provider.read(controller,caller==null?context:caller);
            if(output==null||output.selectable)chooser=provider.chooser(controller,item.packageName);
        }catch(Exception unavailable){Log.w(TAG,"Output capability unavailable",unavailable);}
        item.trailingText=output==null?null:output.name;item.rightImage=output==null?null:copy(output.icon);
        long token=++generation;bindings.put(item.key,token);
        item.trailingTap=chooser==null?null:()->dispatch(item.key,token,controller,item.packageName);
    }
    public void unbind(String key){bindings.remove(key);}
    private void dispatch(String key,long token,MediaController controller,String packageName){
        if(Looper.myLooper()!=main.getLooper()){main.post(()->dispatch(key,token,controller,packageName));return;}
        if(closed||!Long.valueOf(token).equals(bindings.get(key)))return;
        try{
            Output output=provider.read(controller,context);if(output!=null&&!output.selectable)return;
            Runnable capability=provider.chooser(controller,packageName);if(capability!=null)capability.run();
        }catch(Exception failure){Log.w(TAG,"Native route chooser unavailable",failure);}
    }
    /** Resolve only the exact signature observed in the installed One UI artifact. */
    public static void register(ClassLoader loader){
        Method selected=null;
        try{
            Class<?> manager=Class.forName(MANAGER,false,loader);
            Class<?> transition=Class.forName("com.android.systemui.animation.DialogTransitionAnimator$Controller",false,loader);
            Class<?> listener=Class.forName("com.android.systemui.media.dialog.MediaOutputDialog$OnDialogEventListener",false,loader);
            selected=manager.getMethod("createAndShow",String.class,boolean.class,transition,boolean.class,UserHandle.class,MediaSession.Token.class,listener);
        }catch(Exception unavailable){Log.w(TAG,"Verified One UI chooser signature unavailable",unavailable);}
        synchronized(registryLock){showMethod=selected;}notifyObservers();
    }
    /** Called after the existing One UI manager's constructor; never constructs a replacement. */
    public static void captureManager(Object manager){
        synchronized(registryLock){if(manager==null||showMethod==null||!showMethod.getDeclaringClass().isInstance(manager))return;nativeManager=new WeakReference<>(manager);}
        notifyObservers();
    }
    /** Read only exact native session/device fields; maps entries by the real session token. */
    public static void captureDeviceEntry(Object entry){
        Object raw=read(entry,"controller");if(!(raw instanceof MediaController))return;
        MediaSession.Token token=((MediaController)raw).getSessionToken();if(token==null)return;
        synchronized(registryLock){nativeDevices.entrySet().removeIf(e->e.getValue().get()==null);nativeDevices.put(token,new WeakReference<>(entry));}
        notifyObservers();
    }
    public static void releaseDeviceEntry(Object entry){
        synchronized(registryLock){nativeDevices.entrySet().removeIf(e->e.getValue().get()==null||e.getValue().get()==entry);}
        notifyObservers();
    }
    private static void notifyObservers(){
        List<HyperOsMediaOutput> active=new ArrayList<>();
        synchronized(registryLock){observers.removeIf(reference->reference.get()==null);for(WeakReference<HyperOsMediaOutput> reference:observers){HyperOsMediaOutput source=reference.get();if(source!=null)active.add(source);}}
        for(HyperOsMediaOutput source:active)source.refresh();
    }
    private static Object availableManager(){
        synchronized(registryLock){Object manager=nativeManager.get();
            if(manager==null||showMethod==null||!showMethod.getDeclaringClass().isInstance(manager))return null;
            // These injected dependencies are required by the verified constructor/create path.
            return read(manager,"context")!=null&&read(manager,"mediaSwitchingControllerFactory")!=null
                &&read(manager,"mainExecutor")!=null&&read(manager,"backgroundExecutor")!=null?manager:null;
        }
    }
    private static Output fromNative(Object data,boolean local){
        Object enabled=read(data,"enabled"),name=read(data,"name"),icon=read(data,"icon");
        if(!(enabled instanceof Boolean))return null;
        return new Output(name instanceof CharSequence?(CharSequence)name:null,icon instanceof Drawable?(Drawable)icon:null,local,(Boolean)enabled);
    }
    /** Names describe reported active outputs; this never guesses from merely available devices. */
    public static CharSequence describe(List<Device> devices,boolean local){
        LinkedHashSet<String> names=new LinkedHashSet<>();
        if(devices!=null)for(Device device:devices){if(device==null)continue;
            CharSequence name=device.productName;if(name==null||name.toString().trim().isEmpty())name=typeName(device.type);
            names.add(name.toString());
        }
        return names.isEmpty()?local?"Local audio":"Remote audio":String.join(", ",names);
    }
    private static String typeName(int type){
        switch(type){
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "Phone speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:return "Phone earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "Wired headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:case AudioDeviceInfo.TYPE_BLE_SPEAKER:return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_ACCESSORY:case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB audio";
            case AudioDeviceInfo.TYPE_HDMI:case AudioDeviceInfo.TYPE_HDMI_ARC:case AudioDeviceInfo.TYPE_HDMI_EARC:return "HDMI audio";
            default:return "Audio output";
        }
    }
    private static Drawable copy(Drawable icon){if(icon==null)return null;Drawable.ConstantState state=icon.getConstantState();return state==null?icon:state.newDrawable().mutate();}
    private static Object read(Object owner,String name){
        if(owner==null)return null;for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{
            Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
        }catch(Exception ignored){}return null;
    }
    @Override public void close(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::close);return;}
        if(closed)return;closed=true;bindings.clear();main.removeCallbacks(signal);
        synchronized(registryLock){observers.removeIf(reference->reference.get()==null||reference.get()==this);}
        provider.close();
    }
    private static final class SystemProvider implements Provider {
        final Context context;final Handler main=new Handler(Looper.getMainLooper());
        AudioManager audio;Runnable changed;boolean closed,devicesRegistered,playbackRegistered;
        final AudioDeviceCallback devices=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){signal();}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){signal();}
        };
        final AudioManager.AudioPlaybackCallback playback=new AudioManager.AudioPlaybackCallback(){
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations){signal();}
        };
        SystemProvider(Context context){this.context=context;}
        @Override public void start(Runnable changed){
            this.changed=changed;audio=context.getSystemService(AudioManager.class);if(audio==null)return;
            try{audio.registerAudioDeviceCallback(devices,main);devicesRegistered=true;}catch(RuntimeException unavailable){Log.w(TAG,"Device observation unavailable",unavailable);}
            try{audio.registerAudioPlaybackCallback(playback,main);playbackRegistered=true;}catch(RuntimeException unavailable){Log.w(TAG,"Playback-route observation unavailable",unavailable);}
        }
        void signal(){if(!closed&&changed!=null)changed.run();}
        @Override public Output read(MediaController controller,Context caller){
            if(controller==null||closed)return null;MediaController.PlaybackInfo info=controller.getPlaybackInfo();
            if(info==null)return null;boolean local=info.getPlaybackType()==MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
            Object entry;synchronized(registryLock){WeakReference<Object> reference=nativeDevices.get(controller.getSessionToken());entry=reference==null?null:reference.get();}
            Output nativeOutput=fromNative(HyperOsMediaOutput.read(entry,"current"),local);if(nativeOutput!=null)return nativeOutput;
            List<Device> active=new ArrayList<>();
            if(local&&audio!=null)try{
                AudioAttributes attributes=info.getAudioAttributes();
                if(attributes!=null)for(AudioDeviceInfo device:audio.getAudioDevicesForAttributes(attributes))if(device!=null)active.add(new Device(device.getType(),device.getProductName()));
            }catch(RuntimeException unavailable){Log.w(TAG,"Active output query unavailable",unavailable);}
            return new Output(describe(active,local),null,local,true);
        }
        @Override public Runnable chooser(MediaController controller,String packageName){
            if(closed||controller==null||packageName==null||packageName.isEmpty()||!packageName.equals(controller.getPackageName()))return null;
            MediaSession.Token token=controller.getSessionToken();if(token==null)return null;
            if(availableManager()!=null){
                return ()->{
                    if(closed)return;Object manager=availableManager();Method method;synchronized(registryLock){method=showMethod;}
                    if(manager==null||method==null)return;
                    try{method.invoke(manager,packageName,false,null,true,Process.myUserHandle(),token,null);}
                    catch(Exception failure){Log.w(TAG,"Native route chooser failed",failure);}
                };
            }
            // One UI 8+ (Android 16) opens native Media Output via com.android.systemui.action.OPEN_MEDIA_OUTPUT.
            return ()->{
                if(closed)return;
                try{
                    android.content.Intent intent=new android.content.Intent("com.android.systemui.action.OPEN_MEDIA_OUTPUT")
                        .setPackage("com.android.systemui")
                        .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("android.intent.extra.PACKAGE_NAME",packageName);
                    android.app.ActivityOptions opts=android.app.ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(1);
                    context.startActivity(intent,opts.toBundle());
                    HyperIslandController ctrl=HyperIslandController.get();
                    if(ctrl!=null)ctrl.collapseIfExpanded();
                }catch(Exception failure){Log.w(TAG,"One UI media output intent failed",failure);}
            };
        }
        @Override public void close(){
            if(closed)return;closed=true;changed=null;
            if(audio!=null){if(devicesRegistered)try{audio.unregisterAudioDeviceCallback(devices);}catch(RuntimeException ignored){}
                if(playbackRegistered)try{audio.unregisterAudioPlaybackCallback(playback);}catch(RuntimeException ignored){}}
        }
    }
}
