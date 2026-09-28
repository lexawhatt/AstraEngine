package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Non-cancellable logical-server notification after AstraCosmos grants a new private discovery.
 * Posted synchronously on the player's server thread after the catalog is marked dirty; this is
 * not a disk-save acknowledgment. Listeners must not retain the player beyond its server session.
 */
public final class CosmosDiscoveryEvent extends Event {
    private final ServerPlayer player;
    private final String systemId;

    /** Creates a notification for an already-committed discovery; does not itself grant access. */
    public CosmosDiscoveryEvent(ServerPlayer player, String systemId) {
        if (player == null) {
            throw new IllegalArgumentException("A discovery notification requires a server player");
        }
        this.player = player;
        this.systemId = CosmosIds.requireId(systemId);
    }

    /** The player whose private discovery record changed, owned by this logical server. */
    public ServerPlayer player() {
        return player;
    }

    /** Canonical ID now present in the player's private discovery record. */
    public String systemId() {
        return systemId;
    }
}
