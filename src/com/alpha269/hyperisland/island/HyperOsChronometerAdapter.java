package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.res.Resources;
import android.widget.RemoteViews;
import java.lang.reflect.Field;
import java.util.Collection;

/** Reads the producer's dedicated timer payload without inflating or executing RemoteViews. */
public final class HyperOsChronometerAdapter {
    public static final String EXTRA_VIEW="android.ongoingActivityNoti.chronometerRemoteView";
    public static final String EXTRA_TAG="android.ongoingActivityNoti.chronometerRemoteViewTag";
    private HyperOsChronometerAdapter(){}

    public static boolean apply(Context context,IslandItem item,RemoteViews remote,CharSequence tag){
        if(context==null||remote==null||tag==null||!item.packageName.equals(remote.getPackage()))return false;
        String name;boolean countdown=false;
        if(HyperOsActionSemantics.CLOCK.equals(item.packageName)){
            if("timer_ongoing_activity_chronometer".contentEquals(tag)){name="running_chronometer";countdown=true;}
            else if("stopwatch_ongoing_activity_chronometer".contentEquals(tag))name="stopwatch_ongoing_activity_chronometer";
            else return false;
        }else if(HyperOsActionSemantics.RECORDER.equals(item.packageName)&&"voice_recorder_ongoing_activity_chronometer_tag".contentEquals(tag))
            name="ongoing_current_time_chronometer";
        else return false;
        try{
            Resources resources=context.createPackageContext(item.packageName,0).getResources();
            int id=resources.getIdentifier(name,"id",item.packageName);if(id==0)return false;
            Object actions=field(remote,"mActions");if(!(actions instanceof Collection))return false;
            Long base=null,stopped=null;Boolean started=null,useStopped=null;CharSequence fixed=null;
            for(Object action:(Collection<?>)actions){
                // Only the framework's typed scalar actions participate; other payloads stay inert.
                if(action==null||!action.getClass().getName().equals("android.widget.RemoteViews$ReflectionAction"))continue;
                if(!Integer.valueOf(id).equals(scalar(action,"mViewId","viewId")))continue;
                Object method=scalar(action,"mMethodName","methodName"),value=scalar(action,"mValue","value");
                if("setBase".equals(method)&&value instanceof Long)base=(Long)value;
                else if("setStarted".equals(method)&&value instanceof Boolean)started=(Boolean)value;
                else if("setCountDown".equals(method)&&value instanceof Boolean)countdown=(Boolean)value;
                else if("hidden_semSetStoppedTime".equals(method)&&value instanceof Long)stopped=(Long)value;
                else if("hidden_semSetUseStoppedTimeText".equals(method)&&value instanceof Boolean)useStopped=(Boolean)value;
                else if("setText".equals(method)&&value instanceof CharSequence)fixed=(CharSequence)value;
            }
            long paused=Boolean.TRUE.equals(useStopped)&&stopped!=null?stopped:fixed!=null&&!Boolean.TRUE.equals(started)?duration(fixed):-1;
            if(paused>=0){item.pausedMs=paused;item.chronoBase=0;}
            else if(Boolean.TRUE.equals(started)&&!Boolean.TRUE.equals(useStopped)&&base!=null){item.chronoBase=base;item.pausedMs=-1;}
            else return false;
            item.chronoCountDown=countdown;item.trailing=IslandItem.Trailing.CHRONOMETER;
            if(HyperOsActionSemantics.CLOCK.equals(item.packageName))item.kind=IslandItem.Kind.TIMER;
            return true;
        }catch(Exception unavailable){return false;}
    }
    private static Object scalar(Object owner,String current,String legacy){Object value=field(owner,current);return value!=null?value:field(owner,legacy);}
    private static Object field(Object owner,String name){
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{
            Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
        }catch(Exception unavailable){}
        return null;
    }
    /** Localized H:MM:SS / MM:SS and stopwatch hundredths; unknown text remains unknown. */
    public static long duration(CharSequence text){
        if(text==null)return -1;StringBuilder normalized=new StringBuilder();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);int digit=Character.digit(c,10);
            if(digit>=0)normalized.append((char)('0'+digit));
            else if(c==':'||c=='.'||c==','||c=='\u066b')normalized.append(c==':'?':':'.');
            else if(!Character.isWhitespace(c)&&c!='\u200e'&&c!='\u200f'&&c!='\u061c')return -1;
        }
        String[] parts=normalized.toString().split(":",-1);if(parts.length<2||parts.length>3)return -1;
        try{
            long seconds=0;for(int i=0;i<parts.length-1;i++){
                if(parts[i].isEmpty())return -1;long value=Long.parseLong(parts[i]);
                if(i>0&&value>=60)return -1;seconds=Math.addExact(Math.multiplyExact(seconds,60),value);
            }
            String[] last=parts[parts.length-1].split("\\.",-1);if(last.length>2||last[0].isEmpty())return -1;
            long value=Long.parseLong(last[0]);if(value>=60)return -1;seconds=Math.addExact(Math.multiplyExact(seconds,60),value);
            long milliseconds=Math.multiplyExact(seconds,1000);
            if(last.length==2){if(last[1].isEmpty()||last[1].length()>3)return -1;
                milliseconds=Math.addExact(milliseconds,Long.parseLong((last[1]+"000").substring(0,3)));}
            return milliseconds;
        }catch(ArithmeticException|NumberFormatException invalid){return -1;}
    }
}
