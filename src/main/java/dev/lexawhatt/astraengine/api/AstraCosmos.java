package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Logical-server API for immutable astronomical descriptors and private discovery.
 * Every call requires the owning server thread. Consumers own gameplay permissions;
 * this API does not create dimensions, terrain, machines, or resource-economy state.
 */
public final class AstraCosmos {
    private AstraCosmos() {
    }

    /** Result of creating an immutable namespaced system in the server's saved catalog. */
    public enum CreateResult {
        CREATED,
        ALREADY_EXISTS,
        CONFLICT,
        LIMIT_REACHED
    }

    /** Result of charting an existing system for one player; this does not count as a visit. */
    public enum DiscoverResult {
        DISCOVERED,
        ALREADY_KNOWN,
        UNKNOWN_SYSTEM,
        LIMIT_REACHED
    }

    /**
     * Creates a custom descriptor under a namespaced ID, with normal Minecraft save ownership.
     * Repeating an identical descriptor is idempotent; a conflicting descriptor never overwrites
     * saved data. The immutable descriptor and its body list remain safe for the caller to retain.
     * Malformed/null inputs throw before mutation; an off-thread call throws IllegalStateException.
     * CREATED marks the catalog dirty but is not an immediate disk-commit guarantee.
     */
    public static CreateResult create(MinecraftServer server, CosmosSystem descriptor) {
        requireServer(server);
        if (descriptor == null) {
            throw new IllegalArgumentException("A custom system descriptor is required");
        }
        CelestialSystems.validateCustom(descriptor);
        return ExplorationCatalog.get(server).createSystem(descriptor);
    }

    /**
     * Resolves an immutable descriptor without changing discovery or loading dimension chunks.
     * Valid absent custom IDs and empty universe sectors return empty; malformed/null IDs throw. Built-in IDs resolve through
     * this server's generator seed. The descriptor remains valid after the server stops.
     */
    public static Optional<CosmosSystem> find(MinecraftServer server, String id) {
        requireServer(server);
        CosmosIds.requireId(id);
        return ExplorationCatalog.get(server).findSystem(id);
    }

    /**
     * Charts a system for this player without moving them, recording a visit, or unlocking fast travel.
     * Manual flight into a charted system unlocks fast travel. Consumers must check their own permissions.
     * A new discovery is saved normally and emits CosmosDiscoveryEvent after the in-memory mutation;
     * other results emit no event. Missing custom IDs and empty universe sectors return UNKNOWN_SYSTEM. Null/malformed
     * inputs throw and calls from outside the player's server thread throw IllegalStateException.
     */
    public static DiscoverResult discover(ServerPlayer player, String id) {
        if (player == null) {
            throw new IllegalArgumentException("A server player is required for cosmos discovery");
        }
        MinecraftServer server = player.server;
        requireServer(server);
        CosmosIds.requireId(id);
        DiscoverResult result = ExplorationCatalog.get(server).discover(player.getUUID(), id);
        if (result == DiscoverResult.DISCOVERED) {
            NeoForge.EVENT_BUS.post(new CosmosDiscoveryEvent(player, id));
        }
        return result;
    }

    private static void requireServer(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("A Minecraft server is required for the cosmos API");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("The cosmos API requires the owning server thread");
        }
    }
}
