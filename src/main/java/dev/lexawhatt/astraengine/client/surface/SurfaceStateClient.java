package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import net.minecraft.client.multiplayer.ClientLevel;

/** Connection-owned authoritative surface context; client interpolation never advances an independent clock. */
public final class SurfaceStateClient {
    private SurfacePayload snapshot;
    private double previousClock;
    private long receivedAt;

    /** Applies a complete snapshot on the client thread; a context switch never blends unrelated clocks. */
    public void receive(SurfaceReceivedEvent event) {
        SurfacePayload incoming = event.payload();
        previousClock = snapshot == null || !snapshot.bodyId().equals(incoming.bodyId())
                ? incoming.clockTicks() : clockTicks();
        snapshot = incoming;
        receivedAt = System.nanoTime();
    }

    /** Last immutable server context, or null before first synchronization. Client thread only. */
    public SurfacePayload snapshot() { return snapshot; }

    /** Bounded interpolation between received snapshots; stalls and pauses cannot manufacture orbit time. */
    public double clockTicks() {
        if (snapshot == null) { return 0; }
        double blend = Math.clamp((System.nanoTime() - receivedAt) / 100_000_000.0, 0, 1);
        return previousClock * (1 - blend) + snapshot.clockTicks() * blend;
    }

    /** A binding is usable only in its actual persistent dimension; stale flight packets cannot paint the Overworld. */
    public SurfaceDefinition definition(ClientLevel level) {
        if (snapshot == null || snapshot.phase() == SurfacePayload.Phase.NONE || level == null
                || !level.dimension().location().getNamespace().equals("astraengine")
                || !level.dimension().location().getPath().equals("surface_" + snapshot.bodyId())) { return null; }
        return SurfaceDefinition.find("sol", snapshot.bodyId()).orElse(null);
    }

    /** Discards connection data without touching permanent worlds or Minecraft-owned shader programs. */
    public void clear() { snapshot = null; previousClock = 0; receivedAt = 0; }
}
