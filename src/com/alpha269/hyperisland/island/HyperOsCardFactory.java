package com.alpha269.hyperisland.island;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Html;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** HyperOS resource-backed card bodies; Android owns text shaping, accessibility and action dispatch. */
public final class HyperOsCardFactory {
    private static final int WHITE = 0xffffffff;
    private static final int BODY = 0x80ffffff;
    private static final String[] AREA_A = {"animTextInfo", "coverInfo", "iconTextInfo", "baseInfo", "highlightInfo", "chatInfo"};
    private static final LinkedHashMap<String, Template> templates = new LinkedHashMap<String, Template>();
    private static long templateSequence;
    private static AnimationProvider animations;
    private static final WeakHashMap<TextView, Boolean> centeredLabels = new WeakHashMap<TextView, Boolean>();

    private HyperOsCardFactory() {}

    public interface AnimationTarget {
        void setAnimation(HyperOsResources resources, String source, boolean autoplay, boolean loop, int number);
        void step(long nowElapsed);
        boolean active();
        void release();
        default void setOwnerVisible(boolean visible) {}
    }
    public interface AnimationProvider {
        View create(Context context, AttributeSet attributes);
    }
    public static void setAnimationProvider(AnimationProvider provider) { animations = provider; }

    /** Retains an actual native focus payload and its bound Icon/Notification.Action capabilities. */
    public static synchronized void registerTemplate(String key, JSONObject payload, Bundle pictures, Bundle actions) {
        if (key == null || payload == null) return;
        String json = payload.toString();
        if (json.length() > 65536) return;
        try {
            JSONObject copy = new JSONObject(json);
            JSONObject nested = copy.optJSONObject("param_v3");
            if (nested == null) nested = copy.optJSONObject("param_v2");
            if (nested != null) copy = nested;
            templates.remove(key);
            templates.put(key, new Template(copy, pictures, actions, ++templateSequence));
            while (templates.size() > 64) templates.remove(templates.keySet().iterator().next());
        } catch (Throwable ignored) {}
    }
    public static synchronized void unregisterTemplate(String key) { templates.remove(key); }
    private static synchronized Template registered(String key) { return templates.get(key); }

    /** Resources' inflater delegates vendor UI classes here, never to a donor class loader. */
    public static View createVendorView(String name, Context context, AttributeSet attributes) {
        if (name.endsWith(".HyperChronometer") || name.endsWith(".HyperChronometerPlugin"))
            return new Chronometer(context, attributes);
        if (name.endsWith(".TimerTextEffectView") || name.endsWith(".VisibleFocusedTextView"))
            return new TextView(context, attributes);
        if (name.endsWith(".LottieView") || name.endsWith(".AnimateImageView")) {
            AnimationProvider provider = animations;
            View view = provider == null ? null : provider.create(context, attributes);
            return view == null ? new StaticAnimationView(context, attributes) : view;
        }
        if (name.endsWith(".StatusProgressLayout")) return new CircleControl(context, attributes);
        if (name.endsWith(".HyperProgressSeekBar")) return new HyperSeekBar(context, attributes);
        if (name.endsWith(".CachingIconView")) return new ImageView(context, attributes);
        if (name.endsWith(".MusicBgView")) return new HyperOsMusicBackground(context, attributes);
        if (name.endsWith(".ShaderView")) return new View(context, attributes);
        if (name.endsWith(".FlashLightView")) return new HyperOsFlashlightEffect(context, attributes);
        if (name.endsWith(".TextureVideoView")) return new ImageView(context, attributes);
        if (name.endsWith(".LaunchableLinearLayout")) return new LinearLayout(context, attributes);
        if (name.endsWith(".PlayerIslandConstraintLayout")) return new MediaLayout(context, attributes);
        if ("androidx.constraintlayout.widget.ConstraintLayout".equals(name)
                && "com.android.systemui".equals(context.getPackageName())) return new FrameLayout(context, attributes);
        if ("androidx.constraintlayout.widget.ConstraintLayout".equals(name)) {
            try {
                Class<?> type = Class.forName(name, false, HyperOsCardFactory.class.getClassLoader());
                return (View)type.getConstructor(Context.class, AttributeSet.class).newInstance(context, attributes);
            } catch (ReflectiveOperationException unavailable) {
                throw new IllegalStateException("Missing public ConstraintLayout 2.2.0 dependency", unavailable);
            }
        }
        return null;
    }

    public static Card create(Context context, IslandHost host, IslandItem item, int width) {
        Card card = new Card(context, host, item, Math.max(1, width));
        card.rebuild();
        return card;
    }

    public static final class Card {
        public final View view;
        public String failure;
        private final FrameLayout container;
        private final Context context;
        private final IslandHost host;
        private final int width;
        private final List<Binding> bindings = new ArrayList<Binding>();
        private final List<ActionBinding> buttons = new ArrayList<ActionBinding>();
        private final List<AnimationTarget> animated = new ArrayList<AnimationTarget>();
        private final List<Runnable> disposals = new ArrayList<Runnable>();
        private IslandItem item;
        private Template template;
        private HyperOsResources resources;
        private boolean incoming;
        private boolean ticking;
        private boolean released;
        private long contentFingerprint;

        private Card(Context context, IslandHost host, IslandItem item, int width) {
            this.context = context;
            this.host = host;
            this.item = item;
            this.contentFingerprint = fingerprint(item);
            this.width = width;
            this.container = new FrameLayout(context);
            this.container.setClipChildren(false);
            this.container.setClipToPadding(false);
            this.view = container;
        }
        public int height() { return failure == null ? container.getMeasuredHeight() : 0; }
        public void update(IslandItem next, long nowElapsed) {
            if (released || next == null) return;
            Template nextTemplate = registered(next.key);
            long nextFingerprint = fingerprint(next);
            boolean rebuild = item == null || !item.key.equals(next.key) || family(item) != family(next)
                    || (nextTemplate == null ? template != null && template.registered : template != nextTemplate)
                    || (family(next) == 0 && nextTemplate == null && nextFingerprint != contentFingerprint);
            item = next;
            contentFingerprint = nextFingerprint;
            if (family(next) == 2) ticking = next.playing;
            else if (family(next) == 1) ticking = next.hasChronometer() && next.pausedMs < 0;
            if (rebuild) { rebuild(); setOwnerVisible(ownerVisible); return; }
            for (Binding binding : bindings) binding.update(nowElapsed);
            for (AnimationTarget animation : animated) animation.step(nowElapsed);
            measure();
        }
        public boolean hasReadyIncomingControls() {
            if (!incoming || released || failure != null || item == null || !HyperOsIncomingCalls.isIncoming(item)) return false;
            if (buttons.size() != 2) return false;
            for (ActionBinding button : buttons) if (!button.ready()) return false;
            return true;
        }
        private boolean ownerVisible=true;
        public void setOwnerVisible(boolean visible){ownerVisible=visible;for(AnimationTarget animation:animated)animation.setOwnerVisible(visible);}
        public boolean needsFrames() {
            if (released || failure != null || !ownerVisible) return false;
            if (ticking) return true;
            for (AnimationTarget animation : animated) if (animation.active()) return true;
            return false;
        }
        public void release() { released = true; dispose(); container.removeAllViews(); }
        private void dispose() {
            for (AnimationTarget animation : animated) animation.release();
            for (ActionBinding button : buttons) if (button.view != null) button.view.setOnClickListener(null);
            for (Runnable disposal : disposals) disposal.run();
            disposals.clear();
            bindings.clear(); buttons.clear(); animated.clear(); ticking = false;
        }
        private void rebuild() {
            dispose(); container.removeAllViews(); failure = null; incoming = false;
            template = item == null ? null : registered(item.key);
            try {
                if (item == null || item.blank) throw new IllegalArgumentException("No card data");
                if (family(item) == 1) buildCall(this);
                else if (family(item) == 2) buildMedia(this);
                else {
                    resources = asset(context, "plugin");
                    if (template == null) template = new Template(adapt(item), null, null, 0);
                    buildGeneric(this);
                    if (item.torch) bindTorchStrength(this);
                }
                for (Binding binding : bindings) binding.update(SystemClock.elapsedRealtime());
                measure();
            } catch (Throwable error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                dispose(); container.removeAllViews(); container.setVisibility(View.GONE);
            }
        }
        private void add(View child) {
            container.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams previous = child.getLayoutParams();
            int height = previous == null ? ViewGroup.LayoutParams.WRAP_CONTENT : previous.height;
            container.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        }
        private void measure() {
            if (failure != null) return;
            prepareTextMetrics(container);
            container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            container.layout(0, 0, width, container.getMeasuredHeight());
            // RelativeLayout first measures wrap-content descendants with a
            // provisional bound. Propagate their actual text minima through
            // wrap-content groups, then let Android perform the final layout.
            for (int i = 0; i < container.getChildCount(); i++) prepareTextMetrics(container.getChildAt(i));
            container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            container.layout(0, 0, width, container.getMeasuredHeight());
        }
    }

