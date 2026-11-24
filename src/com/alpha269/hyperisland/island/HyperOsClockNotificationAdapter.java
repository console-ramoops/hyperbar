package com.alpha269.hyperisland.island;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.text.format.DateFormat;
import java.util.Date;

/** Exact installed Samsung notification channel/icon identities distinguish clock business states. */
public final class HyperOsClockNotificationAdapter {
    private HyperOsClockNotificationAdapter(){}
    public static void configure(Context context,Notification notification,IslandItem item){
        if(!HyperOsActionSemantics.CLOCK.equals(item.packageName)||!Notification.CATEGORY_ALARM.equals(notification.category))return;
        String channel=notification.getChannelId(),icon=HyperOsActionSemantics.resourceName(context,notification.getSmallIcon(),item.packageName);
        boolean firing="notification_channel_firing_alarm_and_timer".equals(channel)||"notification_channel_firing_alarm_and_timer_in_dex".equals(channel);
        if(firing&&"stat_sys_alarm".equals(icon))item.clockStyle=1;
        else if(firing&&"ic_clock_timer_ongoing_chip_timer".equals(icon))item.clockStyle=3;
        else if("notification_channel_snoozed_alarm".equals(channel)&&"stat_sys_snooze".equals(icon))item.clockStyle=2;
        else return;
        item.kind=IslandItem.Kind.TIMER;item.chronoBase=0;item.pausedMs=-1;item.chronoCountDown=item.clockStyle==3;
        // Snoozed notification.when is its POST time, never the next alarm time.
        item.clockText=null;
        long exactTime=HyperOsAlarmData.exactTime(notification.extras,item.clockStyle);
        if(exactTime==0&&item.clockStyle==1&&notification.when>100000000000L)exactTime=notification.when;
        if(context!=null&&exactTime>0)
            try{item.clockText=DateFormat.getTimeFormat(context).format(new Date(exactTime));}catch(RuntimeException unavailable){}
        item.trailing=item.clockText==null?IslandItem.Trailing.NONE:IslandItem.Trailing.TEXT;
        if(item.clockText!=null)item.trailingText=item.clockText;
        item.overline=item.clockStyle==3?"Timer":"Alarm";
    }
    public static int actionGlyph(Context context,IslandItem item,CharSequence title,int fallback){
        if(fallback!=0||context==null||!HyperOsActionSemantics.CLOCK.equals(item.packageName)||item.clockStyle==0)return fallback;
        try{Resources resources=context.createPackageContext(item.packageName,0).getResources();
            if(matches(resources,"dismiss",title)||matches(resources,"dismiss_all",title))return item.clockStyle==1?12:6;
            if(item.clockStyle==1&&matches(resources,"snooze",title))return 15;
        }catch(Exception unavailable){}
        return fallback;
    }
    private static boolean matches(Resources resources,String name,CharSequence title){
        int id=resources.getIdentifier(name,"string",HyperOsActionSemantics.CLOCK);return id!=0&&TextUtils.equals(resources.getText(id),title);
    }
    public static void orderActions(IslandItem item){
        if(item.clockStyle!=1)return;
        // Original HyperOS order is snooze, dismiss. Unknown source actions keep their order.
        if(item.actions.size()==2&&item.actions.get(0).glyph==12&&item.actions.get(1).glyph==15)
            java.util.Collections.swap(item.actions,0,1);
    }
}
