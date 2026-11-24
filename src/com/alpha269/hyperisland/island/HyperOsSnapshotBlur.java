package com.alpha269.hyperisland.island;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Raw screenshot/blur cache. This class never installs a Drawable or tint. */
public final class HyperOsSnapshotBlur implements AutoCloseable {
    private static final int DOWNSAMPLE=4;
    private static final int CLASSIC_RADIUS=100;
    private static final long RETRY_MS=1500;
    private static final long GEOMETRY_CHECK_MS=500;
    private static final long MAX_PIXELS=4_000_000;
    private static final ThreadFactory THREAD_FACTORY=new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread=new Thread(task,"HyperOS-Rust-snapshot");thread.setDaemon(true);return thread;
        }
    };

    /** Capture transfers ownership of its returned Bitmap to this cache. */
    public interface Capture {Bitmap capture(Request request)throws Exception;}

    public static final class Request {
        public final int displayId,rotation,fullWidth,fullHeight,width,height,blurRadius;
        public final ClassLoader loader;
        public final boolean glass;
        private final long generation;
        private Request(int displayId,int rotation,int fullWidth,int fullHeight,
            ClassLoader loader,long generation,boolean glass) {
            this.displayId=displayId;this.rotation=rotation;this.fullWidth=fullWidth;this.fullHeight=fullHeight;
            this.width=Math.max(1,fullWidth/DOWNSAMPLE);this.height=Math.max(1,fullHeight/DOWNSAMPLE);
            this.blurRadius=Math.max(2,Math.round(CLASSIC_RADIUS/(2f*DOWNSAMPLE)));
            this.loader=loader;this.generation=generation;this.glass=glass;
        }
        private boolean sameGeometry(Request other) {
            return other!=null && displayId==other.displayId && rotation==other.rotation
                && fullWidth==other.fullWidth && fullHeight==other.fullHeight&&glass==other.glass;
        }
    }

    private static final class Snapshot {
        final Bitmap bitmap,refract,color;
        final Request request;
        Snapshot(Bitmap bitmap,Bitmap refract,Bitmap color,Request request){this.bitmap=bitmap;this.refract=refract;this.color=color;this.request=request;}
    }
    private static final class Processed {final Bitmap classic,refract,color;Processed(Bitmap a,Bitmap b,Bitmap c){classic=a;refract=b;color=c;}}

    private static final class SemCapture implements Capture {
        private Method screenshot;
        private Object manager;
        @Override public Bitmap capture(Request request)throws Exception {
            if(screenshot==null) {
                Class<?> type=Class.forName("com.samsung.android.view.SemWindowManager",true,request.loader);
                manager=type.getMethod("getInstance").invoke(null);
                screenshot=type.getMethod("screenshot",int.class,int.class,boolean.class,Rect.class,
                    int.class,int.class,boolean.class,int.class,boolean.class);
            }
            // Screenshot below the status-bar window, excluding the island.
            return (Bitmap)screenshot.invoke(manager,request.displayId,2000,false,new Rect(),
                request.width,request.height,false,0,true);
        }
    }

    private final WeakReference<View> owner;
    private final WeakReference<Runnable> invalidated;
    private final Handler main;
    private final Capture capture;
    private final boolean testCapture;
    private final ThreadPoolExecutor worker;
    private final int[] location=new int[2];
    private final View.OnLayoutChangeListener layoutListener;
    private Snapshot snapshot;
    private Request requested,pending;
    private boolean active,busy,closed,glassEnabled;
    private long generation,lastActive,lastAttempt,lastGeometryCheck;
    private final Runnable glassRefresh=new Runnable(){@Override public void run(){
        synchronized(HyperOsSnapshotBlur.this){if(closed||!active||!glassEnabled)return;if(!busy)refresh();}
        main.postDelayed(this,100);
    }};

    public HyperOsSnapshotBlur(View owner,Runnable invalidated) {
        this(owner,invalidated,null);
    }

    /** Injected capture permits an unattached owned probe View. */
    public HyperOsSnapshotBlur(View owner,Runnable invalidated,Capture capture) {
        if(owner==null)throw new IllegalArgumentException("Snapshot owner cannot be null");
        this.owner=new WeakReference<>(owner);this.invalidated=new WeakReference<>(invalidated);
        main=new Handler(Looper.getMainLooper());testCapture=capture!=null;
        this.capture=capture==null?new SemCapture():capture;
        worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1),THREAD_FACTORY);
        worker.allowCoreThreadTimeOut(true);
        layoutListener=new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                checkGeometry(true);
            }
        };
        owner.addOnLayoutChangeListener(layoutListener);
    }

    private boolean onMain(){return Looper.myLooper()==main.getLooper();}
    public void setGlassEnabled(boolean value){
        if(!onMain()){main.post(()->setGlassEnabled(value));return;}
        synchronized(this){if(closed||glassEnabled==value)return;glassEnabled=value;generation++;snapshot=null;requested=null;pending=null;}
        main.removeCallbacks(glassRefresh);if(active){refresh();if(value)main.postDelayed(glassRefresh,100);}
    }

    public void setActive(final boolean value) {
        if(!onMain()){main.post(new Runnable(){@Override public void run(){setActive(value);}});return;}
        boolean opened,resumed;
        synchronized(this) {
            if(closed)return;
            if(!value&&!active)return;
            long now=SystemClock.uptimeMillis();opened=value&&!active;
            resumed=value&&active&&lastActive>0&&now-lastActive>RETRY_MS;
            active=value;if(value)lastActive=now;
            if(!value) {
                generation++;pending=null;snapshot=null;requested=null;
            }
        }
        if(!value){main.removeCallbacks(glassRefresh);notifyChanged();return;}
        if(opened&&glassEnabled){main.removeCallbacks(glassRefresh);main.postDelayed(glassRefresh,100);}
        if(opened||resumed)refresh();else checkGeometry(false);
    }

    public void refresh() {
        if(!onMain()){main.post(new Runnable(){@Override public void run(){refresh();}});return;}
        View view=owner.get();
        if(view==null){close();return;}
        synchronized(this) {
            if(closed||!active)return;
            Request request=geometry(view,generation+1);
            if(request==null)return;
            generation++;lastAttempt=SystemClock.uptimeMillis();lastGeometryCheck=lastAttempt;
            if(requested!=null&&!request.sameGeometry(requested))snapshot=null;
            requested=request;
            if(busy){pending=request;return;}
            pending=null;
            begin(request);
        }
    }

    private void checkGeometry(final boolean force) {
        if(!onMain()){main.post(new Runnable(){@Override public void run(){checkGeometry(force);}});return;}
        boolean needs=false;
        synchronized(this) {
            if(closed||!active)return;
            long now=SystemClock.uptimeMillis();
            if(!force&&now-lastGeometryCheck<GEOMETRY_CHECK_MS)return;
            lastGeometryCheck=now;View view=owner.get();
            if(view==null){close();return;}
            Request current=geometry(view,generation);
            if(current==null)return;
            needs=!current.sameGeometry(requested)||(snapshot==null&&!busy&&now-lastAttempt>RETRY_MS);
        }
        if(needs)refresh();
    }

    private Request geometry(View view,long version) {
        if(!testCapture&&!view.isAttachedToWindow())return null;
        int width,height,displayId=0,rotation=0;
        Display display=view.getDisplay();
        if(display!=null) {
            Point size=new Point();display.getRealSize(size);width=size.x;height=size.y;
            displayId=display.getDisplayId();rotation=display.getRotation();
        } else if(testCapture) {
            width=view.getResources().getDisplayMetrics().widthPixels;
            height=view.getResources().getDisplayMetrics().heightPixels;
        } else return null;
        if(width<1||height<1)return null;
        Request request=new Request(displayId,rotation,width,height,view.getContext().getClassLoader(),version,glassEnabled);
        if((long)request.width*request.height>MAX_PIXELS)return null;
        return request;
    }

    /** Caller holds this lock; there is at most one running capture/blur task. */
    private void begin(final Request request) {
        busy=true;
        try {
            worker.execute(new Runnable() {
                @Override public void run() {
                    Processed result=null;
                    try {
                        if(owner.get()!=null){Bitmap shot=capture.capture(request);result=request.glass?processGlass(shot,request.blurRadius):new Processed(process(shot,request.blurRadius),null,null);}
                    } catch(Throwable ignored) { /* Material's opaque fallback handles unavailable capture. */ }
                    final Processed completed=result;
                    if(!main.post(new Runnable(){@Override public void run(){publish(request,completed);}}))
                        recycle(completed);
                }
            });
        } catch(RejectedExecutionException ignored) {busy=false;pending=null;}
    }

    private static Bitmap process(Bitmap shot,int radius)throws Exception {
        if(shot==null)throw new IllegalStateException("No screenshot");
        Bitmap readable=null;
        try {
            if(shot.isRecycled())throw new IllegalStateException("Screenshot was recycled");
            readable=shot.getConfig()==Bitmap.Config.HARDWARE?shot.copy(Bitmap.Config.ARGB_8888,false):shot;
            if(readable==null||readable.isRecycled())throw new IllegalStateException("Unreadable screenshot");
            int width=readable.getWidth(),height=readable.getHeight();long count=(long)width*height;
            if(width<1||height<1||count>MAX_PIXELS)throw new IllegalArgumentException("Screenshot dimensions out of range");
            int[] pixels=new int[(int)count];readable.getPixels(pixels,0,width,0,0,width,height);
            try {HyperOsNative.nativeBlurArgb(pixels,width,height,radius);}
            catch(Throwable nativeFailure) {
                Class<?> fallback=Class.forName("com.alpha269.hyperisland.island.SnapshotPixels");
                Method blur=fallback.getDeclaredMethod("blur",int[].class,int.class,int.class,int.class);
                blur.setAccessible(true);blur.invoke(null,pixels,width,height,radius);
            }
            return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        } finally {if(readable!=shot)recycle(readable);recycle(shot);}
    }
    private static Processed processGlass(Bitmap shot,int classicRadius)throws Exception{
        if(shot==null||shot.isRecycled())throw new IllegalStateException("No screenshot");
        Bitmap readable=null,a=null,b=null,c=null;
        try{
            readable=shot.getConfig()==Bitmap.Config.HARDWARE?shot.copy(Bitmap.Config.ARGB_8888,false):shot;
            int w=readable.getWidth(),h=readable.getHeight();if((long)w*h>MAX_PIXELS)throw new IllegalArgumentException("Screenshot dimensions out of range");
            int[] raw=new int[w*h];readable.getPixels(raw,0,w,0,0,w,h);
            a=blurCopy(raw,w,h,classicRadius);
            b=blurCopy(raw,w,h,Math.max(2,Math.round(50/(2f*DOWNSAMPLE))));
            c=blurCopy(raw,w,h,Math.max(2,Math.round(500/(2f*DOWNSAMPLE))));
            return new Processed(a,b,c);
        }catch(Throwable failed){recycle(a);recycle(b);recycle(c);throw failed;}
        finally{if(readable!=shot)recycle(readable);recycle(shot);}
    }
    private static Bitmap blurCopy(int[] raw,int w,int h,int radius)throws Exception{
        int[] pixels=raw.clone();HyperOsNative.nativeBlurArgb(pixels,w,h,radius);return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }

    private void publish(Request request,Processed completed) {
        boolean changed=false;
        synchronized(this) {
            busy=false;View view=owner.get();
            boolean valid=!closed&&active&&view!=null&&(testCapture||view.isAttachedToWindow())
                &&request.generation==generation;
            if(valid) {
                Request current=geometry(view,generation);
                if(current==null||!request.sameGeometry(current)) {
                    valid=false;recycle(completed);completed=null;
                    refresh();
                }
            }
            if(valid) {
                // Published textures may still be borrowed by a recorded GPU
                // shader. Release references; never recycle those live bitmaps.
                snapshot=completed==null||completed.classic.isRecycled()?null:new Snapshot(completed.classic,completed.refract,completed.color,request);
                changed=true;
            } else recycle(completed);
            if(!closed&&active&&pending!=null&&!busy) {
                Request next=pending;pending=null;begin(next);
            }
        }
        if(changed)notifyChanged();
    }

    public synchronized Bitmap bitmap() {
        if(snapshot==null)return null;
        if(snapshot.bitmap.isRecycled()){snapshot=null;return null;}
        return snapshot.bitmap;
    }
    public synchronized Bitmap refractBitmap(){return snapshot==null||snapshot.refract==null||snapshot.refract.isRecycled()?null:snapshot.refract;}
    public synchronized Bitmap colorBitmap(){return snapshot==null||snapshot.color==null||snapshot.color.isRecycled()?null:snapshot.color;}

    /** Map scene pixels to the owner's logical Canvas/window coordinate space. */
    public synchronized void sceneToWindow(Matrix out) {
        if(out==null)throw new IllegalArgumentException("Missing scene Matrix");
        out.reset();Snapshot current=snapshot;View view=owner.get();
        if(current==null||view==null||current.bitmap.isRecycled())return;
        out.setScale((float)current.request.fullWidth/current.bitmap.getWidth(),
            (float)current.request.fullHeight/current.bitmap.getHeight());
        view.getLocationOnScreen(location);out.postTranslate(-location[0],-location[1]);
    }

    private void notifyChanged() {
        View view=owner.get();if(view!=null)view.invalidate();
        Runnable callback=invalidated.get();if(callback!=null)callback.run();
    }
    private static void recycle(Bitmap bitmap) {if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    private static void recycle(Processed images){if(images!=null){recycle(images.classic);recycle(images.refract);recycle(images.color);}}

    @Override public void close() {
        synchronized(this) {
            if(closed)return;
            closed=true;active=false;generation++;pending=null;requested=null;snapshot=null;
        }
        main.removeCallbacks(glassRefresh);
        worker.shutdownNow();
        if(onMain())removeListener();else main.post(new Runnable(){@Override public void run(){removeListener();}});
    }
    private void removeListener(){View view=owner.get();if(view!=null)view.removeOnLayoutChangeListener(layoutListener);}
}
