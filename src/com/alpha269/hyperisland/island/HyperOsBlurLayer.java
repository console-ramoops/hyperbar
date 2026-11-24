package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight island-only backdrop blur behind the expanded card.
 * - Uses native zero-copy SemBlurInfo when WindowManager.isCrossWindowBlurEnabled() is true.
 * - On One UI devices where SemBlurInfo exists but cross-window blur does not render (e.g. SM-M558B),
 *   uses ColorOS dynamicisland-lowblur's one-shot 1/4-res background capture + 3-pass box blur so
 *   expanded cards get crisp light blur instead of looking transparent without blur.
 */
public final class HyperOsBlurLayer extends FrameLayout {
    private static final String TAG = "HyperOsBlurLayer";
    public static final int LOW_BLUR_RADIUS = 88;
    private static final int BACKDROP_TINT = 0x1a000000;
    private static final int CAPTURED_DARK_ALPHA = 132;
    private static final int DOWNSAMPLE = 4;
    private static final int CAPTURED_BOX_RADIUS = 10;
    private static final float PARKED_ALPHA = 0.004f;
    private static final long SUSPEND_DELAY_MS = 320L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HyperBar-light-blur");
        thread.setDaemon(true);
        return thread;
    });

    private static Boolean semSupported;
    private static Constructor<?> builderCtor;
    private static Method setRadius;
    private static Method setCorner;
    private static Method setColor;
    private static Method build;
    private static Method semSetBlurInfo;
    private static Method drawableCorner;

    private final Slot slot;
    private final boolean crossWindowBlur;
    private Runnable onBlurReady;
    private boolean enabled = true;
    private boolean active;
    private boolean busy, again, failureLogged;
    private Bitmap capturedBitmap;
    private int capturedScreenW, capturedScreenH, capturedRotation = -1, capturedVersion;
    private long lastUseUptime, lastAttemptUptime;

    private final Runnable suspend = new Runnable() {
        @Override public void run() {
            if (!active && getVisibility() == VISIBLE) {
                setVisibility(GONE);
                clearCaptured();
            }
        }
    };

    public HyperOsBlurLayer(Context context) {
        super(context);
        WindowManager wm = context.getSystemService(WindowManager.class);
        this.crossWindowBlur = wm != null && wm.isCrossWindowBlurEnabled() && isSemBlurSupported();
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);
        setVisibility(GONE);
        slot = new Slot(context);
        addView(slot, new LayoutParams(0, 0));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (enabled && crossWindowBlur && !active) {
            slot.park();
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Slot positions itself directly via setLeftTopRightBottom without triggering parent relayout.
    }

    public static boolean isSemBlurSupported() {
        if (semSupported == null) {
            try {
                Class<?> builderClass = Class.forName("android.view.SemBlurInfo$Builder");
                Class<?> infoClass = Class.forName("android.view.SemBlurInfo");
                builderCtor = builderClass.getConstructor(int.class);
                setRadius = builderClass.getMethod("setRadius", int.class);
                setCorner = builderClass.getMethod("setBackgroundCornerRadius", float.class);
                setColor = builderClass.getMethod("setBackgroundColor", int.class);
                build = builderClass.getMethod("build");
                semSetBlurInfo = View.class.getMethod("semSetBlurInfo", infoClass);
                semSupported = Boolean.TRUE;
            } catch (Throwable unavailable) {
                semSupported = Boolean.FALSE;
            }
        }
        return Boolean.TRUE.equals(semSupported);
    }

    public static boolean isSupported() {
        return true;
    }

    public void setBlurEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!enabled) {
            active = false;
            removeCallbacks(suspend);
            slot.clear();
            clearCaptured();
            setVisibility(GONE);
        }
    }

    public boolean isBlurEnabled() {
        return enabled;
    }

    public void setOnBlurReady(Runnable callback) {
        onBlurReady = callback;
    }

    /** True only when live SemBlurInfo is active OR a captured light-blur bitmap is ready to draw. */
    public boolean available() {
        if (!enabled) return false;
        return crossWindowBlur || (capturedBitmap != null && !capturedBitmap.isRecycled());
    }

    /** True when using the one-shot 1/4-res box-blurred backdrop (already includes dark frosted scrim). */
    public boolean usesCapturedBlur() {
        return enabled && !crossWindowBlur && capturedBitmap != null && !capturedBitmap.isRecycled();
    }

    public void place(RectF bounds, float corner, float alpha, int radius) {
        if (!enabled || bounds == null || bounds.width() < 2f || bounds.height() < 2f || alpha <= 0.004f) {
            hide();
            return;
        }
        boolean newlyActive = !active;
        active = true;
        removeCallbacks(suspend);
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        if (!crossWindowBlur) {
            maybeRequestCapture(newlyActive);
        }
        slot.update(bounds, corner, Math.min(1f, alpha), radius > 0 ? radius : LOW_BLUR_RADIUS);
    }

    public void hide() {
        if (!active && getVisibility() == GONE) return;
        active = false;
        slot.park();
        if (getVisibility() == VISIBLE) {
            removeCallbacks(suspend);
            postDelayed(suspend, SUSPEND_DELAY_MS);
        }
    }

    public void release() {
        active = false;
        removeCallbacks(suspend);
        slot.clear();
        clearCaptured();
        setVisibility(GONE);
    }

    private void clearCaptured() {
        if (capturedBitmap != null) {
            capturedBitmap = null;
            capturedVersion++;
            slot.invalidate();
        }
    }

    private void maybeRequestCapture(boolean newlyOpened) {
        long now = SystemClock.uptimeMillis();
        Display display = getDisplay();
        boolean rotated = capturedBitmap != null && display != null && display.getRotation() != capturedRotation;
        boolean needRefresh = newlyOpened || rotated || (now - lastUseUptime > 1500L);
        lastUseUptime = now;
        if (needRefresh || (capturedBitmap == null && now - lastAttemptUptime > 1500L)) {
            requestCapture(needRefresh);
        }
    }

    private void requestCapture(boolean fresh) {
        if (busy) {
            again |= fresh;
            return;
        }
        if (!isAttachedToWindow() || !enabled) return;
        Display display = getDisplay();
        if (display == null) return;
        Point size = new Point();
        display.getRealSize(size);
        if (size.x < 1 || size.y < 1) return;
        final int fullW = size.x;
        final int fullH = size.y;
        final int displayId = display.getDisplayId();
        final int rotation = display.getRotation();
        final int w = Math.max(1, fullW / DOWNSAMPLE);
        final int h = Math.max(1, fullH / DOWNSAMPLE);
        final ClassLoader loader = getContext().getClassLoader();
        busy = true;
        again = false;
        lastAttemptUptime = SystemClock.uptimeMillis();
        WORKER.execute(() -> {
            Bitmap blurred = null;
            Throwable failure = null;
            try {
                Class<?> wmClass = Class.forName("com.samsung.android.view.SemWindowManager", true, loader);
                Object manager = wmClass.getMethod("getInstance").invoke(null);
                Method screenshot = wmClass.getMethod("screenshot",
                    int.class, int.class, boolean.class, Rect.class, int.class, int.class,
                    boolean.class, int.class, boolean.class);
                Bitmap shot = (Bitmap) screenshot.invoke(manager, displayId, 2000, false,
                    new Rect(), w, h, false, 0, true);
                if (shot == null) throw new IllegalStateException("No screenshot");
                Bitmap readable = shot.getConfig() == Bitmap.Config.HARDWARE
                    ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
                if (readable == null) throw new IllegalStateException("Unreadable screenshot");
                try {
                    int iw = readable.getWidth(), ih = readable.getHeight();
                    int[] pixels = new int[iw * ih];
                    readable.getPixels(pixels, 0, iw, 0, 0, iw, ih);
                    blurPixels(pixels, iw, ih, CAPTURED_BOX_RADIUS);
                    blurred = Bitmap.createBitmap(pixels, iw, ih, Bitmap.Config.ARGB_8888);
                } finally {
                    if (readable != shot) readable.recycle();
                    shot.recycle();
                }
            } catch (Throwable err) {
                failure = err;
            }
            final Bitmap result = blurred;
            final Throwable err = failure;
            MAIN.post(() -> {
                busy = false;
                if (!isAttachedToWindow() || !enabled) return;
                if (result != null && active) {
                    capturedBitmap = result;
                    capturedScreenW = fullW;
                    capturedScreenH = fullH;
                    capturedRotation = rotation;
                    capturedVersion++;
                    slot.invalidate();
                    if (onBlurReady != null) onBlurReady.run();
                    View parent = getParent() instanceof View ? (View) getParent() : null;
                    if (parent != null) parent.invalidate();
                } else if (err != null && !failureLogged) {
                    failureLogged = true;
                    Log.w(TAG, "Captured light blur unavailable; using frosted dark card", err);
                }
                if (again && active) requestCapture(false);
            });
        });
    }

    /** Fast separable 3-pass box blur (exact ColorOS SnapshotPixels parity) for 1/4-res capture. */
    public static int[] blurPixels(int[] pixels, int width, int height, int radius) {
        if (width < 1 || height < 1 || pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("Invalid blur dimensions");
        }
        int r = Math.max(1, Math.min(32, radius));
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            sweep(pixels, scratch, width, height, r, true);
            sweep(scratch, pixels, width, height, r, false);
        }
        return pixels;
    }

    private static void sweep(int[] src, int[] dst, int width, int height, int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int stride = horizontal ? 1 : width;
        int divisor = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int red = 0, green = 0, blue = 0;
            for (int x = -radius; x <= radius; x++) {
                int c = src[base + clamp(x, length) * stride];
                red += (c >>> 16) & 255;
                green += (c >>> 8) & 255;
                blue += c & 255;
            }
            for (int x = 0; x < length; x++) {
                dst[base + x * stride] = 0xff000000
                    | ((red / divisor) << 16) | ((green / divisor) << 8) | (blue / divisor);
                int leaving = src[base + clamp(x - radius, length) * stride];
                int entering = src[base + clamp(x + radius + 1, length) * stride];
                red += ((entering >>> 16) & 255) - ((leaving >>> 16) & 255);
                green += ((entering >>> 8) & 255) - ((leaving >>> 8) & 255);
                blue += (entering & 255) - (leaving & 255);
            }
        }
    }

    private static int clamp(int pos, int length) {
        return Math.max(0, Math.min(length - 1, pos));
    }

    private final class Slot extends View {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF localRect = new RectF();
        private final Matrix shaderMatrix = new Matrix();
        private final int[] location = new int[2];
        private BitmapShader bitmapShader;
        private int shaderVersion = -1;
        private boolean blurSet;
        private int appliedRadius = -1;
        private float corner = -1f;

        Slot(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void update(RectF r, float c, float alpha, int blurRadius) {
            int left = Math.round(r.left);
            int top = Math.round(r.top);
            int right = Math.round(r.right);
            int bottom = Math.round(r.bottom);
            if (right - left < 2 || bottom - top < 2) {
                park();
                return;
            }
            setLeftTopRightBottom(left, top, right, bottom);
            setAlpha(Math.max(PARKED_ALPHA, alpha));
            if (crossWindowBlur) {
                applySemBlur(c, blurRadius);
            } else {
                corner = Math.max(0f, c);
            }
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
            invalidate();
        }

        void park() {
            setLeftTopRightBottom(0, 0, 2, 2);
            setAlpha(PARKED_ALPHA);
            if (crossWindowBlur && isSemBlurSupported()) {
                applySemBlur(1f, LOW_BLUR_RADIUS);
            }
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
            invalidate();
        }

        void clear() {
            if (blurSet && semSetBlurInfo != null) {
                try { semSetBlurInfo.invoke(this, new Object[]{null}); } catch (Throwable ignored) {}
            }
            blurSet = false;
            appliedRadius = -1;
            corner = -1f;
            bitmapShader = null;
            shaderVersion = -1;
            setVisibility(GONE);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (crossWindowBlur || !enabled || !active) return;
            Bitmap bmp = capturedBitmap;
            if (bmp == null || bmp.isRecycled()) return;
            int w = getWidth(), h = getHeight();
            if (w <= 2 || h <= 2) return;
            if (shaderVersion != capturedVersion) {
                bitmapShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                imagePaint.setShader(bitmapShader);
                shaderVersion = capturedVersion;
            }
            if (bitmapShader == null) return;
            getLocationOnScreen(location);
            shaderMatrix.setScale((float) capturedScreenW / bmp.getWidth(), (float) capturedScreenH / bmp.getHeight());
            shaderMatrix.postTranslate(-location[0], -location[1]);
            bitmapShader.setLocalMatrix(shaderMatrix);
            localRect.set(0, 0, w, h);
            float r = Math.max(0f, Math.min(corner, Math.min(w, h) / 2f));
            canvas.drawRoundRect(localRect, r, r, imagePaint);
            tintPaint.setColor((CAPTURED_DARK_ALPHA << 24) | 0x00101216);
            canvas.drawRoundRect(localRect, r, r, tintPaint);
        }

        private void applySemBlur(float c, int radius) {
            boolean sameRadius = blurSet && appliedRadius == radius;
            if (sameRadius && Math.abs(c - corner) < 1.0f) return;
            if (sameRadius && updateDrawableCorner(c)) {
                corner = c;
                return;
            }
            try {
                Object builder = builderCtor.newInstance(0);
                setRadius.invoke(builder, radius);
                setCorner.invoke(builder, c);
                setColor.invoke(builder, BACKDROP_TINT);
                semSetBlurInfo.invoke(this, build.invoke(builder));
                blurSet = true;
                appliedRadius = radius;
                corner = c;
            } catch (Throwable failure) {
                Log.w(TAG, "SemBlurInfo apply failed", failure);
                semSupported = Boolean.FALSE;
            }
        }

        private boolean updateDrawableCorner(float c) {
            Drawable bg = getBackground();
            if (bg == null || !bg.getClass().getName().endsWith("BackgroundBlurDrawable")) return false;
            try {
                if (drawableCorner == null) {
                    drawableCorner = bg.getClass().getMethod("setCornerRadius", float.class);
                }
                drawableCorner.invoke(bg, c);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
