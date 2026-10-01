package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthLandingTarget;
import dev.lexawhatt.astraengine.surface.SurfaceApproach;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfaceGeography;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** One server-session-owned prepared transfer. Tickets never own or replace persistent world data. */
final class SurfaceTransfer {
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_surface", UUID::compareTo, 240);
    private final SurfaceDefinition definition;
    private final EarthChart earthChart;
    private final int centerX;
    private final int centerZ;
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
        this(definition, null, 0, 0, system, clockTicks, originalPosition, originalOrientation);
    }

    SurfaceTransfer(EarthLandingTarget target, CosmosSystem system, long clockTicks,
            SpaceVector originalPosition, FlightOrientation originalOrientation) {
        this(SurfaceDefinition.byBody("earth"), target.chart(), (int) Math.floor(target.localFeet().x()),
                (int) Math.floor(target.localFeet().z()), system, clockTicks, originalPosition, originalOrientation);
    }

    private SurfaceTransfer(SurfaceDefinition definition, EarthChart earthChart, int centerX, int centerZ,
            CosmosSystem system, long clockTicks, SpaceVector originalPosition, FlightOrientation originalOrientation) {
        this.definition = definition;
        this.earthChart = earthChart;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.originalPosition = originalPosition;
        this.originalOrientation = originalOrientation;
        ascending = false;
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        originalBodyPosition = frame.toBodyPoint(originalPosition);
        originalBodyOrientation = frame.toBodyOrientation(originalOrientation);
    }

    private SurfaceTransfer(SurfaceDefinition definition, EarthChart earthChart, CosmosSystem system, long clockTicks,
            SpaceVector localEye, FlightOrientation localOrientation, boolean departure) {
        this.definition = definition;
        this.earthChart = earthChart;
        centerX = (int) Math.floor(localEye.x());
        centerZ = (int) Math.floor(localEye.z());
        ascending = true;
        var frame = definition.frame(system, clockTicks / 20.0, clockTicks);
        SpaceVector bodyStart = toBody(localEye);
        originalPosition = frame.toSystemPoint(bodyStart);
        startBodyOrientation = toBodyOrientation(localEye.x(), localEye.z(), localOrientation);
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
        return new SurfaceTransfer(definition, null, system, clockTicks, localEye, localOrientation, true);
    }

    static SurfaceTransfer ascent(EarthChart chart, CosmosSystem system, long clockTicks,
            SpaceVector localEye, FlightOrientation localOrientation) {
        return new SurfaceTransfer(SurfaceDefinition.byBody("earth"), chart, system, clockTicks,
                localEye, localOrientation, true);
    }

    SurfaceDefinition definition() { return definition; }
    ResourceKey<Level> dimension() {
        return earthChart == null ? SurfaceWorlds.dimension(definition) : EarthWorlds.dimension(earthChart);
    }
    boolean ascending() { return ascending; }
    boolean prepared() { return route != null; }
    boolean timedOut() { return preparationTicks > preparationLimit(); }
    private int preparationLimit() { return earthChart == null ? 220 : 900; }
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
        return toLocalOrientation(landingFeet.x, landingFeet.z, endBodyOrientation);
    }

    /** Polls nine bounded full chunks without synchronously requesting generation or mutating destination blocks. */
    boolean prepare(MinecraftServer server, ServerPlayer player, CosmosSystem system, long clockTicks) {
        preparationTicks++;
        ServerLevel level = server.getLevel(dimension());
        if (!matches(server, level)) { preparationTicks = preparationLimit() + 1; return false; }
        boolean ready = true;
        for (int x = chunkX() - 1; x <= chunkX() + 1; x++) {
            for (int z = chunkZ() - 1; z <= chunkZ() + 1; z++) {
                if (preparationTicks == 1 || preparationTicks % 100 == 0) {
                    level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 0, player.getUUID());
                }
                ready &= level.getChunkSource().getChunkNow(x, z) != null;
            }
        }
        if (!ready) { return false; }
        // Prefer the selected column, then a bounded dry area. Player edits are never cleared to make room.
        for (int ring = 0; ring <= 14 && landingFeet == null; ring++) {
            for (int x = -ring; x <= ring && landingFeet == null; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) { continue; }
                    int y = level.getHeight(earthChart == null ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
                            : Heightmap.Types.MOTION_BLOCKING, centerX + x, centerZ + z);
                    double standingY = y;
                    if (earthChart != null) {
                        BlockPos floor = new BlockPos(centerX + x, y - 1, centerZ + z);
                        var shape = level.getBlockState(floor).getCollisionShape(level, floor);
                        if (!shape.isEmpty()) { standingY = y - 1 + shape.max(Direction.Axis.Y); }
                    }
                    Vec3 feet = new Vec3(centerX + x + 0.5, standingY, centerZ + z + 0.5);
                    if (safeLanding(level, player, feet)) { landingFeet = feet; break; }
                }
            }
        }
        if (landingFeet == null) { preparationTicks = preparationLimit() + 1; return false; }
        SpaceVector start = originalBodyPosition;
        SpaceVector localEye = new SpaceVector(landingFeet.x, landingFeet.y + player.getEyeHeight(), landingFeet.z);
        route = new SurfaceApproach(start, toBody(localEye), definition.patch().radiusMeters(), false);
        startBodyOrientation = originalBodyOrientation;
        FlightOrientation localStart = toLocalOrientation(landingFeet.x, landingFeet.z, startBodyOrientation);
        endBodyOrientation = toBodyOrientation(landingFeet.x, landingFeet.z,
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
        ServerLevel level = server.getLevel(dimension());
        return matches(server, level) && landingFeet != null
                && destinationReady(level) && safeLanding(level, player, landingFeet);
    }

    private boolean safeLanding(ServerLevel level, ServerPlayer player, Vec3 feet) {
        AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        // Excavated columns can lie below the shared analytic route shell. Try the next bounded candidate.
        double altitude = earthChart == null ? feet.y - definition.patch().seaY() : feet.y + earthChart.altitudeOriginMeters();
        if (altitude + player.getEyeHeight() < SurfaceGeography.MIN_HEIGHT_METERS
                || box.minX < chunkX() * 16 - 14 || box.maxX > chunkX() * 16 + 30
                || box.minZ < chunkZ() * 16 - 14 || box.maxZ > chunkZ() * 16 + 30
                || !level.getWorldBorder().isWithinBounds(box)) { return false; }
        if (earthChart != null && (!earthChart.contains(new SpaceVector(feet.x, feet.y, feet.z))
                || !level.canSeeSky(BlockPos.containing(feet)))) { return false; }
        BlockPos floor = BlockPos.containing(feet.x, feet.y - 1e-5, feet.z);
        boolean waterArrival = earthChart != null && level.getFluidState(floor).is(FluidTags.WATER);
        if (feet.y < level.getMinBuildHeight() + 1 || box.maxY >= level.getMaxBuildHeight()
                || !waterArrival && (earthChart == null ? !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                        : level.getBlockState(floor).getCollisionShape(level, floor).isEmpty())
                || !waterArrival && !level.getFluidState(floor).isEmpty()) { return false; }
        return level.noCollision(player, box) && !level.containsAnyLiquid(box);
    }

    private boolean destinationReady(ServerLevel level) {
        for (int x = chunkX() - 1; x <= chunkX() + 1; x++) {
            for (int z = chunkZ() - 1; z <= chunkZ() + 1; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) { return false; }
            }
        }
        return true;
    }

    void release(MinecraftServer server, UUID id) {
        if (ascending) { return; }
        ServerLevel level = server.getLevel(dimension());
        if (level == null) { return; }
        for (int x = chunkX() - 1; x <= chunkX() + 1; x++) {
            for (int z = chunkZ() - 1; z <= chunkZ() + 1; z++) {
                level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(x, z), 0, id);
            }
        }
    }

    void retain(MinecraftServer server, UUID id) {
        if (ascending || server.getTickCount() % 100 != 0) { return; }
        ServerLevel level = server.getLevel(dimension());
        if (level == null) { return; }
        for (int x = chunkX() - 1; x <= chunkX() + 1; x++) {
            for (int z = chunkZ() - 1; z <= chunkZ() + 1; z++) {
                level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 0, id);
            }
        }
    }

    private int chunkX() { return Math.floorDiv(centerX, 16); }
    private int chunkZ() { return Math.floorDiv(centerZ, 16); }

    private boolean matches(MinecraftServer server, ServerLevel level) {
        return earthChart == null ? SurfaceBindings.get(server).matches(level, definition)
                : level != null && EarthWorlds.chart(level).map(earthChart::equals).orElse(false);
    }

    private SpaceVector toBody(SpaceVector localEye) {
        return earthChart == null ? definition.patch().toBody(localEye)
                : earthChart.tangentFrame(localEye.x(), localEye.z(),
                        localEye.y() + earthChart.altitudeOriginMeters()).originMeters();
    }

    private FlightOrientation toBodyOrientation(double x, double z, FlightOrientation orientation) {
        return earthChart == null ? definition.patch().toBodyOrientation(x, z, orientation)
                : earthChart.tangentFrame(x, z, 0).toBodyOrientation(orientation);
    }

    private FlightOrientation toLocalOrientation(double x, double z, FlightOrientation orientation) {
        return earthChart == null ? definition.patch().toLocalOrientation(x, z, orientation)
                : earthChart.tangentFrame(x, z, 0).toLocalOrientation(orientation);
    }

    record Frame(SpaceVector position, FlightOrientation orientation) {}
}
