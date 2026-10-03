package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.NavigationRules;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthEphemeris;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** One connected Earth-seam/orbit/Moon-ground/Earth journey; only the initial fixture setup relocates a player. */
final class PlanetaryJourneyScenario {
    private static final TicketType<String> TICKET = TicketType.create("astraengine_verify_journey", String::compareTo);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean restart;
    private final Properties checkpoint = new Properties();
    private final StringBuilder evidence = new StringBuilder("One connected journey: ordinary controls and public body approach after initial setup.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> loaded;
    private RocketController controller;
    private EarthChart earth;
    private CubeStorageChart moon;
    private BlockPos earthBlock;
    private BlockPos moonBlock;
    private Vec3 initialFeet;
    private String body = "earth";
    private String moonDimension;
    private int step;
    private int ticks;
    private int loadingFrames;
    private int stable;
    private int placementView;
    private long ascentSettledAt;
    private boolean condition;
    private boolean measuring;
    private boolean speedSent;
    private long since = System.nanoTime();

    PlanetaryJourneyScenario(boolean restart) {
        this.restart = restart;
        game.options.bobView().set(false); game.options.renderDistance().set(4); game.options.simulationDistance().set(5);
        game.options.pauseOnLostFocus = false;
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - since < 420_000_000_000L, "Integrated journey timed out at " + step + " body=" + body);
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (measuring && game.screen instanceof ReceivingLevelScreen) { loadingFrames++; }
        if (restart) { return restartTick(); }
        if (step == 0) {
            if (game.screen != null) { return false; }
            server(server -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                server.getGameRules().getRule(NavigationRules.TRAVEL_SECONDS).set(1, server);
                var calendar = EarthEphemeris.sample(AstraSky.profile(server), server.overworld().getDayTime(), 0);
                var system = ExplorationCatalog.get(server).system("sol");
                var body = system.bodies().stream().filter(value -> value.id().equals("earth")).findFirst().orElseThrow();
                var arrival = FlightDynamics.observation(system, body, calendar.orbitalSeconds());
                var normal = calendar.frame().toBodyPoint(arrival.position()).normalized();
                int version = EarthWorlds.terrainVersion(server);
                double ground = Math.max(0, new ContinentalTerrain(version, ContinentalTerrain.SEED).sample(normal).heightMeters());
                double upper = (Math.floor((ground + 100 + 2032) / EarthChart.HEIGHT) + 1) * EarthChart.HEIGHT - 2032;
                var address = GeographicPosition.fromBody(normal.multiply(body.radiusMeters() + upper - 2), body.radiusMeters());
                earth = EarthChart.owner(address, version).orElseThrow();
                var level = PlanetSurfaceWorlds.ensure(server, earth); var feet = earth.resolve(address).orElseThrow();
                initialFeet = new Vec3(feet.x(), feet.y(), feet.z()); earthBlock = BlockPos.containing(initialFeet).below();
                // A real platform two meters below the storage seam is deliberate initial fixture setup.
                for (int x = -5; x <= 5; x++) {
                    for (int z = -5; z <= 5; z++) { level.setBlock(earthBlock.offset(x, 0, z), Blocks.RED_CONCRETE.defaultBlockState(), 3); }
                }
                var player = player(server); player.setGameMode(GameType.CREATIVE);
                player.getInventory().setItem(0, new ItemStack(Items.RED_CONCRETE, 16)); player.getInventory().selected = 0;
                player.teleportTo(level, initialFeet.x, initialFeet.y, initialFeet.z, 0, -90);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
                evidence.append("initialEarthChart=").append(earth.dimensionId()).append(" platform=").append(earthBlock)
                        .append(" realTerrainBelow=").append(ground).append('\n');
            });
            next(); return false;
        }
        if (step == 1) {
            if (game.screen != null && !(game.screen instanceof CosmosMapScreen)) { return false; }
            if (game.level == null || !game.level.dimension().location().toString().equals(earth.dimensionId())
                    || !obtainController() || ticks < 40) { return false; }
            capture("earth-seam-platform"); measuring = true; game.mouseHandler.grabMouse();
            game.options.keyJump.setDown(true); next(); return false;
        }
        if (step == 2) {
            if (game.level.dimension().location().toString().equals(earth.dimensionId())) { return false; }
            game.options.keyJump.setDown(false);
            var current = EarthChart.forDimension(game.level.dimension().location().toString(), earth.terrainVersion()).orElseThrow();
            require(current.face() == earth.face() && current.band() == earth.band() + 1, "Ordinary seam movement chose a different owner");
            require(loadingFrames == 0, "Ordinary seam crossing showed a loading screen");
            evidence.append("ordinaryCreativeBandCrossing=true\n"); capture("earth-crossed"); tap(GLFW.GLFW_KEY_R);
            next(); return false;
        }
        if (step == 3 || step == 12) {
            if (!controller.active() || ticks < 20) { return false; }
            if (!prepareMotion(2560, true)) { return false; }
            game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 4 || step == 13) {
            if (!game.level.dimension().equals(RocketService.FLIGHT)) { return false; }
            release();
            if (ascentSettledAt == 0) { ascentSettledAt = System.nanoTime(); }
            if (++stable < 8 || System.nanoTime() - ascentSettledAt < 250_000_000L) { return false; }
            double height = altitude();
            require(height >= 99_999 && height < 101_000, "Ascent did not reach the physical100km shell");
            require(loadingFrames == 0, "Ascent displayed a loading screen");
            evidence.append(body).append(" fullAscentToSpace=true altitude=").append(height).append('\n');
            capture(body + "-space"); body = step == 4 ? "moon" : "earth";
            controller.setTargetBody(body); controller.action(FlightActionPayload.Action.APPROACH_BODY, body);
            next(); return false;
        }
        if (step == 5 || step == 14) {
            if (ticks < 50 || controller.snapshot().jumpTicks() != 0) { return false; }
            require(controller.active() && game.level.dimension().equals(RocketService.FLIGHT), "Public body approach lost flight ownership");
            var view = controller.view(); var frame = frame(view);
            double height = frame.toBodyPoint(view.position()).length() - frame.radiusMeters();
            require(height > 100_000 && height < frame.radiusMeters() * 1.5,
                    "Public approach did not arrive near " + body + ": " + height);
            if (!prepareMotion(1_000_000, false)) { return false; }
            capture(body + "-approached"); evidence.append(body).append(" publicApproach=true\n");
            game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 6 || step == 15) {
            if (game.level.dimension().equals(RocketService.FLIGHT)) { return false; }
            release(); if (ticks < 20) { return false; }
            require(controller.active(), "Manual entry unexpectedly ended free flight");
            if (!condition) {
                server(server -> {
                    var chart = PlanetSurfaceWorlds.getCube(player(server).serverLevel()).orElseThrow();
                    require(body.equals("earth") ? chart instanceof EarthChart
                            : chart instanceof dev.lexawhatt.astraengine.surface.PlanetChart p && p.profile().bodyId().equals("moon"),
                            "Physical entry selected another body"); condition = true;
                });
                return false;
            }
            if (!prepareMotion(2560, false)) { return false; }
            game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 7 || step == 16) {
            if (!condition) {
                if (ticks % 5 == 0) {
                    server(server -> {
                        var player = player(server); var level = player.serverLevel();
                        var below = BlockPos.containing(player.getX(), player.getY() - .03, player.getZ());
                        condition = !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                                && player.getDeltaMovement().lengthSqr() < .0001;
                        if (ticks % 200 == 0) {
                            AstraEngine.LOGGER.info("ASTRA_JOURNEY_DESCENT body={} dimension={} feet={} motion={} below={} stopped={}",
                                    body, level.dimension().location(), player.position(), player.getDeltaMovement(), below, condition);
                        }
                        double altitude = player.getY() + PlanetSurfaceWorlds.getCube(level).orElseThrow().altitudeOriginMeters();
                        if (body.equals("earth") && Math.abs(altitude - earthBlock.getY()) < 64) {
                            var targetLevel = server.getLevel(EarthWorlds.dimension(earth));
                            var chunk = targetLevel.getChunkSource().getChunkNow(earthBlock.getX() >> 4, earthBlock.getZ() >> 4);
                            String actual = chunk == null ? "unloaded" : chunk.getBlockState(earthBlock).toString();
                            long shapes = java.util.stream.StreamSupport.stream(level.getBlockCollisions(player,
                                    player.getBoundingBox().expandTowards(0, -8, 0)).spliterator(), false).count();
                            AstraEngine.LOGGER.info("ASTRA_JOURNEY_PLATFORM dimension={} feet={} motion={} altitude={} noPhysics={} mode={} target={} sweptShapes={}",
                                    level.dimension().location(), player.position(), player.getDeltaMovement(), altitude,
                                    player.noPhysics, player.gameMode.getGameModeForPlayer(), actual, shapes);
                        }
                    });
                }
                return false;
            }
            release(); require(loadingFrames == 0, "Continuous descent displayed a loading screen");
            if (step == 16) {
                server(server -> {
                    var player = player(server);
                    require(player.serverLevel().dimension().equals(EarthWorlds.dimension(earth))
                            && player.position().distanceTo(initialFeet) < 8
                            && player.serverLevel().getBlockState(earthBlock).is(Blocks.RED_CONCRETE),
                            "Returning Earth flight missed or reset the original canonical landmark");
                });
            } else {
                server(server -> {
                    moon = PlanetSurfaceWorlds.getCube(player(server).serverLevel()).orElseThrow();
                    moonDimension = moon.dimensionId();
                    require(moon instanceof dev.lexawhatt.astraengine.surface.PlanetChart p && p.profile().bodyId().equals("moon"),
                            "Moon ground collision happened in another body");
                });
            }
            evidence.append(body).append(" manualDescentToRealGroundCollision=true\n");
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 8) {
            if (controller.active() || ticks < 20) { return false; }
            game.player.setYRot(0); game.player.setXRot(40); next(); return false;
        }
        if (step == 9) {
            if (ticks < 10) { return false; }
            BlockPos candidate = game.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    ? hit.getBlockPos().relative(hit.getDirection()) : null;
            if (candidate == null || !game.level.getBlockState(candidate).canBeReplaced()
                    || game.player.getBoundingBox().inflate(.02).intersects(new AABB(candidate))) {
                require(++placementView < 16, "Natural lunar ground offered no ordinary nonintersecting placement in reach");
                game.player.setYRot((placementView % 8) * 45); game.player.setXRot(placementView < 8 ? 40 : 20);
                ticks = 0; return false;
            }
            moonBlock = candidate; capture("moon-ground");
            game.options.keyUse.setDown(true); KeyMapping.click(game.options.keyUse.getKey()); next(); return false;
        }
        if (step == 10) {
            game.options.keyUse.setDown(false);
            require(ticks < 200, "Normal lunar placement was not accepted at " + moonBlock);
            if (!condition) {
                server(server -> condition = player(server).serverLevel().getBlockState(moonBlock).is(Blocks.RED_CONCRETE));
                return false;
            }
            if (ticks < 30) { return false; }
            evidence.append("moonNormalKeyPlacedCanonicalLandmark=true block=").append(moonBlock).append('\n');
            capture("moon-landmark"); game.player.setXRot(-90); next(); return false;
        }
        if (step == 11) {
            if (ticks < 20) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 17) {
            if (controller.active() || ticks < 30) { return false; }
            capture("earth-returned");
            server(server -> {
                var player = player(server);
                require(player.serverLevel().getBlockState(earthBlock).is(Blocks.RED_CONCRETE), "Original Earth platform changed");
                checkpoint.setProperty("version", "1"); checkpoint.setProperty("player", player.getUUID().toString());
                checkpoint.setProperty("earthDimension", earth.dimensionId()); checkpoint.setProperty("moonDimension", moonDimension);
                checkpoint.setProperty("earthBlock", block(earthBlock)); checkpoint.setProperty("moonBlock", block(moonBlock));
                checkpoint.setProperty("pose", player.getX() + "," + player.getY() + "," + player.getZ());
                checkpoint.setProperty("terrainVersion", Integer.toString(earth.terrainVersion()));
                long saveStarted = System.nanoTime();
                AstraEngine.LOGGER.info("ASTRA_JOURNEY_CHECKPOINT_SAVE_STARTED");
                server.getPlayerList().saveAll(); server.saveEverything(false, true, true);
                double saveMilliseconds = (System.nanoTime() - saveStarted) / 1_000_000.0;
                AstraEngine.LOGGER.info("ASTRA_JOURNEY_CHECKPOINT_SAVE_DONE elapsed_ms={}", saveMilliseconds);
                evidence.append("explicitCheckpointSaveMilliseconds=").append(saveMilliseconds).append('\n');
                evidence.append("earthReturnRetainedOriginalLandmark=true loadingFrames=").append(loadingFrames).append('\n');
            });
            next(); return false;
        }
        try (var output = Files.newOutputStream(game.gameDirectory.toPath().resolve("earth-journey-checkpoint.properties"),
                StandardOpenOption.CREATE_NEW)) { checkpoint.store(output, "One connected canonical Earth/Moon journey"); }
        return finish();
    }

    private boolean restartTick() throws Exception {
        if (step == 0) {
            if (game.screen != null) { return false; }
            try (var input = Files.newInputStream(game.gameDirectory.toPath().resolve("earth-journey-checkpoint.properties"))) { checkpoint.load(input); }
            require("1".equals(checkpoint.getProperty("version")), "Unsupported journey checkpoint");
            earthBlock = block(checkpoint.getProperty("earthBlock")); moonBlock = block(checkpoint.getProperty("moonBlock"));
            moonDimension = checkpoint.getProperty("moonDimension");
            server(server -> {
                var player = player(server); var pose = checkpoint.getProperty("pose").split(",");
                require(player.getUUID().toString().equals(checkpoint.getProperty("player"))
                        && player.serverLevel().dimension().location().toString().equals(checkpoint.getProperty("earthDimension"))
                        && player.position().distanceTo(new Vec3(Double.parseDouble(pose[0]), Double.parseDouble(pose[1]),
                                Double.parseDouble(pose[2]))) < .1, "Journey player identity or canonical pose did not survive restart");
                require(player.serverLevel().getBlockState(earthBlock).is(Blocks.RED_CONCRETE), "Earth landmark did not survive restart");
                var lunar = server.getLevel(dimension(moonDimension)); require(lunar != null, "Visited lunar chart was not restored");
                var chart = PlanetSurfaceWorlds.getCube(lunar).orElseThrow();
                require(chart instanceof dev.lexawhatt.astraengine.surface.PlanetChart p && p.profile().bodyId().equals("moon"),
                        "Restored lunar dimension belongs to another body");
                lunar.getChunkSource().addRegionTicket(TICKET, new ChunkPos(moonBlock), 0, "restart-landmark");
                loaded = lunar.getChunkSource().getChunkFuture(moonBlock.getX() >> 4, moonBlock.getZ() >> 4, ChunkStatus.FULL, true);
            });
            next(); return false;
        }
        if (step == 1) {
            if (loaded == null || !loaded.isDone()) { return false; } loaded.join();
            server(server -> {
                var lunar = server.getLevel(dimension(moonDimension));
                require(lunar.getBlockState(moonBlock).is(Blocks.RED_CONCRETE), "Moon landmark did not survive absence and server restart");
                lunar.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(moonBlock), 0, "restart-landmark");
                evidence.append("earthAndMoonCanonicalLandmarksAndPlayerPoseSurvivedRestart=true\n");
            });
            next(); return false;
        }
        if (ticks < 30) { return false; }
        capture("earth-restart"); return finish();
    }

    private boolean prepareMotion(double speed, boolean outward) throws Exception {
        if (!speedSent) {
            controller.setSpeed(speed); aim(outward); speedSent = true; stable = 0; return false;
        }
        if (controller.snapshot().speedMetersPerSecond() != speed) {
            if (ticks % 20 == 0) { controller.setSpeed(speed); } return false;
        }
        return ++stable >= 30;
    }
    private void aim(boolean outward) throws Exception {
        var view = controller.view();
        var direction = view.position().subtract(frame(view).centerMeters());
        var method = RocketController.class.getDeclaredMethod("aimDirection", SpaceVector.class); method.setAccessible(true);
        require((boolean) method.invoke(controller, outward ? direction : direction.multiply(-1)), "Could not aim the ordinary inspection camera");
    }
    private BodyFixedFrame frame(RocketController.View view) {
        if (body.equals("earth")) { return view.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth")); }
        var system = controller.currentSystem(); var descriptor = system.bodies().stream().filter(value -> value.id().equals(body)).findFirst().orElseThrow();
        return SolidPlanetProfile.create(system, descriptor).orElseThrow().frame(system, view.orbitalSeconds());
    }
    private double altitude() {
        var view = controller.view(); var frame = frame(view);
        return frame.toBodyPoint(view.position()).length() - frame.radiusMeters();
    }
    private boolean obtainController() {
        if (controller != null) { return true; }
        if (game.screen instanceof CosmosMapScreen map) { controller = map.controller(); map.onClose(); return true; }
        if (game.screen == null) { game.player.connection.sendCommand("astra-flight map"); } return false;
    }
    private void next() {
        step++; ticks = 0; stable = 0; speedSent = false; condition = false; ascentSettledAt = 0; since = System.nanoTime();
        AstraEngine.LOGGER.info("ASTRA_JOURNEY_VERIFY restart={} step={} body={}", restart, step, body);
        AstraEngine.LOGGER.info("ASTRA_JOURNEY_REACHED {}", evidence.toString().replace('\n', '|'));
    }
    private static ResourceKey<Level> dimension(String id) { return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id)); }
    private static String block(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static BlockPos block(String value) { var p = value.split(","); return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])); }
    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private void server(Consumer<MinecraftServer> action) { var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server); }
    private void release() { game.options.keyUp.setDown(false); game.options.keyJump.setDown(false); game.options.keyUse.setDown(false); }
    private void tap(int key) { game.mouseHandler.grabMouse(); KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void capture(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(directory.resolve("journey-" + name + ".png")); }
    }
    private boolean finish() throws Exception {
        release(); var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(restart ? "earth-journey-restart.txt" : "earth-journey-create.txt"), evidence, StandardOpenOption.CREATE_NEW);
        return true;
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
