package com.alpha269.hyperisland.probe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import com.alpha269.hyperisland.island.HyperIslandView;
import com.alpha269.hyperisland.island.HyperOsAssets;
import com.alpha269.hyperisland.island.HyperOsCardFactory;
import com.alpha269.hyperisland.island.HyperOsFlashlightEffect;
import com.alpha269.hyperisland.island.HyperOsNative;
import com.alpha269.hyperisland.island.HyperOsResources;
import com.alpha269.hyperisland.island.HyperOsTorchSource;
import com.alpha269.hyperisland.island.IslandItem;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Captured source lamp/beam and shared-driver checks on the probe's owned ViewRoot. */
final class TorchShaderProbe {
    interface Work { void run() throws Exception; }
    interface Ready { boolean ready() throws Exception; }
    static final long DEADLINE_MS = 15_000L;
    final int sourceTouchChecks;
    final int[] sent;
    final JSONObject result = new JSONObject();
    final JSONArray observedPhases = new JSONArray();
    HyperOsTorchSource source;
    TorchIntegrationProbe.Camera camera;
    IslandItem current, pairedItem;
    HyperOsCardFactory.Card card, paired;
    HyperOsFlashlightEffect effect, pairedEffect;
    View layer;
    long originalHandle;
    Rect beamRoi, lampRoi;
    int[] earlyBeam, laterBeam;
    String geometry, previousSourceGeometry;

