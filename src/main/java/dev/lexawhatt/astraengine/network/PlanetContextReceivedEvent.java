package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Common-safe main-client-thread notification of validated server chart context. */
public final class PlanetContextReceivedEvent extends Event {
    private final PlanetContextPayload payload;
    public PlanetContextReceivedEvent(PlanetContextPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Planet context payload is required"); }
        this.payload = payload;
    }
    public PlanetContextPayload payload() { return payload; }
}
