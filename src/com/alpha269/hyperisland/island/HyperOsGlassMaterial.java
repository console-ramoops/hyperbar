package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source Bionics shader over the exact F16 normal cache and two backdrop images. */
public final class HyperOsGlassMaterial implements AutoCloseable {
    private static final String[] GROUPS={"uGeoPx_pos_size","uRot_Scale","uAlpha_shapeEdgeThickReflect",
        "uRefractIORStrengthLight_clrpow","uGlassColor","uInClrWhiteMix_bgClrSatBri","uDarkRangeDark_hit",
        "uDirLightDir_angleRange","uRefractBlurScale_invEdgePow","uDirLightIntensityOpp_inBottom_lumiAmount",
        "uLuminanceValues","uSatBri_burn_unshade"};
    private final RuntimeShader glass,opacity;
    private final HyperOsGlassNormalCache normals;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] uniforms=new float[51];
    private final Matrix sceneToLocal=new Matrix();
    private final Shader empty=new RuntimeShader("half4 main(float2 p){return half4(0);}");
    private Bitmap refract,color;
    private BitmapShader refractShader,colorShader;
    private int refractGeneration,colorGeneration;
    private HyperOsGlassNormalCache.Lease normal;
    public HyperOsGlassMaterial(String source,HyperOsGlassNormalCache normalCache){
        if(source==null||normalCache==null)throw new IllegalArgumentException("Missing original glass shader/normal cache");
        glass=new RuntimeShader(source);normals=normalCache;
        glass.setInputShader("uMaskTex",empty);
        opacity=new RuntimeShader("uniform shader child;uniform float alpha;half4 main(float2 p){return child.eval(p)*alpha;}");
        opacity.setFloatUniform("alpha",1);
    }
    public static HyperOsGlassMaterial create(Context context,Runnable changed)throws Exception{
        String source=new String(Files.readAllBytes(HyperOsAssets.asset(context,"glass_material.sksl").toPath()),StandardCharsets.UTF_8);
        return new HyperOsGlassMaterial(source,HyperOsGlassNormalCache.create(context,changed));
    }
    /** Window-local draw adapter. Capture scale is represented in each raw image
     * shader matrix, leaving the native shader's texture scale at one. */
    public boolean draw(Canvas canvas,RectF bounds,Bitmap refracted,Bitmap background,Matrix sceneToWindow,
        float density,float alpha){
        if(canvas==null||!canvas.isHardwareAccelerated()||bounds==null||bounds.isEmpty()
            ||refracted==null||background==null||refracted.isRecycled()||background.isRecycled())return false;
        float radius=Math.min(bounds.height()/2,30*density);
        HyperOsGlassNormalCache.Key key=HyperOsGlassNormalCache.key(radius,72,3.8f,Math.min(bounds.width(),bounds.height())/2-radius<=1);
        if(normal==null||!normal.key.equals(key)){
            HyperOsGlassNormalCache.Lease next=normals.request(radius,72,3.8f,key.circle());
            if(next==null)return false;
            if(normal!=null)normal.close();normal=next;
        }
        if(refract!=refracted||refractShader==null||refractGeneration!=refracted.getGenerationId()){
            refract=refracted;refractGeneration=refracted.getGenerationId();refractShader=new BitmapShader(refract,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);refractShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        if(color!=background||colorShader==null||colorGeneration!=background.getGenerationId()){
            color=background;colorGeneration=background.getGenerationId();colorShader=new BitmapShader(color,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);colorShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        if(sceneToWindow==null)sceneToLocal.reset();else sceneToLocal.set(sceneToWindow);
        sceneToLocal.postTranslate(-bounds.left,-bounds.top);
        refractShader.setLocalMatrix(sceneToLocal);colorShader.setLocalMatrix(sceneToLocal);
        HyperOsNative.nativeGlassUniforms(bounds.width(),bounds.height(),0,1,1,1,false,uniforms);
        for(int i=0;i<12;i++)glass.setFloatUniform(GROUPS[i],uniforms[i*4],uniforms[i*4+1],uniforms[i*4+2],uniforms[i*4+3]);
        glass.setFloatUniform("uUseMaskTex",uniforms[48]);glass.setFloatUniform("uLuminanceCurveMode",uniforms[49]);glass.setFloatUniform("uNmlZAAThreshold",uniforms[50]);
        glass.setInputBuffer("uSdfTex",normal.shader());glass.setInputBuffer("uRefractTex",refractShader);glass.setInputBuffer("uBgColorTex",colorShader);
        opacity.setInputShader("child",glass);opacity.setFloatUniform("alpha",Math.max(0,Math.min(1,alpha)));paint.setShader(opacity);
        int saved=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.drawRect(0,0,bounds.width(),bounds.height(),paint);canvas.restoreToCount(saved);
        return true;
    }
    public String failure(){return normals.failure();}
    public void clearScene(){refract=null;color=null;refractShader=null;colorShader=null;if(normal!=null){normal.close();normal=null;}}
    @Override public void close(){clearScene();normals.close();}
}
