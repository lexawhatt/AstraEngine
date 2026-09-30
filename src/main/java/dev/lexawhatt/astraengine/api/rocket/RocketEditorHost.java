package dev.lexawhatt.astraengine.api.rocket;

import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Implement on a BlockEntity to host AstraEngine's editor without depending on its diagnostic block.
 * All methods run on the owning logical-server thread. Implementations own normal block persistence.
 */
public interface RocketEditorHost {
    /** Immutable current draft, including an empty draft; never null. */
    RocketBlueprint blueprint();
    /** Nonnegative monotonically increasing draft revision, persisted with the blueprint. */
    long revision();
    /** Consumer authorization in addition to common live-host, range, build and spawn-protection checks. */
    boolean canEdit(ServerPlayer player);
    /** Stores a validated draft atomically, advances revision once and marks the host dirty; never accepts null. */
    void setBlueprint(RocketBlueprint blueprint);
    /** Stable identity of this host's last deployed assembly; empty before deployment. */
    Optional<UUID> deployedAssembly();
    /** Persists deployment ownership without changing the draft revision; never accepts null. */
    void setDeployedAssembly(Optional<UUID> assembly);
}
