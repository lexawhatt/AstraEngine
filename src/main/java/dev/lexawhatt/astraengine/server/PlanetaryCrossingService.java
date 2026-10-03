package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.BoundaryHandoffPayload;
import dev.lexawhatt.astraengine.surface.CubeChartRebase;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-thread owner of automatic canonical face/band crossings. Retains only the last reached valid host
 * pose, never a second saved player position. An unprepared/vetoed crossing stays at that valid pose and can
 * retry after preparation. Canonical chunks are not copied or edited by a handoff.
 */
public final class PlanetaryCrossingService implements AutoCloseable {
    private final MinecraftServer server;
    private final EarthBoundaryService boundaries;
    private final RocketService rocket;
    private final Map<UUID, Reached> reached = new HashMap<>();
    private boolean closed;

    public PlanetaryCrossingService(MinecraftServer server, EarthBoundaryService boundaries, RocketService rocket) {
        if (server == null || boundaries == null || rocket == null || !server.isSameThread()) {
            throw new IllegalArgumentException("Planetary crossings require their logical-server owners");
        }
        this.server = server; this.boundaries = boundaries; this.rocket = rocket;
    }

    /** Host has already validated ordinary movement. One bounded handoff at most per player per server tick. */
    public void tick() {
        requireOpen();
        var online = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) { online.add(player.getUUID()); observe(player); }
        reached.keySet().retainAll(online);
    }

    /** Tests and lifecycle adapters may observe an owned connected player without creating another ticking owner. */
    public void observe(ServerPlayer player) {
        requireOpen();
        if (player == null || player.getServer() != server) { throw new IllegalArgumentException("Foreign crossing player"); }
        var source = PlanetSurfaceWorlds.getCube(player.serverLevel()).orElse(null);
        if (source == null || !player.isAlive() || player.isRemoved() || player.isPassenger() || player.isSleeping()
                || rocket.active(player) && RocketService.isFlightWorld(player)) { forget(player); return; }
        var feet = new SpaceVector(player.getX(), player.getY(), player.getZ());
        var previous = reached.get(player.getUUID());
        if (source.contains(feet)) {
            reached.put(player.getUUID(), Reached.of(player, source)); return;
        }
        if (previous == null || previous.player.get() != player || !previous.chart.equals(source)) { return; }
        var velocity = player.getDeltaMovement();
        var target = CubeChartRebase.resolve(source, feet, new SpaceVector(velocity.x, velocity.y, velocity.z),
                FlightOrientation.fromAngles(player.getYRot(), player.getXRot(), 0)).orElse(null);
        var view = boundaries.snapshot(player).orElse(null);
        if (target == null || target.chart().equals(source) || !boundaries.ready(player) || view == null
                || !view.visibleFrom(target.chart(), target.feet()) || target.velocity().length() > 128) {
            hold(player, previous); return;
        }
        var destination = server.getLevel(EarthWorlds.dimension(target.chart()));
        if (destination == null) { hold(player, previous); return; }
        Vec3 position = new Vec3(target.feet().x(), target.feet().y(), target.feet().z());
        var volume = player.getDimensions(player.getPose()).makeBoundingBox(position).deflate(1e-7);
        for (int x = (int) Math.floor(volume.minX - 1) >> 4; x <= (int) Math.floor(volume.maxX + 1) >> 4; x++) {
            for (int z = (int) Math.floor(volume.minZ - 1) >> 4; z <= (int) Math.floor(volume.maxZ + 1) >> 4; z++) {
                if (destination.getChunkSource().getChunkNow(x, z) == null) { hold(player, previous); return; }
            }
        }
        if (!destination.noCollision(null, volume)) { hold(player, previous); return; }
        var payload = new BoundaryHandoffPayload(view.revision(), source, target.chart(), target.feet(),
                target.velocity(), target.orientation(), false);
        PacketDistributor.sendToPlayer(player, payload);
        Vec3 motion = new Vec3(target.velocity().x(), target.velocity().y(), target.velocity().z());
        float fallDistance = player.fallDistance;
        player.changeDimension(new DimensionTransition(destination, position, motion,
                target.orientation().yaw(), target.orientation().pitch(), DimensionTransition.DO_NOTHING));
        if (player.serverLevel() != destination || player.position().distanceToSqr(position) > 1e-8) {
            PacketDistributor.sendToPlayer(player, new BoundaryHandoffPayload(view.revision(), source, target.chart(),
                    target.feet(), target.velocity(), target.orientation(), true));
            if (player.serverLevel().dimension().location().toString().equals(source.dimensionId())) { hold(player, previous); }
            else { forget(player); }
            return;
        }
        // The pinned ServerPlayer implementation does not apply DimensionTransition.speed to players.
        player.setDeltaMovement(motion); player.fallDistance = fallDistance;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        reached.put(player.getUUID(), Reached.of(player, target.chart()));
    }

    private static void hold(ServerPlayer player, Reached previous) {
        player.connection.teleport(previous.feet.x(), previous.feet.y(), previous.feet.z(), player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** Retires only the matching entity instance on logout/death/replacement. Host persistence remains untouched. */
    public void forget(ServerPlayer player) {
        var value = reached.get(player.getUUID());
        if (value != null && value.player.get() == player) { reached.remove(player.getUUID()); }
    }

    @Override public void close() { requireOpen(); reached.clear(); closed = true; }
    private void requireOpen() {
        if (closed || !server.isSameThread()) { throw new IllegalStateException("Crossing owner is closed or on the wrong thread"); }
    }
    private record Reached(WeakReference<ServerPlayer> player, CubeStorageChart chart, SpaceVector feet) {
        private static Reached of(ServerPlayer player, CubeStorageChart chart) {
            return new Reached(new WeakReference<>(player), chart, new SpaceVector(player.getX(), player.getY(), player.getZ()));
        }
    }
}
