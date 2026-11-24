package com.alpha269.hyperisland.probe;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.Region;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewConfiguration;
import com.alpha269.hyperisland.island.*;
import java.util.*;
import java.io.*;
import org.json.JSONObject;

final class QueueIntegrationProbe {
 static int phase,checks,originalChecks,settleFrames;static long downTime,settleDeadline;static float x,y,dragDx;static HyperOsPresentationQueue queue;
 static IslandItem extra;static double[] mainRest,smallRest;static String savedMainKey,savedSmallKey,savedQueue,incomingKey,replacementQueue;
 static Object timerCompact,incomingCompact,removedSurface,removedCompact;static long timerAnimator,incomingAnimator;
 static List<?> timerBindings;static final List<Object> timerClocks=new ArrayList<>();static final List<Long> timerBases=new ArrayList<>();
 static List<Object> cleanupContents;
 static final int[] REST_PROPERTIES={1,2,3,4,5,6,7,9,10,15,16,17,18,19,20};
 static HyperIslandView view(){return RootViewIntegrationProbe.view;}
 static Object field(String name)throws Exception{return RootViewIntegrationProbe.field(HyperIslandView.class,name).get(view());}
 static Object field(Object owner,String name)throws Exception{return RootViewIntegrationProbe.field(owner.getClass(),name).get(owner);}
 static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void later(long time){RootViewIntegrationProbe.MAIN.postDelayed(()->{try{step();}catch(Throwable error){RootViewIntegrationProbe.fail(error);}},time);}
 static void settled(){settleDeadline=SystemClock.uptimeMillis()+15000;settleFrames=0;RootViewIntegrationProbe.MAIN.post(()->{try{pollSettled();}catch(Throwable error){RootViewIntegrationProbe.fail(error);}});}
 static boolean surfaceSettled(Object surface)throws Exception{return surface==null||((Long)field(surface,"activeMask"))==0;}
 static void pollSettled()throws Exception{
  if(view().failure()!=null)throw new AssertionError("Source root failed while settling queue phase "+phase+": "+view().failure());
  boolean ready=(Boolean)field("prepared")&&view().isAttachedToWindow()&&view().getWidth()>0&&!view().isLayoutRequested()
    &&((Long)field("activeMask"))==0&&((Long)field("smallActiveMask"))==0&&!(Boolean)field("closing")&&!(Boolean)field("commitPending")
    &&surfaceSettled(field("secondarySurface"))&&surfaceSettled(field("incomingSurface"));
  for(Object surface:(List<?>)field("outgoingSurfaces"))ready&=surfaceSettled(surface);
  if(ready){if(++settleFrames>=2){later(0);return;}}else settleFrames=0;
  if(SystemClock.uptimeMillis()>=settleDeadline)throw new AssertionError("Native owners did not settle at queue phase "+phase);
  RootViewIntegrationProbe.MAIN.postDelayed(()->{try{pollSettled();}catch(Throwable error){RootViewIntegrationProbe.fail(error);}},32);
 }
 static void show(){view().setItems(queue.layout().visible,0,queue.layout().hidden);}
 static void send(int action,float px,float py){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=now;
  MotionEvent event=MotionEvent.obtain(downTime,now,action,px,py,0);try{view().dispatchTouchEvent(event);}finally{event.recycle();}}
 static void swipe(int direction,boolean restore)throws Exception{
  if(restore){x=RootViewIntegrationProbe.width/2f;y=((Float)field("centerY"));}
  else{double[] s=(double[])field("state");x=((int)s[4]+(int)s[5])/2f+(float)s[1];y=((int)s[6]+(int)s[7])/2f+(float)s[2];}
  float dx=70*view().getResources().getDisplayMetrics().density*direction;
  send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_MOVE,x+dx,y);send(MotionEvent.ACTION_UP,x+dx,y);
 }
 static String primary()throws Exception{IslandItem item=(IslandItem)field("primary");return item==null?null:item.key;}
 static String secondary()throws Exception{IslandItem item=(IslandItem)field("secondary");return item==null?null:item.key;}
 static double[] values(String name)throws Exception{return ((double[])field(name)).clone();}
 static RectF bounds(double[] s){return new RectF((int)s[4]+(float)s[1],(int)s[6]+(float)s[2],(int)s[5]+(float)s[1],(int)s[7]+(float)s[2]);}
 static String signature(){StringBuilder result=new StringBuilder();for(IslandItem item:queue.layout().visible)result.append(item.key).append('|');result.append("hidden:");for(IslandItem item:queue.layout().hidden)result.append(item.key).append('|');return result.append("empty:").append(queue.layout().emptySentinel).toString();}
 static boolean contains(List<IslandItem> items,String key){for(IslandItem item:items)if(key.equals(item.key))return true;return false;}
 static void fixture(){queue.close();queue=new HyperOsPresentationQueue();RootViewIntegrationProbe.host.presentation=queue;
  queue.sync(Arrays.asList(RootViewIntegrationProbe.scenarios.get(1),RootViewIntegrationProbe.scenarios.get(2),extra));show();settled();}
 static void rememberRest()throws Exception{mainRest=values("state");smallRest=values("smallState");savedMainKey=primary();savedSmallKey=secondary();savedQueue=signature();}
 static boolean sameProperties(double[] a,double[] b){for(int index:REST_PROPERTIES)if(!Double.isFinite(a[index])||!Double.isFinite(b[index])||Math.abs(a[index]-b[index])>.0001)return false;return true;}
 static void committed(String reason)throws Exception{require(Objects.equals(savedMainKey,primary())&&Objects.equals(savedSmallKey,secondary())&&savedQueue.equals(signature()),reason+" preserves committed keyed slots");}
 static void restored(String reason)throws Exception{
  committed(reason);require(!(Boolean)field("capsulePreviewActive")&&!(Boolean)field("touchActive")&&((Integer)field("pressedSurface"))==0&&field("incomingSurface")==null,reason+" clears gesture and incoming preview");
  require(sameProperties(mainRest,values("state")),reason+" restores main native properties");require(sameProperties(smallRest,values("smallState")),reason+" restores secondary native properties");
 }
 static float span(boolean commit)throws Exception{
  java.lang.reflect.Method method=HyperIslandView.class.getDeclaredMethod("swipeThreshold");method.setAccessible(true);int threshold=(Integer)method.invoke(view());
  int slop=ViewConfiguration.get(view().getContext()).getScaledTouchSlop();
  if(!commit){if(threshold<=slop+1)throw new AssertionError("Fixture swipe threshold must exceed touch slop");return (threshold+slop)/2f;}
  return threshold+Math.max(slop+1,20*view().getResources().getDisplayMetrics().density);
 }
 static void drag(int surface,int direction,boolean commit)throws Exception{
  if(surface==4){x=RootViewIntegrationProbe.width/2f;y=(Float)field("centerY");}
  else{RectF box=bounds(values(surface==2?"smallState":"state"));x=box.centerX();y=box.centerY();}
  dragDx=span(commit)*direction;send(MotionEvent.ACTION_DOWN,x,y);
  require((Boolean)field("touchActive")&&(Boolean)field("touchAllowed")&&((Integer)field("touchSurface"))==surface,"Settled touch selects requested capsule surface "+surface);
  send(MotionEvent.ACTION_MOVE,x+dragDx,y);
 }
 static void extraPointerCancel(){
  MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
  MotionEvent.PointerCoords[] points={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
  for(int i=0;i<2;i++){properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i].x=x+dragDx+i*20;points[i].y=y+i*10;points[i].pressure=1;points[i].size=1;}
  long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(downTime,now,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,points,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
  try{view().dispatchTouchEvent(event);}finally{event.recycle();}send(MotionEvent.ACTION_CANCEL,x+dragDx,y);
 }
 static void rememberTimer()throws Exception{
  timerCompact=field("secondaryCompact");timerAnimator=(Long)field("smallAnimator");require(timerCompact!=null&&timerAnimator!=0,"Timer secondary has a persistent Compact and native animator");
  timerBindings=new ArrayList<>((List<?>)field(timerCompact,"clocks"));timerClocks.clear();timerBases.clear();require(!timerBindings.isEmpty(),"Timer Compact has real cached clock bindings");
  for(Object binding:timerBindings){Object clock=field(binding,"clock");timerClocks.add(clock);timerBases.add((Long)field(clock,"base"));}
 }
 static void sameTimer(String reason)throws Exception{
  List<?> current=(List<?>)field(timerCompact,"clocks");boolean same=current.size()==timerBindings.size()&&!(Boolean)field(timerCompact,"released");
  for(int i=0;same&&i<current.size();i++){Object clock=field(current.get(i),"clock");same=current.get(i)==timerBindings.get(i)&&clock==timerClocks.get(i)&&timerBases.get(i).equals(field(clock,"base"));}
  require(same,reason+" retains identical clock bindings, clock objects and base");
 }
 static void renderedSurface(Object surface,double[] nativeValues,String screenshot)throws Exception{
  View owner=(View)surface;RectF expected=bounds(nativeValues);Outline outline=new Outline();owner.getOutlineProvider().getOutline(owner,outline);Rect local=new Rect();
  require(owner.getClipToOutline()&&outline.getRect(local)&&local.equals(new Rect((int)nativeValues[4],(int)nativeValues[6],(int)nativeValues[5],(int)nativeValues[7])),"Capsule applies the native integer clip to its actual outline");
  float[] first={local.left,local.top},last={local.right,local.bottom};RootViewIntegrationProbe.toRoot(owner,first);RootViewIntegrationProbe.toRoot(owner,last);
  require(Math.abs(first[0]-expected.left)<.01&&Math.abs(first[1]-expected.top)<.01&&Math.abs(last[0]-expected.right)<.01&&Math.abs(last[1]-expected.bottom)<.01,"Rendered capsule transform matches native clip and container translation");
  require(owner.isAttachedToWindow()&&owner.getVisibility()==View.VISIBLE&&owner.getAlpha()>.9f,"Preview capsule is attached and paints its background");
  RootViewIntegrationProbe.render(screenshot);int row=Math.round(expected.centerY()),left=(int)Math.floor(expected.left)-2,right=(int)Math.ceil(expected.right)+2,width=RootViewIntegrationProbe.width;
  require(row>=0&&row<view().getHeight()&&left>=0&&right<width,"Native capsule bounds fit the owned render fixture");
  int paintedLeft=-1,paintedRight=-1;for(int column=left;column<=right;column++)if(RootViewIntegrationProbe.lastRenderedColors[row*width+column]!=0xff18212a){if(paintedLeft<0)paintedLeft=column;paintedRight=column;}
  require(paintedLeft>=0&&Math.abs(paintedLeft-expected.left)<=1.5&&Math.abs(paintedRight+1-expected.right)<=1.5,"Owned pixels stop at both transformed native capsule clip edges");
 }
 static void start(){
  queue=new HyperOsPresentationQueue();RootViewIntegrationProbe.host.presentation=queue;
  extra=new IslandItem("owned-queue-extra");extra.title="Extra activity";
  queue.sync(Arrays.asList(RootViewIntegrationProbe.scenarios.get(2),RootViewIntegrationProbe.scenarios.get(1),extra));show();settled();
 }
 static void step()throws Exception{
  require(view().failure()==null,"Valid source root at queue phase "+phase);
  switch(phase++){
   case 0:
    require(queue.layout().visible.size()==2&&queue.layout().hidden.size()==1,"Native queue seeds real big/small/hidden slots");
    double[] start=(double[])field("state");x=((int)start[4]+(int)start[5])/2f+(float)start[1];y=((int)start[6]+(int)start[7])/2f+(float)start[2];
    send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_MOVE,x-35*view().getResources().getDisplayMetrics().density,y);settled();break;
   case 1:
    require((Boolean)field("capsulePreviewActive"),"Dragging starts a native capsule preview");
    require(primary().equals(RootViewIntegrationProbe.scenarios.get(2).key)&&queue.layout().visible.get(0).key.equals(primary()),"Preview preserves committed queue owner");
    double[] preview=(double[])field("state");double[] dimensions=(double[])field("geometry");
    require(preview[5]-preview[4]<dimensions[4],"Preview compresses the current capsule before release");
    RootViewIntegrationProbe.render("queue-drag-preview.png");send(MotionEvent.ACTION_CANCEL,x,y);settled();break;
   case 2:
    require(!(Boolean)field("capsulePreviewActive")&&primary().equals(RootViewIntegrationProbe.scenarios.get(2).key),"Canceled preview restores original owner");
    double[] reset=(double[])field("state"),metrics=(double[])field("geometry");
    require(Math.abs((reset[5]-reset[4])-metrics[4])<1.01&&Math.abs(reset[9]-1)<.01,"Canceled preview restores capsule bounds and content alpha");
    swipe(-1,false);settled();break;
   case 3:require(primary().equals(RootViewIntegrationProbe.scenarios.get(1).key)&&queue.layout().visible.size()==2,"Left capsule swipe advances keyed slots");swipe(1,false);settled();break;
   case 4:require(primary().equals(RootViewIntegrationProbe.scenarios.get(2).key),"Right capsule swipe restores prior big");swipe(1,false);settled();break;
   case 5:require(primary()==null&&queue.layout().hidden.size()==3,"Empty sentinel can hide all rendered slots without losing sources");
    Region region=new Region();view().getTouchRegion(region);require(!region.isEmpty()&&region.contains(RootViewIntegrationProbe.width/2,Math.round((Float)field("centerY"))),"Hidden scene retains restore touch region");
    RootViewIntegrationProbe.render("queue-hidden.png");swipe(1,true);settled();break;
   case 6:require("owned-queue-extra".equals(primary())&&queue.layout().hidden.size()==2,"Swipe from hidden region restores the exact tail source");
    RootViewIntegrationProbe.render("queue-restored.png");queue.sync(Collections.emptyList());show();settled();break;
   case 7:Region empty=new Region();view().getTouchRegion(empty);require(empty.isEmpty()&&queue.layout().hidden.isEmpty(),"All-source removal releases hidden restore region");originalChecks=checks;fixture();break;
   case 8:
    require(primary().equals(RootViewIntegrationProbe.scenarios.get(1).key)&&secondary().equals(RootViewIntegrationProbe.scenarios.get(2).key),"Owned handoff fixture starts with Timer in SMALL");
    rememberRest();rememberTimer();drag(2,-1,true);settled();break;
   case 9:
    committed("Secondary drag preview");double[] smallPreview=values("smallState");
    require((Boolean)field("capsulePreviewActive")&&(Boolean)field("smallCapsulePreview")&&smallPreview[4]<smallRest[4]&&smallPreview[5]>smallRest[5]&&smallPreview[1]!=smallRest[1],"Secondary horizontal preview expands its clip and translates its own native container");
    renderedSurface(field("secondarySurface"),smallPreview,"queue-small-drag-preview.png");
    require(bounds(smallPreview).equals((RectF)field("smallBounds")),"Secondary touch bounds follow the transformed native preview");
    send(MotionEvent.ACTION_CANCEL,x+dragDx,y);settled();break;
   case 10:restored("Secondary CANCEL");drag(2,-1,true);settled();break;
   case 11:
    committed("Secondary pre-release preview");double[] promoted=values("smallState");send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(field("mainCompact")==timerCompact&&((Long)field("animator"))==timerAnimator,"SMALL to BIG commit transfers the identical Compact and native animator handle");
    require(sameProperties(promoted,values("state")),"SMALL to BIG release retains current native values before its first retargeted frame");sameTimer("SMALL to BIG handoff");settled();break;
   case 12:
    require(primary().equals(RootViewIntegrationProbe.scenarios.get(2).key)&&primary().equals(queue.layout().visible.get(0).key),"Secondary touch commits its exact owner into BIG");
    RootViewIntegrationProbe.render("queue-small-to-main.png");drag(1,1,true);settled();break;
   case 13:
    require(primary().equals(RootViewIntegrationProbe.scenarios.get(2).key)&&field("mainCompact")==timerCompact,"BIG to SMALL preview keeps the Timer committed in BIG");
    double[] demoted=values("state");send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(field("secondaryCompact")==timerCompact&&((Long)field("smallAnimator"))==timerAnimator,"BIG to SMALL commit transfers the identical Compact and native animator handle");
    require(sameProperties(demoted,(double[])field(field("secondarySurface"),"values")),"BIG to SMALL release retains current native values before its first retargeted frame");sameTimer("BIG to SMALL handoff");settled();break;
   case 14:
    require(primary().equals(RootViewIntegrationProbe.scenarios.get(1).key)&&secondary().equals(RootViewIntegrationProbe.scenarios.get(2).key),"BIG touch restores the original keyed pair");
    RootViewIntegrationProbe.render("queue-main-to-small.png");drag(1,1,true);send(MotionEvent.ACTION_UP,x+dragDx,y);settled();break;
   case 15:
    require(primary()==null&&queue.layout().visible.isEmpty()&&queue.layout().hidden.size()==3,"Empty sentinel fixture has no committed capsule");
    incomingKey=queue.preview(1).visible.get(0).key;require(extra.key.equals(incomingKey),"Empty restore preview selects the exact hidden tail key");savedQueue=signature();
    Region restore=new Region();view().getTouchRegion(restore);require(restore.contains(RootViewIntegrationProbe.width/2,Math.round((Float)field("centerY"))),"Empty restore DOWN uses the retained source touch region");
    drag(4,1,true);settled();break;
   case 16:
    Object incoming=field("incomingSurface");IslandItem pending=(IslandItem)field("incomingItem");require(incoming!=null&&incomingKey.equals(field(incoming,"key"))&&pending!=null&&incomingKey.equals(pending.key),"Hidden restore creates an incoming surface and data owner for the selected key");
    incomingCompact=field(incoming,"content");incomingAnimator=(Long)field(incoming,"animator");double[] incomingValues=(double[])field(incoming,"values");double[] incomingMetrics=(double[])field(incoming,"metrics");
    require(primary()==null&&savedQueue.equals(signature())&&contains(queue.layout().hidden,incomingKey),"Incoming hidden owner stays uncommitted until release");
    require(incomingAnimator!=0&&incomingCompact==((Map<?,?>)field("compactOwners")).get(incomingKey)&&incomingValues[3]>.99&&incomingValues[9]==0&&incomingValues[5]-incomingValues[4]>incomingMetrics[15]+.5,"Hidden BIG preview grows its native cutout background while preserving content identity and zero BIG alpha");
    renderedSurface(incoming,incomingValues,"queue-hidden-incoming-preview.png");send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(incomingKey.equals(primary())&&field("mainCompact")==incomingCompact&&((Long)field("animator"))==incomingAnimator&&field("incomingSurface")==null,"Hidden release commits the exact incoming content and animator without rebuilding the owner");settled();break;
   case 17:
    require(incomingKey.equals(primary())&&field("mainCompact")==incomingCompact&&((Long)field("animator"))==incomingAnimator,"Hidden BIG owner remains identical after its donor CUTOUT reset and appearance");
    RootViewIntegrationProbe.render("queue-hidden-incoming-committed.png");fixture();break;
   case 18:rememberRest();drag(2,-1,false);settled();break;
   case 19:
    committed("Short secondary preview");require((Boolean)field("capsulePreviewActive"),"Drag above touch slop but below swipe threshold previews the native owners");
    send(MotionEvent.ACTION_UP,x+dragDx,y);settled();break;
   case 20:
    restored("Short secondary release");require(!view().isExpanded()&&RootViewIntegrationProbe.host.opens==0,"Short drag does not become a tap or open an app");
    drag(2,-1,true);extraPointerCancel();send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(!(Boolean)field("touchActive")&&!(Boolean)field("capsulePreviewActive")&&((Integer)field("touchPointer"))==-1,"Extra pointer and CANCEL clear the captured gesture owner");settled();break;
   case 21:
    restored("Extra-pointer CANCEL");drag(2,-1,true);view().setSuppressed(true);send(MotionEvent.ACTION_UP,x+dragDx,y);
    Region blocked=new Region();view().getTouchRegion(blocked);require(blocked.isEmpty()&&!(Boolean)field("touchActive")&&!(Boolean)field("capsulePreviewActive"),"Visibility interruption cancels preview and removes the touch region");committed("Suppressed stray release");view().reveal();settled();break;
   case 22:
    restored("Visibility reveal");require(view().visibleAlpha()>.99&&((View)field("secondarySurface")).getAlpha()>.99,"Both capsule surfaces return after visibility interruption");
    removedSurface=field("secondarySurface");removedCompact=field("secondaryCompact");drag(2,-1,true);
    IslandItem replacement=RootViewIntegrationProbe.sample("queue-replacement","Replacement activity",null);
    queue.sync(Arrays.asList(RootViewIntegrationProbe.scenarios.get(1),replacement,extra));show();replacementQueue=signature();send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(!(Boolean)field("touchActive")&&!(Boolean)field("capsulePreviewActive")&&replacementQueue.equals(signature())&&!view().isExpanded(),"Replacing the touched source cancels its preview and rejects stale release");settled();break;
   case 23:
    require(replacementQueue.equals(signature())&&((Long)field(removedSurface,"animator"))==0&&(Boolean)field(removedCompact,"released"),"Removed source releases its outgoing animator and Compact after settling");
    cleanupContents=new ArrayList<>(((Map<?,?>)field("compactOwners")).values());require(cleanupContents.contains(field("mainCompact"))&&cleanupContents.contains(field("secondaryCompact")),"Cleanup tracks both real Compact owners before removal");
    drag(2,-1,true);queue.sync(Collections.emptyList());show();send(MotionEvent.ACTION_UP,x+dragDx,y);
    require(!(Boolean)field("touchActive")&&!(Boolean)field("capsulePreviewActive")&&queue.layout().visible.isEmpty(),"All-source removal cancels an active secondary gesture without committing it");settled();break;
   case 24:
    Region removed=new Region();view().getTouchRegion(removed);
    require(removed.isEmpty()&&queue.layout().hidden.isEmpty()&&field("secondarySurface")==null&&field("incomingSurface")==null&&((List<?>)field("outgoingSurfaces")).isEmpty()&&((Long)field("smallAnimator"))==0&&((Map<?,?>)field("compactOwners")).isEmpty(),"All-source cleanup releases cached owners, extra surfaces, secondary animator and restore region");
    boolean disposed=true;for(Object content:cleanupContents)disposed&=(Boolean)field(content,"released")&&((HyperOsCompactFactory.Compact)content).view.getParent()==null&&((HyperOsCompactFactory.Compact)content).smallView().getParent()==null;
    require(disposed&&RootViewIntegrationProbe.host.opens==0,"Removed Compact modules are disposed and detached without any app operation");finish();break;
  }
 }
 static void finish()throws Exception{
  JSONObject report=new JSONObject().put("passed",true).put("checks",checks).put("original_checks",originalChecks).put("new_checks",checks-originalChecks)
    .put("scope","Native keyed queue and actual source-app capsule/restore-region touch dispatch on private virtual display; persistent Compact/clock/native-owner handoffs, rendered owner clips, hidden incoming ownership and cancellation with sample sources only; live source/window integration not proven")
    .put("screenshots",Arrays.asList("queue-drag-preview.png","queue-hidden.png","queue-restored.png","queue-small-drag-preview.png","queue-small-to-main.png","queue-main-to-small.png","queue-hidden-incoming-preview.png","queue-hidden-incoming-committed.png"));
  try(FileOutputStream out=new FileOutputStream(new File(RootViewIntegrationProbe.output,"queue-validation.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
  queue.close();RootViewIntegrationProbe.host.presentation=null;RootViewIntegrationProbe.surfaceHost.release();RootViewIntegrationProbe.ownedDisplay.release();RootViewIntegrationProbe.displayFrames.close();System.out.println("QUEUE INTEGRATION PASS checks="+checks);System.exit(0);
 }
}
