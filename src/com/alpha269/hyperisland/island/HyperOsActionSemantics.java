package com.alpha269.hyperisland.island;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/** Source capability identity selects HyperOS art; no operation is synthesized. */
public final class HyperOsActionSemantics {
    public static final String CLOCK="com.sec.android.app.clockpackage";
    public static final String RECORDER="com.sec.android.app.voicenote";
    private HyperOsActionSemantics(){}
    public static int glyph(String packageName,String resourceName,String declaredSemantic){
        if(declaredSemantic!=null){
            if(declaredSemantic.startsWith("action_pause"))return 2;
            if(declaredSemantic.startsWith("action_restart"))return 1;
            if(declaredSemantic.equals("action_record_end")||declaredSemantic.equals("action_close"))return 6;
            if(declaredSemantic.equals("action_hangup"))return 9;
            if(declaredSemantic.equals("action_mark_timer"))return 11;
            if(declaredSemantic.equals("action_end"))return 12;
            if(declaredSemantic.equals("action_done"))return 14;
            if(declaredSemantic.equals("action_later"))return 15;
        }
        if(RECORDER.equals(packageName)&&resourceName!=null)switch(resourceName){
            case "ic_ongoing_record_pause":return 2;
            case "ic_ongoing_record_resume":return 1;
            case "ic_ongoing_record_stop":return 6;
            case "ic_ongoing_record_cancel":return 13;
        }
        if(!CLOCK.equals(packageName)||resourceName==null)return 0;
        switch(resourceName){
            case "ic_clock_ic_timer_pause_notification":case "ic_clock_ic_timer_pause":
            case "timer_now_bar_button_pause":case "timer_ongoing_activity_extended_view_button_pause":
            case "ic_stopwatch_ongoing_btn_pause":case "ic_stopwatch_pause":return 2;
            case "ic_clock_ic_timer_start_notification":case "ic_clock_ic_timer_restart":
            case "ic_clock_ic_timer_resume":case "timer_now_bar_button_restart":
            case "timer_now_bar_button_resume":case "timer_ongoing_activity_extended_view_button_restart":
            case "timer_ongoing_activity_extended_view_button_resume":case "ic_stopwatch_ongoing_btn_play":
            case "ic_stopwatch_start_resume":return 1;
            case "ic_clock_ic_timer_cancel_notification":case "ic_clock_ic_timer_cancel":
            case "timer_ongoing_activity_extended_view_button_cancel":
            case "ic_clock_stopwatch_cancel":return 6;
            case "ic_stopwatch_ongoing_btn_lap":case "ic_stopwatch_lap":return 11;
            case "ic_stopwatch_ongoing_btn_cancel":return 12;
            default:return 0;
        }
    }
    public static boolean unavailable(String packageName,String resourceName){
        if(CLOCK.equals(packageName))return "ic_stopwatch_ongoing_btn_lap_disabled".equals(resourceName)||"ic_stopwatch_ongoing_btn_play_disabled".equals(resourceName);
        return RECORDER.equals(packageName)&&("ic_ongoing_record_resume_disable".equals(resourceName)||"ic_ongoing_record_stop_disable".equals(resourceName));
    }
    public static String resourceName(Context context,Icon icon,String packageName){
        if(context==null||icon==null||icon.getType()!=Icon.TYPE_RESOURCE)return null;
        try{String owner=icon.getResPackage();if(owner==null||owner.isEmpty())owner=packageName;
            if(!packageName.equals(owner))return null;
            return context.createPackageContext(owner,0).getResources().getResourceEntryName(icon.getResId());
        }catch(Exception unavailable){return null;}
    }
}
