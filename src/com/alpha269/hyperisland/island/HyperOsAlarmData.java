package com.alpha269.hyperisland.island;

import android.os.Bundle;

/** Private Clock notification metadata; all times come from the source alarm rows. */
public final class HyperOsAlarmData {
    private static final String PREFIX = "com.alpha269.hyperisland.alarm.";
    public static final int VERSION = 1;
    public static final String EXTRA_VERSION = PREFIX + "version";
    public static final String EXTRA_STYLE = PREFIX + "style";
    public static final String EXTRA_IDS = PREFIX + "ids";
    public static final String EXTRA_TIMES = PREFIX + "times";
    public static final String EXTRA_TIME = PREFIX + "time";

    private HyperOsAlarmData() {}

    /** Reject incomplete or inconsistent snapshots instead of inventing a snooze time. */
    public static long exactTime(Bundle extras, int style) {
        if (extras == null || (style != 1 && style != 2)) return 0;
        try {
            if (extras.getInt(EXTRA_VERSION, 0) != VERSION || extras.getInt(EXTRA_STYLE, 0) != style) return 0;
            int[] ids = extras.getIntArray(EXTRA_IDS);
            long[] times = extras.getLongArray(EXTRA_TIMES);
            if (ids == null || times == null || ids.length == 0 || ids.length > 64
                    || ids.length != times.length || (style == 1 && ids.length != 1)) return 0;
            long minimum = Long.MAX_VALUE;
            int previous = 0;
            for (int index = 0; index < ids.length; index++) {
                if (ids[index] <= previous || times[index] < 100_000_000_000L) return 0;
                previous = ids[index];
                minimum = Math.min(minimum, times[index]);
            }
            return extras.getLong(EXTRA_TIME, 0) == minimum ? minimum : 0;
        } catch (RuntimeException malformed) { return 0; }
    }
}
