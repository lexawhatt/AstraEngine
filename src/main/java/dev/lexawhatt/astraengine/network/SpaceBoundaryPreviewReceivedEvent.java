package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Main-client-thread delivery of server-owned physical boundary preparation. */
public final class SpaceBoundaryPreviewReceivedEvent extends Event {
    private final SpaceBoundaryPreviewPayload payload;
    public SpaceBoundaryPreviewReceivedEvent(SpaceBoundaryPreviewPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("A boundary payload is required"); }
        this.payload = payload;
    }
    public SpaceBoundaryPreviewPayload payload() { return payload; }
}
