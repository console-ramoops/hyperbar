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
    private final RectF outline = new RectF(), smallBounds = new RectF(), tertiaryBounds = new RectF();
    private final Path clip = new Path();
    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sceneMap = new Matrix();
    private final ArrayList<IslandItem> items = new ArrayList<>();
    private final ArrayList<IslandItem> hiddenItems = new ArrayList<>();
    private final Set<String> previousItemKeys = new HashSet<>();
    private final Map<String,HyperOsCompactFactory.Compact> compactOwners = new LinkedHashMap<>();
    private final ArrayList<HyperOsCapsuleSurface> outgoingSurfaces = new ArrayList<>();
    private ViewGroup remote;
    private FrameLayout contentLayer, compact, mainSmall, small;
    private HyperOsCapsuleSurface secondarySurface, tertiarySurface, incomingSurface;
    private IslandItem incomingItem;
    private HyperOsCompactFactory.Compact mainCompact, secondaryCompact, tertiaryCompact;
    private String secondaryKey, tertiaryKey;
    private HyperOsResources resources;
    private HyperOsCardFactory.Card card;
    private HyperOsMaterial material;
    private HyperOsSnapshotBlur snapshot;
    private HyperOsBlurLayer blurLayer;
    private HyperOsMusicGlow musicGlow;
    private boolean injectedEffects, blurEnabled = true, glassEnabled = false;
    private long absorbStartNanos;
    private static final long ABSORB_DURATION_NANOS = 320_000_000L;
    private IslandItem primary, secondary, tertiary, expandedItem;
    private long animator, lastFrame, activeMask;
    private long smallAnimator, smallActiveMask, tertiaryActiveMask;
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
    private long touchDownTime;
    private String touchKey,tapKey;
    private boolean preparing, prepared, pendingFrame, expanded, suppressed, statusHidden, sleeping;
    private boolean nativeHidden = true, commitPending, callCommitted, closing, switchingExpand;
    private float shade;
    private int screenWidth, islandHeight, islandY, cutoutWidth, cutoutHeight, expandedWidth;
    private float centerY, clockClearance = -1f, batteryClearance = -1f;
    private String failure, committedKey;
    private long commitGeneration;
    private Runnable frameCommit;
    private android.view.ViewTreeObserver commitObserver;
    private final RenderNode launchBody=new RenderNode("HyperIsland launch source"), launchFrame=new RenderNode("HyperIsland launch frame");
    private final RectF launchBounds=new RectF();
    private boolean launchPrepared, launchStarted, launchCard;
    private int launchSurface;
    private float launchWidth,launchHeight,launchStartHeight,launchAlpha=1,launchCorner;

    public HyperIslandView(Context context, IslandHost host) {
        super(context);
        remote=this;
        controller = host; frames = Choreographer.getInstance();
        density = context.getResources().getDisplayMetrics().density;
        black.setColor(0xff000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density));
        launchFrame.setHasOverlappingRendering(true);
        launchFrame.setUseCompositingLayer(true, null);
        setWillNotDraw(false);
    }

    public void setBlurLayer(HyperOsBlurLayer layer) {
        if (blurLayer != null) blurLayer.setOnBlurReady(null);
        blurLayer = layer;
        if (blurLayer != null) {
            blurLayer.setBlurEnabled(blurEnabled);
            blurLayer.setOnBlurReady(this::invalidate);
        }
    }

    public void setVisualOptions(boolean blur, boolean glass) {
        boolean changed = blurEnabled != blur || glassEnabled != glass;
        blurEnabled = blur;
        glassEnabled = glass;
        if (blurLayer != null) blurLayer.setBlurEnabled(blur);
        if (prepared && !injectedEffects) syncGlassEffects();
        if (changed) requestFrame();
    }

    private void syncGlassEffects() {
        if (injectedEffects) return;
        if (glassEnabled) {
            if (material == null) {
                try { material = new HyperOsMaterial(getContext(), this::requestFrame); }
                catch (Throwable ignored) { material = null; }
            }
            if (snapshot == null && material != null) {
                try {
                    snapshot = new HyperOsSnapshotBlur(this, this::requestFrame);
                    snapshot.setGlassEnabled(material.usesGlass());
                } catch (Throwable ignored) { snapshot = null; }
            }
        } else {
            if (snapshot != null) { try { snapshot.close(); } catch (Throwable ignored) {} snapshot = null; }
            if (material != null) { try { material.close(); } catch (Throwable ignored) {} material = null; }
        }
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
        injectedEffects=true;glassEnabled=true;
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
                    // Light island blur (HyperOsBlurLayer) is enabled by default;
                    // full Bionics Liquid Glass refraction + snapshot blur is opt-in via glass_effect.
                    syncGlassEffects();
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
                float l=(float)state[4],t=(float)state[6],r=(float)state[5],b=(float)state[7];
                if(absorbStartNanos!=0L&&!expanded&&!closing){
                    float wave=absorbWave();
                    if(wave!=0f){
                        float cx=(l+r)*.5f,cy=(t+b)*.5f,hw=(r-l)*.5f*(1f+0.11f*wave),hh=(b-t)*.5f*(1f+0.09f*wave);
                        l=cx-hw;r=cx+hw;t=cy-hh;b=cy+hh;
                    }
                }
                result.setRoundRect(Math.round(l),Math.round(t),Math.round(r),Math.round(b),radius());
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
    private void evictOutgoingKey(String key){
        if(key==null)return;
        for(int i=outgoingSurfaces.size()-1;i>=0;i--){
            HyperOsCapsuleSurface surface=outgoingSurfaces.get(i);
            if(key.equals(surface.key)){surface.close();outgoingSurfaces.remove(i);}
        }
    }
    private void attachMainCompact(){
        if(mainCompact==null){compact.removeAllViews();mainSmall.removeAllViews();return;}
        if(mainCompact.view.getParent()==compact&&mainCompact.smallView().getParent()==mainSmall)return;
        evictOutgoingKey(primary==null?null:primary.key);
        compact.removeAllViews();mainSmall.removeAllViews();
        HyperOsCapsuleSurface.detach(mainCompact.view);HyperOsCapsuleSurface.detach(mainCompact.smallView());
        compact.addView(mainCompact.view,new FrameLayout.LayoutParams(-1,-1));
        mainSmall.addView(mainCompact.smallView(),new FrameLayout.LayoutParams(-1,-1));
    }
    private void updateCompactContents(boolean changed,long now) {
        if(primary==null)return;
        if(mainCompact==null || changed) {
            mainCompact=compactOwner(primary,now);attachMainCompact();
        } else {mainCompact.update(primary,now);attachMainCompact();}
        if(mainCompact.failure!=null)throw new IllegalStateException(mainCompact.failure);
        String nextKey=secondary==null?null:secondary.key;
        if(nextKey==null || !nextKey.equals(secondaryKey) || (secondary!=null&&secondarySurface==null)) {
            if(touchActive&&touchSurface==2)cancelTouch();
            destroySmallAnimator();secondaryCompact=null;
            secondaryKey=nextKey;
            if(secondary!=null) {
                evictOutgoingKey(nextKey);
                secondaryCompact=compactOwner(secondary,now);
                secondarySurface=new HyperOsCapsuleSurface(resources,nextKey,secondaryCompact);
                if((nativeHidden&&!expanded&&!closing)||expanded)secondarySurface.startKind=4;
                small=secondarySurface;smallAnimator=secondarySurface.animator;
                addCapsuleSurface(small);
            }
        } else if(secondaryCompact!=null)secondaryCompact.update(secondary,now);
        if(secondaryCompact!=null&&secondaryCompact.failure!=null)throw new IllegalStateException(secondaryCompact.failure);
        syncTertiary(now);
    }
    private void syncTertiary(long now) {
        tertiary=expanded&&secondary!=null&&!hiddenItems.isEmpty()?hiddenItems.get(0):null;
        String nextTertiaryKey=tertiary==null?null:tertiary.key;
        if(nextTertiaryKey==null || !nextTertiaryKey.equals(tertiaryKey) || (tertiary!=null&&tertiarySurface==null)) {
            if(touchActive&&touchSurface==5)cancelTouch();
            destroyTertiaryAnimator();tertiaryCompact=null;
            tertiaryKey=nextTertiaryKey;
            if(tertiary!=null) {
                evictOutgoingKey(nextTertiaryKey);
                tertiaryCompact=compactOwner(tertiary,now);
                tertiarySurface=new HyperOsCapsuleSurface(resources,nextTertiaryKey,tertiaryCompact);
                tertiarySurface.startKind=4;tertiarySurface.role=3;
                addCapsuleSurface(tertiarySurface);
            }
        } else if(tertiaryCompact!=null)tertiaryCompact.update(tertiary,now);
        if(tertiaryCompact!=null&&tertiaryCompact.failure!=null)throw new IllegalStateException(tertiaryCompact.failure);
    }

    private void addCapsuleSurface(View surface){
        if(remote==null||surface==null)return;
        int index=contentLayer!=null?Math.max(0,remote.indexOfChild(contentLayer)):0;
        remote.addView(surface,index,new FrameLayout.LayoutParams(-1,-1));
        int rw=remote.getWidth(),rh=remote.getHeight();
        if(rw>0&&rh>0){
            surface.measure(MeasureSpec.makeMeasureSpec(rw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rh,MeasureSpec.EXACTLY));
            surface.layout(0,0,rw,rh);
        }
    }
    public void setStatusBarClearance(float clock,float battery){
        if(Math.abs(clockClearance-clock)<.5f&&Math.abs(batteryClearance-battery)<.5f)return;
        clockClearance=clock;batteryClearance=battery;
        if(prepared&&screenWidth>0&&primary!=null){
            double prevW=geometry[4],prevX=geometry[5];
            layoutGeometry();
            if(!expanded&&!closing&&(prevW!=geometry[4]||prevX!=geometry[5]))transition(4);
            requestFrame();
        }
    }
    private int smallWidth(){return Math.max(px(34),islandHeight);}
    private float[] capsuleLimits(){
        boolean soloCharging=primary!=null&&secondary==null&&primary.kind==IslandItem.Kind.CHARGING;
        float max=Math.min(px(soloCharging?280:250),Math.max(px(120),screenWidth-px(24)));
        if(soloCharging)return new float[]{max,-1f,-1f};
        float clock=clockClearance>0?clockClearance:(screenWidth>=px(320)?px(64):-1f);
        float battery=batteryClearance>0?batteryClearance:(screenWidth>=px(320)?px(54):-1f);
        return new float[]{max,clock,battery};
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
        previousItemKeys.clear();
        for(IslandItem it:items)if(it!=null&&it.key!=null)previousItemKeys.add(it.key);
        for(IslandItem it:hiddenItems)if(it!=null&&it.key!=null)previousItemKeys.add(it.key);
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
        boolean autoCollapsed=false;
        if(expanded&&!switchingExpand) {
            IslandItem replacement=find(expandedItem==null?null:expandedItem.key);
            if(replacement==null) {
                boolean promotedExisting=primary==null || previousItemKeys.contains(primary.key);
                if(promotedExisting) {
                    // Expanded activity disappeared or entered its own app: collapse cleanly
                    // instead of silently hijacking and expanding the next queued activity.
                    expanded=false;expandedItem=null;cancelCallCommit();
                    if(card!=null){contentLayer.removeView(card.view);card.release();card=null;}
                    closing=false;autoCollapsed=true;
                    HyperOsIncomingCalls.reportHidden();controller.onExpandedChanged(false);
                } else {
                    // Brand-new sole replacement (e.g. re-keyed notification or fixture swap): update card in place.
                    expandedItem=primary;
                    rebuildCard();
                }
            } else {
                expandedItem=replacement;
                if(card!=null)card.update(replacement,SystemClock.elapsedRealtime());
            }
        }
        tertiary=expanded&&secondary!=null&&!hiddenItems.isEmpty()?hiddenItems.get(0):null;
        if(touchActive&&((touchSurface==1&&!sameItem(before,primary))||(touchSurface==2&&!sameItem(oldSecondary,secondary))
            ||(touchSurface==5&&tertiary==null)||(touchSurface==4&&primary!=null)
            ||touchKey!=null&&findSource(touchKey)==null||incomingItem!=null&&findSource(incomingItem.key)==null))cancelTouch();
        boolean handoff;
        try{handoff=((!expanded&&!closing)||switchingExpand||autoCollapsed)&&reconcileCapsuleOwners(before,oldSecondary);}
        catch(Throwable error){fail(error);return;}
        if(primary==null) {
            destroySmallAnimator();destroyTertiaryAnimator();
            if(!nativeHidden&&!handoff)transition(expanded?7:6);
            expanded=false; closing=card!=null; nativeHidden=true;
            if(musicGlow!=null)musicGlow.clear();
            HyperOsIncomingCalls.reportHidden();pruneCompactOwners();
            if(!closing)controller.onWindowHeightNeeded(islandY+islandHeight+px(12));
            requestFrame(); return;
        }
        boolean changed=before==null || !before.key.equals(primary.key);
        double previousBigWidth=geometry[4],previousBigX=geometry[5];
        if(expanded && expandedItem!=null && expandedItem.isMedia) {
            if(musicGlow!=null)musicGlow.onMediaEnter(expandedItem.key);
        } else if(closing) {
            if(musicGlow!=null)musicGlow.onClosing();
        } else {
            if(musicGlow!=null && musicGlow.hasActiveMedia())musicGlow.clear();
        }
        try {updateCompactContents(changed,SystemClock.elapsedRealtime());}
        catch(Throwable error){fail(error);return;}
        if(switchingExpand){pruneCompactOwners();requestFrame();return;}
        layoutGeometry();
        boolean wasHidden=nativeHidden;
        if(HyperOsIncomingCalls.isIncoming(primary) && (changed || !expanded)) {
            expandedItem=primary; expanded=true; nativeHidden=false;
            rebuildCard(); layoutGeometry(); transition(before==null?11:1);
            controller.onExpandedChanged(true);
        } else if(handoff) {nativeHidden=false;transition(handoffMainTransition);}
        else if(nativeHidden) {nativeHidden=false;transition(0);}
        else if(changed && expanded)transition(1);
        else if(changed && !expanded)transition(4);
        else if(!expanded && (previousBigWidth!=geometry[4] || previousBigX!=geometry[5]))transition(4);
        if(handoff&&secondarySurface!=null)secondarySurface.transition(expanded?4:handoffSmallTransition,motionScale());
        else if(wasHidden&&!expanded&&secondarySurface!=null&&secondarySurface.startKind==4)secondarySurface.transition(14,motionScale());
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
            previous.configure(geometry);System.arraycopy(state,0,previous.values,0,state.length);previous.initialized=true;previous.role=expanded?2:1;
            addCapsuleSurface(previous);previous.setSceneVisibility(sceneVisibility());
            candidates.add(previous);animator=0;mainCompact=null;
        }
        if(changeSmall&&secondarySurface!=null){candidates.add(secondarySurface);secondarySurface=null;secondaryCompact=null;small=null;smallAnimator=0;}
        if(tertiarySurface!=null&&(tertiary==null||!tertiary.key.equals(tertiarySurface.key))){candidates.add(tertiarySurface);tertiarySurface=null;tertiaryCompact=null;tertiaryKey=null;tertiaryActiveMask=0;tertiaryBounds.setEmpty();}
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
                    addCapsuleSurface(secondarySurface);
                }
                handoffSmallTransition=secondarySurface.role==0?14:3;
                if(!switchingExpand)secondarySurface.role=3;
                secondaryCompact=secondarySurface.content;small=secondarySurface;smallAnimator=secondarySurface.animator;
            }
        }
        for(HyperOsCapsuleSurface surface:candidates){
            surface.transition(surface.role==1?6:surface.role==2?7:13,motionScale());surface.role=0;outgoingSurfaces.add(surface);
        }
        mainCapsulePreview=false;smallCapsulePreview=false;incomingCapsulePreview=false;capsulePreviewActive=false;
        return true;
    }
    private static HyperOsCapsuleSurface takeSurface(List<HyperOsCapsuleSurface> candidates,String key){
        if(key==null)return null;for(int i=0;i<candidates.size();i++)if(key.equals(candidates.get(i).key))return candidates.remove(i);return null;
    }
    private double[] capsuleMetrics(HyperOsCompactFactory.Compact content,double[] base,boolean paired){
        double[] result=base.clone();int[] measured=new int[9];
        int sw=smallWidth();
        HyperOsNative.nativeCapsule(new int[]{content.leftMeasured(),content.rightMeasured(),cutoutWidth,screenWidth,getResources().getDisplayMetrics().heightPixels,
            sw,px(4),px(108),px(30)},capsuleLimits(),getLayoutDirection()==LAYOUT_DIRECTION_RTL,false,measured);
        result[3]=measured[0];result[4]=paired?measured[5]:measured[0];result[5]=paired?measured[8]:measured[3];
        result[16]=paired?measured[6]:measured[1];result[17]=paired?measured[7]:measured[2];result[21]=geometry[3];
        return result;
    }
    private void pruneCompactOwners(){
        Set<String> retained=new HashSet<>();for(IslandItem item:items)retained.add(item.key);for(IslandItem item:hiddenItems)retained.add(item.key);
        if(mainCompact!=null&&primary!=null)retained.add(primary.key);
        if(secondarySurface!=null)retained.add(secondarySurface.key);if(tertiarySurface!=null)retained.add(tertiarySurface.key);if(incomingSurface!=null)retained.add(incomingSurface.key);
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)retained.add(surface.key);
        java.util.Iterator<Map.Entry<String,HyperOsCompactFactory.Compact>> entries=compactOwners.entrySet().iterator();
        while(entries.hasNext()){Map.Entry<String,HyperOsCompactFactory.Compact> entry=entries.next();if(!retained.contains(entry.getKey())){entry.getValue().release();entries.remove();}}
    }
    private void clearExtraSurfaces(){
        destroyTertiaryAnimator();
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
        if(tertiarySurface!=null&&tertiarySurface.needsFrames())return true;
        if(incomingSurface!=null&&incomingSurface.needsFrames())return true;
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)if(surface.needsFrames())return true;return false;
    }
    private boolean capsuleOwnersAnimating(){
        return incomingSurface!=null&&incomingSurface.activeMask!=0;
    }
    private void rebuildCard() {
        cancelCallCommit();
        if(card!=null){contentLayer.removeView(card.view);card.release();card=null;}
        if(!prepared || contentLayer==null || expandedItem==null)return;
        card=HyperOsCardFactory.create(getContext(),controller,expandedItem,expandedWidth);
        if(card.failure!=null){
            HyperOsIncomingCalls.reportHidden();
            if(injectedEffects){failure=card.failure;return;}
            android.util.Log.e("HyperIsland","Card build failed; falling back to compact pill: "+card.failure);
            card.release();card=null;expanded=false;expandedItem=null;closing=false;
            controller.onExpandedChanged(false);
            return;
        }
        failure=null;
        card.view.setOnClickListener(view -> open(expandedItem));
        contentLayer.addView(card.view,new FrameLayout.LayoutParams(expandedWidth,card.height()));
    }

    private void layoutGeometry() {
        if(primary==null || compact==null || screenWidth<=0)return;
        if(mainCompact==null)return;
        int left=mainCompact.leftMeasured(),right=mainCompact.rightMeasured();
        int sw=smallWidth();
        int[] dims={left,right,cutoutWidth,screenWidth,getResources().getDisplayMetrics().heightPixels,
            sw,px(4),px(108),px(30)};
        HyperOsNative.nativeCapsule(dims,capsuleLimits(),getLayoutDirection()==LAYOUT_DIRECTION_RTL,false,capsule);
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
        int smallX=getLayoutDirection()==LAYOUT_DIRECTION_RTL?bigX-px(4)-sw:bigX+bigWidth+px(4);
        smallBounds.set(smallX,islandY,smallX+sw,islandY+islandHeight);
        geometry[0]=screenWidth;geometry[1]=islandHeight;geometry[2]=islandY;
        geometry[3]=capsule[0];geometry[4]=bigWidth;geometry[5]=bigX;
        geometry[6]=expandedWidth;geometry[7]=expandedWidth;geometry[8]=cardHeight;
        geometry[9]=expandedY;geometry[10]=margin;geometry[11]=sw;geometry[12]=smallX;
        geometry[13]=cutoutHeight;geometry[14]=centerY;geometry[15]=cutoutWidth;
        geometry[16]=leftWidth;geometry[17]=rightWidth;geometry[18]=density;
        geometry[19]=0;geometry[20]=getLayoutDirection()==LAYOUT_DIRECTION_RTL?1:0;
        geometry[21]=capsule[0];
        if(card!=null){FrameLayout.LayoutParams cardParams=new FrameLayout.LayoutParams(expandedWidth,cardHeight);
            cardParams.leftMargin=margin;cardParams.topMargin=islandY;card.view.setLayoutParams(cardParams);
            card.view.layout(margin,islandY,margin+expandedWidth,islandY+cardHeight);}
        FrameLayout.LayoutParams iconParams=new FrameLayout.LayoutParams(sw,islandHeight);
        iconParams.leftMargin=screenWidth/2-sw/2;iconParams.topMargin=islandY;mainSmall.setLayoutParams(iconParams);
        mainSmall.layout(iconParams.leftMargin,islandY,iconParams.leftMargin+sw,islandY+islandHeight);
        if(secondarySurface!=null){
            boolean topBig=expanded&&secondary!=null;
            boolean topPaired=topBig&&tertiary!=null;
            double[] metrics=capsuleMetrics(secondaryCompact,geometry,topBig?topPaired:true);
            int targetRole=topBig?1:3;
            int prevRole=secondarySurface.role;
            boolean roleChanged=secondarySurface.initialized&&prevRole!=targetRole;
            boolean moved=secondarySurface.initialized&&(topBig
                ?(secondarySurface.metrics[4]!=metrics[4]||secondarySurface.metrics[5]!=metrics[5]||secondarySurface.metrics[2]!=islandY||secondarySurface.metrics[1]!=islandHeight)
                :(secondarySurface.metrics[12]!=smallX||secondarySurface.metrics[2]!=islandY||secondarySurface.metrics[1]!=islandHeight));
            secondarySurface.configure(metrics);
            if(!secondarySurface.initialized){
                secondarySurface.role=targetRole;
                secondarySurface.snap(topBig?0:secondarySurface.startKind);
                if(topBig)secondarySurface.transition(0,motionScale());
            } else if((roleChanged||moved)&&!capsulePreviewActive&&!switchingExpand){
                secondarySurface.role=targetRole;
                int trans=topBig?(prevRole==2?2:4):3;
                secondarySurface.transition(trans,motionScale());
            }
            smallAnimator=secondarySurface.animator;smallActiveMask=secondarySurface.activeMask;
            System.arraycopy(secondarySurface.values,0,smallState,0,smallState.length);updateSmallBounds();
            if(tertiarySurface!=null&&topPaired){
                int topSmallX=getLayoutDirection()==LAYOUT_DIRECTION_RTL?(int)metrics[5]-px(4)-sw:(int)metrics[5]+(int)metrics[4]+px(4);
                double[] tMetrics=metrics.clone();
                tMetrics[11]=sw;tMetrics[12]=topSmallX;
                boolean tMoved=tertiarySurface.initialized&&(tertiarySurface.metrics[12]!=topSmallX||tertiarySurface.metrics[2]!=islandY||tertiarySurface.metrics[1]!=islandHeight);
                tertiarySurface.configure(tMetrics);
                if(!tertiarySurface.initialized){
                    tertiarySurface.role=3;
                    tertiarySurface.snap(4);
                    tertiarySurface.transition(14,motionScale());
                } else if(tMoved&&!switchingExpand){
                    tertiarySurface.role=3;
                    tertiarySurface.transition(3,motionScale());
                }
                tertiaryActiveMask=tertiarySurface.activeMask;
                tertiaryBounds.set(tertiarySurface.bounds);
            }
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
    private void destroyTertiaryAnimator(){
        if(tertiarySurface!=null){tertiarySurface.close();tertiarySurface=null;}
        tertiaryActiveMask=0;tertiaryCompact=null;tertiaryKey=null;tertiaryBounds.setEmpty();
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
            if(tertiarySurface!=null){tertiarySurface.frame(delta,predicted);tertiaryActiveMask=tertiarySurface.activeMask;tertiaryBounds.set(tertiarySurface.bounds);}
            stepExtraSurfaces(delta,predicted);
            if(primary!=null){
                int prevLeft=mainCompact==null?-1:mainCompact.leftMeasured(),prevRight=mainCompact==null?-1:mainCompact.rightMeasured();
                updateCompactContents(false,SystemClock.elapsedRealtime());
                if(mainCompact!=null&&(prevLeft!=mainCompact.leftMeasured()||prevRight!=mainCompact.rightMeasured())){
                    double prevW=geometry[4],prevX=geometry[5];
                    layoutGeometry();
                    if(!expanded&&!closing&&!capsulePreviewActive&&(prevW!=geometry[4]||prevX!=geometry[5]))transition(4);
                }
            }
            if(card!=null){int previousHeight=card.height();card.update(expandedItem,SystemClock.elapsedRealtime());
                if(previousHeight!=card.height()){layoutGeometry();if(expanded)transition(1);}}
            applyState();
            if(visibleAlpha()<=.001f && musicGlow!=null){musicGlow.clear();}
            invalidate();controller.onIslandFrame();
            if(closing && activeMask==0) {
                closing=false;if(card!=null){contentLayer.removeView(card.view);card.release();card=null;}
                if(snapshot!=null)snapshot.setActive(false);layoutGeometry();
            }
            if(snapshot!=null)snapshot.setAnimating(activeMask!=0||smallActiveMask!=0||launchStarted);
            if(activeMask!=0 || smallActiveMask!=0 || tertiaryActiveMask!=0 || absorbStartNanos!=0L || commitPending || card!=null && card.needsFrames() || mainCompact!=null&&mainCompact.needsFrames()
                || secondaryCompact!=null&&secondaryCompact.needsFrames() || tertiaryCompact!=null&&tertiaryCompact.needsFrames() || extraSurfacesNeedFrames() || (visibleAlpha()>.001f && musicGlow!=null && musicGlow.isRunning()))requestFrame();
            else lastFrame=0;
        }catch(Throwable error){fail(error);}
    }

    private void applyState() {
        if(contentLayer==null)return;
        contentLayer.setVisibility(visibleAlpha()>.001f?View.VISIBLE:View.INVISIBLE);
        contentLayer.setTranslationX((float)state[1]);contentLayer.setTranslationY((float)state[2]);
        contentLayer.setAlpha(visibleAlpha());contentLayer.invalidateOutline();
        float abWave=absorbWave();
        compact.setScaleX((float)state[10]*(1f+0.11f*abWave));compact.setScaleY((float)state[10]*(1f+0.09f*abWave));compact.setAlpha((float)state[9]);
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
        float scene=sceneVisibility();
        if(secondarySurface!=null)secondarySurface.setSceneVisibility(scene);
        if(tertiarySurface!=null)tertiarySurface.setSceneVisibility(scene);
        if(incomingSurface!=null)incomingSurface.setSceneVisibility(scene);
        for(HyperOsCapsuleSurface surface:outgoingSurfaces)surface.setSceneVisibility(scene);
        boolean visible=visibleAlpha()>.001f&&!launchStarted;
        setAnimationVisibility(visible&&(state[9]>.001||state[17]>.001),secondarySurface!=null&&secondarySurface.getAlpha()>.001f,visible&&state[22]>.001);
        if(tertiaryCompact!=null)tertiaryCompact.setOwnerVisible(tertiarySurface!=null&&tertiarySurface.getAlpha()>.001f);
        boolean glassActive=(glassEnabled||injectedEffects)&&material!=null&&snapshot!=null&&snapshot.hasTexture(material.usesGlass());
        if(blurLayer!=null){
            float mix=glassMix();float alpha=visibleAlpha();
            if(blurEnabled&&!glassActive&&!launchStarted&&!launchPrepared&&mix>.001f&&alpha>.001f){
                currentBounds(outline);
                blurLayer.place(outline,radius(),mix*alpha,HyperOsBlurLayer.LOW_BLUR_RADIUS);
            }else{
                blurLayer.hide();
            }
        }
        if(snapshot!=null)snapshot.setActive(!launchStarted&&!launchPrepared&&(expanded || closing || glassMix()>.001f) && visibleAlpha()>.001f);
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
        // Per-frame HWUI RenderEffect blur disabled for low-lag rendering; backdrop blur uses HyperOsBlurLayer.
    }
    private float radius(){return Math.max(0,Math.min(((float)state[7]-(float)state[6])/2,px(30)));}
    private void currentBounds(RectF out) {
        out.set((int)state[4]+(float)state[1],(int)state[6]+(float)state[2],
            (int)state[5]+(float)state[1],(int)state[7]+(float)state[2]);
        if(absorbStartNanos!=0L&&!expanded&&!closing){
            float wave=absorbWave();
            if(wave!=0f){
                float cx=out.centerX(),cy=out.centerY();
                float hw=out.width()*.5f*(1f+0.11f*wave),hh=out.height()*.5f*(1f+0.09f*wave);
                out.set(cx-hw,cy-hh,cx+hw,cy+hh);
            }
        }
    }

    public float glassMix() {
        if(!expanded && !closing)return 0f;
        float currentHeight=(float)(state[7]-state[6]);
        float compactHeight=Math.max(1f,(float)geometry[1]);
        float expandedHeight=(float)geometry[8];
        float heightMix=expandedHeight>compactHeight+1f
            ?Math.max(0f,Math.min(1f,(currentHeight-compactHeight)/(expandedHeight-compactHeight)))
            :(expanded?1f:0f);
        float cardMix=Math.max(0f,Math.min(1f,(float)state[22]));
        return Math.max(heightMix,cardMix);
    }

    @Override protected void onDraw(Canvas canvas) {
        if(launchStarted){if(musicGlow!=null)musicGlow.clear();drawLaunch(canvas);return;}
        float alpha=visibleAlpha();
        if(!prepared || alpha<=.001f){if(musicGlow!=null)musicGlow.clear();return;}
        currentBounds(outline);
        if(outline.isEmpty()){if(musicGlow!=null)musicGlow.clear();return;}
        if(musicGlow!=null && ((expanded && expandedItem!=null && expandedItem.isMedia) || closing || musicGlow.isRunning())) {
            musicGlow.sampleFrame(SystemClock.elapsedRealtime(), animationScale());
            musicGlow.drawBackground(canvas, outline, radius(), alpha, density);
        }
        float mix=glassMix();
        boolean hasBackdrop=(glassEnabled||injectedEffects)&&snapshot!=null&&material!=null&&snapshot.hasTexture(material.usesGlass());
        boolean canGlass=mix>.001f && material!=null && snapshot!=null && hasBackdrop;
        if(canGlass) {
            float blackAlpha=alpha*(1f-mix*mix);
            if(blackAlpha>.001f) {
                black.setColor(0xff000000);black.setAlpha(Math.round(255f*blackAlpha));
                canvas.drawRoundRect(outline,radius(),radius(),black);
            }
            int save=canvas.save();
            if(material.usesGlass())canvas.clipRect(outline);
            else{clip.reset();clip.addRoundRect(outline,radius(),radius(),Path.Direction.CW);canvas.clipPath(clip);}
            snapshot.sceneToWindow(sceneMap);
            float glassAlpha=alpha*mix;
            if(!material.drawGlass(canvas,outline,snapshot.refractBitmap(),snapshot.colorBitmap(),sceneMap,density,glassAlpha))
                material.draw(canvas,outline,snapshot.bitmap(),null,sceneMap,density,glassAlpha,false);
            canvas.restoreToCount(save);
        } else {
            boolean hasCapturedBlur=blurEnabled&&blurLayer!=null&&blurLayer.usesCapturedBlur();
            boolean hasCompositorBlur=blurEnabled&&blurLayer!=null&&blurLayer.available();
            int targetColor=hasCapturedBlur?0x2814161a:hasCompositorBlur?0x8c14161a:0xf2161619;
            int fallbackColor=mix>.001f?blendColor(0xff000000,targetColor,mix):0xff000000;
            black.setColor(fallbackColor);
            black.setAlpha(Math.round(((fallbackColor>>>24)&0xff)*alpha));
            float r=radius();
            canvas.drawRoundRect(outline,r,r,black);
            if(mix>.05f){
                rim.setColor(0xffffffff);
                rim.setAlpha(Math.round((hasCompositorBlur?38f:30f)*mix*alpha));
                float halfStroke=rim.getStrokeWidth()*.5f;
                canvas.drawRoundRect(outline.left+halfStroke,outline.top+halfStroke,
                    outline.right-halfStroke,outline.bottom-halfStroke,Math.max(0f,r-halfStroke),Math.max(0f,r-halfStroke),rim);
            }
            black.setColor(0xff000000);
        }
    }
    private static int blendColor(int from,int to,float t){
        float c=Math.max(0f,Math.min(1f,t));
        int a=Math.round(((from>>>24)&0xff)+(((to>>>24)&0xff)-((from>>>24)&0xff))*c);
        int r=Math.round(((from>>>16)&0xff)+(((to>>>16)&0xff)-((from>>>16)&0xff))*c);
        int g=Math.round(((from>>>8)&0xff)+(((to>>>8)&0xff)-((from>>>8)&0xff))*c);
        int b=Math.round((from&0xff)+((to&0xff)-(from&0xff))*c);
        return (a<<24)|(r<<16)|(g<<8)|b;
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
            touchDownTime=event.getDownTime();
            if(restoreTouch)touchSurface=4;
            else if(expanded){
                if(tertiary!=null&&tertiarySurface!=null&&hitBox(tertiaryBounds,px(4),touchX,touchY))touchSurface=5;
                else if(secondary!=null&&secondarySurface!=null&&hitBox(smallBounds,px(4),touchX,touchY))touchSurface=2;
                else{currentBounds(outline);touchSurface=outline.contains(touchX,touchY)?3:0;}
            }
            else if(secondary!=null&&hitBox(smallBounds,px(4),touchX,touchY))touchSurface=2;
            else{currentBounds(outline);touchSurface=outline.contains(touchX,touchY)?1:0;}
            if(!expanded&&(touchSurface==1||touchSurface==2)&&snapshot!=null)snapshot.prewarm();
            IslandItem item=touchSurface==1?primary:touchSurface==2?secondary:touchSurface==3?expandedItem:touchSurface==5?tertiary:null;
            touchKey=item==null?null:item.key;
            boolean mediaControl=touchSurface==3&&expandedItem!=null&&card!=null
                &&(expandedItem.isMedia&&hitMediaControl(card.view,card.view,event.getRawX(),event.getRawY(),false)
                ||expandedItem.torch&&hitMediaControl(card.view,card.view,event.getRawX(),event.getRawY(),true));
            if(mediaControl)touchInSeek=hitMediaControl(card.view,card.view,event.getRawX(),event.getRawY(),true);
            if(touchSurface!=0&&touchSurface<4&&touchAllowed&&!mediaControl)setPress(touchSurface,true);
        }else if(action==MotionEvent.ACTION_MOVE&&touchActive){
            int pointer=event.findPointerIndex(touchPointer);if(pointer<0){cancelTouch();return;}
            int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
            float dx=event.getX(pointer)-touchX,dy=event.getY(pointer)-touchY;
            if(Math.abs(dx)>slop||Math.abs(dy)>slop){
                if(!touchMoved)touchDirection=Math.abs(dx)>Math.abs(dy)?dx>0?1:-1:dy>0?2:-2;
                touchMoved=true;releasePress();
                if(!expanded&&(touchSurface==1||touchSurface==2||touchSurface==4)&&touchAllowed&&Math.abs(touchDirection)==1)previewCapsule(dx);
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
            releasedQueueSwipe=!expanded&&touchActive&&touchMoved&&touchAllowed&&(touchSurface==1||touchSurface==2||touchSurface==4)&&Math.abs(touchDirection)==1&&Math.abs(dx)>threshold;
            queueDirection=(dx<0?-1:1)*(getLayoutDirection()==LAYOUT_DIRECTION_RTL?-1:1);
            if(!releasedQueueSwipe)resetCapsulePreview();
            collapseDrag=releasedDrag&&touchAllowed&&(Math.abs(touchDirection)==1&&Math.abs(dx)>threshold||touchDirection==-2&&-dy>threshold);
            if(collapseDrag)swipeActive=false;else resetSwipe();
            tapSurface=touchActive&&!touchMoved?touchSurface:0;tapKey=touchKey;
            releasePress();touchActive=false;touchKey=null;touchSurface=0;touchPointer=-1;
        }else if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_OUTSIDE
            ||action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP)cancelTouch();
    }
    private static boolean hitMediaControl(View rootCard,View view,float x,float y,boolean seekOnly){
        if(view.getVisibility()!=View.VISIBLE||!view.isEnabled())return false;
        if(view instanceof android.widget.SeekBar||(!seekOnly&&view!=rootCard&&view.hasOnClickListeners())){
            Rect bounds=new Rect();if(view.getGlobalVisibleRect(bounds)&&bounds.contains((int)x,(int)y))return true;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hitMediaControl(rootCard,((ViewGroup)view).getChildAt(i),x,y,seekOnly))return true;
        return false;
    }
    private static boolean hitBox(RectF box,int pad,float x,float y){
        return box!=null&&!box.isEmpty()&&x>=box.left-pad&&x<=box.right+pad&&y>=box.top-pad&&y<=box.bottom+pad;
    }
    private void setPress(int surface,boolean pressed){
        long owner=surface==2?smallAnimator:animator;if(owner==0)return;
        if(pressed)pressedSurface=surface;
        try{
            float scale=Settings.Global.getFloat(getContext().getContentResolver(),"island_animator_duration_scale",1);if(scale<=0||!Float.isFinite(scale))scale=1;
            int pressKind=surface==1?1:surface==2?(secondarySurface!=null&&secondarySurface.role==1?1:3):2;
            HyperOsNative.nativePressAnimator(owner,pressKind,surface==2&&secondarySurface!=null?secondarySurface.metrics:geometry,0xff000000,scale,pressed);
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
                    addCapsuleSurface(incomingSurface);
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
        if(releasedQueueSwipe){controller.onSwipe(queueDirection);if(capsulePreviewActive)resetCapsulePreview();return true;}
        if(releasedDrag){if(collapseDrag)collapse();return true;}
        if(expanded){
            if(tapSurface==5&&tertiary!=null&&!tertiary.noExpand){expand(tertiary);return true;}
            if(tapSurface==2&&secondary!=null&&!secondary.noExpand){expand(secondary);return true;}
            currentBounds(outline);if(!outline.contains(event.getX(),event.getY()))collapse();return true;
        }
        IslandItem item=tapKey==null?null:find(tapKey);
        if(tapSurface==4){controller.onRestore();return true;}
        if((tapSurface==1||tapSurface==2)&&item!=null){
            long holdMs=Math.max(0L,event.getEventTime()-touchDownTime);
            if(item.noExpand){
                if(canLaunch(item))openCapsule(item,tapSurface);
            }else if(holdMs>=380L&&canLaunch(item)){
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                openCapsule(item,tapSurface);
            }else{
                expand(item);
            }
        }
        return true;
    }
    private static boolean canLaunch(IslandItem item){
        if(item==null||item.torch)return false;
        return item.contentIntent!=null||item.mediaOpen!=null||(item.packageName!=null&&!"com.android.systemui".equals(item.packageName));
    }
    public void openCapsule(IslandItem item){
        if(item==null)return;
        int surface=secondary!=null&&item.key.equals(secondary.key)?2:1;
        openCapsule(item,surface);
    }
    private void openCapsule(IslandItem item,int surface){
        if(item==null)return;
        RectF bounds=new RectF();
        if(surface==2&&secondarySurface!=null&&!smallBounds.isEmpty())bounds.set(smallBounds);
        else currentBounds(bounds);
        if(bounds.isEmpty())getRestingPillBounds(bounds);
        launchSurface=surface;
        controller.onOpenCapsule(item,bounds);
    }
    public void expand(IslandItem item) {
        if(!prepared || item==null || item.noExpand)return;
        boolean wasExpanded=expanded;
        boolean tappingSecondary=secondary!=null&&item.key.equals(secondary.key);
        boolean tappingTertiary=tertiary!=null&&item.key.equals(tertiary.key);
        boolean fromSmall=!wasExpanded&&tappingSecondary&&secondarySurface!=null&&secondarySurface.role==3;
        if(tappingSecondary||tappingTertiary){
            switchingExpand=true;
            try{controller.onFocusItem(item);}finally{switchingExpand=false;}
            if(primary!=null&&item.key.equals(primary.key)){
                expandedItem=primary;expanded=true;closing=false;rebuildCard();syncTertiary(SystemClock.elapsedRealtime());layoutGeometry();
                transition(fromSmall?9:1);
                if(expandedItem.isMedia&&musicGlow!=null)musicGlow.onMediaEnter(expandedItem.key);
                controller.onExpandedChanged(true);
                return;
            }
        }
        expandedItem=item;expanded=true;closing=false;rebuildCard();syncTertiary(SystemClock.elapsedRealtime());layoutGeometry();
        if(fromSmall && animator!=0 && geometry[1]>0){
            HyperOsNative.nativeSnapAnimator(animator,3,geometry,0xff000000);
            transition(9);
        } else transition(1);
        if(expandedItem.isMedia && musicGlow!=null)musicGlow.onMediaEnter(expandedItem.key);
        controller.onExpandedChanged(true);
    }
    public void collapse() {
        if(!expanded)return;expanded=false;closing=card!=null;cancelCallCommit();
        if(musicGlow!=null)musicGlow.onClosing();
        HyperOsIncomingCalls.reportHidden();syncTertiary(SystemClock.elapsedRealtime());layoutGeometry();transition(2);controller.onExpandedChanged(false);
    }
    private void open(IslandItem item){if(item==null)return;launchSurface=3;RectF bounds=new RectF();currentBounds(bounds);controller.onOpenItem(item,bounds);}

    private static void installLaunchCallbacks() {
        HyperOsActivityLaunch.setSourceCallbacks(new HyperOsActivityLaunch.SourceCallbacks() {
            @Override public void onPrepare(View source,RectF bounds,boolean expanded) {
                HyperIslandView view=(HyperIslandView)source;
                View body=expanded && view.card!=null?view.card.view
                    :(!expanded && view.launchSurface==2 && view.secondarySurface!=null?view.secondarySurface:view.compact);
                if(body==null || body.getWidth()<=0 || body.getHeight()<=0)
                    throw new IllegalStateException("Launch source has no rendered content");
                if(view.snapshot!=null)view.snapshot.setActive(false);
                if(view.blurLayer!=null)view.blurLayer.hide();
                view.launchCard=expanded;
                view.launchWidth=body.getWidth();view.launchHeight=body.getHeight();
                view.launchStartHeight=Math.max(1f,bounds.height());
                view.launchBounds.set(bounds);view.launchCorner=Math.min(bounds.height()/2f,expanded?view.px(26):view.px(15));
                view.launchBody.setPosition(0,0,body.getWidth(),body.getHeight());
                RecordingCanvas record=view.launchBody.beginRecording(body.getWidth(),body.getHeight());
                try{body.draw(record);}finally{view.launchBody.endRecording();}
                view.launchPrepared=true;view.launchStarted=false;view.launchAlpha=1;
                int screenH=view.getResources().getDisplayMetrics().heightPixels;
                if(screenH>0)view.controller.onWindowHeightNeeded(Math.max(view.getHeight(),screenH));
            }
            @Override public void onStart(View source,HyperOsActivityMotion motion) {
                HyperIslandView view=(HyperIslandView)source;
                view.launchStarted=true;view.lastFrame=0;
                view.cancelCallCommit();
                if(view.snapshot!=null)view.snapshot.setActive(false);
                if(view.blurLayer!=null)view.blurLayer.hide();
                if(view.musicGlow!=null)view.musicGlow.clear();
                boolean keepSecondary=view.secondary!=null&&view.secondarySurface!=null&&(view.launchCard||view.launchSurface!=2);
                boolean keepTertiary=view.tertiary!=null&&view.tertiarySurface!=null&&view.launchCard;
                view.setAnimationVisibility(false,keepSecondary&&view.secondarySurface.getAlpha()>.001f,false);
                if(view.tertiaryCompact!=null)view.tertiaryCompact.setOwnerVisible(keepTertiary&&view.tertiarySurface.getAlpha()>.001f);
                if(view.contentLayer!=null)view.contentLayer.setVisibility(View.INVISIBLE);
                if(view.small!=null&&!keepSecondary)view.small.setVisibility(View.INVISIBLE);
                if(view.tertiarySurface!=null&&!keepTertiary)view.tertiarySurface.setVisibility(View.INVISIBLE);
                HyperOsIncomingCalls.reportHidden();view.invalidate();
            }
            @Override public void onFrame(View source,HyperOsActivityMotion.State state) {
                HyperIslandView view=(HyperIslandView)source;
                if(view.launchCard) {
                    if(state.sourceTracksApp) {
                        int[] location=new int[2];view.getLocationOnScreen(location);
                        view.launchBounds.set(state.bounds);view.launchBounds.offset(-location[0],-location[1]);
                    }
                    view.launchAlpha=state.sourceAlpha;view.launchCorner=state.corner;
                } else {
                    // ColorOS capsule-to-activity morph: track the opening capsule rect while
                    // crossfading the pill smoothly into the rising app task window.
                    int[] location=new int[2];view.getLocationOnScreen(location);
                    view.launchBounds.set(state.bounds);view.launchBounds.offset(-location[0],-location[1]);
                    float growth=(state.bounds.height()-view.launchStartHeight)/Math.max(1f,view.launchStartHeight*1.8f);
                    view.launchAlpha=Math.max(0f,Math.min(1f,1f-growth));
                    view.launchCorner=state.corner;
                }
                view.invalidate();
            }
            @Override public void onFinish(View source,boolean cancelled) {
                HyperIslandView view=(HyperIslandView)source;
                view.launchBody.discardDisplayList();view.launchFrame.discardDisplayList();
                boolean wasCard=view.launchCard;
                view.launchPrepared=false;view.launchStarted=false;view.launchCard=false;view.launchSurface=0;view.lastFrame=0;
                view.launchAlpha=1f;view.launchBounds.setEmpty();
                if(view.contentLayer!=null)view.contentLayer.setVisibility(View.VISIBLE);
                if(view.small!=null)view.small.setVisibility(View.VISIBLE);
                if(view.tertiarySurface!=null)view.tertiarySurface.setVisibility(View.VISIBLE);
                boolean wasExpanded=view.expanded;
                boolean collapseAfterLaunch=!cancelled||(wasExpanded&&view.find(view.expandedItem==null?null:view.expandedItem.key)==null);
                if(collapseAfterLaunch){
                    view.expanded=false;view.expandedItem=null;view.closing=false;view.cancelCallCommit();
                    if(view.card!=null){view.contentLayer.removeView(view.card.view);view.card.release();view.card=null;}
                    if(view.snapshot!=null)view.snapshot.setActive(false);
                    if(view.blurLayer!=null)view.blurLayer.hide();
                    HyperOsIncomingCalls.reportHidden();view.controller.onExpandedChanged(false);
                }
                if(!cancelled){
                    // The launched card/capsule already morphed into the opening app window.
                    // Never spawn a giant outgoing card surface from the pre-launch expanded state.
                    if(view.items.isEmpty()){
                        view.primary=null;view.secondary=null;view.tertiary=null;view.nativeHidden=true;
                        view.destroySmallAnimator();view.destroyTertiaryAnimator();view.clearExtraSurfaces();
                        if(view.animator!=0&&view.geometry[1]>0){
                            HyperOsNative.nativeSnapAnimator(view.animator,0,view.geometry,0xff000000);
                            view.activeMask=HyperOsNative.nativeAnimatorFrame(view.animator,0,0,1,view.state);
                        }
                        view.pruneCompactOwners();
                        view.controller.onWindowHeightNeeded(view.islandY+view.islandHeight+view.px(12));
                        view.applyState();view.requestFrame();
                        return;
                    }
                    if((wasExpanded||wasCard)&&view.state[22]>.01&&view.animator!=0&&view.geometry[1]>0){
                        HyperOsNative.nativeSnapAnimator(view.animator,1,view.geometry,0xff000000);
                        view.activeMask=HyperOsNative.nativeAnimatorFrame(view.animator,0,0,1,view.state);
                    }
                }
                view.refreshItems();
                if(view.primary!=null){
                    view.nativeHidden=false;
                    view.layoutGeometry();
                    if(!cancelled){
                        if(wasExpanded&&view.state[22]>.01&&view.animator!=0&&view.geometry[1]>0){
                            HyperOsNative.nativeSnapAnimator(view.animator,1,view.geometry,0xff000000);
                            view.activeMask=HyperOsNative.nativeAnimatorFrame(view.animator,0,0,1,view.state);
                        } else view.transition(4);
                    } else if(view.state[3]<=.01)view.transition(view.expanded?1:0);
                } else {
                    view.controller.onWindowHeightNeeded(view.islandY+view.islandHeight+view.px(12));
                }
                view.applyState();view.requestFrame();
            }
        });
    }

    private void drawLaunch(Canvas canvas) {
        if(launchAlpha<=.002f || !launchBody.hasDisplayList() || launchBounds.isEmpty())return;
        float clampedAlpha=Math.max(0f,Math.min(1f,launchAlpha));
        if(canvas.isHardwareAccelerated()) {
            int w=Math.max(1,(int)Math.ceil(launchBounds.width()));
            int h=Math.max(1,(int)Math.ceil(launchBounds.height()));
            launchFrame.setPosition(0,0,w,h);
            RecordingCanvas rec=launchFrame.beginRecording(w,h);
            try {
                clip.reset();clip.addRoundRect(0,0,launchBounds.width(),launchBounds.height(),launchCorner,launchCorner,Path.Direction.CW);
                rec.clipPath(clip);
                black.setColor(launchCard?0xff161619:0xff000000);black.setAlpha(255);
                rec.drawRoundRect(0,0,launchBounds.width(),launchBounds.height(),launchCorner,launchCorner,black);
                black.setColor(0xff000000);
                float scale=Math.max(launchBounds.width()/launchWidth,launchBounds.height()/launchHeight);
                rec.translate(launchBounds.width()/2f,launchBounds.height()/2f);rec.scale(scale,scale);
                rec.translate(-launchWidth/2f,-launchHeight/2f);rec.drawRenderNode(launchBody);
            } finally { launchFrame.endRecording(); }
            launchFrame.setAlpha(clampedAlpha);
            int save=canvas.save();
            canvas.translate(launchBounds.left,launchBounds.top);
            canvas.drawRenderNode(launchFrame);
            canvas.restoreToCount(save);
        } else {
            int layer=canvas.saveLayerAlpha(launchBounds,Math.round(clampedAlpha*255));
            clip.reset();clip.addRoundRect(launchBounds,launchCorner,launchCorner,Path.Direction.CW);canvas.clipPath(clip);
            black.setAlpha(255);canvas.drawRoundRect(launchBounds,launchCorner,launchCorner,black);
            float scale=Math.max(launchBounds.width()/launchWidth,launchBounds.height()/launchHeight);
            canvas.translate(launchBounds.centerX(),launchBounds.centerY());canvas.scale(scale,scale);
            canvas.translate(-launchWidth/2,-launchHeight/2);canvas.drawRenderNode(launchBody);
            canvas.restoreToCount(layer);
        }
    }
    public boolean isExpanded(){return expanded;}
    public boolean isLaunchActive(){return launchPrepared||launchStarted;}
    public boolean isFullyHidden(){return !prepared||sceneVisibility()<=.001f||visibleAlpha()<=.001f&&!extraSurfacesVisible();}
    private float sceneVisibility(){return failure!=null||suppressed||statusHidden||sleeping?0:1-Math.max(0,Math.min(1,shade));}
    private boolean extraSurfacesVisible(){if(secondarySurface!=null&&secondarySurface.getAlpha()>.001f)return true;if(tertiarySurface!=null&&tertiarySurface.getAlpha()>.001f)return true;if(incomingSurface!=null&&incomingSurface.getAlpha()>.001f)return true;for(HyperOsCapsuleSurface surface:outgoingSurfaces)if(surface.getAlpha()>.001f)return true;return false;}
    public float visibleAlpha(){return sceneVisibility()*Math.max(0,Math.min(1,(float)state[3]));}
    public boolean needsAudio(){return false;}
    public void getCoverageBounds(RectF out){if(launchStarted){out.set(launchBounds);if(secondary!=null&&secondarySurface!=null&&(launchCard||launchSurface!=2))out.union(smallBounds);if(tertiary!=null&&tertiarySurface!=null&&launchCard)out.union(tertiaryBounds);return;}currentBounds(out);if(secondary!=null)out.union(smallBounds);if(tertiary!=null&&tertiarySurface!=null)out.union(tertiaryBounds);if(incomingSurface!=null)out.union(incomingSurface.bounds);for(HyperOsCapsuleSurface surface:outgoingSurfaces)out.union(surface.bounds);}
    public void getRestingPillBounds(RectF out){out.set((float)geometry[5],islandY,(float)geometry[5]+(float)geometry[4],islandY+islandHeight);}
    public void getTouchRegion(Region out) {
        out.setEmpty();
        if(primary==null&&controller.hasMinimized()&&canReceiveTouch()){
            restoreBounds(outline);out.set((int)outline.left,(int)outline.top,(int)outline.right,(int)outline.bottom);return;
        }
        if(visibleAlpha()<.4f)return;
        if(expanded){out.set(0,0,screenWidth,Math.max(getHeight(),(int)(geometry[9]+geometry[8]+px(32))));return;}
        currentBounds(outline);out.op((int)outline.left-px(4),(int)outline.top-px(4),(int)outline.right+px(4),(int)outline.bottom+px(4),Region.Op.UNION);
        if(secondary!=null)out.op((int)smallBounds.left-px(4),(int)smallBounds.top-px(4),(int)smallBounds.right+px(4),(int)smallBounds.bottom+px(4),Region.Op.UNION);
        if(tertiary!=null&&tertiarySurface!=null)out.op((int)tertiaryBounds.left-px(4),(int)tertiaryBounds.top-px(4),(int)tertiaryBounds.right+px(4),(int)tertiaryBounds.bottom+px(4),Region.Op.UNION);
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
    public void absorb(){
        if(!prepared||expanded||launchStarted)return;
        absorbStartNanos=SystemClock.elapsedRealtimeNanos();
        requestFrame();
    }
    private float absorbWave(){
        if(absorbStartNanos==0L||expanded||closing||launchStarted){absorbStartNanos=0L;return 0f;}
        long elapsed=SystemClock.elapsedRealtimeNanos()-absorbStartNanos;
        if(elapsed<0L)return 0f;
        if(elapsed>=ABSORB_DURATION_NANOS){absorbStartNanos=0L;return 0f;}
        float t=elapsed/(float)ABSORB_DURATION_NANOS;
        return (float)(Math.sin(t*Math.PI*2.0)*Math.exp(-3.8*t));
    }
    public void regrowPillNow(){requestFrame();}
    public void popBubbleNow(){requestFrame();}
    public void fadePillBlobNow(){requestFrame();}
    public void onSettingsChanged(){if(prepared){rebuildCard();layoutGeometry();transition(expanded?1:4);}}
    private void updateVisibility(){
        cancelTouch();HyperOsIncomingCalls.reportHidden();cancelCallCommit();
        if(visibleAlpha()<=.001f){
            lastFrame=0;
            if(musicGlow!=null)musicGlow.clear();
            setAnimationVisibility(false,false,false);
            if(tertiaryCompact!=null)tertiaryCompact.setOwnerVisible(false);
        }
        requestFrame();
    }
    private int px(float value){return Math.round(value*density);}
    private void requestFrame(){if(!pendingFrame&&isAttachedToWindow()){pendingFrame=true;frames.postFrameCallback(this);}invalidate();}
    private void fail(Throwable error){
        failure=error.toString();capsulePreviewActive=false;mainCapsulePreview=false;smallCapsulePreview=false;incomingCapsulePreview=false;
        touchActive=false;touchAllowed=false;pressedSurface=0;cancelCallCommit();setAnimationVisibility(false,false,false);
        clearExtraSurfaces();if(secondarySurface!=null)secondarySurface.setSceneVisibility(0);
        if(blurLayer!=null)blurLayer.hide();
        HyperOsIncomingCalls.reportHidden();if(contentLayer!=null)contentLayer.setVisibility(View.INVISIBLE);
        android.util.Log.e("HyperIsland", "Renderer initialization/frame failed",error);
    }
    public String failure(){return failure;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();prepare();requestFrame();}
    @Override protected void onDetachedFromWindow(){
        cancelTouch();destroySmallAnimator();destroyTertiaryAnimator();clearExtraSurfaces();
        cancelCallCommit();
        if(blurLayer!=null)blurLayer.hide();
        HyperOsIncomingCalls.reportHidden();frames.removeFrameCallback(this);pendingFrame=false;
        if(snapshot!=null){snapshot.close();snapshot=null;}if(material!=null){material.close();material=null;}
        if(musicGlow!=null){musicGlow.release();musicGlow=null;}
        if(card!=null){card.release();card=null;}if(animator!=0){HyperOsNative.nativeDestroyAnimator(animator);animator=0;}
        for(HyperOsCompactFactory.Compact owner:compactOwners.values())owner.release();compactOwners.clear();mainCompact=null;secondaryCompact=null;secondaryKey=null;tertiaryCompact=null;tertiaryKey=null;
        launchBody.discardDisplayList();launchFrame.discardDisplayList();launchPrepared=false;launchStarted=false;launchCard=false;launchSurface=0;absorbStartNanos=0L;
        if(contentLayer!=null&&remote!=null)remote.removeView(contentLayer);
        contentLayer=null;compact=null;mainSmall=null;prepared=false;lastFrame=0;
        super.onDetachedFromWindow();
    }
}
