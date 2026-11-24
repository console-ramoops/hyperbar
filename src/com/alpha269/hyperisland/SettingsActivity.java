package com.alpha269.hyperisland;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperOsAssets;
import com.alpha269.hyperisland.island.HyperOsFonts;
import java.io.File;

/**
 * Miuix / HyperOS-styled native settings screen.
 * Follows compose-miuix-ui/miuix color, rounded super-surface card, switch, and lightweight typography tokens.
 */
public final class SettingsActivity extends Activity {
    private boolean dark;
    private float density;
    private Typeface titleFace;
    private Typeface bodyFace;
    private Typeface mediumFace;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = getResources().getDisplayMetrics().density;
        resolveFaces();
        configureWindow();

        int bgColor = dark ? 0xff000000 : 0xfff7f7f7;
        int cardColor = dark ? 0xff1c1c1e : 0xffffffff;
        int textPrimary = dark ? 0xfff2f2f7 : 0xff111113;
        int textSecondary = dark ? 0xff8e8e93 : 0xff6e6e73;
        int dividerColor = dark ? 0x1affffff : 0x12000000;
        int accent = dark ? 0xff277af7 : 0xff3482ff;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = dp(16);
        page.setPadding(side, dp(24), side, dp(36));
        page.setFitsSystemWindows(true);

        // Large Miuix / HyperOS top header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(8), dp(22), dp(8), dp(18));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("HyperBar");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTextColor(textPrimary);
        title.setTypeface(titleFace);
        titleRow.addView(title);

        TextView badge = new TextView(this);
        badge.setText("0.1.0");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTextColor(accent);
        badge.setTypeface(mediumFace);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor((accent & 0x00ffffff) | 0x20000000);
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMarginStart(dp(10));
        titleRow.addView(badge, badgeLp);
        header.addView(titleRow);

        TextView subtitle = new TextView(this);
        subtitle.setText("HyperOS Dynamic Island for One UI");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(textSecondary);
        subtitle.setTypeface(bodyFace);
        subtitle.setPadding(0, dp(4), 0, 0);
        header.addView(subtitle);
        page.addView(header);

        SharedPreferences prefs = getSharedPreferences(SettingsProvider.PREFS, 0);

        // Section 1: General
        addSectionTitle(page, "GENERAL", textSecondary);
        LinearLayout generalCard = createCard(cardColor);
        addSwitchRow(generalCard, prefs, SettingsProvider.KEY_ENABLED, true,
            "Enable HyperBar", "Replace status-bar & shade Now Bar with HyperBar",
            textPrimary, textSecondary, accent, false, dividerColor);
        addSwitchRow(generalCard, prefs, SettingsProvider.KEY_BLUETOOTH, true,
            "Bluetooth device cards", "Show audio accessory connection cards in the island",
            textPrimary, textSecondary, accent, true, dividerColor);
        page.addView(generalCard, cardLayoutParams());

        // Section 2: Appearance & Effects
        addSectionTitle(page, "APPEARANCE & EFFECTS", textSecondary);
        LinearLayout effectsCard = createCard(cardColor);
        addSwitchRow(effectsCard, prefs, SettingsProvider.KEY_ISLAND_BLUR, true,
            "Island light blur", "Apply lightweight frosted blur behind the expanded island card only",
            textPrimary, textSecondary, accent, false, dividerColor);
        addSwitchRow(effectsCard, prefs, SettingsProvider.KEY_GLASS_EFFECT, false,
            "Liquid glass refraction", "Enable full HyperOS glass refraction shader for flagship devices",
            textPrimary, textSecondary, accent, false, dividerColor);
        addSwitchRow(effectsCard, prefs, SettingsProvider.KEY_CHARGING_ANIM, true,
            "HyperOS charging animation", "Replace One UI battery plug-in animation with HyperOS island charging & fast-charge decimals",
            textPrimary, textSecondary, accent, true, dividerColor);
        page.addView(effectsCard, cardLayoutParams());

