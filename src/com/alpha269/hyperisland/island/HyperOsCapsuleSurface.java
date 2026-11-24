package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** One activity owner: native clip/translation and persistent big/small modules. */
final class HyperOsCapsuleSurface extends FrameLayout {
    final HyperOsCompactFactory.Compact content;
    final String key;
    final double[] values=new double[84], metrics=new double[22];
    final RectF bounds=new RectF();
    private final FrameLayout big,icon;
    private final Paint background=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RenderEffect[] blurs=new RenderEffect[161];
    long animator,activeMask;
    int role=3,startKind=3;
    boolean initialized;
    private float visibility=1;

    HyperOsCapsuleSurface(Context context,String key,HyperOsCompactFactory.Compact content) {
        this(context,key,content,0);
    }
    HyperOsCapsuleSurface(Context context,String key,HyperOsCompactFactory.Compact content,long handle) {
        super(context);this.key=key;this.content=content;
        setWillNotDraw(false);setClipChildren(false);setClipToPadding(false);
        background.setColor(0xff000000);
        setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View view,Outline out){
                out.setRoundRect((int)values[4],(int)values[6],(int)values[5],(int)values[7],radius());
            }
        });setClipToOutline(true);
        big=new FrameLayout(context);big.setClipChildren(false);big.setClipToPadding(false);
        icon=new FrameLayout(context);icon.setClipChildren(false);icon.setClipToPadding(false);
        detach(content.view);detach(content.smallView());
        big.addView(content.view,new LayoutParams(-1,-1));
        icon.addView(content.smallView(),new LayoutParams(-1,-1));
        addView(big);addView(icon);
        animator=handle==0?HyperOsNative.nativeCreateAnimator():handle;
    }

    static void detach(View view){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);}

    void configure(double[] geometry) {
        System.arraycopy(geometry,0,metrics,0,metrics.length);
        int width=(int)metrics[4],height=(int)metrics[1],left=(int)metrics[5],top=(int)metrics[2];
        content.layout((int)metrics[16],(int)metrics[15],(int)metrics[17],height);
        layoutChild(big,left,top,width,height);
        layoutChild(icon,(int)metrics[0]/2-(int)metrics[11]/2,top,(int)metrics[11],height);
    }
    private void layoutChild(View view,int left,int top,int width,int height){
        LayoutParams params=new LayoutParams(width,height);params.leftMargin=left;params.topMargin=top;
        view.setLayoutParams(params);view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        view.layout(left,top,left+width,top+height);
    }
    void snap(int kind){HyperOsNative.nativeSnapAnimator(animator,kind,metrics,0xff000000);initialized=true;frame(0,0);}
    void transition(int kind,float scale){HyperOsNative.nativeTransition(animator,kind,metrics,0xff000000,scale,false);activeMask=1;}
    void preview(int kind,float dx){HyperOsNative.nativeSwipeCapsule(animator,metrics,kind,dx);activeMask=1;}
    void reset(int kind){HyperOsNative.nativeResetCapsule(animator,metrics,kind);activeMask=1;}
    void frame(long delta,long predicted){
        if(animator==0)return;activeMask=HyperOsNative.nativeAnimatorFrame(animator,delta,predicted,1,values);apply();
    }
    void setSceneVisibility(float alpha){visibility=alpha;apply();}
    private void apply(){
        bounds.set((int)values[4]+(float)values[1],(int)values[6]+(float)values[2],(int)values[5]+(float)values[1],(int)values[7]+(float)values[2]);
        float alpha=visibility*Math.max(0,Math.min(1,(float)values[3]));
        setTranslationX((float)values[1]);setTranslationY((float)values[2]);setAlpha(alpha);
        setVisibility(alpha>.001f?VISIBLE:INVISIBLE);invalidateOutline();
        big.setAlpha((float)values[9]);big.setScaleX((float)values[10]);big.setScaleY((float)values[10]);big.setTranslationY((float)values[13]);
        content.setAreaTranslation((float)values[15],(float)values[16]);
        blur(big,values[14]);
        icon.setAlpha((float)values[17]);icon.setScaleX((float)values[18]);icon.setScaleY((float)values[18]);
        icon.setTranslationX((float)values[19]);icon.setTranslationY((float)values[20]);
        blur(icon,values[21]);
        content.setPresentationVisible(values[9]>.001,values[17]>.001);
        if(ownsViews())content.setOwnerVisible(alpha>.001f);
        invalidate();
    }
    boolean ownsViews(){return content.view.getParent()==big||content.smallView().getParent()==icon;}
    boolean needsFrames(){return activeMask!=0||(ownsViews()&&content.needsFrames());}
    private void blur(View view,double ratio){
        // Per-frame HWUI RenderEffect blur disabled for low-lag rendering.
    }
    long takeAnimator(){
        long result=animator;animator=0;
        if(content.view.getParent()==big)detach(content.view);
        if(content.smallView().getParent()==icon)detach(content.smallView());
        detach(this);return result;
    }
    void close(){
        if(animator!=0){HyperOsNative.nativeDestroyAnimator(animator);animator=0;}
        boolean ownedBig=content.view.getParent()==big,ownedSmall=content.smallView().getParent()==icon;
        if(ownedBig||ownedSmall)content.setOwnerVisible(false);
        if(ownedBig)detach(content.view);
        if(ownedSmall)detach(content.smallView());
        detach(this);
    }
    private float radius(){return Math.max(0,Math.min(((float)values[7]-(float)values[6])/2,30*(float)metrics[18]));}
    @Override protected void onDraw(Canvas canvas){if(values[5]>values[4]&&values[7]>values[6])canvas.drawRoundRect((float)values[4],(float)values[6],(float)values[5],(float)values[7],radius(),radius(),background);}
}
