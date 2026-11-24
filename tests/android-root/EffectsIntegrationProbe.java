package com.alpha269.hyperisland.probe;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperIslandView;
import com.alpha269.hyperisland.island.HyperOsCallBackground;
import com.alpha269.hyperisland.island.HyperOsCardFactory;
import com.alpha269.hyperisland.island.HyperOsCompactFactory;
import com.alpha269.hyperisland.island.HyperOsLottieView;
import com.alpha269.hyperisland.island.HyperOsNative;
import com.alpha269.hyperisland.island.IslandItem;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owned call/charging rendering and lifecycle; every action is a fixture Runnable. */
final class EffectsIntegrationProbe {
    interface Work{void run()throws Exception;}
    interface Ready{boolean ready()throws Exception;}
    static int assertions;
    final JSONObject result=new JSONObject();
    final JSONArray frames=new JSONArray();
    IslandItem call,charging;
    HyperOsCardFactory.Card card;
    HyperOsCallBackground background;
    HyperOsCompactFactory.Compact compact;
    HyperOsLottieView charger;
    Object engine;
    Rect roi;
    int[] earlyPixels;
    float maxFrame;
    static void start(){EffectsIntegrationProbe probe=new EffectsIntegrationProbe();probe.guard(probe::begin);}
    void guard(Work work){try{work.run();}catch(Throwable failure){RootViewIntegrationProbe.fail(failure);}}
    void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    Object field(Object owner,String name)throws Exception{return RootViewIntegrationProbe.field(owner.getClass(),name).get(owner);}
    void frame(){RootViewIntegrationProbe.view.doFrame(SystemClock.elapsedRealtimeNanos());
        if(RootViewIntegrationProbe.view.failure()!=null)throw new AssertionError(RootViewIntegrationProbe.view.failure());}
    void poll(String label,Ready ready,Work complete){poll(label,ready,complete,SystemClock.uptimeMillis()+15000);}
    void poll(String label,Ready ready,Work complete,long deadline){guard(()->{
        frame();if(ready.ready()){complete.run();return;}
        if(SystemClock.uptimeMillis()>=deadline)throw new AssertionError("Timed out waiting for "+label);
        RootViewIntegrationProbe.MAIN.postDelayed(()->poll(label,ready,complete,deadline),24);
    });}
    boolean settled()throws Exception{return RootViewIntegrationProbe.field(HyperIslandView.class,"activeMask").getLong(RootViewIntegrationProbe.view)==0
        &&RootViewIntegrationProbe.field(HyperIslandView.class,"smallActiveMask").getLong(RootViewIntegrationProbe.view)==0;}
    void begin()throws Exception{
        call=new IslandItem("hyperos:incoming:owned-provider-effect");call.kind=IslandItem.Kind.CALL;call.title="Alex";call.subtitle="Incoming call";
        call.actions.add(RootViewIntegrationProbe.capability("Decline",9,true));call.actions.add(RootViewIntegrationProbe.capability("Answer",1,false));
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(call));
        if(!RootViewIntegrationProbe.view.isExpanded())RootViewIntegrationProbe.view.expand(call);
        poll("incoming card",()->RootViewIntegrationProbe.view.isExpanded()&&settled(),this::callReady);
    }
    void callReady()throws Exception{
        card=(HyperOsCardFactory.Card)RootViewIntegrationProbe.field(HyperIslandView.class,"card").get(RootViewIntegrationProbe.view);
        background=find(card.view,HyperOsCallBackground.class);
        check(background!=null&&((java.util.List<?>)field(card,"animated")).contains(background),"Incoming source wave delegate missing");
        check(background.getWidth()==card.view.getWidth()&&background.getHeight()==card.height(),"Incoming background does not cover its source card");
        check(background.isHardwareAccelerated()&&background.active()&&card.needsFrames(),"Incoming wave has no owned hardware/frame owner");
        long handle=(Long)field(background,"handle");check(handle!=0,"Incoming wave has no native clock");
        RootViewIntegrationProbe.view.setSuppressed(true);frame();
        check(!background.active()&&!card.needsFrames(),"Hidden call kept requesting wave frames");
        RootViewIntegrationProbe.render("incoming-wave-hidden.png");
        RootViewIntegrationProbe.view.reveal();frame();
        check(background.active()&&(Float)field(background,"time")==0f,"Call reveal did not restart source ShaderDriver time");
        int[] p=new int[2];background.getLocationOnScreen(p);
        roi=new Rect(Math.max(0,p[0]+background.getWidth()/2),Math.max(0,p[1]),
            Math.min(RootViewIntegrationProbe.width,p[0]+background.getWidth()),Math.min(RootViewIntegrationProbe.view.getHeight(),p[1]+background.getHeight()));
        check(!roi.isEmpty(),"Incoming-wave ROI is outside owned root");
        RootViewIntegrationProbe.render("incoming-wave-early.png");earlyPixels=pixels(roi);
        result.put("incomingWaveHandle",handle);
        poll("later call wave",()->(Float)field(background,"time")>=.65f,this::callPixels);
    }
    void callPixels()throws Exception{
        RootViewIntegrationProbe.render("incoming-wave-later.png");JSONObject difference=difference(earlyPixels,pixels(roi));
        check(difference.getInt("changedPixels")>=8&&difference.getInt("maxRgbDelta")>=16,"Original incoming wave did not change captured pixels");
        result.put("incomingWavePixelDelta",difference);
        System.out.println("Owned incoming wave pixels: "+difference);
        float before=(Float)field(background,"time");call.title="Owned caller metadata";call.contentVersion++;
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(call));frame();
        check(find(card.view,HyperOsCallBackground.class)==background&&(Float)field(background,"time")>=before,"Caller metadata replaced or restarted source wave");
        long closed=(Long)field(background,"handle");card.release();
        check((Long)field(background,"handle")==0&&!background.active(),"Incoming wave release leaked clock");
        boolean rejected=false;try{HyperOsNative.nativeWaveFrame(closed,SystemClock.elapsedRealtime(),0);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Closed call-wave JNI handle accepted");
        RootViewIntegrationProbe.view.collapse();
        charging=new IslandItem("owned-provider-charger");charging.kind=IslandItem.Kind.CHARGING;charging.title="Charging";charging.ringPercent=45;
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));
        poll("normal charger",()->!RootViewIntegrationProbe.view.isExpanded()&&settled(),this::chargerReady);
    }
    void chargerReady()throws Exception{
        compact=(HyperOsCompactFactory.Compact)RootViewIntegrationProbe.field(HyperIslandView.class,"mainCompact").get(RootViewIntegrationProbe.view);
        charger=find(compact.view,HyperOsLottieView.class);check(charger!=null,"Original charger Lottie missing");
        engine=field(charger,"engine");check(engine!=null&&!charger.active()&&charger.sourceFrame()==0f,"Normal charger did not begin at its source frame zero");
        Object clock=field(charger,"clock");java.lang.reflect.Method max=clock.getClass().getMethod("b");maxFrame=((Number)max.invoke(clock)).floatValue();
        check(maxFrame>1f,"Original charger frame bounds invalid");record("normal-zero");
        RootViewIntegrationProbe.render("charger-normal.png");
        charging.title="Fast charging";RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();
        retained("fast mode");check(charger.active()&&charger.sourceFrame()<=1f,"Fast charging did not start original forward one-shot");record("fast-start");
        poll("forward charger frames",()->charger.sourceFrame()>1f&&charger.sourceFrame()<maxFrame,this::chargerForward);
    }
    void chargerForward()throws Exception{
        float before=charger.sourceFrame();charging.ringPercent=46;RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();
        retained("percentage refresh");check(charger.sourceFrame()>=before&&hasText(compact.view,"46%"),"Percentage refresh restarted morph or failed to update label");
        record("forward-percent-refresh");RootViewIntegrationProbe.render("charger-forward.png");
        charging.title="Charging";RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));
        retained("reverse mode");check(charger.active()&&Math.abs(charger.sourceFrame()-maxFrame)<1f,"Interrupted forward morph did not snap to donor end: frame="+charger.sourceFrame()+", max="+maxFrame+", active="+charger.active());
        record("reverse-start");poll("reverse charger frames",()->charger.sourceFrame()<maxFrame-1f&&charger.sourceFrame()>0f,this::chargerReverse);
    }
    void chargerReverse()throws Exception{
        float before=charger.sourceFrame();charging.ringPercent=47;RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();
        retained("reverse percentage refresh");check(charger.sourceFrame()<=before&&hasText(compact.view,"47%"),"Reverse refresh restarted morph or lost label");
        record("reverse-percent-refresh");RootViewIntegrationProbe.render("charger-reverse.png");
        RootViewIntegrationProbe.view.setSuppressed(true);frame();check(!charger.active(),"Hidden charger kept running Lottie");
        float frozen=charger.sourceFrame();RootViewIntegrationProbe.MAIN.postDelayed(()->guard(()->{
            frame();check(charger.sourceFrame()==frozen,"Hidden charger phase advanced");
            RootViewIntegrationProbe.view.reveal();frame();retained("reveal");
            check(charger.active()&&charger.sourceFrame()<=frozen&&charger.sourceFrame()>0f,"Charger reveal lost reverse phase");
            poll("settled reverse charger",()->!charger.active(),this::finish);
        }),150);
    }
    void finish()throws Exception{
        check(charger.sourceFrame()==0f,"Reverse one-shot did not return to original normal frame zero");retained("settled normal");record("normal-settled");
        RootViewIntegrationProbe.render("charger-normal-again.png");
        HyperOsCompactFactory.register(charging.key,nativeCharging(false,50),null);
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();
        compact=(HyperOsCompactFactory.Compact)RootViewIntegrationProbe.field(HyperIslandView.class,"mainCompact").get(RootViewIntegrationProbe.view);
        charger=find(compact.view,HyperOsLottieView.class);engine=field(charger,"engine");
        check(charger!=null&&charger.sourceFrame()==0f&&!charger.active(),"Native payload did not create normal charger");
        HyperOsCompactFactory.register(charging.key,nativeCharging(true,51),null);
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();retained("native payload mode update");
        check(charger.active()&&hasText(compact.view,"51%"),"Native fast mode or percentage did not reach retained charger");
        poll("native payload forward",()->charger.sourceFrame()>1f&&charger.active(),this::finishNative);
    }
    void finishNative()throws Exception{
        float before=charger.sourceFrame();HyperOsCompactFactory.register(charging.key,nativeCharging(true,52),null);
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));frame();retained("native payload percentage update");
        check(charger.sourceFrame()>=before&&hasText(compact.view,"52%"),"Native payload percentage restarted forward phase");
        HyperOsCompactFactory.register(charging.key,nativeCharging(false,53),null);
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(charging));retained("native payload reverse update");
        check(charger.active()&&Math.abs(charger.sourceFrame()-maxFrame)<1f&&hasText(compact.view,"53%"),"Native payload did not reverse original asset");
        result.put("nativePayloadRetainsCharger",true);
        result.put("chargerFrames",frames).put("chargerMaxFrame",maxFrame).put("passed",true).put("assertions",assertions)
            .put("realCallModified",false).put("realChargerModified",false).put("sourceCallingShader","calling_light_expand_wave.agsl");
        try(FileOutputStream output=new FileOutputStream(new File(RootViewIntegrationProbe.output,"effects-validation.json"))){output.write(result.toString(2).getBytes("UTF-8"));}
        RootViewIntegrationProbe.surfaceHost.release();if(RootViewIntegrationProbe.ownedDisplay!=null)RootViewIntegrationProbe.ownedDisplay.release();
        System.out.println("Owned call/charging effects assertions passed: "+assertions);System.exit(0);
    }
    JSONObject nativeCharging(boolean fast,int percent)throws Exception{
        JSONObject icon=new JSONObject().put("type",3).put("pic","flash_light_icon");
        JSONObject big=new JSONObject().put("picInfo",icon).put("imageTextInfoRight",new JSONObject().put("type",4)
            .put("picInfo",new JSONObject().put("type",3).put("pic",fast?"quick_charger_mode":"charger_mode"))
            .put("textInfo",new JSONObject().put("title",percent+"%")));
        return new JSONObject().put("param_island",new JSONObject().put("bigIslandArea",big)
            .put("smallIslandArea",new JSONObject().put("picInfo",icon)));
    }
    void retained(String event)throws Exception{check(compact==RootViewIntegrationProbe.field(HyperIslandView.class,"mainCompact").get(RootViewIntegrationProbe.view)
        &&find(compact.view,HyperOsLottieView.class)==charger&&field(charger,"engine")==engine,event+" replaced the retained charger or its engine");}
    void record(String event)throws Exception{frames.put(new JSONObject().put("event",event).put("frame",charger.sourceFrame()).put("running",charger.active()));}
    static <T>T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T found=find(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}return null;}
    static boolean hasText(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),value))return true;return false;}
    int[] pixels(Rect roi){int[] result=new int[roi.width()*roi.height()];for(int y=roi.top;y<roi.bottom;y++)System.arraycopy(RootViewIntegrationProbe.lastRenderedColors,
        y*RootViewIntegrationProbe.width+roi.left,result,(y-roi.top)*roi.width(),roi.width());return result;}
    JSONObject difference(int[] first,int[] second)throws Exception{int changed=0,max=0;for(int i=0;i<first.length;i++){
        int delta=Math.abs((first[i]>>16&255)-(second[i]>>16&255))+Math.abs((first[i]>>8&255)-(second[i]>>8&255))+Math.abs((first[i]&255)-(second[i]&255));
        if(delta>3)changed++;max=Math.max(max,delta);}return new JSONObject().put("pixels",first.length).put("changedPixels",changed).put("maxRgbDelta",max);}
}
