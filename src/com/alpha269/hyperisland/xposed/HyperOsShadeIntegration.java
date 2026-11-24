package com.alpha269.hyperisland.xposed;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import com.alpha269.hyperisland.island.HyperIslandController;
import com.alpha269.hyperisland.island.HyperOsCardFactory;
import com.alpha269.hyperisland.island.IslandItem;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces One UI Now Bar live cards and notification-panel media player with native HyperOS cards,
 * applies dark frosted shade theming, hides duplicated Now Bar / synthetic media rows, and applies
 * lightweight shade performance guards (ColorOS dynamicisland-lowblur integration parity).
 */
public final class HyperOsShadeIntegration {
    private static final String TAG = "HyperBarShade";
    private static final String ROW_PKG = "com.android.systemui.statusbar.notification.row.";
    private static final String BINDER_COMPANION = ROW_PKG + "NotificationRowContentBinderImpl$Companion";
    private static final String BINDER_TASK = ROW_PKG + "NotificationRowContentBinderImpl$AsyncInflationTask";
    private static final String CONTENT_VIEW = ROW_PKG + "NotificationContentView";
    private static final String VIEW_WRAPPER = ROW_PKG + "wrapper.NotificationViewWrapper";
    private static final String ANV = ROW_PKG + "ActivatableNotificationView";
    private static final String NBV = ROW_PKG + "NotificationBackgroundView";
    private static final String COLOR_PICKER = "noticolorpicker.NotificationColorPicker";
    private static final String SHADE_BUILDER = "com.android.systemui.statusbar.notification.collection.ShadeListBuilder";
    private static final String NOTIF_ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String MEDIA_PANEL = "com.android.systemui.media.SecMediaControlPanel";
    private static final String QS_PICKER = "com.android.systemui.qs.SecQSPanelResourcePicker";
    private static final String[] CONTENT_LIMITS = {"mSmallHeight", "mMinContractedHeight", "mHeadsUpHeight"};
    private static final String[] MEDIA_LIMITS = {"mSmallHeight", "mMinContractedHeight", "mNotificationMaxHeight", "mHeadsUpHeight"};
    private static final float[] DARK_CURVE = {0.4f, -23.0f, 5.0f, 250.0f, 21.6f, 141.5f};
    private static final int DARK_SOLID = 0xf0202022;
    private static final long DUPLICATE_GRACE_MS = 2500L;
    private static final String LIVEBRIDGE_PKG = "com.locnall.KimGiSa";
    private static final String LIVEBRIDGE_CHANNEL = "livebridge_";
    private static final String FACEWIDGET_OWNER = "com.samsung.android.app.facewidget.FaceWidgetNotificationController";
    private static final int MAX_TILE_FRAMES = 32;
    private static final int MAX_TILE_FRAME_PX = 512;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, LiveRowState> EXPOSED = new WeakHashMap<>();
    private static final Map<View, MediaBinding> MEDIA_PLAYERS = new WeakHashMap<>();
    private static final Map<Object, WeakReference<View>> MEDIA_ROWS = new WeakHashMap<>();
    private static final Set<Method> HOOKED_MEDIA_METHODS = new HashSet<>();
    private static final ThreadLocal<Integer> MEDIA_MEASURE_HEIGHT = new ThreadLocal<>();
    private static final ThreadLocal<Object> MEDIA_BLUR_NOTIF = new ThreadLocal<>();
    private static final ThreadLocal<View> THEME_CURRENT_ROW = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ViewTreeObserver>> LAYOUT_DISPATCH = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<Class<?>, Boolean> FACEWIDGET_DECORATORS = new ConcurrentHashMap<>();
    private static final Map<String, Drawable.ConstantState> TILE_FRAMES = new LinkedHashMap<String, Drawable.ConstantState>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable.ConstantState> eldest) {
            return size() > MAX_TILE_FRAMES;
        }
    };
    private static final Map<View, Long> THEME_REPAIRED = new WeakHashMap<>();
    private static final Map<View, Boolean> BLUR_CLEARED = new WeakHashMap<>();
    private static final Set<String> DUPLICATE_COPIES = new HashSet<>();
    private static final Map<String, Long> DUPLICATE_HIDDEN = new HashMap<>();
    private static final Map<String, String> APP_LABELS = new HashMap<>();
    private static WeakReference<Object> shadeFilter = new WeakReference<>(null);
    private static Class<?> anvClass;
    private static Class<?> entryClass;
    private static boolean installed;

    private HyperOsShadeIntegration() {}

    public static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        installShadePerf(loader);
        installShadeLayoutCallbacks();
        installHideDuplicated(loader);
        installShadeCardTheme(loader);
        installShadeLiveCards(loader);
        installShadeMediaPlayer(loader);
        XposedBridge.log(TAG + ": installed");
    }

    // -------------------------------------------------------------------------
    // 1. ShadePerf + ShadeLayoutCallbacks (ColorOS parity)
    // -------------------------------------------------------------------------
    private static void installShadePerf(ClassLoader loader) {
        try {
            Method setFps = View.class.getDeclaredMethod("setRequestedFrameRate", float.class);
            XposedBridge.hookMethod(setFps, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        float next = ((Float) param.args[0]).floatValue();
                        if (Float.compare(next, ((View) param.thisObject).getRequestedFrameRate()) == 0) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        // Keep GPU caches across shade close (ColorOS ShadePerf.keepGpuCachesOnShadeClose parity)
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal", false, null);
            XposedBridge.hookAllMethods(wmg, "trimMemory", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Integer && ((Integer) param.args[0]).intValue() == 20) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}
        // Drop Samsung QS debug stack traces on every shade animation frame
        hook(loader, "com.android.systemui.qs.animator.QSAnimView", "nowWithDebugCallers", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(""); }
        });
        hook(loader, "com.android.systemui.log.QuickPanelLoggerHelper$PanelStateLogger", "log", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(null); }
        });
        try {
            XposedBridge.hookAllMethods(View.class, "semSetBlurInfo", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 1) return;
                        View v = (View) param.thisObject;
                        if (!"com.android.systemui.blur.SecQSNewBlurView".equals(v.getClass().getName())) return;
                        if (param.args[0] != null) BLUR_CLEARED.remove(v);
                        else if (BLUR_CLEARED.put(v, Boolean.TRUE) != null) param.setResult(null);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        // Keep small .webp QS tile animation frames warm across shade openings
        try {
            Class<?> resImpl = Class.forName("android.content.res.ResourcesImpl", false, null);
            XposedBridge.hookAllMethods(resImpl, "loadDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof BitmapDrawable)) return;
                        TypedValue tv = null;
                        for (Object a : param.args) if (a instanceof TypedValue) tv = (TypedValue) a;
                        if (tv == null || tv.string == null) return;
                        String file = tv.string.toString();
                        if (!file.endsWith(".webp")) return;
                        BitmapDrawable bd = (BitmapDrawable) result;
                        if (bd.getBitmap() == null || bd.getBitmap().getWidth() > MAX_TILE_FRAME_PX || bd.getBitmap().getHeight() > MAX_TILE_FRAME_PX) return;
                        Drawable.ConstantState cs = bd.getConstantState();
                        if (cs == null) return;
                        synchronized (TILE_FRAMES) { TILE_FRAMES.put(file + "#" + tv.density, cs); }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void installShadeLayoutCallbacks() {
        try {
            XposedBridge.hookAllMethods(ViewTreeObserver.class, "dispatchOnGlobalLayout", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewTreeObserver) enterLayoutDispatch((ViewTreeObserver) param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    leaveLayoutDispatch();
                }
            });
            XposedBridge.hookAllMethods(ViewTreeObserver.class, "removeOnGlobalLayoutListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || !(param.args[0] instanceof ViewTreeObserver.OnGlobalLayoutListener)) return;
                    ViewTreeObserver.OnGlobalLayoutListener listener = (ViewTreeObserver.OnGlobalLayoutListener) param.args[0];
                    if (isFaceWidgetDecorator(listener.getClass())
                            && removeFromActiveLayoutDispatch((ViewTreeObserver) param.thisObject, listener)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    public static void enterLayoutDispatch(ViewTreeObserver observer) {
        LAYOUT_DISPATCH.get().push(observer);
    }

    public static void leaveLayoutDispatch() {
        ArrayDeque<ViewTreeObserver> q = LAYOUT_DISPATCH.get();
        if (!q.isEmpty()) q.pop();
    }

    public static boolean removeFromActiveLayoutDispatch(ViewTreeObserver requested, ViewTreeObserver.OnGlobalLayoutListener listener) {
        ViewTreeObserver active = LAYOUT_DISPATCH.get().peek();
        if (active == null || active == requested || !active.isAlive()) return false;
        active.removeOnGlobalLayoutListener(listener);
        return true;
    }

    private static boolean isFaceWidgetDecorator(Class<?> type) {
        return Boolean.TRUE.equals(FACEWIDGET_DECORATORS.computeIfAbsent(type, cls -> {
            for (Field f : cls.getDeclaredFields()) {
                if (FACEWIDGET_OWNER.equals(f.getType().getName())) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }));
    }

    // -------------------------------------------------------------------------
    // 2. HideDuplicated (hide synthetic MediaOngoingActivity, ongoing summaries,
    //    and LiveBridge duplicated originals — ColorOS HideDuplicated parity)
    // -------------------------------------------------------------------------
    private static void installHideDuplicated(ClassLoader loader) {
        entryClass = find(loader, NOTIF_ENTRY);
        if (entryClass == null) return;
        hook(loader, SHADE_BUILDER, "filterNotifs", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length > 2 && param.args[2] == read(param.thisObject, "mNotifPreGroupFilters")
                            && param.args[0] instanceof Collection) {
                        collectDuplicateSnapshot((Collection<?>) param.args[0]);
                    }
                } catch (Throwable ignored) {}
            }
        });
        hook(loader, SHADE_BUILDER, "applyFilters", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) return;
                    if (shadeFilter.get() == null && param.args.length > 2 && param.args[2] instanceof List
                            && !((List<?>) param.args[2]).isEmpty()) {
                        shadeFilter = new WeakReference<>(((List<?>) param.args[2]).get(0));
                    }
                    if (param.args.length > 0 && shouldHideEntry(param.args[0])) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void collectDuplicateSnapshot(Collection<?> entries) {
        List<StatusBarNotification> snapshot = new ArrayList<>();
        for (Object entry : entries) {
            if (entryClass != null && entryClass.isInstance(entry)) {
                StatusBarNotification sbn = sbnOf(entry);
                if (sbn != null) snapshot.add(sbn);
            }
        }
        refreshDuplicateSnapshot(snapshot);
    }

    public static void refreshDuplicateSnapshot(List<StatusBarNotification> snapshot) {
        List<StatusBarNotification> copies = new ArrayList<>();
        Set<String> active = new HashSet<>();
        for (StatusBarNotification sbn : snapshot) {
            if (sbn == null) continue;
            active.add(sbn.getKey());
            if (isLiveBridgeCopy(sbn)) copies.add(sbn);
        }
        Map<StatusBarNotification, Set<String>> represented = new HashMap<>();
        for (StatusBarNotification copy : copies) represented.put(copy, representedKeys(copy.getNotification()));
        Map<StatusBarNotification, String> directSources = new HashMap<>();
        for (StatusBarNotification copy : copies) {
            for (StatusBarNotification source : snapshot) {
                if (source != null && !isLiveBridge(source) && !isSummary(source.getNotification())
                        && source.getUserId() == copy.getUserId() && copy.getId() == mirrorId(source.getKey())) {
                    directSources.put(copy, source.getKey());
                    break;
                }
            }
        }
        DUPLICATE_COPIES.clear();
        DUPLICATE_HIDDEN.keySet().retainAll(active);
        for (StatusBarNotification source : snapshot) {
            if (source == null || isLiveBridge(source) || isSummary(source.getNotification())) continue;
            for (StatusBarNotification copy : copies) {
                Set<String> keys = represented.get(copy);
                boolean explicit = copy.getUserId() == source.getUserId() && keys != null && keys.contains(source.getKey());
                String direct = directSources.get(copy);
                boolean matched = explicit || (direct != null ? direct.equals(source.getKey()) : matchesMirror(source, copy));
                if (matched) {
                    DUPLICATE_COPIES.add(source.getKey());
                    break;
                }
            }
        }
        for (StatusBarNotification summary : snapshot) {
            if (summary == null || isLiveBridge(summary) || !isSummary(summary.getNotification())) continue;
            boolean any = false, all = true;
            for (StatusBarNotification child : snapshot) {
                if (child != null && !isSummary(child.getNotification()) && !isLiveBridge(child)
                        && child.getUserId() == summary.getUserId()
                        && summary.getPackageName().equals(child.getPackageName())
                        && summary.getGroupKey().equals(child.getGroupKey())) {
                    any = true;
                    all &= DUPLICATE_COPIES.contains(child.getKey());
                }
            }
            if (any && all) DUPLICATE_COPIES.add(summary.getKey());
        }
    }

    public static boolean shouldHideNotification(StatusBarNotification sbn, long nowUptime) {
        if (sbn == null) return false;
        if (HyperIslandController.isSyntheticMediaOngoing(sbn, null)) return true;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if (LIVEBRIDGE_PKG.equals(sbn.getPackageName())) return false;
        if (isSummary(n) && n.extras != null && n.extras.containsKey("android.ongoingActivityNoti.style")) {
            return true;
        }
        String key = sbn.getKey();
        if (DUPLICATE_COPIES.contains(key)) {
            DUPLICATE_HIDDEN.put(key, nowUptime);
            return true;
        }
        Long last = DUPLICATE_HIDDEN.get(key);
        if (last == null) return false;
        long elapsed = nowUptime - last.longValue();
        if (elapsed >= 0 && elapsed < DUPLICATE_GRACE_MS) {
            MAIN.postDelayed(HyperOsShadeIntegration::invalidateShadeList, (DUPLICATE_GRACE_MS - elapsed) + 50L);
            return true;
        }
        DUPLICATE_HIDDEN.remove(key);
        return false;
    }

    private static boolean shouldHideEntry(Object entry) {
        return shouldHideNotification(sbnOf(entry), SystemClock.uptimeMillis());
    }

    private static void invalidateShadeList() {
        Object f = shadeFilter.get();
        if (f != null) call(f, "invalidateList", "hyperbar duplicate copy gone");
    }

    private static boolean isLiveBridge(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return false;
        String ch = sbn.getNotification().getChannelId();
        return LIVEBRIDGE_PKG.equals(sbn.getPackageName()) && ch != null && ch.startsWith(LIVEBRIDGE_CHANNEL);
    }

    private static boolean isLiveBridgeCopy(StatusBarNotification sbn) {
        if (!isLiveBridge(sbn)) return false;
        Notification n = sbn.getNotification();
        String ch = n.getChannelId();
        return (n.extras == null || n.extras.getInt("android.ongoingActivityNoti.style", 0) != 2)
                && (ch == null || !ch.startsWith("livebridge_notification_capsule"));
    }

    private static boolean isSummary(Notification n) {
        return n != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private static int mirrorId(String key) {
        int hash = key.hashCode();
        return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private static Set<String> representedKeys(Notification n) {
        Set<String> keys = new HashSet<>();
        if (n == null) return keys;
        collectIntentKeys(n.deleteIntent, keys);
        if (n.extras != null) {
            Object del = n.extras.get("com.nowbar.ongoing.DELETE_INTENT");
            if (del instanceof PendingIntent) collectIntentKeys((PendingIntent) del, keys);
        }
        if (n.actions != null) {
            for (Notification.Action a : n.actions) if (a != null) collectIntentKeys(a.actionIntent, keys);
        }
        return keys;
    }

    private static void collectIntentKeys(PendingIntent pi, Set<String> keys) {
        if (pi == null || !LIVEBRIDGE_PKG.equals(pi.getCreatorPackage())) return;
        Object raw = call(pi, "getIntent");
        if (!(raw instanceof Intent)) return;
        Intent intent = (Intent) raw;
        if ("com.kakao.taxi.action.CLEAR_NOTIFICATION_CAPSULE_KEYS".equals(intent.getAction())) {
            ArrayList<String> list = intent.getStringArrayListExtra("notification_keys");
            if (list != null) for (String k : list) if (k != null && !k.trim().isEmpty()) keys.add(k.trim());
        }
    }

    private static boolean matchesMirror(StatusBarNotification source, StatusBarNotification copy) {
        if (!isLiveBridgeCopy(copy) || LIVEBRIDGE_PKG.equals(source.getPackageName())) return false;
        if (source.getUserId() != copy.getUserId()) return false;
        Notification sn = source.getNotification(), cn = copy.getNotification();
        if (sn == null || cn == null || isSummary(sn)) return false;
        if (copy.getId() == mirrorId(source.getKey())) return true;
        String st = normalized(sn.extras == null ? null : sn.extras.getCharSequence(Notification.EXTRA_TITLE));
        String ct = normalized(cn.extras == null ? null : cn.extras.getCharSequence(Notification.EXTRA_TITLE));
        boolean sameTitle = !st.isEmpty() && st.equals(ct);
        boolean sameIntent = sn.contentIntent != null && sn.contentIntent.equals(cn.contentIntent);
        if (sameTitle && sameIntent) return true;
        String sub = normalized(cn.extras == null ? null : cn.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (sameTitle && !sub.isEmpty() && sub.equals(normalized(appLabel(source.getPackageName())))) return true;
        if (sameIntent && sn.actions != null && cn.actions != null) {
            for (Notification.Action a : sn.actions) {
                if (a == null || a.actionIntent == null) continue;
                for (Notification.Action b : cn.actions) {
                    if (b != null && a.actionIntent.equals(b.actionIntent)) return true;
                }
            }
        }
        return false;
    }

    private static String normalized(CharSequence cs) {
        return cs == null ? "" : cs.toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String appLabel(String pkg) {
        String cached = APP_LABELS.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            Context app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app != null) {
                PackageManager pm = app.getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            }
        } catch (Throwable ignored) {}
        APP_LABELS.put(pkg, label);
        return label;
    }

    // -------------------------------------------------------------------------
    // 3. ShadeCardTheme (dark frosted blur curve for live & media rows)
    // -------------------------------------------------------------------------
    private static void installShadeCardTheme(ClassLoader loader) {
        anvClass = find(loader, ANV);
        if (anvClass == null) return;
        for (String method : new String[]{"initBlurBackground", "keepLandBlurClipInfo"}) {
            hook(loader, ANV, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) THEME_CURRENT_ROW.set((View) param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    THEME_CURRENT_ROW.remove();
                }
            });
        }
        hook(loader, COLOR_PICKER, "getColorCurvePreset", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object res = param.getResult();
                    View row = THEME_CURRENT_ROW.get();
                    if (res instanceof float[] && row != null && isIslandRow(row)) {
                        float[] out = applyDarkCurve((float[]) res);
                        if (out != null) param.setResult(out);
                    }
                } catch (Throwable ignored) {}
            }
        });
        hook(loader, NBV, "setCustomBackground", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject instanceof View) applyDarkFill((View) param.thisObject);
                } catch (Throwable ignored) {}
            }
        });
        hook(loader, NBV, "setTint", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof View) || param.args.length == 0) return;
                    View row = findAnvRow((View) param.thisObject);
                    if (row != null && isLightMode(row) && isIslandRow(row)) param.args[0] = 0;
                } catch (Throwable ignored) {}
            }
        });
    }

    public static float[] applyDarkCurve(float[] preset) {
        if (preset == null || preset.length < 7 || preset[5] < 100f) return null;
        float[] out = new float[7];
        out[0] = preset[0];
        System.arraycopy(DARK_CURVE, 0, out, 1, 6);
        return out;
    }

    private static boolean isIslandRow(View row) {
        return isLiveCardRow(row) || isActiveMediaRow(row);
    }

    private static boolean isLightMode(View v) {
        return (v.getResources().getConfiguration().uiMode & 48) != 32;
    }

    private static View findAnvRow(View v) {
        if (anvClass == null || v == null) return null;
        if (anvClass.isInstance(v)) return v;
        int depth = 0;
        for (ViewParent p = v.getParent(); depth < 4 && p instanceof View; p = p.getParent(), depth++) {
            if (anvClass.isInstance(p)) return (View) p;
        }
        return null;
    }

    private static void applyDarkFill(View nbv) {
        View row = findAnvRow(nbv);
        if (row == null || !isLightMode(row) || !isIslandRow(row)) return;
        Object bg = read(nbv, "mBackground");
        if (!(bg instanceof LayerDrawable) || ((LayerDrawable) bg).getNumberOfLayers() == 0) return;
        Drawable d0 = ((LayerDrawable) bg).getDrawable(0);
        if (!(d0 instanceof GradientDrawable)) return;
        GradientDrawable gd = (GradientDrawable) d0.mutate();
        int[] colors = gd.getColors();
        if (colors == null) return;
        boolean anyVisible = false;
        for (int c : colors) if ((c >>> 24) != 0) { anyVisible = true; break; }
        if (!anyVisible) return;
        gd.setColors(new int[]{DARK_SOLID, DARK_SOLID});
        nbv.invalidate();
    }

    private static void refreshRowTheme(final View row) {
        if (row == null) return;
        row.post(() -> {
            try {
                call(row, "initBackground");
                call(row, "updateBackground");
                row.invalidate();
            } catch (Throwable ignored) {}
        });
    }

    private static void ensureRowBlur(View row) {
        if (row == null || !row.isAttachedToWindow()) return;
        Object nbv = read(row, "mBackgroundNormal");
        if (!(nbv instanceof View)) return;
        Drawable bg = ((View) nbv).getBackground();
        if (bg != null && bg.getClass().getName().endsWith("BackgroundBlurDrawable")) return;
        if (bool(row, "mOnKeyguard", false)) return;
        for (String m : new String[]{"isHeadsUpState", "isPinned", "isHeadsUpAnimatingAway"}) {
            if (Boolean.TRUE.equals(call(row, m))) return;
        }
        if (Boolean.TRUE.equals(call(row, "isBlurBackground"))) {
            long now = SystemClock.uptimeMillis();
            Long last = THEME_REPAIRED.get(row);
            if (last == null || now - last.longValue() >= 1000L) {
                THEME_REPAIRED.put(row, now);
                refreshRowTheme(row);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. ShadeLiveCards (replaces One UI Now Bar ongoing rows in notification panel)
    // -------------------------------------------------------------------------
    private static void installShadeLiveCards(ClassLoader loader) {
        try {
            int apply = hookCount(loader, BINDER_COMPANION, "access$apply", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        LiveRowState s = liveRowState(privateLayoutFromArgs(param.args));
                        if (s != null) s.exposeOriginals();
                    } catch (Throwable ignored) {}
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        LiveRowState s = exposedState(privateLayoutFromArgs(param.args));
                        if (s != null) s.hideOriginals();
                    } catch (Throwable ignored) {}
                }
            });
            if (apply == 0) return;
            int measure = hookCount(loader, CONTENT_VIEW, "onMeasure", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        LiveRowState s = liveRowState(param.thisObject);
                        if (s != null) s.beforeMeasure(View.MeasureSpec.getSize(((Integer) param.args[0]).intValue()));
                    } catch (Throwable ignored) {}
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        LiveRowState s = liveRowState(param.thisObject);
                        if (s != null) s.afterMeasure();
                    } catch (Throwable ignored) {}
                }
            });
            int minHeight = hookCount(loader, CONTENT_VIEW, "getMinHeight", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        LiveRowState s = liveRowState(param.thisObject);
                        if (s == null || param.hasThrowable()) return;
                        int h = s.cardView.getHeight() > 0 ? s.cardView.getHeight() : s.cardView.desiredHeight;
                        if (h > 0) param.setResult(h);
                    } catch (Throwable ignored) {}
                }
            });
            if (measure == 0 || minHeight == 0) return;
            hook(loader, VIEW_WRAPPER, "needsInversion", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (read(param.thisObject, "mView") instanceof ShadeCardView) param.setResult(Boolean.FALSE);
                    } catch (Throwable ignored) {}
                }
            });
            hook(loader, BINDER_TASK, "onAsyncInflationFinished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!param.hasThrowable()) bindLiveRow(read(param.thisObject, "row"));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " live cards failed: " + failure.getClass().getSimpleName());
        }
    }

    private static IslandItem shadeItem(String key) {
        HyperIslandController c = HyperIslandController.get();
        return c == null ? null : c.shadeItemForKey(key);
    }

    private static boolean isLiveCardRow(View row) {
        Object layout = read(row, "mPrivateLayout");
        return layout != null && (liveRowState(layout) != null || EXPOSED.containsKey(layout));
    }

    private static LiveRowState liveRowState(Object layout) {
        Object child = read(layout, "mContractedChild");
        return child instanceof ShadeCardView ? ((ShadeCardView) child).state : null;
    }

    private static LiveRowState exposedState(Object layout) {
        return layout == null ? null : EXPOSED.get(layout);
    }

    private static Object privateLayoutFromArgs(Object[] args) {
        if (args == null) return null;
        if (args.length > 8 && args[8] != null) {
            Object layout = read(args[8], "mPrivateLayout");
            if (layout != null) return layout;
        }
        for (Object arg : args) {
            if (arg == null) continue;
            if (arg.getClass().getName().endsWith("ExpandableNotificationRow")) {
                Object layout = read(arg, "mPrivateLayout");
                if (layout != null) return layout;
            }
        }
        return null;
    }

    private static void bindLiveRow(Object rowObj) {
        if (!(rowObj instanceof View)) return;
        OneUiHooks.syncSnapshotIfNeeded();
        View row = (View) rowObj;
        Object adapter = read(row, "mEntryAdapter");
        Object rawKey = call(adapter, "getKey");
        if (!(rawKey instanceof String)) return;
        String key = (String) rawKey;
        IslandItem item = shadeItem(key);
        if (item == null) return;
        Object layoutObj = read(row, "mPrivateLayout");
        if (!(layoutObj instanceof ViewGroup)) return;
        ViewGroup layout = (ViewGroup) layoutObj;
        LiveRowState previous = liveRowState(layout);
        if (previous != null) previous.restore();
        LiveRowState state = null;
        try {
            state = new LiveRowState(row, layout, key, item);
            state.apply();
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " bind failed: " + failure.getClass().getSimpleName());
            if (state != null) state.restore();
        }
    }

    private static final class LiveRowState {
        final View row;
        final ViewGroup layout;
        final View contracted, expanded, headsUp;
        final ShadeCardView cardView, headsUpCardView;
        final Method setContracted, setExpanded, setHeadsUp, updateExpandButtons, selectLayout;
        final boolean expandable, rowExpandable;
        final ArrayDeque<int[]> measureLimits = new ArrayDeque<>();
        boolean restored, restorePosted;

        LiveRowState(View row, ViewGroup layout, String key, IslandItem item) throws Throwable {
            this.row = row;
            this.layout = layout;
            this.contracted = (View) read(layout, "mContractedChild");
            this.expanded = (View) read(layout, "mExpandedChild");
            this.headsUp = (View) read(layout, "mHeadsUpChild");
            this.setContracted = requiredMethod(layout, "setContractedChild", 1);
            this.setExpanded = requiredMethod(layout, "setExpandedChild", 1);
            this.setHeadsUp = requiredMethod(layout, "setHeadsUpChild", 1);
            this.updateExpandButtons = requiredMethod(layout, "updateExpandButtons", 1);
            this.selectLayout = requiredMethod(layout, "selectLayout", 2);
            for (String f : CONTENT_LIMITS) if (findField(layout.getClass(), f) == null) throw new NoSuchFieldException(f);
            if (findField(row.getClass(), "mExpandable") == null) throw new NoSuchFieldException("mExpandable");
            this.expandable = bool(layout, "mExpandable", false);
            this.rowExpandable = bool(row, "mExpandable", false);
            this.cardView = new ShadeCardView(row.getContext(), this, key, item);
            this.headsUpCardView = headsUp == null ? null : new ShadeCardView(row.getContext(), this, key, item);
            int width = layout.getWidth() - layout.getPaddingLeft() - layout.getPaddingRight();
            if (width <= 0) width = Math.max(1, row.getResources().getDisplayMetrics().widthPixels - Math.round(28 * row.getResources().getDisplayMetrics().density));
            this.cardView.measureForWidth(width);
        }

        void apply() throws Throwable {
            setContracted.invoke(layout, cardView);
            setExpanded.invoke(layout, new Object[]{null});
            if (headsUpCardView != null) setHeadsUp.invoke(layout, headsUpCardView);
            updateExpandButtons.invoke(layout, Boolean.FALSE);
            if (!write(row, "mExpandable", Boolean.FALSE)) throw new IllegalStateException("row mExpandable");
            selectLayout.invoke(layout, Boolean.FALSE, Boolean.TRUE);
            layout.requestLayout();
            call(row, "notifyHeightChanged", "hyperbar live card", Boolean.FALSE);
            refreshRowTheme(row);
        }

        void exposeOriginals() {
            if (restored) return;
            EXPOSED.put(layout, this);
            write(layout, "mContractedChild", contracted);
            write(layout, "mExpandedChild", expanded);
            if (headsUpCardView != null) write(layout, "mHeadsUpChild", headsUp);
        }

        void hideOriginals() {
            EXPOSED.remove(layout);
            if (restored) return;
            if (read(layout, "mContractedChild") == contracted) write(layout, "mContractedChild", cardView);
            if (read(layout, "mExpandedChild") == expanded) write(layout, "mExpandedChild", null);
            if (headsUpCardView != null && read(layout, "mHeadsUpChild") == headsUp) write(layout, "mHeadsUpChild", headsUpCardView);
        }

        void beforeMeasure(int width) {
            int[] saved = new int[CONTENT_LIMITS.length];
            try {
                int w = Math.max(1, width - layout.getPaddingLeft() - layout.getPaddingRight());
                int h = cardView.measureForWidth(w);
                for (int i = 0; i < CONTENT_LIMITS.length; i++) saved[i] = integer(layout, CONTENT_LIMITS[i], 0);
                measureLimits.push(saved);
                for (String name : CONTENT_LIMITS) {
                    if (!write(layout, name, h)) throw new IllegalStateException(name);
                }
            } catch (Throwable failure) {
                afterMeasure();
                fail(failure);
            }
        }

        void afterMeasure() {
            if (measureLimits.isEmpty()) return;
            int[] saved = measureLimits.pop();
            for (int i = 0; i < CONTENT_LIMITS.length; i++) write(layout, CONTENT_LIMITS[i], saved[i]);
        }

        void fail(Throwable failure) {
            if (restored || restorePosted) return;
            restorePosted = true;
            row.post(this::restore);
        }

        void restore() {
            if (restored) return;
            restored = true;
            while (!measureLimits.isEmpty()) afterMeasure();
            cardView.release();
            if (headsUpCardView != null) headsUpCardView.release();
            restoreChild(setContracted, "mContractedChild", cardView, contracted);
            restoreChild(setExpanded, "mExpandedChild", null, expanded);
            if (headsUpCardView != null) restoreChild(setHeadsUp, "mHeadsUpChild", headsUpCardView, headsUp);
            write(row, "mExpandable", rowExpandable);
            try {
                updateExpandButtons.invoke(layout, expandable);
                selectLayout.invoke(layout, Boolean.FALSE, Boolean.TRUE);
                layout.requestLayout();
                call(row, "notifyHeightChanged", "hyperbar live card restore", Boolean.FALSE);
                refreshRowTheme(row);
            } catch (Throwable ignored) {}
        }

        private void restoreChild(Method setter, String field, View replacement, View original) {
            try {
                Object current = read(layout, field);
                if (current == replacement || (current == original && original != null && original.getParent() != layout)) {
                    setter.invoke(layout, original);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Hosts a real HyperOsCardFactory.Card inside a One UI notification row. */
    public static final class ShadeCardView extends FrameLayout {
        final LiveRowState state;
        final String key;
        IslandItem current;
        HyperOsCardFactory.Card card;
        int cardWidth;
        int desiredHeight;
        float downX, downY;
        private final Runnable tick = this::onTick;
        private final Runnable goneCheck = this::checkGone;

        public static ShadeCardView createForTest(Context context, String key, IslandItem item) {
            return new ShadeCardView(context, null, key, item);
        }

        public int desiredHeightForTest() {
            return desiredHeight;
        }

        ShadeCardView(Context context, LiveRowState state, String key, IslandItem item) {
            super(context);
            this.state = state;
            this.key = key;
            this.current = item;
            setClipChildren(false);
            setClipToPadding(false);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            setOnClickListener(v -> openItem());
            setOnLongClickListener(v -> forwardLongClick());
        }

        private void openItem() {
            IslandItem latest = shadeItem(key);
            if (latest == null) latest = current;
            if (latest == null || latest.torch) return;
            HyperIslandController c = HyperIslandController.get();
            if (c != null) c.onOpenItem(latest, null);
            collapsePanels(getContext());
        }

        private boolean forwardLongClick() {
            if (state == null || state.restored) return false;
            try {
                int[] here = new int[2], there = new int[2];
                getLocationOnScreen(here);
                state.row.getLocationOnScreen(there);
                Method m = requiredMethod(state.row, "doLongClickCallback", 2);
                m.invoke(state.row, Math.round(downX + here[0] - there[0]), Math.round(downY + here[1] - there[1]));
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void ensureCard(int width) {
            int w = Math.max(1, width);
            IslandItem latest = shadeItem(key);
            if (latest == null) {
                if (state != null) {
                    removeCallbacks(goneCheck);
                    postDelayed(goneCheck, 700L);
                }
            } else {
                removeCallbacks(goneCheck);
                current = latest;
            }
            if (current == null) return;
            if (card == null || Math.abs(w - cardWidth) > 2) {
                if (card != null) { removeView(card.view); card.release(); card = null; }
                HyperIslandController c = HyperIslandController.get();
                card = HyperOsCardFactory.create(getContext(), c, current, w);
                if (card.failure != null) {
                    card.release();
                    card = null;
                    if (state != null) state.fail(new IllegalStateException(cardWidth + " card failure"));
                    return;
                }
                cardWidth = w;
                card.view.setOnClickListener(v -> openItem());
                addView(card.view, new LayoutParams(w, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            } else {
                card.update(current, SystemClock.elapsedRealtime());
            }
            desiredHeight = Math.max(1, card.height());
        }

        int measureForWidth(int width) {
            ensureCard(width);
            return Math.max(1, desiredHeight);
        }

        private void checkGone() {
            if (state != null && !state.restored && isAttachedToWindow() && shadeItem(key) == null) {
                state.fail(new IllegalStateException("live activity gone: " + key));
            }
        }

        private void onTick() {
            if ((state != null && state.restored) || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) return;
            IslandItem latest = shadeItem(key);
            if (latest == null) {
                if (state != null) { removeCallbacks(goneCheck); postDelayed(goneCheck, 700L); }
                return;
            }
            current = latest;
            if (card != null) {
                int oldH = card.height();
                card.update(current, SystemClock.elapsedRealtime());
                int newH = card.height();
                if (newH > 0 && newH != oldH) {
                    desiredHeight = newH;
                    requestLayout();
                }
                if (state != null) ensureRowBlur(state.row);
            }
            scheduleTick();
        }

        private void scheduleTick() {
            removeCallbacks(tick);
            if ((state != null && state.restored) || current == null || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) return;
            if (card != null && card.needsFrames()) {
                postDelayed(tick, current.hasChronometer() ? 500L : 250L);
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = Math.max(1, MeasureSpec.getSize(widthSpec));
            ensureCard(w);
            int h = Math.max(1, desiredHeight);
            if (card != null) {
                card.view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, resolveSize(h, heightSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (card != null) {
                card.view.layout(0, 0, Math.min(r - l, cardWidth), Math.min(b - t, Math.max(1, desiredHeight)));
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (h != oldh && state != null && !state.restored) {
                post(() -> {
                    if (!state.restored) call(state.row, "notifyHeightChanged", "hyperbar live card size", Boolean.FALSE);
                });
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (card != null) card.setOwnerVisible(true);
            scheduleTick();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            removeCallbacks(goneCheck);
            if (card != null) card.setOwnerVisible(false);
            super.onDetachedFromWindow();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (card != null) card.setOwnerVisible(visibility == VISIBLE);
            scheduleTick();
        }

        void release() {
            removeCallbacks(tick);
            removeCallbacks(goneCheck);
            if (card != null) {
                removeView(card.view);
                card.release();
                card = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. ShadeMediaPlayer (replaces Samsung ENR media card in notification panel
    //    via dispatchDraw + dispatchTouchEvent + onMeasure — exact ColorOS
    //    TransitionLayout-safe architecture)
    // -------------------------------------------------------------------------
    private static void installShadeMediaPlayer(ClassLoader loader) {
        installMediaBackgroundHooks(loader);
        hook(loader, QS_PICKER, "getMediaPlayerExpandHeight", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Integer h = MEDIA_MEASURE_HEIGHT.get();
                if (h != null) param.setResult(h);
            }
        });
        hook(loader, CONTENT_VIEW, "onMeasure", new XC_MethodHook() {
            final ArrayDeque<MediaMeasure> stack = new ArrayDeque<>();
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    ViewGroup layout = (ViewGroup) param.thisObject;
                    MediaMeasure m = new MediaMeasure(layout);
                    stack.push(m);
                    for (Map.Entry<View, MediaBinding> entry : MEDIA_PLAYERS.entrySet()) {
                        View root = entry.getKey();
                        if (isDescendant(root, layout)) {
                            IslandItem item = entry.getValue().item(root);
                            entry.getValue().updateRowBackground(root, item != null);
                            if (item != null) {
                                int width = View.MeasureSpec.getSize(((Integer) param.args[0]).intValue());
                                int height = entry.getValue().measureCardHeight(root, item, width);
                                m.apply(height);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!stack.isEmpty()) stack.pop().restore();
            }
        });
        hook(loader, MEDIA_PANEL, "bind", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.hasThrowable()) return;
                    if (!"ENR".equals(String.valueOf(read(param.thisObject, "mType")))) return;
                    Object holder = read(param.thisObject, "mViewHolder");
                    Object root = read(holder, "playerView");
                    if (!(root instanceof ViewGroup)) return;
                    ViewGroup playerView = (ViewGroup) root;
                    if (!MEDIA_PLAYERS.containsKey(playerView)) {
                        hookMediaViewMethod(playerView.getClass(), "dispatchDraw", true);
                        hookMediaViewMethod(playerView.getClass(), "dispatchTouchEvent", false);
                        hookMediaMeasure(playerView.getClass());
                        MEDIA_PLAYERS.put(playerView, new MediaBinding(playerView, param.thisObject));
                    }
                    playerView.invalidate();
                    playerView.requestLayout();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void hookMediaViewMethod(Class<?> cls, String name, final boolean draw) {
        Method method = findMethod(cls, name, 1);
        if (method == null || !HOOKED_MEDIA_METHODS.add(method)) return;
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                MediaBinding b = MEDIA_PLAYERS.get(param.thisObject);
                if (b == null) return;
                View view = (View) param.thisObject;
                try {
                    IslandItem item = b.item(view);
                    b.updateRowBackground(view, item != null);
                    if (item == null) {
                        b.cancel();
                        return;
                    }
                    if (draw) {
                        if (b.draw(view, (Canvas) param.args[0], item)) {
                            param.setResult(null);
                        }
                    } else {
                        if (b.touch(view, (MotionEvent) param.args[0], item)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                } catch (Throwable failure) {
                    b.release();
                    b.updateRowBackground(view, false);
                    MEDIA_PLAYERS.remove(view);
                    view.invalidate();
                }
            }
        });
    }

    private static void hookMediaMeasure(Class<?> cls) {
        Method onMeasure = findMethod(cls, "onMeasure", 2);
        if (onMeasure == null || !HOOKED_MEDIA_METHODS.add(onMeasure)) return;
        XposedBridge.hookMethod(onMeasure, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    MediaBinding b = MEDIA_PLAYERS.get(param.thisObject);
                    if (b == null) return;
                    View v = (View) param.thisObject;
                    IslandItem item = b.item(v);
                    if (item == null) return;
                    int w = View.MeasureSpec.getSize(((Integer) param.args[0]).intValue());
                    int h = b.measureCardHeight(v, item, w);
                    param.args[1] = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void installMediaBackgroundHooks(ClassLoader loader) {
        hook(loader, ANV, "applyGradientBackground", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isActiveMediaRow(param.thisObject) && param.args.length == 3) param.args[2] = Boolean.FALSE;
            }
        });
        hook(loader, ANV, "isMediaNotification", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 1 && isActiveMediaRow(param.args[0])) param.setResult(Boolean.FALSE);
            }
        });
        hook(loader, ANV, "isBlurBackground", new XC_MethodHook() {
            final ArrayDeque<Object[]> stack = new ArrayDeque<>();
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                stack.push(new Object[]{MEDIA_BLUR_NOTIF.get()});
                if (isActiveMediaRow(param.thisObject)) {
                    Object sbn = call(read(param.thisObject, "mEntryAdapter"), "getSbn");
                    MEDIA_BLUR_NOTIF.set(call(sbn, "getNotification"));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (stack.isEmpty()) return;
                Object old = stack.pop()[0];
                if (old == null) MEDIA_BLUR_NOTIF.remove(); else MEDIA_BLUR_NOTIF.set(old);
            }
        });
        try {
            XposedBridge.hookAllMethods(Notification.class, "isColorized", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == MEDIA_BLUR_NOTIF.get()) param.setResult(Boolean.FALSE);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isActiveMediaRow(Object row) {
        if (row == null) return false;
        WeakReference<View> ref = MEDIA_ROWS.get(row);
        View view = ref == null ? null : ref.get();
        MediaBinding binding = view == null ? null : MEDIA_PLAYERS.get(view);
        return binding != null && binding.item(view) != null;
    }

    private static boolean isDescendant(View view, View parent) {
        if (view == null || parent == null) return false;
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private static final class MediaMeasure {
        final Object layout;
        final Integer previous = MEDIA_MEASURE_HEIGHT.get();
        final int[] saved = new int[MEDIA_LIMITS.length];
        final Map<View, Integer> childHeights = new HashMap<>();
        boolean changed;
        MediaMeasure(Object layout) { this.layout = layout; }
        void apply(int height) {
            changed = true;
            MEDIA_MEASURE_HEIGHT.set(height);
            for (int i = 0; i < MEDIA_LIMITS.length; i++) {
                saved[i] = integer(layout, MEDIA_LIMITS[i], height);
                write(layout, MEDIA_LIMITS[i], height);
            }
            for (String f : new String[]{"mExpandedChild", "mContractedChild"}) {
                Object child = read(layout, f);
                if (child instanceof View && ((View) child).getLayoutParams() != null) {
                    View v = (View) child;
                    childHeights.put(v, v.getLayoutParams().height);
                    v.getLayoutParams().height = height;
                }
            }
        }
        void restore() {
            if (changed) {
                for (int i = 0; i < MEDIA_LIMITS.length; i++) write(layout, MEDIA_LIMITS[i], saved[i]);
                for (Map.Entry<View, Integer> e : childHeights.entrySet()) e.getKey().getLayoutParams().height = e.getValue();
            }
            if (previous == null) MEDIA_MEASURE_HEIGHT.remove(); else MEDIA_MEASURE_HEIGHT.set(previous);
        }
    }

    public static int renderMediaCardForTest(Context context, IslandItem item, int width, Canvas canvas) {
        if (context == null || item == null || width <= 0) return 0;
        HyperOsCardFactory.Card card = HyperOsCardFactory.create(context, HyperIslandController.get(), item, width);
        try {
            if (card.failure != null) return 0;
            int h = Math.max(1, card.height());
            card.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            card.view.layout(0, 0, width, h);
            if (canvas != null) card.view.draw(canvas);
            return h;
        } finally {
            card.release();
        }
    }

    private static final class MediaBinding {
        final WeakReference<Object> panel;
        final float slop;
        WeakReference<View> backgroundRow;
        boolean backgroundActive;
        HyperOsCardFactory.Card card;
        int cardWidth;
        int cardHeight;
        float scale = 1f;
        float offsetY;
        float downX, downY;
        boolean moved;
        boolean childConsumed;

        MediaBinding(ViewGroup playerView, Object panel) {
            this.panel = new WeakReference<>(panel);
            this.slop = ViewConfiguration.get(playerView.getContext()).getScaledTouchSlop();
        }

        private HyperOsCardFactory.Card ensureCard(View hostView, IslandItem item, int width) {
            int w = width > 0 ? width : Math.max(1, hostView.getResources().getDisplayMetrics().widthPixels - Math.round(28f * hostView.getResources().getDisplayMetrics().density));
            if (card == null || Math.abs(w - cardWidth) > 2) {
                if (card != null) { card.release(); card = null; }
                HyperOsCardFactory.Card next = HyperOsCardFactory.create(hostView.getContext(), HyperIslandController.get(), item, w);
                if (next.failure != null) { next.release(); return null; }
                card = next;
                cardWidth = w;
            } else {
                card.update(item, SystemClock.elapsedRealtime());
            }
            cardHeight = Math.max(1, card.height());
            card.view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(cardHeight, View.MeasureSpec.EXACTLY));
            card.view.layout(0, 0, w, cardHeight);
            return card;
        }

        int measureCardHeight(View hostView, IslandItem item, int width) {
            HyperOsCardFactory.Card c = ensureCard(hostView, item, width);
            if (c != null && cardHeight > 0) return cardHeight;
            return Math.round(168f * hostView.getResources().getDisplayMetrics().density);
        }

        boolean draw(View view, Canvas canvas, IslandItem item) {
            int vw = view.getWidth(), vh = view.getHeight();
            if (vw <= 0 || vh <= 0 || canvas == null) return false;
            HyperOsCardFactory.Card c = ensureCard(view, item, vw);
            if (c == null) return false;
            scale = Math.min(1f, vh / (float) Math.max(1, cardHeight));
            if (scale <= 0f) return false;
            offsetY = Math.max(0f, (vh - scale * cardHeight) / 2f);
            int save = canvas.save();
            try {
                canvas.translate((vw * (1f - scale)) / 2f, offsetY);
                canvas.scale(scale, scale);
                c.view.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
            if (view.isShown() && view.getWindowVisibility() == View.VISIBLE) {
                View row = backgroundRow == null ? null : backgroundRow.get();
                if (backgroundActive) ensureRowBlur(row);
                view.postInvalidateDelayed(item.playing ? 250L : 500L);
            }
            return true;
        }

        boolean touch(View view, MotionEvent event, IslandItem item) {
            if (event == null) return false;
            HyperOsCardFactory.Card c = ensureCard(view, item, view.getWidth());
            if (c == null) return false;
            float s = scale > 0.01f ? scale : 1f;
            float offsetX = (view.getWidth() * (1f - s)) / 2f;
            MotionEvent mapped = MotionEvent.obtain(event);
            try {
                mapped.setLocation((event.getX() - offsetX) / s, (event.getY() - offsetY) / s);
                int action = mapped.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = mapped.getX();
                    downY = mapped.getY();
                    moved = false;
                    childConsumed = c.view.dispatchTouchEvent(mapped);
                    view.invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!moved && (Math.abs(mapped.getX() - downX) > slop || Math.abs(mapped.getY() - downY) > slop)) {
                        moved = true;
                    }
                    if (childConsumed) {
                        c.view.dispatchTouchEvent(mapped);
                        if (moved && view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    view.invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    boolean handled = childConsumed && c.view.dispatchTouchEvent(mapped);
                    if (!handled && !moved) {
                        HyperIslandController ctrl = HyperIslandController.get();
                        if (ctrl != null) ctrl.onOpenItem(item, null);
                        collapsePanels(view.getContext());
                    }
                    cancel();
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    view.invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    if (childConsumed) c.view.dispatchTouchEvent(mapped);
                    cancel();
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    view.invalidate();
                    return true;
                }
                if (childConsumed) c.view.dispatchTouchEvent(mapped);
                return true;
            } finally {
                mapped.recycle();
            }
        }

        void cancel() {
            moved = false;
            childConsumed = false;
        }

        void release() {
            cancel();
            if (card != null) { card.release(); card = null; }
            cardWidth = 0;
            cardHeight = 0;
        }

        IslandItem item(View view) {
            Context ctx = view.getContext();
            KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
            AccessibilityManager am = ctx.getSystemService(AccessibilityManager.class);
            if ((km != null && km.isKeyguardLocked()) || (am != null && am.isTouchExplorationEnabled())) return null;
            Object p = panel.get();
            if (p == null) return null;
            Object holder = read(p, "mViewHolder");
            Object options = read(holder, "options");
            if (options instanceof View && ((View) options).getVisibility() == View.VISIBLE) return null;
            Object ctrl = read(p, "mController");
            HyperIslandController source = HyperIslandController.get();
            if (!(ctrl instanceof MediaController) || source == null) return null;
            return source.mediaItemForSession(((MediaController) ctrl).getSessionToken());
        }

        void updateRowBackground(View view, final boolean active) {
            View row = null;
            for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
                if (p instanceof View && p.getClass().getName().endsWith("ExpandableNotificationRow")) {
                    row = (View) p;
                    break;
                }
            }
            if (row == null) return;
            View old = backgroundRow == null ? null : backgroundRow.get();
            if (old == row && backgroundActive == active) return;
            backgroundRow = new WeakReference<>(row);
            backgroundActive = active;
            if (active) MEDIA_ROWS.put(row, new WeakReference<>(view)); else MEDIA_ROWS.remove(row);
            final View target = row;
            Runnable reinit = () -> {
                try {
                    call(target, "initBackground");
                    if (active) call(target, "applyGradientBackground", target.getWidth(), target.getHeight(), Boolean.FALSE);
                    call(target, "updateBackground");
                    target.invalidate();
                } catch (Throwable ignored) {}
            };
            row.post(reinit);
            if (active) row.postDelayed(reinit, 700L);
        }
    }

    private static void collapsePanels(Context context) {
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm != null) sbm.getClass().getMethod("collapsePanels").invoke(sbm);
        } catch (Throwable ignored) {}
    }

    private static StatusBarNotification sbnOf(Object entry) {
        Object sbn = read(entry, "mSbn");
        if (sbn == null) sbn = call(entry, "getSbn");
        return sbn instanceof StatusBarNotification ? (StatusBarNotification) sbn : null;
    }

    private static Class<?> find(ClassLoader loader, String name) {
        try { return Class.forName(name, false, loader); } catch (Throwable ignored) { return null; }
    }

    private static int hookCount(ClassLoader loader, String clsName, String method, XC_MethodHook hook) {
        Class<?> cls = find(loader, clsName);
        if (cls == null) return 0;
        try { return XposedBridge.hookAllMethods(cls, method, hook).size(); } catch (Throwable ignored) { return 0; }
    }

    private static void hook(ClassLoader loader, String clsName, String method, XC_MethodHook hook) {
        hookCount(loader, clsName, method, hook);
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Object read(Object target, String name) {
        if (target == null) return null;
        Field f = findField(target.getClass(), name);
        if (f == null) return null;
        try { return f.get(target); } catch (Throwable ignored) { return null; }
    }

    private static boolean write(Object target, String name, Object value) {
        if (target == null) return false;
        Field f = findField(target.getClass(), name);
        if (f == null) return false;
        try { f.set(target, value); return true; } catch (Throwable ignored) { return false; }
    }

    private static boolean bool(Object target, String name, boolean fallback) {
        Object v = read(target, name);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : fallback;
    }

    private static int integer(Object target, String name, int fallback) {
        Object v = read(target, name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Method requiredMethod(Object target, String name, int paramCount) throws NoSuchMethodException {
        Method m = findMethod(target.getClass(), name, paramCount);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + paramCount);
        return m;
    }

    private static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), name, args.length);
        if (m == null) return null;
        try { return m.invoke(target, args); } catch (Throwable ignored) { return null; }
    }
}
