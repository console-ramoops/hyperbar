package com.alpha269.hyperisland.island;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bluetooth audio observation only: no device connection, pairing or ANC writes. */
public final class HyperOsBluetoothSource implements AutoCloseable {
    public static final String KEY_PREFIX="hyperos:bluetooth:";
    private static final long CONNECT_MS=6000,DISCONNECT_MS=3000,POLL_MS=15000;
    private final Context context;
    private final Runnable changed;
    private final Provider provider;
    private final Clock clock;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"HyperOS-Bluetooth-observation");thread.setDaemon(true);return thread;
    });
    private final Map<String,DeviceData> ready=new LinkedHashMap<>();
    private final Map<String,Event> events=new LinkedHashMap<>();
    private boolean started,closed,busy,again,ownsGenericBluetooth;
    private long generation,version;
    private final Runnable expiry=()->expire();
    private final Runnable poll=new Runnable(){@Override public void run(){
        synchronized(HyperOsBluetoothSource.this){if(closed||!started)return;}
        refresh();main.postDelayed(this,POLL_MS);
    }};

    public interface Provider extends AutoCloseable {
        void start(Runnable signal)throws Exception;
        Snapshot snapshot()throws Exception;
        @Override void close();
    }
    public interface Clock {long uptime();long wallTime();}
    private static final Clock SYSTEM_CLOCK=new Clock(){
        public long uptime(){return SystemClock.uptimeMillis();}
        public long wallTime(){return System.currentTimeMillis();}
    };

    /** Immutable observation, also usable by an owned fixture Provider. */
    public static final class DeviceData {
        public final String id,name,photoUri;
        public final int state,mainBattery,leftBattery,rightBattery,caseBattery,profiles;
        public final Drawable picture,icon;
        public DeviceData(String id,String name,int state,int main,int left,int right,int batteryCase,
            Drawable picture,Drawable icon) {
            this(id,name,state,main,left,right,batteryCase,picture,icon,null,0);
        }
        public DeviceData(String id,String name,int state,int main,int left,int right,int batteryCase,
            Drawable picture,Drawable icon,String photoUri,int profiles) {
            if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("Missing Bluetooth device identity");
            if(state<0||state>3)throw new IllegalArgumentException("Invalid Bluetooth connection state");
            this.id=id.trim().toUpperCase(Locale.ROOT);this.name=name==null||name.trim().isEmpty()?"Bluetooth audio":name.trim();
            this.state=state;mainBattery=percent(main);leftBattery=percent(left);rightBattery=percent(right);caseBattery=percent(batteryCase);
            this.picture=picture;this.icon=icon;this.photoUri=photoUri;this.profiles=profiles;
        }
        private boolean same(DeviceData other) {
            return other!=null&&name.equals(other.name)&&state==other.state&&mainBattery==other.mainBattery
                &&leftBattery==other.leftBattery&&rightBattery==other.rightBattery&&caseBattery==other.caseBattery
                &&equal(photoUri,other.photoUri);
        }
        private DeviceData rename(String id){return new DeviceData(id,name,state,mainBattery,leftBattery,rightBattery,caseBattery,picture,icon,photoUri,profiles);}
        private DeviceData merge(DeviceData other) {
            int status=state==BluetoothProfile.STATE_CONNECTED||other.state==BluetoothProfile.STATE_CONNECTED?
                BluetoothProfile.STATE_CONNECTED:state==BluetoothProfile.STATE_CONNECTING||other.state==BluetoothProfile.STATE_CONNECTING?
                BluetoothProfile.STATE_CONNECTING:BluetoothProfile.STATE_DISCONNECTING;
            return new DeviceData(id,"Bluetooth audio".equals(other.name)?name:other.name,status,
                known(other.mainBattery,mainBattery),known(other.leftBattery,leftBattery),known(other.rightBattery,rightBattery),
                known(other.caseBattery,caseBattery),other.picture==null?picture:other.picture,other.icon==null?icon:other.icon,
                other.photoUri==null?photoUri:other.photoUri,profiles|other.profiles);
        }
        public int primaryPercent() {
            if(mainBattery>=0)return mainBattery;
            if(leftBattery>=0&&rightBattery>=0)return Math.min(leftBattery,rightBattery);
            return leftBattery>=0?leftBattery:rightBattery;
        }
    }
    public static final class Snapshot {
        public final boolean available,adapterEnabled,complete;
        public final List<DeviceData> devices;
        public final Set<String> disconnected;
        public final Map<String,String> aliases;
        public Snapshot(boolean enabled,List<DeviceData> devices){this(true,enabled,true,devices,Collections.emptySet(),Collections.emptyMap());}
        public Snapshot(boolean available,boolean enabled,boolean complete,List<DeviceData> devices,
            Set<String> disconnected,Map<String,String> aliases) {
            this.available=available;adapterEnabled=enabled;this.complete=complete;
            this.devices=devices==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(devices));
            Set<String> removals=new HashSet<>();if(disconnected!=null)for(String id:disconnected)removals.add(id.toUpperCase(Locale.ROOT));
            this.disconnected=Collections.unmodifiableSet(removals);
            Map<String,String> identities=new LinkedHashMap<>();if(aliases!=null)for(Map.Entry<String,String> pair:aliases.entrySet())
                identities.put(pair.getKey().toUpperCase(Locale.ROOT),pair.getValue().toUpperCase(Locale.ROOT));
            this.aliases=Collections.unmodifiableMap(identities);
        }
    }
    private static final class Event {
        final DeviceData device;
        final boolean connected;
        final long wallTime,expires,version;
        Event(DeviceData device,boolean connected,long wallTime,long expires,long version) {
            this.device=device;this.connected=connected;this.wallTime=wallTime;this.expires=expires;this.version=version;
        }
        Event update(DeviceData next,long version){return new Event(next,connected,wallTime,expires,version);}
    }

    public HyperOsBluetoothSource(Context context,Runnable changed) {
        this(context,changed,new SystemProvider(context),SYSTEM_CLOCK);
    }
    public HyperOsBluetoothSource(Context context,Runnable changed,Provider provider,Clock clock) {
        if(context==null||provider==null||clock==null)throw new IllegalArgumentException("Missing Bluetooth source boundary");
        this.context=context;this.changed=changed;this.provider=provider;this.clock=clock;
    }

    public void start() {
        if(Looper.myLooper()!=main.getLooper()){main.post(this::start);return;}
        synchronized(this){if(closed||started)return;started=true;}
        try{provider.start(this::refresh);}catch(Exception ignored){}
        refresh();main.postDelayed(poll,POLL_MS);
    }
    public void refresh() {
        final long token;
        synchronized(this){if(closed||!started)return;generation++;if(busy){again=true;return;}busy=true;again=false;token=generation;}
        try {
            worker.execute(()->{
                Snapshot result=null;try{result=provider.snapshot();}catch(Throwable ignored){}
                final Snapshot observation=result;
                main.post(()->publish(token,observation));
            });
        }catch(java.util.concurrent.RejectedExecutionException ignored){synchronized(this){busy=false;}}
    }
    private void publish(long token,Snapshot observation) {
        boolean notify=false,retry;
        synchronized(this) {
            busy=false;if(closed)return;
            if(token==generation&&observation!=null&&observation.available)notify=apply(observation);
            retry=again;again=false;
        }
        if(notify)notifyChanged();if(retry)refresh();
    }
    private boolean apply(Snapshot observation) {
        boolean notify=!ownsGenericBluetooth&&(observation.complete||!observation.devices.isEmpty()||!observation.disconnected.isEmpty());
        ownsGenericBluetooth|=observation.complete||!observation.devices.isEmpty()||!observation.disconnected.isEmpty();
        if(!observation.adapterEnabled){boolean any=notify||!ready.isEmpty()||!events.isEmpty();ready.clear();events.clear();scheduleExpiry();return any;}
        long now=clock.uptime(),wall=clock.wallTime();
        for(Map.Entry<String,String> alias:observation.aliases.entrySet()) {
            String target=canonical(alias.getKey(),observation.aliases);
            if(alias.getKey().equals(target))continue;
            DeviceData old=ready.remove(alias.getKey());
            if(old!=null){DeviceData present=ready.get(target);ready.put(target,present==null?old.rename(target):old.rename(target).merge(present));}
            Event event=events.remove(alias.getKey());
            if(event!=null){
                Event present=events.get(target);
                if(present==null||event.version>present.version)events.put(target,event.update(event.device.rename(target),++version));
            }
            notify|=old!=null||event!=null;
        }
        Map<String,DeviceData> next=new LinkedHashMap<>();
        Set<String> rawRemoved=new HashSet<>(observation.disconnected);
        for(DeviceData device:observation.devices)if(device!=null&&device.state==BluetoothProfile.STATE_DISCONNECTED)rawRemoved.add(device.id);
        for(DeviceData device:observation.devices) {
            if(device==null||device.state==BluetoothProfile.STATE_DISCONNECTED||rawRemoved.contains(device.id))continue;
            String id=canonical(device.id,observation.aliases);if(!device.id.equals(id))device=device.rename(id);
            DeviceData previous=next.get(device.id);next.put(device.id,previous==null?device:previous.merge(device));
        }
        Set<String> removed=new HashSet<>();
        for(String id:rawRemoved){String target=canonical(id,observation.aliases);
            // A departing LE member does not disconnect a lead still reported connected.
            if(id.equals(target)||!next.containsKey(target))removed.add(target);
        }
        for(String id:removed)next.remove(id);
        Iterator<Map.Entry<String,DeviceData>> old=ready.entrySet().iterator();
        while(old.hasNext()) {
            Map.Entry<String,DeviceData> entry=old.next();
            if((observation.complete&&!next.containsKey(entry.getKey()))||removed.contains(entry.getKey())) {
                DeviceData device=entry.getValue();old.remove();
                events.put(device.id,new Event(device,false,wall,now+DISCONNECT_MS,++version));notify=true;
            }
        }
        for(DeviceData device:next.values()) {
            DeviceData previous=ready.put(device.id,device);Event event=events.get(device.id);
            boolean stateChanged=previous==null||previous.state!=device.state;
            if(stateChanged) {
                events.put(device.id,new Event(device,true,wall,now+CONNECT_MS,++version));notify=true;
            } else if(!device.same(previous)&&event!=null&&event.expires>now) {
                events.put(device.id,event.update(device,++version));notify=true;
            }
        }
        scheduleExpiry();return notify;
    }
    public synchronized void appendTo(List<IslandItem> items) {
        if(items==null||closed)return;
        if(ownsLegacyGenericBluetooth())items.removeIf(item->"alert:pods".equals(item.key)&&item.packageName==null);
        long now=clock.uptime();
        Iterator<Event> iterator=events.values().iterator();
        while(iterator.hasNext()) {
            Event event=iterator.next();if(event.expires<=now){iterator.remove();continue;}
            items.add(item(event));
        }
    }
    /** Controller must also gate its later legacy singleton override with this ownership. */
    public synchronized boolean ownsLegacyGenericBluetooth(){return started&&!closed&&ownsGenericBluetooth;}
    private IslandItem item(Event event) {
        DeviceData data=event.device;IslandItem item=new IslandItem(KEY_PREFIX+data.id);
        item.kind=IslandItem.Kind.LIVE;item.alert=true;item.title=data.name;
        String status=event.connected?status(data.state):"Disconnected";
        item.overline=status;item.subtitle=status;item.postTime=event.wallTime;item.expiresAtUptime=event.expires;
        item.contentVersion=event.version;item.accent=0xffffffff;item.iconTint=0xffffffff;item.trailingColor=0xffffffff;
        item.trailing=IslandItem.Trailing.TEXT;
        int primary=event.connected&&data.state==BluetoothProfile.STATE_CONNECTED?data.primaryPercent():-1;
        item.trailingText=primary>=0?primary+"%":status;item.ringPercent=primary;
        Drawable picture=cloneDrawable(data.picture),icon=cloneDrawable(data.icon);
        if(picture!=null){item.icon=picture;item.cardImage=cloneDrawable(data.picture);item.iconIsImage=true;item.cardImageIsImage=true;}
        else {item.icon=icon;item.cardIcon=cloneDrawable(data.icon);}
        item.iconColor=true;
        if(event.connected&&data.state==BluetoothProfile.STATE_CONNECTED) {
            item.metrics=new ArrayList<>();item.metrics.add(metric("L",data.leftBattery));
            item.metrics.add(metric("R",data.rightBattery));item.metrics.add(metric("Case",data.caseBattery));
        }
        return item;
    }
    private static IslandItem.Metric metric(String label,int value) {
        IslandItem.Metric result=new IslandItem.Metric();result.type=IslandItem.Metric.TEXT;result.label=label;
        result.fixed=value>=0?value+"%":"—";return result;
    }
    private static Drawable cloneDrawable(Drawable original) {
        if(original==null)return null;Drawable.ConstantState state=original.getConstantState();return state==null?original:state.newDrawable().mutate();
    }
    private void scheduleExpiry() {
        main.removeCallbacks(expiry);long next=Long.MAX_VALUE;
        for(Event event:events.values())next=Math.min(next,event.expires);
        if(next!=Long.MAX_VALUE)main.postDelayed(expiry,Math.max(1,next-clock.uptime()));
    }
    private void expire() {
        boolean notify;
        synchronized(this){if(closed)return;long now=clock.uptime();notify=events.values().removeIf(e->e.expires<=now);scheduleExpiry();}
        if(notify)notifyChanged();
    }
    private void notifyChanged(){if(changed!=null)changed.run();}
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;generation++;ready.clear();events.clear();}
        main.removeCallbacks(expiry);main.removeCallbacks(poll);worker.shutdownNow();provider.close();
    }
    private static int percent(int value){return value>=0&&value<=100?value:-1;}
    private static int known(int newer,int old){return newer>=0?newer:old;}
    private static boolean equal(Object a,Object b){return a==b||(a!=null&&a.equals(b));}
    private static String canonical(String id,Map<String,String> aliases){
        String next=id;Set<String> visited=new HashSet<>();
        while(visited.add(next)){String target=aliases.get(next);if(target==null||target.equals(next))return next;next=target;}
        // Contradictory cyclic evidence cannot establish a new canonical identity.
        return id;
    }
    private static String status(int state){return state==BluetoothProfile.STATE_CONNECTED?"Connected":state==BluetoothProfile.STATE_CONNECTING?"Connecting":"Disconnecting";}

    /** Own profile proxies/listeners only. All queries and image reads are passive. */
    private static final class SystemProvider implements Provider {
        private static final int LE_AUDIO=22;
        private static final String A2DP="android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
        private static final String HEADSET="android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
        private static final String LE="android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED";
        private static final String BATTERY="android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
        private final Context context;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Map<Integer,BluetoothProfile> profiles=new LinkedHashMap<>();
        private final Set<Integer> requested=new HashSet<>();
        private final Map<String,Map<Integer,Integer>> hints=new HashMap<>();
        private final Set<String> disconnected=new HashSet<>();
        private final Map<Integer,String> audioKeys=new HashMap<>();
        private final Map<String,MetadataRegistration> metadata=new HashMap<>();
        private final Map<String,Drawable> pictures=new LinkedHashMap<String,Drawable>(16,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,Drawable> eldest){return size()>16;}
        };
        private BluetoothAdapter adapter;private AudioManager audio;private Runnable signal;private boolean closed,registered;
        private Drawable genericIcon;
        private final BluetoothProfile.ServiceListener listener=new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int profile,BluetoothProfile proxy) {
                synchronized(SystemProvider.this){if(closed){if(adapter!=null)adapter.closeProfileProxy(profile,proxy);return;}profiles.put(profile,proxy);}
                signal();
            }
            @Override public void onServiceDisconnected(int profile){synchronized(SystemProvider.this){profiles.remove(profile);requested.remove(profile);}signal();}
        };
        private final AudioDeviceCallback audioCallback=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){signal();}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){signal();}
        };
        private final BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context ignored,Intent intent) {
                String action=intent.getAction();if(action==null)return;
                BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
                String id=device==null?null:address(device);
                synchronized(SystemProvider.this) {
                    if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
                        if(state==BluetoothAdapter.STATE_ON){hints.clear();disconnected.clear();requestProfiles();}
                    } else if(id!=null) {
                        if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action))disconnected.add(id);
                        else if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action))disconnected.remove(id);
                        else {
                            int profile=A2DP.equals(action)?BluetoothProfile.A2DP:HEADSET.equals(action)?BluetoothProfile.HEADSET:LE.equals(action)?LE_AUDIO:-1;
                            if(profile>=0) {
                                int state=intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
                                if(state>=0&&state<=3)hints.computeIfAbsent(id,key->new HashMap<>()).put(profile,state);
                                if(state==BluetoothProfile.STATE_CONNECTED)disconnected.remove(id);
                            }
                        }
                    }
                }
                signal();main.postDelayed(SystemProvider.this::signal,400);
            }
        };
        private static final class MetadataRegistration {
            final BluetoothDevice device;final Object listener;final Method remove;
            MetadataRegistration(BluetoothDevice d,Object l,Method r){device=d;listener=l;remove=r;}
        }
        SystemProvider(Context context){this.context=context;}
        @Override public synchronized void start(Runnable callback) {
            signal=callback;
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            adapter=manager==null?BluetoothAdapter.getDefaultAdapter():manager.getAdapter();audio=context.getSystemService(AudioManager.class);
            IntentFilter filter=new IntentFilter();
            for(String action:new String[]{A2DP,HEADSET,LE,BATTERY,BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,BluetoothDevice.ACTION_ACL_DISCONNECTED,BluetoothDevice.ACTION_NAME_CHANGED,
                "android.bluetooth.device.action.ALIAS_CHANGED"})filter.addAction(action);
            try{context.registerReceiver(receiver,filter,"android.permission.BLUETOOTH_CONNECT",main,Context.RECEIVER_EXPORTED);registered=true;}catch(RuntimeException ignored){}
            if(audio!=null)try{audio.registerAudioDeviceCallback(audioCallback,main);}catch(RuntimeException ignored){}
            requestProfiles();
        }
        private synchronized void requestProfiles() {
            if(adapter==null||closed)return;
            for(int profile:new int[]{BluetoothProfile.HEADSET,BluetoothProfile.A2DP,LE_AUDIO}) {
                if(requested.add(profile))try{if(!adapter.getProfileProxy(context,listener,profile))requested.remove(profile);}catch(RuntimeException ignored){requested.remove(profile);}
            }
        }
        private void signal(){Runnable callback;synchronized(this){if(closed)return;callback=signal;}if(callback!=null)callback.run();}
        @Override public Snapshot snapshot() {
            BluetoothAdapter current;Map<Integer,BluetoothProfile> profileCopy;Map<String,Map<Integer,Integer>> hintCopy=new HashMap<>();Set<String> removed;
            synchronized(this){if(closed)return new Snapshot(false,Collections.emptyList());current=adapter;profileCopy=new LinkedHashMap<>(profiles);
                for(Map.Entry<String,Map<Integer,Integer>> e:hints.entrySet())hintCopy.put(e.getKey(),new HashMap<>(e.getValue()));removed=new HashSet<>(disconnected);}
            boolean enabled=true,complete=true;
            if(current!=null)try{enabled=current.isEnabled();}catch(RuntimeException ignored){complete=false;}
            if(!enabled)return new Snapshot(false,Collections.emptyList());
            Map<String,DeviceData> result=new LinkedHashMap<>();
            Map<String,String> aliases=new HashMap<>();
            for(int profile:new int[]{BluetoothProfile.HEADSET,BluetoothProfile.A2DP,LE_AUDIO}) {
                BluetoothProfile proxy=profileCopy.get(profile);
                if(proxy==null) {
                    if(current!=null)try{if(current.getProfileConnectionState(profile)!=BluetoothProfile.STATE_DISCONNECTED)complete=false;}catch(RuntimeException ignored){complete=false;}
                    continue;
                }
                try {
                    List<BluetoothDevice> devices=proxy.getConnectedDevices();
                    if(devices==null){complete=false;continue;}
                    for(BluetoothDevice device:devices) {
                        String id=address(device);if(id==null||removed.contains(id))continue;
                        Map<Integer,Integer> deviceHints=hintCopy.get(id);
                        int state=deviceHints!=null&&deviceHints.containsKey(profile)?deviceHints.get(profile):proxy.getConnectionState(device);
                        if(state==BluetoothProfile.STATE_DISCONNECTED)continue;
                        BluetoothDevice lead=profile==LE_AUDIO?lead(proxy,device):device;
                        DeviceData observed=readDevice(lead,state,1<<Math.min(profile,29));
                        if(observed!=null){
                            if(!observed.id.equals(id)){
                                aliases.put(id,observed.id);DeviceData member=result.remove(id);
                                if(member!=null)observed=member.rename(observed.id).merge(observed);
                            }
                            DeviceData previous=result.get(observed.id);result.put(observed.id,previous==null?observed:previous.merge(observed));
                        }
                    }
                }catch(RuntimeException ignored){complete=false;}
            }
            if(audio!=null)try {
                for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if(!bluetoothAudio(device.getType()))continue;
                    String id=device.getAddress();CharSequence product=device.getProductName();String name=product==null?null:product.toString();
                    if(id!=null)id=id.toUpperCase(Locale.ROOT);
                    if(id!=null&&BluetoothAdapter.checkBluetoothAddress(id)) {
                        id=id.toUpperCase(Locale.ROOT);if(removed.contains(id))continue;
                        if(aliases.containsKey(id))id=aliases.get(id);
                        if(!result.containsKey(id)&&current!=null) {
                            DeviceData observed=readDevice(current.getRemoteDevice(id),BluetoothProfile.STATE_CONNECTED,1<<30);
                            if(observed!=null)result.put(observed.id,observed);
                        }
                    } else {
                        id=uniqueName(result,name);if(id==null)id="AUDIO:"+device.getId();
                        if(!result.containsKey(id))result.put(id,new DeviceData(id,name,BluetoothProfile.STATE_CONNECTED,-1,-1,-1,-1,null,icon(),null,1<<30));
                    }
                    synchronized(this){String previous=audioKeys.put(device.getId(),id);if(previous!=null&&!previous.equals(id))aliases.put(previous,id);}
                }
            }catch(RuntimeException ignored){complete=false;}
            if(complete)trimMetadata(result.keySet());
            return new Snapshot(true,enabled,complete,new ArrayList<>(result.values()),removed,aliases);
        }
        private DeviceData readDevice(BluetoothDevice device,int state,int profile) {
            String id=address(device);if(id==null)return null;
            String name=null;try{name=(String)BluetoothDevice.class.getMethod("getAlias").invoke(device);}catch(Exception ignored){}
            if(name==null)try{name=device.getName();}catch(RuntimeException ignored){}
            int mainBattery=metadataInt(device,18);if(mainBattery<0)try{mainBattery=percent(((Number)BluetoothDevice.class.getMethod("getBatteryLevel").invoke(device)).intValue());}catch(Exception ignored){}
            String uri=metadataString(device,5);Drawable picture=picture(uri);watchMetadata(device,id);
            return new DeviceData(id,name,state,mainBattery,metadataInt(device,10),metadataInt(device,11),metadataInt(device,12),picture,icon(),uri,profile);
        }
        private synchronized Drawable icon() {
            if(genericIcon==null)try{HyperOsResources resources=HyperOsAssets.plugin(context);genericIcon=resources.getResources().getDrawable(resources.id("drawable","ic_miui_volume_headset"),resources.getTheme());}catch(RuntimeException ignored){}
            return genericIcon;
        }
        private Drawable picture(String value) {
            if(value==null||value.isEmpty())return null;Drawable found;
            synchronized(this){found=pictures.get(value);}if(found!=null)return found;
            Uri uri=Uri.parse(value);if(!"content".equals(uri.getScheme())&&!"android.resource".equals(uri.getScheme()))return null;
            try(InputStream stream=context.getContentResolver().openInputStream(uri)) {
                if(stream==null)return null;ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;
                while((count=stream.read(chunk))!=-1){if(output.size()+count>2*1024*1024)return null;output.write(chunk,0,count);}
                byte[] bytes=output.toByteArray();BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(size.outWidth/options.inSampleSize>256||size.outHeight/options.inSampleSize>256)options.inSampleSize*=2;
                Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return null;
                found=new BitmapDrawable(context.getResources(),bitmap);synchronized(this){if(!closed)pictures.put(value,found);}return found;
            }catch(Exception ignored){return null;}
        }
        private synchronized void watchMetadata(BluetoothDevice device,String id) {
            if(adapter==null||closed||metadata.containsKey(id))return;
            try {
                Class<?> type=Class.forName("android.bluetooth.BluetoothAdapter$OnMetadataChangedListener");
                Method add=BluetoothAdapter.class.getMethod("addOnMetadataChangedListener",BluetoothDevice.class,Executor.class,type);
                Method remove=BluetoothAdapter.class.getMethod("removeOnMetadataChangedListener",BluetoothDevice.class,type);
                Object listener=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                    if(method.getDeclaringClass()==Object.class){if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);if("equals".equals(method.getName()))return proxy==args[0];return "HyperOSBluetoothMetadataListener";}
                    signal();return null;
                });
                Object accepted=add.invoke(adapter,device,(Executor)command->main.post(command),listener);
                if(!Boolean.FALSE.equals(accepted))metadata.put(id,new MetadataRegistration(device,listener,remove));
            }catch(Exception ignored){}
        }
        private void trimMetadata(Set<String> connected) {
            List<MetadataRegistration> expired=new ArrayList<>();BluetoothAdapter current;
            synchronized(this) {
                current=adapter;Iterator<Map.Entry<String,MetadataRegistration>> records=metadata.entrySet().iterator();
                while(records.hasNext()){Map.Entry<String,MetadataRegistration> record=records.next();if(!connected.contains(record.getKey())){expired.add(record.getValue());records.remove();}}
            }
            if(current!=null)for(MetadataRegistration record:expired)try{record.remove.invoke(current,record.device,record.listener);}catch(Exception ignored){}
        }
        @Override public synchronized void close() {
            if(closed)return;closed=true;
            if(registered)try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}
            if(audio!=null)try{audio.unregisterAudioDeviceCallback(audioCallback);}catch(RuntimeException ignored){}
            if(adapter!=null) {
                for(MetadataRegistration registration:metadata.values())try{registration.remove.invoke(adapter,registration.device,registration.listener);}catch(Exception ignored){}
                for(Map.Entry<Integer,BluetoothProfile> e:profiles.entrySet())try{adapter.closeProfileProxy(e.getKey(),e.getValue());}catch(RuntimeException ignored){}
            }
            metadata.clear();profiles.clear();hints.clear();disconnected.clear();pictures.clear();audioKeys.clear();signal=null;
        }
        private static BluetoothDevice lead(BluetoothProfile proxy,BluetoothDevice device) {
            try{int group=((Number)proxy.getClass().getMethod("getGroupId",BluetoothDevice.class).invoke(proxy,device)).intValue();
                if(group>=0){
                    Method getter;
                    try{getter=proxy.getClass().getMethod("getConnectedGroupLeadDevice",int.class);}
                    catch(NoSuchMethodException ignored){getter=proxy.getClass().getMethod("getGroupLeadDevice",int.class);}
                    Object lead=getter.invoke(proxy,group);if(lead instanceof BluetoothDevice)return (BluetoothDevice)lead;
                }}
            catch(Exception ignored){}return device;
        }
        private static boolean bluetoothAudio(int type){return type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ||type==AudioDeviceInfo.TYPE_HEARING_AID||type==AudioDeviceInfo.TYPE_BLE_HEADSET||type==AudioDeviceInfo.TYPE_BLE_SPEAKER||type==30;}
        private static String uniqueName(Map<String,DeviceData> devices,String name) {
            String result=null;for(DeviceData device:devices.values())if(device.name.equals(name)){if(result!=null)return null;result=device.id;}return result;
        }
        private static String address(BluetoothDevice device){try{return device.getAddress().toUpperCase(Locale.ROOT);}catch(RuntimeException ignored){return null;}}
        private static String metadataString(BluetoothDevice device,int key) {
            try{Object bytes=BluetoothDevice.class.getMethod("getMetadata",int.class).invoke(device,key);return bytes instanceof byte[]?new String((byte[])bytes,StandardCharsets.UTF_8).trim():null;}catch(Exception ignored){return null;}
        }
        private static int metadataInt(BluetoothDevice device,int key){String value=metadataString(device,key);try{return value==null?-1:percent(Integer.parseInt(value));}catch(NumberFormatException ignored){return -1;}}
    }
}
