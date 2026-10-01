package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Common-safe main-client-thread delivery of validated, immutable neighboring block observations. */
public final class EarthBoundaryReceivedEvent extends Event {
    private final EarthBoundaryPayload payload;

    /** Requires the already decoded server payload; contains no client classes or world references. */
    public EarthBoundaryReceivedEvent(EarthBoundaryPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Earth boundary payload is required"); }
        this.payload = payload;
    }

    /** Immutable server-authored observations. */
    public EarthBoundaryPayload payload() { return payload; }
}
