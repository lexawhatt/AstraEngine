package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery without loading physical-client classes from the common entry point. */
public final class SurfaceReceivedEvent extends Event {
    private final SurfacePayload payload;

    /** Wraps one validated authoritative surface context. */
    public SurfaceReceivedEvent(SurfacePayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Surface context is required"); }
        this.payload = payload;
    }

    /** Returns immutable connection-owned presentation input. */
    public SurfacePayload payload() { return payload; }
}
