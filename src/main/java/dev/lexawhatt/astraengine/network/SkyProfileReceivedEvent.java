package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery without references to physical-client classes from common code. */
public final class SkyProfileReceivedEvent extends Event {
    private final SkyProfilePayload payload;

    /** Receives fully validated settings; presentation must retain them across resource reload and clear on logout. */
    public SkyProfileReceivedEvent(SkyProfilePayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Sky profile payload must not be null"); }
        this.payload = payload;
    }

    /** Immutable authoritative snapshot for the current connection. */
    public SkyProfilePayload payload() { return payload; }
}
