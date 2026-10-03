package dev.lexawhatt.astraengine.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.lexawhatt.astraengine.AstraEngine;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/**
 * Render-thread orbital camera meter. A bounded GPU reduction and two one-pixel histories
 * adapt one multiplier for the entire HDR scene; no GPU readback or simulation state is involved.
 * Radiance is renderer-relative, not a calibrated photographic luminance measurement.
 */
final class CelestialExposure implements AutoCloseable {
    private ShaderInstance meter;
    private ShaderInstance adapt;
    private HdrColorTarget[] levels = new HdrColorTarget[0];
    private HdrColorTarget[] history = new HdrColorTarget[0];
    private int previous;
    private long lastFrame;
    private boolean initialized;
    private boolean allocationFailed;

    void registerShaders(RegisterShadersEvent event) throws IOException {
        close();
        allocationFailed = false;
        meter = null;
        adapt = null;
        event.registerShader(load(event, "meter"), shader -> meter = shader);
        event.registerShader(load(event, "adapt"), shader -> adapt = shader);
    }

    private static ShaderInstance load(RegisterShadersEvent event, String suffix) throws IOException {
        return new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(
                AstraEngine.MOD_ID, "cosmos_exposure_" + suffix), DefaultVertexFormat.POSITION);
    }

    /** Called inside the parent's saved fullscreen state. Zero denotes fixed-exposure fallback. */
    int render(HdrColorTarget scene) {
        if (meter == null || adapt == null || allocationFailed) { return 0; }
        if (levels.length == 0) {
            try {
                levels = new HdrColorTarget[4];
                history = new HdrColorTarget[2];
                for (int i = 0, size = 64; i < levels.length; i++, size /= 4) {
                    levels[i] = new HdrColorTarget(size, size);
                }
                history[0] = new HdrColorTarget(1, 1);
                history[1] = new HdrColorTarget(1, 1);
            } catch (IllegalStateException failure) {
                close();
                allocationFailed = true;
                AstraEngine.LOGGER.error("Could not allocate orbital exposure meter; using fixed exposure until reload", failure);
                return 0;
            }
        }
        HdrColorTarget input = scene;
        for (int i = 0; i < levels.length; i++) {
            var target = levels[i];
            target.bind();
            meter.setSampler("Source", input.texture());
            meter.safeGetUniform("Extract").set(i == 0 ? 1 : 0);
            meter.safeGetUniform("MeterSize").set((float) target.width, (float) target.height);
            FullscreenPass.draw(meter);
            input = target;
        }
        long now = System.nanoTime();
        float seconds = initialized && !Minecraft.getInstance().isPaused()
                ? (float) Math.clamp((now - lastFrame) * 1e-9, 0, 0.1) : 0;
        int next = 1 - previous;
        history[next].bind();
        adapt.setSampler("Meter", input.texture());
        adapt.setSampler("Previous", history[previous].texture());
        adapt.safeGetUniform("Initialized").set(initialized ? 1 : 0);
        adapt.safeGetUniform("FrameSeconds").set(seconds);
        FullscreenPass.draw(adapt);
        previous = next;
        initialized = true;
        lastFrame = now;
        return history[previous].texture();
    }

    /** Disconnect, reload, resize and disabling the meter discard owned history and textures. */
    @Override
    public void close() {
        for (var target : levels) { if (target != null) { target.close(); } }
        for (var target : history) { if (target != null) { target.close(); } }
        levels = new HdrColorTarget[0];
        history = new HdrColorTarget[0];
        initialized = false;
        lastFrame = 0;
        previous = 0;
    }
}
