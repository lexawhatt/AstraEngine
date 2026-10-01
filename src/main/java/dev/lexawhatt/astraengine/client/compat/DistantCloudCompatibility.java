package dev.lexawhatt.astraengine.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lexawhatt.astraengine.AstraEngine;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Optional DH cloud ownership adapter. Created once by the physical-client entry point; its event binding is
 * connection-owned. It retains only the engine's ownership predicate, never a player, level, framebuffer or config.
 * All methods and callbacks run on the render thread. Absent/incompatible DH leaves host settings untouched.
 */
public final class DistantCloudCompatibility {
    private final BooleanSupplier ownsCurrentClouds;
    private Binding binding;
    private String failure = "";
    private long observedCloudGroups;
    private long cancelledCloudGroups;

    /** The supplied predicate must read live presentation state without retaining the current world. */
    public DistantCloudCompatibility(BooleanSupplier ownsCurrentClouds) {
        if (ownsCurrentClouds == null) { throw new IllegalArgumentException("Cloud ownership predicate is required"); }
        this.ownsCurrentClouds = ownsCurrentClouds;
    }

    /** Binds once after optional DH API initialization; resource reloads do not register duplicate listeners. */
    public void frame(RenderFrameEvent.Pre event) {
        RenderSystem.assertOnRenderThread();
        if (binding != null || !failure.isEmpty() || Minecraft.getInstance().level == null
                || !ModList.get().isLoaded("distanthorizons")) { return; }
        try {
            // This is the sole class-loading edge into optional DH symbols.
            binding = DistantCloudBridge.bind(this);
        } catch (LinkageError | IllegalArgumentException | IllegalStateException unavailableApi) {
            fail(unavailableApi);
        }
    }

    /** Releases this connection's callback and observations; no third-party configuration needs restoring. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        RenderSystem.assertOnRenderThread();
        if (binding != null) {
            try { binding.close(); }
            catch (LinkageError | IllegalArgumentException | IllegalStateException unavailableApi) { fail(unavailableApi); }
            binding = null;
        }
        observedCloudGroups = 0;
        cancelledCloudGroups = 0;
    }

    /** Immutable callback counts, not GPU draw-call counts or a performance measurement. */
    public Diagnostics diagnostics() {
        RenderSystem.assertOnRenderThread();
        return new Diagnostics(binding != null, observedCloudGroups, cancelledCloudGroups, failure);
    }

    boolean cancelCurrentClouds() {
        if (!failure.isEmpty()) { return false; }
        observedCloudGroups++;
        boolean cancel = !RenderCompatibility.shaderPackActive() && !RenderCompatibility.shadowPass()
                && ownsCurrentClouds.getAsBoolean();
        if (cancel) { cancelledCloudGroups++; }
        return cancel;
    }

    void fail(Throwable cause) {
        if (!failure.isEmpty()) { return; }
        failure = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        AstraEngine.LOGGER.error("Optional Distant Horizons cloud event API is unavailable; custom cloud ownership "
                + "cannot suppress DH clouds. No cloud or shader setting was changed.", cause);
    }

    interface Binding { void close(); }

    /** A binding observes exact DH cloud groups only; other generic objects and terrain never increment these counts. */
    public record Diagnostics(boolean bound, long observedCloudGroups, long cancelledCloudGroups, String failure) { }
}
