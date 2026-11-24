package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Original HyperOS classic material, evaluated by the hardware graphics runtime.
 * The caller owns capture/blur and supplies its scene-to-canvas coordinate map.
 * No image is captured, copied, sampled on the CPU, or recycled by this class.
 */
public final class HyperOsMaterial implements AutoCloseable {
    public static final String BLEND_ASSET="assets/hyperos/material_blend.sksl";
    private static final String OPACITY_SOURCE=
        "uniform shader child;uniform float alpha;half4 main(float2 xy){return child.eval(xy)*alpha;}";
    private static final int[] ORDINARY_COLORS={0xff7a7a7a,0xff338000,0xb30d0d0d};
    private static final int[] PROMOTED_COLORS={0xff999999,0xffccff00,0x99ffffff};
    private final RuntimeShader[] ordinary=new RuntimeShader[3];
    private final RuntimeShader[] promoted=new RuntimeShader[3];
    private final RuntimeShader ordinaryOpacity;
    private final RuntimeShader promotedOpacity;
    private final RuntimeShader fallbackOpacity;
    private final Shader black=new RuntimeShader("half4 main(float2 xy){return half4(0,0,0,1);}");
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix sceneMatrix=new Matrix();
    private final float[] matrixValues=new float[9];
    private final float[] previousMatrixValues=new float[9];
    private Bitmap scene;
    private BitmapShader sceneShader;
    private int sceneGeneration;
    private boolean matrixValid;
    private int bindingCount;
    private float ordinaryAlpha=1f,promotedAlpha=1f,fallbackAlpha=1f;
    private HyperOsGlassMaterial glass;

    public HyperOsMaterial(Context context)throws IOException {
        this(context,(Runnable)null);
    }
    public HyperOsMaterial(Context context,Runnable changed)throws IOException {
        this(readModuleSource(context));
        try{glass=HyperOsGlassMaterial.create(context,changed);}catch(Exception failure){throw new IOException("Could not prepare original HyperOS glass material",failure);}
    }

    /** Source directory contains the exact extracted shader-000aae0b.sksl. */
    public HyperOsMaterial(Context context,File sourceDirectory)throws IOException {
        this(read(new FileInputStream(new File(sourceDirectory,"shader-000aae0b.sksl"))));
    }

    /** Source is used unchanged; it must be the recovered native blend shader. */
    public HyperOsMaterial(String originalBlendSource) {
        if(originalBlendSource==null || originalBlendSource.length()==0)
            throw new IllegalArgumentException("Missing HyperOS material source");
        initialize(ordinary,originalBlendSource,new int[]{100,106,3},ORDINARY_COLORS);
        initialize(promoted,originalBlendSource,new int[]{107,106,3},PROMOTED_COLORS);
        ordinaryOpacity=opacity(ordinary[2]);
        promotedOpacity=opacity(promoted[2]);
        fallbackOpacity=opacity(black);
    }

    private static RuntimeShader opacity(Shader child) {
        RuntimeShader result=new RuntimeShader(OPACITY_SOURCE);
        result.setInputShader("child",child);
        result.setFloatUniform("alpha",1f);
        return result;
    }

    private void initialize(RuntimeShader[] chain,String source,int[] modes,int[] colors) {
        Shader child=black;
        for(int i=0;i<3;i++) {
            RuntimeShader shader=new RuntimeShader(source);
            // The source declares raw half4, rather than layout(color), uniforms.
            int color=colors[i];
            shader.setFloatUniform("blendColor",((color>>>16)&255)/255f,
                ((color>>>8)&255)/255f,(color&255)/255f,((color>>>24)&255)/255f);
            shader.setIntUniform("mode",modes[i]);
            shader.setIntUniform("hasCustom",0);
            shader.setFloatUniform("customColor",0f,0f,0f,0f);
            shader.setInputShader("backgroundImage",child);
            shader.setInputShader("customImage",black);
            chain[i]=shader;child=shader;
        }
    }

    private static String readModuleSource(Context context)throws IOException {
        try {
            ApplicationInfo module=context.getPackageManager()
                .getApplicationInfo("com.alpha269.hyperisland",0);
            try(ZipFile zip=new ZipFile(module.sourceDir)) {
                ZipEntry entry=zip.getEntry(BLEND_ASSET);
                if(entry==null)throw new IOException("Missing HyperOS blend asset: "+BLEND_ASSET);
                return read(zip.getInputStream(entry));
            }
        } catch(android.content.pm.PackageManager.NameNotFoundException failure) {
            throw new IOException("Could not locate Dynamic Island resources",failure);
        }
    }

