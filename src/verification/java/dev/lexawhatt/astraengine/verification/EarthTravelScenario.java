package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.EarthLandingPayload;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PreparedPlayerReturn;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthLandingTarget;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Real R/landing packets across continental storage, poles, mountain altitude and cancel/restart persistence. */
final class EarthTravelScenario {
    private static final TicketType<UUID> TICKET = TicketType.create("astra_verify_earth", UUID::compareTo, 300);
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final boolean restart;
    private final ContinentalTerrain terrain = new ContinentalTerrain(2, ContinentalTerrain.SEED);
    private final Consumer<SurfaceReceivedEvent> listener = this::receive;
    private final StringBuilder evidence = new StringBuilder("Geographic transfer native fixture\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private SurfacePayload surface;
    private EarthLandingTarget expected;
    private EarthChart sourceChart;
    private SpaceVector sourceFeet;
    private SpaceVector mountain;
    private boolean sourceReady;
    private boolean observedAscent;
    private boolean observedDescent;
    private RuntimeException failure;
    private int step;
    private int ticks;
    private int round;
    private BlockPos marker;
    private EarthChart markerChart;
    private String savedPose;

    EarthTravelScenario(String phase) {
        this.phase = phase;
        restart = phase.endsWith("-restart");
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, listener);
        game.options.bobView().set(false);
        game.options.hideGui = false;
        if (!restart) {
            Random random = new Random(41);
            double highest = 0;
            for (int i = 0; i < 20000; i++) {
                var normal = new SpaceVector(random.nextDouble() * 2 - 1,
                        random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1).normalized();
                double height = terrain.sample(normal).heightMeters();
                if (height > highest) { mountain = normal; highest = height; }
            }
            require(highest > 6000, "Mountain fixture is not above 6km");
        }
    }

    private void receive(SurfaceReceivedEvent event) {
        surface = event.payload();
        if (surface.phase() == SurfacePayload.Phase.ASCENDING) { observedAscent = true; }
        if (surface.phase() == SurfacePayload.Phase.DESCENDING) { observedDescent = true; }
        if (surface.phase() == SurfacePayload.Phase.PREPARING && controller != null && expected == null) {
            try {
                var snapshot = controller.snapshot();
                require(snapshot.clockTicks() == surface.clockTicks(), "Surface/navigation clocks differ at aim acceptance");
                var frame = SurfaceDefinition.byBody("earth").frame(controller.currentSystem(),
                        surface.clockTicks() / 20.0, surface.clockTicks());
                expected = EarthLandingTarget.aim(terrain, frame.toBodyPoint(snapshot.position()),
                        frame.toBodyDirection(snapshot.orientation().forward())).orElseThrow();
                evidence.append("round=").append(round).append(" target=").append(expected).append('\n');
                AstraEngine.LOGGER.info("ASTRA_EARTH_TRAVEL_TARGET round={} target={}", round, expected);
            } catch (RuntimeException exception) { failure = exception; }
        }
    }

