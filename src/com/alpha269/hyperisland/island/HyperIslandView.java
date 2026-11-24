package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RecordingCanvas;
import android.graphics.Shader;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Source-built Android presentation driven by the verified native HyperOS state model. */
public final class HyperIslandView extends FrameLayout implements Choreographer.FrameCallback {
    private final IslandHost controller;
    private final Choreographer frames;
    private final float density;
    private final double[] state = new double[84], geometry = new double[22];
    private final double[] smallState = new double[84];
    private final int[] capsule = new int[9];
    private final RectF outline = new RectF(), smallBounds = new RectF();
    private final Path clip = new Path();
    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sceneMap = new Matrix();
    private final RenderEffect[] blurs = new RenderEffect[161];
    private final ArrayList<IslandItem> items = new ArrayList<>();
    private final ArrayList<IslandItem> hiddenItems = new ArrayList<>();
    private final Map<String,HyperOsCompactFactory.Compact> compactOwners = new LinkedHashMap<>();
    private final ArrayList<HyperOsCapsuleSurface> outgoingSurfaces = new ArrayList<>();
    private ViewGroup remote;
    private FrameLayout contentLayer, compact, mainSmall, small;
    private HyperOsCapsuleSurface secondarySurface, incomingSurface;
    private IslandItem incomingItem;
    private HyperOsCompactFactory.Compact mainCompact, secondaryCompact;
    private String secondaryKey;
    private HyperOsResources resources;
    private HyperOsCardFactory.Card card;
    private HyperOsMaterial material;
    private HyperOsSnapshotBlur snapshot;
    private HyperOsMusicGlow musicGlow;
    private IslandItem primary, secondary, expandedItem;
    private long animator, lastFrame, activeMask;
    private long smallAnimator, smallActiveMask;
    private boolean touchActive,touchMoved,touchInSeek,releasedGesture;
    private boolean touchAllowed,swipeActive,releasedDrag,collapseDrag;
    private boolean releasedQueueSwipe;
    private boolean capsulePreviewActive;
    private boolean mainCapsulePreview,smallCapsulePreview,incomingCapsulePreview;
    private int handoffMainTransition=4,handoffSmallTransition=3;
    private int queueDirection;
    private int touchDirection;
    private int touchPointer=-1,touchSurface,pressedSurface,tapSurface;
    private float touchX,touchY;
    private String touchKey,tapKey;
    private boolean preparing, prepared, pendingFrame, expanded, suppressed, statusHidden, sleeping;
    private boolean nativeHidden = true, commitPending, callCommitted, closing;
    private float shade;
    private int screenWidth, islandHeight, islandY, cutoutWidth, cutoutHeight, expandedWidth;
    private float centerY;
    private String failure, committedKey;
    private long commitGeneration;
    private Runnable frameCommit;
    private android.view.ViewTreeObserver commitObserver;
    private final RenderNode launchBody=new RenderNode("HyperIsland launch source");
    private final RectF launchBounds=new RectF();
    private boolean launchPrepared, launchStarted;
    private float launchWidth,launchHeight,launchAlpha=1,launchCorner;

    public HyperIslandView(Context context, IslandHost host) {
        super(context);
        remote=this;
        controller = host; frames = Choreographer.getInstance();
        density = context.getResources().getDisplayMetrics().density;
        black.setColor(0xff000000);
        setWillNotDraw(false);
    }

    /** Dependencies may be prepared independently for an owned offscreen scene. */
    public HyperIslandView(Context context,IslandHost host,HyperOsResources sourceResources,
        HyperOsMaterial sourceMaterial,HyperOsSnapshotBlur sourceSnapshot) {
        this(context,host,sourceResources,sourceMaterial,sourceSnapshot,null);
    }

    public HyperIslandView(Context context,IslandHost host,HyperOsResources sourceResources,
        HyperOsMaterial sourceMaterial,HyperOsSnapshotBlur sourceSnapshot,HyperOsMusicGlow sourceMusicGlow) {
        this(context,host);
        if(sourceResources==null||sourceMaterial==null)throw new IllegalArgumentException("Missing scene resources/material");
        resources=sourceResources;material=sourceMaterial;snapshot=sourceSnapshot;
        musicGlow=sourceMusicGlow;
        animator=HyperOsNative.nativeCreateAnimator();
        installLaunchCallbacks();prepared=true;attachContent();
    }


    void setRemoteLayer(ViewGroup layer) {
        remote = layer;
        if (prepared) attachContent();
    }

    private void prepare() {
        if (preparing || prepared) return;
        preparing = true;
        HyperOsAssets.prepare(getContext(),new HyperOsAssets.Prepared() {
            @Override public void ready() {
                preparing = false;
                try {
                    HyperOsNative.load(getContext());
                    android.content.pm.ApplicationInfo info = getContext().getPackageManager()
                        .getApplicationInfo("com.alpha269.hyperisland",0);
                    HyperOsNative.nativeInitializePalette(info.sourceDir+"!/lib/arm64-v8a/libMiMainColor.so");
                    resources = HyperOsAssets.plugin(getContext());
                    HyperOsLottieView.install(getContext());
                    material = new HyperOsMaterial(getContext(),HyperIslandView.this::requestFrame);
                    snapshot = new HyperOsSnapshotBlur(HyperIslandView.this,HyperIslandView.this::requestFrame);
                    snapshot.setGlassEnabled(material.usesGlass());
                    animator = HyperOsNative.nativeCreateAnimator();
                    installLaunchCallbacks();
                    prepared = true; failure = null;
                    attachContent(); refreshItems();
                } catch(Throwable error) { fail(error); }
            }
            @Override public void failed(Throwable error) { preparing = false; fail(error); }
        });
    }

