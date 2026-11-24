package com.alpha269.hyperisland.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * Observes real Android/Samsung battery charging state and drives a single transient HyperOS
 * charging capsule per plug-in session, with calibrated hardware fuel-gauge decimals when fast
 * charging and automatic integer-percentage fallback if fuel-gauge telemetry is unavailable.
 */
public final class HyperOsChargingSource implements AutoCloseable {
    private static final String TAG = "HyperOsChargingSource";
    public static final String KEY = "hyperos:charging";
    public static final long DISPLAY_DURATION_MS = 5500L;
    private static final long DISCONNECT_DEBOUNCE_MS = 600L;
    private static final long DECIMAL_TICK_MS = 250L;
    private static final double MAX_RATE_PERCENT_PER_SEC = 0.05;
    private static final double MAX_EXTRAPOLATION_PERCENT = 0.12;
    private static final double MAX_GAUGE_DRIFT_PERCENT = 2.5;

    private final Context context;
    private final Handler main;
    private final Runnable changed;
    private BatteryManager batteryManager;
    private boolean started, closed, receiverRegistered;
    private boolean plugged, fast, superFast, shownForPlug;
    private boolean decimalsReliable;
    private boolean batteryCurrentInUa;
    private int level = -1;
    private int superFastFromEvent;
    private int hundredths;
    private double fullCapacityUah = -1.0;
    private Boolean detectedHalfUp;
    private Double calibratedOffset;
    private long anchorChargeUah = -1L;
    private long anchorUptimeMs;
    private double anchorFraction;
    private long expiresAtUptime;
    private long shownAtEpoch;
    private long version;

