package com.alpha269.hyperisland.island;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.animation.PathInterpolator;

/** Exact finite task-window geometry from ColorOS plugin b5/i and b5/y. */
public final class HyperOsActivityMotion {
    private static final PathInterpolator POSITION=new PathInterpolator(.3f,0,.1f,1);
    private static final PathInterpolator SOFT=new PathInterpolator(.33f,0,.67f,1);
    private static final PathInterpolator CAPSULE_FIRST=new PathInterpolator(.47f,0,.83f,.96f);
    private static final PathInterpolator CAPSULE_LAST=new PathInterpolator(.17f,.03f,.1f,1);
    private static final PathInterpolator CARD_FIRST=new PathInterpolator(.3f,0,.83f,1);
    private static final PathInterpolator CARD_LAST=new PathInterpolator(.17f,0,.1f,1);
    private static final PathInterpolator CARD_CORNERS=new PathInterpolator(.17f,.17f,.1f,1);
    private static final PathInterpolator NAV_OUT=new PathInterpolator(.2f,0,1,1);
    private static final PathInterpolator NAV_IN=new PathInterpolator(0,0,0,1);
    public final RectF start,end;
    public final boolean card;
    public final long duration;
    public final float split,startCorner,endCorner,middleWidth,middleHeight,middleCorner;

    public HyperOsActivityMotion(RectF start,RectF end,float density,float startCorner,float endCorner,boolean card,boolean fastCard) {
        this.start=new RectF(start);this.end=new RectF(end);this.card=card;
        this.startCorner=startCorner;this.endCorner=endCorner;
        duration=card && fastCard?400:500;
        split=card && fastCard?150f/400f:200f/500f;
        // b5/i.m truncates dp multiplication. Card middle resources use
        // getDimensionPixelSize and therefore round positive dimensions.
        middleWidth=card?Math.round(280*density):(int)(150*density);
        middleHeight=card?Math.round(380*density):(int)(230*density);
        middleCorner=(int)(36*density);
    }

    /** u4/r uses a fixed55x20dp launch rect centered on the selected capsule. */
    public static RectF capsuleStart(RectF actualCapsule,float density) {
        int cx=(int)actualCapsule.centerX(),cy=(int)actualCapsule.centerY();
        int halfW=Math.round(55*density)/2,halfH=Math.round(20*density)/2;
        return new RectF(cx-halfW,cy-halfH,cx+halfW,cy+halfH);
    }

    public State sample(long elapsedMillis){return sampleFraction(clamp((float)elapsedMillis/(float)duration));}
    public State sampleFraction(float progress) {
        float t=clamp(progress),first=clamp(t/split),last=clamp((t-split)/(1-split));
        float cx=end.centerX(),cy=lerp(start.centerY(),end.centerY(),POSITION.getInterpolation(t));
        float width,height,corner,alpha;
        if(t<=split) {
            float p=(card?CARD_FIRST:CAPSULE_FIRST).getInterpolation(first);
            width=lerp(start.width(),middleWidth,p);height=lerp(start.height(),middleHeight,p);
            corner=startCorner;
            if(!card && p>=.5f)corner=lerp(startCorner,middleCorner,p/.5f-1);
            alpha=card?POSITION.getInterpolation(first):1;
        } else {
            float p=(card?CARD_LAST:CAPSULE_LAST).getInterpolation(last);
            width=lerp(middleWidth,end.width(),p);height=lerp(middleHeight,end.height(),p);
            corner=lerp(card?startCorner:middleCorner,endCorner,card?CARD_CORNERS.getInterpolation(last):p);
            alpha=1;
        }
        State state=new State();
        state.bounds.set((int)(cx-width/2),(int)(cy-height/2),(int)(cx+width/2),(int)(cy+height/2));
        state.corner=corner;state.alpha=alpha;state.sourceAlpha=card?1-alpha:1;
        state.sourceTracksApp=card && t<=split;
        state.blurRadius=(int)(400*SOFT.getInterpolation(t));
        state.mirrorScale=1-(POSITION.getInterpolation(t)*.100000024f);
        state.cornerWeight=t<.1f?1.1f:2;
        state.behindAlpha=Math.max(1-NAV_OUT.getInterpolation(clamp((500*t)/133)),.7f);
        float navIn=clamp((500*t-(500-266))/133);
        state.navAlpha=navIn>0?NAV_IN.getInterpolation(navIn):1-NAV_OUT.getInterpolation(clamp((500*t)/133));
        return state;
    }

    /** Native s4/h uses uniform scale and center crop for its opening task. */
    public static SurfaceState surface(State state,Rect appScreenBounds) {
        float appW=appScreenBounds.width(),appH=appScreenBounds.height();
        if(appW<=0 || appH<=0)throw new IllegalArgumentException("App task bounds are empty");
        float appCx=appScreenBounds.exactCenterX(),appCy=appScreenBounds.exactCenterY();
        float scale=Math.max(state.bounds.width()/appW,state.bounds.height()/appH);
        if(!(scale>0))throw new IllegalArgumentException("App task scale is invalid");
        Matrix matrix=new Matrix();matrix.setScale(scale,scale,appCx,appCy);
        float scaledH=scale*appH;
        float tx=state.bounds.centerX()-appCx;
        float ty=(scaledH-appH)/2+(state.bounds.top-appScreenBounds.top)-(scaledH-state.bounds.height())/2;
        matrix.postTranslate(tx,ty);
        RectF crop=new RectF(state.bounds);crop.offset(-appScreenBounds.left,-appScreenBounds.top);
        Matrix inverse=new Matrix();if(!matrix.invert(inverse))throw new IllegalArgumentException("Task matrix is singular");inverse.mapRect(crop);
        SurfaceState result=new SurfaceState();result.matrix.set(matrix);
        result.crop.set(Math.round(crop.left),Math.round(crop.top),Math.round(crop.right),Math.round(crop.bottom));
        result.scale=scale;result.corner=state.corner/scale;result.alpha=state.alpha;
        return result;
    }

    public static final class State {
        public final RectF bounds=new RectF();
        public float corner,alpha,sourceAlpha,mirrorScale,cornerWeight,behindAlpha,navAlpha;
        public int blurRadius;
        public boolean sourceTracksApp;
    }
    public static final class SurfaceState {
        public final Matrix matrix=new Matrix();public final Rect crop=new Rect();
        public float scale,corner,alpha;
    }
    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
    private static float lerp(float from,float to,float p){return (to-from)*p+from;}
}