    private interface Binding { void update(long nowElapsed); }
    private static int family(IslandItem item) {
        return item.kind == IslandItem.Kind.CALL ? 1 : item.isMedia ? 2 : item.torch ? 3 : 0;
    }
    private static long fingerprint(IslandItem item) {
        if (item == null) return 0;
        long hash = item.contentVersion;
        for (CharSequence text : new CharSequence[]{item.title, item.subtitle, item.overline}) hash = hash * 31 + (text == null ? 0 : text.toString().hashCode());
        hash = hash * 31 + System.identityHashCode(item.cardImage);
        hash = hash * 31 + System.identityHashCode(item.cardIcon);
        hash = hash * 31 + System.identityHashCode(item.art);
        for (IslandItem.Action action : item.actions) {
            hash = hash * 31 + (action.title == null ? 0 : action.title.toString().hashCode());
            hash = hash * 31 + System.identityHashCode(action.intent);
            hash = hash * 31 + System.identityHashCode(action.runnable);
        }
        hash = hash * 31 + item.actions.size();
        hash = hash * 31 + (item.chronoCountDown ? 1 : 0);
        hash = hash * 31 + item.clockStyle;
        hash = hash * 31 + (item.clockText==null?0:item.clockText.toString().hashCode());
        hash = hash * 31 + (item.pausedMs >= 0 ? 1 : 0);
        hash = hash * 31 + (item.recorderSaved ? 1 : 0);
        hash = hash * 31 + (item.torchOn ? 1 : 0);
        hash = hash * 31 + item.ringPercent;
        if (item.metrics != null) for (IslandItem.Metric metric : item.metrics) {
            hash = hash * 31 + (metric.label == null ? 0 : metric.label.toString().hashCode());
            hash = hash * 31 + (metric.fixed == null ? 0 : metric.fixed.toString().hashCode());
        }
        return hash * 31 + (item.progress >= 0 ? 1 : 0);
    }
    private static final class Template {
        final JSONObject json;
        final Bundle pictures;
        final Bundle actions;
        final long version;
        final boolean registered;
        Template(JSONObject json, Bundle pictures, Bundle actions, long version) {
            this.json = json; this.pictures = pictures; this.actions = actions;
            this.version = version; this.registered = version > 0;
        }
    }

