package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Connection-owned read-only neighboring blocks. Resource reload retains numeric data; logout retires it. */
public final class EarthBoundaryClient {
    private final EarthStateClient earth;
    private EarthBoundarySnapshot current;
    private long revision;
    private dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewPayload preview;

    /** Requires this connection's server-authored Earth binding. Does not register callbacks or own a world. */
    public EarthBoundaryClient(EarthStateClient earth) {
        if (earth == null) { throw new IllegalArgumentException("Boundary observations require the Earth connection owner"); }
        this.earth = earth;
    }

    /** Main client thread; stale revisions or foreign terrain contexts cannot replace the last valid observation. */
    public void receive(EarthBoundaryReceivedEvent event) {
        requireThread();
        var snapshot = event.payload().snapshot();
        if (snapshot.revision() <= revision || !earth.reference(snapshot.source().dimensionId()).map(snapshot.source()::equals).orElse(false)) { return; }
        revision = snapshot.revision();
        current = snapshot.sections().isEmpty() ? null : snapshot;
    }

    /** Hands an acknowledged observation to this actual host connection before its dimension respawn packet. */
    public void handoff(dev.lexawhatt.astraengine.network.BoundaryHandoffReceivedEvent event) {
        requireThread();
        var connection = Minecraft.getInstance().getConnection();
        var payload = event.payload();
        if (connection instanceof BoundaryHandoffAccess access
                && earth.cubeChart(payload.target().dimensionId()).map(payload.target()::equals).orElse(false)) {
            access.astra$handoff().prepare(payload, current);
        }
    }

    /** Retains a bounded future destination while the camera still occupies the flight stage. */
    public void preview(dev.lexawhatt.astraengine.network.SpaceBoundaryPreviewReceivedEvent event) {
        requireThread(); var game = Minecraft.getInstance(); var incoming = event.payload();
        if (game.level == null || !game.level.dimension().location().equals(incoming.sourceDimension())
                || !earth.cubeChart(incoming.snapshot().source().dimensionId()).filter(incoming.snapshot().source()::equals).isPresent()
                || preview != null && incoming.snapshot().revision() <= preview.snapshot().revision()) { return; }
        preview = incoming.snapshot().sections().isEmpty() ? null : incoming;
    }

    /** Main/render-thread preparation only; it does not expose future blocks as the current world's collision. */
    public Optional<EarthBoundarySnapshot> preparation() {
        requireThread(); var game = Minecraft.getInstance();
        if (preview == null || game.level == null || !game.level.dimension().location().equals(preview.sourceDimension())) {
            return Optional.empty();
        }
        return Optional.of(preview.snapshot());
    }

    /** Installs only the previously prepared target, or the already rendered permanent space presentation. */
    public void spaceHandoff(dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffReceivedEvent event) {
        requireThread(); var game = Minecraft.getInstance(); var payload = event.payload();
        if (!(game.getConnection() instanceof BoundaryHandoffAccess access)) { return; }
        if (payload.cancelled()) { access.astra$handoff().prepare(payload, null); preview = null; return; }
        if (payload.chart() == null) {
            if (game.level == null || earth.cubeChart(game.level.dimension().location().toString()).isEmpty()) { return; }
            access.astra$handoff().prepare(payload, null);
        } else if (preview != null && preview.snapshot().revision() == payload.revision()) {
            access.astra$handoff().prepare(payload, preview.snapshot());
            current = preview.snapshot(); preview = null;
        }
    }

    /**
     * Main client thread. Only a geographically nearby observation in its source or observed neighboring chart
     * is exposed. The immutable value is safe to give to a worker; it confers no block-edit or travel authority.
     */
    public Optional<EarthBoundarySnapshot> view() {
        requireThread();
        var game = Minecraft.getInstance();
        if (current == null || game.level == null || game.player == null) { return Optional.empty(); }
        var chart = earth.cubeChart(game.level.dimension().location().toString()).orElse(null);
        var feet = new SpaceVector(game.player.getX(), game.player.getY(), game.player.getZ());
        return current.visibleFrom(chart, feet) ? Optional.of(current) : Optional.empty();
    }

    /** Retires spatially obsolete data; adjacent-chart handoffs may retain the same immutable section observations. */
    public void tick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (game.level != null) {
            if (view().isEmpty()) { current = null; }
            ((dev.lexawhatt.astraengine.surface.PlanetaryLevelView) game.level).astra$geography(
                    earth.cubeChart(game.level.dimension().location().toString()).orElse(null), current);
        }
    }

    /** Discards the complete connection context, including revision ownership. No saved world data is touched. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) { requireThread(); current = null; preview = null; revision = 0; }

    private static void requireThread() {
        if (!Minecraft.getInstance().isSameThread()) { throw new IllegalStateException("Boundary observations require the main client thread"); }
    }
}
