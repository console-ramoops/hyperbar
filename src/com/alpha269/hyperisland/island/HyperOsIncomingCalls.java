package com.alpha269.hyperisland.island;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.List;

/** SystemUI side of the capability bridge from Samsung's actual incoming popup. */
public final class HyperOsIncomingCalls {
    public static final String APP = "com.samsung.android.incallui";
    public static final String SYSTEM_UI = "com.android.systemui";
    public static final String STATE = "com.alpha269.hyperisland.HYPEROS_INCOMING_CALL_STATE";
    public static final String QUERY = "com.alpha269.hyperisland.HYPEROS_INCOMING_CALL_QUERY";
    public static final String PERMISSION = "android.permission.MODIFY_PHONE_STATE";
    public static final String KEY_PREFIX = "hyperos:incoming:";
    private static final long STATE_TIMEOUT_MS = 3500;
    private static final long HEARTBEAT_MS = 300;
    private static Context context;
    private static Handler main;
    private static Runnable changed;
    private static Snapshot current;
    private static WeakReference<View> surface;
    private static boolean presentationReady;
    private static boolean advertisedVisible;
    private static long lastAdvertisement;
    private static String latestEpoch;
    private static long latestSequence = -1;

    private HyperOsIncomingCalls() {}

    private static final class Snapshot {
        final String token;
        final String key;
        final IslandItem item;
        final PendingIntent presentation;
        final long receivedAt;
        Snapshot(String token, IslandItem item, PendingIntent presentation, long now) {
            this.token = token;
            this.key = item.key;
            this.item = item;
            this.presentation = presentation;
            this.receivedAt = now;
        }
    }

