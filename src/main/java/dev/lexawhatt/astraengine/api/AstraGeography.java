package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.PlanetaryGeographyState;
import dev.lexawhatt.astraengine.server.PlanetaryTerrainWorld;
import dev.lexawhatt.astraengine.surface.PlanetaryPose;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Read-only logical-server geography sampling. Does not load chunks, mutate player state or own another save clock. */
public final class AstraGeography {
    private static final ResourceLocation HIGHLANDS = ResourceLocation.parse(PlanetaryGeographyState.GEOGRAPHY_ID);

    private AstraGeography() {}

    /**
     * Samples actual host feet, stored velocity in meters per game tick and yaw/pitch (host players have no roll).
     * Requires a non-null player and its owning server thread, including in singleplayer. Dead/removed players,
     * unsupported worlds, foreign world instances and columns outside the playable highlands return empty.
     * No data is cached or persisted here: after restart the host's saved position remains authoritative.
     * Snapshot velocity is the differential of the nonlinear patch mapping, not a rotation-only approximation.
     * Stored velocity is not displacement measured between observations. Sampling does not require network membership;
     * trusted server code can also inspect unconnected host players without adding them to the tracker.
     */
    public static Optional<SurfaceFrameSnapshot> snapshot(ServerPlayer player) {
        if (player == null) { throw new IllegalArgumentException("A server player is required"); }
        var server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Geographic sampling requires the owning server thread");
        }
        var level = player.serverLevel();
        var patch = PlanetaryTerrain.PATCH;
        if (!player.isAlive() || player.isRemoved() || !level.dimension().equals(PlanetaryTerrainWorld.DIMENSION)
                || level != server.getLevel(PlanetaryTerrainWorld.DIMENSION)
                || !patch.contains(player.getX(), player.getZ())
                || !level.getWorldBorder().isWithinBounds(player.getX(), player.getZ())
                || patch.radiusMeters() + player.getY() - patch.seaY() <= 0) { return Optional.empty(); }
        var velocity = player.getDeltaMovement();
        PlanetaryPose pose = PlanetaryPose.fromPatch(patch,
                new SpaceVector(player.getX(), player.getY(), player.getZ()),
                new SpaceVector(velocity.x, velocity.y, velocity.z),
                FlightOrientation.fromAngles(player.getYRot(), player.getXRot(), 0));
        return Optional.of(new SurfaceFrameSnapshot(HIGHLANDS, level.dimension(),
                PlanetaryGeographyState.get(server).topology(), pose));
    }
}
