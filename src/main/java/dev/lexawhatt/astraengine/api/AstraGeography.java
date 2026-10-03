package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfaceReference;
import dev.lexawhatt.astraengine.surface.SurfaceReferences;
import dev.lexawhatt.astraengine.surface.GeographicReference;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.worldgen.ContinentalTerrainChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetaryTerrainChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.SurfaceChunkGenerator;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Read-only logical-server geography sampling. Does not load chunks, mutate player state or own another save clock. */
public final class AstraGeography {
    private AstraGeography() {}

    /**
     * Samples actual host feet, stored velocity in meters per game tick and yaw/pitch (host players have no roll).
     * Requires a non-null player and its owning server thread, including in singleplayer. Dead/removed players,
     * unsupported worlds, foreign world instances and columns outside the bound surface window return empty.
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
        var reference = planetaryReference(level).orElse(null);
        SpaceVector feet = new SpaceVector(player.getX(), player.getY(), player.getZ());
        if (!player.isAlive() || player.isRemoved() || reference == null || !reference.contains(feet)
                || !level.getWorldBorder().isWithinBounds(player.getX(), player.getZ())) { return Optional.empty(); }
        var velocity = player.getDeltaMovement();
        var pose = reference.pose(feet,
                new SpaceVector(velocity.x, velocity.y, velocity.z),
                FlightOrientation.fromAngles(player.getYRot(), player.getXRot(), 0));
        return Optional.of(new SurfaceFrameSnapshot(ResourceLocation.parse(reference.geographyId()),
                level.dimension(), reference.topology(), pose));
    }

    /**
     * Returns the immutable reference for a loaded world whose pinned generator matches its dimension.
     * Owning server thread only; null/wrong-thread access throws. Unsupported or foreign levels return absence.
     * Does not load chunks, save data, allocate a dimension or retain the level.
     */
    public static Optional<SurfaceReference> reference(ServerLevel level) {
        if (level == null) { throw new IllegalArgumentException("A server level is required"); }
        var server = level.getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("Geographic references require the owning server thread");
        }
        if (server.getLevel(level.dimension()) != level) { return Optional.empty(); }
        var reference = SurfaceReferences.forDimension(level.dimension().location().toString());
        if (reference.isEmpty()) { return Optional.empty(); }
        var generator = level.getChunkSource().getGenerator();
        boolean matches = generator instanceof SurfaceChunkGenerator surface
                && reference.get().dimensionId().equals("astraengine:surface_" + surface.definition().bodyId())
                || generator instanceof PlanetaryTerrainChunkGenerator
                && reference.get().dimensionId().equals("astraengine:terrain_highlands")
                || generator instanceof ContinentalTerrainChunkGenerator continental
                && reference.get().dimensionId().equals(continental.region().dimensionId());
        return matches ? reference : Optional.empty();
    }

    /**
     * Read-only binding for any implemented projection, including the saved Earth preset. Requires the owning
     * server thread and a non-null level. Unsupported/foreign levels return absence. The legacy patch-only
     * {@link #reference(ServerLevel)} API retains its original return type and behavior.
     */
    public static Optional<GeographicReference> planetaryReference(ServerLevel level) {
        var earth = dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds.getCube(level);
        return earth.<GeographicReference>map(chart -> chart).or(() -> reference(level).map(chart -> chart));
    }

    /**
     * Resolves latitude/longitude/altitude to host feet in an existing world on its server thread.
     * Absence means unbound geography, outside window/border, or outside the host build-height interval.
     * No teleport, collision search, chunk load or clipping is performed. Null arguments are invalid.
     */
    public static Optional<SpaceVector> resolve(ServerLevel level, GeographicPosition position) {
        if (position == null) { throw new IllegalArgumentException("A geographic position is required"); }
        return planetaryReference(level).flatMap(reference -> reference.resolve(position))
                .filter(feet -> feet.y() >= level.getMinBuildHeight() && feet.y() < level.getMaxBuildHeight()
                        && level.getWorldBorder().isWithinBounds(feet.x(), feet.z()));
    }
}
