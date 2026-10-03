package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.OrbitalEditAtlas;
import dev.lexawhatt.astraengine.client.surface.orbit.OrbitalSummaryClient;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SkyState;
import dev.lexawhatt.astraengine.server.orbit.OrbitalSummaryIndex;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthEphemeris;
import dev.lexawhatt.astraengine.surface.PlanetaryFrame;
import dev.lexawhatt.astraengine.surface.SurfaceHeightTile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Actual canonical landmark -> persisted server summaries -> real network -> production orbital material. */
final class OrbitalEditsScenario {
    private static final int SIDE = 128;
    private static final int TOP = 900;
    private static final EarthChart LANDMARK_CHART = new EarthChart(
            dev.lexawhatt.astraengine.surface.CubeFace.POSITIVE_X, 1);
    private static final net.minecraft.server.level.TicketType<String> TICKET =
            net.minecraft.server.level.TicketType.create("astraengine_verify_orbital", String::compareTo);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean restart;
    private final Consumer<ViewportEvent.ComputeCameraAngles> camera = event -> {
        if (this.orientation != null) {
            event.setYaw(this.orientation.yaw()); event.setPitch(this.orientation.pitch()); event.setRoll(this.orientation.roll());
        }
    };
    private final Consumer<RenderLevelStageEvent> render = this::render;
    private final StringBuilder evidence = new StringBuilder("Actual 128 m landmark with exposed street lights; all poses physical meters.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CosmosRenderer renderer;
    private OrbitalSummaryClient summaries;
    private EarthEphemeris.Sample calendar;
    private FlightOrientation orientation;
    private SpaceVector observer;
    private int step;
    private int frames;
    private int view;
    private long priorRevision;
    private RuntimeException failure;
    private double litNightPeak;
    private long checkpointRevision;
    private int reportedStep = -1;

    OrbitalEditsScenario(String phase) {
        restart = phase.endsWith("-restart");
        game.options.hideGui = true; game.options.fov().set(30);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, render);
    }

