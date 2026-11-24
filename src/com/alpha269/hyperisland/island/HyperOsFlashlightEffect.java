package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Original FlashLightView resource and shader contract, with the shared phase owned by Rust. */
public final class HyperOsFlashlightEffect extends FrameLayout implements HyperOsCardFactory.AnimationTarget {
    private static final long DEFAULT_FRAME_NANOS = 16_666_667L;
    private static final String[] BEAMS = {"uBeam1", "uBeam5", "uBeam6"};
    private final ImageView layer;
    private final float[] packet = new float[37];
    private final float[] offsets = {-.4f, .15f, 0f, 0f};
    private final long constructedElapsed = SystemClock.elapsedRealtime();
    private RuntimeShader shader;
    private Bitmap iconTexture, glowTexture;
    private long handle, lastTimestampMs;
    private boolean ownerVisible = true, nativeVisible, displayVisible, lit = true, needsFrames, released;

    public HyperOsFlashlightEffect(Context context, AttributeSet attributes) {
        super(context, attributes);
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(false);
        setClipToPadding(false);
        layer = new ImageView(context);
        layer.setBackgroundColor(Color.TRANSPARENT);
        addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                Gravity.START | Gravity.CENTER_VERTICAL));
        layer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyEffect());
    }

    /** The notification holder overrides all four original offsets; AGSL consumes the first two. */
    public void setNotificationOffsets() {
        offsets[0] = 0f;
        offsets[1] = .14f;
        offsets[2] = -50f;
        offsets[3] = 0f;
        if (shader != null) shader.setFloatUniform("uOffset", offsets[0], offsets[1]);
        applyEffect();
    }
    /** The retained off/strength-zero card paints the source lamp without its beam. */
    public void setLit(boolean value) {if(lit==value)return;lit=value;updateVisibility();}

    @Override public void setAnimation(HyperOsResources resources, String source, boolean autoplay, boolean loop, int number) {
        clearEngine();
        released = false;
        try {
            shader = new RuntimeShader(raw(resources, "flash_light"));
            iconTexture = bitmap(resources, "flash_light_ui_icon");
            glowTexture = bitmap(resources, "flash_light_ui_glow");
            Shader.TileMode clamp = Shader.TileMode.CLAMP;
            shader.setFloatUniform("uIconSize", (float)iconTexture.getWidth(), (float)iconTexture.getHeight());
            shader.setFloatUniform("uGlowSize", (float)glowTexture.getWidth(), (float)glowTexture.getHeight());
            shader.setInputBuffer("uFlashlightUiIconTex", new BitmapShader(iconTexture, clamp, clamp));
            shader.setInputBuffer("uFlashlightUiGlowTex", new BitmapShader(glowTexture, clamp, clamp));
            Bitmap geometry = bitmap(resources, "flash_light_glow_icon");
            int height = geometry.getHeight();
            geometry.recycle();
            layer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height,
                    Gravity.START | Gravity.CENTER_VERTICAL));
            handle = HyperOsNative.nativeCreateFlashlight(raw(resources, "flash_light_data"));
            if (handle == 0) throw new IllegalStateException("Could not create the original flashlight phase driver");
            updateVisibility();
        } catch (Exception error) {
            clearEngine();
            released = true;
            throw new IllegalStateException("Could not render the original HyperOS flashlight effect", error);
        }
    }

    @Override public void step(long nowElapsed) {
        if (released || !displayVisible || handle == 0 || shader == null) return;
        long ts = Math.max(lastTimestampMs, nowElapsed);
        lastTimestampMs = ts;
        needsFrames = HyperOsNative.nativeFlashlightFrame(handle, ts * 1_000_000L, frameNanos(), packet);
        shader.setFloatUniform("uTime", (ts - constructedElapsed) / 1000f);
        shader.setFloatUniform("uOffset", offsets[0], offsets[1]);
        shader.setFloatUniform("uEffectProgress", packet[0]);
        shader.setIntUniform("uRTL", layer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : 0);
        for (int beam = 0; beam < BEAMS.length; beam++) {
            String prefix = BEAMS[beam];
            int index = 1 + beam * 12;
            shader.setFloatUniform(prefix + "PointF", packet[index], packet[index + 1]);
            shader.setFloatUniform(prefix + "LineY", packet[index + 2]);
            shader.setFloatUniform(prefix + "E", packet[index + 3]);
            shader.setFloatUniform(prefix + "Highband", packet[index + 4]);
            shader.setFloatUniform(prefix + "Color", packet[index + 5], packet[index + 6], packet[index + 7]);
            shader.setFloatUniform(prefix + "Alpha", packet[index + 8]);
            shader.setFloatUniform(prefix + "ScaleX", packet[index + 9]);
            shader.setFloatUniform(prefix + "Shift", packet[index + 10]);
            shader.setFloatUniform(prefix + "Dispersion", packet[index + 11]);
        }
        applyEffect();
    }

    private void applyEffect() {
        if (released || !displayVisible || shader == null || layer.getWidth() <= 0 || layer.getHeight() <= 0) return;
        shader.setFloatUniform("uResolution", (float)layer.getWidth(), (float)layer.getHeight());
        layer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "uTex"));
        layer.invalidate();
    }

    private long frameNanos() {
        Display display = getDisplay();
        float rate = display == null ? 0f : display.getRefreshRate();
        return Float.isFinite(rate) && rate > 0f ? Math.max(1L, Math.round(1_000_000_000d / rate)) : DEFAULT_FRAME_NANOS;
    }

    @Override public void setOwnerVisible(boolean visible) {
        ownerVisible = visible;
        updateVisibility();
    }

    private void updateVisibility() {
        if (released || handle == 0) return;
        boolean visible = ownerVisible && isShown(), running = visible && lit;
        if (nativeVisible == running && displayVisible == visible) return;
        long nowElapsed = Math.max(lastTimestampMs, SystemClock.elapsedRealtime());
        lastTimestampMs = nowElapsed;
        HyperOsNative.nativeSetFlashlightVisible(handle, running, nowElapsed * 1_000_000L);
        nativeVisible = running;displayVisible = visible;
        needsFrames = running;
        if (visible) step(nowElapsed);
        else layer.setRenderEffect(null);
    }

    @Override public boolean active() { return !released && nativeVisible && needsFrames; }

    @Override public void release() {
        released = true;
        clearEngine();
    }

    private void clearEngine() {
        layer.setRenderEffect(null);
        if (handle != 0) HyperOsNative.nativeDestroyFlashlight(handle);
        handle = 0;
        lastTimestampMs = 0;
        nativeVisible = false;
        displayVisible = false;
        needsFrames = false;
        shader = null;
        iconTexture = glowTexture = null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (layer != null) updateVisibility();
    }

    private static Bitmap bitmap(HyperOsResources resources, String name) {
        Bitmap bitmap = BitmapFactory.decodeResource(resources.getResources(), resources.id("drawable", name));
        if (bitmap == null) throw new IllegalArgumentException("Missing original flashlight bitmap " + name);
        return bitmap;
    }

    private static String raw(HyperOsResources resources, String name) throws Exception {
        try (InputStream input = resources.getResources().openRawResource(resources.id("raw", name));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            return bytes.toString("UTF-8");
        }
    }
}
