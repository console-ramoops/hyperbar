package com.alpha269.hyperisland.xposed;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.alpha269.hyperisland.island.HyperOsAlarmCommand;
import com.alpha269.hyperisland.island.HyperOsAlarmData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Native Clock factories issue the capabilities; only marked commands enter the guard. */
final class HyperOsAlarmCapabilities {
    private static final String APP=HyperOsAlarmCommand.APP, PREFIX=APP+".alarm.";
    private final HyperOsAlarmCommand commands=new HyperOsAlarmCommand();
    private final ThreadLocal<Deque<Capture>> captures=new ThreadLocal<>();
    private final Method factory,defaultStop,ids,query,addAction,receive;
    private final Field utilInstance,companion,ringId,ringTime,stopped;
    private final List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
    static final class Capture {
        final Object builder;
        final Intent nativeIntent;
        CharSequence title;
        PendingIntent nativeAction;
        Capture(Object builder,Intent intent){this.builder=builder;nativeIntent=intent==null?null:new Intent(intent);}
    }
    HyperOsAlarmCapabilities(ClassLoader loader,Class<?> alarm,Class<?> builder,Method ids,Method query)throws Exception{
        this.ids=ids;this.query=query;
        Class<?> util=Class.forName(PREFIX+"utils.AlarmUtil",false,loader);
        Class<?> base=Class.forName(PREFIX+"model.AlarmItemBase",false,loader);
        Class<?> helper=Class.forName(PREFIX+"repo.AlarmNotificationHelperBase",false,loader);
        Class<?> service=Class.forName(PREFIX+"service.AlarmServiceExtended",false,loader);
        Class<?> model=Class.forName(PREFIX+"model.Alarm",false,loader);
        Class<?> receiver=Class.forName(HyperOsAlarmCommand.RECEIVER,false,loader);
        factory=method(util,"dismissOrSnoozeAlarmInDexIntent",Context.class,alarm,String.class);
        defaultStop=method(base,"isDefaultStop");addAction=method(builder,"a",String.class,PendingIntent.class);
        receive=method(receiver,"onReceive",Context.class,Intent.class);
        utilInstance=field(util,"INSTANCE");companion=field(helper,"Companion");
        ringId=field(service,"sAlarmAlertId");ringTime=field(service,"sAlarmAlertTime");stopped=field(model,"isStopAlarmAlert");
        if(factory.getReturnType()!=Intent.class||defaultStop.getReturnType()!=boolean.class
            ||addAction.getReturnType()!=void.class||receive.getReturnType()!=void.class
            ||ringId.getType()!=int.class||ringTime.getType()!=long.class||stopped.getType()!=boolean.class
            ||!Modifier.isStatic(query.getModifiers())||!Modifier.isStatic(companion.getModifiers())
            ||!Modifier.isStatic(utilInstance.getModifiers())||!Modifier.isStatic(ringId.getModifiers())
            ||!Modifier.isStatic(ringTime.getModifiers())||!Modifier.isStatic(stopped.getModifiers())
            ||companion.getType()!=ids.getDeclaringClass()||utilInstance.getType()!=util)
            throw new IllegalStateException("Unsupported Samsung Clock control ABI");
    }
    void install()throws Exception{
        try{
            hooks.add(XposedBridge.hookMethod(addAction,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Deque<Capture> stack=captures.get();if(stack==null||stack.isEmpty()||p.hasThrowable())return;
                    Capture capture=stack.peek();
                    if(capture.builder==p.thisObject&&p.args[0] instanceof CharSequence&&p.args[1] instanceof PendingIntent){
                        capture.title=(CharSequence)p.args[0];capture.nativeAction=(PendingIntent)p.args[1];
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(receive,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    Intent intent=(Intent)p.args[1];
                    try{
                        if(!HyperOsAlarmCommand.marked(intent)&&(intent==null||!HyperOsAlarmCommand.PRIVATE_ACTION.equals(intent.getAction())))return;
                        Context context=(Context)p.args[0];
                        if(!APP.equals(context.getPackageName())||!commands.accept(intent,state(context)))p.setResult(null);
                        else p.args[1]=commands.nativeCommand(intent);
                    }catch(Throwable invalid){p.setResult(null);}
                }
            }));
        }catch(Throwable failure){
            for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();
            if(failure instanceof Exception)throw (Exception)failure;
            throw new IllegalStateException("Could not bind Samsung Clock controls",failure);
        }
    }
    void enter(Object builder,Intent intent){
        Deque<Capture> stack=captures.get();
        if(stack==null){stack=new ArrayDeque<>();captures.set(stack);}
        stack.push(new Capture(builder,intent));
    }
    void close(){for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();captures.remove();}
    Capture leave(){
        Deque<Capture> stack=captures.get();if(stack==null||stack.isEmpty())return null;
        Capture capture=stack.pop();if(stack.isEmpty())captures.remove();return capture;
    }
    void ringing(Context context,Object alarm,HyperOsAlarmData.Snapshot snapshot,Bundle extras)throws Exception{
        CharSequence dismiss=label(context,"dismiss"),snooze=label(context,"snooze");
        if(dismiss==null)return;
        PendingIntent dismissAction=issue(context,(Intent)factory.invoke(utilInstance.get(null),context,alarm,HyperOsAlarmCommand.DISMISS),snapshot);
        if(dismissAction==null)return;
        extras.putParcelable(HyperOsAlarmData.EXTRA_DISMISS,dismissAction);extras.putCharSequence(HyperOsAlarmData.EXTRA_DISMISS_TITLE,dismiss);
        extras.remove(HyperOsAlarmData.EXTRA_SNOOZE);extras.remove(HyperOsAlarmData.EXTRA_SNOOZE_TITLE);
        extras.putInt(HyperOsAlarmData.EXTRA_CONTROLS,HyperOsAlarmData.VERSION);
        if(snooze!=null&&!Boolean.TRUE.equals(defaultStop.invoke(alarm))){
            PendingIntent snoozeAction=issue(context,(Intent)factory.invoke(utilInstance.get(null),context,alarm,HyperOsAlarmCommand.SNOOZE),snapshot);
            if(snoozeAction!=null){extras.putParcelable(HyperOsAlarmData.EXTRA_SNOOZE,snoozeAction);extras.putCharSequence(HyperOsAlarmData.EXTRA_SNOOZE_TITLE,snooze);}
        }
    }
    void snoozed(Context context,HyperOsAlarmData.Snapshot snapshot,Capture capture,Bundle extras)throws Exception{
        if(capture==null||capture.title==null||!owned(context,capture.nativeAction))return;
        PendingIntent dismiss=issue(context,capture.nativeIntent,snapshot);if(dismiss==null)return;
        extras.putParcelable(HyperOsAlarmData.EXTRA_DISMISS,dismiss);extras.putCharSequence(HyperOsAlarmData.EXTRA_DISMISS_TITLE,capture.title);
        extras.putInt(HyperOsAlarmData.EXTRA_CONTROLS,HyperOsAlarmData.VERSION);
    }
    private PendingIntent issue(Context context,Intent nativeIntent,HyperOsAlarmData.Snapshot snapshot)throws Exception{
        Intent intent=commands.seal(nativeIntent,snapshot,context.getApplicationInfo().uid);if(intent==null)return null;
        PendingIntent pending=PendingIntent.getBroadcast(context,0x4842,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return owned(context,pending)?pending:null;
    }
    private static boolean owned(Context context,PendingIntent pending){return pending!=null&&APP.equals(pending.getCreatorPackage())
        &&context.getApplicationInfo().uid==pending.getCreatorUid();}
    private HyperOsAlarmCommand.State state(final Context context){return new HyperOsAlarmCommand.State(){
        public int ownerUid(){return context.getApplicationInfo().uid;}
        public boolean stopped()throws Exception{return stopped.getBoolean(null);}
        public HyperOsAlarmData.Snapshot current(int style)throws Exception{
            if(style==2)return HyperOsAlarmData.from(2,HyperOsAlarmHooks.snoozedSnapshot(context,companion.get(null),ids,query));
            if(style!=1)return null;
            int id=ringId.getInt(null);long time=ringTime.getLong(null);
            if(id<=0||time<100_000_000_000L||stopped.getBoolean(null))return null;
            Map<Integer,Long> value=new TreeMap<>();value.put(id,time);
            if(id!=ringId.getInt(null)||time!=ringTime.getLong(null))return null;
            return HyperOsAlarmData.from(1,value);
        }
    };}
    private static CharSequence label(Context context,String name){
        int id=context.getResources().getIdentifier(name,"string",APP);return id==0?null:context.getText(id);
    }
    private static Method method(Class<?> owner,String name,Class<?>... parameters)throws Exception{
        Method method=owner.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
    private static Field field(Class<?> owner,String name)throws Exception{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
}
