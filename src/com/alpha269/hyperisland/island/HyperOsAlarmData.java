package com.alpha269.hyperisland.island;

import android.os.Bundle;
import java.util.Arrays;
import java.util.Map;

/** Private Clock notification metadata; all times come from the source alarm rows. */
public final class HyperOsAlarmData {
    private static final String PREFIX = "com.alpha269.hyperisland.alarm.";
    public static final int VERSION = 1;
    public static final String EXTRA_VERSION = PREFIX + "version";
    public static final String EXTRA_STYLE = PREFIX + "style";
    public static final String EXTRA_IDS = PREFIX + "ids";
    public static final String EXTRA_TIMES = PREFIX + "times";
    public static final String EXTRA_TIME = PREFIX + "time";
    public static final String EXTRA_CONTROLS = PREFIX + "controls";
    public static final String EXTRA_DISMISS = PREFIX + "dismiss";
    public static final String EXTRA_DISMISS_TITLE = PREFIX + "dismissTitle";
    public static final String EXTRA_SNOOZE = PREFIX + "snooze";
    public static final String EXTRA_SNOOZE_TITLE = PREFIX + "snoozeTitle";

    private HyperOsAlarmData() {}

    /** Reject incomplete or inconsistent snapshots instead of inventing a snooze time. */
    public static long exactTime(Bundle extras, int style) {
        Snapshot snapshot=read(extras,style);
        return snapshot==null?0:snapshot.minimum;
    }

    public static final class Snapshot {
        public final int style;
        public final long minimum;
        private final int[] ids;
        private final long[] times;
        private Snapshot(int style,int[] ids,long[] times,long minimum){
            this.style=style;this.ids=ids.clone();this.times=times.clone();this.minimum=minimum;
        }
        public int size(){return ids.length;}
        public int id(int index){return ids[index];}
        public long time(int index){return times[index];}
        public boolean same(Snapshot other){return other!=null&&style==other.style
            &&Arrays.equals(ids,other.ids)&&Arrays.equals(times,other.times);}
        public void write(Bundle extras){
            extras.putInt(EXTRA_VERSION,VERSION);extras.putInt(EXTRA_STYLE,style);
            extras.putIntArray(EXTRA_IDS,ids.clone());extras.putLongArray(EXTRA_TIMES,times.clone());
            extras.putLong(EXTRA_TIME,minimum);
        }
    }

    public static Snapshot from(int style,Map<Integer,Long> alarms){
        if(alarms==null||alarms.isEmpty()||alarms.size()>64)return null;
        for(Map.Entry<Integer,Long> entry:alarms.entrySet())if(entry.getKey()==null||entry.getValue()==null)return null;
        java.util.TreeMap<Integer,Long> sorted=new java.util.TreeMap<>(alarms);
        int[] ids=new int[sorted.size()];long[] times=new long[sorted.size()];int index=0;long minimum=Long.MAX_VALUE;
        for(Map.Entry<Integer,Long> entry:sorted.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)return null;
            ids[index]=entry.getKey();times[index++]=entry.getValue();minimum=Math.min(minimum,entry.getValue());
        }
        Bundle extras=new Bundle();new Snapshot(style,ids,times,minimum).write(extras);return read(extras,style);
    }

    public static Snapshot read(Bundle extras,int style){
        if (extras == null || (style != 1 && style != 2)) return null;
        try {
            if (extras.getInt(EXTRA_VERSION, 0) != VERSION || extras.getInt(EXTRA_STYLE, 0) != style) return null;
            int[] ids = extras.getIntArray(EXTRA_IDS);
            long[] times = extras.getLongArray(EXTRA_TIMES);
            if (ids == null || times == null || ids.length == 0 || ids.length > 64
                    || ids.length != times.length || (style == 1 && ids.length != 1)) return null;
            long minimum = Long.MAX_VALUE;
            int previous = 0;
            for (int index = 0; index < ids.length; index++) {
                if (ids[index] <= previous || times[index] < 100_000_000_000L) return null;
                previous = ids[index];
                minimum = Math.min(minimum, times[index]);
            }
            return extras.getLong(EXTRA_TIME, 0) == minimum ? new Snapshot(style,ids,times,minimum) : null;
        } catch (RuntimeException malformed) { return null; }
    }
}
