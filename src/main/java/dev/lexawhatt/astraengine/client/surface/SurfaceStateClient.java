package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import net.minecraft.client.multiplayer.ClientLevel;

/** Connection-owned authoritative surface context; client interpolation never advances an independent clock. */
public final class SurfaceStateClient {
    private SurfacePayload snapshot;
    private double previousClock;
    private double previousOrbit;
    private FlightOrientation previousEarth;
    private long receivedAt;

    /** Applies a complete snapshot on the client thread; a context switch never blends unrelated clocks. */
    public void receive(SurfaceReceivedEvent event) {
        SurfacePayload incoming = event.payload();
        boolean reset = snapshot == null || !snapshot.bodyId().equals(incoming.bodyId())
                || snapshot.calendarEpoch() != incoming.calendarEpoch()
                || (snapshot.earthOrientation() == null) != (incoming.earthOrientation() == null);
        previousClock = reset ? incoming.clockTicks() : clockTicks();
        previousOrbit = reset ? incoming.orbitalSeconds() : orbitalSeconds();
        previousEarth = reset ? incoming.earthOrientation() : earthOrientation();
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

    /** Signed orbital epoch at the same interpolation point as the surface pose. */
    public double orbitalSeconds() {
        if (snapshot == null) { return 0; }
        double blend = blend();
        return previousOrbit * (1 - blend) + snapshot.orbitalSeconds() * blend;
    }

    /** Full authoritative Earth rotation; null selects the legacy surface clock. */
    public FlightOrientation earthOrientation() {
        if (snapshot == null || snapshot.earthOrientation() == null) { return null; }
        return previousEarth == null ? snapshot.earthOrientation()
                : previousEarth.interpolate(snapshot.earthOrientation(), blend());
    }

    private double blend() { return Math.clamp((System.nanoTime() - receivedAt) / 100_000_000.0, 0, 1); }

    /** Resolves a supported surface at this context's orbital epoch and rotation. */
    public BodyFixedFrame frame(
            CosmosSystem system, SurfaceDefinition definition) {
        FlightOrientation orientation = earthOrientation();
        return orientation == null ? definition.frame(system, orbitalSeconds(), clockTicks())
                : definition.calendarFrame(system, orbitalSeconds(), orientation);
    }

    /** A binding is usable only in its actual persistent dimension; stale flight packets cannot paint the Overworld. */
    public SurfaceDefinition definition(ClientLevel level) {
        if (snapshot == null || snapshot.phase() == SurfacePayload.Phase.NONE || level == null
                || !level.dimension().location().getNamespace().equals("astraengine")
                || !level.dimension().location().getPath().equals("surface_" + snapshot.bodyId())) { return null; }
        return SurfaceDefinition.find("sol", snapshot.bodyId()).orElse(null);
    }

    /** Discards connection data without touching permanent worlds or Minecraft-owned shader programs. */
    public void clear() { snapshot = null; previousClock = 0; previousOrbit = 0; previousEarth = null; receivedAt = 0; }
}
