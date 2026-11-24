package com.alpha269.hyperisland.island;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/* JADX INFO: loaded from: classes3.dex */
public final class IslandItem {
    private static final AtomicLong SEQ = new AtomicLong();
    public boolean alert;
    public Drawable appGlyph;
    public Drawable appIcon;
    public CharSequence appName;
    public Bitmap art;
    public int assistantStyle;
    public boolean blank;
    public Drawable cardIcon;
    public Drawable cardImage;
    public boolean cardImageIsImage;
    public long chronoBase;
    public boolean chronoCountDown;
    public boolean chronoMillis;
    /** 0 timer/stopwatch, 1 ringing alarm, 2 snoozed alarm, 3 finished countdown. */
    public int clockStyle;
    public CharSequence clockText;
    public boolean claude;
    public String claudeQuestion;
    public Drawable claudeSpark;
    public String claudeState;
    public Drawable claudeTaskIcon;
    public int claudeTasks;
    public PendingIntent contentIntent;
    public long contentVersion;
    public CharSequence detail;
    public long expiresAtUptime;
    public Drawable icon;
    public boolean iconColor;
    public boolean iconIsImage;
    public boolean iconRight;
    public Runnable iconTap;
    public int iconTint;
    public boolean incomingCall;
    public boolean isMedia;
    public final String key;
    public Drawable leftImage;
    public Runnable mediaNext;
    public Runnable mediaOpen;
    public Runnable mediaPlayPause;
    public long mediaPositionUpdateTime;
    public Runnable mediaPrev;
    public LongConsumer mediaSeek;
    public List<Metric> metrics;
    public boolean noExpand;
    public CharSequence overline;
    public int overlineColor;
    public String packageName;
    public int pillColor;
    public boolean playing;
    public long postTime;
    public boolean progressStyle;
    public boolean pulsingDot;
    public boolean recorder;
    public boolean recorderSaved;
    public RemoteViews remoteView;
    public Drawable rightImage;
    public CharSequence subtitle;
    public CharSequence title;
    public boolean torch;
    public boolean torchOn;
    public IntConsumer torchSet;
    public Runnable torchToggle;
    public int trailingColor;
    public Runnable trailingTap;
    public CharSequence trailingText;
    public int userId;
    public Kind kind = Kind.LIVE;
    public int accent = -1;
    public Trailing trailing = Trailing.NONE;
    public long pausedMs = -1;
    public float progress = -1.0f;
    public int criticalMetric = -1;
    public final List<Action> actions = new ArrayList();
    public String[] claudeStages = new String[0];
    public Drawable[] claudeIcons = new Drawable[0];
    public long mediaPosition = -1;
    public long mediaDuration = -1;
    public float mediaSpeed = 1.0f;
    public final List<Action> mediaCustom = new ArrayList();
    public final List<Shortcut> mediaShortcuts = new ArrayList();
    public int toggle = -1;
    public int ringPercent = -1;
    public int torchLevel = 1;
    public int torchMax = 1;
    public final long seq = SEQ.incrementAndGet();

    public enum Trailing {
        NONE,
        TEXT,
        CHRONOMETER,
        PROGRESS_RING,
        WAVEFORM,
        CLAUDE
    }

    public enum Kind {
        CALL(100),
        NAVIGATION(90),
        RECORDING(80),
        TIMER(70),
        LIVE(60),
        MEDIA(50),
        CHARGING(40);

        public final int priority;

        Kind(int priority) {
            this.priority = priority;
        }
    }

    public static final class Shortcut {
        public final Bitmap art;
        public final Runnable run;
        public final CharSequence title;

        public Shortcut(CharSequence title, Bitmap art, Runnable run) {
            this.title = title;
            this.art = art;
            this.run = run;
        }
    }

    public static final class Action {
        public boolean active;
        public final boolean destructive;
        public int glyph;
        public final Drawable icon;
        public final PendingIntent intent;
        public final Runnable runnable;
        public final CharSequence title;

        public Action(CharSequence title, Drawable icon, PendingIntent intent, Runnable runnable, boolean destructive) {
            this.title = title;
            this.icon = icon;
            this.intent = intent;
            this.runnable = runnable;
            this.destructive = destructive;
        }
    }

    public static final class Metric {
        public static final int DATE = 2;
        public static final int FLOAT = 5;
        public static final int INT = 4;
        public static final int TEXT = 6;
        public static final int TIME = 1;
        public static final int TIME_OF_DAY = 3;
        public boolean countDown;
        public CharSequence fixed;
        public int format;
        public CharSequence label;
        public long pausedMs = -1;
        public int semantic;
        public int type;
        public long zeroTime;

        public boolean running() {
            return this.type == 1 && this.pausedMs < 0;
        }
    }

    public IslandItem(String key) {
        this.key = key;
    }
    public boolean hasChronometer() {
        return trailing == Trailing.CHRONOMETER || chronoBase > 0 || pausedMs >= 0;
    }

    public boolean hasTeams() {
        return (this.leftImage == null || this.rightImage == null) ? false : true;
    }

    public int priority() {
        return this.kind.priority;
    }

    public long currentMediaPosition(long nowElapsed) {
        if (this.mediaPosition < 0) {
            return -1L;
        }
        if (!this.playing) {
            return this.mediaPosition;
        }
        long pos = this.mediaPosition + ((long) ((nowElapsed - this.mediaPositionUpdateTime) * this.mediaSpeed));
        if (this.mediaDuration > 0) {
            pos = Math.min(pos, this.mediaDuration);
        }
        return Math.max(0L, pos);
    }

    public boolean sameCompactLook(IslandItem o) {
        return o != null && Objects.equals(this.key, o.key) && this.trailing == o.trailing && (this.accent == o.accent || (this.isMedia && o.isMedia)) && this.iconIsImage == o.iconIsImage && this.pulsingDot == o.pulsingDot && this.pillColor == o.pillColor && this.torch == o.torch && Objects.equals(this.claudeState, o.claudeState) && this.recorder == o.recorder && this.recorderSaved == o.recorderSaved && this.trailingColor == o.trailingColor && hasTeams() == o.hasTeams();
    }

    public String toString() {
        return "IslandItem{" + this.key + " " + this.kind + " " + this.packageName + "}";
    }
}