    /** Called once by IslandController; updates run on its main looper. */
    public static synchronized void initialize(Context app, Runnable onChanged) {
        changed = onChanged;
        if (context != null || app == null) return;
        context = app.getApplicationContext();
        if (context == null) context = app;
        main = new Handler(Looper.getMainLooper());
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    receive(intent);
                }
            }, new IntentFilter(STATE), PERMISSION, main, Context.RECEIVER_EXPORTED);
            main.post(heartbeat);
            // The receiver is protected by the platform signature permission in both directions.
            context.sendBroadcast(new Intent(QUERY).setPackage(APP), PERMISSION);
        } catch (Throwable failure) {
            context = null;
            main = null;
            current = null;
        }
    }

    public static void appendTo(List<IslandItem> items) {
        IslandItem incoming = currentItem();
        if (incoming == null || items == null) return;
        for (IslandItem item : items) if (incoming.key.equals(item.key)) return;
        items.add(incoming);
    }

    public static IslandItem currentItem() {
        Snapshot snapshot = current;
        if (snapshot == null) return null;
        if (SystemClock.uptimeMillis() - snapshot.receivedAt > STATE_TIMEOUT_MS) {
            if (main != null) main.post(new Runnable() {
                @Override public void run() { expire(); }
            });
            return null;
        }
        return snapshot.item;
    }

    public static boolean isIncoming(IslandItem item) {
        return item != null && item.key.startsWith(KEY_PREFIX);
    }

    /**
     * Call after the replacement frame is committed and its answer/decline buttons are ready.
     * The heartbeat independently stops the lease if this surface becomes hidden or detached.
     */
    public static void reportPresentation(String key, View replacement, boolean interactive) {
        Snapshot snapshot = current;
        if (snapshot == null || key == null || !snapshot.key.equals(key)) {
            reportHidden();
            return;
        }
        surface = replacement == null ? null : new WeakReference<View>(replacement);
        presentationReady = interactive;
        advertise(snapshot, interactive && visible(replacement));
    }

    /** For callers that already own a frame-commit visibility check; repeat on each frame. */
    public static void reportPresentation(String key, boolean ready, boolean visible) {
        Snapshot snapshot = current;
        if (snapshot == null || key == null || !snapshot.key.equals(key)) {
            reportHidden();
            return;
        }
        surface = null;
        presentationReady = ready;
        advertise(snapshot, ready && visible);
    }

    /** Must be called when suppressed, detached, disabled, or when another card takes over. */
    public static void reportHidden() {
        surface = null;
        presentationReady = false;
        Snapshot snapshot = current;
        if (snapshot != null) advertise(snapshot, false);
    }

    /** Prefer the framework's explicit incoming CallStyle capabilities when supplied. */
    public static void onNotificationPosted(StatusBarNotification sbn) {
        Snapshot snapshot = current;
        if (snapshot == null || sbn == null || !APP.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null
                || notification.extras.getInt("android.callType", 0) != 1) return;
        PendingIntent answer = parcel(notification.extras, "android.answerIntent");
        PendingIntent decline = parcel(notification.extras, "android.declineIntent");
        if (answer == null || decline == null) return;
        IslandItem replacement = copyItem(snapshot.item, decline, answer);
        current = new Snapshot(snapshot.token, replacement, snapshot.presentation, snapshot.receivedAt);
        notifyChanged();
    }

    private static void receive(Intent intent) {
        if (intent == null || !STATE.equals(intent.getAction())) return;
        long now = SystemClock.uptimeMillis();
        long sentAt = intent.getLongExtra("sentAt", -1);
        if (sentAt < 0 || sentAt > now + 1000 || now - sentAt > STATE_TIMEOUT_MS) return;
        String epoch = intent.getStringExtra("epoch");
        long sequence = intent.getLongExtra("sequence", -1);
        if (epoch == null || epoch.length() > 128 || sequence < 0) return;
        if (epoch.equals(latestEpoch) && sequence <= latestSequence) return;
        latestEpoch = epoch;
        latestSequence = sequence;
        String token = intent.getStringExtra("token");
        if (!intent.getBooleanExtra("incoming", false)) {
            if (current == null || token == null || current.token.equals(token)) clear();
            return;
        }
        if (token == null || token.length() == 0 || token.length() > 128) return;
        int generation = intent.getIntExtra("generation", -1);
        if (generation < 1) return;
        PendingIntent answer = parcel(intent, "answer");
        PendingIntent decline = parcel(intent, "decline");
        PendingIntent presentation = parcel(intent, "presentation");
        if (answer == null || decline == null || presentation == null) {
            clear();
            return;
        }
        Snapshot previous = current;
        String title = bounded(intent.getStringExtra("title"), "Incoming call");
        String subtitle = bounded(intent.getStringExtra("subtitle"), "Incoming call");
        // A new native button binding invalidates the old card's frame/interaction readiness.
        IslandItem item = new IslandItem(KEY_PREFIX + token + ":" + generation);
        item.kind = IslandItem.Kind.CALL;
        item.packageName = APP;
        item.appName = bounded(intent.getStringExtra("appName"), "Phone");
        item.title = title;
        item.subtitle = subtitle;
        item.overline = "Incoming call";
        item.detail = "hyperos_incoming_call";
        item.alert = true;
        item.accent = 0xff1dcd3a;
        item.pillColor = 0xff000000;
        item.trailing = IslandItem.Trailing.TEXT;
        item.trailingText = "Incoming call";
        item.postTime = Long.MAX_VALUE;
        item.userId = intent.getIntExtra("userId", 0);
        item.contentVersion = intent.getLongExtra("contentVersion", sequence);
        item.contentIntent = parcel(intent, "open");
        item.actions.add(new IslandItem.Action(bounded(intent.getStringExtra("declineLabel"), "Decline"),
                null, decline, null, true));
        item.actions.add(new IslandItem.Action(bounded(intent.getStringExtra("answerLabel"), "Answer"),
                null, answer, null, false));
        if (previous != null && !previous.key.equals(item.key)) {
            advertise(previous, false);
            surface = null;
            presentationReady = false;
        }
        current = new Snapshot(token, item, presentation, now);
        if (previous == null || !same(previous.item, item)) notifyChanged();
    }

    private static boolean same(IslandItem a, IslandItem b) {
        return a.key.equals(b.key) && String.valueOf(a.title).equals(String.valueOf(b.title))
                && String.valueOf(a.subtitle).equals(String.valueOf(b.subtitle))
                && a.contentVersion == b.contentVersion;
    }

    private static IslandItem copyItem(IslandItem source, PendingIntent decline, PendingIntent answer) {
        IslandItem item = new IslandItem(source.key);
        item.kind = source.kind;
        item.packageName = source.packageName;
        item.appName = source.appName;
        item.title = source.title;
        item.subtitle = source.subtitle;
        item.overline = source.overline;
        item.detail = source.detail;
        item.alert = source.alert;
        item.accent = source.accent;
        item.pillColor = source.pillColor;
        item.trailing = source.trailing;
        item.trailingText = source.trailingText;
        item.postTime = source.postTime;
        item.userId = source.userId;
        item.contentVersion = source.contentVersion;
        item.contentIntent = source.contentIntent;
        item.actions.add(new IslandItem.Action(source.actions.get(0).title, null, decline, null, true));
        item.actions.add(new IslandItem.Action(source.actions.get(1).title, null, answer, null, false));
        return item;
    }

    private static String bounded(String text, String fallback) {
        if (text == null || text.trim().length() == 0) return fallback;
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    private static PendingIntent parcel(Intent intent, String key) {
        try { return intent.getParcelableExtra(key, PendingIntent.class); }
        catch (Throwable ignored) { return null; }
    }
    private static PendingIntent parcel(android.os.Bundle bundle, String key) {
        try { return bundle.getParcelable(key, PendingIntent.class); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean visible(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0 && view.getAlpha() > 0.01f;
    }

    private static void advertise(Snapshot snapshot, boolean shown) {
        long now = SystemClock.uptimeMillis();
        if (shown == advertisedVisible && now - lastAdvertisement < HEARTBEAT_MS) return;
        try {
            Intent fillIn = new Intent().putExtra("ready", shown).putExtra("visible", shown);
            snapshot.presentation.send(context, 0, fillIn);
            advertisedVisible = shown;
            lastAdvertisement = now;
        } catch (Throwable failure) {
            advertisedVisible = false;
            presentationReady = false;
            surface = null;
            if (current == snapshot) {
                current = null;
                notifyChanged();
            }
        }
    }

    private static void clear() {
        Snapshot previous = current;
        current = null;
        presentationReady = false;
        surface = null;
        if (previous != null) {
            advertise(previous, false);
            notifyChanged();
        }
    }

    private static void expire() {
        Snapshot snapshot = current;
        if (snapshot != null && SystemClock.uptimeMillis() - snapshot.receivedAt > STATE_TIMEOUT_MS) clear();
    }

    private static void notifyChanged() {
        Runnable callback = changed;
        if (callback != null) {
            try { callback.run(); } catch (Throwable ignored) {}
        }
    }

    private static final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            expire();
            Snapshot snapshot = current;
            if (snapshot != null && surface != null) {
                advertise(snapshot, presentationReady && visible(surface.get()));
            }
            Handler handler = main;
            if (handler != null) handler.postDelayed(this, HEARTBEAT_MS);
        }
    };
}
