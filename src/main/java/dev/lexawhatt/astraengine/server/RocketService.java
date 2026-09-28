package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.BodyApproach;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.CustomSystemsPayload;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread owner of virtual rocket navigation and bounded, recoverable physical flight staging. */
public final class RocketService implements AutoCloseable {
    public static final ResourceKey<Level> FLIGHT = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("astraengine", "flight"));
    private static final String RECOVERY = "astraengine_flight_recovery";
    private static final TicketType<UUID> TICKET = TicketType.create("astraengine_flight", UUID::compareTo, 240);
    private static final SpaceVector ZERO = new SpaceVector(0, 0, 0);
    private final MinecraftServer server;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> lastAction = new HashMap<>();
    private final Map<UUID, List<String>> sentCustomIds = new HashMap<>();

    /** Creates the service owned by one Minecraft server. */
    public RocketService(MinecraftServer server) { this.server = server; }

    /** Whether this player is preparing or occupying rocket mode. Used to exclude other travel owners. */
    public boolean active(ServerPlayer player) { return sessions.containsKey(player.getUUID()); }
    /** Physical staging-world check, including orphaned sessions awaiting login recovery. */
    public static boolean isFlightWorld(ServerPlayer player) { return player.serverLevel().dimension().equals(FLIGHT); }

    /** Toggles the requesting player's presentation flight; no permissions or ability flags are elevated. */
    public void toggle(ServerPlayer player) {
        Session previous = sessions.remove(player.getUUID());
        if (previous != null) {
            stop(player, previous, true);
            send(player, null);
            return;
        }
        if (!player.isAlive() || player.isPassenger() || player.isSleeping()
                || player.serverLevel().dimension().equals(SystemWorlds.TRANSIT) || isFlightWorld(player)) {
            message(player, "unavailable"); return;
        }
        ServerLevel flight = server.getLevel(FLIGHT);
        if (flight == null) { message(player, "missing_world"); return; }
        ExplorationCatalog.get(server).player(player.getUUID());
        sessions.put(player.getUUID(), new Session(Point.of(player)));
        flight.getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, 0), 2, player.getUUID());
        message(player, "preparing");
    }

    /** Applies only one discrete own-player action per four ticks, rejecting uncharted destinations. */
    public void action(ServerPlayer player, FlightActionPayload payload) {
        int tick = server.getTickCount();
        Integer last = lastAction.get(player.getUUID());
        if (last != null && tick - last >= 0 && tick - last < 4) { return; }
        lastAction.put(player.getUUID(), tick);
        if (payload.action() == FlightActionPayload.Action.TOGGLE) { toggle(player); return; }
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()) { return; }
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        if (session.approach != null) {
            if (payload.action() == FlightActionPayload.Action.BRAKE) { finishApproach(catalog, player, session, "approach_cancelled"); }
            return;
        }
        if (session.jumpTicks > 0) { return; }
        switch (payload.action()) {
            case SCAN -> {
                CosmosSystem current = currentSystem(catalog, pilot, session);
                int added = 0;
                for (CosmosSystem system : CosmosGenerator.nearby(catalog.galaxySeed(), current.galaxyPosition(), 1)) {
                    if (pilot.discover(system.id())) { added++; }
                }
                player.sendSystemMessage(Component.translatable("astraengine.rocket.scanned", added,
                        pilot.discoveredSystems().size(), 256), true);
            }
            case JUMP_SYSTEM -> {
                if (!pilot.discoveredSystems().contains(payload.target()) || pilot.systemId().equals(payload.target())) {
                    message(player, "unknown_target"); return;
                }
                session.jumpTarget = payload.target(); session.jumpBody = ""; session.jumpTicks = 80;
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
            }
            case APPROACH_BODY -> {
                CosmosSystem system = currentSystem(catalog, pilot, session);
                CelestialBody body = system.bodies().stream().filter(value -> value.id().equals(payload.target()))
                        .findFirst().orElse(null);
                if (body == null) { message(player, "unknown_target"); return; }
                var route = BodyApproach.plan(system, body, new FlightDynamics.State(pilot.position(), pilot.velocity()),
                        pilot.orientation(), catalog.clockTicks() / 20.0);
                if (route.isEmpty()) { message(player, "approach_blocked"); return; }
                session.approach = route.get(); session.approachTicks = 0;
                session.jumpTarget = pilot.systemId(); session.jumpBody = payload.target();
                session.jumpTicks = session.approach.durationTicks();
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
                session.controls.relocate(pilot.revision()); session.input = null;
            }
            case SPEED_UP -> pilot.speed(Math.min(FlightDynamics.MAX_SPEED, pilot.speedMetersPerSecond() * 1.5));
            case SPEED_DOWN -> pilot.speed(Math.max(FlightDynamics.MIN_SPEED, pilot.speedMetersPerSecond() / 1.5));
            case BRAKE -> pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
            default -> { }
        }
        catalog.setDirty(); send(player, session);
    }

    /** Accepts finite sequential controls at most once per server tick; abandoned controls expire after ten ticks. */
    public void control(ServerPlayer player, FlightControlPayload payload) {
        Session session = sessions.get(player.getUUID());
        int tick = server.getTickCount();
        if (session == null || !session.entered || !isFlightWorld(player) || !player.isAlive()
                || !session.controls.accept(payload.sequence(), payload.navigationEpoch(), tick)) { return; }
        if (session.approach != null) {
            if (payload.brake()) { finishApproach(ExplorationCatalog.get(server), player, session, "approach_cancelled"); }
            return;
        }
        session.input = new FlightDynamics.Input(payload.forward(), payload.strafe(), payload.vertical(),
                payload.orientation(), payload.brake());
    }

    /** Polls preparation and advances virtual flight at 20 Hz without blocking on chunk generation. */
    public void tick() {
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        boolean occupied = sessions.entrySet().stream().anyMatch(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            return entry.getValue().entered && player != null && player.isAlive() && isFlightWorld(player);
        });
        catalog.tick(occupied);
        Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null) {
                if (session.approach != null) { stopApproachMotion(entry.getKey()); }
                sentCustomIds.remove(entry.getKey());
                release(entry.getKey()); iterator.remove(); continue;
            }
            if (!player.isAlive() || (session.entered && !isFlightWorld(player))) {
                stop(player, session, false); iterator.remove(); send(player, null); continue;
            }
            if (!session.entered) {
                if (++session.age > 220 || !player.serverLevel().dimension().equals(session.source.dimension())) {
                    stop(player, session, false); iterator.remove(); message(player, "failed"); continue;
                }
                ServerLevel flight = server.getLevel(FLIGHT);
                if (flight == null) { stop(player, session, false); iterator.remove(); continue; }
                if (flight.getChunkSource().getChunkNow(0, 0) == null) { continue; }
                initializeLanding(flight, catalog);
                if (!flight.isEmptyBlock(new BlockPos(8, 80, 8)) || !flight.isEmptyBlock(new BlockPos(8, 81, 8))) {
                    stop(player, session, false); iterator.remove(); message(player, "obstructed"); continue;
                }
                player.getPersistentData().put(RECOVERY, session.source.save());
                ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
                pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
                session.controls.relocate(pilot.revision());
                player.teleportTo(flight, 8.5, 80, 8.5, Set.of(), pilot.yaw(), pilot.pitch());
                session.entered = true; send(player, session);
            } else {
                player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
                if (player.position().distanceToSqr(8.5, 80, 8.5) > 0.01) { player.teleportTo(8.5, 80, 8.5); }
                if (server.getTickCount() % 100 == 0) {
                    player.serverLevel().getChunkSource().addRegionTicket(TICKET, new ChunkPos(0, 0), 2, player.getUUID());
                }
                advance(catalog, player, session);
                if (server.getTickCount() % 2 == 0) { send(player, session); }
            }
        }
    }

    private void advance(ExplorationCatalog catalog, ServerPlayer player, Session session) {
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        if (session.approach != null) {
            BodyApproach route = session.approach;
            BodyApproach.Frame next = route.frame(session.approachTicks + 1);
            double endSeconds = catalog.clockTicks() / 20.0;
            if (!FlightDynamics.clearSegment(pilot.position(), next.state().position(),
                    currentSystem(catalog, pilot, session).bodies(), Math.max(0, endSeconds - 0.05), endSeconds)) {
                finishApproach(catalog, player, session, "approach_failed");
                return;
            }
            pilot.navigate(next.state(), next.orientation());
            session.approachTicks++;
            session.jumpTicks = route.durationTicks() - session.approachTicks;
            if (session.jumpTicks == 0) { finishApproach(catalog, player, session, null); }
        } else if (session.jumpTicks > 0) {
            if (--session.jumpTicks == 0) {
                CosmosSystem system = catalog.system(session.jumpTarget);
                CelestialBody body = session.jumpBody.isEmpty() ? system.bodies().getFirst()
                        : system.bodies().stream().filter(value -> value.id().equals(session.jumpBody)).findFirst().orElseThrow();
                pilot.arrive(system.id(), ExplorationCatalog.arrival(system, body, catalog.clockTicks() / 20.0));
                session.system = system;
                session.controls.relocate(pilot.revision());
                session.input = null; session.jumpTarget = ""; session.jumpBody = "";
                player.teleportTo(8.5, 80, 8.5);
                player.setYRot(pilot.yaw()); player.setXRot(pilot.pitch());
                send(player, session);
            }
        } else {
            FlightDynamics.Input input = session.input;
            if (input == null || session.controls.expired(server.getTickCount())) {
                input = new FlightDynamics.Input(0, 0, 0, pilot.orientation(), true);
            }
            CosmosSystem system = currentSystem(catalog, pilot, session);
            FlightDynamics.State next = FlightDynamics.step(new FlightDynamics.State(pilot.position(), pilot.velocity()),
                    input, pilot.speedMetersPerSecond(), 0.05, system.bodies(), catalog.clockTicks() / 20.0);
            pilot.navigate(next, input.orientation());
        }
        catalog.setDirty();
    }

    private void finishApproach(ExplorationCatalog catalog, ServerPlayer player, Session session, String reason) {
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
        session.approach = null; session.approachTicks = 0;
        session.jumpTicks = 0; session.jumpTarget = ""; session.jumpBody = ""; session.input = null;
        session.controls.relocate(pilot.revision());
        catalog.setDirty();
        if (reason != null) { message(player, reason); }
        send(player, session);
    }

    private static CosmosSystem currentSystem(ExplorationCatalog catalog, ExplorationCatalog.Pilot pilot,
            Session session) {
        if (session.system == null || !session.system.id().equals(pilot.systemId())) {
            session.system = catalog.system(pilot.systemId());
        }
        return session.system;
    }

    /** Recovers an interrupted flight to its original real location, while retaining private discoveries. */
    public void recover(ServerPlayer player) {
        sentCustomIds.remove(player.getUUID());
        if (isFlightWorld(player)) { Point.load(player.getPersistentData().getCompound(RECOVERY), server).teleport(player, server); }
        player.getPersistentData().remove(RECOVERY);
        send(player, null);
    }

    /** Releases runtime tickets on logout. The persisted recovery point remains for the next login. */
    public void disconnect(ServerPlayer player) {
        Session session = sessions.remove(player.getUUID());
        if (session != null && session.approach != null) { stopApproachMotion(player.getUUID()); }
        lastAction.remove(player.getUUID()); release(player.getUUID());
        sentCustomIds.remove(player.getUUID());
    }

    /** Emits a private snapshot; permits map viewing before entering Rocket mode. */
    public void send(ServerPlayer player) { send(player, sessions.get(player.getUUID())); }

    private void send(ServerPlayer player, Session session) {
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        List<String> customIds = pilot.discoveredSystems().stream().filter(CosmosIds::isCustom).toList();
        if (!customIds.equals(sentCustomIds.get(player.getUUID()))) {
            // The ordered play connection delivers definitions before any snapshot can reference them.
            PacketDistributor.sendToPlayer(player, new CustomSystemsPayload(customIds.stream().map(catalog::system).toList()));
            sentCustomIds.put(player.getUUID(), customIds);
        }
        boolean active = session != null && session.entered && isFlightWorld(player);
        String target = !active ? "" : session.jumpTarget + (session.jumpBody.isEmpty() ? "" : "/" + session.jumpBody);
        PacketDistributor.sendToPlayer(player, new ExplorationPayload(catalog.galaxySeed(), catalog.clockTicks(),
                pilot.systemId(), pilot.position(), pilot.velocity(), active, pilot.speedMetersPerSecond(), pilot.orientation(),
                active ? session.jumpTicks : 0, target, pilot.discoveredSystems(), pilot.revision(),
                active ? session.controls.navigationEpoch() : 0));
    }

    private void stop(ServerPlayer player, Session session, boolean restore) {
        if (session.approach != null) { stopApproachMotion(player.getUUID()); }
        if (restore && session.entered && isFlightWorld(player) && player.isAlive()) { session.source.teleport(player, server); }
        player.getPersistentData().remove(RECOVERY); release(player.getUUID());
    }

    private static void initializeLanding(ServerLevel level, ExplorationCatalog catalog) {
        if (catalog.landingInitialized()) { return; }
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                BlockPos floor = new BlockPos(x, 79, z);
                if (level.isEmptyBlock(floor)) { level.setBlockAndUpdate(floor, Blocks.BARRIER.defaultBlockState()); }
            }
        }
        catalog.markLandingInitialized();
    }
    private void release(UUID id) {
        ServerLevel level = server.getLevel(FLIGHT);
        if (level != null) { level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(0, 0), 2, id); }
    }
    private static void message(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable("astraengine.rocket." + key), true);
    }
    private void stopApproachMotion(UUID id) {
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(id);
        pilot.navigate(new FlightDynamics.State(pilot.position(), ZERO), pilot.orientation());
        catalog.setDirty();
    }

    @Override
    public void close() {
        sessions.forEach((id, session) -> {
            if (session.approach != null) { stopApproachMotion(id); }
            release(id);
        });
        sessions.clear(); lastAction.clear(); sentCustomIds.clear();
    }

    private static final class Session {
        private final Point source;
        private CosmosSystem system;
        private int age;
        private boolean entered;
        private final FlightInputWindow controls = new FlightInputWindow();
        private FlightDynamics.Input input;
        private BodyApproach approach;
        private int approachTicks;
        private int jumpTicks;
        private String jumpTarget = "";
        private String jumpBody = "";
        private Session(Point source) { this.source = source; }
    }
    private record Point(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {
        static Point of(ServerPlayer player) {
            return new Point(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot());
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.location().toString());
            tag.putDouble("x", position.x); tag.putDouble("y", position.y); tag.putDouble("z", position.z);
            tag.putFloat("yaw", yaw); tag.putFloat("pitch", pitch); return tag;
        }
        static Point load(CompoundTag tag, MinecraftServer server) {
            ResourceLocation location = ResourceLocation.tryParse(tag.getString("dimension"));
            if (location != null && tag.contains("x", Tag.TAG_DOUBLE) && tag.contains("y", Tag.TAG_DOUBLE)
                    && tag.contains("z", Tag.TAG_DOUBLE) && tag.contains("yaw", Tag.TAG_FLOAT) && tag.contains("pitch", Tag.TAG_FLOAT)) {
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
                ServerLevel level = server.getLevel(key);
                Vec3 position = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
                float yaw = tag.getFloat("yaw"), pitch = tag.getFloat("pitch");
                if (level != null && !key.equals(FLIGHT) && !key.equals(SystemWorlds.TRANSIT)
                        && Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                        && Math.abs(position.x) < 29_999_984 && Math.abs(position.z) < 29_999_984
                        && position.y >= level.getMinBuildHeight() && position.y < level.getMaxBuildHeight()
                        && Float.isFinite(yaw) && Float.isFinite(pitch)) {
                    return new Point(key, position, yaw, pitch);
                }
            }
            return new Point(Level.OVERWORLD, Vec3.atBottomCenterOf(server.overworld().getSharedSpawnPos()), 0, 0);
        }
        void teleport(ServerPlayer player, MinecraftServer server) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) { Point.load(new CompoundTag(), server).teleport(player, server); return; }
            player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        }
    }
}
