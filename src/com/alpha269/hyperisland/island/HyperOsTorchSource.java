package com.alpha269.hyperisland.island;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.Log;
import java.util.List;

/** Camera2 torch integration, based on the stable ColorOS island's source. */
public final class HyperOsTorchSource implements AutoCloseable {
    public interface Listener {
        void mode(boolean on);
        void strength(int level);
        void unavailable();
    }
    public interface Backend {
        int maximum();
        int current() throws Exception;
        void observe(Listener listener) throws Exception;
        void stop();
        void set(boolean on, int level) throws Exception;
    }
    private final Backend backend;
    private final Runnable changed;
    private final Listener listener = new Listener() {
        @Override public void mode(boolean enabled) {
            if (closed) return;
            available = true;
            if (enabled && !on) onSince = System.currentTimeMillis();
            on = enabled;
            if (enabled) try { level = clamp(backend.current()); } catch (Exception ignored) {}
            cached = null; changed.run();
        }
        @Override public void strength(int value) {
            if (closed) return;
            level = clamp(value); cached = null; changed.run();
        }
        @Override public void unavailable() {
            if (closed) return;
            available = false; on = false; keep = false; cached = null; changed.run();
        }
    };
    private IslandItem cached;
    private boolean on, keep, available, closed;
    private int level = 1;
    private long onSince;

    public HyperOsTorchSource(Context context, Handler main, Runnable changed) {
        this(new CameraBackend(context, main), changed);
    }
    public HyperOsTorchSource(Backend backend, Runnable changed) {
        this.backend = backend; this.changed = changed;
    }
    public void start() {
        if (closed) return;
        try { available = backend.maximum() >= 1; if (available) backend.observe(listener); }
        catch (Exception error) { available = false; Log.w("HyperBar", "Torch observation unavailable", error); }
    }
    private int clamp(int value) { return Math.max(1, Math.min(Math.max(1,backend.maximum()),value)); }
    public boolean available() { return available && !closed; }
    public void appendTo(List<IslandItem> items, boolean expanded) {
        if (!available() || !on && (!keep || !expanded)) { if (!expanded) keep = false; return; }
        if (cached == null) {
            IslandItem item = new IslandItem("hyperos:torch");
            item.packageName = "com.android.systemui";
            item.title = "Flashlight"; item.subtitle = on ? "Flashlight is on" : "Flashlight is off";
            item.torch = true; item.torchOn = on; item.torchLevel = level;
            item.torchMax = Math.max(1, backend.maximum());
            item.torchSet = this::setLevel; item.torchToggle = () -> setLevel(on ? 0 : level);
            item.postTime = onSince;
            cached = item;
        }
        items.add(cached);
    }
    private void setLevel(int requested) {
        if (!available()) return;
        int selected = Math.max(0, Math.min(backend.maximum(), requested));
        keep = true;
        if (selected == 0 && !on || selected == level && on) return;
        try { backend.set(selected > 0, Math.max(1, selected)); }
        catch (Exception error) { Log.w("HyperBar", "Torch strength update failed", error); changed.run(); }
    }
    public static boolean nativeNotificationKey(String key) {
        return key != null && key.contains("|com.android.systemui|") && key.contains("|Flashlight|");
    }
    @Override public void close() { if (closed) return; closed = true; backend.stop(); cached = null; }

    private static final class CameraBackend implements Backend {
        private final CameraManager manager;
        private final Handler main;
        private String camera;
        private int maximum;
        private CameraManager.TorchCallback callback;
        CameraBackend(Context context, Handler main) {
            this.main = main; manager = context.getSystemService(CameraManager.class);
            if (manager == null) return;
            try {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics info = manager.getCameraCharacteristics(id);
                    if (!Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        || !Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) continue;
                    camera = id;
                    Integer levels = info.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    maximum = levels == null ? 1 : Math.max(1, levels); break;
                }
            } catch (Exception error) { Log.w("HyperBar", "Torch capabilities unavailable", error); }
        }
        @Override public int maximum() { return maximum; }
        @Override public int current() throws Exception { return maximum > 1 ? manager.getTorchStrengthLevel(camera) : 1; }
        @Override public void observe(final Listener listener) {
            if (callback != null) return;
            callback = new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) { if (id.equals(camera)) listener.mode(on); }
                @Override public void onTorchStrengthLevelChanged(String id, int level) { if (id.equals(camera)) listener.strength(level); }
                @Override public void onTorchModeUnavailable(String id) { if (id.equals(camera)) listener.unavailable(); }
            };
            manager.registerTorchCallback(callback, main);
        }
        @Override public void stop() {
            if (callback != null) {
                try { manager.unregisterTorchCallback(callback); }
                catch (RuntimeException error) { Log.w("HyperBar", "Torch observer cleanup failed", error); }
                callback = null;
            }
        }
        @Override public void set(boolean on, int level) throws Exception {
            if (on && maximum > 1) manager.turnOnTorchWithStrengthLevel(camera, level);
            else manager.setTorchMode(camera, on);
        }
    }
}
