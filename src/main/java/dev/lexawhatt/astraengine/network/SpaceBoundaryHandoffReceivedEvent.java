package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Main-client-thread delivery of server-owned physical boundary preparation. */
public final class SpaceBoundaryHandoffReceivedEvent extends Event {
    private final SpaceBoundaryHandoffPayload payload;
    public SpaceBoundaryHandoffReceivedEvent(SpaceBoundaryHandoffPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("A boundary payload is required"); }
        this.payload = payload;
    }
    public SpaceBoundaryHandoffPayload payload() { return payload; }
}
