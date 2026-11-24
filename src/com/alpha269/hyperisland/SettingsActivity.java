package com.alpha269.hyperisland;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperOsAssets;
import java.io.File;

/** Clean HyperOS / Miuix-styled settings screen for HyperBar. */
public final class SettingsActivity extends Activity {
    private boolean dark;
    private float density;
    private Typeface titleFace;
    private Typeface mediumFace;
    private Typeface bodyFace;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = getResources().getDisplayMetrics().density;
        resolveFaces();
        configureWindow();

        int bgColor = dark ? 0xff000000 : 0xfff4f5f7;
        int cardColor = dark ? 0xff16171a : 0xffffffff;
        int cardStroke = dark ? 0x16ffffff : 0x0c000000;
        int textPrimary = dark ? 0xfff5f6f8 : 0xff111214;
        int textSecondary = dark ? 0xff8e929b : 0xff6b707a;
        int dividerColor = dark ? 0x14ffffff : 0x0f000000;
        int accent = dark ? 0xff2f80ff : 0xff2b76f6;
        int rippleColor = dark ? 0x1cffffff : 0x12000000;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = dp(16);
        page.setPadding(side, dp(18), side, dp(32));
        page.setFitsSystemWindows(true);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(6), dp(10), dp(6), dp(20));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setLetterSpacing(-0.02f);
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
        badgeBg.setColor((accent & 0x00ffffff) | 0x22000000);
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMarginStart(dp(10));
        titleRow.addView(badge, badgeLp);
        header.addView(titleRow);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.module_description));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(textSecondary);
        subtitle.setTypeface(bodyFace);
        subtitle.setLineSpacing(dp(2), 1.0f);
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle);
        page.addView(header);

        SharedPreferences prefs = getSharedPreferences(SettingsProvider.PREFS, 0);

        // Master switch hero card
        boolean enabledNow = prefs.getBoolean(SettingsProvider.KEY_ENABLED, true);
        LinearLayout masterCard = createCard(masterBg(enabledNow, accent, cardColor), masterStroke(enabledNow, accent, cardStroke));
        addSwitchRow(masterCard, prefs, SettingsProvider.KEY_ENABLED, true,
            "Enable HyperBar", "Replace Now Bar with HyperIsland",
            textPrimary, textSecondary, accent, rippleColor, false, dividerColor,
            checked -> updateCardBg(masterCard, masterBg(checked, accent, cardColor), masterStroke(checked, accent, cardStroke)));
        LinearLayout.LayoutParams masterLp = new LinearLayout.LayoutParams(-1, -2);
        masterLp.bottomMargin = dp(10);
        page.addView(masterCard, masterLp);

        // Features
        addSectionTitle(page, "Features", textSecondary);
        LinearLayout featuresCard = createCard(cardColor, cardStroke);
        addSwitchRow(featuresCard, prefs, SettingsProvider.KEY_CHARGING_ANIM, true,
            "Charging animation", "HyperOS charging pill & fast-charge decimals",
            textPrimary, textSecondary, accent, rippleColor, false, dividerColor, null);
        addSwitchRow(featuresCard, prefs, SettingsProvider.KEY_BLUETOOTH, true,
            "Bluetooth cards", "Show connected audio accessories",
            textPrimary, textSecondary, accent, rippleColor, true, dividerColor, null);
        page.addView(featuresCard, cardLayoutParams());

        // Visuals
        addSectionTitle(page, "Visuals", textSecondary);
        LinearLayout visualsCard = createCard(cardColor, cardStroke);
        addSwitchRow(visualsCard, prefs, SettingsProvider.KEY_ISLAND_BLUR, true,
            "Island blur", "Frosted blur behind expanded cards",
            textPrimary, textSecondary, accent, rippleColor, false, dividerColor, null);
        addSwitchRow(visualsCard, prefs, SettingsProvider.KEY_GLASS_EFFECT, false,
            "Liquid glass", "HyperOS refraction shader for flagship GPUs",
            textPrimary, textSecondary, accent, rippleColor, true, dividerColor, null);
        page.addView(visualsCard, cardLayoutParams());

        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private int masterBg(boolean enabled, int accent, int cardColor) {
        if (!enabled) return cardColor;
        return dark ? blend(cardColor, accent, 0.16f) : blend(cardColor, accent, 0.08f);
    }

    private int masterStroke(boolean enabled, int accent, int defaultStroke) {
        if (!enabled) return defaultStroke;
        return (accent & 0x00ffffff) | (dark ? 0x48000000 : 0x34000000);
    }

    private void resolveFaces() {
        File fontFile = null;
        try {
            File fonts = HyperOsAssets.fonts(this);
            if (fonts != null) {
                File latin = new File(fonts, "MiSansLatinVF.ttf");
                File vf = new File(fonts, "MiSansVF.ttf");
                if (latin.isFile()) fontFile = latin;
                else if (vf.isFile()) fontFile = vf;
            }
        } catch (Throwable ignored) {}
        titleFace = buildWeightFace(fontFile, 600);
        mediumFace = buildWeightFace(fontFile, 500);
        bodyFace = buildWeightFace(fontFile, 400);
    }

    private static Typeface buildWeightFace(File fontFile, int weight) {
        if (fontFile != null && fontFile.isFile()) {
            try {
                Font font = new Font.Builder(fontFile)
                    .setFontVariationSettings("'wght' " + weight)
                    .setWeight(weight)
                    .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                    .build();
                Typeface face = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                    .setStyle(new FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif")
                    .build();
                if (face != null) return face;
            } catch (Throwable ignored) {}
        }
        return Typeface.create(Typeface.SANS_SERIF, weight, false);
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        int bgColor = dark ? 0xff000000 : 0xfff4f5f7;
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
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(color);
        label.setTypeface(mediumFace);
        label.setPadding(dp(10), dp(12), dp(10), dp(8));
        page.addView(label);
    }

    private LinearLayout createCard(int color, int strokeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        updateCardBg(card, color, strokeColor);
        card.setClipToOutline(true);
        return card;
    }

    private void updateCardBg(LinearLayout card, int color, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(color);
        bg.setStroke(Math.max(1, Math.round(density * 0.75f)), strokeColor);
        card.setBackground(bg);
    }

    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private void addSwitchRow(LinearLayout card, SharedPreferences prefs, String key, boolean defValue,
                              String titleText, String summaryText, int primaryColor, int secondaryColor,
                              int accent, int rippleColor, boolean addTopDivider, int dividerColor,
                              OnCheckedListener extraListener) {
        if (addTopDivider) addDivider(card, dividerColor);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), null, null));

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
            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            prefs.edit().putBoolean(key, checked).apply();
            getContentResolver().notifyChange(SettingsProvider.URI, null);
            if (extraListener != null) extraListener.onCheckedChanged(checked);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(50), dp(28)));
        row.setOnClickListener(v -> toggle.toggle());
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

    private static int blend(int base, int overlay, float ratio) {
        int r = Math.round(Color.red(base) + (Color.red(overlay) - Color.red(base)) * ratio);
        int g = Math.round(Color.green(base) + (Color.green(overlay) - Color.green(base)) * ratio);
        int b = Math.round(Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * ratio);
        return Color.rgb(r, g, b);
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
            this.offColor = dark ? 0xff363840 : 0xffd9dce3;
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
            animator.setDuration(190L);
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
