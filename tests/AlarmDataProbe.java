import android.os.Bundle;
import android.os.Parcel;
import com.alpha269.hyperisland.island.HyperOsAlarmData;

/** Real Android Bundle/Parcel validation, without Clock hooks or alarm dispatch. */
public final class AlarmDataProbe {
    private static int assertions;
    private static final long LATER = 1_800_000_120_000L, EARLIER = 1_800_000_060_000L;
    private static void check(boolean passed) {
        assertions++;
        if (!passed) throw new AssertionError("Alarm metadata assertion " + assertions);
    }
    private static Bundle snapshot(int style, int[] ids, long[] times, long minimum) {
        Bundle extras = new Bundle();
        extras.putInt(HyperOsAlarmData.EXTRA_VERSION, HyperOsAlarmData.VERSION);
        extras.putInt(HyperOsAlarmData.EXTRA_STYLE, style);
        extras.putIntArray(HyperOsAlarmData.EXTRA_IDS, ids);
        extras.putLongArray(HyperOsAlarmData.EXTRA_TIMES, times);
        extras.putLong(HyperOsAlarmData.EXTRA_TIME, minimum);
        return extras;
    }
    public static void main(String[] args) {
        Bundle ringing = snapshot(1, new int[]{7}, new long[]{LATER}, LATER);
        Bundle snoozed = snapshot(2, new int[]{7, 12}, new long[]{LATER, EARLIER}, EARLIER);
        check(HyperOsAlarmData.exactTime(ringing, 1) == LATER);
        check(HyperOsAlarmData.exactTime(snoozed, 2) == EARLIER);
        check(HyperOsAlarmData.exactTime(ringing, 2) == 0);
        check(HyperOsAlarmData.exactTime(snoozed, 1) == 0);
        check(HyperOsAlarmData.exactTime(null, 2) == 0);
        check(HyperOsAlarmData.exactTime(new Bundle(), 2) == 0);
        check(HyperOsAlarmData.exactTime(ringing, 3) == 0);
        for (String key : new String[]{HyperOsAlarmData.EXTRA_VERSION, HyperOsAlarmData.EXTRA_STYLE,
                HyperOsAlarmData.EXTRA_IDS, HyperOsAlarmData.EXTRA_TIMES, HyperOsAlarmData.EXTRA_TIME}) {
            Bundle incomplete = new Bundle(snoozed); incomplete.remove(key);
            check(HyperOsAlarmData.exactTime(incomplete, 2) == 0);
        }
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{12, 7}, new long[]{LATER, EARLIER}, EARLIER), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{7, 7}, new long[]{LATER, EARLIER}, EARLIER), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{0}, new long[]{EARLIER}, EARLIER), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{7}, new long[]{99}, 99), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{7, 12}, new long[]{EARLIER}, EARLIER), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{}, new long[]{}, EARLIER), 2) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(1, new int[]{7, 12}, new long[]{LATER, EARLIER}, EARLIER), 1) == 0);
        check(HyperOsAlarmData.exactTime(snapshot(2, new int[]{7, 12}, new long[]{LATER, EARLIER}, LATER), 2) == 0);
        Bundle unknown = new Bundle(snoozed); unknown.putInt(HyperOsAlarmData.EXTRA_VERSION, 2);
        check(HyperOsAlarmData.exactTime(unknown, 2) == 0);
        Bundle wrongType = new Bundle(snoozed); wrongType.putString(HyperOsAlarmData.EXTRA_IDS, "invalid");
        check(HyperOsAlarmData.exactTime(wrongType, 2) == 0);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(snoozed); parcel.setDataPosition(0);
            check(HyperOsAlarmData.exactTime(parcel.readBundle(AlarmDataProbe.class.getClassLoader()), 2) == EARLIER);
        } finally { parcel.recycle(); }
        System.out.println("Alarm metadata/Parcel assertions passed: " + assertions);
    }
}