    private void attachContent() {
        if (remote == null || contentLayer != null) return;
        contentLayer = new FrameLayout(getContext());
        contentLayer.setClipChildren(false); contentLayer.setClipToPadding(false);
        contentLayer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view,Outline result) {
                result.setRoundRect((int)state[4],(int)state[6],(int)state[5],(int)state[7],radius());
            }
        });
        contentLayer.setClipToOutline(true);
        remote.addView(contentLayer,new FrameLayout.LayoutParams(-1,-1));
        buildCompact();
    }

    private void buildCompact() {
        if (contentLayer == null) return;
        if (compact != null) contentLayer.removeView(compact);
        compact = new FrameLayout(resources);
        compact.setClipChildren(false); compact.setClipToPadding(false);
        contentLayer.addView(compact);
        mainSmall=new FrameLayout(resources);mainSmall.setClipChildren(false);mainSmall.setClipToPadding(false);
        contentLayer.addView(mainSmall);
    }

    private HyperOsCompactFactory.Compact compactOwner(IslandItem item,long now){
        HyperOsCompactFactory.Compact content=compactOwners.get(item.key);
        if(content==null){content=HyperOsCompactFactory.create(getContext(),item,false);compactOwners.put(item.key,content);}
        else content.update(item,now);
        if(content.failure!=null)throw new IllegalStateException(content.failure);return content;
    }
    private void attachMainCompact(){
        compact.removeAllViews();mainSmall.removeAllViews();
        if(mainCompact==null)return;
        HyperOsCapsuleSurface.detach(mainCompact.view);HyperOsCapsuleSurface.detach(mainCompact.smallView());
        compact.addView(mainCompact.view,new FrameLayout.LayoutParams(-1,-1));
        mainSmall.addView(mainCompact.smallView(),new FrameLayout.LayoutParams(-1,-1));
    }
    private void updateCompactContents(boolean changed,long now) {
        if(primary==null)return;
        if(mainCompact==null || changed) {
            mainCompact=compactOwner(primary,now);attachMainCompact();
        } else mainCompact.update(primary,now);
        if(mainCompact.failure!=null)throw new IllegalStateException(mainCompact.failure);
        String nextKey=secondary==null?null:secondary.key;
        if(nextKey==null || !nextKey.equals(secondaryKey)) {
            if(touchActive&&touchSurface==2)cancelTouch();
            destroySmallAnimator();secondaryCompact=null;
            secondaryKey=nextKey;
            if(secondary!=null) {
                secondaryCompact=compactOwner(secondary,now);
                secondarySurface=new HyperOsCapsuleSurface(resources,nextKey,secondaryCompact);
                small=secondarySurface;smallAnimator=secondarySurface.animator;
                remote.addView(small,new FrameLayout.LayoutParams(-1,-1));
            }
        } else if(secondaryCompact!=null)secondaryCompact.update(secondary,now);
        if(secondaryCompact!=null&&secondaryCompact.failure!=null)throw new IllegalStateException(secondaryCompact.failure);
    }

    public void setGeometry(int width,float centerX,float cy,float holeDiameter,
        boolean tablet,float anchor,boolean glass,float limit) {
        int previousWidth=screenWidth,previousHeight=islandHeight,previousY=islandY;
        int previousCutoutWidth=cutoutWidth,previousCutoutHeight=cutoutHeight,previousExpandedWidth=expandedWidth;
        screenWidth = width; centerY = cy;
        cutoutWidth = Math.max(px(20),Math.round(holeDiameter / .78f));
        cutoutHeight = cutoutWidth;
        android.view.WindowInsets insets = getRootWindowInsets();
        if (insets != null && insets.getDisplayCutout() != null) {
            Rect bounds = insets.getDisplayCutout().getBoundingRectTop();
            if (!bounds.isEmpty()) {
                cutoutWidth = bounds.width(); cutoutHeight = bounds.height();
                centerY = bounds.exactCenterY();
            }
        }
        islandHeight = Math.max(px(34),cutoutHeight + px(4));
        islandY = Math.max(px(3),(int)(centerY - islandHeight/2f));
        expandedWidth = Math.min(px(364),Math.max(1,screenWidth-px(28)));
        boolean changed=previousWidth!=screenWidth || previousHeight!=islandHeight || previousY!=islandY
            || previousCutoutWidth!=cutoutWidth || previousCutoutHeight!=cutoutHeight
            || previousExpandedWidth!=expandedWidth;
        if(!changed)return;
        cancelTouch();clearExtraSurfaces();
        if (prepared) {
            rebuildCard(); layoutGeometry();
            if(primary!=null)transition(expanded ? 1 : nativeHidden ? 0 : 4);
            if(snapshot!=null)snapshot.refresh();
        }
        requestFrame();
    }

    public void setItems(List<IslandItem> source) { setItems(source,0); }
    public void setItems(List<IslandItem> source,int hint) {setItems(source,hint,null);}
    public void setItems(List<IslandItem> source,int hint,List<IslandItem> minimized) {
        items.clear(); if(source!=null)items.addAll(source);
        hiddenItems.clear();if(minimized!=null)hiddenItems.addAll(minimized);
        if(prepared)refreshItems(); else if(isAttachedToWindow())prepare();
    }

    private void refreshItems() {
        if(!prepared || screenWidth<=0 || contentLayer==null)return;
        if(launchPrepared)return;
        IslandItem before=primary;
        IslandItem oldSecondary=secondary;
        primary=items.isEmpty()?null:items.get(0);
        secondary=items.size()>1?items.get(1):null;
        if(touchActive&&((touchSurface==1&&!sameItem(before,primary))||(touchSurface==2&&!sameItem(oldSecondary,secondary))||(touchSurface==4&&primary!=null)
            ||touchKey!=null&&find(touchKey)==null||incomingItem!=null&&findSource(incomingItem.key)==null))cancelTouch();
        boolean handoff;
        try{handoff=!expanded&&!closing&&reconcileCapsuleOwners(before,oldSecondary);}
        catch(Throwable error){fail(error);return;}
        if(primary==null) {
            destroySmallAnimator();
            if(!nativeHidden&&!handoff)transition(expanded?7:6);
            expanded=false; closing=card!=null; nativeHidden=true;
            if(musicGlow!=null)musicGlow.clear();
            HyperOsIncomingCalls.reportHidden();pruneCompactOwners();requestFrame(); return;
        }
        boolean changed=before==null || !before.key.equals(primary.key);
        double previousBigWidth=geometry[3],previousBigX=geometry[5];
        if(expanded) {
            IslandItem replacement=find(expandedItem==null?null:expandedItem.key);
            if(replacement==null){expandedItem=primary;rebuildCard();}
            else{expandedItem=replacement;if(card!=null)card.update(replacement,SystemClock.elapsedRealtime());}
        }
        if(expanded && expandedItem!=null && expandedItem.isMedia) {
            if(musicGlow!=null)musicGlow.onMediaEnter(expandedItem.key);
        } else if(closing) {
            if(musicGlow!=null)musicGlow.onClosing();
        } else {
            if(musicGlow!=null && musicGlow.hasActiveMedia())musicGlow.clear();
        }
        try {updateCompactContents(changed,SystemClock.elapsedRealtime());}
        catch(Throwable error){fail(error);return;}
        layoutGeometry();
        if(HyperOsIncomingCalls.isIncoming(primary) && (changed || !expanded)) {
            expandedItem=primary; expanded=true; nativeHidden=false;
            rebuildCard(); layoutGeometry(); transition(before==null?11:1);
            controller.onExpandedChanged(true);
        } else if(handoff) {nativeHidden=false;transition(handoffMainTransition);}
        else if(nativeHidden) {nativeHidden=false;transition(0);}
        else if(changed && expanded)transition(1);
        else if(changed && !expanded)transition(4);
        else if(!expanded && (previousBigWidth!=geometry[3] || previousBigX!=geometry[5]))transition(4);
        if(handoff&&secondarySurface!=null)secondarySurface.transition(handoffSmallTransition,motionScale());
        pruneCompactOwners();
        requestFrame();
    }

    private IslandItem find(String key) {
        if(key==null)return null;for(IslandItem item:items)if(key.equals(item.key))return item;return null;
    }
    private IslandItem findSource(String key){IslandItem item=find(key);if(item!=null)return item;for(IslandItem hidden:hiddenItems)if(key.equals(hidden.key))return hidden;return null;}
    private float motionScale(){float scale=animationScale();return scale>0?scale:1;}
    private boolean reconcileCapsuleOwners(IslandItem before,IslandItem oldSecondary){
        if(sameItem(before,primary)&&sameItem(oldSecondary,secondary))return false;
        if(geometry[1]<=0)return false;
        boolean changeMain=!sameItem(before,primary),changeSmall=!sameItem(oldSecondary,secondary);
        ArrayList<HyperOsCapsuleSurface> candidates=new ArrayList<>(outgoingSurfaces);outgoingSurfaces.clear();
        if(incomingSurface!=null){candidates.add(incomingSurface);incomingSurface=null;incomingItem=null;}
        if(changeMain&&before!=null&&mainCompact!=null){
            HyperOsCapsuleSurface previous=new HyperOsCapsuleSurface(resources,before.key,mainCompact,animator);
            previous.configure(geometry);System.arraycopy(state,0,previous.values,0,state.length);previous.initialized=true;previous.role=1;
            remote.addView(previous,new FrameLayout.LayoutParams(-1,-1));previous.setSceneVisibility(sceneVisibility());
            candidates.add(previous);animator=0;mainCompact=null;
        }
        if(changeSmall&&secondarySurface!=null){candidates.add(secondarySurface);secondarySurface=null;secondaryCompact=null;small=null;smallAnimator=0;}
        handoffMainTransition=4;handoffSmallTransition=3;
        if(changeMain){
            HyperOsCapsuleSurface owner=takeSurface(candidates,primary==null?null:primary.key);
            if(owner!=null){
                if(animator!=0)HyperOsNative.nativeDestroyAnimator(animator);
                animator=owner.takeAnimator();System.arraycopy(owner.values,0,state,0,state.length);mainCompact=owner.content;
                handoffMainTransition=owner.role==0?0:4;
            }else{
                if(animator==0)animator=HyperOsNative.nativeCreateAnimator();
                HyperOsNative.nativeSnapAnimator(animator,0,geometry,0xff000000);
                activeMask=HyperOsNative.nativeAnimatorFrame(animator,0,0,1,state);
                mainCompact=primary==null?null:compactOwner(primary,SystemClock.elapsedRealtime());handoffMainTransition=0;
            }
            attachMainCompact();
        }
        if(changeSmall){
            secondaryKey=secondary==null?null:secondary.key;
            if(secondary!=null){
                secondarySurface=takeSurface(candidates,secondary.key);
                if(secondarySurface==null){
                    secondarySurface=new HyperOsCapsuleSurface(resources,secondary.key,compactOwner(secondary,SystemClock.elapsedRealtime()));
                    secondarySurface.startKind=4;secondarySurface.role=0;
                    remote.addView(secondarySurface,new FrameLayout.LayoutParams(-1,-1));
                }
                handoffSmallTransition=secondarySurface.role==0?14:3;
                secondarySurface.role=3;secondaryCompact=secondarySurface.content;small=secondarySurface;smallAnimator=secondarySurface.animator;
            }
        }
        for(HyperOsCapsuleSurface surface:candidates){
            surface.transition(surface.role==1?6:13,motionScale());surface.role=0;outgoingSurfaces.add(surface);
        }
        mainCapsulePreview=false;smallCapsulePreview=false;incomingCapsulePreview=false;capsulePreviewActive=false;
        return true;
    }
    private static HyperOsCapsuleSurface takeSurface(List<HyperOsCapsuleSurface> candidates,String key){
        if(key==null)return null;for(int i=0;i<candidates.size();i++)if(key.equals(candidates.get(i).key))return candidates.remove(i);return null;
    }
    private double[] capsuleMetrics(HyperOsCompactFactory.Compact content,double[] base,boolean paired){
        double[] result=base.clone();int[] measured=new int[9];
        HyperOsNative.nativeCapsule(new int[]{content.leftMeasured(),content.rightMeasured(),cutoutWidth,screenWidth,getResources().getDisplayMetrics().heightPixels,
            px(34),px(4),px(108),px(30)},new float[]{Math.min(px(300),screenWidth-px(28)),-1,-1},getLayoutDirection()==LAYOUT_DIRECTION_RTL,false,measured);
        result[3]=measured[0];result[4]=paired?measured[5]:measured[0];result[5]=paired?measured[8]:measured[3];
        result[16]=paired?measured[6]:measured[1];result[17]=paired?measured[7]:measured[2];result[21]=geometry[3];
        return result;
    }
    private void pruneCompactOwners(){
        Set<String> retained=new HashSet<>();for(IslandItem item:items)retained.add(item.key);for(IslandItem item:hiddenItems)retained.add(item.key);
        if(mainCompact!=null&&primary!=null)retained.add(primary.key);
        if(secondarySurface!=null)retained.add(secondarySurface.key);if(incomingSurface!=null)retained.add(incomingSurface.key);
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)retained.add(surface.key);
        java.util.Iterator<Map.Entry<String,HyperOsCompactFactory.Compact>> entries=compactOwners.entrySet().iterator();
        while(entries.hasNext()){Map.Entry<String,HyperOsCompactFactory.Compact> entry=entries.next();if(!retained.contains(entry.getKey())){entry.getValue().release();entries.remove();}}
    }
    private void clearExtraSurfaces(){
        if(incomingSurface!=null){incomingSurface.close();incomingSurface=null;incomingItem=null;}
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)surface.close();outgoingSurfaces.clear();
    }
    private void stepExtraSurfaces(long delta,long predicted){
        long now=SystemClock.elapsedRealtime();
        if(incomingSurface!=null){
            incomingSurface.frame(delta,predicted);IslandItem replacement=findSource(incomingSurface.key);
            if(replacement!=null)incomingItem=replacement;
            if(incomingItem!=null)incomingSurface.content.update(incomingItem,now);
            if(!incomingCapsulePreview&&incomingSurface.activeMask==0&&incomingSurface.values[3]<=.001){incomingSurface.close();incomingSurface=null;incomingItem=null;}
        }
        for(int i=outgoingSurfaces.size()-1;i>=0;i--){
            HyperOsCapsuleSurface surface=outgoingSurfaces.get(i);surface.frame(delta,predicted);
            IslandItem item=findSource(surface.key);if(item!=null)surface.content.update(item,now);
            if(surface.activeMask==0&&surface.values[3]<=.001){surface.close();outgoingSurfaces.remove(i);}
        }
        pruneCompactOwners();
    }
    private boolean extraSurfacesNeedFrames(){
        if(incomingSurface!=null&&incomingSurface.needsFrames())return true;
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)if(surface.needsFrames())return true;return false;
    }
    private boolean capsuleOwnersAnimating(){
        if(incomingSurface!=null&&incomingSurface.activeMask!=0)return true;
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)if(surface.activeMask!=0)return true;return false;
    }
    private void rebuildCard() {
        cancelCallCommit();
        if(card!=null){contentLayer.removeView(card.view);card.release();card=null;}
        if(!prepared || contentLayer==null || expandedItem==null)return;
        card=HyperOsCardFactory.create(getContext(),controller,expandedItem,expandedWidth);
        if(card.failure!=null){failure=card.failure;HyperOsIncomingCalls.reportHidden();return;}
        failure=null;
        card.view.setOnClickListener(view -> open(expandedItem));
        contentLayer.addView(card.view,new FrameLayout.LayoutParams(expandedWidth,card.height()));
    }

    private void layoutGeometry() {
        if(primary==null || compact==null || screenWidth<=0)return;
        if(mainCompact==null)return;
        int left=mainCompact.leftMeasured(),right=mainCompact.rightMeasured();
        int[] dims={left,right,cutoutWidth,screenWidth,getResources().getDisplayMetrics().heightPixels,
            px(34),px(4),px(108),px(30)};
        float max=Math.min(px(300),screenWidth-px(28));
        HyperOsNative.nativeCapsule(dims,new float[]{max,-1,-1},getLayoutDirection()==LAYOUT_DIRECTION_RTL,false,capsule);
        int bigWidth=secondary==null?capsule[0]:capsule[5];
        int leftWidth=secondary==null?capsule[1]:capsule[6];
        int rightWidth=secondary==null?capsule[2]:capsule[7];
        int bigX=secondary==null?capsule[3]:capsule[8];
        FrameLayout.LayoutParams bigParams=new FrameLayout.LayoutParams(bigWidth,islandHeight);
        bigParams.leftMargin=bigX;bigParams.topMargin=islandY;compact.setLayoutParams(bigParams);
        mainCompact.layout(leftWidth,cutoutWidth,rightWidth,islandHeight);
        compact.measure(MeasureSpec.makeMeasureSpec(bigWidth,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(islandHeight,MeasureSpec.EXACTLY));
        compact.layout(bigX,islandY,bigX+bigWidth,islandY+islandHeight);
        int expandedY=Math.max((int)(centerY*2)+px(4),islandY+islandHeight+px(8));
        int cardHeight=card==null?px(84):Math.max(1,card.height());
        int margin=(screenWidth-expandedWidth)/2;
        int smallX=getLayoutDirection()==LAYOUT_DIRECTION_RTL?bigX-px(4)-px(34):bigX+bigWidth+px(4);
        smallBounds.set(smallX,islandY,smallX+px(34),islandY+islandHeight);
        geometry[0]=screenWidth;geometry[1]=islandHeight;geometry[2]=islandY;
        geometry[3]=capsule[0];geometry[4]=bigWidth;geometry[5]=bigX;
        geometry[6]=expandedWidth;geometry[7]=expandedWidth;geometry[8]=cardHeight;
        geometry[9]=expandedY;geometry[10]=margin;geometry[11]=px(34);geometry[12]=smallX;
        geometry[13]=cutoutHeight;geometry[14]=centerY;geometry[15]=cutoutWidth;
        geometry[16]=leftWidth;geometry[17]=rightWidth;geometry[18]=density;
        geometry[19]=0;geometry[20]=getLayoutDirection()==LAYOUT_DIRECTION_RTL?1:0;
        geometry[21]=capsule[0];
        if(card!=null){FrameLayout.LayoutParams cardParams=new FrameLayout.LayoutParams(expandedWidth,cardHeight);
            cardParams.leftMargin=margin;cardParams.topMargin=islandY;card.view.setLayoutParams(cardParams);
            card.view.layout(margin,islandY,margin+expandedWidth,islandY+cardHeight);}
        FrameLayout.LayoutParams iconParams=new FrameLayout.LayoutParams(px(34),islandHeight);
        iconParams.leftMargin=screenWidth/2-px(34)/2;iconParams.topMargin=islandY;mainSmall.setLayoutParams(iconParams);
        mainSmall.layout(iconParams.leftMargin,islandY,iconParams.leftMargin+px(34),islandY+islandHeight);
        if(secondarySurface!=null){
            double[] metrics=capsuleMetrics(secondaryCompact,geometry,true);
            boolean moved=secondarySurface.initialized&&(secondarySurface.metrics[12]!=smallX||secondarySurface.metrics[2]!=islandY||secondarySurface.metrics[1]!=islandHeight);
            secondarySurface.configure(metrics);
            if(!secondarySurface.initialized)secondarySurface.snap(secondarySurface.startKind);
            else if(moved&&!capsulePreviewActive)secondarySurface.transition(3,motionScale());
            smallAnimator=secondarySurface.animator;smallActiveMask=secondarySurface.activeMask;
            System.arraycopy(secondarySurface.values,0,smallState,0,smallState.length);updateSmallBounds();
        }
        controller.onWindowHeightNeeded(Math.max(islandY+islandHeight+px(12),
            expanded || closing ? expandedY+cardHeight+px(32):0));
    }

    private float animationScale() {
        try {
            float scale=Settings.Global.getFloat(getContext().getContentResolver(),"island_animator_duration_scale",1);
            return scale<=0 || !Float.isFinite(scale) ? 0f : scale;
        } catch(Throwable t) {
            return 1f;
        }
    }

    private void transition(int kind) {
        if(animator==0 || geometry[1]<=0)return;
        if(touchActive)cancelTouch();
        try {
            float scale=animationScale();
            if(scale<=0)scale=1;
            HyperOsNative.nativeTransition(animator,kind,geometry,0xff000000,scale,false);
            activeMask=1;requestFrame();
        }catch(Throwable error){fail(error);}
    }
    private static boolean sameItem(IslandItem a,IslandItem b){return a==b||a!=null&&b!=null&&a.key.equals(b.key);}
    private void destroySmallAnimator(){
        if(secondarySurface!=null){secondarySurface.close();secondarySurface=null;}
        smallAnimator=0;smallActiveMask=0;small=null;secondaryCompact=null;secondaryKey=null;
    }
    private void updateSmallBounds(){
        if(secondarySurface==null)return;smallBounds.set(secondarySurface.bounds);
    }

    @Override public void doFrame(long timeNanos) {
        pendingFrame=false;
        if(!prepared || animator==0)return;
        if(launchStarted){
            if(musicGlow!=null)musicGlow.clear();
            invalidate();controller.onIslandFrame();return;
        }
        boolean activeMedia = (expanded && expandedItem != null && expandedItem.isMedia);
        if(visibleAlpha() <= .001f || (!activeMedia && !closing)) {
            if(musicGlow != null && (musicGlow.isRunning() || musicGlow.hasActiveMedia())) {
                musicGlow.clear();
            }
        } else if(activeMedia && musicGlow != null && !musicGlow.hasActiveMedia()) {
            musicGlow.onMediaEnter(expandedItem.key);
        }
        try {
            long delta=lastFrame==0?0:Math.max(0,timeNanos-lastFrame);lastFrame=timeNanos;
            long predicted=getDisplay()==null?0:Math.round(1_000_000_000d/getDisplay().getRefreshRate());
            activeMask=HyperOsNative.nativeAnimatorFrame(animator,delta,predicted,1,state);
            if(secondarySurface!=null){secondarySurface.frame(delta,predicted);smallActiveMask=secondarySurface.activeMask;System.arraycopy(secondarySurface.values,0,smallState,0,smallState.length);updateSmallBounds();}
            stepExtraSurfaces(delta,predicted);
            if(primary!=null)updateCompactContents(false,SystemClock.elapsedRealtime());
            if(card!=null){int previousHeight=card.height();card.update(expandedItem,SystemClock.elapsedRealtime());
                if(previousHeight!=card.height()){layoutGeometry();if(expanded)transition(1);}}
            applyState();
            if(visibleAlpha()<=.001f && musicGlow!=null){musicGlow.clear();}
            invalidate();controller.onIslandFrame();
            if(closing && activeMask==0) {
                closing=false;if(card!=null){contentLayer.removeView(card.view);card.release();card=null;}
                if(snapshot!=null)snapshot.setActive(false);layoutGeometry();
            }
            if(activeMask!=0 || smallActiveMask!=0 || commitPending || card!=null && card.needsFrames() || mainCompact!=null&&mainCompact.needsFrames()
                || secondaryCompact!=null&&secondaryCompact.needsFrames() || extraSurfacesNeedFrames() || (visibleAlpha()>.001f && musicGlow!=null && musicGlow.isRunning()))requestFrame();
        }catch(Throwable error){fail(error);}
    }

    private void applyState() {
        if(contentLayer==null)return;
        contentLayer.setVisibility(visibleAlpha()>.001f?View.VISIBLE:View.INVISIBLE);
        contentLayer.setTranslationX((float)state[1]);contentLayer.setTranslationY((float)state[2]);
        contentLayer.setAlpha(visibleAlpha());contentLayer.invalidateOutline();
        compact.setScaleX((float)state[10]);compact.setScaleY((float)state[10]);compact.setAlpha((float)state[9]);
        compact.setTranslationY((float)state[13]);
        mainSmall.setAlpha((float)state[17]);mainSmall.setScaleX((float)state[18]);mainSmall.setScaleY((float)state[18]);
        mainSmall.setTranslationX((float)state[19]);mainSmall.setTranslationY((float)state[20]);
        if(mainCompact!=null)mainCompact.setPresentationVisible(state[9]>.001,state[17]>.001);
        if(mainCompact!=null)mainCompact.setAreaTranslation((float)state[15],(float)state[16]);
        blur(compact,state[14]);
        if(card!=null) {
            int margin=(int)geometry[10];card.view.layout(margin,islandY,margin+expandedWidth,islandY+card.height());
            card.view.setScaleX((float)state[23]);card.view.setScaleY((float)state[24]);
            card.view.setAlpha((float)state[22]);card.view.setTranslationX((float)state[26]);
            card.view.setTranslationY((float)state[25]);blur(card.view,state[27]);
        }
        float capsuleVisibility=expanded?0:sceneVisibility();
        if(secondarySurface!=null)secondarySurface.setSceneVisibility(capsuleVisibility);
        if(incomingSurface!=null)incomingSurface.setSceneVisibility(capsuleVisibility);
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)surface.setSceneVisibility(capsuleVisibility);
        boolean visible=visibleAlpha()>.001f&&!launchStarted;
        setAnimationVisibility(visible&&(state[9]>.001||state[17]>.001),secondarySurface!=null&&secondarySurface.getAlpha()>.001f,visible&&state[22]>.001);
        if(snapshot!=null)snapshot.setActive((expanded || closing) && visibleAlpha()>.001f);
        boolean ready=expanded && card!=null && card.hasReadyIncomingControls()
            && card.view.isAttachedToWindow() && visibleAlpha()>.9f && state[22]>.9;
        if(ready && !callCommitted && !commitPending) {
            commitPending=true;final String key=expandedItem.key;final long generation=++commitGeneration;
            final HyperOsCardFactory.Card presentedCard=card;
            frameCommit=() -> {
                if(generation!=commitGeneration)return;
                commitPending=false;frameCommit=null;commitObserver=null;
                if(expandedItem!=null && key.equals(expandedItem.key) && expanded && card==presentedCard
                    && card.hasReadyIncomingControls() && visibleAlpha()>.9f) {
                    callCommitted=true;committedKey=key;
                    HyperOsIncomingCalls.reportPresentation(key,card.view,true);
                }
                requestFrame();
            };
            commitObserver=getViewTreeObserver();commitObserver.registerFrameCommitCallback(frameCommit);
            postInvalidateOnAnimation();
        }
        if(ready && callCommitted && expandedItem.key.equals(committedKey))
            HyperOsIncomingCalls.reportPresentation(committedKey,card.view,true);
        else if(!ready){cancelCallCommit();HyperOsIncomingCalls.reportHidden();}
    }

    private void setAnimationVisibility(boolean main,boolean secondary,boolean expanded){
        if(mainCompact!=null)mainCompact.setOwnerVisible(main);
        if(secondaryCompact!=null)secondaryCompact.setOwnerVisible(secondary);
        if(card!=null)card.setOwnerVisible(expanded);
    }
    private void cancelCallCommit(){
        commitGeneration++;
        if(frameCommit!=null&&commitObserver!=null&&commitObserver.isAlive())commitObserver.unregisterFrameCommitCallback(frameCommit);
        frameCommit=null;commitObserver=null;commitPending=false;callCommitted=false;committedKey=null;
    }

    private void blur(View view,double ratio) {
        int radius=Math.max(0,Math.min(160,(int)(40*(float)ratio)));
        if(radius>0 && blurs[radius]==null)blurs[radius]=RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP);
        view.setRenderEffect(radius==0?null:blurs[radius]);
    }
    private float radius(){return Math.max(0,Math.min(((float)state[7]-(float)state[6])/2,px(30)));}
    private void currentBounds(RectF out) {
        out.set((int)state[4]+(float)state[1],(int)state[6]+(float)state[2],
            (int)state[5]+(float)state[1],(int)state[7]+(float)state[2]);
    }

    @Override protected void onDraw(Canvas canvas) {
        if(launchStarted){if(musicGlow!=null)musicGlow.clear();drawLaunch(canvas);return;}
        if(!prepared || visibleAlpha()<=.001f){if(musicGlow!=null)musicGlow.clear();return;}
        currentBounds(outline);
        if(outline.isEmpty()){if(musicGlow!=null)musicGlow.clear();return;}
        if(musicGlow!=null && ((expanded && expandedItem!=null && expandedItem.isMedia) || closing || musicGlow.isRunning())) {
            musicGlow.sampleFrame(SystemClock.elapsedRealtime(), animationScale());
            musicGlow.drawBackground(canvas, outline, radius(), visibleAlpha(), density);
        }
        int save=canvas.save();
        if((expanded||closing)&&material!=null&&material.usesGlass())canvas.clipRect(outline);
        else{clip.reset();clip.addRoundRect(outline,radius(),radius(),Path.Direction.CW);canvas.clipPath(clip);}
        if((expanded || closing) && material!=null && snapshot!=null) {
            snapshot.sceneToWindow(sceneMap);
            if(!material.drawGlass(canvas,outline,snapshot.refractBitmap(),snapshot.colorBitmap(),sceneMap,density,visibleAlpha()))
                material.draw(canvas,outline,snapshot.bitmap(),null,sceneMap,density,visibleAlpha(),false);
        }else{black.setAlpha(Math.round(255*visibleAlpha()));canvas.drawRoundRect(outline,radius(),radius(),black);}
        canvas.restoreToCount(save);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if(!launchStarted && prepared && visibleAlpha()>.001f && musicGlow!=null && ((expanded && expandedItem!=null && expandedItem.isMedia) || closing || musicGlow.isRunning())) {
            currentBounds(outline);
            if(!outline.isEmpty()) {
                musicGlow.drawForeground(canvas, outline, radius(), visibleAlpha(), density);
            }
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        trackPress(event);
        try{return super.dispatchTouchEvent(event);}
        finally{if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){tapSurface=0;tapKey=null;releasedGesture=false;releasedDrag=false;collapseDrag=false;releasedQueueSwipe=false;}}
    }
    private void trackPress(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            cancelTouch();tapSurface=0;tapKey=null;
            if(!canReceiveTouch())return;
            RectF restore=new RectF();restoreBounds(restore);
            boolean restoreTouch=primary==null&&controller.hasMinimized()&&restore.contains(event.getX(),event.getY());
            if(!restoreTouch&&(visibleAlpha()<=.3f||primary==null))return;
            touchActive=true;touchMoved=false;touchInSeek=false;releasedGesture=false;touchDirection=0;
            touchAllowed=activeMask==0&&smallActiveMask==0&&!capsuleOwnersAnimating();touchPointer=event.getPointerId(0);touchX=event.getX();touchY=event.getY();
            if(restoreTouch)touchSurface=4;
            else if(expanded){currentBounds(outline);touchSurface=outline.contains(touchX,touchY)?3:0;}
            else if(secondary!=null&&smallBounds.contains(touchX,touchY))touchSurface=2;
            else{currentBounds(outline);touchSurface=outline.contains(touchX,touchY)?1:0;}
            IslandItem item=touchSurface==1?primary:touchSurface==2?secondary:touchSurface==3?expandedItem:null;
            touchKey=item==null?null:item.key;
            boolean mediaControl=touchSurface==3&&expandedItem!=null&&card!=null
                &&(expandedItem.isMedia&&hitMediaControl(card.view,event.getRawX(),event.getRawY(),false)
                ||expandedItem.torch&&hitMediaControl(card.view,event.getRawX(),event.getRawY(),true));
            if(mediaControl)touchInSeek=hitMediaControl(card.view,event.getRawX(),event.getRawY(),true);
            if(touchSurface!=0&&touchSurface<4&&touchAllowed&&!mediaControl)setPress(touchSurface,true);
        }else if(action==MotionEvent.ACTION_MOVE&&touchActive){
            int pointer=event.findPointerIndex(touchPointer);if(pointer<0){cancelTouch();return;}
            int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
            float dx=event.getX(pointer)-touchX,dy=event.getY(pointer)-touchY;
            if(Math.abs(dx)>slop||Math.abs(dy)>slop){
                if(!touchMoved)touchDirection=Math.abs(dx)>Math.abs(dy)?dx>0?1:-1:dy>0?2:-2;
                touchMoved=true;releasePress();
                if((touchSurface==1||touchSurface==2||touchSurface==4)&&touchAllowed&&Math.abs(touchDirection)==1)previewCapsule(dx);
                if(touchSurface==3&&touchAllowed&&(!touchInSeek||Math.abs(touchDirection)==2)){
                    if(touchInSeek)requestDisallowInterceptTouchEvent(false);
                    swipeExpanded(dx,dy);
                }
            }
        }else if(action==MotionEvent.ACTION_UP){
            releasedGesture=touchActive;
            int pointer=event.findPointerIndex(touchPointer);float dx=pointer<0?0:event.getX(pointer)-touchX,dy=pointer<0?0:event.getY(pointer)-touchY;
            releasedDrag=touchActive&&touchSurface==3&&touchMoved&&(!touchInSeek||Math.abs(touchDirection)==2);
            int threshold=swipeThreshold();
            releasedQueueSwipe=touchActive&&touchMoved&&touchAllowed&&(touchSurface==1||touchSurface==2||touchSurface==4)&&Math.abs(touchDirection)==1&&Math.abs(dx)>threshold;
            queueDirection=(dx<0?-1:1)*(getLayoutDirection()==LAYOUT_DIRECTION_RTL?-1:1);
            if(!releasedQueueSwipe)resetCapsulePreview();
            collapseDrag=releasedDrag&&touchAllowed&&(Math.abs(touchDirection)==1&&Math.abs(dx)>threshold||touchDirection==-2&&-dy>threshold);
            if(collapseDrag)swipeActive=false;else resetSwipe();
            tapSurface=touchActive&&!touchMoved?touchSurface:0;tapKey=touchKey;
            releasePress();touchActive=false;touchKey=null;touchSurface=0;touchPointer=-1;
        }else if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_OUTSIDE
            ||action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP)cancelTouch();
    }
    private static boolean hitMediaControl(View view,float x,float y,boolean seekOnly){
        if(view.getVisibility()!=View.VISIBLE||!view.isEnabled())return false;
        if(view instanceof android.widget.SeekBar||!seekOnly&&view instanceof ImageView&&view.hasOnClickListeners()){
            Rect bounds=new Rect();if(view.getGlobalVisibleRect(bounds)&&bounds.contains((int)x,(int)y))return true;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hitMediaControl(((ViewGroup)view).getChildAt(i),x,y,seekOnly))return true;
        return false;
    }
    private void setPress(int surface,boolean pressed){
        long owner=surface==2?smallAnimator:animator;if(owner==0)return;
        if(pressed)pressedSurface=surface;
        try{
            float scale=Settings.Global.getFloat(getContext().getContentResolver(),"island_animator_duration_scale",1);if(scale<=0||!Float.isFinite(scale))scale=1;
            HyperOsNative.nativePressAnimator(owner,surface==1?1:surface==2?3:2,surface==2&&secondarySurface!=null?secondarySurface.metrics:geometry,0xff000000,scale,pressed);
            if(surface==2)smallActiveMask=1;else activeMask=1;requestFrame();
        }catch(Throwable error){pressedSurface=0;fail(error);}
    }
    private void releasePress(){int surface=pressedSurface;pressedSurface=0;if(surface!=0)setPress(surface,false);}
    private int swipeThreshold(){
        if(resources==null)return px(50);int id=resources.getResources().getIdentifier("island_swipe_threshold","dimen",resources.getPackageName());
        return id==0?px(50):resources.getResources().getDimensionPixelSize(id);
    }
    private void swipeExpanded(float dx,float dy){
        if(animator==0)return;try{if(HyperOsNative.nativeSwipeExpanded(animator,geometry,dx,dy)){swipeActive=true;activeMask=1;requestFrame();}}
        catch(Throwable error){swipeActive=false;fail(error);}
    }
    private void resetSwipe(){
        if(!swipeActive)return;swipeActive=false;if(animator==0)return;
        try{HyperOsNative.nativeResetSwipe(animator,geometry);activeMask=1;requestFrame();}catch(Throwable error){fail(error);}
    }
    private void previewCapsule(float dx){
        if(animator==0||geometry[1]<=0)return;
        try {
            int direction=(dx<0?-1:1)*(getLayoutDirection()==LAYOUT_DIRECTION_RTL?-1:1);
            HyperOsPresentationQueue.Layout next=controller.previewSwipe(direction);
            if(next==null)return;
            IslandItem big=next.visible.isEmpty()?null:next.visible.get(0);
            IslandItem little=next.visible.size()>1?next.visible.get(1):null;
            if(sameItem(primary,big)&&sameItem(secondary,little)){resetCapsulePreview();return;}
            if(primary!=null&&!sameItem(primary,big)){
                HyperOsNative.nativeSwipeCapsule(animator,geometry,sameItem(primary,little)?1:0,dx);
                mainCapsulePreview=true;activeMask=1;
            }else if(mainCapsulePreview){HyperOsNative.nativeResetCapsule(animator,geometry,1);mainCapsulePreview=false;activeMask=1;}
            if(secondarySurface!=null&&!sameItem(secondary,little)){
                secondarySurface.preview(sameItem(secondary,big)?2:3,dx);smallCapsulePreview=true;smallActiveMask=1;
            }else if(smallCapsulePreview&&secondarySurface!=null){secondarySurface.reset(3);smallCapsulePreview=false;smallActiveMask=1;}
            if(big!=null&&!sameItem(big,primary)&&!sameItem(big,secondary)){
                if(incomingSurface==null||!big.key.equals(incomingSurface.key)){
                    if(incomingSurface!=null)incomingSurface.close();
                    incomingSurface=new HyperOsCapsuleSurface(resources,big.key,compactOwner(big,SystemClock.elapsedRealtime()));
                    incomingSurface.role=0;incomingSurface.configure(capsuleMetrics(incomingSurface.content,geometry,little!=null));incomingSurface.snap(0);
                    remote.addView(incomingSurface,new FrameLayout.LayoutParams(-1,-1));
                }
                incomingItem=big;incomingSurface.preview(4,dx);incomingCapsulePreview=true;
            }else if(incomingCapsulePreview&&incomingSurface!=null){incomingSurface.reset(0);incomingCapsulePreview=false;}
            capsulePreviewActive=mainCapsulePreview||smallCapsulePreview||incomingCapsulePreview;requestFrame();
        }catch(Throwable error){capsulePreviewActive=false;fail(error);}
    }
    private void resetCapsulePreview(){
        if(!capsulePreviewActive&&!mainCapsulePreview&&!smallCapsulePreview&&!incomingCapsulePreview)return;capsulePreviewActive=false;
        try{
            if(mainCapsulePreview&&animator!=0){HyperOsNative.nativeResetCapsule(animator,geometry,1);activeMask=1;}
            if(smallCapsulePreview&&secondarySurface!=null){secondarySurface.reset(3);smallActiveMask=1;}
            if(incomingCapsulePreview&&incomingSurface!=null)incomingSurface.reset(0);
            mainCapsulePreview=false;smallCapsulePreview=false;incomingCapsulePreview=false;requestFrame();
        }
        catch(Throwable error){fail(error);}
    }
    private void cancelTouch(){releasePress();resetSwipe();resetCapsulePreview();touchActive=false;touchMoved=true;touchInSeek=false;releasedGesture=false;releasedDrag=false;collapseDrag=false;
        touchAllowed=false;touchDirection=0;releasedQueueSwipe=false;queueDirection=0;touchPointer=-1;touchSurface=0;touchKey=null;tapSurface=0;tapKey=null;}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        return event.getActionMasked()==MotionEvent.ACTION_MOVE&&touchActive&&touchSurface==3&&touchMoved&&(!touchInSeek||Math.abs(touchDirection)==2)
            ||super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){collapse();return true;}
        if(event.getActionMasked()!=MotionEvent.ACTION_UP)return visibleAlpha()>.3f||touchActive&&touchSurface==4;
        if(!releasedGesture)return false;
        if(releasedQueueSwipe){controller.onSwipe(queueDirection);resetCapsulePreview();return true;}
        if(releasedDrag){if(collapseDrag)collapse();return true;}
        if(expanded){currentBounds(outline);if(!outline.contains(event.getX(),event.getY()))collapse();return true;}
        IslandItem item=tapKey==null?null:find(tapKey);
        if(tapSurface==4){controller.onRestore();return true;}
        if((tapSurface==1||tapSurface==2)&&item!=null&&!item.noExpand)expand(item);
        return true;
    }
    public void expand(IslandItem item) {
        if(!prepared || item==null || item.noExpand)return;
        expandedItem=item;expanded=true;closing=false;rebuildCard();layoutGeometry();transition(1);
        if(expandedItem.isMedia && musicGlow!=null)musicGlow.onMediaEnter(expandedItem.key);
        controller.onExpandedChanged(true);
    }
    public void collapse() {
        if(!expanded)return;expanded=false;closing=card!=null;cancelCallCommit();
        if(musicGlow!=null)musicGlow.onClosing();
        HyperOsIncomingCalls.reportHidden();transition(2);controller.onExpandedChanged(false);
    }
    private void open(IslandItem item){if(item==null)return;RectF bounds=new RectF();currentBounds(bounds);controller.onOpenItem(item,bounds);}

    private static void installLaunchCallbacks() {
        HyperOsActivityLaunch.setSourceCallbacks(new HyperOsActivityLaunch.SourceCallbacks() {
            @Override public void onPrepare(View source,RectF bounds,boolean expanded) {
                HyperIslandView view=(HyperIslandView)source;
                View body=expanded && view.card!=null?view.card.view:view.compact;
                if(body==null || body.getWidth()<=0 || body.getHeight()<=0)
                    throw new IllegalStateException("Launch source has no rendered content");
                view.launchWidth=body.getWidth();view.launchHeight=body.getHeight();
                view.launchBounds.set(bounds);view.launchCorner=Math.min(bounds.height()/2,view.px(30));
                view.launchBody.setPosition(0,0,body.getWidth(),body.getHeight());
                RecordingCanvas record=view.launchBody.beginRecording(body.getWidth(),body.getHeight());
                try{body.draw(record);}finally{view.launchBody.endRecording();}
                view.launchPrepared=true;view.launchStarted=false;view.launchAlpha=1;
            }
            @Override public void onStart(View source,HyperOsActivityMotion motion) {
                HyperIslandView view=(HyperIslandView)source;
                view.launchStarted=true;
                view.cancelCallCommit();
                if(view.musicGlow!=null)view.musicGlow.clear();
                view.setAnimationVisibility(false,false,false);
                if(view.contentLayer!=null)view.contentLayer.setVisibility(View.INVISIBLE);
                if(view.small!=null)view.small.setVisibility(View.INVISIBLE);
                HyperOsIncomingCalls.reportHidden();view.invalidate();
            }
            @Override public void onFrame(View source,HyperOsActivityMotion.State state) {
                HyperIslandView view=(HyperIslandView)source;
                if(state.sourceTracksApp) {
                    int[] location=new int[2];view.getLocationOnScreen(location);
                    view.launchBounds.set(state.bounds);view.launchBounds.offset(-location[0],-location[1]);
                    view.controller.onWindowHeightNeeded(Math.max(view.getHeight(),(int)Math.ceil(view.launchBounds.bottom)));
                }
                view.launchAlpha=state.sourceAlpha;view.launchCorner=state.corner;
                view.invalidate();
            }
            @Override public void onFinish(View source,boolean cancelled) {
                HyperIslandView view=(HyperIslandView)source;
                view.launchBody.discardDisplayList();view.launchPrepared=false;view.launchStarted=false;
                if(!cancelled){view.expanded=false;view.closing=view.card!=null;view.transition(6);view.controller.onExpandedChanged(false);}
                view.refreshItems();view.requestFrame();
            }
        });
    }

    private void drawLaunch(Canvas canvas) {
        if(launchAlpha<=0 || !launchBody.hasDisplayList() || launchBounds.isEmpty())return;
        int layer=canvas.saveLayerAlpha(launchBounds,Math.round(Math.max(0,Math.min(1,launchAlpha))*255));
        clip.reset();clip.addRoundRect(launchBounds,launchCorner,launchCorner,Path.Direction.CW);canvas.clipPath(clip);
        black.setAlpha(255);canvas.drawRoundRect(launchBounds,launchCorner,launchCorner,black);
        float scale=Math.max(launchBounds.width()/launchWidth,launchBounds.height()/launchHeight);
        canvas.translate(launchBounds.centerX(),launchBounds.centerY());canvas.scale(scale,scale);
        canvas.translate(-launchWidth/2,-launchHeight/2);canvas.drawRenderNode(launchBody);
        canvas.restoreToCount(layer);
    }
    public boolean isExpanded(){return expanded;}
    public boolean isFullyHidden(){return !prepared||sceneVisibility()<=.001f||visibleAlpha()<=.001f&&!extraSurfacesVisible();}
    private float sceneVisibility(){return failure!=null||suppressed||statusHidden||sleeping?0:1-Math.max(0,Math.min(1,shade));}
    private boolean extraSurfacesVisible(){if(incomingSurface!=null&&incomingSurface.getAlpha()>.001f)return true;for(HyperOsCapsuleSurface surface:outgoingSurfaces)if(surface.getAlpha()>.001f)return true;return false;}
    public float visibleAlpha(){return sceneVisibility()*Math.max(0,Math.min(1,(float)state[3]));}
    public boolean needsAudio(){return false;}
    public void getCoverageBounds(RectF out){if(launchStarted){out.set(launchBounds);return;}currentBounds(out);if(secondary!=null&&!expanded)out.union(smallBounds);if(incomingSurface!=null)out.union(incomingSurface.bounds);for(HyperOsCapsuleSurface surface:outgoingSurfaces)out.union(surface.bounds);}
    public void getRestingPillBounds(RectF out){out.set((float)geometry[5],islandY,(float)geometry[5]+(float)geometry[4],islandY+islandHeight);}
    public void getTouchRegion(Region out) {
        out.setEmpty();
        if(primary==null&&controller.hasMinimized()&&canReceiveTouch()){
            restoreBounds(outline);out.set((int)outline.left,(int)outline.top,(int)outline.right,(int)outline.bottom);return;
        }
        if(visibleAlpha()<.4f)return;
        if(expanded){out.set(0,0,screenWidth,Math.max(getHeight(),(int)(geometry[9]+geometry[8]+px(32))));return;}
        currentBounds(outline);out.op((int)outline.left-px(4),(int)outline.top-px(4),(int)outline.right+px(4),(int)outline.bottom+px(4),Region.Op.UNION);
        if(secondary!=null)out.op((int)smallBounds.left,(int)smallBounds.top,(int)smallBounds.right,(int)smallBounds.bottom,Region.Op.UNION);
    }
    private boolean canReceiveTouch(){return prepared&&failure==null&&!suppressed&&!statusHidden&&!sleeping&&shade<.01f&&!launchStarted;}
    private void restoreBounds(RectF out){float padding=px(6);out.set(screenWidth/2f-cutoutWidth/2f-padding,centerY-cutoutHeight/2f-padding,
        screenWidth/2f+cutoutWidth/2f+padding,centerY+cutoutHeight/2f+padding);}
    public void setSuppressed(boolean value){if(suppressed==value)return;suppressed=value;if(suppressed&&musicGlow!=null)musicGlow.clear();updateVisibility();}
    public void setShadeFraction(float value){if(shade==value)return;shade=value;if(shade>=.999f&&musicGlow!=null)musicGlow.clear();updateVisibility();}
    public void setStatusBarHidden(boolean value){if(statusHidden==value)return;statusHidden=value;if(statusHidden&&musicGlow!=null)musicGlow.clear();updateVisibility();}
    public void setSleepFold(boolean value){if(sleeping==value)return;sleeping=value;if(sleeping&&musicGlow!=null)musicGlow.clear();updateVisibility();if(!value&&snapshot!=null)snapshot.refresh();}
    public void setPanelState(int value){}
    public void setHeadsUpPinned(boolean value){}
    public boolean shadePulled(){return shade>0;}
    public void reveal(){suppressed=false;updateVisibility();}
    public void absorb(){requestFrame();}
    public void regrowPillNow(){requestFrame();}
    public void popBubbleNow(){requestFrame();}
    public void fadePillBlobNow(){requestFrame();}
    public void onSettingsChanged(){if(prepared){rebuildCard();layoutGeometry();transition(expanded?1:4);}}
    private void updateVisibility(){
        cancelTouch();HyperOsIncomingCalls.reportHidden();cancelCallCommit();
        if(visibleAlpha()<=.001f && musicGlow!=null)musicGlow.clear();
        requestFrame();
    }
    private int px(float value){return Math.round(value*density);}
    private void requestFrame(){if(!pendingFrame&&isAttachedToWindow()){pendingFrame=true;frames.postFrameCallback(this);}invalidate();}
    private void fail(Throwable error){
        failure=error.toString();capsulePreviewActive=false;mainCapsulePreview=false;smallCapsulePreview=false;incomingCapsulePreview=false;
        touchActive=false;touchAllowed=false;pressedSurface=0;cancelCallCommit();setAnimationVisibility(false,false,false);
        clearExtraSurfaces();if(secondarySurface!=null)secondarySurface.setSceneVisibility(0);
        HyperOsIncomingCalls.reportHidden();if(contentLayer!=null)contentLayer.setVisibility(View.INVISIBLE);
        android.util.Log.e("HyperIsland", "Renderer initialization/frame failed",error);
    }
    public String failure(){return failure;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();prepare();requestFrame();}
    @Override protected void onDetachedFromWindow(){
        cancelTouch();destroySmallAnimator();clearExtraSurfaces();
        cancelCallCommit();
        HyperOsIncomingCalls.reportHidden();frames.removeFrameCallback(this);pendingFrame=false;
        if(snapshot!=null){snapshot.close();snapshot=null;}if(material!=null){material.close();material=null;}
        if(musicGlow!=null){musicGlow.release();musicGlow=null;}
        if(card!=null){card.release();card=null;}if(animator!=0){HyperOsNative.nativeDestroyAnimator(animator);animator=0;}
        for(HyperOsCompactFactory.Compact owner:compactOwners.values())owner.release();compactOwners.clear();mainCompact=null;secondaryCompact=null;secondaryKey=null;
        launchBody.discardDisplayList();launchPrepared=false;launchStarted=false;
        if(contentLayer!=null&&remote!=null)remote.removeView(contentLayer);
        contentLayer=null;compact=null;mainSmall=null;prepared=false;lastFrame=0;
        super.onDetachedFromWindow();
    }
}
