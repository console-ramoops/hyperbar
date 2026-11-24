package com.alpha269.hyperisland.xposed;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.alpha269.hyperisland.island.HyperOsAlarmData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Source-owned alarm timestamps. Notification posting time is never a snooze time. */
public final class HyperOsAlarmHooks {
    public static final String APP="com.sec.android.app.clockpackage";
    private static final String PREFIX=APP+".alarm.";
    private static final List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
    private static boolean ready;
    private HyperOsAlarmHooks(){}

    public static synchronized void install(ClassLoader loader)throws Exception{
        if(ready)return;
        Class<?> alarm=Class.forName(PREFIX+"model.AlarmItem",false,loader);
        Class<?> ringing=Class.forName(PREFIX+"repo.AlarmNotificationHelper$Companion",false,loader);
        Class<?> snoozed=Class.forName(PREFIX+"repo.AlarmNotificationHelperBase$Companion",false,loader);
        Class<?> builder=Class.forName("l3.k",false,loader);
        Class<?> repo=Class.forName(PREFIX+"repo.AlarmMainDbRepoHelper",false,loader);
        Method ring=method(ringing,"makeNotify",Context.class,alarm);
        Method snooze=method(snoozed,"setSnoozeContentTitle",Context.class,Intent.class,builder,alarm,Calendar.class);
        Method ids=method(snoozed,"getSSnoozedAlarmIds");
        Method query=method(repo,"getAlarm",Context.class,int.class);
        Method extras=method(builder,"c");
        if(ring.getReturnType()!=Notification.class||ids.getReturnType()!=int[].class
            ||query.getReturnType()!=alarm||extras.getReturnType()!=Bundle.class)
            throw new IllegalStateException("Unsupported Samsung Clock notification ABI");
        try{
            hooks.add(XposedBridge.hookMethod(ring,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam parameter){
                    if(!ready||parameter.hasThrowable()||!(parameter.getResult() instanceof Notification))return;
                    try{
                        Context context=(Context)parameter.args[0];if(!trusted(context))return;
                        Map<Integer,Long> snapshot=ringingSnapshot(parameter.args[1]);if(snapshot==null)return;
                        Notification notification=(Notification)parameter.getResult();
                        if(!Notification.CATEGORY_ALARM.equals(notification.category))return;
                        if(notification.extras==null)notification.extras=new Bundle();
                        write(notification.extras,1,snapshot);
                    }catch(Throwable error){report(error);}
                }
            }));
            hooks.add(XposedBridge.hookMethod(snooze,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam parameter){
                    if(!ready||parameter.hasThrowable())return;
                    try{
                        Context context=(Context)parameter.args[0];if(!trusted(context))return;
                        Map<Integer,Long> snapshot=snoozedSnapshot(context,parameter.thisObject,ids,query);
                        if(snapshot==null)return;
                        Object value=extras.invoke(parameter.args[2]);
                        if(value instanceof Bundle)write((Bundle)value,2,snapshot);
                    }catch(Throwable error){report(error);}
                }
            }));
            ready=true;
        }catch(Throwable error){
            for(XC_MethodHook.Unhook hook:hooks)hook.unhook();hooks.clear();
            if(error instanceof Exception)throw (Exception)error;
            throw new IllegalStateException("Could not bind Samsung Clock timestamp hooks",error);
        }
    }
    private static boolean trusted(Context context){return context!=null&&APP.equals(context.getPackageName());}
    private static Method method(Class<?> owner,String name,Class<?>... parameters)throws Exception{
        Method method=owner.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
    private static Object field(Object owner,String name)throws Exception{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()){
            try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
            catch(NoSuchFieldException absent){}
        }
        throw new NoSuchFieldException(name);
    }
    private static Map<Integer,Long> ringingSnapshot(Object alarm)throws Exception{
        if(alarm==null)return null;
        int id=((Number)field(alarm,"mId")).intValue();long time=((Number)field(alarm,"mAlarmAlertTime")).longValue();
        if(id<=0||time<100_000_000_000L)return null;
        Map<Integer,Long> snapshot=new TreeMap<>();snapshot.put(id,time);return snapshot;
    }
    private static Map<Integer,Long> snoozedSnapshot(Context context,Object companion,Method ids,Method query)throws Exception{
        Object value=ids.invoke(companion);if(!(value instanceof int[]))return null;
        int[] current=((int[])value).clone();if(current.length==0||current.length>64)return null;
        Map<Integer,Long> snapshot=new TreeMap<>();
        for(int id:current){
            if(id<=0||snapshot.containsKey(id))return null;
            Object alarm=query.invoke(null,context,id);if(alarm==null)return null;
            if(((Number)field(alarm,"mActivate")).intValue()!=2||((Number)field(alarm,"mId")).intValue()!=id)return null;
            long time=((Number)field(alarm,"mAlarmAlertTime")).longValue();if(time<100_000_000_000L)return null;
            snapshot.put(id,time);
        }
        int[] latest=(int[])ids.invoke(companion);
        if(latest==null||latest.length!=snapshot.size())return null;
        java.util.Set<Integer> verified=new java.util.HashSet<>();
        for(int id:latest)if(!snapshot.containsKey(id)||!verified.add(id))return null;
        return snapshot;
    }
    private static void write(Bundle extras,int style,Map<Integer,Long> snapshot){
        int[] ids=new int[snapshot.size()];long[] times=new long[snapshot.size()];int index=0;long minimum=Long.MAX_VALUE;
        for(Map.Entry<Integer,Long> entry:snapshot.entrySet()){
            ids[index]=entry.getKey();times[index++]=entry.getValue();minimum=Math.min(minimum,entry.getValue());
        }
        extras.putInt(HyperOsAlarmData.EXTRA_VERSION,HyperOsAlarmData.VERSION);
        extras.putInt(HyperOsAlarmData.EXTRA_STYLE,style);extras.putIntArray(HyperOsAlarmData.EXTRA_IDS,ids);
        extras.putLongArray(HyperOsAlarmData.EXTRA_TIMES,times);extras.putLong(HyperOsAlarmData.EXTRA_TIME,minimum);
    }
    private static void report(Throwable error){android.util.Log.w("HyperBar","Samsung Clock timestamp bridge unavailable",error);}
}
