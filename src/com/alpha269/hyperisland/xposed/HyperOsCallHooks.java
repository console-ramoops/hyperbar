package com.alpha269.hyperisland.xposed;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.VideoProfile;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import com.alpha269.hyperisland.island.HyperOsIncomingCalls;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;

/**
 * Hooks SamsungInCallUI 17.6.00.134's actual popup owner (l2.A), without changing its call service.
 * Capabilities dispatch its bound native buttons; an expiring presentation lease gates concealment.
 */
public final class HyperOsCallHooks {
    private static final long POLL_MS = 200;
    private static final String EPOCH = UUID.randomUUID().toString();
    private static final String COMMAND = "com.alpha269.hyperisland.CALL_CAPABILITY." + EPOCH;
    private static final String APP = HyperOsIncomingCalls.APP;
    private static Context context;
    private static Handler main;
    private static boolean installed;
    private static boolean polling;
    private static long sequence;
    private static long lastPacket;
    private static long lastContentVersion = Long.MIN_VALUE;
    private static int lastReadiness = -1;
    private static final Map<Call, Call.Callback> calls = new IdentityHashMap<Call, Call.Callback>();
    private static final Map<Call, String> tokens = new IdentityHashMap<Call, String>();
    private static final Map<View, WeakReference<Object>> nativeBindings = new WeakHashMap<View, WeakReference<Object>>();
    private static Popup popup;
    private static Session session;
    private static Notification recentIncomingNotification;
    private static long recentNotificationAt;

    private HyperOsCallHooks() {}