    private static HyperOsResources asset(Context context, String name) throws Exception {
        Class<?> assets = Class.forName("com.alpha269.hyperisland.island.HyperOsAssets");
        return (HyperOsResources)assets.getMethod(name, Context.class).invoke(null, context);
    }
    private static View find(HyperOsResources resources, View parent, String name) {
        int id = resources.getResources().getIdentifier(name, "id", resources.getPackageName());
        return id == 0 ? null : parent.findViewById(id);
    }
    private static Drawable drawable(HyperOsResources resources, String name) {
        if (name == null || name.length() == 0) return null;
        int id = resources.getResources().getIdentifier(name, "drawable", resources.getPackageName());
        return id == 0 ? null : resources.getResources().getDrawable(id, resources.getTheme());
    }
    private static int px(HyperOsResources resources, String name, float fallbackDp) {
        int id = resources.getResources().getIdentifier(name, "dimen", resources.getPackageName());
        return id == 0 ? Math.round(fallbackDp * resources.getResources().getDisplayMetrics().density)
                : resources.getResources().getDimensionPixelSize(id);
    }
    private static void text(View view, CharSequence value, Integer color) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView)view;
        text.setWillNotDraw(false);
        if ((text.getTextAlignment() == View.TEXT_ALIGNMENT_CENTER
                || (text.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL)
                && !centeredLabels.containsKey(text)) {
            // One UI's singleLine+center BoringLayout can retain VERY_WIDE after weighted measure.
            // Keep the source's one-line/ellipsis behavior with a width-constrained layout.
            text.setSingleLine(false); text.setMaxLines(1); text.setHorizontallyScrolling(false);
            centeredLabels.put(text, Boolean.TRUE);
        }
        boolean show = value != null && value.length() > 0;
        text.setVisibility(show ? View.VISIBLE : View.GONE);
        if (color != null) { text.setTextColor(color); text.getPaint().setColor(color); }
        if (show && !TextUtils.equals(text.getText(), value)) text.setText(value);
    }
    private static void text(HyperOsResources r, View parent, String id, CharSequence value, Integer color) {
        text(find(r, parent, id), value, color);
    }
    private static void image(View view, Drawable image) {
        if (!(view instanceof ImageView)) return;
        view.setVisibility(image == null ? View.GONE : View.VISIBLE);
        ((ImageView)view).setImageDrawable(image);
    }
    private static CharSequence html(String value) {
        return value == null ? null : Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY);
    }
    private static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        return object.optString(key, null);
    }
    private static Integer color(JSONObject object, String name, Integer fallback) {
        String text = string(object, name + "Dark");
        if (text == null) text = string(object, name);
        if (text == null) return fallback;
        try { return Color.parseColor(text); } catch (Throwable ignored) { return fallback; }
    }
    private static void gone(HyperOsResources r, View parent, String id) {
        View view = find(r, parent, id); if (view != null) view.setVisibility(View.GONE);
    }
    private static void margin(View view, boolean start, float dp) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)view.getLayoutParams();
        int value = Math.round(dp * view.getResources().getDisplayMetrics().density);
        if (start) params.setMarginStart(value); else params.setMarginEnd(value);
        view.setLayoutParams(params);
    }
    private static void size(TextView view, float dp) {
        if (view != null) view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp);
    }

    /** Measure the face in the Paint; One UI can report a different default face on TextView. */
    private static void prepareTextMetrics(View view) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            ViewGroup.LayoutParams parameters = text.getLayoutParams();
            if (parameters != null && parameters.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                Paint.FontMetricsInt metrics = text.getPaint().getFontMetricsInt();
                int line = text.getIncludeFontPadding() ? metrics.bottom - metrics.top : metrics.descent - metrics.ascent;
                int actual = text.getLayout() == null ? line : Math.max(line, text.getLayout().getHeight());
                int minimum = Math.max(0, actual) + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom();
                if (text.getMinHeight() < minimum) text.setMinHeight(minimum);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) prepareTextMetrics(group.getChildAt(i));
            ViewGroup.LayoutParams parameters = view.getLayoutParams();
            if (parameters != null && parameters.height == ViewGroup.LayoutParams.WRAP_CONTENT
                    && (group instanceof LinearLayout || group instanceof FrameLayout || group instanceof android.widget.RelativeLayout)) {
                int content = 0;
                boolean vertical = group instanceof LinearLayout && ((LinearLayout)group).getOrientation() == LinearLayout.VERTICAL;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                    ViewGroup.LayoutParams childParameters = child.getLayoutParams();
                    int top = 0, bottom = 0;
                    if (childParameters instanceof ViewGroup.MarginLayoutParams) {
                        top = ((ViewGroup.MarginLayoutParams)childParameters).topMargin;
                        bottom = ((ViewGroup.MarginLayoutParams)childParameters).bottomMargin;
                    }
                    int height = childParameters.height >= 0 ? childParameters.height
                            : Math.max(child.getMeasuredHeight(), Math.max(child.getMinimumHeight(),
                                    child instanceof TextView ? ((TextView)child).getMinHeight() : 0));
                    if (vertical) content += Math.max(0, top + height + bottom);
                    else if (group instanceof LinearLayout) content = Math.max(content, top + height + bottom);
                    else content = Math.max(content, Math.max(top, child.getTop() - group.getPaddingTop()) + height + bottom);
                }
                int minimum = group.getPaddingTop() + content + group.getPaddingBottom();
                if (group.getMinimumHeight() < minimum) group.setMinimumHeight(minimum);
            }
        }
    }

    private static void buildCall(final Card card) throws Exception {
        final HyperOsResources r = asset(card.context, "call");
        card.resources = r;
        card.incoming = HyperOsIncomingCalls.isIncoming(card.item);
        final View body = r.inflate(card.incoming ? "call_focus_incoming_notification_view"
                : "call_focus_active_notification_view", card.container);
        margin(find(r, body, "caller_layout"), true, 14);
        margin(find(r, body, "caller_layout"), false, 10);
        margin(find(r, body, "answer_layout"), false, 14);
        margin(find(r, body, card.incoming ? "reject_call" : "audio"), false, 10);
        gone(r, body, "caller_sim"); gone(r, body, "rtt_text"); gone(r, body, "auto_disconnect_timer");
        gone(r, body, "caller_active_time_layout_aod"); gone(r, body, "phone_relay_device");
        gone(r, body, "phone_relay_device_aod"); gone(r, body, "call_info");
        final TextView title = (TextView)find(r, body, card.incoming ? "caller_name" : "caller_active_name");
        final TextView caption = (TextView)find(r, body, card.incoming ? "caller_info" : "caller_active_time");
        size(title, 18); size(caption, 14);
        // The producer calls setSingleLine(true) for ordinary caller names after
        // inflation. Its RemoteViews update overrides the incoming XML default.
        title.setSingleLine(true);
        card.bindings.add(new Binding() {
            @Override public void update(long now) {
                text(title, card.item.title, WHITE);
                CharSequence value = card.incoming ? card.item.subtitle
                        : card.item.hasChronometer() ? time(card.item, now) : card.item.subtitle;
                text(caption, value, BODY);
            }
        });
        if (card.incoming) {
            callButton(card, body, "reject_call", "ic_focus_reject_dark", 0);
            callButton(card, body, "answer_call", "ic_focus_answer_dark", 1);
        } else {
            int speaker = actionIndex(card.item, 10, false);
            int hangup = actionIndex(card.item, 9, true);
            callButton(card, body, "audio", "ic_focus_speaker_dark", speaker);
            callButton(card, body, "reject_active_call", "ic_focus_reject_dark", hangup);
            final View speakerView = find(r, body, "audio");
            card.bindings.add(new Binding() {
                @Override public void update(long now) {
                    int index = actionIndex(card.item, 10, false);
                    boolean active = index >= 0 && card.item.actions.get(index).active;
                    Drawable image = drawable(r, active ? "ic_focus_speaker_on_dark" : "ic_focus_speaker_dark");
                    if (speakerView instanceof ImageView) ((ImageView)speakerView).setImageDrawable(image);
                }
            });
            card.ticking = card.item.hasChronometer() && card.item.pausedMs < 0;
        }
        if (card.incoming) {
            View controls = find(r, body, "answer_layout");
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams)controls.getLayoutParams();
            int extent = px(r, "incall_focus_notification_button_size", 44);
            body.setMinimumHeight(extent + margins.topMargin + margins.bottomMargin);
        }
        card.add(body);
    }
    private static int actionIndex(IslandItem item, int glyph, boolean destructive) {
        for (int i = 0; i < item.actions.size(); i++) if (item.actions.get(i).glyph == glyph) return i;
        for (int i = 0; i < item.actions.size(); i++) if (item.actions.get(i).destructive == destructive) return i;
        return -1;
    }
    private static void callButton(Card card, View parent, String name, String icon, int index) {
        View view = find(card.resources, parent, name);
        if (view instanceof ImageView) {
            ((ImageView)view).setImageDrawable(drawable(card.resources, icon));
            ViewGroup.LayoutParams parameters = view.getLayoutParams();
            parameters.width = parameters.height = px(card.resources, "incall_focus_notification_button_size", 44);
            view.setLayoutParams(parameters);
        }
        ActionBinding binding = new ActionBinding(card, view, index, null);
        binding.update(0); card.bindings.add(binding); card.buttons.add(binding);
    }

    private static final class ActionBinding implements Binding, View.OnClickListener {
        final Card card;
        final View view;
        final int index;
        final JSONObject specification;
        final IslandItem.Action nativeAction;
        IslandItem.Action action;
        ActionBinding(Card card, View view, int index, JSONObject specification) {
            this.card = card; this.view = view; this.index = index; this.specification = specification;
            this.nativeAction = card.template != null && card.template.registered
                    ? resolveAction(card, index, specification) : null;
            if (view != null) {
                view.setOnClickListener(this);
                view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
                    }
                });
            }
        }
        @Override public void update(long now) {
            action = card.template != null && card.template.registered ? nativeAction
                    : specification != null && specification.optBoolean("__torchAction", false) ? resolveAction(card, index, specification)
                    : index >= 0 && index < card.item.actions.size() ? card.item.actions.get(index) : null;
            if (view == null) return;
            boolean enabled = action != null && (action.intent != null || action.runnable != null);
            view.setEnabled(enabled);
            view.setVisibility(action == null ? View.GONE : View.VISIBLE);
            if (action != null) view.setContentDescription(action.title);
            view.setAlpha(enabled ? 1 : 0.38f);
        }
        boolean ready() {
            return view != null && action != null && (action.intent != null || action.runnable != null)
                    && view.getVisibility() == View.VISIBLE && view.isEnabled()
                    && view.getWidth() > 0 && view.getHeight() > 0 && view.hasOnClickListeners();
        }
        @Override public void onClick(View clicked) {
            update(SystemClock.elapsedRealtime());
            if (ready() && card.host != null) card.host.onItemAction(card.item, action);
        }
    }
    private static IslandItem.Action resolveAction(Card card, int index, JSONObject specification) {
        if (specification != null && specification.optBoolean("__torchAction", false)) {
            if (card.item.torchToggle == null) return null;
            return new IslandItem.Action(card.item.torchOn ? "Turn off" : "Turn on", null, null, card.item.torchToggle, false);
        }
        if (specification != null && specification.has("__itemAction")) {
            int mapped = specification.optInt("__itemAction", -1);
            return mapped >= 0 && mapped < card.item.actions.size() ? card.item.actions.get(mapped) : null;
        }
        if (specification != null && card.template != null && card.template.actions != null) {
            String key = string(specification, "action");
            if (key != null) {
                try {
                    Notification.Action nativeAction = card.template.actions.getParcelable(key, Notification.Action.class);
                    if (nativeAction != null) {
                        String semantic = nativeAction.getExtras().getString("icon_name");
                        IslandItem.Action result = new IslandItem.Action(nativeAction.title,
                                nativeAction.getIcon() == null ? null : nativeAction.getIcon().loadDrawable(card.context),
                                nativeAction.actionIntent, null, "action_hangup".equals(semantic) || "action_record_end".equals(semantic));
                        result.glyph = HyperOsActionSemantics.glyph(card.item.packageName, null, semantic);
                        return result;
                    }
                } catch (Throwable ignored) {}
                return null;
            }
        }
        if (card.template != null && card.template.registered) return null;
        return index >= 0 && index < card.item.actions.size() ? card.item.actions.get(index) : null;
    }

    private static JSONObject adapt(IslandItem item) throws Exception {
        JSONObject result = new JSONObject();
        if (item.recorder || item.kind == IslandItem.Kind.RECORDING || item.kind == IslandItem.Kind.TIMER) {
            HyperOsNative.ClockCardPlan plan=item.kind==IslandItem.Kind.TIMER?HyperOsNative.clockCardPlan(item):null;
            String source = item.kind == IslandItem.Kind.TIMER ? item.chronoCountDown ? "hourglass_big" : "stopwatch_big" : "voiceWaveBig";
            JSONObject animation = new JSONObject().put("src", plan==null?source:plan.expanded).put("autoplay", plan==null?item.pausedMs < 0:plan.autoplay)
                    .put("loop", true).put("type", plan==null?2:plan.type);
            JSONObject main = new JSONObject().put("animIconInfo", animation);
            if (item.hasChronometer()&&(plan==null||plan.grammar==0)) main.put("__itemTimer", true);
            else main.put("title", plan!=null&&plan.grammar==1?item.clockText:item.title).put("content", item.subtitle);
            if (item.recorderSaved) main.put("title", item.title).remove("__itemTimer");
            result.put("animTextInfo", main);
        } else if (item.cardImage != null || item.art != null) {
            result.put("coverInfo", new JSONObject().put("title", item.title).put("content", item.subtitle));
        } else if (item.cardIcon != null || item.icon != null || item.torch) {
            result.put("iconTextInfo", new JSONObject().put("title", item.title).put("content", item.subtitle));
        } else {
            result.put("baseInfo", new JSONObject().put("type", 2).put("title", item.title)
                    .put("content", item.subtitle).put("extraTitle", item.overline));
        }
        if (!item.actions.isEmpty()) {
            JSONArray actions = new JSONArray();
            boolean textButtons = false;
            for (int i = 0; i < item.actions.size(); i++) {
                IslandItem.Action action = item.actions.get(i);
                textButtons |= action.glyph == 0 && action.icon == null;
                actions.put(new JSONObject().put("__itemAction", i));
            }
            result.put(textButtons ? "textButton" : "actions", actions);
        }
        if (item.progress >= 0) result.put("progressInfo", new JSONObject().put("progress", Math.round(item.progress * 100))
                .put("colorProgress", "#3482FF").put("colorProgressDark", "#4788FF"));
        if (item.torch) {
            JSONObject text = result.optJSONObject("iconTextInfo");
            if (text != null) text.put("title", "Flashlight").put("content", item.torchOn ? "Flashlight is on" : "Flashlight is off")
                    .put("__torchIcon", true).put("animIconInfo", new JSONObject().put("src", "doubleCharge").put("type", 2));
            if (item.torchToggle != null) result.put("textButton", new JSONArray().put(new JSONObject()
                    .put("__torchAction", true).put("type", 2).put("actionTitle", item.torchOn ? "Turn off" : "Turn on")
                    .put("actionTitleColor", "#FFFFFF").put("actionBgColor", "#1EFFFFFF").put("actionBgPressColor", "#24FFFFFF")));
        }
        if (item.key.startsWith("hyperos:bluetooth:") && item.ringPercent >= 0) {
            JSONObject main = result.optJSONObject("iconTextInfo");
            if (main == null) main = result.optJSONObject("baseInfo");
            if (main != null) main.put("subContent", item.ringPercent + "%");
        }
        return result;
    }

    private static void buildGeneric(Card card) throws Exception {
        HyperOsResources r = card.resources;
        View body = r.inflate("focus_notification_template_standard", card.container);
        body.setBackground(null); body.setClipToOutline(false);
        FrameLayout a = (FrameLayout)find(r, body, "area_a");
        FrameLayout c = (FrameLayout)find(r, body, "area_c");
        FrameLayout d = (FrameLayout)find(r, body, "area_d");
        JSONObject root = card.template.json;
        String main = null;
        for (String key : AREA_A) if (root.optJSONObject(key) != null) { main = key; break; }
        if (main == null) throw new IllegalArgumentException("Native template has no area A");
        JSONObject mainData = root.optJSONObject(main);
        if (("baseInfo".equals(main) || "chatInfo".equals(main)) && TextUtils.isEmpty(string(mainData, "title")))
            throw new IllegalArgumentException("Native primary title is missing");
        module(card, a, main, root.optJSONObject(main));
        JSONArray actions = root.optJSONArray("actions");
        if (actions != null) actionModule(card, c, actions);
        else if (root.optJSONObject("picInfo") != null) module(card, c, "picInfo", root.optJSONObject("picInfo"));
        else c.setVisibility(View.GONE);
        if (root.optJSONArray("textButton") != null) textActions(card, d, root.optJSONArray("textButton"));
        else if (root.optJSONObject("highlightInfoV3") != null) module(card, d, "highlightInfoV3", root.optJSONObject("highlightInfoV3"));
        else if (root.optJSONObject("multiProgressInfo") != null) module(card, d, "multiProgressInfo", root.optJSONObject("multiProgressInfo"));
        else if (root.optJSONObject("competitionIconTextInfo") != null) module(card, d, "competitionIconTextInfo", root.optJSONObject("competitionIconTextInfo"));
        else if (root.optJSONObject("progressInfo") != null) module(card, d, "progressInfo", root.optJSONObject("progressInfo"));
        else if (root.optJSONObject("hintInfo") != null) module(card, d, "hintInfo", root.optJSONObject("hintInfo"));
        else d.setVisibility(View.GONE);
        if (!card.template.registered && card.item.metrics != null && !card.item.metrics.isEmpty()) metricRows(card, d);
        card.add(body);
    }

    private static void module(final Card card, FrameLayout area, final String type, final JSONObject data) throws Exception {
        final HyperOsResources r = card.resources;
        String layout;
        switch (type) {
            case "animTextInfo": layout = "focus_notification_module_animation_text"; break;
            case "coverInfo": layout = "focus_notification_module_cover"; break;
            case "iconTextInfo": layout = "focus_notification_module_new_image_text"; break;
            case "baseInfo": layout = "focus_notification_module_text_" + (data.optInt("type", 1) == 2 ? 2 : 1); break;
            case "highlightInfo": layout = "focus_notification_module_image_text_highlight"; break;
            case "chatInfo": layout = "focus_notification_module_image_text_im"; break;
            case "picInfo": layout = "focus_notification_module_" + (data.optInt("type", 1) == 5 ? "mark_text_image"
                    : "mark_" + Math.max(1, Math.min(4, data.optInt("type", 1)))); break;
            case "highlightInfoV3": layout = "focus_notification_module_button_5"; break;
            case "multiProgressInfo": layout = "focus_notification_module_multi_progress"; break;
            case "competitionIconTextInfo": layout = "focus_notification_module_competition"; break;
            case "progressInfo": layout = "focus_notification_module_progress"; break;
            case "hintInfo": layout = "focus_notification_module_button_" + (data.optInt("type", 1) == 2 ? 2 : 3); break;
            default: throw new IllegalArgumentException("Unknown native module " + type);
        }
        final View body = r.inflate(layout, area);
        body.setVisibility(View.VISIBLE);
        area.setVisibility(View.VISIBLE); area.addView(body);
        final JSONObject animation = data.optJSONObject("animIconInfo");
        final boolean flashlightShader = "iconTextInfo".equals(type) && animation != null && animation.optInt("type", 0) == 2;
        card.bindings.add(new Binding() {
            boolean bound;
            @Override public void update(long now) {
                if (data.optBoolean("__torchIcon", false)) {
                    if (!bound) bindFields(card, body, data, now);
                    text(r, body, "focus_content", card.item.torchOn ? "Flashlight is on" : "Flashlight is off", BODY);
                } else if (!bound) bindFields(card, body, data, now);
                if (!bound && "competitionIconTextInfo".equals(type)) bindCompetition(card, body, data);
                if ("progressInfo".equals(type)) bindProgress(card, body, data);
                if ("multiProgressInfo".equals(type)) bindMultiProgress(card, body, data);
                if (!bound && "picInfo".equals(type)) {
                    Drawable picture = picture(card, string(data, "picDark") == null ? string(data, "pic") : string(data, "picDark"), card.item.icon);
                    image(find(r, body, "focus_mark_small_icon"), picture);
                    image(find(r, body, "focus_mark_large_icon"), picture);
                }
                if (!bound && "coverInfo".equals(type)) image(find(r, body, "focus_profile"), picture(card, string(data, "picCover"), card.item.cardImage != null ? card.item.cardImage : card.item.art == null ? null : new android.graphics.drawable.BitmapDrawable(r.getResources(), card.item.art)));
                if (!bound && "chatInfo".equals(type)) {
                    image(find(r, body, "focus_profile"), picture(card, string(data, "picProfileDark") == null
                            ? string(data, "picProfile") : string(data, "picProfileDark"), card.item.cardImage));
                    image(find(r, body, "focus_app_icon"), card.item.appIcon);
                }
                if (!bound && "iconTextInfo".equals(type) && !flashlightShader) image(find(r, body, "focus_profile"), data.optBoolean("__torchIcon", false)
                        ? drawable(r, "mi_flashlight_on_notification_icon") : card.item.cardIcon != null ? card.item.cardIcon : card.item.icon);
                if (!bound) image(find(r, body, "focus_function_icon"), picture(card,
                        string(data, "picFunctionDark") == null ? string(data, "picFunction") : string(data, "picFunctionDark"), null));
                bound = true;
            }
        });
        if (flashlightShader) {
            View target = find(r, body, "focus_flash");
            if (!(target instanceof HyperOsFlashlightEffect)) throw new IllegalStateException("Missing original flashlight shader delegate");
            HyperOsFlashlightEffect effect = (HyperOsFlashlightEffect)target;
            gone(r, body, "focus_profile");
            gone(r, body, "focus_icon_container");
            gone(r, body, "focus_video");
            effect.setNotificationOffsets();
            effect.setLit(!card.item.torch || card.item.torchOn);
            effect.setAnimation(r, string(animation, "src"), false, false, -1);
            card.bindings.add(now -> effect.setLit(!card.item.torch || card.item.torchOn));
            effect.setOwnerVisible(card.ownerVisible);
            effect.setVisibility(View.VISIBLE);
            card.animated.add(effect);
        } else if (animation != null) {
            View target = find(r, body, "focus_animation");
            if (target == null) target = find(r, body, "focus_flash");
            if (target instanceof HyperOsFlashlightEffect) target = null;
            if(animation.optInt("type",2)==0){
                if(target!=null)target.setVisibility(View.GONE);
                String name=string(animation,"srcDark");if(name==null)name=string(animation,"src");
                if("alarmBig".equals(name))name="alarm_big";if("alarmSmall".equals(name))name="alarm_small";
                image(find(r,body,"focus_animation_static"),drawable(r,name));
            }else if (target instanceof AnimationTarget) {
                target.setVisibility(View.VISIBLE);
                AnimationTarget animated = (AnimationTarget)target;
                animated.setAnimation(r, string(animation, "srcDark") == null ? string(animation, "src") : string(animation, "srcDark"),
                        animation.optBoolean("autoplay", false), animation.optBoolean("loop", true), animation.optInt("number", -1));
                card.animated.add(animated);
                gone(r, body, "focus_animation_static");
            }
        }
        bindClock(card, body, data, data.optJSONObject("timerInfo"), "chronometer");
        if ("hintInfo".equals(type)) bindClock(card, body, data, data.optJSONObject("timerInfo"), "chronometer_hint");
        if ("competitionIconTextInfo".equals(type)) {
            bindClock(card, body, data, data.optJSONObject("timerInfo"), "competition_timer");
            bindClock(card, body, data, data.optJSONObject("subTimerInfo"), "competition_sub_timer");
        }
        JSONObject action = data.optJSONObject("actionInfo");
        if (action != null) textAction(card, body, action, 0, "focus_button_container_action");
        else if ("hintInfo".equals(type) || "highlightInfoV3".equals(type) || ("picInfo".equals(type) && data.optInt("type", 1) == 4)) {
            gone(r, body, "focus_button_container");
            gone(r, body, "focus_button_container_action");
        }
    }

    private static void bindFields(Card card, View body, JSONObject data, long now) {
        String[][] fields = {{"focus_title","title","colorTitle"},{"focus_content","content","colorContent"},
                {"focus_subtitle","subTitle","colorSubTitle"},{"focus_extra_title","extraTitle","colorExtraTitle"},
                {"focus_special_title","specialTitle","colorSpecialTitle"},{"focus_sub_content","subContent","colorSubContent"},
                {"focus_primary_text","primaryText","primaryColor"},{"focus_secondary_text","secondaryText","secondaryColor"},
                {"focus_highLight_text","highLightText","highLightTextColor"},{"focus_small_title","title","colorTitle"},
                {"focus_front_content","content","colorContent"},{"focus_small_subtitle","subTitle","colorSubTitle"},
                {"focus_front_sub_content","subContent","colorSubContent"},{"focus_text_title","specialTitle","colorSpecialTitle"}};
        for (String[] field : fields) text(card.resources, body, field[0], html(string(data, field[1])), color(data, field[2], null));
        goneIfEmpty(card.resources, body, "focus_subtitle_divider", string(data, "subTitle"));
        goneIfEmpty(card.resources, body, "focus_extra_title_divider", string(data, "extraTitle"));
        goneIfEmpty(card.resources, body, "focus_function_text_divider", string(data, "subContent"));
        if (data.optBoolean("__itemTimer", false)) {
            gone(card.resources, body, "focus_title");
            if (!card.item.recorderSaved) text(card.resources, body, "focus_content", card.item.kind == IslandItem.Kind.TIMER ? card.item.subtitle : null, BODY);
        }
    }
    private static void goneIfEmpty(HyperOsResources r, View body, String id, String value) {
        View view = find(r, body, id); if (view != null) view.setVisibility(value == null || value.length() == 0 ? View.GONE : View.VISIBLE);
    }
    private static Drawable picture(Card card, String key, Drawable fallback) {
        if (key == null || card.template == null || card.template.pictures == null) return fallback;
        try {
            Object value = card.template.pictures.get(key);
            if (value instanceof Icon) return ((Icon)value).loadDrawable(card.context);
            if (value instanceof Bitmap) return new android.graphics.drawable.BitmapDrawable(card.context.getResources(), (Bitmap)value);
        } catch (Throwable ignored) {}
        return fallback;
    }
    private static void bindClock(final Card card, View body, JSONObject data, JSONObject timer, String id) {
        final View target = find(card.resources, body, id);
        if (!(target instanceof TextView)) return;
        final boolean fromItem = data.optBoolean("__itemTimer", false);
        if (timer == null && !fromItem) { target.setVisibility(View.GONE); return; }
        if (timer != null && timer.optInt("timerType", 0) == 0) { target.setVisibility(View.GONE); return; }
        final NativeClock clock = timer == null ? null : new NativeClock(timer, SystemClock.elapsedRealtime());
        card.ticking |= fromItem ? card.item.pausedMs < 0 : clock.running;
        card.bindings.add(new Binding() {
            @Override public void update(long now) { text(target, fromItem ? time(card.item, now) : clock.text(now), null); }
        });
    }
    private static final class NativeClock {
        final boolean running;
        final boolean countDown;
        final long reference;
        final long base;
        NativeClock(JSONObject timer, long nowElapsed) {
            int mode = timer.optInt("timerType", 0);
            this.running = mode == 1 || mode == -1;
            this.countDown = mode < 0;
            this.reference = nowElapsed;
            long when = timer.optLong("timerWhen", System.currentTimeMillis());
            long current = timer.optLong("timerSystemCurrent", System.currentTimeMillis());
            this.base = running ? nowElapsed + when - System.currentTimeMillis() : countDown ? when - current : current - when;
        }
        String text(long now) { return duration(running ? countDown ? base - now : now - base : base); }
    }
    private static String time(IslandItem item, long now) {
        return duration(item.pausedMs >= 0 ? item.pausedMs : item.chronoCountDown ? item.chronoBase - now : now - item.chronoBase);
    }
    private static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        long hours = seconds / 3600, minutes = (seconds / 60) % 60, rest = seconds % 60;
        return hours > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.getDefault(), "%02d:%02d", minutes, rest);
    }
    private static void metricRows(final Card card, FrameLayout area) throws Exception {
        LinearLayout stack = new LinearLayout(card.resources); stack.setOrientation(LinearLayout.VERTICAL);
        List<View> existing = new ArrayList<View>();
        for (int i = 0; i < area.getChildCount(); i++) existing.add(area.getChildAt(i));
        area.removeAllViews(); area.addView(stack); area.setVisibility(View.VISIBLE);
        for (View child : existing) stack.addView(child);
        for (int index = 0; index < card.item.metrics.size(); index++) {
            final int position = index;
            final View row = card.resources.inflate("focus_notification_module_button_2", stack);
            row.setVisibility(View.VISIBLE);
            gone(card.resources, row, "focus_button_container");
            gone(card.resources, row, "subtitle_hint");
            gone(card.resources, row, "chronometer_hint");
            stack.addView(row);
            card.ticking |= card.item.metrics.get(index).running();
            card.bindings.add(new Binding() {
                @Override public void update(long now) {
                    if (card.item.metrics == null || position >= card.item.metrics.size()) { row.setVisibility(View.GONE); return; }
                    IslandItem.Metric metric = card.item.metrics.get(position);
                    text(card.resources, row, "focus_front_content", metric.label, BODY);
                    CharSequence value = metricText(metric, now);
                    text(card.resources, row, "focus_small_title", value == null ? "—" : value, WHITE);
                }
            });
        }
    }
    private static CharSequence metricText(IslandItem.Metric metric, long now) {
        if (metric.type != IslandItem.Metric.TIME) return metric.fixed;
        long ms = metric.pausedMs >= 0 ? metric.pausedMs : metric.countDown ? metric.zeroTime - now : now - metric.zeroTime;
        if (metric.format != 1) return duration(ms);
        long seconds = Math.max(0, ms) / 1000;
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : String.format(Locale.getDefault(), "%d h %02d min", minutes / 60, minutes % 60);
    }

    private static void actionModule(Card card, FrameLayout area, JSONArray actions) throws Exception {
        LinearLayout stack = new LinearLayout(card.resources); stack.setOrientation(LinearLayout.VERTICAL);
        area.setVisibility(actions.length() == 0 ? View.GONE : View.VISIBLE); area.addView(stack);
        for (int offset = 0; offset < actions.length(); offset += 3) {
            View row = card.resources.inflate("focus_notification_module_button_1", stack);
            row.setVisibility(View.VISIBLE); stack.addView(row);
            for (int slot = 0; slot < 3; slot++) {
                int index = offset + slot;
                JSONObject specification = actions.optJSONObject(index);
                int type = specification == null ? 0 : specification.optInt("type", 0);
                String iconId = "focus_button_icon" + (slot + 1);
                String progressId = "focus_button_progress" + (slot + 1);
                View target = find(card.resources, row, type == 1 ? progressId : type == 2 ? "focus_button_container_action" : iconId);
                gone(card.resources, row, type == 1 ? iconId : progressId);
                if (index >= actions.length()) { if (target != null) target.setVisibility(View.GONE); continue; }
                if (type == 2) { textAction(card, row, specification, index, "focus_button_container_action"); continue; }
                final ActionBinding binding = new ActionBinding(card, target, index, specification);
                binding.update(0); card.buttons.add(binding); card.bindings.add(binding);
                final JSONObject spec = specification;
                card.bindings.add(new Binding() {
                    IslandItem.Action previous;
                    Drawable cached;
                    boolean active;
                    int glyph;
                    @Override public void update(long now) {
                        if (binding.action != previous || (binding.action != null
                                && (binding.action.active != active || binding.action.glyph != glyph))) {
                            previous = binding.action;
                            active = previous != null && previous.active;
                            glyph = previous == null ? 0 : previous.glyph;
                            cached = actionImage(card, previous, spec);
                            if (binding.view instanceof CircleControl) ((CircleControl)binding.view).icon.setImageDrawable(cached);
                            else if (binding.view instanceof ImageView) ((ImageView)binding.view).setImageDrawable(cached);
                        }
                        if (binding.view instanceof CircleControl) {
                            JSONObject progress = spec == null ? null : spec.optJSONObject("progressInfo");
                            CircleControl control = (CircleControl)binding.view;
                            control.progress = progress == null ? card.item.progress : progress.optInt("progress", 0) / 100f;
                            control.progressColor = progress == null ? 0xff4788ff : color(progress, "colorProgress", 0xff4788ff);
                            control.endColor = progress == null ? 0x29ffffff : color(progress, "colorProgressEnd", 0x29ffffff);
                            control.ccw = progress != null && progress.optBoolean("isCCW", false);
                            control.invalidate();
                        }
                    }
                });
            }
        }
    }
    private static Drawable actionImage(Card card, IslandItem.Action action, JSONObject spec) {
        if (action == null) return null;
        String explicit = string(spec, "actionIconDark");
        if (explicit == null) explicit = string(spec, "actionIcon");
        Drawable provided = picture(card, explicit, null);
        if (provided != null) return provided;
        String name = null;
        switch (action.glyph) {
            case 1: name = card.item.kind == IslandItem.Kind.TIMER && card.item.chronoCountDown ? "restart_timer_v3" : "restart"; break;
            case 2: name = card.item.kind == IslandItem.Kind.TIMER && card.item.chronoCountDown ? "pause_timer_v3" : "pause"; break;
            case 6: name = card.item.recorder || card.item.kind == IslandItem.Kind.RECORDING ? "record_end_v3" : "close_v3"; break;
            case 9: name = "hangup_v2"; break;
            case 10: name = action.active ? "speaker_during_v2" : "speaker_v2"; break;
            case 11: name = "mark_timer"; break;
            case 12: name = "end_v3"; break;
            case 13: name = "close_v3"; break;
            case 14: name = "done_v3"; break;
            case 15: name = "later_v3"; break;
        }
        if (explicit != null && explicit.startsWith("action_")) {
            String semantic = explicit.substring(7);
            if ("pause_timer".equals(semantic)) name = "pause_timer_v3";
            else if ("restart_timer".equals(semantic)) name = "restart_timer_v3";
            else if ("record_end".equals(semantic)) name = "record_end_v3";
            else if ("done".equals(semantic) || "end".equals(semantic) || "close".equals(semantic) || "later".equals(semantic)) name = semantic + "_v3";
            else name = semantic;
        }
        Drawable nativeImage = drawable(card.resources, name);
        return nativeImage == null ? action.icon : nativeImage;
    }
    private static void textActions(Card card, FrameLayout area, JSONArray actions) throws Exception {
        LinearLayout stack = new LinearLayout(card.resources); stack.setOrientation(LinearLayout.VERTICAL);
        area.addView(stack); area.setVisibility(actions.length() == 0 ? View.GONE : View.VISIBLE);
        for (int offset = 0; offset < actions.length(); offset += 3) {
            View row = card.resources.inflate("focus_notification_module_button_4", stack); row.setVisibility(View.VISIBLE); stack.addView(row);
            for (int slot = 0; slot < 3; slot++) {
                int index = offset + slot;
                String target = "focus_button_container_action" + (slot + 1);
                if (index >= actions.length()) gone(card.resources, row, target);
                else textAction(card, row, actions.optJSONObject(index), index, target);
            }
        }
    }
    private static void textAction(final Card card, final View parent, final JSONObject spec, int index, String targetId) {
        View target = find(card.resources, parent, targetId);
        if (target == null) target = find(card.resources, parent, "focus_button_container");
        final ActionBinding binding = new ActionBinding(card, target, index, spec);
        card.buttons.add(binding); card.bindings.add(binding);
        card.bindings.add(new Binding() {
            Integer lastBackground;
            Integer lastPressed;
            @Override public void update(long now) {
                if (binding.view == null) return;
                CharSequence title = (spec != null && spec.optBoolean("__torchAction", false)) || string(spec, "actionTitle") == null ? binding.action == null ? null : binding.action.title
                        : html(string(spec, "actionTitle"));
                text(card.resources, binding.view, "focus_button_title", title, color(spec, "actionTitleColor", WHITE));
                Integer background = color(spec, "actionBgColor", null);
                Integer press = background == null ? null : color(spec, "actionBgPressColor", pressedColor(background));
                if (background != null && (!background.equals(lastBackground) || !press.equals(lastPressed))) {
                    GradientDrawable normal = new GradientDrawable(); normal.setCornerRadius(103 * card.resources.getResources().getDisplayMetrics().density); normal.setColor(background);
                    GradientDrawable pressed = new GradientDrawable(); pressed.setCornerRadius(normal.getCornerRadius());
                    pressed.setColor(press);
                    StateListDrawable selector = new StateListDrawable();
                    selector.addState(new int[]{android.R.attr.state_pressed}, pressed); selector.addState(new int[0], normal);
                    binding.view.setBackground(selector);
                    lastBackground = background; lastPressed = press;
                }
            }
        });
    }
    private static int pressedColor(int color) {
        return Color.argb((int)(255 * .14f + Color.alpha(color) * .86f),
                (int)(255 * .14f + Color.red(color) * .86f), (int)(255 * .14f + Color.green(color) * .86f),
                (int)(255 * .14f + Color.blue(color) * .86f));
    }
    private static void bindProgress(Card card, View body, JSONObject data) {
        int progress = card.template.registered ? data.optInt("progress", 0) : Math.round(card.item.progress * 100);
        int reached = color(data, "colorProgress", 0xff4788ff);
        for (String id : new String[]{"focus_progress_info1", "focus_progress_info2"}) {
            View target = find(card.resources, body, id);
            if (target instanceof ProgressBar) {
                ProgressBar bar = (ProgressBar)target; bar.setMax(100); bar.setProgress(Math.max(0, Math.min(100, progress)));
                bar.setProgressTintList(ColorStateList.valueOf(reached));
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(color(data, "colorProgressEnd", 0x29ffffff)));
            }
        }
        image(find(card.resources, body, "progress_icon"), picture(card, string(data, "picForward"), null));
        image(find(card.resources, body, "progress_point1"), picture(card, string(data, "picMiddle"), null));
        image(find(card.resources, body, "progress_point2"), picture(card, string(data, "picEnd"), null));
    }
    private static void bindMultiProgress(Card card, View body, JSONObject data) {
        View target = find(card.resources, body, "multi_progress");
        if (target instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar)target; progress.setMax(100); progress.setProgress(data.optInt("progress", 0));
            progress.setProgressTintList(ColorStateList.valueOf(color(data, "color", 0xff4788ff)));
        }
    }
    private static void bindCompetition(Card card, View body, JSONObject data) {
        String[][] fields = {{"competition_title","title","colorTitle"},{"competition_sub_title","subTitle","colorSubTitle"},
                {"competition_content","content","colorContent"},{"competition_sub_content","subContent","colorSubContent"},
                {"competition_field","field","colorExtraTitle"},{"competition_sub_field","subField","colorExtraTitle"},
                {"competition_note","note","colorContent"},{"competition_sub_note","subNote","colorContent"}};
        for (String[] field : fields) text(card.resources, body, field[0], html(string(data, field[1])), color(data, field[2], null));
        image(find(card.resources, body, "competition_icon"), picture(card, string(data, "compIcon"), card.item.leftImage));
        image(find(card.resources, body, "competition_sub_icon"), picture(card, string(data, "subCompIcon"), card.item.rightImage));
    }

    private static void bindTorchStrength(final Card card) {
        if (card.item.torchMax <= 1 || card.item.torchSet == null) return;
        final HyperOsResources r = card.resources;
        View original = card.container.getChildAt(0);
        if (original == null) return;
        card.container.removeView(original);
        LinearLayout column = new LinearLayout(r); column.setOrientation(LinearLayout.VERTICAL);
        column.addView(original, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(r); controls.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * r.getResources().getDisplayMetrics().density);
        controls.setPadding(pad, 0, pad, pad / 2);
        LinearLayout heading = new LinearLayout(r); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(r); label.setText("Strength"); label.setTextSize(12); label.setTextColor(0xe5ffffff);
        final TextView value = new TextView(r); value.setTextSize(12); value.setTextColor(0xb3ffffff); value.setGravity(Gravity.END);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(value, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        final HyperSeekBar strength = new HyperSeekBar(r, null);
        strength.setContentDescription("Flashlight strength"); strength.setMax(card.item.torchMax);
        strength.setProgress(card.item.torchOn ? card.item.torchLevel : 0);
        controls.addView(strength, new LinearLayout.LayoutParams(-1, Math.round(42 * r.getResources().getDisplayMetrics().density)));
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int sent = -1;
            @Override public void onStartTrackingTouch(SeekBar bar) { sent = bar.getProgress(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { sent = -1; }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user || card.released || card.item.torchSet == null || sent == progress) return;
                sent = progress; card.item.torchSet.accept(progress);
            }
        });
        card.disposals.add(() -> strength.setOnSeekBarChangeListener(null));
        card.bindings.add(now -> {
            int maximum = Math.max(1, card.item.torchMax);
            strength.setEnabled(card.item.torchSet != null && maximum > 1);
            if (!strength.isPressed()) {
                strength.setMax(maximum);
                strength.setProgress(card.item.torchOn ? Math.max(1,Math.min(maximum,card.item.torchLevel)) : 0);
            }
            value.setText(card.item.torchOn ? card.item.torchLevel + " / " + maximum : "Off");
        });
        column.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        card.add(column);
    }
    private static void buildMedia(final Card card) throws Exception {
        final HyperOsResources r = asset(card.context, "systemUi"); card.resources = r;
        final View body = r.inflate("miui_media_session_island", card.container);
        if (!(body instanceof MediaLayout)) throw new IllegalArgumentException("Missing source-backed media layout delegate");
        ((MediaLayout)body).resources = r;
        final HyperOsMusicBackground background = (HyperOsMusicBackground)find(r, body, "media_bg_view");
        if (background != null) {
            clip(background, px(r, "media_control_bg_radius", 26));
            card.disposals.add(background::close);
        }
        final View seamless = find(r, body, "media_seamless");
        if (seamless != null) seamless.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (!card.released && card.item.trailingTap != null) card.item.trailingTap.run(); }
        });
        if (seamless != null) card.disposals.add(new Runnable() { @Override public void run() { seamless.setOnClickListener(null); } });
        image(find(r, body, "icon"), card.item.appIcon);
        final ImageView album = (ImageView)find(r, body, "album_art_image");
        View albumContainer = find(r, body, "album_art");
        if (albumContainer != null) clip(albumContainer, px(r, "album_art_bg_radius", 12));
        View appIcon = find(r, body, "icon"); if (appIcon != null) clip(appIcon, px(r, "media_app_icon_bg_radius", 6));
        if (appIcon != null && appIcon.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)appIcon.getLayoutParams();
            params.gravity = Gravity.END | Gravity.BOTTOM;
            params.bottomMargin = px(r, "header_artist_margin_top", 4);
            params.setMarginEnd(params.bottomMargin);
            appIcon.setLayoutParams(params);
        }
        for (int slot = 0; slot < 5; slot++) {
            final int position = slot;
            final View button = find(r, body, "action" + slot);
            if (button == null) continue;
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View clicked) {
                    Runnable action = card.released ? null : mediaAction(card, position);
                    if (action != null) action.run();
                }
            });
            card.disposals.add(new Runnable() { @Override public void run() { button.setOnClickListener(null); } });
        }
        final SeekBar seek = (SeekBar)find(r, body, "media_progress_bar");
        if (seek != null) {
            final boolean[] cancelled={false};
            seek.setMax(10000);
            seek.setOnTouchListener((view,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)cancelled[0]=false;
                else if(event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)cancelled[0]=true;return false;});
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                boolean dragging;
                @Override public void onStartTrackingTouch(SeekBar view) { dragging = true; }
                @Override public void onStopTrackingTouch(SeekBar view) {
                    if (dragging && !cancelled[0] && card.item.mediaSeek != null && card.item.mediaDuration > 0)
                        card.item.mediaSeek.accept((long)(view.getProgress() / 10000.0 * card.item.mediaDuration));
                    dragging = false;
                }
                @Override public void onProgressChanged(SeekBar view, int value, boolean user) {}
            });
            card.disposals.add(new Runnable() { @Override public void run() { seek.setOnSeekBarChangeListener(null);seek.setOnTouchListener(null); } });
        }
        final Drawable previousIcon = drawable(r, "ic_media_prev");
        final Drawable nextIcon = drawable(r, "ic_media_next");
        final Drawable playIcon = drawable(r, "ic_media_play_button");
        final Drawable pauseIcon = drawable(r, "ic_media_pause_button");
        card.bindings.add(new Binding() {
            Bitmap previousArt;
            final Drawable[] icons = new Drawable[5];
            @Override public void update(long now) {
                if (background != null) background.bind(card.item.art, card.item.playing);
                text(r, body, "header_title", card.item.title, 0xe5ffffff);
                text(r, body, "header_artist", card.item.subtitle, 0x80ffffff);
                if (seamless != null) {
                    seamless.setVisibility(card.item.trailingTap == null ? View.GONE : View.VISIBLE);
                    seamless.setEnabled(card.item.trailingTap != null);
                    gone(r, body, "media_seamless_text");
                    seamless.setContentDescription(card.item.trailingText == null ? "Audio output" : card.item.trailingText);
                    if (card.item.rightImage != null) image(find(r, body, "media_seamless_image"), card.item.rightImage);
                }
                if (album != null && previousArt != card.item.art) {
                    previousArt = card.item.art; album.setImageBitmap(previousArt);
                }
                long elapsed = card.item.currentMediaPosition(now);
                text(r, body, "media_elapsed_time", elapsed >= 0 ? duration(elapsed) : null, 0x66ffffff);
                text(r, body, "media_total_time", card.item.mediaDuration > 0 ? duration(card.item.mediaDuration) : null, 0x66ffffff);
                if (seek != null) {
                    seek.setEnabled(card.item.mediaSeek != null && card.item.mediaDuration > 0);
                    if (!seek.isPressed() && card.item.mediaDuration > 0) seek.setProgress((int)Math.min(10000, Math.max(0, elapsed) * 10000 / card.item.mediaDuration));
                }
                for (int slot = 0; slot < 5; slot++) {
                    View button = find(r, body, "action" + slot);
                    if (button == null) continue;
                    Runnable callback = mediaAction(card, slot);
                    button.setVisibility(callback == null ? View.GONE : View.VISIBLE); button.setEnabled(callback != null);
                    Drawable icon = slot == 1 ? previousIcon : slot == 2 ? card.item.playing ? pauseIcon : playIcon : slot == 3 ? nextIcon : null;
                    if (slot == 0 && card.item.mediaCustom.size() > 0) icon = card.item.mediaCustom.get(0).icon;
                    if (slot == 4 && card.item.mediaCustom.size() > 1) icon = card.item.mediaCustom.get(1).icon;
                    if (button instanceof ImageView && icon != icons[slot]) {
                        icons[slot] = icon; ((ImageView)button).setImageDrawable(icon);
                    }
                    button.setContentDescription(slot == 1 ? "Previous" : slot == 2 ? card.item.playing ? "Pause" : "Play" : slot == 3 ? "Next" :
                            slot == 0 && card.item.mediaCustom.size() > 0 ? card.item.mediaCustom.get(0).title : slot == 4 && card.item.mediaCustom.size() > 1 ? card.item.mediaCustom.get(1).title : null);
                }
            }
        });
        card.ticking = card.item.playing;
        card.add(body);
    }
    private static Runnable mediaAction(final Card card, int slot) {
        final IslandItem item = card.item;
        if (slot == 1) return item.mediaPrev;
        if (slot == 2) return item.mediaPlayPause;
        if (slot == 3) return item.mediaNext;
        int custom = slot == 0 ? 0 : 1;
        if (custom >= item.mediaCustom.size()) return null;
        final IslandItem.Action action = item.mediaCustom.get(custom);
        if (action.intent == null && action.runnable == null) return null;
        return new Runnable() { @Override public void run() { if (!card.released && card.host != null) card.host.onItemAction(item, action); } };
    }
    private static void clip(View view, final int radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius); }
        });
        view.setClipToOutline(true);
    }

    /** Default animation target preserves an actual donor static contour until a renderer is bound. */
    private static final class StaticAnimationView extends ImageView implements AnimationTarget {
        StaticAnimationView(Context context, AttributeSet attributes) { super(context, attributes); setScaleType(ScaleType.FIT_CENTER); }
        @Override public void setAnimation(HyperOsResources r, String source, boolean autoplay, boolean loop, int number) {
            String name = "voiceWaveBig".equals(source) ? "voice_wave_big" : "voiceWaveSmall".equals(source) ? "voice_wave_small"
                    : "alarmBig".equals(source) ? "alarm_big" : "alarmSmall".equals(source) ? "alarm_small"
                    : "stopwatch_big".equals(source) ? "mark_timing_big" : source;
            setImageDrawable(drawable(r, name));
        }
        @Override public void step(long now) {}
        @Override public boolean active() { return false; }
        @Override public void release() { setImageDrawable(null); }
    }
    private static final class CircleControl extends FrameLayout {
        final ImageView icon;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF ring = new RectF();
        float progress;
        int progressColor = 0xff4788ff;
        int endColor = 0x29ffffff;
        boolean ccw;
        CircleControl(Context context, AttributeSet attributes) {
            super(context, attributes); setWillNotDraw(false);
            icon = new ImageView(context); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(7 * getResources().getDisplayMetrics().density); icon.setPadding(pad, pad, pad, pad);
            addView(icon, new FrameLayout.LayoutParams(-1, -1));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float stroke = 3.79f * getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            ring.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
            paint.setColor(endColor); canvas.drawArc(ring, -90, 360, false, paint);
            paint.setColor(progressColor); canvas.drawArc(ring, -90, (ccw ? -1 : 1) * 360 * Math.max(0, Math.min(1, progress)), false, paint);
        }
    }

    /** Source primary-mode track geometry; spring interpolation remains in the Rust core. */
    private static final class HyperSeekBar extends SeekBar {
        private static final String APP = "http://schemas.android.com/apk/res-auto";
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF track = new RectF();
        final double[] spring = new double[16], result = new double[6];
        final int minimumHeight, maximumHeight, normalAlpha, pressedAlpha;
        final int backgroundColor, foregroundColor, disabledForeground;
        double height, velocity, alpha, alphaVelocity;
        long previousFrame;
        boolean nativeUnavailable;
        HyperSeekBar(Context context, AttributeSet attributes) {
            super(context, attributes);
            setThumb(null); setBackground(null); setSplitTrack(false);
            minimumHeight = dimension(context, "miuix_appcompat_hyper_progress_seekbar_min_height", 6) & ~1;
            maximumHeight = dimension(context, "miuix_appcompat_hyper_progress_seekbar_height", 12) & ~1;
            normalAlpha = attributes == null ? 153 : attributes.getAttributeIntValue(APP, "progressAlpha", 153);
            pressedAlpha = attributes == null ? 230 : attributes.getAttributeIntValue(APP, "progressPressedAlpha", 230);
            backgroundColor = attributeColor(context, attributes, "backgroundPrimaryColor", 0x1affffff);
            foregroundColor = attributeColor(context, attributes, "foregroundPrimaryColor", 0xffffffff);
            disabledForeground = attributeColor(context, attributes, "foregroundPrimaryDisableColor", 0x4dffffff);
            height = minimumHeight; alpha = normalAlpha;
        }
        private static int dimension(Context context, String name, float fallback) {
            int id = context.getResources().getIdentifier(name, "dimen", context.getPackageName());
            return id == 0 ? Math.round(fallback * context.getResources().getDisplayMetrics().density) : context.getResources().getDimensionPixelSize(id);
        }
        private static int attributeColor(Context context, AttributeSet attributes, String name, int fallback) {
            if (attributes == null) return fallback;
            int id = attributes.getAttributeResourceValue(APP, name, 0);
            if (id != 0) return context.getResources().getColor(id, context.getTheme());
            String literal = attributes.getAttributeValue(APP, name);
            try { return literal == null ? fallback : Color.parseColor(literal); } catch (Throwable ignored) { return fallback; }
        }
        @Override protected synchronized void onDraw(Canvas canvas) {
            double targetHeight = isPressed() && isEnabled() ? maximumHeight : minimumHeight;
            double targetAlpha = isPressed() && isEnabled() ? pressedAlpha : normalAlpha;
            long frame = SystemClock.elapsedRealtimeNanos();
            double dt = previousFrame == 0 ? 1.0 / 60 : Math.min(.064, Math.max(0, (frame - previousFrame) * 1e-9));
            previousFrame = frame;
            boolean moving = height != targetHeight || velocity != 0 || alpha != targetAlpha || alphaVelocity != 0;
            if (moving && !nativeUnavailable) {
                spring[0] = height; spring[1] = targetHeight; spring[2] = velocity;
                spring[3] = .95; spring[4] = .35; spring[5] = 1; spring[6] = .1; spring[7] = 0;
                spring[8] = alpha; spring[9] = targetAlpha; spring[10] = alphaVelocity;
                spring[11] = .95; spring[12] = .35; spring[13] = 1; spring[14] = .1; spring[15] = 0;
                try {
                    moving = HyperOsNative.nativeSpringBatch(spring, 2, dt, result);
                    height = result[0]; velocity = result[1]; alpha = result[3]; alphaVelocity = result[4];
                } catch (LinkageError missingNativeInResourceProbe) { nativeUnavailable = true; }
            }
            if (nativeUnavailable) { height = targetHeight; alpha = targetAlpha; moving = false; }
            int h = Math.max(1, (int)height), top = (getHeight() - h) / 2;
            track.set(0, top, getWidth(), top + h);
            paint.setColor(backgroundColor); canvas.drawRoundRect(track, h / 2f, h / 2f, paint);
            float progress = getMax() <= 0 ? 0 : getProgress() / (float)getMax();
            int filledWidth = (int)(h + (getWidth() - h) * Math.max(0, Math.min(1, progress)));
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) track.left = getWidth() - filledWidth;
            else track.right = filledWidth;
            int color = isEnabled() ? foregroundColor : disabledForeground;
            paint.setColor(color); paint.setAlpha(isEnabled() ? Math.max(0, Math.min(255, (int)alpha)) : Color.alpha(color));
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint);
            if (moving) postInvalidateOnAnimation();
        }
    }

    /** Finite phone media positions from miui_media_session_island_normal.xml. */
    private static final class MediaLayout extends FrameLayout {
        HyperOsResources resources;
        MediaLayout(Context context, AttributeSet attributes) { super(context, attributes); setClipChildren(true); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = Math.round(168 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(width, height);
            if (resources == null) {
                for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
                return;
            }
            place(false, width, height);
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (resources != null) place(true, right - left, bottom - top);
        }
        private void box(boolean layout, String name, int x, int y, int width, int height) {
            View view = find(resources, this, name); if (view == null || view.getVisibility() == GONE) return;
            if (!layout) view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, height), height > 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
            else view.layout(x, y, x + Math.max(0, width), y + (height > 0 ? height : view.getMeasuredHeight()));
        }
        private void place(boolean layout, int width, int height) {
            HyperOsResources r = resources;
            int margin = px(r, "media_margin_left", 16), art = px(r, "album_art_width", 52.5f);
            int seamless = px(r, "media_control_seamless", 24), end = px(r, "seamless_margin_right", 16);
            box(layout, "media_bg_view", 0, 0, width, height);
            box(layout, "album_art", margin, margin, art, art);
            box(layout, "media_seamless", width - end - seamless, px(r, "album_art_margin_bottom", 16), seamless, seamless);
            int titleX = margin + art + px(r, "album_art_margin_right", 12);
            int titleWidth = width - end - seamless - px(r, "media_header_title_margin_end", 6) - titleX;
            int titleY = px(r, "media_control_title_top_margin", 21);
            box(layout, "header_title", titleX, titleY, titleWidth, 0);
            View title = find(r, this, "header_title");
            box(layout, "header_artist", titleX, titleY + (title == null ? 0 : title.getMeasuredHeight()) + px(r, "header_artist_margin_top", 4), titleWidth, 0);
            int actionY = margin + art + px(r, "media_action_margin_top", 11);
            int actionWidth = px(r, "media_action_width", 60), actionHeight = px(r, "media_action_height", 50), visible = 0;
            for (int i = 0; i < 5; i++) { View child = find(r, this, "action" + i); if (child != null && child.getVisibility() != GONE) visible++; }
            int side = px(r, "media_control_action_left_margin", 6);
            int gap = visible > 1 ? (width - 2 * side - visible * actionWidth) / (visible - 1) : 0;
            int x = visible == 1 ? (width - actionWidth) / 2 : side;
            for (int i = 0; i < 5; i++) {
                View child = find(r, this, "action" + i); if (child == null || child.getVisibility() == GONE) continue;
                box(layout, "action" + i, x, actionY, actionWidth, actionHeight); x += actionWidth + gap;
            }
            int timeWidth = px(r, "media_time_width", 40), seekHeight = px(r, "media_hyper_seekbar_height", 38), seekGap = px(r, "seekbar_margin_left", 4);
            int seekTop = Math.round((actionY + actionHeight + px(r, "media_progressbar_margin_top", 2)
                    + height - px(r, "media_control_seekbar_bottom_margin", 16) - seekHeight) / 2f);
            box(layout, "media_progress_bar", margin + timeWidth + seekGap, seekTop, width - 2 * (margin + timeWidth + seekGap), seekHeight);
            box(layout, "media_elapsed_time", margin, seekTop + seekHeight / 2 - px(r, "media_control_time_island_size", 12) / 2, timeWidth, 0);
            box(layout, "media_total_time", width - margin - timeWidth, seekTop + seekHeight / 2 - px(r, "media_control_time_island_size", 12) / 2, timeWidth, 0);
        }
    }
}
