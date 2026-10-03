package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Validated Earth weather delivered on the logical client thread without common-code client dependencies. */
public final class EarthWeatherReceivedEvent extends Event {
    private final EarthWeatherPayload payload;

    /** The immutable snapshot belongs to this connection and survives shader reload only. */
    public EarthWeatherReceivedEvent(EarthWeatherPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Earth weather snapshot required"); }
        this.payload = payload;
    }

    public EarthWeatherPayload payload() { return payload; }
}