    boolean tick() throws Exception {
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join();
        require(++ticks < 1600, "Earth travel stage timed out");
        if (restart) { return restartTick(); }
        switch (step) {
            case 0 -> { tap(GLFW.GLFW_KEY_M); next(); }
            case 1 -> {
                if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
                controller = map.controller(); map.onClose();
                sourceReady = false;
                next();
            }
            case 2 -> {
                if (!sourceReady) { server(this::prepareSource); ticks = 0; return false; }
                if (ticks < 60 || game.screen != null) { return false; }
                if (!game.level.dimension().location().toString().equals(sourceChart.dimensionId())) { return false; }
                shot("source");
                observedAscent = false;
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!controller.active() || !observedAscent) { return false; }
                if (surface.remainingTicks() > 250 && surface.remainingTicks() < 300) { shot("ascent"); }
                if (controller.snapshot().jumpTicks() != 0) { return false; }
                require(controller.snapshot().systemId().equals("sol"), "Earth departure used stale system identity");
                next();
            }
            case 4 -> {
                if (ticks < 35) { return false; }
                var body = controller.currentSystem().bodies().stream().filter(value -> value.id().equals("earth")).findFirst().orElseThrow();
                var aim = RocketController.class.getDeclaredMethod("aimDirection", SpaceVector.class);
                aim.setAccessible(true);
                // Presentation-only camera input; navigation, aim intersection and world transfer remain production-owned.
                require((boolean) aim.invoke(controller, controller.currentSystem().positionAt(body, controller.timeSeconds())
                        .subtract(controller.visualPosition())), "Could not aim camera at Earth");
                expected = null; observedDescent = false;
                next();
            }
            case 5 -> {
                if (ticks < 40) { return false; }
                if (expected == null) {
                    var frame = SurfaceDefinition.byBody("earth").frame(controller.currentSystem(),
                            controller.timeSeconds(), controller.timeSeconds() * 20);
                    expected = EarthLandingTarget.aim(terrain, frame.toBodyPoint(controller.visualPosition()),
                            frame.toBodyDirection(controller.orientation().forward())).orElseThrow();
                    PacketDistributor.sendToServer(new EarthLandingPayload(expected.chart().normal(expected.localFeet().x(),
                            expected.localFeet().z()), controller.snapshot().navigationEpoch() + 1));
                    return false;
                }
                if (ticks < 50) { return false; }
                require(controller.snapshot().jumpTicks() == 0, "Stale landing epoch started navigation");
                shot("orbit");
                // Retain the original body-fixed point across ten ticks of planetary rotation/network delay.
                PacketDistributor.sendToServer(new EarthLandingPayload(expected.chart().normal(expected.localFeet().x(),
                        expected.localFeet().z()), controller.snapshot().navigationEpoch()));
                evidence.append("round=").append(round).append(" delayed_view_target=").append(expected).append('\n');
                next();
            }
            case 6 -> {
                if (controller.active()) {
                    require(ticks < 30 || controller.snapshot().jumpTicks() != 0,
                            "Geographic landing was rejected or canceled before arrival: " + surface);
                    if (surface != null && surface.phase() == SurfacePayload.Phase.DESCENDING
                            && surface.remainingTicks() > 110 && surface.remainingTicks() < 125) { shot("descent"); }
                    return false;
                }
                require(expected != null && observedDescent, "Landing did not follow an accepted geographic descent");
                server(this::verifyLandingAndEdit);
                next();
            }
            case 7 -> {
                if (ticks < 35 || game.screen != null) { return false; }
                shot("landed");
                observedAscent = false;
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 8 -> {
                if (!controller.active() || !observedAscent || ticks < 12) { return false; }
                PacketDistributor.sendToServer(new FlightActionPayload(FlightActionPayload.Action.BRAKE, ""));
                next();
            }
            case 9 -> {
                if (controller.active() || ticks < 30) { return false; }
                server(server -> {
                    verifyEdit(server);
                    require(pose(player(server)).equals(savedPose), "Canceled ascent did not restore exact source pose");
                    evidence.append("round=").append(round).append(" cancel_return_pose=").append(savedPose).append('\n');
                });
                next();
            }
            case 10 -> {
                if (++round < 4) {
                    sourceReady = false; expected = null; step = 2; ticks = 0;
                } else {
                    server(server -> {
                        write("earth-travel-checkpoint.properties", markerChart.dimensionId() + "\n" + marker.getX()
                                + "\n" + marker.getY() + "\n" + marker.getZ() + "\n" + savedPose);
                        server.saveEverything(false, true, true);
                    });
                    next();
                }
            }
            case 11 -> { return finish(); }
            default -> throw new IllegalStateException("Unknown Earth travel fixture step");
        }
        return false;
    }

