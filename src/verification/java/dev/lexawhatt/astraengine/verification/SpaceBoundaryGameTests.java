package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.network.FlightSpeedPayload;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.mixin.ServerStorageAccessor;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.PlanetaryFlightGround;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SpaceBoundaryPreparation;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.EarthBoundaryPlan;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.PlanetaryInspectionAccess;
import java.lang.reflect.InvocationTargetException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.Util;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Dedicated-server failure paths using real canonical chunks and host dimension vetoes. Reflection arranges
 * the private virtual-flight session and observes its owned tickets; it never fabricates a capture or readiness.
 * Native fixtures separately cover the ordinary client input, transport and rendered successful crossing.
 */
@PrefixGameTestTemplate(false)
public final class SpaceBoundaryGameTests {
    private static final String RECOVERY = "astraengine_flight_recovery";

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void orbitShortcutRejectsUnboundSourcesAndPreservesVetoedSurface(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var base = SolidPlanetGameTests.chart("moon");
        var chart = new PlanetChart(base.profile(), CubeFace.NEGATIVE_Z, 5);
        var level = PlanetSurfaceWorlds.ensure(server, chart);
        level.getChunk(0, 0);
        staging(server).getChunk(0, 0);
        var unbound = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "NoOrbitSource"));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OrbitSource"));
        player.setPos(8.5, 80, 8.5);
        var rocket = new RocketService(server);
        try (var connection = new HostPlayerConnection(player);
                var otherConnection = new HostPlayerConnection(unbound)) {
            var request = new FlightActionPayload(FlightActionPayload.Action.ORBIT, "");
            rocket.action(unbound, request);
            helper.assertTrue(!rocket.active(unbound) && !unbound.getPersistentData().contains(RECOVERY),
                    "Unbound orbit request created flight or a recovery record");
            var catalog = ExplorationCatalog.get(server);
            var pilot = catalog.player(player.getUUID());
            invoke(pilot, "speed", 19_317.0);
            var sourcePosition = player.position();
            rocket.action(player, request);
            var sessions = (Map<?, ?>) value(rocket, "sessions");
            var session = sessions.get(player.getUUID());
            helper.assertTrue(session != null && (Boolean) value(session, "orbitRequested")
                            && player.serverLevel() == level && player.position().equals(sourcePosition),
                    "Orbit request must prepare asynchronously without moving its real source");
            var vetoes = new AtomicInteger();
            Consumer<EntityTravelToDimensionEvent> veto = event -> {
                if (event.getEntity() == player && event.getDimension().equals(RocketService.FLIGHT)) {
                    vetoes.incrementAndGet(); event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, veto);
            try {
                invoke(rocket, "leaveGround", player, session, chart,
                        new FlightDynamics.Input(0, 0, 0, pilot.orientation(), false), catalog);
            } finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(vetoes.get() == 1 && value(session, "ground") != null
                            && player.serverLevel() == level && player.position().equals(sourcePosition)
                            && !player.getPersistentData().contains(RECOVERY)
                            && pilot.speedMetersPerSecond() == 19_317,
                    "Vetoed orbit transfer changed ownership, source, recovery or selected speed");
            long epoch = (Long) invoke(value(session, "controls"), "navigationEpoch");
            rocket.control(player, new FlightControlPayload(0, 0, 0, pilot.orientation(), true, 1, epoch, true));
            helper.assertTrue(!(Boolean) value(session, "orbitRequested") && rocket.active(player)
                            && value(session, "ground") != null && player.position().equals(sourcePosition),
                    "Cancelling an orbit request must retain ordinary surface inspection and the reached pose");
        } finally { rocket.close(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void respawnRetiresExactInspectionOwnerAndStaleDisconnectCannotStopReplacement(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var base = SolidPlanetGameTests.chart("moon");
        var chart = new PlanetChart(base.profile(), CubeFace.NEGATIVE_X, 3);
        var nextChart = new PlanetChart(base.profile(), CubeFace.NEGATIVE_X, 4);
        var level = PlanetSurfaceWorlds.ensure(server, chart);
        var nextLevel = PlanetSurfaceWorlds.ensure(server, nextChart);
        var profile = new GameProfile(UUID.randomUUID(), "ReplacedPilot");
        var original = new FakePlayer(level, profile);
        var replacement = new FakePlayer(level, profile);
        original.setPos(8.5, 0, 8.5); replacement.setPos(8.5, 0, 8.5);
        var rocket = new RocketService(server);
        try (var originalConnection = new HostPlayerConnection(original);
                var replacementConnection = new HostPlayerConnection(replacement)) {
            rocket.toggle(original);
            var sessions = (Map<?, ?>) value(rocket, "sessions");
            Object oldSession = sessions.get(original.getUUID());
            var ground = (PlanetaryFlightGround) value(oldSession, "ground");
            var pilot = ExplorationCatalog.get(server).player(original.getUUID());
            double speed = pilot.speedMetersPerSecond();
            long epoch = (Long) invoke(value(oldSession, "controls"), "navigationEpoch");
            helper.assertTrue(rocket.active(original) && !rocket.active(replacement),
                    "Same-UUID replacement inherited another entity's inspection session");
            rocket.control(replacement, new FlightControlPayload(1, 0, 0, FlightOrientation.IDENTITY, false, 1, epoch, true));
            rocket.speed(replacement, new FlightSpeedPayload(1234));
            rocket.toggle(replacement);
            helper.assertTrue(sessions.get(original.getUUID()) == oldSession && value(oldSession, "input") == null
                    && pilot.speedMetersPerSecond() == speed,
                    "Replacement controls, speed or toggle changed the previous entity's live session");
            boolean rejected = false;
            try {
                ground.move(replacement, chart, (BodyFixedFrame) invoke(rocket, "chartFrame", chart,
                        ExplorationCatalog.get(server)), new FlightDynamics.Input(0, 0, 0, FlightOrientation.IDENTITY, true), 100);
            } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected && !((PlanetaryInspectionAccess) replacement).astra$inspectionMovement(),
                    "A same-UUID foreign entity gained inspection movement ownership");
            var reached = replacement.position(); var navigation = pilot.position();
            rocket.respawn(replacement);
            helper.assertTrue(!rocket.active(original) && !rocket.active(replacement)
                    && !((PlanetaryInspectionAccess) original).astra$inspectionMovement()
                    && !((PlanetaryInspectionAccess) replacement).astra$inspectionMovement()
                    && replacement.position().equals(reached) && pilot.position().equals(navigation),
                    "Respawn retained old movement ownership or changed reached physical/virtual positions");
            rocket.toggle(replacement);
            Object newSession = sessions.get(replacement.getUUID());
            helper.assertTrue(newSession != oldSession && rocket.active(replacement),
                    "Replacement could not establish its own fresh session");
            rocket.disconnect(original);
            rocket.toggle(original);
            helper.assertTrue(sessions.get(replacement.getUUID()) == newSession && rocket.active(replacement)
                    && ((PlanetaryInspectionAccess) replacement).astra$inspectionMovement(),
                    "A stale entity's disconnect or toggle retired its replacement's session");
            replacement.teleportTo(nextLevel, 8.5, 0, 8.5, Set.of(), 0, 0);
            helper.assertTrue(replacement.serverLevel() == nextLevel && rocket.active(replacement)
                    && sessions.get(replacement.getUUID()) == newSession,
                    "Same-entity canonical dimension travel was mistaken for a respawn");
            rocket.disconnect(replacement);
            helper.assertTrue(!rocket.active(replacement)
                    && !((PlanetaryInspectionAccess) replacement).astra$inspectionMovement(),
                    "Actual owner disconnect left inspection movement active");
        } finally {
            rocket.close();
            if (replacement.serverLevel() == nextLevel) {
                nextLevel.removePlayerImmediately(replacement, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void inspectionDisposalClearsItsOfflineOwnerWithoutChangingAbilities(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "InspectionOwner"));
        player.setPos(9.5, 80, 10.5);
        var position = player.position();
        boolean mayFly = player.getAbilities().mayfly, flying = player.getAbilities().flying;
        helper.assertTrue(player.getServer().getPlayerList().getPlayer(player.getUUID()) == null,
                "Fixture player unexpectedly belongs to the online player list");
        var inspection = new PlanetaryFlightGround(player);
        helper.assertTrue(((PlanetaryInspectionAccess) player).astra$inspectionMovement(), "Inspection did not own movement");
        player.setDeltaMovement(new Vec3(1, 2, 3));
        inspection.close();
        helper.assertTrue(!((PlanetaryInspectionAccess) player).astra$inspectionMovement()
                && player.getDeltaMovement().equals(Vec3.ZERO) && player.position().equals(position),
                "Disposal left an offline owner under inspection movement or changed its reached pose");
        helper.assertTrue(player.getAbilities().mayfly == mayFly && player.getAbilities().flying == flying,
                "Inspection disposal changed host ability permissions");
        boolean rejected = false;
        try { inspection.move(player, null, null, null, 100); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Closed inspection accepted more movement");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void readinessRejectsStaleAndForeignAcksAndInvalidatesActualEdits(GameTestHelper helper) {
        var fixture = new Fixture(helper, 4096);
        var preparation = fixture.preparation;
        long initialRevision = preparation.revision();
        fixture.rocket.spaceBoundaryReady(fixture.player, initialRevision);
        helper.assertTrue(!preparation.ready(fixture.player), "An early client assertion bypassed actual chunk capture");
        helper.startSequence().thenWaitUntil(() -> fixture.awaitCapture(helper)).thenExecute(() -> {
            var foreign = new FakePlayer(fixture.player.serverLevel(), fixture.player.getGameProfile());
            boolean rejected = false;
            try { preparation.acknowledge(foreign, initialRevision); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "A replaced player object reused the previous preparation capability");
            fixture.rocket.spaceBoundaryReady(fixture.player, initialRevision - 1);
            helper.assertTrue(!preparation.ready(fixture.player), "A stale acknowledgement authorized entry");
            fixture.rocket.spaceBoundaryReady(fixture.player, initialRevision);
            helper.assertTrue(preparation.ready(fixture.player), "Actual unchanged destination did not become ready");
            var changed = BlockPos.containing(fixture.feet.x() + 8, fixture.feet.y() + 4, fixture.feet.z());
            fixture.target.setBlock(changed, Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(!preparation.ready(fixture.player) && preparation.revision() > initialRevision,
                    "A real block change did not invalidate the acknowledged destination");
            fixture.rocket.spaceBoundaryReady(fixture.player, initialRevision);
            helper.assertTrue(!preparation.ready(fixture.player), "The invalidated revision became usable again");
        }).thenWaitUntil(() -> {
            fixture.awaitCapture(helper);
            fixture.rocket.spaceBoundaryReady(fixture.player, initialRevision);
            helper.assertTrue(!preparation.ready(fixture.player), "Old readiness leaked across recapture");
            fixture.rocket.spaceBoundaryReady(fixture.player, preparation.revision());
            helper.assertTrue(preparation.ready(fixture.player), "Waiting for stable actual recapture");
        }).thenExecute(() -> {
            helper.assertTrue(fixture.player.serverLevel().dimension().equals(RocketService.FLIGHT),
                    "Preparation moved the player before the crossing owner committed");
            fixture.close();
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void brakingTurningAwayAndDisconnectRetireOnlyRuntimePreparation(GameTestHelper helper) {
        for (int mode = 0; mode < 3; mode++) {
            try (var fixture = new Fixture(helper, 4352 + mode * 64)) {
                var before = fixture.pilotPosition();
                long revision = fixture.preparation.revision();
                FlightDynamics.Input input = switch (mode) {
                    case 0 -> new FlightDynamics.Input(1, 0, 0, fixture.inward(), true);
                    case 1 -> new FlightDynamics.Input(1, 0, 0, fixture.outward(), false);
                    default -> new FlightDynamics.Input(0, 0, 0, fixture.inward(), false);
                };
                helper.assertTrue(!fixture.enter(input), "Cancelled preparation retained control of the flight tick");
                helper.assertTrue(value(fixture.session, "boundary") == null
                        && Boolean.TRUE.equals(value(fixture.preparation, "closed"))
                        && ((Set<?>) value(fixture.preparation, "tickets")).isEmpty(),
                        "Cancelled preparation retained a destination owner or tickets");
                fixture.rocket.spaceBoundaryReady(fixture.player, revision);
                helper.assertTrue(value(fixture.session, "boundary") == null && fixture.pilotPosition().equals(before)
                        && fixture.player.serverLevel().dimension().equals(RocketService.FLIGHT),
                        "Late acknowledgement revived a cancelled route or changed its reached pose");
            }
        }
        try (var fixture = new Fixture(helper, 4608)) {
            CompoundTag saved = fixture.player.getPersistentData().getCompound(RECOVERY).copy();
            var before = fixture.pilotPosition();
            long revision = fixture.preparation.revision();
            fixture.rocket.disconnect(fixture.player);
            helper.assertTrue(!fixture.rocket.active(fixture.player)
                    && Boolean.TRUE.equals(value(fixture.preparation, "closed"))
                    && ((Set<?>) value(fixture.preparation, "tickets")).isEmpty(),
                    "Disconnect retained preparation or owned chunk tickets");
            helper.assertTrue(saved.equals(fixture.player.getPersistentData().getCompound(RECOVERY))
                    && before.equals(fixture.pilotPosition()), "Disconnect erased durable recovery or virtual navigation");
            fixture.rocket.spaceBoundaryReady(fixture.player, revision);
            helper.assertTrue(!fixture.rocket.active(fixture.player), "Late disconnected acknowledgement restored flight ownership");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void actualDimensionVetoPreservesSourceAndRecoverableState(GameTestHelper helper) {
        var fixture = new Fixture(helper, 4864);
        helper.startSequence().thenWaitUntil(() -> {
            fixture.awaitCapture(helper);
            fixture.rocket.spaceBoundaryReady(fixture.player, fixture.preparation.revision());
            helper.assertTrue(fixture.preparation.ready(fixture.player), "Waiting for actual ready destination");
        }).thenExecute(() -> {
            var source = fixture.player.serverLevel(); var position = fixture.player.position();
            CompoundTag saved = fixture.player.getPersistentData().getCompound(RECOVERY).copy();
            var vetoes = new AtomicInteger();
            Consumer<EntityTravelToDimensionEvent> listener = event -> {
                if (event.getEntity() == fixture.player && event.getDimension().equals(fixture.target.dimension())) {
                    vetoes.incrementAndGet(); event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, listener);
            try {
                helper.assertTrue(fixture.enter(new FlightDynamics.Input(1, 0, 0, fixture.inward(), false)),
                        "Prepared shell entry did not reach its authoritative transition");
                helper.assertTrue(vetoes.get() == 1 && fixture.player.serverLevel() == source
                        && fixture.player.position().equals(position), "Dimension veto changed the real source pose");
                helper.assertTrue(value(fixture.session, "ground") == null && value(fixture.session, "boundary") == null
                        && ((Integer) value(fixture.session, "boundaryRetryTick")) > source.getServer().getTickCount(),
                        "Vetoed entry committed ground ownership or omitted retry backoff");
                fixture.rocket.disconnect(fixture.player);
                fixture.rocket.recover(fixture.player);
                helper.assertTrue(vetoes.get() == 2 && fixture.player.serverLevel() == source
                        && saved.equals(fixture.player.getPersistentData().getCompound(RECOVERY)),
                        "Vetoed recovery was incorrectly treated as a successful return");
            } finally { NeoForge.EVENT_BUS.unregister(listener); fixture.close(); }
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void occupiedCustomSurfaceChartsOnlyItsPilotAndRejectsFullDiscovery(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var catalog = ExplorationCatalog.get(server);
        var sol = CosmosGenerator.sol();
        var occupied = new CosmosSystem("astraengine:verify_occupied_surface",
                "Occupied surface fixture", 84921, sol.kind(), new SpaceVector(12_000, 13_000, 14_000), sol.bodies());
        var secret = new CosmosSystem("astraengine:verify_unrelated_surface",
                "Unrelated surface fixture", 84922, sol.kind(), new SpaceVector(15_000, 16_000, 17_000), sol.bodies());
        var created = catalog.createSystem(occupied); var secretCreated = catalog.createSystem(secret);
        helper.assertTrue(created == AstraCosmos.CreateResult.CREATED
                && secretCreated == AstraCosmos.CreateResult.CREATED,
                "Fixture descriptors must be unique in the disposable server");
        var moon = occupied.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var profile = SolidPlanetProfile.create(occupied, moon).orElseThrow();
        var chart = new PlanetChart(profile, CubeFace.POSITIVE_X, 3);
        var level = PlanetSurfaceWorlds.ensure(server, chart);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OccupiedPilot"));
        var peer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "PrivatePeer"));
        var full = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "FullPilot"));
        player.setPos(.5, 0, .5); peer.setPos(8.5, 0, .5); full.setPos(16.5, 0, .5);
        var pilot = catalog.player(player.getUUID()); var peerPilot = catalog.player(peer.getUUID());
        var peerDiscovered = peerPilot.discoveredSystems(); var peerVisited = peerPilot.visitedSystems();
        helper.assertTrue(!pilot.discoveredSystems().contains(occupied.id())
                && !pilot.visitedSystems().contains(occupied.id()), "Fixture already knows the occupied custom world");
        var rocket = new RocketService(server);
        try {
            rocket.action(player, new FlightActionPayload(
                    FlightActionPayload.Action.TOGGLE, ""));
            helper.assertTrue(rocket.active(player) && ((PlanetaryInspectionAccess) player).astra$inspectionMovement()
                    && pilot.systemId().equals(occupied.id()) && pilot.discoveredSystems().contains(occupied.id())
                    && pilot.visitedSystems().contains(occupied.id()),
                    "Actual occupied custom surface did not safely become this pilot's physical visit");
            helper.assertTrue(!pilot.discoveredSystems().contains(secret.id())
                    && peerPilot.discoveredSystems().equals(peerDiscovered) && peerPilot.visitedSystems().equals(peerVisited),
                    "Entering a physically occupied chart revealed another private system or changed another pilot");
            var fullPilot = catalog.player(full.getUUID());
            for (int index = 1; fullPilot.discoveredSystems().size() < 256 && index < 512; index++) {
                catalog.discover(full.getUUID(), "s_" + index + "_0_0");
            }
            helper.assertTrue(fullPilot.discoveredSystems().size() == 256, "Fixture did not reach the actual discovery bound");
            var beforePosition = fullPilot.position(); var beforeVisited = fullPilot.visitedSystems();
            var actualFeet = full.position(); long revision = fullPilot.revision();
            rocket.action(full, new FlightActionPayload(
                    FlightActionPayload.Action.TOGGLE, ""));
            helper.assertTrue(!rocket.active(full) && !((PlanetaryInspectionAccess) full).astra$inspectionMovement()
                    && !((Map<?, ?>) value(rocket, "sessions")).containsKey(full.getUUID())
                    && !fullPilot.discoveredSystems().contains(occupied.id()) && fullPilot.position().equals(beforePosition)
                    && fullPilot.visitedSystems().equals(beforeVisited) && fullPilot.revision() == revision
                    && full.position().equals(actualFeet),
                    "Full discovery catalog partially installed an inspection/session or mutated navigation");
        } finally { rocket.disconnect(player); rocket.disconnect(full); rocket.close(); }
        helper.assertTrue(!((PlanetaryInspectionAccess) player).astra$inspectionMovement(),
                "Custom surface inspection retained its movement flag after disposal");
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        private final RocketService rocket;
        private final FakePlayer player;
        private final PlanetChart chart;
        private final SpaceVector feet;
        private final ServerLevel target;
        private final ExplorationCatalog catalog;
        private final Object session;
        private final SpaceBoundaryPreparation preparation;
        private boolean closed;

        @SuppressWarnings("unchecked")
        private Fixture(GameTestHelper helper, int coordinate) {
            var server = helper.getLevel().getServer(); var base = SolidPlanetGameTests.chart("moon");
            chart = new PlanetChart(base.profile(), base.face(), 25);
            feet = new SpaceVector(coordinate + .5, 99_999.99 - chart.altitudeOriginMeters(), coordinate + .5);
            target = PlanetSurfaceWorlds.ensure(server, chart);
            for (var address : EarthBoundaryPlan.arrival(chart, feet).sections()) {
                PlanetSurfaceWorlds.ensure(server, address.chart()).getChunk(address.section().x(), address.section().z());
            }
            var staging = staging(server);
            staging.getChunk(0, 0);
            player = new FakePlayer(staging, new GameProfile(UUID.randomUUID(), "BoundaryFlight"));
            player.setPos(8.5, 80, 8.5);
            rocket = new RocketService(server); catalog = ExplorationCatalog.get(server);
            var source = new FakePlayer(target, new GameProfile(UUID.randomUUID(), "BoundaryOrigin"));
            source.setPos(feet.x(), feet.y(), feet.z());
            try {
                Class<?> pointType = Class.forName(RocketService.class.getName() + "$Point");
                var pointOf = pointType.getDeclaredMethod("of", ServerPlayer.class); pointOf.setAccessible(true);
                Object point = pointOf.invoke(null, source);
                var sessionType = Class.forName(RocketService.class.getName() + "$Session");
                var constructor = sessionType.getDeclaredConstructor(ServerPlayer.class, pointType); constructor.setAccessible(true);
                session = constructor.newInstance(player, point);
                player.getPersistentData().put(RECOVERY, (CompoundTag) invoke(point, "save"));
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot arrange flight fixture", exception); }
            var revisions = new AtomicLong();
            preparation = new SpaceBoundaryPreparation(player, chart, feet, revisions::incrementAndGet);
            set(session, "boundary", preparation); set(session, "boundaryBody", "moon"); set(session, "boundaryHolding", true);
            set(session, "entered", true); set(session, "system", catalog.system("sol"));
            ((Map<UUID, Object>) value(rocket, "sessions")).put(player.getUUID(), session);
            var pilot = catalog.player(player.getUUID());
            invoke(pilot, "speed", 100.0);
            var frame = frame(); var normal = chart.geographic(feet).normal();
            invoke(pilot, "navigate", new FlightDynamics.State(frame.toSystemPoint(normal.multiply(chart.radiusMeters()
                    + 100_000 + player.getEyeHeight() + 1)), SpaceVector.ZERO), inward());
            ((GameTestInfo) value(helper, "testInfo")).addListener(new GameTestListener() {
                @Override public void testStructureLoaded(GameTestInfo info) { }
                @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
                @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
                @Override public void testAddedForRerun(GameTestInfo previous, GameTestInfo next, GameTestRunner runner) { close(); }
            });
        }

        private void awaitCapture(GameTestHelper helper) {
            preparation.tick(player);
            helper.assertTrue(value(preparation, "snapshot") != null, "Waiting for actual loaded and lit arrival sections");
        }
        private BodyFixedFrame frame() { return (BodyFixedFrame) invoke(rocket, "chartFrame", chart, catalog); }
        private FlightOrientation inward() { return view(frame().toSystemDirection(chart.geographic(feet).normal()).multiply(-1)); }
        private FlightOrientation outward() { return view(frame().toSystemDirection(chart.geographic(feet).normal())); }
        private SpaceVector pilotPosition() { return catalog.player(player.getUUID()).position(); }
        private boolean enter(FlightDynamics.Input input) {
            return (Boolean) invoke(rocket, "enterBoundary", catalog, player, session, catalog.system("sol"), pilotPosition(), input);
        }
        @Override public void close() { if (!closed) { rocket.disconnect(player); rocket.close(); closed = true; } }
    }

    /** GameTestServer opens only its flat Overworld; instantiate the shipped staging definition in this fixture. */
    private static ServerLevel staging(MinecraftServer server) {
        var existing = server.getLevel(RocketService.FLIGHT);
        if (existing != null) { return existing; }
        LevelStem stem;
        try (var input = server.getResourceManager().open(
                net.minecraft.resources.ResourceLocation.parse("astraengine:dimension/flight.json"))) {
            stem = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()),
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))).getOrThrow();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read the shipped flight fixture definition", exception);
        }
        var progress = new ChunkProgressListener() {
            @Override public void updateSpawnPos(ChunkPos center) { }
            @Override public void onStatusChange(ChunkPos position, ChunkStatus status) { }
            @Override public void start() { }
            @Override public void stop() { }
        };
        var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        var level = new ServerLevel(server, Util.backgroundExecutor(),
                ((ServerStorageAccessor) server).astraengine$storageSource(), data, RocketService.FLIGHT, stem,
                progress, false, BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed()),
                List.of(), false, server.overworld().getRandomSequences());
        level.getWorldBorder().setCenter(8.5, 8.5); level.getWorldBorder().setSize(128);
        server.forgeGetWorldMap().put(RocketService.FLIGHT, level); server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    private static FlightOrientation view(SpaceVector direction) {
        return FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                Math.toDegrees(Math.asin(Math.clamp(-direction.y(), -1, 1))), 0);
    }
    private static Object value(Object owner, String name) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot inspect fixture field " + name, exception); }
    }
    private static void set(Object owner, String name, Object value) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot arrange fixture field " + name, exception); }
    }
    private static Object invoke(Object owner, String name, Object... arguments) {
        var method = Arrays.stream(owner.getClass().getDeclaredMethods())
                .filter(value -> value.getName().equals(name) && value.getParameterCount() == arguments.length).findFirst().orElseThrow();
        try { method.setAccessible(true); return method.invoke(owner, arguments); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException failure) { throw failure; }
            if (exception.getCause() instanceof Error failure) { throw failure; }
            throw new IllegalStateException("Fixture invocation failed: " + name, exception.getCause());
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot invoke " + name, exception); }
    }
}
