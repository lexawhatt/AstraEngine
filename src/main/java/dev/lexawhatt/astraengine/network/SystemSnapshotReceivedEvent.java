package dev.lexawhatt.astraengine.network;

import net.neoforged.bus.api.Event;

/** Client-main-thread delivery without a common-code reference to any Minecraft client class. */
public final class SystemSnapshotReceivedEvent extends Event {
    private final SystemPayload payload;

    /** Delivers an already-decoded server snapshot to client presentation. */
    public SystemSnapshotReceivedEvent(SystemPayload payload) { this.payload = payload; }
    /** The immutable payload delivered on the client main thread. */
    public SystemPayload payload() { return payload; }
}