    TorchShaderProbe(int checks, int[] sent) { sourceTouchChecks = checks; this.sent = sent; }
    static void start(int checks, int[] sent) {
        TorchShaderProbe probe = new TorchShaderProbe(checks, sent);
        probe.guard(probe::bindSource);
    }
    void guard(Work work) {
        try { work.run(); } catch (Throwable error) { RootViewIntegrationProbe.fail(error); }
    }
    void require(boolean condition, String message) { TorchIntegrationProbe.check(condition, message); }
    Object field(Object owner, String name) throws Exception { return RootViewIntegrationProbe.field(owner.getClass(), name).get(owner); }
    HyperOsCardFactory.Card mainCard() throws Exception {
        return (HyperOsCardFactory.Card)RootViewIntegrationProbe.field(HyperIslandView.class, "card").get(RootViewIntegrationProbe.view);
    }
    static HyperOsFlashlightEffect findEffect(View view) {
        if (view instanceof HyperOsFlashlightEffect) return (HyperOsFlashlightEffect)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            HyperOsFlashlightEffect found = findEffect(((ViewGroup)view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    float phase(HyperOsFlashlightEffect value) throws Exception { return ((float[])field(value, "packet"))[0]; }
    long handle(HyperOsFlashlightEffect value) throws Exception { return (Long)field(value, "handle"); }
    boolean flag(HyperOsFlashlightEffect value, String name) throws Exception { return (Boolean)field(value, name); }
    void frame() throws Exception {
        RootViewIntegrationProbe.view.doFrame(SystemClock.elapsedRealtimeNanos());
        if (RootViewIntegrationProbe.view.failure() != null) throw new AssertionError("Torch root renderer failed: " + RootViewIntegrationProbe.view.failure());
        if (paired != null) sharedFrame();
    }
    void sharedFrame() {
        long now = SystemClock.elapsedRealtime();
        card.update(current, now);
        paired.update(pairedItem, now);
    }
    void poll(String label, Ready ready, Work complete) {
        poll(label, ready, complete, SystemClock.uptimeMillis() + DEADLINE_MS);
    }
    void poll(String label, Ready ready, Work complete, long deadline) {
        guard(() -> {
            frame();
            if (ready.ready()) { complete.run(); return; }
            if (SystemClock.uptimeMillis() >= deadline) {
                HyperOsCardFactory.Card candidate = mainCard();
                throw new AssertionError("Timed out waiting for " + label + ", p=" + (effect == null ? "unbound" : phase(effect))
                        + ", card=" + (candidate != null) + ", expanded=" + RootViewIntegrationProbe.view.isExpanded()
                        + ", attached=" + RootViewIntegrationProbe.view.isAttachedToWindow()
                        + ", layout=" + RootViewIntegrationProbe.view.isLayoutRequested()
                        + ", mask=" + RootViewIntegrationProbe.field(HyperIslandView.class, "activeMask").getLong(RootViewIntegrationProbe.view)
                        + ", smallMask=" + RootViewIntegrationProbe.field(HyperIslandView.class, "smallActiveMask").getLong(RootViewIntegrationProbe.view));
            }
            RootViewIntegrationProbe.MAIN.postDelayed(() -> poll(label, ready, complete, deadline), 24);
        });
    }
    void observe(String label) throws Exception {
        observedPhases.put(new JSONObject().put("event", label).put("elapsedMs", SystemClock.elapsedRealtime())
                .put("p", phase(effect)).put("active", effect.active()));
    }
    void bindSource() throws Exception {
        camera = new TorchIntegrationProbe.Camera(5);
        camera.on = true;
        source = new HyperOsTorchSource(camera, () -> guard(this::sourceChanged));
        source.start();
        if (!RootViewIntegrationProbe.view.isExpanded()) RootViewIntegrationProbe.view.expand(current);
        poll("settled source card geometry", () -> {
            HyperOsCardFactory.Card candidate = mainCard();
            if(candidate==null||candidate.failure!=null)return false;
            String bounds=RootViewIntegrationProbe.bounds(candidate.view).toString();
            boolean stable=bounds.equals(previousSourceGeometry);previousSourceGeometry=bounds;
            return stable && candidate.view.getWidth()>0 && candidate.view.getHeight()>0 && RootViewIntegrationProbe.view.isExpanded()
                    && RootViewIntegrationProbe.view.isAttachedToWindow()
                    && RootViewIntegrationProbe.field(HyperIslandView.class, "activeMask").getLong(RootViewIntegrationProbe.view) == 0
                    && RootViewIntegrationProbe.field(HyperIslandView.class, "smallActiveMask").getLong(RootViewIntegrationProbe.view) == 0;
        }, this::verifyRenderer);
    }
    void sourceChanged() throws Exception {
        current = TorchIntegrationProbe.item(source, true);
        if (current == null) throw new AssertionError("Owned available torch source lost its expanded item");
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(current));
    }
    void verifyRenderer() throws Exception {
        card = mainCard();
        effect = findEffect(card.view);
        require(effect != null, "Expanded torch does not contain its source shader delegate");
        require(((java.util.List<?>)field(card, "animated")).contains(effect), "Flashlight delegate is absent from Card.animated");
        layer = (View)field(effect, "layer");
        originalHandle = handle(effect);
        require(originalHandle != 0 && effect.isShown() && layer.isHardwareAccelerated(), "Flashlight renderer has no live owned hardware layer");
        require(field(effect, "shader") instanceof RuntimeShader && hasRenderEffect(effect), "Original flashlight RuntimeShader is not applied");
        HyperOsResources resources = HyperOsAssets.plugin(RootViewIntegrationProbe.ownedContext);
        Bitmap decodedGeometry = BitmapFactory.decodeResource(resources.getResources(), resources.id("drawable", "flash_light_glow_icon"));
        Bitmap decodedIcon = BitmapFactory.decodeResource(resources.getResources(), resources.id("drawable", "flash_light_ui_icon"));
        Bitmap decodedGlow = BitmapFactory.decodeResource(resources.getResources(), resources.id("drawable", "flash_light_ui_glow"));
        if (decodedGeometry == null || decodedIcon == null || decodedGlow == null) throw new AssertionError("Original flashlight texture closure is incomplete");
        try {
            int outer = resources.getResources().getDimensionPixelSize(resources.id("dimen", "focus_notify_profile_container_size"));
            require(effect.getWidth() == outer && effect.getHeight() == outer && layer.getWidth() == effect.getWidth(), "56dp source icon/child width is wrong");
            require(layer.getHeight() == decodedGeometry.getHeight()
                    && layer.getHeight() == Math.round(84f * resources.getResources().getDisplayMetrics().density), "Flashlight child lost its density-decoded 84dp height");
            require(!effect.getClipChildren() && layer.getTop() == (effect.getHeight() - layer.getHeight()) / 2
                    && layer.getTop() < 0 && layer.getBottom() > effect.getHeight(), "Source flashlight's centered 14dp overflow is clipped or misplaced");
            Bitmap icon = (Bitmap)field(effect, "iconTexture"), glow = (Bitmap)field(effect, "glowTexture");
            require(icon != null && glow != null && !icon.isRecycled() && !glow.isRecycled()
                    && icon.getWidth() == decodedIcon.getWidth() && icon.getHeight() == decodedIcon.getHeight()
                    && glow.getWidth() == decodedGlow.getWidth() && glow.getHeight() == decodedGlow.getHeight(), "Density-decoded shader input-buffer textures are missing or rescaled");
            result.put("outerPx", outer).put("childWidthPx", layer.getWidth()).put("childHeightPx", layer.getHeight())
                    .put("childTopPx", layer.getTop()).put("inputBufferTexturesLive", true);
        } finally { decodedGeometry.recycle(); decodedIcon.recycle(); decodedGlow.recycle(); }
        geometry = geometry();
        beamRoi = roi(0.12f, 0f, 0.88f, 0.32f);
        lampRoi = roi(0.12f, 0.34f, 0.88f, 0.88f);
        require(!beamRoi.isEmpty() && !lampRoi.isEmpty(), "Source lamp/beam sample regions are outside the captured root");
        result.put("beamRoi", rectangle(beamRoi)).put("lampRoi", rectangle(lampRoi));
        RootViewIntegrationProbe.view.setSuppressed(true);
        frame();
        require(!flag(effect, "nativeVisible") && !effect.active() && !card.needsFrames() && !hasRenderEffect(effect), "Suppression did not stop and clear the source flashlight shader");
        observe("suppressed");
        RootViewIntegrationProbe.render("torch-shader-suppressed.png");
        RootViewIntegrationProbe.view.reveal();
        frame();
        require(phase(effect) == 0f && flag(effect, "nativeVisible") && effect.active() && hasRenderEffect(effect), "Reveal did not restart the flashlight entrance at p=0");
        observe("reveal-zero");
        RootViewIntegrationProbe.render("torch-shader-early.png");
        earlyBeam = pixels(beamRoi);
        require(geometry.equals(geometry()), "Reveal moved the settled source card geometry");
        poll("nonzero flashlight entrance", () -> phase(effect) >= .15f, this::verifyMetadata);
    }
    void verifyMetadata() throws Exception {
        float beforeMetadata = phase(effect);
        current.title = "Owned metadata refresh";
        current.contentVersion++;
        RootViewIntegrationProbe.view.setItems(Collections.singletonList(current));
        frame();
        retained("metadata update");
        require(phase(effect) >= beforeMetadata && phase(effect) > 0f, "Metadata refresh restarted the flashlight phase");
        JSONArray levels = new JSONArray();
        for (int level = 1; level <= 5; level++) {
            float before = phase(effect);
            current.torchSet.accept(level);
            frame();
            retained("strength " + level);
            SeekBar bar = TorchIntegrationProbe.slider(card.view);
            require(current.torchOn && current.torchLevel == level && bar != null && bar.getProgress() == level,
                    "Owned source strength did not reach the retained card: " + level);
            require(phase(effect) >= before && phase(effect) > 0f, "Strength " + level + " restarted the entrance");
            levels.put(level);
        }
        result.put("levelsWithoutRestart", levels).put("metadataRetainsHandle", true);
        observe("metadata-and-strength");
        poll("later flashlight entrance", () -> phase(effect) >= .75f, this::verifyBeamPixels);
    }
    void retained(String event) throws Exception {
        require(mainCard() == card && findEffect(card.view) == effect && handle(effect) == originalHandle,
                event + " replaced the retained card, shader, or native handle");
    }
    void verifyBeamPixels() throws Exception {
        RootViewIntegrationProbe.render("torch-shader-later.png");
        laterBeam = pixels(beamRoi);
        JSONObject delta = difference(earlyBeam, laterBeam);
        require(delta.getInt("changedPixels") >= 8 && delta.getInt("maxRgbDelta") >= 16, "The actual source beam ROI did not change with its phase");
        require(geometry.equals(geometry()), "Flashlight phase changed settled card/child geometry");
        result.put("earlyLaterBeamDelta", delta).put("geometryFixedDuringEntrance", true);
        observe("later-beam");
        poll("settled flashlight entrance", () -> !effect.active(), this::verifySettledAndOff);
    }
    void verifySettledAndOff() throws Exception {
        require(phase(effect) == 1f && !card.needsFrames() && hasRenderEffect(effect), "Settled flashlight lost its final effect or kept requesting frames");
        require(geometry.equals(geometry()), "Settled flashlight changed the card geometry");
        observe("settled-one");
        current.torchSet.accept(0);
        frame();
        retained("source off");
        float[] offPacket = (float[])field(effect, "packet");
        require(!current.torchOn && !flag(effect, "lit") && !flag(effect, "nativeVisible") && flag(effect, "displayVisible")
                && phase(effect) == 0f && !effect.active() && !card.needsFrames(), "Source off did not retain an inactive p=0 lamp card");
        require(offPacket[9] == 0f && offPacket[21] == 0f && offPacket[33] == 0f && hasRenderEffect(effect), "Source off kept beam alpha or removed the lamp shader");
        require(geometry.equals(geometry()), "Source off changed the retained card/child geometry");
        RootViewIntegrationProbe.render("torch-shader-off.png");
        int[] offLamp = pixels(lampRoi);
        int litLampPixels = brightPixels(offLamp);
        JSONObject beamOff = difference(laterBeam, pixels(beamRoi));
        require(beamOff.getInt("changedPixels") >= 8 && beamOff.getInt("maxRgbDelta") >= 16, "Source off did not remove the captured lit beam");
        card.setOwnerVisible(false);
        require(!hasRenderEffect(effect), "Hiding the retained off card did not clear its child effect");
        RootViewIntegrationProbe.render("torch-shader-off-without-lamp.png");
        int[] withoutLamp = pixels(lampRoi);
        JSONObject lampDelta = difference(offLamp, withoutLamp).put("lampBrightPixels", litLampPixels)
                .put("bareBrightPixels", brightPixels(withoutLamp));
        require(lampDelta.getInt("changedPixels") >= 8 && lampDelta.getInt("maxRgbDelta") >= 16
                && litLampPixels - brightPixels(withoutLamp) >= 8, "The inactive source lamp texture was not actually rendered");
        card.setOwnerVisible(true);
        require(!effect.active() && phase(effect) == 0f && hasRenderEffect(effect), "Revealing an off card started a beam or failed to restore the lamp");
        result.put("offBeamDelta", beamOff).put("offLampTextureDelta", lampDelta).put("offRetainsLampWithoutFrames", true);
        observe("source-off-lamp");
        current.torchSet.accept(3);
        frame();
        retained("source on again");
        require(current.torchOn && flag(effect, "lit") && phase(effect) == 0f && effect.active(), "Source on did not restart its retained flashlight entrance");
        observe("source-on-zero");
        createPair();
    }
    void createPair() throws Exception {
        pairedItem = new IslandItem("owned-independent-torch-shader");
        pairedItem.torch = true; pairedItem.torchOn = true; pairedItem.title = "Flashlight";
        pairedItem.subtitle = "Flashlight is on"; pairedItem.torchMax = 1; pairedItem.torchLevel = 1;
        paired = HyperOsCardFactory.create(RootViewIntegrationProbe.ownedContext, RootViewIntegrationProbe.host, pairedItem, card.view.getWidth());
        if (paired.failure != null) throw new AssertionError("Owned independent torch card failed: " + paired.failure);
        paired.setOwnerVisible(false);
        paired.view.setTranslationX(-2f * RootViewIntegrationProbe.width);
        RootViewIntegrationProbe.view.addView(paired.view, new FrameLayout.LayoutParams(card.view.getWidth(), paired.height()));
        pairedEffect = findEffect(paired.view);
        require(pairedEffect != null && pairedEffect != effect && handle(pairedEffect) != originalHandle
                && ((java.util.List<?>)field(paired, "animated")).contains(pairedEffect), "Independent source shader delegate or handle is missing");
        paired.setOwnerVisible(true);
        sharedFrame();
        require(pairedEffect.isShown() && flag(pairedEffect, "nativeVisible") && flag(effect, "nativeVisible")
                && Arrays.equals((float[])field(effect, "packet"), (float[])field(pairedEffect, "packet")), "Same-timestamp delegates did not receive one shared flashlight packet");
        poll("active shared flashlight packet", () -> phase(effect) >= .15f && effect.active() && pairedEffect.active(), this::verifySharedLifecycle);
    }
    void verifySharedLifecycle() throws Exception {
        sharedFrame();
        float before = phase(effect);
        paired.setOwnerVisible(false);
        require(!flag(pairedEffect, "nativeVisible") && !pairedEffect.active() && !hasRenderEffect(pairedEffect)
                && effect.active() && flag(effect, "nativeVisible"), "Hiding one shared delegate stopped the other");
        paired.setOwnerVisible(true);
        sharedFrame();
        require(phase(effect) >= before && phase(pairedEffect) >= before
                && Arrays.equals((float[])field(effect, "packet"), (float[])field(pairedEffect, "packet")), "Joining an existing visible driver restarted or split its packet");
        RootViewIntegrationProbe.view.setSuppressed(true);
        frame();
        require(!flag(effect, "nativeVisible") && !effect.active() && !hasRenderEffect(effect)
                && flag(pairedEffect, "nativeVisible") && pairedEffect.active(), "Main-card suppression stopped the independent visible flashlight");
        RootViewIntegrationProbe.view.reveal();
        frame();
        require(phase(effect) > 0f && effect.active()
                && Arrays.equals((float[])field(effect, "packet"), (float[])field(pairedEffect, "packet")), "Reveal restarted a driver that still had an independent visible owner");
        result.put("sameTimestampSharedPacket", true).put("hideOneKeepsOtherActive", true).put("visibleOwnerJoinRetainsPhase", true)
                .put("independentOwnedCardOffscreen", true);
        long closed = handle(pairedEffect);
        paired.setOwnerVisible(false);
        paired.release();
        RootViewIntegrationProbe.view.removeView(paired.view);
        require(handle(pairedEffect) == 0 && flag(pairedEffect, "released") && !pairedEffect.active() && !hasRenderEffect(pairedEffect), "Independent shader release leaked its handle or effect");
        require(rejectFrame(closed, new float[37], "closed"), "Released independent shader handle was accepted by JNI");
        paired = null;
        float[] shortPacket = new float[36];
        Arrays.fill(shortPacket, -17f);
        float[] untouched = shortPacket.clone();
        require(rejectFrame(originalHandle, shortPacket, "too small") && Arrays.equals(shortPacket, untouched), "Short JNI output was accepted or partially overwritten");
        result.put("releasedHandleRejected", true).put("shortOutputRejectedWithoutWrite", true);
        observe("shared-owner-released");
        poll("final restarted flashlight settlement", () -> !effect.active(), this::finish);
    }
    void finish() throws Exception {
        require(phase(effect) == 1f && hasRenderEffect(effect) && !card.needsFrames(), "Restarted flashlight did not retain its final rendered effect");
        retained("final settlement");
        RootViewIntegrationProbe.render("torch-shader-on-again.png");
        observe("on-again-settled");
        long closed = originalHandle;
        card.release();
        require(handle(effect) == 0 && flag(effect, "released") && !effect.active() && !hasRenderEffect(effect), "Main flashlight release leaked native/render state");
        require(rejectFrame(closed, new float[37], "closed"), "Released main flashlight handle was accepted by JNI");
        source.close();
        require(camera.stopped, "Owned shader source observation leaked");
        result.put("observedPhases", observedPhases).put("shader", "flash_light.agsl")
                .put("sourceKeyframes", "flash_light_data.json").put("actualCameraModified", false)
                .put("ownedBackendCalls", camera.calls).put("genericNotificationGlow", false);
        TorchIntegrationProbe.finish(sourceTouchChecks, sent, result);
    }
    boolean rejectFrame(long id, float[] output, String reason) {
        try { HyperOsNative.nativeFlashlightFrame(id, SystemClock.elapsedRealtime() * 1_000_000L, 16_666_667L, output); return false; }
        catch (IllegalArgumentException rejected) { return rejected.getMessage() != null && rejected.getMessage().contains(reason); }
    }
    boolean hasRenderEffect(HyperOsFlashlightEffect owner) throws Exception {
        View child = (View)field(owner, "layer");
        RenderNode node = (RenderNode)RootViewIntegrationProbe.field(View.class, "mRenderNode").get(child);
        // Android exposes no getter. The setter reports an actual native property change;
        // restore the same shader without advancing the native animation clock.
        boolean present = node.setRenderEffect(null);
        if (present) {
            Object shader = field(owner, "shader");
            if (shader instanceof RuntimeShader) child.setRenderEffect(RenderEffect.createRuntimeShaderEffect((RuntimeShader)shader, "uTex"));
        }
        return present;
    }
    String geometry() throws Exception {
        return RootViewIntegrationProbe.bounds(card.view).toString() + RootViewIntegrationProbe.bounds(effect).toString()
                + RootViewIntegrationProbe.bounds(layer).toString();
    }
    Rect roi(float left, float top, float right, float bottom) {
        float[] a = {layer.getWidth() * left, layer.getHeight() * top};
        float[] b = {layer.getWidth() * right, layer.getHeight() * bottom};
        RootViewIntegrationProbe.toRoot(layer, a); RootViewIntegrationProbe.toRoot(layer, b);
        return new Rect(Math.max(0, (int)Math.floor(a[0])), Math.max(0, (int)Math.floor(a[1])),
                Math.min(RootViewIntegrationProbe.width, (int)Math.ceil(b[0])), Math.min(RootViewIntegrationProbe.view.getHeight(), (int)Math.ceil(b[1])));
    }
    JSONObject rectangle(Rect rect) throws Exception {
        return new JSONObject().put("left", rect.left).put("top", rect.top).put("width", rect.width()).put("height", rect.height());
    }
    int[] pixels(Rect roi) {
        int[] root = RootViewIntegrationProbe.lastRenderedColors, result = new int[roi.width() * roi.height()];
        for (int y = roi.top; y < roi.bottom; y++) System.arraycopy(root, y * RootViewIntegrationProbe.width + roi.left,
                result, (y - roi.top) * roi.width(), roi.width());
        return result;
    }
    JSONObject difference(int[] before, int[] after) throws Exception {
        if (before.length != after.length || before.length == 0) throw new AssertionError("Invalid captured source effect ROI");
        int changed = 0, maximum = 0; long total = 0;
        for (int i = 0; i < before.length; i++) {
            int delta = Math.abs(((before[i] >> 16) & 255) - ((after[i] >> 16) & 255))
                    + Math.abs(((before[i] >> 8) & 255) - ((after[i] >> 8) & 255)) + Math.abs((before[i] & 255) - (after[i] & 255));
            if (delta > 3) changed++; maximum = Math.max(maximum, delta); total += delta;
        }
        return new JSONObject().put("pixels", before.length).put("changedPixels", changed).put("maxRgbDelta", maximum).put("sumRgbDelta", total);
    }
    int brightPixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if (((pixel >> 16) & 255) + ((pixel >> 8) & 255) + (pixel & 255) >= 384) count++;
        return count;
    }
}
