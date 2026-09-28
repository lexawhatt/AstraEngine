package dev.lexawhatt.astraengine.api;

import net.neoforged.bus.api.Event;

/** Non-cancellable server-thread notification after an authoritative change has been committed in memory. */
public final class StellarStateEvent extends Event {
    private final SystemSnapshot before;
    private final SystemSnapshot after;
    private final Kind kind;

    /** Creates a notification; listeners must not treat it as a durable cross-mod transaction. */
    public StellarStateEvent(SystemSnapshot before, SystemSnapshot after, Kind kind) {
        this.before = before;
        this.after = after;
        this.kind = kind;
    }

    /** State immediately before this mutation. */
    public SystemSnapshot before() { return before; }
    /** State after this mutation, independent of later changes. */
    public SystemSnapshot after() { return after; }
    /** Cause of the mutation; compare snapshots to detect a stage change. */
    public Kind kind() { return kind; }

    /** EXTRACTION may also change the stage; BURST is diagnostic and grants no material or energy. */
    public enum Kind { EXTRACTION, BURST }
}
