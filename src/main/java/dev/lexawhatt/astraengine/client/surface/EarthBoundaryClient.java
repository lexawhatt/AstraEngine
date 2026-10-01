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

    /** Requires this connection's server-authored Earth binding. Does not register callbacks or own a world. */
    public EarthBoundaryClient(EarthStateClient earth) {
        if (earth == null) { throw new IllegalArgumentException("Boundary observations require the Earth connection owner"); }
        this.earth = earth;
    }

    /** Main client thread; stale revisions or foreign terrain contexts cannot replace the last valid observation. */
    public void receive(EarthBoundaryReceivedEvent event) {
        requireThread();
        var snapshot = event.payload().snapshot();
        if (snapshot.revision() <= revision || snapshot.source().terrainVersion() != earth.terrainVersion()) { return; }
        revision = snapshot.revision();
        current = snapshot.sections().isEmpty() ? null : snapshot;
    }

    /**
     * Main client thread. Only a geographically nearby observation in its source or observed neighboring chart
     * is exposed. The immutable value is safe to give to a worker; it confers no block-edit or travel authority.
     */
    public Optional<EarthBoundarySnapshot> view() {
        requireThread();
        var game = Minecraft.getInstance();
        if (current == null || game.level == null || game.player == null) { return Optional.empty(); }
        var chart = earth.chart(game.level.dimension().location().toString()).orElse(null);
        var feet = new SpaceVector(game.player.getX(), game.player.getY(), game.player.getZ());
        return current.visibleFrom(chart, feet) ? Optional.of(current) : Optional.empty();
    }

    /** Retires spatially obsolete data; adjacent-chart handoffs may retain the same immutable section observations. */
    public void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level != null && view().isEmpty()) { current = null; }
    }

    /** Discards the complete connection context, including revision ownership. No saved world data is touched. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) { requireThread(); current = null; revision = 0; }

    private static void requireThread() {
        if (!Minecraft.getInstance().isSameThread()) { throw new IllegalStateException("Boundary observations require the main client thread"); }
    }
}
