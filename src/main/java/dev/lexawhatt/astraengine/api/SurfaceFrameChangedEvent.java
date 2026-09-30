package dev.lexawhatt.astraengine.api;

import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Non-cancellable server-thread observation, posted after a player's geographic context is updated.
 * Empty previous means first observation/re-entry; empty current means loss of context. Both present means
 * the observed tile changed. This does not prove continuous travel: commands can skip arbitrarily many tiles.
 * Listeners must not retain the player beyond the callback; immutable snapshots may be retained independently.
 * No movement, chunk transfer, discovery grant or disk-save acknowledgment is implied by this event.
 */
public final class SurfaceFrameChangedEvent extends Event {
    private final ServerPlayer player;
    private final Optional<SurfaceFrameSnapshot> previous;
    private final Optional<SurfaceFrameSnapshot> current;

    /** Creates a fact notification, not an instruction; requires at least one nonempty context. */
    public SurfaceFrameChangedEvent(ServerPlayer player, Optional<SurfaceFrameSnapshot> previous,
            Optional<SurfaceFrameSnapshot> current) {
        if (player == null || previous == null || current == null || previous.isEmpty() && current.isEmpty()) {
            throw new IllegalArgumentException("A frame observation requires a player and at least one context");
        }
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    /** Host player for this callback; its state may already differ from the immutable previous observation. */
    public ServerPlayer player() { return player; }
    /** Last observed pose before the context change; empty on initial observation. */
    public Optional<SurfaceFrameSnapshot> previous() { return previous; }
    /** New observed context; empty on leave, death, respawn reset or disconnect. */
    public Optional<SurfaceFrameSnapshot> current() { return current; }
}
