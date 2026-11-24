package com.alpha269.hyperisland.probe;

import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import com.alpha269.hyperisland.island.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.JSONObject;

/** Real Android touch dispatch on the owned virtual display; all control callbacks are fixtures. */
final class PressIntegrationProbe {
 static int checks,phase,before;static long downTime;static float x,y;static RectF big,small;
 static double[] mainRest;static SeekBar seek;
 static HyperIslandView view(){return RootViewIntegrationProbe.view;}
 static Object field(String name)throws Exception{return RootViewIntegrationProbe.field(HyperIslandView.class,name).get(view());}
 static double[] state(String name)throws Exception{return ((double[])field(name)).clone();}
 static void start(){
  view().setItems(Arrays.asList(RootViewIntegrationProbe.scenarios.get(2),RootViewIntegrationProbe.scenarios.get(1)));
  later(2000);RootViewIntegrationProbe.MAIN.postDelayed(()->RootViewIntegrationProbe.fail(new AssertionError("Press fixture deadline")),60000);
 }
 static void later(long ms){RootViewIntegrationProbe.MAIN.postDelayed(()->{try{step();}catch(Throwable error){RootViewIntegrationProbe.fail(error);}},ms);}
 static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static boolean send(int action,float px,float py){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=now;
  MotionEvent event=MotionEvent.obtain(downTime,now,action,px,py,0);try{return view().dispatchTouchEvent(event);}finally{event.recycle();}}
 static RectF box(double[] s){return new RectF((int)s[4]+(float)s[1],(int)s[6]+(float)s[2],(int)s[5]+(float)s[1],(int)s[7]+(float)s[2]);}
 static void down(RectF bounds){x=bounds.centerX();y=bounds.centerY();require(send(MotionEvent.ACTION_DOWN,x,y),"Owned press down accepted");}
 static void sameMain()throws Exception{double[] current=state("state");for(int i:new int[]{1,2,4,5,6,7,10})require(current[i]==mainRest[i],"Small press does not change main property "+i);}
 static View firstButton()throws Exception{
  Object card=field("card");Field buttons=card.getClass().getDeclaredField("buttons");buttons.setAccessible(true);
  Object binding=((List<?>)buttons.get(card)).get(0);Field target=binding.getClass().getDeclaredField("view");target.setAccessible(true);return (View)target.get(binding);
 }
 static SeekBar seek(View v){if(v instanceof SeekBar)return (SeekBar)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){SeekBar found=seek(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
 static void step()throws Exception{
  require(view().failure()==null,"Source root stays valid at phase "+phase);
  switch(phase++){
   case 0:
    require(!view().isExpanded()&&((Long)field("activeMask"))==0,"Two capsule scene settled");mainRest=state("state");big=box(mainRest);small=new RectF((RectF)field("smallBounds"));
    down(big);later(1600);break;
   case 1:
    require(state("state")[66]==(double).95f,"Big press target is original .95f");require(state("state")[10]<.99,"Big content compresses");
    RootViewIntegrationProbe.render("press-big.png");send(MotionEvent.ACTION_CANCEL,x,y);later(1000);break;
   case 2:
    require(!view().isExpanded()&&state("state")[10]==1,"Cancel restores big without expanding");mainRest=state("state");down(small);later(800);break;
   case 3:
    require(state("smallState")[74]==(double).9f&&state("smallState")[18]<.99,"Independent small owner compresses to .9f");sameMain();
    RootViewIntegrationProbe.render("press-small.png");send(MotionEvent.ACTION_CANCEL,x,y);later(800);break;
   case 4:
    require(state("smallState")[18]==1,"Small cancel restores original scale");sameMain();
    down(big);send(MotionEvent.ACTION_MOVE,x+100,y);send(MotionEvent.ACTION_UP,x+100,y);later(900);break;
   case 5:
    require(!view().isExpanded()&&!((Boolean)field("touchActive"))&&((Integer)field("pressedSurface"))==0,"Dragged capsule releases press without accidental expansion");
    send(MotionEvent.ACTION_UP,big.centerX(),big.centerY());require(!view().isExpanded(),"Unpaired release cannot open an activity");
    down(big);send(MotionEvent.ACTION_UP,x,y);require(view().isExpanded(),"Paired tap expands its own capsule");later(1800);break;
   case 6:
    RectF expanded=box(state("state"));x=expanded.left+70;y=expanded.centerY();send(MotionEvent.ACTION_DOWN,x,y);later(1500);break;
   case 7:
    double[] s=state("state");require(s[79]==(double).95f&&s[80]==(double).95f&&s[23]<.99&&s[24]<.99,"Expanded card uses original .95f targets on both axes");
    RootViewIntegrationProbe.render("press-expanded.png");send(MotionEvent.ACTION_CANCEL,x,y);later(1100);break;
   case 8:
    require(state("state")[23]==1&&state("state")[24]==1&&RootViewIntegrationProbe.host.opens==0,"Expanded cancel restores without opening app");
    send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_MOVE,x+100,y);send(MotionEvent.ACTION_UP,x+100,y);
    View button=firstButton();float[] point={button.getWidth()/2f,button.getHeight()/2f};RootViewIntegrationProbe.toRoot(button,point);before=RootViewIntegrationProbe.actions.get();
    send(MotionEvent.ACTION_DOWN,point[0],point[1]);send(MotionEvent.ACTION_UP,point[0],point[1]);later(300);break;
   case 9:
    require(RootViewIntegrationProbe.actions.get()==before+1&&RootViewIntegrationProbe.host.opens==0,"Card control dispatches exactly once without opening app");
    view().setItems(Collections.singletonList(RootViewIntegrationProbe.scenarios.get(13)));later(2200);break;
   case 10:
    HyperOsCardFactory.Card card=(HyperOsCardFactory.Card)field("card");seek=seek(card.view);require(seek!=null&&seek.isEnabled()&&seek.getWidth()>0,"Actual media seek control is available");
    float[] p={seek.getWidth()*.35f,seek.getHeight()/2f};RootViewIntegrationProbe.toRoot(seek,p);x=p[0];y=p[1];before=RootViewIntegrationProbe.actions.get();send(MotionEvent.ACTION_DOWN,x,y);later(400);break;
   case 11:
    require(((Integer)field("pressedSurface"))==0&&state("state")[23]==1&&state("state")[24]==1,"Seek touch retains scale and bypasses island press");
    send(MotionEvent.ACTION_MOVE,x+seek.getWidth()*.1f,y);send(MotionEvent.ACTION_UP,x+seek.getWidth()*.1f,y);later(300);break;
   case 12:
    require(RootViewIntegrationProbe.actions.get()>before&&RootViewIntegrationProbe.host.opens==0,"Media seek gesture reaches its own fixture capability");
    view().collapse();view().setItems(Arrays.asList(RootViewIntegrationProbe.scenarios.get(2),RootViewIntegrationProbe.scenarios.get(1)));later(1800);break;
   case 13:
    big=box(state("state"));down(big);IslandItem replacement=new IslandItem("owned-press-replacement");replacement.title="Replacement";
    view().setItems(Collections.singletonList(replacement));send(MotionEvent.ACTION_UP,x,y);
    require(!view().isExpanded()&&((Integer)field("pressedSurface"))==0,"Replacing gesture owner cancels rather than opening replacement");finish();break;
  }
 }
 static void finish()throws Exception{
  JSONObject report=new JSONObject().put("passed",true).put("checks",checks).put("scope","Actual source-app touch dispatch and Rust press owners on private virtual display; sample timer/media/capabilities only, no live call, audio or app operation")
    .put("screenshots",Arrays.asList("press-big.png","press-small.png","press-expanded.png"));
  try(FileOutputStream out=new FileOutputStream(new File(RootViewIntegrationProbe.output,"press-validation.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
  RootViewIntegrationProbe.surfaceHost.release();RootViewIntegrationProbe.ownedDisplay.release();RootViewIntegrationProbe.displayFrames.close();System.out.println("PRESS INTEGRATION PASS checks="+checks);System.exit(0);
 }
}
