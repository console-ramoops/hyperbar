package com.alpha269.hyperisland.probe;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperIslandView;
import com.alpha269.hyperisland.island.HyperOsResources;
import com.alpha269.hyperisland.island.HyperOsMusicBackground;
import com.alpha269.hyperisland.island.HyperOsAssets;
import com.alpha269.hyperisland.island.HyperOsCardFactory;
import com.alpha269.hyperisland.island.HyperOsLottieView;
import com.alpha269.hyperisland.island.HyperOsMaterial;
import com.alpha269.hyperisland.island.HyperOsNative;
import com.alpha269.hyperisland.island.HyperOsSnapshotBlur;
import com.alpha269.hyperisland.island.IslandHost;
import com.alpha269.hyperisland.island.IslandItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import com.alpha269.hyperisland.island.HyperOsPresentationQueue;

/** Actual source-app classes, resource loader, ViewRoot parent layout and native rendering. */
public final class RootViewIntegrationProbe {
    static Handler MAIN;
    static HyperIslandView view;
    static SurfaceControlViewHost surfaceHost;
    static android.hardware.display.VirtualDisplay ownedDisplay;
    static ImageReader displayFrames;
    static Host host;
    static File output;
    static final JSONArray findings = new JSONArray();
    static final List<IslandItem> scenarios = new ArrayList<>();
    static final AtomicInteger actions = new AtomicInteger();
    static int index, width = 1080, height = 1200;
    static long scenarioStarted;
    static String apkPath;
    static Context ownedContext;
    static int[] lastRenderedColors;
    static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static void set(String name, Object value) throws Exception { field(HyperIslandView.class, name).set(view, value); }
    static void invoke(String name) throws Exception { Method method = HyperIslandView.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(view); }
    public static void main(String[] args) {
        try { if(args.length==1){sheet(new File(args[0]));System.exit(0);} start(args); Looper.loop(); }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }
    static void start(String[] args) throws Exception {
        Looper.prepareMainLooper();
        MAIN = new Handler(Looper.getMainLooper());
        if (Typeface.DEFAULT == null) {
            Method fonts = Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap"); fonts.setAccessible(true); fonts.invoke(null);
        }
        output = new File(args[1]); output.mkdirs();
        Class<?> activity = Class.forName("android.app.ActivityThread");
        Object thread = activity.getMethod("systemMain").invoke(null);
        Context base = (Context)activity.getMethod("getSystemContext").invoke(thread);
        Object manager = base.getPackageManager();
        Field binderField = manager.getClass().getDeclaredField("mPM"); binderField.setAccessible(true);
        final Object original = binderField.get(manager);
        final ApplicationInfo info = new ApplicationInfo(base.getPackageManager().getApplicationInfo("android", 0));
        info.packageName = "com.alpha269.hyperisland"; info.sourceDir = args[0]; info.publicSourceDir = args[0];
        Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), new Class<?>[]{Class.forName("android.content.pm.IPackageManager")}, (p, m, a) -> {
            if (m.getName().equals("getApplicationInfo") && a != null && "com.alpha269.hyperisland".equals(a[0])) return info;
            try { return m.invoke(original, a); } catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        });
        binderField.set(manager, proxy);
        final File cache = new File(output, "owned-cache"); cache.mkdirs();
        final android.content.ContentProvider settings = new android.content.ContentProvider() {
            @Override public boolean onCreate(){return true;}
            @Override public android.os.Bundle call(String method,String arg,android.os.Bundle extras){android.os.Bundle value=new android.os.Bundle();value.putString("value","1.0");return value;}
            @Override public android.os.Bundle call(String authority,String method,String arg,android.os.Bundle extras){return call(method,arg,extras);}
            @Override public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] values,String order){android.database.MatrixCursor cursor=new android.database.MatrixCursor(new String[]{"name","value"});cursor.addRow(new Object[]{"island_animator_duration_scale","1.0"});return cursor;}
            @Override public String getType(android.net.Uri uri){return null;}
            @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues value){throw new UnsupportedOperationException();}
            @Override public int delete(android.net.Uri uri,String selection,String[] values){throw new UnsupportedOperationException();}
            @Override public int update(android.net.Uri uri,android.content.ContentValues value,String selection,String[] values){throw new UnsupportedOperationException();}
        };
        android.content.pm.ProviderInfo settingsInfo=new android.content.pm.ProviderInfo();settingsInfo.authority="settings";
        settings.attachInfo(base,settingsInfo);
        Method localProvider=android.content.ContentProvider.class.getDeclaredMethod("getIContentProvider");localProvider.setAccessible(true);
        for(String namespace:new String[]{"Global","System","Secure"}) {
            Object cacheHolder=field(Class.forName("android.provider.Settings$"+namespace),"sNameValueCache").get(null);
            Object providerHolder=field(cacheHolder.getClass(),"mProviderHolder").get(cacheHolder);
            field(providerHolder.getClass(),"mContentProvider").set(providerHolder,localProvider.invoke(settings));
        }
        final android.content.ContentResolver resolver=android.content.ContentResolver.wrap(settings);
        Context owned = new ContextWrapper(base) {
            @Override public File getCodeCacheDir() { return cache; }
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.ContentResolver getContentResolver(){return resolver;}
        };
        apkPath = args[0];
        ownedContext = owned;
        HyperOsNative.loadPath(args[0] + "!/lib/arm64-v8a/libhyperos_island.so");
        HyperOsNative.nativeInitializePalette(args[0] + "!/lib/arm64-v8a/libMiMainColor.so");
        HyperOsLottieView.install(owned);
        host = new Host();
        HyperOsResources res = HyperOsAssets.plugin(owned);
        HyperOsMaterial mat = new HyperOsMaterial(owned, () -> { if (view != null) view.absorb(); });
        view = new HyperIslandView(owned, host, res, mat, null);
        HyperOsSnapshotBlur snap = new HyperOsSnapshotBlur(view, view::invalidate, request -> {
            Bitmap bitmap = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(0xff405564); return bitmap;
        });
        snap.setGlassEnabled(true);
        set("snapshot", snap);
        view.setGeometry(width, width / 2f, 58, 52, false, 0, false, 0);
        // Windowless ViewRoot owns its surfaces/layout/rendering. This local session
        // disables external input registration; no installed window or process is touched.
        Class<?> windowGlobal = Class.forName("android.view.WindowManagerGlobal");
        Class<?> windowSession = Class.forName("android.view.IWindowSession");
        Object ownedSession = Proxy.newProxyInstance(windowSession.getClassLoader(), new Class<?>[]{windowSession}, (p,m,a) -> {
            Class<?> result=m.getReturnType();
            if(result==boolean.class)return false;if(result==int.class)return 0;if(result==long.class)return 0L;
            if(m.getName().equals("asBinder"))return new Binder();return null;
        });
        field(windowGlobal,"sWindowSession").set(null,ownedSession);
        DisplayManager displays=base.getSystemService(DisplayManager.class);
        displayFrames=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
        displayFrames.setOnImageAvailableListener(reader->{try(Image image=reader.acquireLatestImage()){}},MAIN);
        ownedDisplay=displays.createVirtualDisplay("HyperBar owned card verification",width,height,base.getResources().getDisplayMetrics().densityDpi,
            displayFrames.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        if(ownedDisplay==null)throw new IllegalStateException("Owned display unavailable");
        System.out.println("OWNED DISPLAY state="+ownedDisplay.getDisplay().getState());
        surfaceHost = new SurfaceControlViewHost(owned, ownedDisplay.getDisplay(), new Binder());
        android.view.WindowManager.LayoutParams params=new android.view.WindowManager.LayoutParams(width,height,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED|android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        Field input=field(android.view.WindowManager.LayoutParams.class,"inputFeatures");
        input.setInt(params,field(android.view.WindowManager.LayoutParams.class,"INPUT_FEATURE_NO_INPUT_CHANNEL").getInt(null));
        Method setView=SurfaceControlViewHost.class.getDeclaredMethod("setView",View.class,android.view.WindowManager.LayoutParams.class);
        setView.setAccessible(true);setView.invoke(surfaceHost,view,params);
        IslandItem incoming = new IslandItem("hyperos:incoming:owned-root:1"); incoming.kind = IslandItem.Kind.CALL;
        incoming.title = "Alex"; incoming.subtitle = "Incoming call";
        incoming.actions.add(new IslandItem.Action("Decline", null, null, actions::incrementAndGet, true));
        incoming.actions.add(new IslandItem.Action("Answer", null, null, actions::incrementAndGet, false));
        scenarios.add(incoming);
        IslandItem buds = new IslandItem("hyperos:bluetooth:owned-root"); buds.title = "Earbuds"; buds.subtitle = "Connected";
        buds.metrics = new ArrayList<>(); IslandItem.Metric left = new IslandItem.Metric(); left.type = IslandItem.Metric.TEXT; left.label = "Left"; left.fixed = "84%"; buds.metrics.add(left);
        IslandItem.Metric right = new IslandItem.Metric(); right.type = IslandItem.Metric.TEXT; right.label = "Right"; right.fixed = "—"; buds.metrics.add(right); scenarios.add(buds);
        IslandItem timer = new IslandItem("owned-root-timer"); timer.kind = IslandItem.Kind.TIMER; timer.title = "Timer";
        timer.chronoCountDown = true; timer.chronoBase = SystemClock.elapsedRealtime() + 90000; timer.subtitle = "Focus";
        IslandItem.Action pause = new IslandItem.Action("Pause", null, null, actions::incrementAndGet, false); pause.glyph = 2; timer.actions.add(pause);
        IslandItem.Action stop = new IslandItem.Action("Stop", null, null, actions::incrementAndGet, true); stop.glyph = 6; timer.actions.add(stop); scenarios.add(timer);
        moreScenarios();
        if(args.length>2&&!args[2].isEmpty()){String[] selected=args[2].split(",");List<IslandItem> picked=new ArrayList<>();for(String number:selected)picked.add(scenarios.get(Integer.parseInt(number)));scenarios.clear();scenarios.addAll(picked);}
        if(args.length>3&&args[3].equals("press")){PressIntegrationProbe.start();return;}
        if(args.length>3&&args[3].equals("swipe")){SwipeIntegrationProbe.start();return;}
        if(args.length>3&&args[3].equals("queue")){QueueIntegrationProbe.start();return;}
        if(args.length>3&&args[3].equals("torch")){TorchIntegrationProbe.start();return;}
        if(args.length>3&&args[3].equals("effects")){EffectsIntegrationProbe.start();return;}
        MAIN.postDelayed(RootViewIntegrationProbe::next, 100);
    }
    static IslandItem.Action capability(String label,int glyph,boolean destructive){
        IslandItem.Action action=new IslandItem.Action(label,null,null,actions::incrementAndGet,destructive);action.glyph=glyph;return action;
    }
    static IslandItem sample(String key,String title,String subtitle){
        IslandItem item=new IslandItem("owned-root-"+key);item.title=title;item.subtitle=subtitle;return item;
    }
    static JSONObject content(int type)throws Exception{
        return new JSONObject().put("type",type).put("title","Original layout").put("content","Subtitle with clear glyphs")
            .put("subTitle","Secondary").put("subContent","Detail").put("extraTitle","Label").put("specialTitle","Status")
            .put("primaryText","Primary").put("secondaryText","Secondary").put("highLightText","Status")
            .put("field","Live").put("progress",40).put("colorProgress","#4788FF").put("color","#4788FF").put("points",3);
    }
    static void nativeSample(String key,JSONObject payload)throws Exception{
        IslandItem item=sample(key,"Native reference","Original resource module");
        item.icon=new android.graphics.drawable.ColorDrawable(0xff4788ff);
        item.cardImage=item.icon;item.cardIcon=item.icon;item.appIcon=item.icon;
        item.actions.add(capability("Details",0,false));
        HyperOsCardFactory.registerTemplate(item.key,payload,null,null);scenarios.add(item);
    }
    static void moreScenarios()throws Exception{
        IslandItem active=sample("active-call","Alex","Ongoing call");active.kind=IslandItem.Kind.CALL;active.chronoBase=SystemClock.elapsedRealtime()-65000;
        active.actions.add(capability("Speaker",10,false));active.actions.add(capability("End call",9,true));scenarios.add(active);
        IslandItem longName=sample("incoming-long","Alexandria Catherine Alexander — an extended caller name","Incoming call");longName.kind=IslandItem.Kind.CALL;
        longName.actions.add(capability("Decline",0,true));longName.actions.add(capability("Answer",0,false));
        // The incoming adapter identity is part of its capability grammar.
        IslandItem longIncoming=new IslandItem("hyperos:incoming:owned-root-long:1");longIncoming.kind=longName.kind;longIncoming.title=longName.title;longIncoming.subtitle=longName.subtitle;longIncoming.actions.addAll(longName.actions);scenarios.add(longIncoming);
        IslandItem paused=sample("timer-paused","Timer","Paused");paused.kind=IslandItem.Kind.TIMER;paused.chronoCountDown=true;paused.pausedMs=83000;
        paused.actions.add(capability("Restart",1,false));paused.actions.add(capability("Close",6,true));scenarios.add(paused);
        IslandItem stopwatch=sample("stopwatch","Stopwatch","Running");stopwatch.kind=IslandItem.Kind.TIMER;stopwatch.chronoBase=SystemClock.elapsedRealtime()-120000;
        stopwatch.actions.add(capability("Lap",11,false));stopwatch.actions.add(capability("Pause",2,false));scenarios.add(stopwatch);
        for(int state=0;state<3;state++){
            IslandItem recording=sample("voice-recording-"+state,state==2?"Finished recording":"Recording",null);recording.kind=IslandItem.Kind.RECORDING;recording.recorder=true;
            recording.chronoBase=state==0?SystemClock.elapsedRealtime()-65000:0;recording.pausedMs=state==0?-1:65000;recording.recorderSaved=state==2;
            if(state==2)recording.actions.add(capability("Done",14,false));
            else {recording.actions.add(capability(state==1?"Resume":"Pause",state==1?1:2,false));recording.actions.add(capability("Finish",6,true));}
            scenarios.add(recording);
        }
        IslandItem screen=sample("screen-recording","Screen recording",null);screen.kind=IslandItem.Kind.RECORDING;screen.chronoBase=SystemClock.elapsedRealtime()-45000;screen.actions.add(capability("Stop",6,true));scenarios.add(screen);
        IslandItem torch=sample("flashlight","Flashlight","Flashlight is on");torch.torch=true;torch.torchOn=true;torch.torchToggle=actions::incrementAndGet;scenarios.add(torch);
        IslandItem trip=sample("navigation","Airport","Arrival");trip.kind=IslandItem.Kind.NAVIGATION;trip.metrics=new ArrayList<>();
        IslandItem.Metric metric=new IslandItem.Metric();metric.type=IslandItem.Metric.TEXT;metric.label="Distance";metric.fixed="12 km";trip.metrics.add(metric);trip.actions.add(capability("Details",0,false));scenarios.add(trip);
        IslandItem music=sample("media","Solaris","Aurora Beats");music.kind=IslandItem.Kind.MEDIA;music.isMedia=true;music.mediaDuration=180000;music.mediaPosition=30000;music.mediaPositionUpdateTime=SystemClock.elapsedRealtime();
        Bitmap syntheticArt = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Canvas artCanvas = new Canvas(syntheticArt);
        Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        artPaint.setColor(0xffffb400);
        artCanvas.drawRect(0, 0, 128, 128, artPaint);
        artPaint.setColor(0xff8a3fc1);
        artCanvas.drawCircle(64, 64, 38, artPaint);
        artPaint.setColor(0xff287e91);
        artCanvas.drawCircle(84, 84, 24, artPaint);
        music.art = syntheticArt;
        music.cardImage = new android.graphics.drawable.BitmapDrawable(syntheticArt);
        music.cardImageIsImage = true;
        music.playing=true;music.mediaPrev=actions::incrementAndGet;music.mediaPlayPause=actions::incrementAndGet;music.mediaNext=actions::incrementAndGet;music.mediaSeek=position->actions.incrementAndGet();music.trailingTap=actions::incrementAndGet;music.trailingText="Headphones";scenarios.add(music);
        for(String family:new String[]{"baseInfo","animTextInfo","coverInfo","iconTextInfo","highlightInfo","chatInfo"}){
            JSONObject data=content(2);
            if(family.equals("animTextInfo"))data.put("animIconInfo",new JSONObject().put("src","alarmBig").put("autoplay",false));
            nativeSample("area-a-"+family,new JSONObject().put(family,data));
        }
        nativeSample("area-a-baseInfo-type1",new JSONObject().put("baseInfo",content(1)));
        for(int marker=1;marker<=5;marker++){
            JSONObject data=content(marker).put("pic","owned-icon");
            if(marker==4)data.put("actionInfo",new JSONObject().put("__itemAction",0).put("actionTitle","Details"));
            nativeSample("marker-"+marker,new JSONObject().put("baseInfo",content(2)).put("picInfo",data));
        }
        for(String family:new String[]{"highlightInfoV3","multiProgressInfo","competitionIconTextInfo","progressInfo","hintInfo"}){
            JSONObject data=content(2);
            if(family.equals("competitionIconTextInfo"))data.put("title","2").put("subTitle","1").put("content","Home").put("subContent","Away");
            nativeSample("area-d-"+family,new JSONObject().put("baseInfo",content(2)).put(family,data));
        }
        nativeSample("area-d-hintInfo-type1",new JSONObject().put("baseInfo",content(2)).put("hintInfo",content(1)));
        nativeSample("area-d-text-actions",new JSONObject().put("baseInfo",content(2)).put("textButton",new JSONArray().put(new JSONObject().put("__itemAction",0).put("actionTitle","Details"))));
        IslandItem stopped=sample("stopwatch-paused","Stopwatch","Paused");stopped.kind=IslandItem.Kind.TIMER;stopped.pausedMs=62450;
        stopped.actions.add(capability("Reset",12,true));stopped.actions.add(capability("Resume",1,false));scenarios.add(stopped);
        for(int style=1;style<=3;style++){
            IslandItem clock=sample("clock-business-"+style,style==3?"Timer finished":"Alarm",style==1?"Morning alarm":style==2?"Snoozed":null);
            clock.kind=IslandItem.Kind.TIMER;clock.clockStyle=style;clock.clockText=style==3?null:"07:30";
            if(style==1){clock.actions.add(capability("Snooze",15,false));clock.actions.add(capability("Dismiss",12,true));}
            else clock.actions.add(capability("Close",6,true));scenarios.add(clock);
        }
    }
    static void next() {
        try {
            if (index >= scenarios.size()) { finish(); return; }
            IslandItem item = scenarios.get(index); view.setItems(Collections.singletonList(item));
            if (!view.isExpanded()) view.expand(item);
            scenarioStarted=SystemClock.uptimeMillis();
            MAIN.postDelayed(RootViewIntegrationProbe::capture, 2000);
        } catch (Throwable error) { fail(error); }
    }
    static void capture() {
        try {
            long active=field(HyperIslandView.class,"activeMask").getLong(view);
            if(active!=0&&SystemClock.uptimeMillis()-scenarioStarted<10000){MAIN.postDelayed(RootViewIntegrationProbe::capture,250);return;}
            HyperOsCardFactory.Card card = (HyperOsCardFactory.Card)field(HyperIslandView.class, "card").get(view);
            if (card == null || card.failure != null || view.failure() != null) throw new AssertionError("Root card failure " + view.failure() + "/" + (card == null ? "null" : card.failure));
            if(card.hasReadyIncomingControls()&&!field(HyperIslandView.class,"callCommitted").getBoolean(view)){
                if(SystemClock.uptimeMillis()-scenarioStarted>=10000){
                    JSONObject debug=new JSONObject().put("alpha",view.visibleAlpha()).put("pending",field(HyperIslandView.class,"commitPending").getBoolean(view))
                      .put("generation",field(HyperIslandView.class,"commitGeneration").getLong(view)).put("observerAlive",view.getViewTreeObserver().isAlive()).put("attached",view.isAttachedToWindow()).put("hardware",view.isHardwareAccelerated());
                    try{debug.put("callbacks",String.valueOf(field(android.view.ViewTreeObserver.class,"mFrameCommitCallbacks").get(view.getViewTreeObserver())));}catch(Exception ignored){}
                    try{Object info=field(View.class,"mAttachInfo").get(view);Object renderer=field(info.getClass(),"mThreadedRenderer").get(info);debug.put("renderer",String.valueOf(renderer));if(renderer!=null)debug.put("rendererEnabled",renderer.getClass().getMethod("isEnabled").invoke(renderer));}catch(Exception ignored){}
                    for(Field f:android.view.ViewTreeObserver.class.getDeclaredFields())if(f.getName().contains("Commit")){f.setAccessible(true);debug.put(f.getName(),String.valueOf(f.get(view.getViewTreeObserver())));}
                    try{Object info=field(View.class,"mAttachInfo").get(view);Object root=field(info.getClass(),"mViewRootImpl").get(info);for(String n:new String[]{"mStopped","mViewVisibility","mWindowVisibility","mDirty","mSurface"})try{Object v=field(root.getClass(),n).get(root);debug.put(n,String.valueOf(v));if(v instanceof android.view.Surface)debug.put("validSurface",((android.view.Surface)v).isValid());}catch(Exception ignored){}
                    Object renderer=field(info.getClass(),"mThreadedRenderer").get(info);if(renderer!=null)for(Field f:renderer.getClass().getDeclaredFields())if(f.getName().contains("Enabled")||f.getName().contains("Requested")){f.setAccessible(true);debug.put(f.getName(),String.valueOf(f.get(renderer)));}}catch(Exception ignored){}
                    System.out.println("CALL COMMIT DEBUG "+debug);
                    throw new AssertionError("Incoming controls never committed a rendered frame");
                }
                view.postInvalidateOnAnimation();view.absorb();MAIN.postDelayed(RootViewIntegrationProbe::capture,100);return;
            }
            if(card.hasReadyIncomingControls()){
                view.setSuppressed(false);view.setStatusBarHidden(false);view.setSleepFold(false);view.setShadeFraction(0);
                if(!field(HyperIslandView.class,"callCommitted").getBoolean(view))throw new AssertionError("Identical window state invalidated current incoming frame");
            }
            JSONObject record = new JSONObject().put("item", scenarios.get(index).key).put("attached", view.isAttachedToWindow())
                    .put("hardware", view.isHardwareAccelerated()).put("rootHeight", view.getHeight()).put("wantedHeight", host.wanted)
                    .put("bodyHeight", card.height()).put("cardBeforeParentLayout", bounds(card.view));
            record.put("activeMask",active).put("callCommitted",field(HyperIslandView.class,"callCommitted").getBoolean(view));
            double[] nativeState=(double[])field(HyperIslandView.class,"state").get(view);
            JSONArray stateValues=new JSONArray();for(int slot=0;slot<28;slot++)stateValues.put(nativeState[slot]);record.put("stateValues",stateValues);
            surfaceHost.relayout(width, height);
            view.forceLayout(); view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            record.put("cardAfterParentLayout", bounds(card.view));
            JSONArray tree = new JSONArray(); traverse(view, tree, "root"); record.put("tree", tree);
            record.put("incomingControlsReady", card.hasReadyIncomingControls());
            verifyText(card.view, record);
            findings.put(record);
            render("root-" + index + ".png");
            if (scenarios.get(index).isMedia) {
                HyperOsMusicBackground background = (HyperOsMusicBackground) named(card.view, "media_bg_view");
                if (background == null) throw new AssertionError("No source-backed music background");
                android.graphics.RectF area = new android.graphics.RectF();
                view.getCoverageBounds(area);
                int sampleY = Math.max(0, Math.min(view.getHeight()-1, (int)(area.top + area.height()*.42f)));
                int sampleLeft = Math.max(0, (int)(area.left + area.width()*.35f));
                int sampleRight = Math.min(width-1, (int)(area.left + area.width()*.65f));
                float[] first = null, last = null;
                JSONArray frameTimes = new JSONArray();
                for (int f = 0; f < 30; f++) {
                    Thread.sleep(100);
                    view.doFrame(SystemClock.elapsedRealtimeNanos());
                    render(String.format(java.util.Locale.US, "music-background-frame-%02d.png", f));
                    frameTimes.put(SystemClock.elapsedRealtime());
                    float red=0, green=0, blue=0; int count=0;
                    for (int x=sampleLeft; x<=sampleRight; x+=4) {
                        int pixel=lastRenderedColors[sampleY*width+x];
                        red+=(pixel>>16)&255;green+=(pixel>>8)&255;blue+=pixel&255;count++;
                    }
                    if(count==0)throw new AssertionError("Empty music background sample region");
                    last=new float[]{red/count,green/count,blue/count};
                    if(first==null)first=last;
                }
                if(field(HyperOsMusicBackground.class,"failed").getBoolean(background))
                    throw new AssertionError("Original music background shader failed");
                long backgroundHandle=field(HyperOsMusicBackground.class,"handle").getLong(background);
                if(backgroundHandle==0)throw new AssertionError("Music background native engine missing");
                float difference=Math.abs(first[0]-last[0])+Math.abs(first[1]-last[1])+Math.abs(first[2]-last[2]);
                if(difference<.5f) {
                    View shaderLayer=(View)field(HyperOsMusicBackground.class,"layer").get(background);
                    float[] nativePacket=new float[5];
                    HyperOsNative.nativeMusicBackgroundFrame(backgroundHandle,SystemClock.elapsedRealtime(),0,0,nativePacket);
                    System.err.println("MUSIC_DIAG color="+Integer.toHexString(field(HyperOsMusicBackground.class,"color").getInt(background))
                        +" scenario="+scenarios.get(index).key+" title="+scenarios.get(index).title
                        +" itemArt="+scenarios.get(index).art
                        +" backgroundArt="+field(HyperOsMusicBackground.class,"artwork").get(background)
                        +" paletteTask="+field(HyperOsMusicBackground.class,"paletteTask").get(background)
                        +" shown="+background.isShown()+" size="+background.getWidth()+"x"+background.getHeight()
                        +" layer="+shaderLayer.getWidth()+"x"+shaderLayer.getHeight()
                        +" shader="+field(shaderLayer.getClass(),"shader").get(shaderLayer)
                        +" packet="+java.util.Arrays.toString(nativePacket)
                        +" first="+java.util.Arrays.toString(first)+" last="+java.util.Arrays.toString(last));
                }
                if(scenarios.get(index).playing && difference<.5f)
                    throw new AssertionError("Music background interior did not animate: "+difference);
                record.put("music_background_hardware",new JSONObject()
                    .put("shader","music_panel_bg.agsl").put("frameCount",30)
                    .put("interiorRgbDelta",difference).put("nativeHandleValid",true)
                    .put("notificationGlow",false).put("frameTimesMs",frameTimes));
                JSONObject checks=record.getJSONObject("music_background_hardware");
                background.bind(scenarios.get(index).art,false);
                float[] pausedFrame=new float[5],afterPause=new float[5];
                HyperOsNative.nativeMusicBackgroundFrame(backgroundHandle,SystemClock.elapsedRealtime(),0,0,pausedFrame);
                Thread.sleep(120);
                if(HyperOsNative.nativeMusicBackgroundFrame(backgroundHandle,SystemClock.elapsedRealtime(),0,0,afterPause)
                    || !java.util.Arrays.equals(pausedFrame,afterPause))throw new AssertionError("Music background did not pause");
                background.bind(scenarios.get(index).art,true);
                float[] resumedFrame=new float[5];
                if(!HyperOsNative.nativeMusicBackgroundFrame(backgroundHandle,SystemClock.elapsedRealtime(),0,0,resumedFrame)
                    || Math.abs(resumedFrame[0]-pausedFrame[0])>.02f)throw new AssertionError("Music resume caught up paused time");
                if(HyperOsNative.nativeMusicBackgroundFrame(backgroundHandle,SystemClock.elapsedRealtime(),0,0,new float[4]))
                    throw new AssertionError("Music frame accepted short output");
                long destroyed=HyperOsNative.nativeCreateMusicBackground();
                HyperOsNative.nativeDestroyMusicBackground(destroyed);
                if(HyperOsNative.nativeMusicBackgroundFrame(destroyed,SystemClock.elapsedRealtime(),0,0,new float[5]))
                    throw new AssertionError("Destroyed music background handle accepted");
                checks.put("pauseFreezesFrame",true).put("resumeNoCatchUp",true)
                    .put("shortOutputRejected",true).put("destroyedHandleRejected",true);
                view.collapse();
                final JSONObject musicRecord=record;
                final IslandItem musicItem=scenarios.get(index);
                final long compactStart=SystemClock.elapsedRealtime();
                MAIN.postDelayed(()->verifyCompactMusic(musicRecord,musicItem,compactStart,false),1000);
                return;
            }
            if (index == 0 && card.hasReadyIncomingControls()) {
                View button = named(card.view, "answer_call");
                if (button == null) throw new AssertionError("Missing answer control");
                int before = actions.get(); float[] point = {button.getWidth()/2f, button.getHeight()/2f};
                toRoot(button, point); long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
                MotionEvent up = MotionEvent.obtain(now, now+25, MotionEvent.ACTION_UP, point[0], point[1], 0);
                boolean acceptedDown = view.dispatchTouchEvent(down), acceptedUp = view.dispatchTouchEvent(up); down.recycle(); up.recycle();
                record.put("tap", new JSONObject().put("x", point[0]).put("y", point[1]).put("down", acceptedDown).put("up", acceptedUp).put("actions", actions.get()-before));
                MAIN.postDelayed(()->{try{record.getJSONObject("tap").put("actionsAfterCallback",actions.get()-before);}catch(Exception error){fail(error);}},100);
            }
            if (index == 1 && scenarios.get(index).metrics != null) {
                IslandItem.Metric third = new IslandItem.Metric(); third.type = IslandItem.Metric.TEXT; third.label = "Case"; third.fixed = "62%";
                scenarios.get(index).metrics.add(third); scenarios.get(index).contentVersion++;
                view.setItems(Collections.singletonList(scenarios.get(index)));
                record.put("afterMetricAdded", new JSONObject().put("bodyHeight", card.height()).put("wantedHeight", host.wanted).put("geometryHeight", ((double[])field(HyperIslandView.class,"geometry").get(view))[8]));
            }
            index++; MAIN.postDelayed(RootViewIntegrationProbe::next, 200);
        } catch (Throwable error) { fail(error); }
    }
    static void verifyCompactMusic(JSONObject record,IslandItem item,long started,boolean changed) {
        try {
            view.doFrame(SystemClock.elapsedRealtimeNanos());
            Object compact=field(HyperIslandView.class,"mainCompact").get(view);
            List<?> animations=(List<?>)field(compact.getClass(),"musicAnimations").get(compact);
            if(animations.isEmpty())throw new AssertionError("No compact music visualizer");
            Object waveform=animations.get(0);
            int top=field(waveform.getClass(),"topColor").getInt(waveform);
            int bottom=field(waveform.getClass(),"bottomColor").getInt(waveform);
            Bitmap small=Bitmap.createScaledBitmap(item.art,64,64,true);
            int[] pixels=new int[4096],expected=new int[4];small.getPixels(pixels,0,64,0,0,64,64);
            if(small!=item.art)small.recycle();
            HyperOsNative.nativeArtworkPalette(pixels,64,64,expected);
            if(top!=expected[1]||bottom!=expected[2]) {
                if(SystemClock.elapsedRealtime()-started>8000)throw new AssertionError("Compact colors did not follow album art: "+Integer.toHexString(top)+" expected "+Integer.toHexString(expected[1]));
                MAIN.postDelayed(()->verifyCompactMusic(record,item,started,changed),150);return;
            }
            JSONObject checks=record.getJSONObject("music_background_hardware");
            checks.put(changed?"changedCompactTop":"compactTop",Integer.toHexString(top));
            checks.put(changed?"changedCompactBottom":"compactBottom",Integer.toHexString(bottom));
            render(changed?"compact-music-changed.png":"compact-music-original.png");
            if(!changed) {
                checks.put("compactMatchesAlbumPalette",true);
                Bitmap nextArt=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);nextArt.eraseColor(0xffb845ca);
                item.art=nextArt;item.contentVersion++;
                view.setItems(Collections.singletonList(item));
                long now=SystemClock.elapsedRealtime();
                MAIN.postDelayed(()->verifyCompactMusic(record,item,now,true),150);return;
            }
            if(checks.getString("compactTop").equals(checks.getString("changedCompactTop")))
                throw new AssertionError("Different album kept old compact colors");
            checks.put("compactUpdatesOnArtworkChange",true);
            long frame=field(waveform.getClass(),"lastStep").getLong(waveform);
            MAIN.postDelayed(()->{
                try {
                    view.doFrame(SystemClock.elapsedRealtimeNanos());render("compact-music-animation.png");
                    if(field(waveform.getClass(),"lastStep").getLong(waveform)<=frame)
                        throw new AssertionError("Compact music animation stopped");
                    checks.put("compactAnimationAdvances",true);
                    index++;MAIN.postDelayed(RootViewIntegrationProbe::next,200);
                } catch(Throwable error){fail(error);}
            },250);
        } catch(Throwable error){fail(error);}
    }
    static void verifyText(View body,JSONObject record)throws Exception{
        JSONArray nodes=new JSONArray();traverse(body,nodes,"card");JSONArray clipped=new JSONArray();
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.getJSONObject(i);
            if(node.optInt("visible")!=View.VISIBLE||node.optString("text").isEmpty()||!node.has("layoutHeight"))continue;
            if(node.optInt("viewBaseline")+node.optInt("glyphTop")<0 || node.optInt("viewBaseline")+node.optInt("glyphBottom")>node.optInt("height"))clipped.put(node.getString("path")+":"+node.getString("text"));
        }
        record.put("clippedText",clipped);
    }
    static JSONObject bounds(View view) throws Exception {
        return new JSONObject().put("left", view.getLeft()).put("top", view.getTop()).put("width", view.getWidth()).put("height", view.getHeight())
                .put("tx", view.getTranslationX()).put("ty", view.getTranslationY()).put("sx",view.getScaleX()).put("sy",view.getScaleY()).put("alpha",view.getAlpha());
    }
    static void traverse(View view, JSONArray tree, String path) throws Exception {
        JSONObject record = bounds(view).put("path", path).put("class", view.getClass().getName()).put("visible", view.getVisibility());
        if (view instanceof TextView) {
            TextView text=(TextView)view;android.graphics.Paint.FontMetricsInt metrics=text.getPaint().getFontMetricsInt();
            android.graphics.Rect glyph=new android.graphics.Rect();String value=text.getText().toString();text.getPaint().getTextBounds(value,0,value.length(),glyph);
            record.put("glyphTop",glyph.top).put("glyphBottom",glyph.bottom).put("viewBaseline",text.getBaseline()).put("minimumHeight",text.getMinHeight());
            record.put("text",text.getText()).put("textSize",text.getTextSize()).put("paintTextSize",text.getPaint().getTextSize())
                .put("fakeBold",text.getPaint().isFakeBoldText()).put("fontTop",metrics.top).put("fontBottom",metrics.bottom)
                .put("fontAscent",metrics.ascent).put("fontDescent",metrics.descent).put("includePadding",text.getIncludeFontPadding());
            if(text.getLayout()!=null&&text.getLayout().getLineCount()>0)record.put("lineBaseline",text.getLayout().getLineBaseline(0))
                .put("layoutHeight",text.getLayout().getHeight()).put("lineAscent",text.getLayout().getLineAscent(0)).put("lineDescent",text.getLayout().getLineDescent(0));
        }
        tree.put(record);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) traverse(((ViewGroup)view).getChildAt(i),tree,path+"/"+i);
    }
    static View named(View view, String name) {
        for(String owner:new String[]{"com.android.incallui","com.android.systemui","miui.systemui.plugin"}) {
            int id=view.getResources().getIdentifier(name,"id",owner);
            if(id!=0){View found=view.findViewById(id);if(found!=null)return found;}
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=named(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}return null;
    }
    static void toRoot(View view,float[] point){
        View current=view;
        while(current!=RootViewIntegrationProbe.view){current.getMatrix().mapPoints(point);point[0]+=current.getLeft();point[1]+=current.getTop();current=(View)current.getParent();}
    }
    static void render(String name) throws Exception {
        int h=Math.max(1,view.getHeight()); RenderNode node=new RenderNode("owned-HyperBar-root-probe");node.setPosition(0,0,width,h);
        RecordingCanvas canvas=node.beginRecording(width,h);try{canvas.drawColor(0xff18212a);view.draw(canvas);}finally{node.endRecording();}
        ImageReader reader=ImageReader.newInstance(width,h,PixelFormat.RGBA_8888,2);
        HardwareRenderer renderer=new HardwareRenderer();renderer.setSurface(reader.getSurface());renderer.setContentRoot(node);
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
        Image image=null;for(int attempt=0;attempt<20&&image==null;attempt++){Thread.sleep(10);image=reader.acquireLatestImage();}
        if(image==null)throw new AssertionError("No owned root render buffer");
        Image.Plane plane=image.getPlanes()[0];ByteBuffer data=plane.getBuffer();int stride=plane.getRowStride(),pixelStride=plane.getPixelStride();int[] colors=new int[width*h];
        byte[] rgba = new byte[width * h * 4];
        for(int y=0;y<h;y++)for(int x=0;x<width;x++){
            int offset=y*stride+x*pixelStride;int r=data.get(offset)&255,g=data.get(offset+1)&255,b=data.get(offset+2)&255,a=data.get(offset+3)&255;
            colors[y*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
            int p = (y*width+x)*4;
            rgba[p] = (byte)r; rgba[p+1] = (byte)g; rgba[p+2] = (byte)b; rgba[p+3] = (byte)a;
        }
        lastRenderedColors = colors;
        if(name.endsWith(".png")){
            String rawName = name.substring(0, name.length() - 4) + ".rgba";
            try(FileOutputStream raw = new FileOutputStream(new File(output, rawName))){raw.write(rgba);}
        }
        Bitmap bitmap=Bitmap.createBitmap(colors,width,h,Bitmap.Config.ARGB_8888);try(FileOutputStream file=new FileOutputStream(new File(output,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}bitmap.recycle();image.close();renderer.destroy();reader.close();node.discardDisplayList();
    }
    static void sheet(File directory)throws Exception{
        File[] files=directory.listFiles((dir,name)->name.startsWith("root-")&&name.endsWith(".png"));
        java.util.Arrays.sort(files,(a,b)->Integer.parseInt(a.getName().substring(5,a.getName().length()-4))-Integer.parseInt(b.getName().substring(5,b.getName().length()-4)));
        int width=360,height=270,columns=4;Bitmap bitmap=Bitmap.createBitmap(width*columns,height*((files.length+columns-1)/columns),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff18212a);android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        for(int i=0;i<files.length;i++){
            Bitmap source=android.graphics.BitmapFactory.decodeFile(files[i].getAbsolutePath());
            canvas.drawBitmap(source,new android.graphics.Rect(0,100,1080,910),new android.graphics.Rect((i%columns)*width,(i/columns)*height,(i%columns+1)*width,(i/columns+1)*height),paint);source.recycle();
        }
        try(FileOutputStream stream=new FileOutputStream(new File(directory,"full-card-sheet.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}bitmap.recycle();
    }
    static void finish() throws Exception {try(FileOutputStream file=new FileOutputStream(new File(output,"composed-findings.json"))){file.write(findings.toString(2).getBytes("UTF-8"));}sheet(output);surfaceHost.release();if(ownedDisplay!=null)ownedDisplay.release();if(displayFrames!=null)displayFrames.close();System.out.println("Actual source-app composed root captures="+findings.length()+", actions="+actions.get());System.exit(0);}
    static void fail(Throwable error){error.printStackTrace(System.err);try{if(surfaceHost!=null)surfaceHost.release();if(ownedDisplay!=null)ownedDisplay.release();if(displayFrames!=null)displayFrames.close();}catch(Throwable ignored){}System.exit(1);}
    static final class Host implements IslandHost {
        int wanted,opens;HyperOsPresentationQueue presentation;
        @Override public boolean hasMinimized(){return presentation!=null&&!presentation.layout().hidden.isEmpty();}@Override public void onCycle(int direction){}@Override public void onExpandedChanged(boolean value){}
        @Override public void onSwipe(int direction){if(presentation!=null){presentation.swipe(direction);view.setItems(presentation.layout().visible,0,presentation.layout().hidden);}}
        @Override public HyperOsPresentationQueue.Layout previewSwipe(int direction){return presentation==null?null:presentation.preview(direction);}
        @Override public void onIslandFrame(){}@Override public void onItemAction(IslandItem item,IslandItem.Action action){if(action.runnable!=null)action.runnable.run();}
        @Override public void onMinimize(IslandItem item){}@Override public void onOpenItem(IslandItem item,android.graphics.RectF from){opens++;}
        @Override public void onRestore(){onSwipe(1);}@Override public void onShadeDragEvent(MotionEvent event){}@Override public void onShadeDragStart(float x,float y,long time){}
        @Override public boolean onShadeFade(float alpha,float scale,float x,float y){return false;}@Override public void onWindowHeightNeeded(int value){wanted=value;}
    }
}
