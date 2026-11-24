package com.alpha269.hyperisland.island;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/** Original per-level corner masks, retained instead of regenerated mipmaps. */
public final class HyperOsCornerMask implements HyperOsGlassNormalCache.MaskProvider,AutoCloseable {
    private static final int[] SIDES={512,256,128,64,32,16,8,4,2,1};
    private static final String SAMPLER=source();
    private final Bitmap[] bitmaps=new Bitmap[10];
    private final BitmapShader[] samples=new BitmapShader[10];
    private volatile boolean closed;
    public HyperOsCornerMask(byte[] originalRgba){
        int[] pixels=new int[349525];HyperOsNative.nativeCornerMips(originalRgba,pixels);
        int offset=0;
        for(int i=0;i<10;i++){
            int side=SIDES[i];bitmaps[i]=Bitmap.createBitmap(pixels,offset,side,side,side,Bitmap.Config.ARGB_8888);offset+=side*side;
            samples[i]=new BitmapShader(bitmaps[i],Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            samples[i].setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
    }
    private static String source(){
        StringBuilder b=new StringBuilder("uniform half radius;uniform half circle;");
        for(int i=0;i<10;i++)b.append("uniform shader mip").append(i).append(';');
        b.append("half4 sampleLevel(int level,float2 p){");
        for(int i=0;i<9;i++)b.append("if(level==").append(i).append(")return mip").append(i).append(".eval(p/").append(1<<i).append(".0);");
        b.append("return mip9.eval(p/512.0);}");
        b.append("half4 main(float2 p){half r=circle>0.5?radius:radius*1.5286649465560913;float lod=clamp(log2(512.0/float(r)),0.0,9.0);int low=int(floor(lod));int high=low<9?low+1:9;return mix(sampleLevel(low,p),sampleLevel(high,p),fract(lod));}");
        return b.toString();
    }
    @Override public Shader forKey(HyperOsGlassNormalCache.Key key){
        if(closed)throw new IllegalStateException("Original corner masks closed");
        RuntimeShader shader=new RuntimeShader(SAMPLER);shader.setFloatUniform("radius",key.radius());shader.setFloatUniform("circle",key.circle()?1f:0f);
        for(int i=0;i<10;i++)shader.setInputBuffer("mip"+i,samples[i]);return shader;
    }
    /** Original levels remain inspectable by owned validation without data changes. */
    public Bitmap level(int level){if(closed)throw new IllegalStateException("Original corner masks closed");return bitmaps[level];}
    @Override public void close(){if(closed)return;closed=true;for(Bitmap bitmap:bitmaps)if(bitmap!=null)bitmap.recycle();}
}
