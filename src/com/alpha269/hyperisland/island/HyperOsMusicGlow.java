package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GPU shader driver and lifecycle manager for expanded media music glow effect.
 */
public final class HyperOsMusicGlow {
    private static final String TAG = "HyperOsMusicGlow";
    private static final String TRANSPARENT_SHADER = "half4 main(float2 p){return half4(0);}";

    public static final int EVENT_NONE = 0;
    public static final int EVENT_START_ANIM = 1;
    public static final int EVENT_START_TRANS = 2;
    public static final int EVENT_STOP_ANIM = 3;
    public static final int EVENT_STOP_TRANS = 4;
    public static final int EVENT_RESET = 5;
    public static final int EVENT_START_STILL = 6;

    private final Context context;
    private final String explicitApkPath;
    private long nativeHandle;
    private boolean initialized;
    private boolean available;
    private boolean errorLogged;

    private RuntimeShader textureShader;
    private RuntimeShader bgShader;
    private RuntimeShader fgShader;
    private Shader emptyShader;
    private Paint bgPaint;
    private Paint fgPaint;

    private final float[] packet = new float[63];
    private final float[] lights = new float[22];
    private final float[] colors = new float[33];
    private final RectF viewport = new RectF();

    private String currentOwnerKey;
    private int pendingEvent = EVENT_NONE;
    private boolean running;
    private long lastSampleTimeMs = -1;

    public HyperOsMusicGlow(Context context) {
        this(context, null);
    }

    public HyperOsMusicGlow(Context context, String explicitApkPath) {
        this.context = context;
        this.explicitApkPath = explicitApkPath;
    }

    public boolean isAvailable() {
        return ensureInitialized();
    }

    private boolean ensureInitialized() {
        if (initialized) return available;
        initialized = true;
        try {
            if (!HyperOsNative.isLoaded()) {
                HyperOsNative.load(context);
            }
            nativeHandle = HyperOsNative.nativeCreateGlow();
            if (nativeHandle == 0) {
                throw new IllegalStateException("Failed to create native glow engine handle");
            }

            String textureSource = loadAsset("light_texture.agsl");
            String bgSource = loadAsset("light_background.agsl");
            String fgSource = loadAsset("light_foreground.agsl");

            textureShader = new RuntimeShader(textureSource);
            bgShader = new RuntimeShader(bgSource);
            fgShader = new RuntimeShader(fgSource);
            emptyShader = new RuntimeShader(TRANSPARENT_SHADER);

            // Link texture uTex1 into bg and fg, bind unused uTex to transparent Shader
            textureShader.setInputShader("uTex", emptyShader);
            bgShader.setInputShader("uTex", emptyShader);
            bgShader.setInputShader("uTex1", textureShader);
            fgShader.setInputShader("uTex", emptyShader);
            fgShader.setInputShader("uTex1", textureShader);

            // Set fixed / default uniforms
            textureShader.setFloatUniform("uResolution", 60.0f, 37.0f);

            fgShader.setFloatUniform("uInputResolution", 60.0f, 37.0f);
            fgShader.setFloatUniform("uEdgeHighlightDim", 0.0f);

            bgShader.setFloatUniform("uInputResolution", 60.0f, 37.0f);
            bgShader.setFloatUniform("uEdgeHighlightDim", 0.0f);
            bgShader.setFloatUniform("uLightness", 0.8f);
            bgShader.setFloatUniform("uOrigin", 0.0f, 1.35f);
            bgShader.setFloatUniform("uMin", 0.02f);
            bgShader.setFloatUniform("uMax", 0.1f);

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setShader(bgShader);

            fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fgPaint.setShader(fgShader);

            available = true;
        } catch (Throwable t) {
            available = false;
            if (!errorLogged) {
                errorLogged = true;
                Log.w(TAG, "Glow effect unavailable; disabling safely", t);
            }
            if (nativeHandle != 0) {
                try { HyperOsNative.nativeDestroyGlow(nativeHandle); } catch (Throwable ignored) {}
                nativeHandle = 0;
            }
        }
        return available;
    }

