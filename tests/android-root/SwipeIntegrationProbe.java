package com.alpha269.hyperisland.probe;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.*;
import android.widget.SeekBar;
import com.alpha269.hyperisland.island.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

final class SwipeIntegrationProbe {
 static int phase,checks,before;static float x,y,dx,dy;static long downTime;static SeekBar seek;
 static HyperIslandView view(){return RootViewIntegrationProbe.view;}
 static Object field(String name)throws Exception{return RootViewIntegrationProbe.field(HyperIslandView.class,name).get(view());}
 static double[] state()throws Exception{return ((double[])field("state")).clone();}
 static void later(long delay){RootViewIntegrationProbe.MAIN.postDelayed(()->{try{step();}catch(Throwable error){RootViewIntegrationProbe.fail(error);}},delay);}
 static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static float dp(float value){return value*view().getResources().getDisplayMetrics().density;}
 static void send(int action,float px,float py){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=now;
  MotionEvent event=MotionEvent.obtain(downTime,now,action,px,py,0);try{view().dispatchTouchEvent(event);}finally{event.recycle();}}
 static void scene(boolean media){IslandItem item=RootViewIntegrationProbe.scenarios.get(media?13:2);view().setItems(Collections.singletonList(item));view().expand(item);}
 static void drag(float px,float py)throws Exception{
  double[] s=state();RectF bounds=new RectF((int)s[4]+(float)s[1],(int)s[6]+(float)s[2],(int)s[5]+(float)s[1],(int)s[7]+(float)s[2]);
  x=bounds.left+bounds.width()*.35f;y=bounds.centerY();dx=px;dy=py;send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_MOVE,x+dx,y+dy);
 }
 static SeekBar findSeek(View v){if(v instanceof SeekBar)return (SeekBar)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){SeekBar s=findSeek(((ViewGroup)v).getChildAt(i));if(s!=null)return s;}return null;}
 static void seekDown()throws Exception{
  seek=findSeek(((HyperOsCardFactory.Card)field("card")).view);require(seek!=null&&seek.isEnabled(),"Owned media seek capability available");
  float[] p={seek.getWidth()*.35f,seek.getHeight()/2f};RootViewIntegrationProbe.toRoot(seek,p);x=p[0];y=p[1];before=RootViewIntegrationProbe.actions.get();send(MotionEvent.ACTION_DOWN,x,y);
 }
 static void start(){scene(false);later(1800);RootViewIntegrationProbe.MAIN.postDelayed(()->RootViewIntegrationProbe.fail(new AssertionError("Swipe deadline")),60000);}
 static void step()throws Exception{
  require(view().failure()==null,"Valid source root at swipe phase "+phase);
  switch(phase++){
   case 0:drag(dp(30),0);later(500);break;
   case 1:double[] s=state();require(s[79]<1&&s[78]<1&&s[83]>0,"Horizontal swipe selects original scale/alpha/blur targets");
    RootViewIntegrationProbe.render("swipe-horizontal.png");send(MotionEvent.ACTION_UP,x+dx,y);later(1000);break;
   case 2:require(view().isExpanded()&&state()[23]==1&&state()[22]==1,"Subthreshold horizontal swipe restores expanded card");drag(0,-dp(70));later(500);break;
   case 3:double[] up=state();require(up[80]<1&&up[81]<0&&up[78]>=.2&&up[83]>0,"Upward swipe selects original vertical shrink/translation/fade targets");
    RootViewIntegrationProbe.render("swipe-up.png");send(MotionEvent.ACTION_UP,x,y+dy);later(1400);break;
   case 4:require(!view().isExpanded(),"Upward swipe past HyperOS threshold collapses");scene(false);later(1800);break;
   case 5:drag(-dp(70),0);send(MotionEvent.ACTION_UP,x+dx,y);later(1300);break;
   case 6:require(!view().isExpanded(),"Left expanded swipe past threshold collapses");scene(false);later(1800);break;
   case 7:drag(dp(70),0);send(MotionEvent.ACTION_UP,x+dx,y);later(1300);break;
   case 8:require(!view().isExpanded(),"Right expanded swipe past threshold collapses");scene(false);later(1800);break;
   case 9:drag(0,-dp(70));send(MotionEvent.ACTION_CANCEL,x,y+dy);later(1000);break;
   case 10:require(view().isExpanded()&&state()[22]==1&&state()[24]==1&&RootViewIntegrationProbe.host.opens==0,"Cancelled swipe restores without collapse or app opening");scene(true);later(2200);break;
   case 11:seekDown();send(MotionEvent.ACTION_MOVE,x+dp(70),y);send(MotionEvent.ACTION_UP,x+dp(70),y);later(300);break;
   case 12:require(view().isExpanded()&&RootViewIntegrationProbe.actions.get()==before+1,"Horizontal seek beyond swipe threshold stays a seek operation");seekDown();send(MotionEvent.ACTION_MOVE,x+dp(30),y);
    require(seek.isPressed(),"Seek is actively dragging before cancellation");send(MotionEvent.ACTION_CANCEL,x+dp(30),y);later(300);break;
   case 13:require(RootViewIntegrationProbe.actions.get()==before&&view().isExpanded(),"Cancelled active seek dispatches no audio operation");seekDown();send(MotionEvent.ACTION_MOVE,x,y-dp(70));send(MotionEvent.ACTION_UP,x,y-dp(70));later(1400);break;
   case 14:require(!view().isExpanded()&&RootViewIntegrationProbe.actions.get()==before&&RootViewIntegrationProbe.host.opens==0,"Vertical seek swipe collapses without committing cancelled seek");finish();break;
  }
 }
 static void finish()throws Exception{
  JSONObject report=new JSONObject().put("passed",true).put("checks",checks).put("scope","Owned source-app expanded swipes and actual Android seek cancellation on private virtual display; original HyperOS targets, sample callbacks only; no live app, call or audio operation");
  try(FileOutputStream out=new FileOutputStream(new File(RootViewIntegrationProbe.output,"swipe-validation.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
  RootViewIntegrationProbe.surfaceHost.release();RootViewIntegrationProbe.ownedDisplay.release();RootViewIntegrationProbe.displayFrames.close();System.out.println("SWIPE INTEGRATION PASS checks="+checks);System.exit(0);
 }
}