    /** Called only for the com.samsung.android.incallui process from the module entry. */
    public static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        main = new Handler(Looper.getMainLooper());
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Application) initialize((Application)param.thisObject);
            }
        });
        try {
            final Class<?> service = Class.forName("com.android.incallui.InCallServiceImpl", false, loader);
            int addedHooks = XposedBridge.hookAllMethods(service, "onCallAdded", new XC_MethodHook() {
                @Override protected void afterHookedMethod(final MethodHookParam param) {
                    if (param.thisObject instanceof Context) initialize((Context)param.thisObject);
                    if (param.args.length > 0 && param.args[0] instanceof Call) {
                        final Call call = (Call)param.args[0];
                        main.post(new Runnable() { @Override public void run() { add(call); } });
                    }
                }
            }).size();
            int removedHooks = XposedBridge.hookAllMethods(service, "onCallRemoved", new XC_MethodHook() {
                @Override protected void afterHookedMethod(final MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Call) {
                        final Call call = (Call)param.args[0];
                        main.post(new Runnable() { @Override public void run() { remove(call); } });
                    }
                }
            }).size();
            // Stock DEX l2.A.C() calls WindowManager.addView(u, C), never a SystemUI capsule.
            final Class<?> owner = Class.forName("l2.A", false, loader);
            int popupHooks = XposedBridge.hookAllMethods(owner, "C", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    capturePopup(param.thisObject);
                }
            }).size();
            XC_MethodHook beforeRemoval = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Popup current = popup;
                    if (current != null && current.owner.get() == param.thisObject) {
                        current.restore();
                        popup = null;
                        update();
                    }
                }
            };
            XposedBridge.hookAllMethods(owner, "N", beforeRemoval);
            XposedBridge.hookAllMethods(owner, "onDestroy", beforeRemoval);
            XposedBridge.hookAllMethods(owner, "K", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    capturePopup(param.thisObject);
                }
            });
            int bindings = hookBinding(loader, "U9.b") + hookBinding(loader, "X9.c");
            int launcherHooks = hookScreenLauncher(loader);
            XposedBridge.log("HyperBar incoming hooks: added=" + addedHooks + " removed=" + removedHooks
                    + " popup=" + popupHooks + " bindings=" + bindings + " launcher=" + launcherHooks);
            XposedBridge.hookAllMethods(NotificationManager.class, "notify", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (!(arg instanceof Notification)) continue;
                        final Notification notification = (Notification)arg;
                        if (Notification.CATEGORY_CALL.equals(notification.category)
                                || (notification.extras != null && notification.extras.getInt("android.callType", 0) == 1)) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    recentIncomingNotification = notification;
                                    recentNotificationAt = SystemClock.uptimeMillis();
                                    update();
                                }
                            });
                        }
                    }
                }
            });
        } catch (Throwable failure) {
            // A changed vendor build is fail-open: no popup owner => no concealment.
            XposedBridge.log("HyperOS incoming popup hook unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static int hookScreenLauncher(ClassLoader loader) {
        try {
            final Class<?> launcher = Class.forName("l2.S", false, loader);
            final java.lang.reflect.Method launchPopup = launcher.getDeclaredMethod("s");
            launchPopup.setAccessible(true);
            return XposedBridge.hookAllMethods(launcher, "r", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                    if (((Integer) param.args[0]).intValue() != 1) return;
                    if (!shouldUsePopupInsteadOfFullScreen()) return;
                    try {
                        launchPopup.invoke(param.thisObject);
                        param.setResult(null);
                        XposedBridge.log("HyperBar incoming redirected unlocked full-screen launch to popup");
                    } catch (Throwable failure) {
                        XposedBridge.log("HyperBar incoming popup redirect fallback: " + failure.getClass().getSimpleName());
                    }
                }
            }).size();
        } catch (Throwable missing) {
            XposedBridge.log("HyperBar incoming launcher hook unavailable: " + missing.getClass().getSimpleName());
            return 0;
        }
    }

    private static boolean shouldUsePopupInsteadOfFullScreen() {
        Context app = context;
        if (app == null) return false;
        Call ringing = null;
        int count = 0;
        for (Call call : calls.keySet()) {
            if (call.getState() == Call.STATE_RINGING) {
                ringing = call;
                count++;
            }
        }
        if (count != 1 || ringing == null) return false;
        try {
            Call.Details details = ringing.getDetails();
            if (details != null && VideoProfile.isVideo(details.getVideoState())) return false;
        } catch (Throwable ignored) {}
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) return false;
            KeyguardManager km = app.getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static int hookBinding(ClassLoader loader, String name) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            return XposedBridge.hookAllMethods(type, "A", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { captureBinding(param.thisObject); }
            }).size();
        } catch (Throwable missing) {
            XposedBridge.log("HyperBar incoming binding unavailable: " + name + " " + missing.getClass().getSimpleName());
            return 0;
        }
    }

    private static void captureBinding(Object binding) {
        if (binding == null) return;
        try {
            String name = binding.getClass().getName();
            String[] fields = "U9.b".equals(name) ? new String[]{"u", "v", "w", "x", "y", "A", "B"}
                    : "X9.c".equals(name) ? new String[]{"s", "t", "u", "v", "w"} : null;
            if (fields == null) return;
            Object root = field(binding, "o");
            if (root instanceof View) initialize(((View)root).getContext());
            int captured = 0;
            for (String nameOfField : fields) {
                Object view = field(binding, nameOfField);
                if (view instanceof View) {
                    nativeBindings.put((View)view, new WeakReference<Object>(binding));
                    captured++;
                }
            }
            XposedBridge.log("HyperBar incoming binding captured: " + name + " controls=" + captured
                    + " controller=" + (field(binding, "U9.b".equals(name) ? "C" : "y") != null));
            update();
        } catch (Throwable failure) {
            XposedBridge.log("HyperBar incoming binding pending: " + failure.getClass().getSimpleName());
        }
    }

    private static synchronized void initialize(Context app) {
        if (context != null || app == null || !APP.equals(app.getPackageName())) return;
        Context application = app.getApplicationContext();
        if (application == null) application = app;
        try {
            application.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { command(intent); }
            }, new IntentFilter(COMMAND), null, main, Context.RECEIVER_NOT_EXPORTED);
            application.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    update();
                    emit(true);
                }
            }, new IntentFilter(HyperOsIncomingCalls.QUERY), HyperOsIncomingCalls.PERMISSION,
                    main, Context.RECEIVER_EXPORTED);
            context = application;
        } catch (Throwable failure) {
            XposedBridge.log("HyperOS call bridge unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static void add(final Call call) {
        if (call == null || calls.containsKey(call)) return;
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call current, int state) { update(); }
            @Override public void onDetailsChanged(Call current, Call.Details details) { update(); }
            @Override public void onCallDestroyed(Call current) { remove(current); }
        };
        calls.put(call, callback);
        tokens.put(call, UUID.randomUUID().toString());
        try { call.registerCallback(callback, main); }
        catch (Throwable failure) {
            calls.remove(call);
            tokens.remove(call);
            return;
        }
        update();
        startPoll();
    }

    private static void remove(Call call) {
        Call.Callback callback = calls.remove(call);
        tokens.remove(call);
        if (callback != null) {
            try { call.unregisterCallback(callback); } catch (Throwable ignored) {}
        }
        update();
    }

    private static void capturePopup(Object owner) {
        try {
            View root = (View)field(owner, "u");
            WindowManager.LayoutParams parameters = (WindowManager.LayoutParams)field(owner, "C");
            if (root == null || parameters == null) return;
            initialize(root.getContext());
            Popup previous = popup;
            if (previous != null && previous.root.get() != root) previous.restore();
            if (previous == null || previous.root.get() != root) popup = new Popup(owner, root, parameters);
            update();
            startPoll();
        } catch (Throwable ignored) {
            Popup previous = popup;
            if (previous != null) previous.restore();
        }
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static void startPoll() {
        if (polling || main == null) return;
        polling = true;
        main.post(poll);
    }

    private static final Runnable poll = new Runnable() {
        @Override public void run() {
            update();
            if (!calls.isEmpty() || popup != null) main.postDelayed(this, POLL_MS);
            else polling = false;
        }
    };

    private static void update() {
        Call ringing = null;
        int ringingCount = 0;
        for (Call call : calls.keySet()) {
            if (call.getState() == Call.STATE_RINGING) {
                ringing = call;
                ringingCount++;
            }
        }
        Popup currentPopup = popup;
        View root = currentPopup == null ? null : currentPopup.root.get();
        Pair controls = root == null ? null : buttons(root);
        int readiness = Math.min(ringingCount, 2) | (root != null ? 4 : 0)
                | (root != null && root.isAttachedToWindow() ? 8 : 0)
                | (controls != null ? 16 : 0) | (context != null ? 32 : 0);
        if (readiness != lastReadiness) {
            lastReadiness = readiness;
            // Only lifecycle/capability flags: no caller names, handles, call tokens or intents.
            XposedBridge.log("HyperBar incoming readiness: ringing=" + Math.min(ringingCount, 2)
                    + " popup=" + (root != null) + " attached=" + (root != null && root.isAttachedToWindow())
                    + " controls=" + (controls != null) + " bridge=" + (context != null));
        }
        if (ringingCount != 1 || context == null || (currentPopup != null && (controls == null || !root.isAttachedToWindow()))) {
            clearSession();
            if (currentPopup != null) {
                currentPopup.restore();
                if (root == null || !root.isAttachedToWindow()) popup = null;
            }
            return;
        }
        if (currentPopup == null) {
            Session previous = session;
            boolean changedBackend = previous == null || previous.call != ringing || previous.popup != null;
            if (changedBackend) {
                if (previous != null) {
                    if (previous.popup != null) previous.popup.restore();
                    previous.cancel();
                }
                try {
                    session = new Session(ringing, tokens.get(ringing), null, null,
                            previous == null ? 1 : previous.generation + 1);
                } catch (Throwable failure) {
                    session = null;
                    emitRemoved(previous == null ? null : previous.token);
                    return;
                }
            }
            emit(false);
            return;
        }
        Session previous = session;
        boolean changedBackend = previous == null || previous.call != ringing
                || previous.popup != currentPopup || previous.answer.get() != controls.answer
                || previous.decline.get() != controls.decline;
        if (changedBackend) {
            if (previous != null) {
                if (previous.popup != null) previous.popup.restore();
                previous.cancel();
            }
            try {
                session = new Session(ringing, tokens.get(ringing), currentPopup, controls,
                        previous == null ? 1 : previous.generation + 1);
            } catch (Throwable failure) {
                session = null;
                currentPopup.restore();
                emitRemoved(previous == null ? null : previous.token);
                return;
            }
        }
        Session current = session;
        if (current == null) return;
        long now = SystemClock.uptimeMillis();
        if (current.lease.mayHide(current.token, current.generation, now)) {
            if (!current.popup.hide()) {
                current.lease.clear();
                clearSession();
                return;
            }
        } else current.popup.restore();
        emit(false);
    }

    private static void clearSession() {
        Session previous = session;
        if (previous == null) return;
        previous.lease.clear();
        if (previous.popup != null) previous.popup.restore();
        session = null;
        emitRemoved(previous.token);
        previous.cancel();
    }

    private static Pair buttons(View root) {
        String[][] candidates = {{"accept_btn", "reject_btn"},
                {"three_way_accept_btn", "three_way_reject_btn"},
                {"voicemail_accept_btn", "voicemail_reject_btn"}};
        for (String[] pair : candidates) {
            View answer = find(root, pair[0]);
            View decline = find(root, pair[1]);
            if (usable(answer) && usable(decline)) return new Pair(answer, decline);
        }
        return null;
    }

    private static boolean usable(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && view.isShown()
                && view.isEnabled() && view.hasOnClickListeners() && backendReady(view);
    }

    private static boolean backendReady(View view) {
        try {
            WeakReference<Object> reference = nativeBindings.get(view);
            Object listener = reference == null ? null : reference.get();
            if (listener == null) return false;
            String name = listener.getClass().getName();
            // Exact ready fields from the supplied Samsung DEX, not guessed Call service APIs.
            if ("U9.b".equals(name)) return field(listener, "C") != null;
            if ("X9.c".equals(name)) return field(listener, "y") != null;
        } catch (Throwable ignored) {}
        return false;
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", APP);
        return id == 0 ? null : root.findViewById(id);
    }

    private static String text(View root, String name) {
        View view = find(root, name);
        if (!(view instanceof TextView) || view.getVisibility() != View.VISIBLE) return null;
        CharSequence text = ((TextView)view).getText();
        return text == null || text.length() == 0 ? null : text.toString();
    }

    private static String callerName(Session current) {
        View root = current.popup == null ? null : current.popup.root.get();
        String name = root == null ? null : text(root, "name_number");
        if (name == null && root != null) name = text(root, "primary_call_name");
        if (name == null && root != null) name = text(root, "name");
        Call.Details details = current.call.getDetails();
        if (name == null && details != null && details.getContactDisplayName() != null
                && details.getContactDisplayName().length() > 0) {
            name = details.getContactDisplayName();
        }
        if (name == null && details != null && details.getCallerDisplayName() != null
                && details.getCallerDisplayName().length() > 0) {
            name = details.getCallerDisplayName();
        }
        if (name == null && details != null && details.getHandle() != null) {
            name = details.getHandle().getSchemeSpecificPart();
        }
        return name == null || name.length() == 0 ? "Incoming call" : name;
    }

    private static String label(View view, String fallback) {
        CharSequence label = view == null ? null : view.getContentDescription();
        return label == null || label.length() == 0 ? fallback : label.toString();
    }

    private static void emit(boolean force) {
        Session current = session;
        if (context == null) return;
        if (current == null) {
            if (force) emitRemoved(null);
            return;
        }
        String title = callerName(current);
        long version = ((long)current.generation << 32) ^ title.hashCode();
        long now = SystemClock.uptimeMillis();
        if (!force && version == lastContentVersion && now - lastPacket < 1000) return;
        PendingIntent answer = current.answerIntent;
        PendingIntent decline = current.declineIntent;
        PendingIntent open = null;
        Notification notification = recentIncomingNotification;
        if (notification != null && now - recentNotificationAt < 2000) {
            open = notification.contentIntent;
            PendingIntent nativeAnswer = parcel(notification, "android.answerIntent");
            PendingIntent nativeDecline = parcel(notification, "android.declineIntent");
            if (notification.extras != null && notification.extras.getInt("android.callType", 0) == 1
                    && nativeAnswer != null && nativeDecline != null) {
                answer = nativeAnswer;
                decline = nativeDecline;
            }
        }
        Intent intent = packet().putExtra("incoming", true).putExtra("token", current.token)
                .putExtra("generation", current.generation)
                .putExtra("title", title).putExtra("subtitle", "Incoming call")
                .putExtra("answerLabel", label(current.answer.get(), "Answer"))
                .putExtra("declineLabel", label(current.decline.get(), "Decline"))
                .putExtra("answer", answer).putExtra("decline", decline)
                .putExtra("presentation", current.presentationIntent).putExtra("open", open)
                .putExtra("contentVersion", version).putExtra("userId", Process.myUid() / 100000);
        try {
            context.sendBroadcast(intent, HyperOsIncomingCalls.PERMISSION);
            lastContentVersion = version;
            lastPacket = now;
        } catch (Throwable failure) {
            current.lease.clear();
            if (current.popup != null) current.popup.restore();
        }
    }

    private static PendingIntent parcel(Notification notification, String key) {
        try { return notification.extras.getParcelable(key, PendingIntent.class); }
        catch (Throwable ignored) { return null; }
    }

    private static Intent packet() {
        return new Intent(HyperOsIncomingCalls.STATE).setPackage(HyperOsIncomingCalls.SYSTEM_UI)
                .putExtra("epoch", EPOCH).putExtra("sequence", ++sequence)
                .putExtra("sentAt", SystemClock.uptimeMillis());
    }

    private static void emitRemoved(String token) {
        if (context == null) return;
        try {
            context.sendBroadcast(packet().putExtra("incoming", false).putExtra("token", token),
                    HyperOsIncomingCalls.PERMISSION);
        } catch (Throwable ignored) {}
        lastContentVersion = Long.MIN_VALUE;
        lastPacket = 0;
    }

    private static void command(Intent intent) {
        Session current = session;
        if (intent == null || !COMMAND.equals(intent.getAction()) || current == null) return;
        String token = intent.getStringExtra("token");
        int generation = intent.getIntExtra("generation", -1);
        if (!current.token.equals(token) || current.generation != generation
                || current.call.getState() != Call.STATE_RINGING) return;
        String operation = intent.getStringExtra("operation");
        if ("presentation".equals(operation)) {
            if (current.popup != null) {
                current.lease.presented(token, generation, intent.getBooleanExtra("ready", false),
                        intent.getBooleanExtra("visible", false), SystemClock.uptimeMillis());
                update();
            }
            return;
        }
        if (current.popup == null) {
            try {
                if ("answer".equals(operation)) {
                    int videoState = VideoProfile.STATE_AUDIO_ONLY;
                    try {
                        Call.Details details = current.call.getDetails();
                        if (details != null) videoState = details.getVideoState();
                    } catch (Throwable ignored) {}
                    current.call.answer(videoState);
                } else if ("decline".equals(operation)) {
                    current.call.reject(false, null);
                }
            } catch (Throwable ignored) {}
            main.postDelayed(new Runnable() { @Override public void run() { update(); } }, 100);
            return;
        }
        View button = "answer".equals(operation) ? current.answer.get()
                : "decline".equals(operation) ? current.decline.get() : null;
        // isShown does not depend on window alpha; the native handler keeps its own call checks.
        if (!usable(button) || current.popup != popup) {
            current.lease.clear();
            current.popup.restore();
            return;
        }
        current.lease.clear();
        try {
            // Verified U9.b/X9.c listeners route to Samsung j1(false,false) / w5().
            if (!button.performClick()) current.popup.restore();
        } catch (Throwable failure) {
            current.popup.restore();
        }
        main.postDelayed(new Runnable() { @Override public void run() { update(); } }, 100);
    }

    private static final class Pair {
        final View answer;
        final View decline;
        Pair(View answer, View decline) { this.answer = answer; this.decline = decline; }
    }

    private static final class Session {
        final Call call;
        final String token;
        final int generation;
        final Popup popup;
        final WeakReference<View> answer;
        final WeakReference<View> decline;
        final PendingIntent answerIntent;
        final PendingIntent declineIntent;
        final PendingIntent presentationIntent;
        final PresentationLease lease;
        Session(Call call, String token, Popup popup, Pair controls, int generation) {
            this.call = call;
            this.token = token;
            this.popup = popup;
            this.generation = generation;
            this.answer = new WeakReference<View>(controls == null ? null : controls.answer);
            this.decline = new WeakReference<View>(controls == null ? null : controls.decline);
            this.lease = new PresentationLease(token, generation);
            this.answerIntent = capability("answer", false);
            this.declineIntent = capability("decline", false);
            this.presentationIntent = popup == null ? null : capability("presentation", true);
        }
        PendingIntent capability(String operation, boolean mutable) {
            Intent intent = new Intent(COMMAND).setPackage(APP)
                    .setData(Uri.parse("hyperos-call://" + token + "/" + generation + "/" + operation))
                    .putExtra("token", token).putExtra("generation", generation).putExtra("operation", operation);
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT
                    | (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE));
        }
        void cancel() {
            try { answerIntent.cancel(); } catch (Throwable ignored) {}
            try { declineIntent.cancel(); } catch (Throwable ignored) {}
            if (presentationIntent != null) try { presentationIntent.cancel(); } catch (Throwable ignored) {}
        }
    }

    private static final class Popup {
        final WeakReference<Object> owner;
        final WeakReference<View> root;
        final WindowManager.LayoutParams parameters;
        boolean hidden;
        boolean originallyNotTouchable;
        float originalAlpha;
        int originalAccessibility;
        Popup(Object owner, View root, WindowManager.LayoutParams parameters) {
            this.owner = new WeakReference<Object>(owner);
            this.root = new WeakReference<View>(root);
            this.parameters = parameters;
        }
        boolean hide() {
            View view = root.get();
            if (view == null || !view.isAttachedToWindow()) return false;
            if (!hidden) {
                originallyNotTouchable = (parameters.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
                originalAlpha = parameters.alpha;
                originalAccessibility = view.getImportantForAccessibility();
            } else {
                if (parameters.alpha > 0) originalAlpha = parameters.alpha;
                if (view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    originalAccessibility = view.getImportantForAccessibility();
            }
            parameters.alpha = 0;
            parameters.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                manager(view).updateViewLayout(view, parameters);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                hidden = true;
                return true;
            } catch (Throwable failure) {
                restoreParameters();
                // Preserve retry state: a failed binder update leaves compositor state unknown.
                hidden = true;
                return false;
            }
        }
        void restore() {
            if (!hidden) return;
            restoreParameters();
            View view = root.get();
            if (view != null && view.isAttachedToWindow()) {
                try { manager(view).updateViewLayout(view, parameters); }
                catch (Throwable ignored) { return; }
            }
            if (view != null) view.setImportantForAccessibility(originalAccessibility);
            hidden = false;
        }
        void restoreParameters() {
            parameters.alpha = originalAlpha;
            parameters.flags = (parameters.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    | (originallyNotTouchable ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        }
        WindowManager manager(View root) {
            return (WindowManager)root.getContext().getSystemService(Context.WINDOW_SERVICE);
        }
    }

    /** Pure lease policy, separately tested without a call, process attach, or Telecom operation. */
    static final class PresentationLease {
        private static final long LEASE_MS = 1500;
        final String token;
        final int generation;
        private long expiresAt;
        PresentationLease(String token, int generation) { this.token = token; this.generation = generation; }
        void presented(String candidate, int candidateGeneration, boolean ready, boolean visible, long now) {
            if (!token.equals(candidate) || generation != candidateGeneration) return;
            expiresAt = ready && visible && now >= 0 ? now + LEASE_MS : 0;
        }
        boolean mayHide(String candidate, int candidateGeneration, long now) {
            return token.equals(candidate) && generation == candidateGeneration && now >= 0
                    && expiresAt > now && expiresAt - now <= LEASE_MS;
        }
        void clear() { expiresAt = 0; }
    }
}
