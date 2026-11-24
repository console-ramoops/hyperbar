package com.alpha269.hyperisland.island;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Seals source-issued commands and checks the source state immediately before native dispatch. */
public final class HyperOsAlarmCommand {
    public static final String APP="com.sec.android.app.clockpackage";
    public static final String RECEIVER=APP+".alarm.receiver.AlarmSecurityReceiver";
    public static final String DISMISS="com.samsung.sec.android.clockpackage.alarm.DISMISS_ALARM_IN_DEX";
    public static final String SNOOZE="com.samsung.sec.android.clockpackage.alarm.SNOOZE_ALARM_IN_DEX";
    public static final String DISMISS_SNOOZED="com.samsung.sec.android.clockpackage.alarm.ALARM_NOTIFICATION_CLEAR_SNOOZE_ALL_COVERSTATE";
    public static final String PRIVATE_ACTION="com.alpha269.hyperisland.alarm.CONTROL";
    private static final String MARKER="com.alpha269.hyperisland.alarm.command";
    private static final String NATIVE_ACTION="com.alpha269.hyperisland.alarm.nativeAction";
    private static final String UID="com.alpha269.hyperisland.alarm.ownerUid";
    private static final String CATEGORY="com.alpha269.hyperisland.alarm.identity.";
    private final String session=UUID.randomUUID().toString();
    private final LinkedHashSet<String> consumed=new LinkedHashSet<>();

    public interface State {
        int ownerUid();
        HyperOsAlarmData.Snapshot current(int style)throws Exception;
        boolean stopped()throws Exception;
    }

    private static int style(String action){return DISMISS.equals(action)||SNOOZE.equals(action)?1:DISMISS_SNOOZED.equals(action)?2:0;}
    private static boolean nativeTarget(Intent intent){
        ComponentName component=intent.getComponent();
        return APP.equals(intent.getPackage())&&component!=null&&APP.equals(component.getPackageName())
            &&RECEIVER.equals(component.getClassName());
    }
    public static boolean marked(Intent intent){return intent!=null&&intent.hasExtra(MARKER);}

    public Intent seal(Intent nativeIntent,HyperOsAlarmData.Snapshot snapshot,int ownerUid)throws Exception{
        if(nativeIntent==null||snapshot==null||ownerUid<0||!nativeTarget(nativeIntent)
            ||style(nativeIntent.getAction())!=snapshot.style||marked(nativeIntent))return null;
        if(snapshot.style==1){
            byte[] data=nativeIntent.getByteArrayExtra("com.samsung.sec.android.clockpackage.alarm.ALARM_DATA");
            if(data==null||data.length==0)return null;
        }
        Intent sealed=new Intent(nativeIntent);
        String nativeAction=nativeIntent.getAction();
        Bundle extras=new Bundle();snapshot.write(extras);extras.putString(MARKER,session);extras.putInt(UID,ownerUid);
        extras.putString(NATIVE_ACTION,nativeAction);
        sealed.putExtras(extras);
        // A stock receiver does not recognize this action if the module/scope is removed.
        sealed.setAction(PRIVATE_ACTION);
        sealed.addCategory(CATEGORY+session+"."+identity(snapshot)+"."+nativeAction);
        return sealed;
    }

    /** Called only after accept; native Clock receives the original routing and payload. */
    public Intent nativeCommand(Intent sealed){
        String action=sealed.getStringExtra(NATIVE_ACTION);
        if(!PRIVATE_ACTION.equals(sealed.getAction())||style(action)==0)throw new IllegalArgumentException("Invalid private alarm command");
        Intent original=new Intent(sealed).setAction(action);
        if(original.getCategories()!=null)for(String category:new java.util.HashSet<>(original.getCategories()))
            if(category.startsWith(CATEGORY))original.removeCategory(category);
        for(String key:new String[]{MARKER,NATIVE_ACTION,UID,HyperOsAlarmData.EXTRA_VERSION,HyperOsAlarmData.EXTRA_STYLE,
            HyperOsAlarmData.EXTRA_IDS,HyperOsAlarmData.EXTRA_TIMES,HyperOsAlarmData.EXTRA_TIME})original.removeExtra(key);
        return original;
    }

    /** Only marked commands use this path; Samsung's own intents remain untouched. */
    public synchronized boolean accept(Intent intent,State state){
        try{
            if(intent==null||state==null||!PRIVATE_ACTION.equals(intent.getAction())||!nativeTarget(intent)||!session.equals(intent.getStringExtra(MARKER)))return false;
            String nativeAction=intent.getStringExtra(NATIVE_ACTION);int style=style(nativeAction);
            HyperOsAlarmData.Snapshot expected=HyperOsAlarmData.read(intent.getExtras(),style);
            if(expected==null||intent.getIntExtra(UID,-1)!=state.ownerUid())return false;
            String identity=identity(expected);
            String category=CATEGORY+session+"."+identity+"."+nativeAction;
            if(intent.getCategories()==null||!intent.getCategories().contains(category)||consumed.contains(identity))return false;
            if(style==1&&state.stopped())return false;
            if(!expected.same(state.current(style)))return false;
            consumed.add(identity);
            while(consumed.size()>128)consumed.remove(consumed.iterator().next());
            return true;
        }catch(Exception invalid){return false;}
    }
    private static String identity(HyperOsAlarmData.Snapshot snapshot)throws Exception{
        StringBuilder text=new StringBuilder().append(snapshot.style);
        for(int i=0;i<snapshot.size();i++)text.append(':').append(snapshot.id(i)).append('/').append(snapshot.time(i));
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);
        for(byte value:hash){int n=value&255;hex.append(Character.forDigit(n>>>4,16)).append(Character.forDigit(n&15,16));}
        return hex.toString();
    }
}
