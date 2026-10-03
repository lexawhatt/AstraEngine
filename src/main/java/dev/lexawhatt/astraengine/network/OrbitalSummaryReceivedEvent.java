package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Common-safe delivery on the client main thread; contains immutable observations only. */
public final class OrbitalSummaryReceivedEvent extends Event {
    private final OrbitalSummaryPayload payload;
    public OrbitalSummaryReceivedEvent(OrbitalSummaryPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Orbital payload is required"); }
        this.payload = payload;
    }
    public OrbitalSummaryPayload payload() { return payload; }
}