    private static String read(InputStream input)throws IOException {
        try(InputStream stream=input;ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;
            while((count=stream.read(buffer))!=-1)output.write(buffer,0,count);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private boolean bind(Bitmap bitmap,Matrix mapping) {
        if(bitmap==null || bitmap.isRecycled() || bitmap.getWidth()<=0 || bitmap.getHeight()<=0)
            return false;
        int generation=bitmap.getGenerationId();
        boolean rebound=scene!=bitmap || sceneGeneration!=generation || sceneShader==null;
        if(rebound) {
            scene=bitmap;sceneGeneration=generation;
            sceneShader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            bindingCount++;matrixValid=false;
        }
        if(mapping==null)sceneMatrix.reset();else sceneMatrix.set(mapping);
        sceneMatrix.getValues(matrixValues);
        boolean changed=!matrixValid;
        for(int i=0;i<9;i++) {
            float value=matrixValues[i];
            if(Float.isNaN(value) || Float.isInfinite(value))return false;
            if(value!=previousMatrixValues[i])changed=true;
        }
        if(changed) {
            sceneShader.setLocalMatrix(sceneMatrix);
            System.arraycopy(matrixValues,0,previousMatrixValues,0,9);matrixValid=true;
        }
        if(rebound || changed)bindSceneGraph(sceneShader);
        return true;
    }

    private void bindSceneGraph(Shader source) {
        bindChain(ordinary,ordinaryOpacity,source);
        bindChain(promoted,promotedOpacity,source);
    }
    private static void bindChain(RuntimeShader[] chain,RuntimeShader opacity,Shader source) {
        // Each parent retains the child's native shader snapshot. Propagate an
        // input/matrix change through the whole graph before recording a draw.
        Shader child=source;
        for(RuntimeShader shader:chain) {shader.setInputShader("backgroundImage",child);child=shader;}
        opacity.setInputShader("child",child);
    }

    /**
     * bounds and sceneToWindow use the Canvas's logical coordinate space. If the
     * Canvas already has a card-local transform, the caller includes that inverse
     * transform in the supplied map. originalScene is reserved for source Bionics;
     * this verified classic branch needs only the caller's Rust-blurred scene.
     */
    public synchronized void draw(Canvas canvas,RectF bounds,Bitmap blurredScene,
        Bitmap originalScene,Matrix sceneToWindow,float density,float appearance,boolean isPromoted) {
        if(canvas==null || bounds==null || bounds.isEmpty())return;
        if(!Float.isFinite(density) || density<=0f || !Float.isFinite(appearance))
            throw new IllegalArgumentException("Invalid HyperOS material geometry or alpha");
        float alpha=Math.max(0f,Math.min(1f,appearance));
        if(alpha==0f)return;
        boolean ready=canvas.isHardwareAccelerated() && bind(blurredScene,sceneToWindow);
        if(canvas.isHardwareAccelerated()) {
            RuntimeShader shader=ready?(isPromoted?promotedOpacity:ordinaryOpacity):fallbackOpacity;
            float previous=ready?(isPromoted?promotedAlpha:ordinaryAlpha):fallbackAlpha;
            if(previous!=alpha) {
                shader.setFloatUniform("alpha",alpha);
                if(!ready)fallbackAlpha=alpha;else if(isPromoted)promotedAlpha=alpha;else ordinaryAlpha=alpha;
            }
            paint.setShader(shader);paint.setAlpha(255);
        } else {
            // Source material is unavailable on a software Canvas.
            paint.setShader(null);paint.setColor(0xff000000);paint.setAlpha((int)(255f*alpha));
        }
        float radius=Math.min(bounds.height()/2f,30f*density);
        canvas.drawRoundRect(bounds,radius,radius,paint);
    }

    public synchronized void clearScene() {
        bindSceneGraph(black);
        scene=null;sceneShader=null;sceneGeneration=0;matrixValid=false;
        if(glass!=null)glass.clearScene();
    }
    public boolean usesGlass(){return glass!=null;}
    public boolean drawGlass(Canvas canvas,RectF bounds,Bitmap refract,Bitmap color,Matrix sceneToWindow,float density,float appearance){
        return glass!=null&&glass.draw(canvas,bounds,refract,color,sceneToWindow,density,appearance);
    }
    @Override public void close() {clearScene();if(glass!=null){glass.close();glass=null;}}
}
