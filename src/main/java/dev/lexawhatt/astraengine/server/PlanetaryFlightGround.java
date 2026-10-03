package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.PlanetaryInspectionAccess;
import dev.lexawhatt.astraengine.surface.InspectionFlightStep;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** One inspection session's bounded real-world movement and host chunk tickets. Server thread only. */
public final class PlanetaryFlightGround implements AutoCloseable {
    public static final double MAX_SPEED = InspectionFlightStep.TERRAIN_SPEED;
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_surface_inspection", UUID::compareTo, 60);
    private final MinecraftServer server;
    private final UUID playerId;
    private final java.lang.ref.WeakReference<ServerPlayer> owner;
    private final Set<Address> tickets = new HashSet<>();
    private boolean closed;

    public PlanetaryFlightGround(ServerPlayer player) {
        this.server = player.getServer(); this.playerId = player.getUUID();
        this.owner = new java.lang.ref.WeakReference<>(player);
        requireOpen(); ((PlanetaryInspectionAccess) player).astra$inspectionMovement(true);
    }

    /**
     * Moves through loaded canonical blocks using the host collision solver. Each collision substep is at most
     * 8m near nonempty terrain; larger interior sweeps require already loaded, entirely air sections.
     * Close to an approached storage seam the whole tick stays at most8m for the prepared observation.
     * Missing terrain retains the reached pose; no synchronous generation or procedural collision is used.
     */
    public FlightDynamics.State move(ServerPlayer player, CubeStorageChart chart, BodyFixedFrame frame,
            FlightDynamics.Input input, double speedMetersPerSecond) {
        requireOpen();
        if (player == null || owner.get() != player || player.getServer() != server
                || !player.getUUID().equals(playerId) || chart == null || frame == null) {
            throw new IllegalArgumentException("Inspection movement requires its player and planetary frame");
        }
        ((PlanetaryInspectionAccess) player).astra$inspectionMovement(true);
        var feet = vector(player.position());
        var bodyView = frame.toBodyOrientation(input.orientation());
        var localView = chart.tangentFrame(feet.x(), feet.z(), feet.y() + chart.altitudeOriginMeters()).toLocalOrientation(bodyView);
        player.setYRot(localView.yaw()); player.setXRot(localView.pitch());
        if (!chart.contains(feet)) { return state(player, chart, frame, SpaceVector.ZERO); }
        var desired = FlightDynamics.desiredVelocity(input, Math.min(InspectionFlightStep.AIR_SPEED, speedMetersPerSecond));
        var requested = chart.localVelocity(feet, frame.toBodyDirection(desired).multiply(.05));
        var host = InspectionFlightStep.seam(chart, feet, InspectionFlightStep.corridor(requested));
        boolean clearAir = desired.length() > MAX_SPEED && InspectionAirSweep.clear(player.serverLevel(), chart,
                player.getBoundingBox().expandTowards(host.x(), host.y(), host.z()).inflate(.001));
        if (!clearAir) {
            var terrainVelocity = FlightDynamics.desiredVelocity(input, Math.min(MAX_SPEED, speedMetersPerSecond));
            host = chart.localVelocity(feet, frame.toBodyDirection(terrainVelocity).multiply(.05));
            if (host.length() > 128) { host = host.normalized().multiply(128); }
            host = InspectionFlightStep.seam(chart, feet, host);
        }
        if (host.y() > 0) {
            double allowance = Math.max(0, dev.lexawhatt.astraengine.surface.PlanetarySpaceBoundary.ALTITUDE_METERS
                    + .01 - chart.altitudeOriginMeters() - feet.y());
            if (host.y() > allowance) { host = host.multiply(allowance / host.y()); }
        }
        var wanted = new HashSet<Address>();
        int count = Math.max(1, (int) Math.ceil(Math.hypot(host.x(), host.z()) / 16));
        for (int step = 0; step <= count; step++) {
            var p = feet.add(host.multiply(step / (double) count));
            int x = (int) Math.floor(p.x()) >> 4, z = (int) Math.floor(p.z()) >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    wanted.add(new Address(chart.dimensionId(), new ChunkPos(x + dx, z + dz)));
                }
            }
        }
        if (wanted.size() > 72) { throw new IllegalStateException("Inspection chunk corridor exceeded its bounded budget"); }
        var corridor = Set.copyOf(wanted);
        prefetchAltitude(chart, feet, requested.y(), wanted);
        tickets.removeIf(address -> {
            if (wanted.contains(address)) { return false; }
            release(address); return true;
        });
        tickets.addAll(wanted);
        boolean ready = true;
        for (var address : wanted) {
            var level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(address.dimension)));
            level.getChunkSource().addRegionTicket(TICKET, address.chunk, 0, playerId);
            if (corridor.contains(address)) {
                ready &= level.getChunkSource().getChunkNow(address.chunk.x, address.chunk.z) != null;
            }
        }
        Vec3 before = player.position();
        if (ready && host.length() > 0) {
            int steps = clearAir ? 1 : Math.max(1, (int) Math.ceil(host.length() / 8));
            Vec3 step = new Vec3(host.x() / steps, host.y() / steps, host.z() / steps);
            for (int i = 0; i < steps; i++) {
                player.move(MoverType.SELF, step);
                if (!chart.contains(vector(player.position()))) { break; }
            }
        }
        Vec3 moved = player.position().subtract(before);
        player.setDeltaMovement(moved); player.fallDistance = 0;
        // Position remains a normal authoritative host player position; the client renders interpolated snapshots.
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), localView.yaw(), localView.pitch());
        return state(player, chart, frame, vector(moved));
    }

    private void prefetchAltitude(CubeStorageChart source, SpaceVector feet, double vertical, Set<Address> wanted) {
        if (Math.abs(vertical) < .01) { return; }
        int direction = vertical > 0 ? 1 : -1;
        double lookAheadMeters = Math.max(256, Math.abs(vertical) * 80);
        for (int ahead = 1; ahead <= 2; ahead++) {
            var chart = source.chart(source.face(), source.band() + direction * ahead).orElse(null);
            if (chart == null || chart.minY() + chart.altitudeOriginMeters()
                    > dev.lexawhatt.astraengine.surface.PlanetarySpaceBoundary.ALTITUDE_METERS) { continue; }
            double entryAltitude = chart.minY() + chart.altitudeOriginMeters() + (direction < 0 ? chart.height() : 0);
            if (Math.abs(entryAltitude - feet.y() - source.altitudeOriginMeters()) > lookAheadMeters) { continue; }
            try { PlanetSurfaceWorlds.ensure(server, chart); }
            catch (PlanetSurfaceBindings.CapacityExceededException exhausted) {
                // Optional lookahead cannot evict stored worlds or turn an unavailable crossing into success.
                break;
            }
            int x = (int) Math.floor(feet.x()) >> 4, z = (int) Math.floor(feet.z()) >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    wanted.add(new Address(chart.dimensionId(), new ChunkPos(x + dx, z + dz)));
                }
            }
        }
    }

    public static FlightDynamics.State state(ServerPlayer player, CubeStorageChart chart, BodyFixedFrame frame,
            SpaceVector hostVelocityPerTick) {
        var feet = vector(player.position());
        var address = chart.projectedGeographic(feet);
        var eye = new dev.lexawhatt.astraengine.surface.GeographicPosition(address.latitudeRadians(), address.longitudeRadians(),
                address.altitudeMeters() + player.getEyeHeight());
        // pose requires canonical ownership; at a crossing the extended differential has the same finite analytic form.
        var plane = chart.face().outward().multiply(chart.radiusMeters()).add(chart.face().u().multiply(feet.x()))
                .add(chart.face().v().multiply(feet.z()));
        var up = address.normal();
        var horizontal = chart.face().u().multiply(hostVelocityPerTick.x()).add(chart.face().v().multiply(hostVelocityPerTick.z()));
        var physical = horizontal.subtract(up.multiply(up.dot(horizontal)))
                .multiply((chart.radiusMeters() + address.altitudeMeters()) / plane.length())
                .add(up.multiply(hostVelocityPerTick.y())).multiply(20);
        return new FlightDynamics.State(frame.toSystemPoint(eye.toBody(chart.radiusMeters())), frame.toSystemDirection(physical));
    }

    private static SpaceVector vector(Vec3 v) { return new SpaceVector(v.x, v.y, v.z); }
    private void release(Address address) {
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(address.dimension));
        var level = server.getLevel(key);
        if (level != null) { level.getChunkSource().removeRegionTicket(TICKET, address.chunk, 0, playerId); }
    }
    private void requireOpen() {
        if (closed || !server.isSameThread()) { throw new IllegalStateException("Inspection owner is closed or on the wrong thread"); }
    }
    @Override public void close() {
        requireOpen(); tickets.forEach(this::release); tickets.clear();
        var player = owner.get();
        if (player != null) { ((PlanetaryInspectionAccess) player).astra$inspectionMovement(false); player.setDeltaMovement(Vec3.ZERO); }
        closed = true;
    }
    private record Address(String dimension, ChunkPos chunk) { }
}
