package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Original calling_light_expand_wave on a background child, anchored to the answer control. */
public final class HyperOsCallBackground extends View implements HyperOsCardFactory.AnimationTarget {
    private final Paint paint=new Paint();
    private final int[] ownPosition=new int[2],anchorPosition=new int[2];
    private RuntimeShader shader;
    private View anchor;
    private long handle;
    private float time;
    private boolean ownerVisible=true,running,released;
    public HyperOsCallBackground(Context context){super(context);}
    public void setAnchor(View anchor){this.anchor=anchor;}
    @Override public void setAnimation(HyperOsResources resources,String source,boolean autoplay,boolean loop,int number){
        release();released=false;
        try(InputStream input=resources.getResources().openRawResource(resources.id("raw","calling_light_expand_wave"));
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            shader=new RuntimeShader(output.toString("UTF-8"));
            shader.setInputShader("uTex",new RuntimeShader("half4 main(float2 p){return half4(0);}"));
            shader.setFloatUniform("uAlpha",1f);shader.setFloatUniform("uScale",2f);
            paint.setShader(shader);handle=HyperOsNative.nativeCreateWave();
            if(handle==0)throw new IllegalStateException("Could not create incoming-call wave clock");
            visibility();
        }catch(Exception failure){release();throw new IllegalStateException("Could not prepare the original incoming-call background",failure);}
    }
    @Override public void step(long now){
        if(!running||released)return;time=HyperOsNative.nativeWaveFrame(handle,now,0);
        shader.setFloatUniform("uTime",time);invalidate();
    }
    @Override public void setOwnerVisible(boolean visible){ownerVisible=visible;visibility();}
    private void visibility(){
        if(released||handle==0)return;
        boolean visible=ownerVisible&&isShown()&&getWindowVisibility()==View.VISIBLE;if(running==visible)return;
        running=visible;time=HyperOsNative.nativeWaveFrame(handle,SystemClock.elapsedRealtime(),visible?1:2);
        shader.setFloatUniform("uTime",time);invalidate();
    }
    @Override public boolean active(){return !released&&running;}
    @Override public void release(){
        released=true;running=false;if(handle!=0)HyperOsNative.nativeDestroyWave(handle);
        handle=0;shader=null;paint.setShader(null);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();visibility();}
    @Override protected void onDetachedFromWindow(){setOwnerVisible(false);super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);visibility();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);visibility();}
    @Override protected void onDraw(Canvas canvas){
        if(released||!running||shader==null||getWidth()<=0||getHeight()<=0||!canvas.isHardwareAccelerated())return;
        float x=getWidth();
        if(anchor!=null){getLocationOnScreen(ownPosition);anchor.getLocationOnScreen(anchorPosition);
            x=Math.max(0,anchorPosition[0]-ownPosition[0]+anchor.getWidth()/2f);}
        shader.setFloatUniform("uResolution",(float)getWidth(),(float)getHeight());
        shader.setFloatUniform("uRippleOrigin",x,(float)(getHeight()/2));
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
    }
}
