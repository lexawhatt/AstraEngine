package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery; common networking does not load rendering classes. */
public final class NavigationPolicyReceivedEvent extends Event {
    private final NavigationPolicyPayload payload;

    /** Accepts a validated server payload for the current connection. */
    public NavigationPolicyReceivedEvent(NavigationPolicyPayload payload) {
        if (payload == null) { throw new IllegalArgumentException("Navigation policy payload is required"); }
        this.payload = payload;
    }

    /** Immutable connection policy; retain on resource reload and discard on logout. */
    public NavigationPolicyPayload payload() { return payload; }
}
