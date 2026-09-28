package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery of validated custom descriptors without loading client-only classes in common code. */
public final class CustomSystemsReceivedEvent extends Event {
    private final CustomSystemsPayload payload;

    /** Creates a delivery event for the receiving connection; null is rejected. */
    public CustomSystemsReceivedEvent(CustomSystemsPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Custom descriptor delivery requires a payload");
        }
        this.payload = payload;
    }

    /** Immutable replacement snapshot belonging exclusively to the receiving connection. */
    public CustomSystemsPayload payload() {
        return payload;
    }
}