    private void prepareSource(MinecraftServer server) {
        EarthWorlds.validate(server);
        require(EarthWorlds.terrainVersion(server) == 2, "Fixture requires the new Earth preset");
        var normal = switch (round) {
            case 0 -> new SpaceVector(1, 0, 0);
            case 1 -> mountain;
            case 2 -> new SpaceVector(0, 1, 0);
            default -> new SpaceVector(0, -1, 0);
        };
        double altitude = Math.max(0, terrain.sample(normal).heightMeters()) + 40;
        var address = GeographicPosition.fromBody(normal.multiply(EarthChart.RADIUS_METERS + altitude), EarthChart.RADIUS_METERS);
        sourceChart = EarthChart.owner(address, 2).orElseThrow();
        sourceFeet = sourceChart.resolve(address).orElseThrow();
        var level = server.getLevel(EarthWorlds.dimension(sourceChart));
        var player = player(server);
        var center = new ChunkPos(BlockPos.containing(sourceFeet.x(), sourceFeet.y(), sourceFeet.z()));
        level.getChunkSource().addRegionTicket(TICKET, center, 2, player.getUUID());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (level.getChunkSource().getChunkNow(center.x + x, center.z + z) == null) { return; }
            }
        }
        server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.overworld().setDayTime(6000);
        server.overworld().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        player.teleportTo(level, sourceFeet.x(), sourceFeet.y(), sourceFeet.z(), 25, 20);
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        level.getChunkSource().removeRegionTicket(TICKET, center, 2, player.getUUID());
        sourceReady = true;
        evidence.append("round=").append(round).append(" source=").append(sourceChart).append(' ').append(sourceFeet).append('\n');
    }

    private void verifyLandingAndEdit(MinecraftServer server) {
        var player = player(server);
        var chart = EarthWorlds.chart(player.serverLevel()).orElseThrow();
        require(chart.equals(expected.chart()), "Landing selected a different storage chart");
        require(Math.hypot(player.getX() - expected.localFeet().x(), player.getZ() - expected.localFeet().z()) < 22,
                "Landing missed the accepted geographic column");
        require(player.serverLevel().noCollision(player), "Landing placed the player inside terrain");
        require(player.serverLevel().canSeeSky(player.blockPosition()), "Landing left the departure corridor obstructed");
        markerChart = chart;
        marker = BlockPos.containing(player.getX() + 3, player.getY() - 1, player.getZ());
        var level = player.serverLevel();
        level.setBlockAndUpdate(marker, Blocks.DIAMOND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(marker.above(), Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(marker.above());
        chest.setItem(5, new ItemStack(Items.AMETHYST_SHARD, 17)); chest.setChanged();
        player.getAbilities().flying = true; player.onUpdateAbilities();
        savedPose = pose(player);
        evidence.append("round=").append(round).append(" landed=").append(savedPose).append('\n');
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                String[] values = Files.readString(game.gameDirectory.toPath().resolve("earth-travel-checkpoint.properties"))
                        .split("\n", 5);
                markerChart = EarthChart.all(2).stream().filter(value -> value.dimensionId().equals(values[0]))
                        .findFirst().orElseThrow();
                marker = new BlockPos(Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3]));
                savedPose = values[4];
                server(server -> {
                    var player = player(server);
                    verifyEdit(server);
                    require(savedPose.equals(pose(player)), "Independent restart changed the saved geographic pose");
                    sourceFeet = new SpaceVector(player.getX(), player.getY(), player.getZ());
                    evidence.append("restart_pose=").append(savedPose).append('\n');
                    var level = player.serverLevel();
                    BlockPos first = player.blockPosition().below(), second = first.below();
                    var firstState = level.getBlockState(first); var secondState = level.getBlockState(second);
                    try {
                        level.setBlockAndUpdate(first, Blocks.WATER.defaultBlockState());
                        level.setBlockAndUpdate(second, Blocks.WATER.defaultBlockState());
                        player.setPos(sourceFeet.x(), sourceFeet.y() - .5, sourceFeet.z());
                        require(PreparedPlayerReturn.acceptsSource(server, player), "Above-water eye cannot retain a swimming source");
                        player.setPos(sourceFeet.x(), sourceFeet.y() - 2, sourceFeet.z());
                        require(!PreparedPlayerReturn.acceptsSource(server, player), "Submerged head became a return source");
                        player.setPos(sourceFeet.x(), sourceFeet.y() - .5, sourceFeet.z());
                        level.setBlockAndUpdate(first, Blocks.LAVA.defaultBlockState());
                        require(!PreparedPlayerReturn.acceptsSource(server, player), "Lava became a return source");
                        evidence.append("fluid_source=above_water_only_submerged_and_lava_rejected\n");
                    } finally {
                        player.setPos(sourceFeet.x(), sourceFeet.y(), sourceFeet.z());
                        level.setBlockAndUpdate(first, firstState); level.setBlockAndUpdate(second, secondState);
                    }
                });
                next();
            }
            case 1 -> {
                if (ticks < 70 || game.screen != null) { return false; }
                shot("restored"); tap(GLFW.GLFW_KEY_M); next();
            }
            case 2 -> {
                if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
                controller = map.controller(); map.onClose(); observedAscent = false;
                tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!controller.active() || !observedAscent || controller.snapshot().jumpTicks() != 0) { return false; }
                next();
            }
            case 4 -> {
                if (ticks < 10) { return false; }
                shot("return-orbit");
                expected = new EarthLandingTarget(markerChart, sourceFeet);
                observedDescent = false;
                PacketDistributor.sendToServer(new EarthLandingPayload(markerChart.normal(sourceFeet.x(), sourceFeet.z()),
                        controller.snapshot().navigationEpoch()));
                next();
            }
            case 5 -> {
                if (controller.active()) {
                    require(ticks < 30 || controller.snapshot().jumpTicks() != 0, "Saved geographic return was rejected");
                    return false;
                }
                require(observedDescent, "Saved geographic return skipped its route");
                server(server -> {
                    var player = player(server);
                    require(EarthWorlds.chart(player.serverLevel()).orElseThrow().equals(markerChart),
                            "Full return changed the saved storage owner");
                    require(Math.hypot(player.getX() - sourceFeet.x(), player.getZ() - sourceFeet.z()) < 22,
                            "Full return missed the saved build location");
                    verifyEdit(server);
                    evidence.append("full_orbit_return_pose=").append(pose(player)).append('\n');
                });
                next();
            }
            case 6 -> {
                if (ticks < 45 || game.screen != null) { return false; }
                shot("full-return"); return finish();
            }
            default -> throw new IllegalStateException("Unknown Earth restart stage");
        }
        return false;
    }

    private void verifyEdit(MinecraftServer server) {
        var level = server.getLevel(EarthWorlds.dimension(markerChart));
        require(level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "Travel lost the saved block edit");
        var chest = (ChestBlockEntity) level.getBlockEntity(marker.above());
        require(chest != null && chest.getItem(5).is(Items.AMETHYST_SHARD) && chest.getItem(5).getCount() == 17,
                "Travel lost the saved block entity inventory");
    }

    private boolean finish() throws Exception {
        write("evidence/" + phase + ".txt", evidence.toString());
        NeoForge.EVENT_BUS.unregister(listener);
        return true;
    }
    private void next() {
        step++; ticks = 0;
        AstraEngine.LOGGER.info("ASTRA_EARTH_TRAVEL_STAGE round={} step={}", round, step);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private static String pose(ServerPlayer player) {
        return player.level().dimension().location() + " " + Double.toHexString(player.getX()) + " "
                + Double.toHexString(player.getY()) + " " + Double.toHexString(player.getZ()) + " "
                + Float.toHexString(player.getYRot()) + " " + Float.toHexString(player.getXRot());
    }
    private void write(String name, String value) {
        try {
            var file = game.gameDirectory.toPath().resolve(name); Files.createDirectories(file.getParent());
            Files.writeString(file, value, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot retain Earth travel evidence", exception); }
    }
    private void shot(String name) throws Exception {
        var file = game.gameDirectory.toPath().resolve("evidence/" + phase + "-" + round + "-" + name + ".png");
        if (Files.exists(file)) { return; }
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error during geographic transfer");
    }
    private static void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " at step=" + step + " round=" + round); }
    }
}