    private String loadAsset(String name) throws Exception {
        String assetPath = "assets/hyperbar/glow/" + name;
        if (explicitApkPath != null) {
            File apkFile = new File(explicitApkPath);
            if (!apkFile.isFile()) {
                throw new java.io.FileNotFoundException("Explicit APK file does not exist: " + explicitApkPath);
            }
            try (ZipFile zip = new ZipFile(apkFile)) {
                ZipEntry entry = zip.getEntry(assetPath);
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        return readFully(in);
                    }
                }
            }
            throw new java.io.FileNotFoundException("Missing asset entry " + assetPath + " in explicit APK " + explicitApkPath);
        }

        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo("com.alpha269.hyperisland", 0);
            if (info != null && info.sourceDir != null) {
                File apkFile = new File(info.sourceDir);
                if (apkFile.isFile()) {
                    try (ZipFile zip = new ZipFile(apkFile)) {
                        ZipEntry entry = zip.getEntry(assetPath);
                        if (entry != null) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                return readFully(in);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Context moduleContext = context.createPackageContext("com.alpha269.hyperisland", 0);
            try (InputStream in = moduleContext.getAssets().open("hyperbar/glow/" + name)) {
                return readFully(in);
            }
        } catch (Throwable ignored) {}

        throw new java.io.FileNotFoundException("Unable to load asset " + name + " from com.alpha269.hyperisland");
    }

    private static String readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    public void onMediaEnter(String ownerKey) {
        if (!ensureInitialized()) return;
        if (ownerKey == null) {
            clear();
            return;
        }
        if (ownerKey.equals(currentOwnerKey)) {
            // Retain glow through interrupted transition with same owner
            if (!running || pendingEvent == EVENT_STOP_TRANS || pendingEvent == EVENT_STOP_ANIM) {
                pendingEvent = EVENT_START_TRANS;
            }
        } else {
            currentOwnerKey = ownerKey;
            pendingEvent = EVENT_START_ANIM;
        }
    }

    public void onClosing() {
        if (!available) return;
        if (running || pendingEvent == EVENT_START_ANIM || pendingEvent == EVENT_START_TRANS) {
            pendingEvent = EVENT_STOP_TRANS;
        }
    }

    public void clear() {
        running = false;
        currentOwnerKey = null;
        pendingEvent = EVENT_NONE;
        lastSampleTimeMs = -1;
        java.util.Arrays.fill(packet, 0.0f);
        java.util.Arrays.fill(lights, 0.0f);
        java.util.Arrays.fill(colors, 0.0f);
        if (available && nativeHandle != 0) {
            HyperOsNative.nativeGlowFrame(nativeHandle, 0, EVENT_RESET, 1.0f, packet);
        }
    }

    public void release() {
        if (nativeHandle != 0) {
            HyperOsNative.nativeDestroyGlow(nativeHandle);
            nativeHandle = 0;
        }
        available = false;
        running = false;
        currentOwnerKey = null;
        pendingEvent = EVENT_NONE;
        lastSampleTimeMs = -1;
        java.util.Arrays.fill(packet, 0.0f);
        java.util.Arrays.fill(lights, 0.0f);
        java.util.Arrays.fill(colors, 0.0f);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasActiveMedia() {
        return currentOwnerKey != null;
    }

    public boolean sampleFrame(long nowMs, float animationScale) {
        if (!ensureInitialized()) return false;
        if (nowMs == lastSampleTimeMs && pendingEvent == EVENT_NONE) {
            return running;
        }
        lastSampleTimeMs = nowMs;
        int event = pendingEvent;
        pendingEvent = EVENT_NONE;

        running = HyperOsNative.nativeGlowFrame(nativeHandle, nowMs, event, animationScale, packet);

        System.arraycopy(packet, 8, lights, 0, 22);
        System.arraycopy(packet, 30, colors, 0, 33);

        textureShader.setFloatUniform("uLights", lights);
        textureShader.setFloatUniform("uLightColors", colors);
        textureShader.setFloatUniform("uPower", packet[1]);
        textureShader.setFloatUniform("uLightness", packet[2]);
        textureShader.setFloatUniform("uTime", packet[0]);

        fgShader.setInputShader("uTex1", textureShader);
        bgShader.setInputShader("uTex1", textureShader);

        return running;
    }

    public void drawBackground(Canvas canvas, RectF outline, float radius, float visibleAlpha, float density) {
        if (!available || visibleAlpha <= 0.001f || packet[6] <= 0.001f) return;
        float glareWidth = 20.0f * density;
        viewport.set(outline.left - glareWidth, outline.top - glareWidth,
                     outline.right + glareWidth, outline.bottom + glareWidth);
        if (viewport.isEmpty()) return;

        bgShader.setFloatUniform("uResolution", viewport.width(), viewport.height());
        bgShader.setFloatUniform("uSize", outline.width(), outline.height());
        bgShader.setFloatUniform("uGlareWidth", glareWidth);
        bgShader.setFloatUniform("uSdfRadius", radius);
        bgShader.setFloatUniform("uEdgeHighlightProgress", packet[4]);
        bgShader.setFloatUniform("uTime", packet[0]);
        bgShader.setFloatUniform("uAlpha", packet[6] * visibleAlpha);

        int save = canvas.save();
        canvas.translate(viewport.left, viewport.top);
        canvas.drawRect(0, 0, viewport.width(), viewport.height(), bgPaint);
        canvas.restoreToCount(save);
    }

    public void drawForeground(Canvas canvas, RectF outline, float radius, float visibleAlpha, float density) {
        if (!available || visibleAlpha <= 0.001f || packet[5] <= 0.001f) return;
        float glareWidth = 20.0f * density;
        viewport.set(outline.left - glareWidth, outline.top - glareWidth,
                     outline.right + glareWidth, outline.bottom + glareWidth);
        if (viewport.isEmpty()) return;

        fgShader.setFloatUniform("uResolution", viewport.width(), viewport.height());
        fgShader.setFloatUniform("uSize", outline.width(), outline.height());
        fgShader.setFloatUniform("uSdfRadius", radius);
        fgShader.setFloatUniform("uEdgeHighlightProgress", packet[3]);
        fgShader.setFloatUniform("uTime", packet[0]);
        fgShader.setFloatUniform("uAlpha", packet[5] * visibleAlpha);

        int save = canvas.save();
        canvas.translate(viewport.left, viewport.top);
        canvas.drawRect(0, 0, viewport.width(), viewport.height(), fgPaint);
        canvas.restoreToCount(save);
    }
}