        // Section 3: Setup & Notes
        addSectionTitle(page, "SETUP & INTEGRATION", textSecondary);
        LinearLayout infoCard = createCard(cardColor);
        addInfoRow(infoCard, "LSPosed scope",
            "Enable System UI and Samsung Clock in LSPosed, then reboot. Visual switches apply live without restarting System UI.",
            textPrimary, textSecondary, false, dividerColor);
        addInfoRow(infoCard, "Calls & gestures",
            "Ongoing calls appear in the island while incoming calls stay native One UI. Long-press a capsule to launch directly into its app.",
            textPrimary, textSecondary, true, dividerColor);
        page.addView(infoCard, cardLayoutParams());

        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void resolveFaces() {
        try {
            File fonts = HyperOsAssets.fonts(this);
            if (fonts != null && new File(fonts, "MiSansLatinVF.ttf").isFile()) {
                titleFace = HyperOsFonts.face(this, fonts, "mipro-regular", Typeface.NORMAL);
                bodyFace = HyperOsFonts.face(this, fonts, "mipro-light", Typeface.NORMAL);
                mediumFace = HyperOsFonts.face(this, fonts, "mipro-medium", Typeface.NORMAL);
            }
        } catch (Throwable ignored) {}
        if (titleFace == null) titleFace = Typeface.create(Typeface.SANS_SERIF, 400, false);
        if (bodyFace == null) bodyFace = Typeface.create(Typeface.SANS_SERIF, 350, false);
        if (mediumFace == null) mediumFace = Typeface.create(Typeface.SANS_SERIF, 500, false);
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        int bgColor = dark ? 0xff000000 : 0xfff7f7f7;
        window.setStatusBarColor(bgColor);
        window.setNavigationBarColor(bgColor);
        if (Build.VERSION.SDK_INT >= 30) {
            View decor = window.getDecorView();
            WindowInsetsController ctrl = decor == null ? null : decor.getWindowInsetsController();
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
    }

    private void addSectionTitle(LinearLayout page, String text, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setLetterSpacing(0.06f);
        label.setTextColor(color);
        label.setTypeface(mediumFace);
        label.setPadding(dp(12), dp(14), dp(12), dp(8));
        page.addView(label);
    }

    private LinearLayout createCard(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(color);
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private void addSwitchRow(LinearLayout card, SharedPreferences prefs, String key, boolean defValue,
                              String titleText, String summaryText, int primaryColor, int secondaryColor,
                              int accent, boolean addTopDivider, int dividerColor) {
        if (addTopDivider) addDivider(card, dividerColor);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(primaryColor);
        title.setTypeface(mediumFace);
        textCol.addView(title);

        TextView summary = new TextView(this);
        summary.setText(summaryText);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summary.setTextColor(secondaryColor);
        summary.setTypeface(bodyFace);
        summary.setPadding(0, dp(3), 0, 0);
        textCol.addView(summary);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textLp.setMarginEnd(dp(14));
        row.addView(textCol, textLp);

        MiuixSwitch toggle = new MiuixSwitch(this, accent, dark);
        toggle.setChecked(prefs.getBoolean(key, defValue), false);
        toggle.setOnCheckedChangeListener(checked -> {
            prefs.edit().putBoolean(key, checked).apply();
            getContentResolver().notifyChange(SettingsProvider.URI, null);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(50), dp(28)));
        row.setOnClickListener(v -> toggle.toggle());
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addInfoRow(LinearLayout card, String titleText, String bodyText,
                            int primaryColor, int secondaryColor, boolean addTopDivider, int dividerColor) {
        if (addTopDivider) addDivider(card, dividerColor);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(15), dp(18), dp(15));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(primaryColor);
        title.setTypeface(mediumFace);
        row.addView(title);

        TextView body = new TextView(this);
        body.setText(bodyText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(secondaryColor);
        body.setTypeface(bodyFace);
        body.setLineSpacing(dp(2), 1.0f);
        body.setPadding(0, dp(4), 0, 0);
        row.addView(body);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDivider(LinearLayout card, int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(density * 0.5f)));
        lp.setMarginStart(dp(18));
        lp.setMarginEnd(dp(18));
        card.addView(line, lp);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private interface OnCheckedListener {
        void onCheckedChanged(boolean checked);
    }

    /** Animated HyperOS / Miuix pill switch. */
    private static final class MiuixSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private final int onColor;
        private final int offColor;
        private final float density;
        private boolean checked;
        private float progress;
        private ValueAnimator animator;
        private OnCheckedListener listener;

        MiuixSwitch(Context context, int accent, boolean dark) {
            super(context);
            this.onColor = accent;
            this.offColor = dark ? 0xff3a3a3c : 0xffe2e2e6;
            this.density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
            setOnClickListener(v -> toggle());
        }

        void setOnCheckedChangeListener(OnCheckedListener l) {
            listener = l;
        }

        void toggle() {
            setChecked(!checked, true);
            if (listener != null) listener.onCheckedChanged(checked);
        }

        void setChecked(boolean value, boolean animate) {
            if (checked == value && !animate) {
                progress = value ? 1f : 0f;
                invalidate();
                return;
            }
            checked = value;
            float target = value ? 1f : 0f;
            if (animator != null) animator.cancel();
            if (!animate) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(200L);
            animator.setInterpolator(new DecelerateInterpolator(1.8f));
            animator.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            track.set(0, 0, w, h);
            paint.setColor(lerpColor(offColor, onColor, progress));
            canvas.drawRoundRect(track, r, r, paint);

            float pad = 3f * density;
            float thumbRadius = Math.max(1f, (h - pad * 2f) / 2f);
            float startX = pad + thumbRadius;
            float endX = w - pad - thumbRadius;
            float cx = startX + (endX - startX) * progress;
            paint.setColor(0xffffffff);
            canvas.drawCircle(cx, h / 2f, thumbRadius, paint);
        }

        private static int lerpColor(int a, int b, float t) {
            float c = Math.max(0f, Math.min(1f, t));
            int aa = Color.alpha(a), ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
            int ba = Color.alpha(b), br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
            return Color.argb(Math.round(aa + (ba - aa) * c), Math.round(ar + (br - ar) * c),
                Math.round(ag + (bg - ag) * c), Math.round(ab + (bb - ab) * c));
        }
    }
}
