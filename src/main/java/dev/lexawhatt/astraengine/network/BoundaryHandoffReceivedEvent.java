package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Common-safe main-client-thread delivery of an authoritative prepared chart handoff. */
public final class BoundaryHandoffReceivedEvent extends Event {
    private final BoundaryHandoffPayload payload;
    public BoundaryHandoffReceivedEvent(BoundaryHandoffPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("A handoff payload is required"); }
        this.payload = payload;
    }
    public BoundaryHandoffPayload payload() { return payload; }
}
