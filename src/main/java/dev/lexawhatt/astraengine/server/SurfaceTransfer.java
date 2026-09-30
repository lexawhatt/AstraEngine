package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.SurfaceApproach;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfaceGeography;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** One server-session-owned prepared transfer. Tickets never own or replace persistent world data. */
final class SurfaceTransfer {
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_surface", UUID::compareTo, 240);
    private final SurfaceDefinition definition;
    private final boolean ascending;
    private final SpaceVector originalPosition;
    private final FlightOrientation originalOrientation;
    private final SpaceVector originalBodyPosition;
    private final FlightOrientation originalBodyOrientation;
    private SurfaceApproach route;
    private FlightOrientation startBodyOrientation;
    private FlightOrientation endBodyOrientation;
    private Vec3 landingFeet;
    private int preparationTicks;
    private int elapsedTicks;

    SurfaceTransfer(SurfaceDefinition definition, CosmosSystem system, long clockTicks,
            SpaceVector originalPosition, FlightOrientation originalOrientation) {
        this.definition = definition;
        this.originalPosition = originalPosition;
        this.originalOrientation = originalOrientation;
        ascending = false;
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        originalBodyPosition = frame.toBodyPoint(originalPosition);
        originalBodyOrientation = frame.toBodyOrientation(originalOrientation);
    }

    private SurfaceTransfer(SurfaceDefinition definition, CosmosSystem system, long clockTicks,
            SpaceVector localEye, FlightOrientation localOrientation, boolean departure) {
        this.definition = definition;
        ascending = true;
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        SpaceVector bodyStart = definition.patch().toBody(localEye);
        originalPosition = frame.toSystemPoint(bodyStart);
        startBodyOrientation = definition.patch().toBodyOrientation(localEye.x(), localEye.z(), localOrientation);
        originalOrientation = frame.toSystemOrientation(startBodyOrientation);
        originalBodyPosition = bodyStart;
        originalBodyOrientation = startBodyOrientation;
        endBodyOrientation = startBodyOrientation;
        CelestialBody body = system.bodies().stream().filter(value -> value.id().equals(definition.bodyId()))
                .findFirst().orElseThrow();
        SpaceVector end = bodyStart.normalized().multiply(FlightDynamics.safeRadius(body) + 20_000);
        route = new SurfaceApproach(bodyStart, end, body.radiusMeters(), true);
    }

    static SurfaceTransfer ascent(SurfaceDefinition definition, CosmosSystem system, long clockTicks,
            SpaceVector localEye, FlightOrientation localOrientation) {
        return new SurfaceTransfer(definition, system, clockTicks, localEye, localOrientation, true);
    }

    SurfaceDefinition definition() { return definition; }
    boolean ascending() { return ascending; }
    boolean prepared() { return route != null; }
    boolean timedOut() { return preparationTicks > 220; }
    boolean finished() { return route != null && elapsedTicks >= route.durationTicks(); }
    int remainingTicks() { return route == null ? 1 : route.durationTicks() - elapsedTicks; }
    SpaceVector originalPosition() { return originalPosition; }
    FlightOrientation originalOrientation() { return originalOrientation; }
    Vec3 landingFeet() { return landingFeet; }
    Frame sourceFrame(CosmosSystem system, long clockTicks) {
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        return new Frame(frame.toSystemPoint(originalBodyPosition), frame.toSystemOrientation(originalBodyOrientation));
    }
    FlightOrientation landingOrientation() {
        return definition.patch().toLocalOrientation(landingFeet.x, landingFeet.z, endBodyOrientation);
    }

    /** Polls nine bounded full chunks without synchronously requesting generation or mutating destination blocks. */
    boolean prepare(MinecraftServer server, ServerPlayer player, CosmosSystem system, long clockTicks) {
        preparationTicks++;
        ServerLevel level = server.getLevel(SurfaceWorlds.dimension(definition));
        if (!SurfaceBindings.get(server).matches(level, definition)) { preparationTicks = 221; return false; }
        boolean ready = true;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (preparationTicks == 1 || preparationTicks % 100 == 0) {
                    level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 0, player.getUUID());
                }
                ready &= level.getChunkSource().getChunkNow(x, z) != null;
            }
        }
        if (!ready) { return false; }
        // Prefer the patch center, then search a bounded dry area. Player edits are never cleared to make room.
        for (int ring = 0; ring <= 14 && landingFeet == null; ring++) {
            for (int x = -ring; x <= ring && landingFeet == null; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) { continue; }
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    Vec3 feet = new Vec3(x + 0.5, y, z + 0.5);
                    if (safeLanding(level, player, feet)) { landingFeet = feet; break; }
                }
            }
        }
        if (landingFeet == null) { preparationTicks = 221; return false; }
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        SpaceVector start = originalBodyPosition;
        SpaceVector localEye = new SpaceVector(landingFeet.x, landingFeet.y + player.getEyeHeight(), landingFeet.z);
        route = new SurfaceApproach(start, definition.patch().toBody(localEye), definition.patch().radiusMeters(), false);
        startBodyOrientation = originalBodyOrientation;
        FlightOrientation localStart = definition.patch().toLocalOrientation(landingFeet.x, landingFeet.z,
                startBodyOrientation);
        endBodyOrientation = definition.patch().toBodyOrientation(landingFeet.x, landingFeet.z,
                FlightOrientation.fromAngles(localStart.yaw(), 15, 0));
        return true;
    }

    /** Applies the current orbital/spin frame to an immutable body-fixed route; no secondary simulation clock. */
    Frame advance(CosmosSystem system, long clockTicks) {
        if (route == null || finished()) { throw new IllegalStateException("Surface route is not advancing"); }
        elapsedTicks++;
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        return new Frame(frame.toSystemPoint(route.positionAt(elapsedTicks)),
                frame.toSystemOrientation(route.orientationAt(elapsedTicks, startBodyOrientation, endBodyOrientation)));
    }

    boolean canCommit(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.getLevel(SurfaceWorlds.dimension(definition));
        return SurfaceBindings.get(server).matches(level, definition) && landingFeet != null
                && destinationReady(level) && safeLanding(level, player, landingFeet);
    }

    private boolean safeLanding(ServerLevel level, ServerPlayer player, Vec3 feet) {
        AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        // Excavated columns can lie below the shared analytic route shell. Try the next bounded candidate.
        if (feet.y + player.getEyeHeight() < definition.patch().seaY() + SurfaceGeography.MIN_HEIGHT_METERS
                || box.minX < -14 || box.maxX > 30 || box.minZ < -14 || box.maxZ > 30
                || !level.getWorldBorder().isWithinBounds(box)) { return false; }
        BlockPos floor = BlockPos.containing(feet).below();
        if (feet.y < level.getMinBuildHeight() + 1 || box.maxY >= level.getMaxBuildHeight()
                || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                || !level.getFluidState(floor).isEmpty()) { return false; }
        return level.noCollision(player, box) && !level.containsAnyLiquid(box);
    }

    private static boolean destinationReady(ServerLevel level) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) { return false; }
            }
        }
        return true;
    }

    void release(MinecraftServer server, UUID id) {
        if (ascending) { return; }
        ServerLevel level = server.getLevel(SurfaceWorlds.dimension(definition));
        if (level == null) { return; }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(x, z), 0, id);
            }
        }
    }

    void retain(MinecraftServer server, UUID id) {
        if (ascending || server.getTickCount() % 100 != 0) { return; }
        ServerLevel level = server.getLevel(SurfaceWorlds.dimension(definition));
        if (level == null) { return; }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 0, id);
            }
        }
    }

    record Frame(SpaceVector position, FlightOrientation orientation) {}
}
