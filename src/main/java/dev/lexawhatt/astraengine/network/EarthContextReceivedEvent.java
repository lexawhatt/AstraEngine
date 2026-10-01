package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Common-safe main-client-thread delivery; the connection owner retains this context until logout. */
public final class EarthContextReceivedEvent extends Event {
    private final EarthContextPayload payload;

    /** Requires an already validated immutable payload. */
    public EarthContextReceivedEvent(EarthContextPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Earth context payload is required"); }
        this.payload = payload;
    }

    /** Immutable server-authored context. */
    public EarthContextPayload payload() { return payload; }
}
