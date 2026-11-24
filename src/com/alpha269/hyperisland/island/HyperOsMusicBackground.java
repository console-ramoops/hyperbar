package com.alpha269.hyperisland.island;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The sysui MusicBgView, using its original full-card music_panel_bg shader. */
public final class HyperOsMusicBackground extends ViewGroup {
    private static final float CHILD_SCALE = .06f;
    private static final ExecutorService PALETTE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HyperBar-music-palette");
        thread.setDaemon(true); return thread;
    });
    private final ShaderLayer layer;
    private final float[] packet = new float[5];
    private final float[] rgba = new float[4];
    private long handle;
    private int color;
    private Bitmap artwork;
    private Future<?> paletteTask;
    private int generation;
    private boolean playing, disposed, failed;

    public HyperOsMusicBackground(Context context, AttributeSet attributes) {
        super(context, attributes);
        setClipChildren(true); setClipToPadding(true);
        layer = new ShaderLayer(context);
        layer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean ensureEngine() {
        if (disposed || failed || !HyperOsNative.isLoaded()) return false;
        if (handle != 0) return true;
        try {
            handle = HyperOsNative.nativeCreateMusicBackground();
            if (handle == 0) throw new IllegalStateException("No music background engine");
            long now = SystemClock.elapsedRealtime();
            event(now, 5, color); event(now, 1, 0);
            if (!playing) event(now, 2, 0);
            return true;
        } catch (Throwable error) { fail(error); return false; }
    }

    private void event(long now, int command, int value) {
        if (handle != 0) HyperOsNative.nativeMusicBackgroundFrame(handle, now, command, value, packet);
    }

    public void bind(Bitmap nextArtwork, boolean nextPlaying) {
        boolean wasPlaying = playing;
        playing = nextPlaying;
        boolean existed = handle != 0;
        if (ensureEngine() && existed && wasPlaying != playing) {
            event(SystemClock.elapsedRealtime(), playing ? 3 : 2, 0);
            layer.invalidate();
        }
        if (playing) layer.invalidate();
        if (artwork == nextArtwork) return;
        artwork = nextArtwork;
        final int token = ++generation;
        if (paletteTask != null) paletteTask.cancel(true);
        if (nextArtwork == null) { setColor(0); return; }
        paletteTask = PALETTE.submit(() -> {
            Bitmap temporary = null;
            try {
                Bitmap input = nextArtwork;
                int width = input.getWidth(), height = input.getHeight();
                if ((long) width * height > 16 * 1024 * 1024 || Thread.currentThread().isInterrupted()) return;
                if (input.getConfig() == Bitmap.Config.HARDWARE) {
                    temporary = input.copy(Bitmap.Config.ARGB_8888, false); input = temporary;
                }
                float ratio = Math.min(1f, 64f / Math.max(width, height));
                if (ratio < 1f) {
                    Bitmap scaled = Bitmap.createScaledBitmap(input, Math.max(1,Math.round(width*ratio)),
                        Math.max(1,Math.round(height*ratio)), true);
                    if (temporary != null && temporary != scaled) temporary.recycle();
                    temporary = scaled; input = scaled;
                }
                width = input.getWidth(); height = input.getHeight();
                int[] pixels = new int[width * height];
                input.getPixels(pixels, 0, width, 0, 0, width, height);
                int[] colors = new int[4];
                HyperOsNative.nativeArtworkPalette(pixels, width, height, colors);
                post(() -> { if (!disposed && token == generation) setColor(colors[0]); });
            } catch (Throwable error) {
                Log.w("HyperBar", "Music artwork palette unavailable", error);
            } finally { if (temporary != null) temporary.recycle(); }
        });
    }

    private void setColor(int value) {
        if (color == value) return;
        color = value;
        if (ensureEngine()) event(SystemClock.elapsedRealtime(), 5, value);
        layer.invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int smallWidth = Math.max(1, (int)Math.ceil(width * CHILD_SCALE));
        int smallHeight = Math.max(1, (int)Math.ceil(height * CHILD_SCALE));
        layer.measure(MeasureSpec.makeMeasureSpec(smallWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(smallHeight, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = layer.getMeasuredWidth(), height = layer.getMeasuredHeight();
        int x = (int)((getWidth() - width) * .5f), y = (int)((getHeight() - height) * .5f);
        layer.setScaleX(1f / CHILD_SCALE); layer.setScaleY(1f / CHILD_SCALE);
        layer.layout(x, y, x + width, y + height);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && !disposed) layer.invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ensureEngine()) layer.invalidate();
    }

    private void releaseEngine() {
        if (handle != 0) { HyperOsNative.nativeDestroyMusicBackground(handle); handle = 0; }
        java.util.Arrays.fill(packet, 0f);
    }

    @Override protected void onDetachedFromWindow() {
        releaseEngine();
        super.onDetachedFromWindow();
    }

    public void close() {
        disposed = true; generation++;
        if (paletteTask != null) paletteTask.cancel(true);
        releaseEngine();
    }

    private void fail(Throwable error) {
        if (!failed) Log.w("HyperBar", "Music background unavailable", error);
        failed = true; releaseEngine();
    }

    private final class ShaderLayer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private RuntimeShader shader;
        ShaderLayer(Context context) { super(context); }

        private boolean shader() {
            if (shader != null) return true;
            try {
                int resource = getResources().getIdentifier("music_panel_bg", "raw", "com.android.systemui");
                if (resource == 0) throw new IllegalStateException("Missing donor music_panel_bg shader");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = getResources().openRawResource(resource)) {
                    byte[] buffer = new byte[4096]; int size;
                    while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
                }
                shader = new RuntimeShader(bytes.toString("UTF-8"));
                shader.setInputShader("uTex", new RuntimeShader("half4 main(float2 p){return half4(0);}"));
                shader.setFloatUniform("uGradientColorPositions", new float[]{.9f,-.21f,.1f,-.26f,.5f,-.28f});
                shader.setFloatUniform("uSdfRadius", 0f);
                paint.setShader(shader);
                return true;
            } catch (Throwable error) { fail(error); return false; }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!canvas.isHardwareAccelerated() || !ensureEngine() || !shader()) return;
            try {
                boolean animate = HyperOsNative.nativeMusicBackgroundFrame(handle,
                    SystemClock.elapsedRealtime(), 0, 0, packet);
                System.arraycopy(packet, 1, rgba, 0, 4);
                shader.setFloatUniform("uResolution", (float)getWidth(), (float)getHeight());
                shader.setFloatUniform("uTime", packet[0]);
                shader.setFloatUniform("uGradientColor", rgba);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                if (animate && color != 0 && isShown()) postInvalidateOnAnimation();
            } catch (Throwable error) { fail(error); }
        }
    }
}
