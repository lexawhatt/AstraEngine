package dev.lexawhatt.astraengine.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import java.util.function.Supplier;
import net.neoforged.fml.ModList;

/** Render-owner scoped optional fog binding. No DH setting or level is retained or changed. */
public final class DistantFogCompatibility implements AutoCloseable {
    private final Supplier<Frame> frame;
    private Binding binding;
    private boolean failed;
    private long adjustedPasses;

    /** The supplier returns this frame's geographic haze, or null when another renderer owns the view. */
    public DistantFogCompatibility(Supplier<Frame> frame) {
        if (frame == null) { throw new IllegalArgumentException("Fog frame supplier is required"); }
        this.frame = frame;
    }

    /** Retry only delayed API startup. Call on the render thread before drawing a geographic background. */
    public void bind() {
        RenderSystem.assertOnRenderThread();
        if (binding != null || failed || !ModList.get().isLoaded("distanthorizons")) { return; }
        try { binding = DistantFogBridge.bind(this); }
        catch (LinkageError | IllegalArgumentException | IllegalStateException failure) { fail(failure); }
    }

    Frame current() {
        return failed || RenderCompatibility.shaderPackActive() || RenderCompatibility.shadowPass() ? null : frame.get();
    }

    void adjusted() { adjustedPasses++; }

    void fail(Throwable failure) {
        if (failed) { return; }
        failed = true;
        AstraEngine.LOGGER.error("Optional Distant Horizons geographic fog is unavailable; retaining DH fog", failure);
    }

    /** Render-thread diagnostic count of adjusted fog passes; not a draw-call or performance measure. */
    public long adjustedPasses() { return adjustedPasses; }

    /** Removes this renderer's callback at world/resource retirement; a rejected unbind leaves it inert. */
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (binding != null) {
            try { binding.close(); }
            catch (LinkageError | IllegalArgumentException | IllegalStateException failure) { fail(failure); }
            binding = null;
        }
    }

    interface Binding { void close(); }

    /** Dimension identity, physical exponential length in meters and display fog color for one rendered frame. */
    public record Frame(String dimension, float lengthMeters, float red, float green, float blue) {
        public Frame {
            if (dimension == null || !Float.isFinite(lengthMeters) || lengthMeters <= 0
                    || !Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                    || red < 0 || red > 1 || green < 0 || green > 1 || blue < 0 || blue > 1) {
                throw new IllegalArgumentException("Geographic fog requires a dimension, positive length and RGB color");
            }
        }
    }
}
