package dev.lexawhatt.astraengine.network;

import java.util.Objects;
import net.neoforged.bus.api.Event;

/** Client-main-thread editor delivery, without loading client-only types from common code. */
public final class RocketEditorStateReceivedEvent extends Event {
    private final RocketEditorStatePayload payload;
    public RocketEditorStateReceivedEvent(RocketEditorStatePayload payload) { this.payload = Objects.requireNonNull(payload); }
    public RocketEditorStatePayload payload() { return payload; }
}
