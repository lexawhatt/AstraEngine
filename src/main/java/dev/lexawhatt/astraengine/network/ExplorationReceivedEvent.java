package dev.lexawhatt.astraengine.network;

import java.util.Objects;
import net.neoforged.bus.api.Event;

/** Client-main-thread delivery without referencing client-only Minecraft classes in common code. */
public final class ExplorationReceivedEvent extends Event {
    private final ExplorationPayload payload;

    /** Creates a delivery event for one validated server snapshot. */
    public ExplorationReceivedEvent(ExplorationPayload payload) { this.payload = Objects.requireNonNull(payload); }
    /** Immutable discovery/navigation data owned by the receiving connection. */
    public ExplorationPayload payload() { return payload; }
}