    private final Runnable clearPlugSession = () -> {
        if (closed || plugged) return;
        shownForPlug = false;
        superFastFromEvent = 0;
        fast = false;
        superFast = false;
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.uptimeMillis();
            if (!plugged || now >= expiresAtUptime) {
                expiresAtUptime = 0L;
                notifyChanged();
                return;
            }
            if (fast && decimalsReliable && level >= 0 && level < 100) {
                int nextH = computeHundredths(now);
                if (nextH != hundredths) {
                    hundredths = nextH;
                    version++;
                    notifyChanged();
                }
                main.postDelayed(this, DECIMAL_TICK_MS);
            } else {
                main.postDelayed(this, Math.max(50L, expiresAtUptime - now + 20L));
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                onUnplugged();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action) || Intent.ACTION_POWER_CONNECTED.equals(action)) {
                onBatteryIntent(intent, false);
            }
        }
    };

    public HyperOsChargingSource(Context context, Handler main, Runnable changed) {
        this.context = context;
        this.main = main;
        this.changed = changed;
    }

    public void start() {
        if (started || closed || context == null) return;
        started = true;
        try { batteryManager = context.getSystemService(BatteryManager.class); } catch (Throwable ignored) {}
        fullCapacityUah = readFullCapacityUah(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            Intent sticky = context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            if (sticky != null) onBatteryIntent(sticky, true);
        } catch (Throwable failure) {
            Log.w(TAG, "Charging observation unavailable", failure);
        }
    }

    /**
     * Triggered directly from One UI's BatteryEvent hook on plug-in for zero-delay replacement.
     * speedTier: 0 = normal, 1 = fast (AFC 15W / Fast Wireless / SFC 25W), >= 2 = turbo (SFC 2.0 45W+).
     */
    public void onPluggedEvent(int reportedLevel, int speedTier) {
        if (closed) return;
        main.removeCallbacks(clearPlugSession);
        int prevLevel = level;
        if (reportedLevel >= 0 && reportedLevel <= 100) level = reportedLevel;
        if (speedTier > 0) superFastFromEvent = Math.max(superFastFromEvent, speedTier);
        boolean eventFast = speedTier >= 1;
        boolean eventSuper = speedTier >= 2;
        plugged = true;
        if (eventFast) fast = true;
        if (eventSuper) superFast = true;

        long now = SystemClock.uptimeMillis();
        if (!shownForPlug) {
            shownForPlug = true;
            triggerBurst(true);
        } else if (now < expiresAtUptime) {
            // Fast charging negotiated while the single capsule is still on screen: update in-place only.
            if (prevLevel != level) resetDecimalAnchor(prevLevel >= 0 && level == prevLevel + 1);
            updateActiveInPlace(now);
        }
    }

    public void dismiss() {
        if (closed || expiresAtUptime <= 0L) return;
        expiresAtUptime = 0L;
        main.removeCallbacks(ticker);
        notifyChanged();
    }

    public void onBatteryIntent(Intent intent, boolean initial) {
        if (closed || intent == null) return;
        int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int nextLevel = rawLevel >= 0 && scale > 0 ? Math.max(0, Math.min(100, Math.round(rawLevel * 100f / scale))) : level;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean isCharging = plugType != 0
            && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || status == -1);

        if (!isCharging) {
            level = nextLevel;
            if (initial) {
                plugged = false;
                shownForPlug = false;
                fast = false;
                superFast = false;
                superFastFromEvent = 0;
            } else {
                onUnplugged();
            }
            return;
        }

        main.removeCallbacks(clearPlugSession);
        boolean slow = extraFlag(intent, "slow_charger");
        boolean hvc = extraFlag(intent, "hv_charger") || extraFlag(intent, "hvc");
        int chargerType = intent.getIntExtra("charger_type", 0);
        int online = intent.getIntExtra("online", 0);
        int fastExtra = extraInt(intent, "fast_charger");
        int maxCurrentUa = intent.getIntExtra("max_charging_current", 0);
        int maxVoltageUv = intent.getIntExtra("max_charging_voltage", 0);

        float watts = 0f;
        if (maxCurrentUa > 0) {
            float volts = maxVoltageUv > 0 ? (maxVoltageUv / 1_000_000f) : 5.0f;
            watts = (maxCurrentUa / 1_000_000f) * volts;
        }
        // On Samsung One UI, charger_type 1/2 is standard non-fast TA/USB; only 3/4/5/520 (or wireless online==100 / hv_charger) is fast.
        boolean samsungFast = chargerType == 3 || chargerType == 4 || chargerType == 5 || chargerType == 520
            || (plugType == BatteryManager.BATTERY_PLUGGED_WIRELESS && online == 100);
        boolean samsungSuper = chargerType == 4 || chargerType == 5;
        boolean nextFast = !slow && (hvc || samsungFast || fastExtra >= 1 || superFastFromEvent >= 1 || watts >= 11.5f);
        boolean nextSuper = !slow && (samsungSuper || superFastFromEvent >= 2 || watts >= 33.0f);

        int prevLevel = level;
        plugged = true;
        level = nextLevel;
        fast = nextFast;
        superFast = nextSuper;

        if (prevLevel >= 0 && nextLevel == prevLevel + 1) {
            calibrateOnLevelStep(nextLevel);
            resetDecimalAnchor(true);
        } else if (prevLevel != nextLevel) {
            resetDecimalAnchor(false);
        }

        if (initial) {
            shownForPlug = true;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!shownForPlug) {
            shownForPlug = true;
            triggerBurst(true);
        } else if (now < expiresAtUptime) {
            // Never pop up a second time after dismissal; only refine the currently visible capsule in-place.
            updateActiveInPlace(now);
        }
    }

    private void updateActiveInPlace(long now) {
        if (fast && level >= 0 && level < 100) {
            if (anchorUptimeMs == 0L || !decimalsReliable) resetDecimalAnchor(false);
            if (decimalsReliable) hundredths = computeHundredths(now);
            main.removeCallbacks(ticker);
            main.postDelayed(ticker, decimalsReliable ? DECIMAL_TICK_MS : Math.max(50L, expiresAtUptime - now + 20L));
        }
        version++;
        notifyChanged();
    }

    private void triggerBurst(boolean resetAnchor) {
        long now = SystemClock.uptimeMillis();
        shownAtEpoch = System.currentTimeMillis();
        expiresAtUptime = now + DISPLAY_DURATION_MS;
        double measuredFull = readFullCapacityUah(context);
        if (measuredFull > 0.0) fullCapacityUah = measuredFull;
        if (resetAnchor || anchorUptimeMs == 0L) resetDecimalAnchor(false);
        hundredths = fast && decimalsReliable && level >= 0 && level < 100 ? computeHundredths(now) : 0;
        version++;
        main.removeCallbacks(ticker);
        main.postDelayed(ticker,
            fast && decimalsReliable && level >= 0 && level < 100 ? DECIMAL_TICK_MS : DISPLAY_DURATION_MS + 20L);
        notifyChanged();
    }

    private void onUnplugged() {
        plugged = false;
        anchorChargeUah = -1L;
        anchorUptimeMs = 0L;
        if (expiresAtUptime > 0L) {
            expiresAtUptime = 0L;
            main.removeCallbacks(ticker);
            notifyChanged();
        }
        // Debounce resetting shownForPlug so 50-100ms USB-C PD renegotiation dips never replay the popup.
        main.removeCallbacks(clearPlugSession);
        main.postDelayed(clearPlugSession, DISCONNECT_DEBOUNCE_MS);
    }

    /** Immediately clears plug session state (used when physically unplugged longer than debounce). */
    void resetPlugSessionForTest() {
        main.removeCallbacks(clearPlugSession);
        plugged = false;
        shownForPlug = false;
        fast = false;
        superFast = false;
        superFastFromEvent = 0;
        expiresAtUptime = 0L;
        main.removeCallbacks(ticker);
    }

    private void calibrateOnLevelStep(int steppedLevel) {
        if (fullCapacityUah <= 100_000.0 || steppedLevel <= 0 || steppedLevel >= 100) return;
        long cc = readChargeCounterUah();
        if (cc <= 0L || cc > fullCapacityUah * 1.15) return;
        double raw = (cc * 100.0) / fullCapacityUah;
        double offset = raw - steppedLevel;
        if (offset >= -0.65 && offset <= 0.15) {
            calibratedOffset = offset;
            detectedHalfUp = offset < -0.20;
        }
    }

    private void resetDecimalAnchor(boolean justSteppedUp) {
        anchorUptimeMs = SystemClock.uptimeMillis();
        long cc = readChargeCounterUah();
        anchorChargeUah = cc;
        if (fullCapacityUah <= 100_000.0 || cc <= 0L || cc > fullCapacityUah * 1.15 || level < 0 || level >= 100) {
            decimalsReliable = false;
            anchorFraction = 0.0;
            return;
        }
        double raw = (cc * 100.0) / fullCapacityUah;
        if (Math.abs(raw - level) > MAX_GAUGE_DRIFT_PERCENT) {
            // Uncalibrated or broken kernel charge_counter: fall back cleanly to integer percentage.
            decimalsReliable = false;
            anchorFraction = 0.0;
            return;
        }
        double diff = raw - level;
        if (diff < -0.02) detectedHalfUp = Boolean.TRUE;
        else if (diff >= 0.52) detectedHalfUp = Boolean.FALSE;
        decimalsReliable = true;
        if (justSteppedUp) {
            anchorFraction = 0.0;
        } else {
            anchorFraction = realFraction(level, cc, fullCapacityUah, resolveRoundingOffset());
        }
    }

    private double resolveRoundingOffset() {
        if (calibratedOffset != null) return calibratedOffset;
        boolean halfUp = detectedHalfUp != null ? detectedHalfUp : isHalfUpDefaultDevice();
        return halfUp ? -0.5 : 0.0;
    }

    private static boolean isHalfUpDefaultDevice() {
        try {
            return Build.MANUFACTURER != null && "samsung".equalsIgnoreCase(Build.MANUFACTURER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Computes continuous, monotonic sub-percent [0.00, 0.99] from hardware charge_counter (µAh)
     * and full capacity (µAh) using a single continuous offset (0.0 for floor-rounded fuel gauges,
     * -0.5 for half-up-rounded fuel gauges such as Samsung/Qualcomm PMIC). Returns NaN when
     * telemetry is missing or out of sync with reportedLevel so callers fall back to integer %.
     */
    public static double realFraction(int reportedLevel, long chargeCounterUah, double fullCapacityUah, double roundingOffset) {
        if (reportedLevel < 0 || reportedLevel >= 100 || chargeCounterUah <= 0L || fullCapacityUah <= 100_000.0) {
            return Double.NaN;
        }
        if (chargeCounterUah > fullCapacityUah * 1.15) return Double.NaN;
        double rawPercent = (chargeCounterUah * 100.0) / fullCapacityUah;
        if (Math.abs(rawPercent - reportedLevel) > MAX_GAUGE_DRIFT_PERCENT) return Double.NaN;
        double frac = rawPercent - (reportedLevel + roundingOffset);
        return Math.max(0.0, Math.min(0.99, frac));
    }

    private int computeHundredths(long nowUptime) {
        if (!decimalsReliable || fullCapacityUah <= 100_000.0) return 0;
        double uahPerPercent = Math.max(10_000.0, fullCapacityUah / 100.0);
        long cc = readChargeCounterUah();
        if (cc > 0L && anchorChargeUah > 0L && cc != anchorChargeUah) {
            double liveFrac = realFraction(level, cc, fullCapacityUah, resolveRoundingOffset());
            if (!Double.isNaN(liveFrac)) {
                anchorChargeUah = cc;
                // Keep monotonic forward progress within the same integer percentage level.
                anchorFraction = Math.max(anchorFraction, liveFrac);
                anchorUptimeMs = nowUptime;
            }
        }
        double elapsedSec = Math.max(0.0, (nowUptime - anchorUptimeMs) / 1000.0);
        int currentUa = readChargingCurrentUa();
        double ratePercentPerSec = currentUa > 0 ? Math.min(MAX_RATE_PERCENT_PER_SEC, (currentUa / 3600.0) / uahPerPercent) : 0.0;
        double delta = Math.min(MAX_EXTRAPOLATION_PERCENT, elapsedSec * ratePercentPerSec);
        double frac = Math.max(0.0, Math.min(0.99, anchorFraction + delta));
        return Math.max(0, Math.min(99, (int) Math.floor(frac * 100.0)));
    }

    private static boolean extraFlag(Intent intent, String key) {
        android.os.Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null || !extras.containsKey(key)) return false;
        Object raw = extras.get(key);
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() > 0;
        return false;
    }

    private static int extraInt(Intent intent, String key) {
        android.os.Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null || !extras.containsKey(key)) return 0;
        Object raw = extras.get(key);
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof Boolean) return ((Boolean) raw) ? 1 : 0;
        return 0;
    }

    private static double readFullCapacityUah(Context context) {
        for (String node : new String[]{
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/battery/charge_full_design"
        }) {
            long sysCap = normalizeCapacityUah(readSysfsLong(node));
            if (sysCap > 0L) return sysCap;
        }
        if (context != null) {
            try {
                Class<?> pp = Class.forName("com.android.internal.os.PowerProfile");
                Object profile = pp.getConstructor(Context.class).newInstance(context);
                double mah = ((Number) pp.getMethod("getBatteryCapacity").invoke(profile)).doubleValue();
                if (mah >= 1500.0 && mah <= 12000.0) return mah * 1000.0;
            } catch (Throwable ignored) {}
        }
        return -1.0;
    }

    private static long normalizeCapacityUah(long raw) {
        if (raw >= 1_500_000L && raw <= 12_000_000L) return raw;
        if (raw >= 1_500L && raw <= 12_000L) return raw * 1000L;
        return -1L;
    }

    /** Normalizes current to µA without turning low µA trickle readings (e.g. 12,000 µA = 12 mA) into Amps. */
    private int normalizeCurrentReadingUa(long raw, boolean explicitUa) {
        if (raw == Integer.MIN_VALUE || raw == Long.MIN_VALUE || raw == 0L) return 0;
        long abs = Math.abs(raw);
        if (abs > 25_000_000L) return 0; // Sanity guard against uninitialized HAL values
        if (explicitUa) return (int) abs;
        if (abs >= 15_000L) {
            batteryCurrentInUa = true;
            return (int) abs;
        }
        if (!batteryCurrentInUa && abs <= 8_000L) {
            return (int) (abs * 1000L);
        }
        return (int) abs;
    }

    private static long readSysfsLong(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line != null) return Long.parseLong(line.trim());
        } catch (Throwable ignored) {}
        return -1L;
    }

    private long readChargeCounterUah() {
        long raw = -1L;
        if (batteryManager != null) {
            try {
                int cc = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (cc > 0) raw = cc;
            } catch (Throwable ignored) {}
        }
        if (raw <= 0L) raw = readSysfsLong("/sys/class/power_supply/battery/charge_counter");
        if (raw > 0L && raw <= 12_000L && level >= 2) raw *= 1000L;
        return raw;
    }

    /** Prefers explicit µA sysfs nodes or smoothed BatteryManager average current. */
    private int readChargingCurrentUa() {
        for (String uaNode : new String[]{
            "/sys/class/power_supply/battery/batt_current_ua_avg",
            "/sys/class/power_supply/battery/batt_current_ua_now"
        }) {
            long sysUa = readSysfsLong(uaNode);
            if (sysUa != -1L && sysUa != 0L) {
                int norm = normalizeCurrentReadingUa(sysUa, true);
                if (norm > 0) return norm;
            }
        }
        if (batteryManager != null) {
            try {
                int avg = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                int normAvg = normalizeCurrentReadingUa(avg, false);
                if (normAvg > 0) return normAvg;
            } catch (Throwable ignored) {}
            try {
                int now = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int normNow = normalizeCurrentReadingUa(now, false);
                if (normNow > 0) return normNow;
            } catch (Throwable ignored) {}
        }
        for (String node : new String[]{
            "/sys/class/power_supply/battery/current_avg",
            "/sys/class/power_supply/battery/current_now"
        }) {
            long sys = readSysfsLong(node);
            if (sys != -1L && sys != 0L) {
                int norm = normalizeCurrentReadingUa(sys, false);
                if (norm > 0) return norm;
            }
        }
        return 0;
    }

    public boolean isActive(long nowUptime) {
        return !closed && plugged && level >= 0 && expiresAtUptime > nowUptime;
    }

    public boolean isFast() {
        return fast;
    }

    public boolean hasReliableDecimals() {
        return decimalsReliable;
    }

    public void appendTo(List<IslandItem> out) {
        if (out == null || closed) return;
        long now = SystemClock.uptimeMillis();
        if (!isActive(now)) return;
        IslandItem item = new IslandItem(KEY);
        item.kind = IslandItem.Kind.CHARGING;
        item.packageName = "com.android.systemui";
        item.noExpand = true;
        item.title = fast ? "Fast charging" : "Charging";
        item.overline = superFast ? "Turbo charging" : fast ? "Fast charging" : "Charging";
        item.ringPercent = level;
        item.trailingText = formatPercentage(level, fast && decimalsReliable, hundredths);
        item.trailing = IslandItem.Trailing.TEXT;
        item.postTime = shownAtEpoch;
        item.expiresAtUptime = expiresAtUptime;
        item.contentVersion = version;
        item.pillColor = 0xff000000;
        item.accent = 0xff30d158;
        out.add(item);
    }

    public static String formatPercentage(int level, boolean showDecimal, int hundredths) {
        int clampedLevel = Math.max(0, Math.min(100, level));
        if (!showDecimal || clampedLevel >= 100) return clampedLevel + "%";
        int clampedH = Math.max(0, Math.min(99, hundredths));
        return String.format(Locale.US, "%d.%02d%%", clampedLevel, clampedH);
    }

    private void notifyChanged() {
        if (!closed && changed != null) changed.run();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(ticker);
        main.removeCallbacks(clearPlugSession);
        if (receiverRegistered && context != null) {
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        receiverRegistered = false;
    }
}
