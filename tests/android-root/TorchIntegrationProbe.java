package com.alpha269.hyperisland.probe;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import com.alpha269.hyperisland.island.HyperOsTorchSource;
import com.alpha269.hyperisland.island.IslandItem;
import com.alpha269.hyperisland.island.HyperOsCardFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Owned torch source/touch fixtures; no physical camera control is invoked. */
final class TorchIntegrationProbe {
    static final class Camera implements HyperOsTorchSource.Backend {
        final int maximum;
        int level=3,calls; boolean on,stopped;
        HyperOsTorchSource.Listener listener;
        Camera(int maximum){this.maximum=maximum;level=Math.min(level,maximum);}
        public int maximum(){return maximum;}
        public int current(){return level;}
        public void observe(HyperOsTorchSource.Listener value){listener=value;listener.mode(on);}
        public void stop(){stopped=true;listener=null;}
        public void set(boolean enabled,int strength){
            calls++;on=enabled;level=strength;
            if(enabled)listener.strength(strength);listener.mode(enabled);
        }
    }
    static int checks;
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static IslandItem item(HyperOsTorchSource source,boolean expanded){
        List<IslandItem> list=new ArrayList<>();source.appendTo(list,expanded);
        return list.isEmpty()?null:list.get(0);
    }
    static void start(){
        try {
            Camera camera=new Camera(5);HyperOsTorchSource source=new HyperOsTorchSource(camera,()->{});source.start();
            check(item(source,false)==null,"Off torch appeared unsolicited");
            camera.on=true;camera.listener.mode(true);
            IslandItem it=item(source,false);check(it!=null&&it.torchMax==5&&it.torchLevel==3,"Wrong source strength");
            it.torchSet.accept(99);check(camera.level==5&&camera.on,"Upper strength clamp failed");
            it=item(source,true);it.torchSet.accept(0);check(!camera.on,"Zero did not turn off");
            check(item(source,true)!=null,"Expanded off card vanished");
            check(item(source,false)==null,"Off card remained collapsed");
            camera.listener.mode(true);camera.listener.strength(2);
            check(item(source,false).torchLevel==2,"External strength change not observed");
            camera.listener.unavailable();check(item(source,true)==null,"Unavailable camera remained controllable");
            source.close();check(camera.stopped,"Torch callback leaked");
            Camera single=new Camera(1);HyperOsTorchSource simple=new HyperOsTorchSource(single,()->{});simple.start();
            single.listener.mode(true);item(simple,false).torchSet.accept(5);
            check(single.level==1,"Single-level camera received unsupported strength");simple.close();
            check(HyperOsTorchSource.nativeNotificationKey("0|com.android.systemui|42|Flashlight|1000"),"Native torch key not recognized");
            check(!HyperOsTorchSource.nativeNotificationKey("0|other.app|42|Flashlight|1000"),"Unrelated app notification filtered");

            IslandItem torch=new IslandItem("owned-strength-torch");torch.torch=true;torch.torchOn=true;
            torch.title="Flashlight";torch.subtitle="Flashlight is on";torch.torchMax=5;torch.torchLevel=3;
            final int[] sent={-1,0};
            torch.torchSet=level->{sent[0]=level;sent[1]++;torch.torchOn=level>0;torch.torchLevel=Math.max(1,level);
                RootViewIntegrationProbe.view.setItems(Collections.singletonList(torch));};
            torch.torchToggle=()->torch.torchSet.accept(torch.torchOn?0:torch.torchLevel);
            RootViewIntegrationProbe.scenarios.clear();RootViewIntegrationProbe.scenarios.add(torch);
            RootViewIntegrationProbe.view.setItems(Collections.singletonList(torch));RootViewIntegrationProbe.view.expand(torch);
            RootViewIntegrationProbe.MAIN.postDelayed(()->verifyUi(torch,sent),1500);
        }catch(Throwable error){RootViewIntegrationProbe.fail(error);}
    }
    static SeekBar slider(View view){
        if(view instanceof SeekBar&&"Flashlight strength".contentEquals(view.getContentDescription()))return (SeekBar)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar found=slider(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    static void verifyUi(IslandItem torch,int[] sent){
        try {
            RootViewIntegrationProbe.view.doFrame(SystemClock.elapsedRealtimeNanos());
            HyperOsCardFactory.Card card=(HyperOsCardFactory.Card)RootViewIntegrationProbe.field(
                com.alpha269.hyperisland.island.HyperIslandView.class,"card").get(RootViewIntegrationProbe.view);
            SeekBar bar=slider(card.view);
            check(bar!=null&&bar.isEnabled()&&bar.getMax()==5,"Strength slider missing");
            check(bar.getProgress()==3,"Initial strength wrong");
            RootViewIntegrationProbe.render("torch-strength-card.png");
            int before=sent[1];bar.setProgress(2);check(sent[1]==before,"Programmatic update controlled camera");
            long time=SystemClock.uptimeMillis();
            float[] start={bar.getWidth()*.25f,bar.getHeight()/2f},end={bar.getWidth()*.8f,bar.getHeight()/2f};
            RootViewIntegrationProbe.toRoot(bar,start);RootViewIntegrationProbe.toRoot(bar,end);
            MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,start[0],start[1],0);
            MotionEvent move=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_MOVE,end[0],end[1],0);
            MotionEvent up=MotionEvent.obtain(time,time+60,MotionEvent.ACTION_UP,end[0],end[1],0);
            RootViewIntegrationProbe.view.dispatchTouchEvent(down);RootViewIntegrationProbe.view.dispatchTouchEvent(move);RootViewIntegrationProbe.view.dispatchTouchEvent(up);
            down.recycle();move.recycle();up.recycle();
            check(sent[1]>before&&sent[0]>=3&&sent[0]<=5,"Drag did not dispatch supported strength");
            check(RootViewIntegrationProbe.view.isExpanded(),"Strength drag collapsed card");
            check(card==RootViewIntegrationProbe.field(com.alpha269.hyperisland.island.HyperIslandView.class,"card").get(RootViewIntegrationProbe.view),"Strength callback rebuilt dragged card");
            RootViewIntegrationProbe.render("torch-strength-after-drag.png");
            TorchShaderProbe.start(checks,sent);
        }catch(Throwable error){RootViewIntegrationProbe.fail(error);}
    }
    static void finish(int sourceTouchChecks,int[] sent,JSONObject shader)throws Exception{
        JSONObject result=new JSONObject().put("assertions",checks).put("passed",true)
            .put("sourceTouchAssertions",sourceTouchChecks).put("shaderAssertions",checks-sourceTouchChecks)
            .put("supportedLevels",5).put("draggedLevel",sent[0]).put("actualCameraModified",false)
            .put("sourceBeamEffect",shader);
        try(FileOutputStream file=new FileOutputStream(new File(RootViewIntegrationProbe.output,"torch-validation.json"))){file.write(result.toString(2).getBytes("UTF-8"));}
        RootViewIntegrationProbe.surfaceHost.release();if(RootViewIntegrationProbe.ownedDisplay!=null)RootViewIntegrationProbe.ownedDisplay.release();
        System.out.println("Owned torch source/touch assertions="+sourceTouchChecks+", shader assertions="+(checks-sourceTouchChecks));System.exit(0);
    }
}
