package com.alpha269.hyperisland.island;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Original normal shader and F16 images behind the source-derived Rust LRU. */
public final class HyperOsGlassNormalCache implements AutoCloseable {
    public interface MaskProvider { Shader forKey(Key key)throws Exception; }
    public static final class Key {
        private final int[] data;
        private Key(int[] data){this.data=data;}
        public boolean circle(){return data[0]!=0;}
        public float radius(){return data[1];}
        public float edge(){return data[2]/10f;}
        public float power(){return data[3]/100f;}
        public int side(){return data[4];}
        @Override public int hashCode(){return Arrays.hashCode(data);}
        @Override public boolean equals(Object other){return other instanceof Key&&Arrays.equals(data,((Key)other).data);}
    }
    private static final class Image {
        final HardwareBuffer buffer;
        final Bitmap bitmap;
        final BitmapShader shader;
        final AtomicInteger references=new AtomicInteger(1);
        Image(HardwareBuffer buffer,Bitmap bitmap){
            this.buffer=buffer;this.bitmap=bitmap;
            shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        void release(){if(references.decrementAndGet()==0){bitmap.recycle();buffer.close();}}
    }
    /** A caller lease mirrors the original SkImage reference held by a shader. */
    public static final class Lease implements AutoCloseable {
        public final Key key;
        private Image image;
        private Lease(Key key,Image image){this.key=key;this.image=image;image.references.incrementAndGet();}
        public BitmapShader shader(){if(image==null)throw new IllegalStateException("Normal image lease is closed");return image.shader;}
        public Bitmap bitmap(){if(image==null)throw new IllegalStateException("Normal image lease is closed");return image.bitmap;}
        @Override public void close(){if(image!=null){Image value=image;image=null;value.release();}}
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"HyperOS-normal-image");t.setDaemon(true);return t;});
    private final String originalSource;
    private final MaskProvider masks;
    private final Runnable changed;
    private final Map<Long,Image> images=new HashMap<>();
    private final Set<Key> pending=new HashSet<>();
    private final long[] retired=new long[21];
    private long owner,nextImage=1;
    private volatile boolean closed;
    private RuntimeShader normal;
    private String failure;
    private AutoCloseable ownedMasks;

    /** Original shader and every original mip level from verified app assets. */
    public static HyperOsGlassNormalCache create(Context context,Runnable changed)throws Exception{
        String source=new String(Files.readAllBytes(HyperOsAssets.asset(context,"glass_normal.sksl").toPath()),StandardCharsets.UTF_8);
        HyperOsCornerMask masks=new HyperOsCornerMask(Files.readAllBytes(HyperOsAssets.asset(context,"smooth_corner_mips.bin").toPath()));
        HyperOsGlassNormalCache result=new HyperOsGlassNormalCache(source,masks,changed);result.ownedMasks=masks;return result;
    }

    public HyperOsGlassNormalCache(String originalNormalSource,MaskProvider masks,Runnable changed){
        if(originalNormalSource==null||masks==null)throw new IllegalArgumentException("Missing original normal shader/mask source");
        this.originalSource=originalNormalSource;this.masks=masks;this.changed=changed;
        owner=HyperOsNative.nativeCreateNormalCache();HyperOsNative.nativeNormalCacheContext(owner,1,retired);
    }
    public String failure(){return failure;}
    public int cachedImages(){return images.size();}
    public static Key key(float radius,float edge,float power,boolean circle){
        int[] data=new int[5];HyperOsNative.nativeNormalKey(radius,edge,power,circle,data);return new Key(data);
    }
    /** Returns an owned image lease when ready; image generation never blocks UI. */
    public Lease request(float radius,float edge,float power,boolean circle){
        if(Looper.myLooper()!=main.getLooper())throw new IllegalStateException("Normal image owner requires main thread");
        if(closed)return null;Key key=key(radius,edge,power,circle);
        long id=HyperOsNative.nativeNormalCacheGet(owner,key.data);
        Image image=images.get(id);if(image!=null)return new Lease(key,image);
        if(pending.add(key)){
            long newId=nextImage++;
            worker.execute(()->{Image generated=null;Throwable error=null;
                try{if(!closed)generated=render(key);}catch(Throwable failed){error=failed;}
                final Image value=generated;final Throwable failed=error;
                main.post(()->{
                    pending.remove(key);
                    if(closed){if(value!=null)value.release();return;}
                    if(failed!=null){failure=failed.toString();if(changed!=null)changed.run();return;}
                    if(value==null)return;
                    images.put(newId,value);
                    int count=HyperOsNative.nativeNormalCachePut(owner,key.data,newId,retired);
                    for(int i=0;i<count;i++){Image old=images.remove(retired[i]);if(old!=null)old.release();}
                    failure=null;if(changed!=null)changed.run();
                });
            });
        }
        return null;
    }
    private Image render(Key key)throws Exception{
        if(normal==null)normal=new RuntimeShader(originalSource);
        normal.setFloatUniform("uR_edge_edgePow",key.radius(),key.edge(),key.power());
        normal.setFloatUniform("uIsCircle",key.circle()?1f:0f);
        Shader mask=masks.forKey(key);
        if(mask instanceof BitmapShader)normal.setInputBuffer("uCornerMaskTex",(BitmapShader)mask);
        else normal.setInputShader("uCornerMaskTex",mask);
        HardwareBuffer buffer=HardwareBuffer.create(key.side(),key.side(),HardwareBuffer.RGBA_FP16,1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareBufferRenderer renderer=null;RenderNode node=null;Bitmap bitmap=null;
        try{
            renderer=new HardwareBufferRenderer(buffer);node=new RenderNode("HyperOS original quarter normals");
            node.setPosition(0,0,key.side(),key.side());renderer.setContentRoot(node);
            Paint paint=new Paint();paint.setShader(normal);paint.setBlendMode(BlendMode.SRC);
            RecordingCanvas canvas=node.beginRecording(key.side(),key.side());
            canvas.drawRect(0,0,key.side(),key.side(),paint);node.endRecording();
            CountDownLatch done=new CountDownLatch(1);AtomicReference<HardwareBufferRenderer.RenderResult> result=new AtomicReference<>();
            renderer.obtainRenderRequest().setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB)).draw(Runnable::run,value->{result.set(value);done.countDown();});
            if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Normal image render timeout");
            HardwareBufferRenderer.RenderResult value=result.get();
            if(value.getStatus()!=HardwareBufferRenderer.RenderResult.SUCCESS)throw new IllegalStateException("Normal image GPU render failed");
            try(SyncFence fence=value.getFence()){if(!fence.await(Duration.ofSeconds(5)))throw new IllegalStateException("Normal image GPU fence timeout");}
            bitmap=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
            if(bitmap==null)throw new IllegalStateException("Normal image GPU buffer import failed");
            return new Image(buffer,bitmap);
        }catch(Throwable failure){if(bitmap!=null)bitmap.recycle();buffer.close();throw failure;}
        finally{if(renderer!=null)renderer.close();if(node!=null)node.discardDisplayList();}
    }
    @Override public void close(){
        if(Looper.myLooper()!=main.getLooper()){main.post(this::close);return;}
        if(closed)return;closed=true;
        worker.execute(()->{normal=null;if(ownedMasks!=null)try{ownedMasks.close();}catch(Exception ignored){}});
        worker.shutdown();HyperOsNative.nativeDestroyNormalCache(owner);owner=0;
        for(Image image:images.values())image.release();images.clear();pending.clear();
    }
}