    boolean tick() throws Exception {
        if (reportedStep != step) {
            dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_ORBITAL_VERIFY step={} restart={}", step, restart);
            reportedStep = step;
        }
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; } pending.join();
        if (step == 0) {
            game.player.connection.sendCommand("astra-flight map"); step++; return false;
        }
        if (step == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            renderer = (CosmosRenderer) field(map.controller(), "renderer"); map.onClose();
            var atlas = typedField(renderer, OrbitalEditAtlas.class);
            summaries = typedField(atlas, OrbitalSummaryClient.class);
            require(summaries != null, "Renderer has no connection-owned orbital data");
            renderer.setContinentalEarth(ContinentalTerrain.CURRENT_VERSION);
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var level = landmarkLevel(server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                var futures = new ArrayList<CompletableFuture<?>>();
                for (int z = 0; z < SIDE / 16; z++) { for (int x = 0; x < SIDE / 16; x++) {
                    level.getChunkSource().addRegionTicket(TICKET, new net.minecraft.world.level.ChunkPos(x, z), 0, "orbital");
                    futures.add(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true));
                } }
                chunkReady = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            }, server);
            step++; return false;
        }
        if (step == 2) {
            if (chunkReady == null || !chunkReady.isDone()) { return false; } chunkReady.join();
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var level = landmarkLevel(server);
                if (restart) {
                    Properties checkpoint = new Properties();
                    try (var input = Files.newInputStream(game.gameDirectory.toPath()
                            .resolve("earth-orbital-edits-checkpoint.properties"))) {
                        checkpoint.load(input);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("Cannot read orbital restart checkpoint", exception);
                    }
                    require("1".equals(checkpoint.getProperty("version"))
                            && "sol".equals(checkpoint.getProperty("system"))
                            && "earth".equals(checkpoint.getProperty("body"))
                            && LANDMARK_CHART.dimensionId().equals(checkpoint.getProperty("dimension"))
                            && "0".equals(checkpoint.getProperty("x"))
                            && "0".equals(checkpoint.getProperty("z"))
                            && Integer.toString(SIDE).equals(checkpoint.getProperty("side"))
                            && Integer.toString(TOP).equals(checkpoint.getProperty("top")),
                            "Restart checkpoint does not describe this landmark");
                    long revision = Long.parseLong(checkpoint.getProperty("revision"));
                    require(revision > 0 && OrbitalSummaryIndex.get(server).pages().stream()
                            .anyMatch(patch -> patch.revision() >= revision), "Restart lost checkpoint summary revision");
                    evidence.append("validatedCheckpointRevision=").append(revision).append('\n');
                    require(level.getBlockState(new BlockPos(0, TOP, 0)).is(Blocks.GLOWSTONE), "Restart lost canonical street light");
                    require(OrbitalSummaryIndex.get(server).pages().stream().anyMatch(patch -> patch.cells().stream()
                            .anyMatch(cell -> cell.emission() > .01)), "Restart lost persisted emission directory");
                } else {
                    for (int z = 0; z < SIDE; z++) { for (int x = 0; x < SIDE; x++) {
                        level.setBlock(new BlockPos(x, TOP, z), (x % 16 < 2 || z % 16 < 2
                                ? Blocks.GLOWSTONE : Blocks.RED_CONCRETE).defaultBlockState(), 2);
                    } }
                }
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.getLevel(RocketService.FLIGHT), 0, 200, 0, 0, 0);
                player.getAbilities().flying = true; player.onUpdateAbilities();
            }, server);
            step++; return false;
        }
        if (step == 3) {
            long completeChunks = summaries.patches().stream().filter(patch -> patch.level() == 0 && patch.surface().dimensionId().equals(LANDMARK_CHART.dimensionId())
                    && patch.x() >= 0
                    && patch.x() < SIDE / 16 && patch.z() >= 0 && patch.z() < SIDE / 16
                    && patch.cells().stream().anyMatch(cell -> cell.emission() > .01)).count();
            if (completeChunks < 64) { return false; }
            evidence.append("receivedRealChunks=").append(completeChunks).append(" revision=").append(summaries.revision()).append('\n');
            setView(); step++; return false;
        }
        if (step == 4) {
            if (frames < 70 || !ready()) { return false; }
            screenshot((restart ? "restart-" : "create-") + view);
            if (++view < 3) { setView(); return false; }
            if (!restart) {
                var server = game.getSingleplayerServer();
                pending = CompletableFuture.runAsync(() -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(landmarkLevel(server), SIDE / 2.0, TOP + 3, SIDE / 2.0, 0, 0);
                    checkpointRevision = OrbitalSummaryIndex.get(server).pages().stream()
                            .filter(patch -> patch.x() == 0 && patch.z() == 0
                                    && patch.surface().dimensionId().equals(LANDMARK_CHART.dimensionId()))
                            .mapToLong(patch -> patch.revision()).max().orElseThrow();
                    server.saveEverything(false, true, true);
                }, server);
                step = 7; return false;
            }
            frames = 0; pending = game.reloadResourcePacks(); step = 8; return false;
        }
        if (step == 8) {
            view = 1; setView(); step = 9; return false;
        }
        if (step == 9) {
            if (frames < 70 || !ready()) { return false; }
            screenshot("reload-1"); evidence.append("resourceReloadRetainedActualLights=true\n");
            priorRevision = summaries.revision();
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var level = landmarkLevel(server);
                for (int z = 0; z < SIDE; z++) { for (int x = 0; x < SIDE; x++) {
                    level.setBlock(new BlockPos(x, TOP, z), Blocks.AIR.defaultBlockState(), 2);
                } }
            }, server);
            step = 5; return false;
        }
        if (step == 5) {
            if (summaries.revision() <= priorRevision) { return false; }
            var removed = summaries.patches().stream().filter(patch -> patch.level() == 0 && patch.surface().dimensionId().equals(LANDMARK_CHART.dimensionId())
                    && patch.x() >= 0
                    && patch.x() < SIDE / 16 && patch.z() >= 0 && patch.z() < SIDE / 16).toList();
            if (removed.size() < 64 || removed.stream().flatMap(patch -> patch.cells().stream())
                    .anyMatch(cell -> cell.altitudeMeters() > TOP + LANDMARK_CHART.altitudeOriginMeters() || cell.emission() > .001)) { return false; }
            view = 1; setView(); step++; return false;
        }
        if (step == 6) {
            if (frames < 70 || !ready()) { return false; }
            screenshot("removed-night"); evidence.append("removedEmission=true revision=").append(summaries.revision()).append('\n');
            step++; return false;
        }
        if (step == 7) {
            if (restart && (frames < 70 || !ready())) { return false; }
            var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
            Files.writeString(directory.resolve("orbital-edits-" + (restart ? "restart" : "create") + ".txt"), evidence,
                    StandardOpenOption.CREATE_NEW);
            if (!restart) {
                Files.writeString(game.gameDirectory.toPath().resolve("earth-orbital-edits-checkpoint.properties"),
                        "version=1\nsystem=sol\nbody=earth\ndimension=" + LANDMARK_CHART.dimensionId() + "\nx=0\nz=0\nside=" + SIDE + "\ntop=" + TOP
                                + "\nrevision=" + checkpointRevision + "\n", StandardOpenOption.CREATE_NEW);
            }
            NeoForge.EVENT_BUS.unregister(camera); NeoForge.EVENT_BUS.unregister(render); return true;
        }
        return false;
    }
    private CompletableFuture<?> chunkReady;

    private static net.minecraft.server.level.ServerLevel landmarkLevel(net.minecraft.server.MinecraftServer server) {
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(LANDMARK_CHART.dimensionId()));
        var level = server.getLevel(key);
        require(level != null, "Missing canonical upper landmark chart");
        return level;
    }

    private void setView() {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            var profile = SkyState.get(server).profile();
            SpaceVector normal = new EarthChart(dev.lexawhatt.astraengine.surface.CubeFace.POSITIVE_X, 0)
                    .normal(SIDE / 2.0, SIDE / 2.0);
            double best = view == 0 ? -2 : 2; long selected = 0;
            for (long time = 0; time < 24000; time += 500) {
                var sample = EarthEphemeris.sample(profile, time, 0);
                double light = normal.dot(sample.frame().toBodyDirection(sample.frame().centerMeters().multiply(-1)).normalized());
                if (view == 0 ? light > best : light < best) { best = light; selected = time; }
            }
            server.overworld().setDayTime(selected);
            calendar = EarthEphemeris.sample(profile, selected, 0);
        }, server).thenRunAsync(() -> {
            var chart = new EarthChart(dev.lexawhatt.astraengine.surface.CubeFace.POSITIVE_X, 0);
            SpaceVector normal = chart.normal(SIDE / 2.0, SIDE / 2.0);
            double altitude = view == 2 ? 100_000 : 10_000;
            var frame = calendar.frame(); observer = frame.toSystemPoint(normal.multiply(frame.radiusMeters() + altitude));
            var grid = SurfaceHeightTile.Grid.at(normal, frame.radiusMeters(), 513, 4);
            var tangent = new PlanetaryFrame(normal, grid.east(), normal, grid.south());
            orientation = frame.toSystemOrientation(tangent.toBodyOrientation(FlightOrientation.fromAngles(0, 90, 0)));
            frames = 0;
            evidence.append("view=").append(view).append(" altitudeMeters=").append(altitude)
                    .append(" FOV=30 orbitalSeconds=").append(calendar.orbitalSeconds()).append('\n');
        }, game);
    }

    private void render(RenderLevelStageEvent event) {
        if (renderer == null || observer == null || orientation == null || calendar == null) { return; }
        try {
            renderer.render(event, CosmosGenerator.sol(), observer, 0, calendar.orbitalSeconds(),
                    calendar.frame().bodyToSystem(), 0, 1);
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) { frames++; }
        } catch (RuntimeException exception) { failure = exception; }
    }

    private boolean ready() throws Exception {
        var cache = field(renderer, "continental");
        return (int) field(cache, "globe") != 0 && field(cache, "grid") != null;
    }
    private void screenshot(String name) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence/orbital-edits-" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path);
            double peak = 0; int redPixels = 0;
            for (int y = image.getHeight() / 2 - 32; y < image.getHeight() / 2 + 32; y++) {
                for (int x = image.getWidth() / 2 - 32; x < image.getWidth() / 2 + 32; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    double red = (pixel & 255) / 255.0, green = ((pixel >>> 8) & 255) / 255.0,
                            blue = ((pixel >>> 16) & 255) / 255.0;
                    peak = Math.max(peak, (red + green + blue) / 3);
                    if (red > green * 1.3 + .08 && red > blue * 1.3 + .08) { redPixels++; }
                }
            }
            evidence.append(name).append(" centerPeak=").append(peak).append(" redPixels=").append(redPixels).append('\n');
            evidence.append("fineChunks=").append(summaries.patches().stream().filter(patch -> patch.level() == 0).count())
                    .append(" coarsePages=").append(summaries.patches().stream().filter(patch -> patch.level() == 4).count()).append('\n');
            Files.writeString(path.resolveSibling(name + "-metrics.txt"), evidence.toString(), StandardOpenOption.CREATE_NEW);
            if (name.endsWith("-0")) { require(redPixels >= 4, "Canonical red landmark is not visible in the orbital material"); }
            if (name.endsWith("-1")) {
                require(peak > .2, "Actual street lights did not appear in the night orbital renderer"); litNightPeak = peak;
            }
            if (name.endsWith("-2")) { require(peak > .1, "Actual settlement is absent at 100 km altitude"); }
            if (name.equals("removed-night")) {
                require(peak < litNightPeak * .8, "Removed lights retained their night orbital brightness");
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Orbital edit frame left a GL error");
    }
    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static <T> T typedField(Object instance, Class<T> type) throws Exception {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) { field.setAccessible(true); return type.cast(field.get(instance)); }
        }
        throw new IllegalStateException("Missing fixture field type " + type.getName());
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
