package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery that does not reference any physical-client class from common code. */
public final class SolarReceivedEvent extends Event {
    private final SolarPayload payload;
    /** Delivers one fully validated authoritative solar snapshot. */
    public SolarReceivedEvent(SolarPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Solar payload must not be null"); }
        this.payload = payload;
    }
    /** Immutable payload for connection-owned presentation. */
    public SolarPayload payload() { return payload; }
}
