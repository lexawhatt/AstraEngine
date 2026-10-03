package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.ContinentalLandscape;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfaceHeightTile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Disposable same-pose layer ownership diagnosis; the only shader replacement marks landscape fragments magenta. */
final class EarthLayerDiagnosisScenario {
    private record View(String name, GeographicPosition address, float yaw, float pitch) { }
    private static final String PACK = "file/astra-layer-diagnosis";
    private final Minecraft game = Minecraft.getInstance();
    private final int terrainVersion = ContinentalTerrain.CURRENT_VERSION;
    private final ContinentalTerrain terrain = new ContinentalTerrain(terrainVersion, ContinentalTerrain.SEED);
    private final List<View> views = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder("Earth landscape layer ownership; no production fade or cache mutation\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private EarthChart chart;
    private Vec3 feet;
    private int index;
    private int stage;
    private long since = System.nanoTime();
    private long started = System.nanoTime();
    private boolean marked;
    private long lastDiagnostic;

    EarthLayerDiagnosisScenario() throws Exception {
        views.add(new View("coast-70km", new GeographicPosition(.3518588510742868, .495659856477681, 70_000), 0, 60));
        views.add(new View("coast-10km", new GeographicPosition(.3518588510742868, .495659856477681, 10_000), 0, 60));
        views.add(new View("mountain-70km", new GeographicPosition(.18192107303316596, .6372012293281402, 70_000), 36, 60));
        var forest = new EarthChart(CubeFace.POSITIVE_Y, 0, terrainVersion);
        views.add(new View("forest", forest.geographic(new dev.lexawhatt.astraengine.cosmos.SpaceVector(
                -6202050.7126105, 302.87902140375127, -2318912.223103872)), -90, 12));
        var coast = new GeographicPosition(.3518588510742868, .495659856477681, 45);
        var coastChart = EarthChart.owner(coast, terrainVersion).orElseThrow();
        var position = coastChart.resolve(coast).orElseThrow();
        double minimum = Double.POSITIVE_INFINITY;
        float yaw = 0;
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI * 2 / 32;
            double height = terrain.sample(coastChart.normal(position.x() + Math.cos(angle) * 20000,
                    position.z() + Math.sin(angle) * 20000)).heightMeters();
            if (height < minimum) { minimum = height; yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))); }
        }
        views.add(new View("coast-low", coast, yaw, 12));
        addRiverViews();
        game.options.renderDistance().set(12); game.options.simulationDistance().set(5);
        game.options.broadcastOptions(); game.options.hideGui = true;
        game.options.cloudStatus().set(CloudStatus.OFF); game.options.bobView().set(false); game.options.fov().set(70); game.options.framerateLimit().set(60);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        installPack();
        evidence.append("Java=").append(System.getProperty("java.version")).append(" heap=")
                .append(Runtime.getRuntime().maxMemory()).append(" GPU=").append(GL11.glGetString(GL11.GL_RENDERER))
                .append(" terrainVersion=").append(terrainVersion).append('\n');
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 900_000_000_000L, "Layer diagnosis timed out at " + index + "/" + stage);
        if (!pending.isDone()) { return false; }
        pending.join();
        if (game.level == null || game.player == null) { return false; }
        if (System.nanoTime() - lastDiagnostic > 10_000_000_000L) {
            lastDiagnostic = System.nanoTime();
            String state = "stage=" + stage + " view=" + index + " elapsed=" + elapsed()
                    + " dimension=" + game.level.dimension().location() + " player=" + game.player.position()
                    + " inspection=" + (controller != null && controller.active()) + " screen=" + game.screen;
            if (controller != null && stage >= 3) { state += " " + readiness(); }
            AstraEngine.LOGGER.info("ASTRA_LAYER_WAIT {}", state);
            Files.writeString(output().resolve("waiting.txt"), state);
            if (elapsed() > 15) { image(game.getMainRenderTarget(), "waiting-" + index + "-" + stage); }
        }
        require(elapsed() < 150, "Layer stage did not become ready: " + stage + "/" + index + " " + readiness());
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(1); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            controller = map.controller();
            var options = (dev.lexawhatt.astraengine.client.render.RenderOptions) field(controller, "options");
            options.setAutoExposure(false);
            evidence.append("autoExposure=").append(options.autoExposure()).append('\n');
            evidence.append(EarthMaterialGpuVerification.verify());
            map.onClose(); next(2); return false;
        }
        if (stage == 2) {
            if (controller.active()) { game.player.connection.sendCommand("astra-flight"); next(8); return false; }
            teleport(); return false;
        }
        if (stage == 8) {
            if (controller.active()) { return false; }
            teleport(); return false;
        }
        if (stage == 3) {
            if (!game.level.dimension().location().toString().equals(chart.dimensionId())
                    || game.player.position().distanceTo(feet) > 2 || elapsed() < 6) { return false; }
            game.player.connection.sendCommand("astra-flight"); next(4); return false;
        }
        if (stage == 4 || stage == 5) {
            double settle = views.get(index).address().altitudeMeters() < 2_000 ? 20 : 5;
            if (!controller.active() || elapsed() < settle || !landscapeReady() || !orbitalReady()
                    || !nativeRiverReady()) { return false; }
            capture(marked ? "magenta" : "normal");
            if (views.get(index).name().startsWith("river-") && !marked) { next(6); return false; }
            if (!marked) { setPack(true); next(5); return false; }
            setPack(false); next(6); return false;
        }
        if (stage == 6) {
            if (elapsed() < 2) { return false; }
            if (++index < views.size()) { next(2); return false; }
            if (controller.active()) { game.player.connection.sendCommand("astra-flight"); }
            Files.writeString(output().resolve("layers.txt"), evidence.toString()); return true;
        }
        return false;
    }

    private void addRiverViews() {
        var atlas = terrain.rivers().orElseThrow();
        var center = atlas.point(273441, .80);
        var geographic = GeographicPosition.fromBody(center.multiply(EarthChart.RADIUS_METERS), EarthChart.RADIUS_METERS);
        double water = terrain.sample(center).waterMeters();
        var low = new GeographicPosition(geographic.latitudeRadians(), geographic.longitudeRadians(), water + 90);
        var local = EarthChart.owner(low, terrainVersion).orElseThrow();
        var origin = local.resolve(low).orElseThrow();
        var downstream = GeographicPosition.fromBody(atlas.point(273441, .81)
                .multiply(EarthChart.RADIUS_METERS + water + 90), EarthChart.RADIUS_METERS);
        var target = local.resolve(downstream).orElseThrow();
        float yaw = (float) Math.toDegrees(Math.atan2(-(target.x() - origin.x()), target.z() - origin.z()));
        views.add(new View("river-junction-high", new GeographicPosition(geographic.latitudeRadians(),
                geographic.longitudeRadians(), water + 3500), yaw, 60));
        views.add(new View("river-junction-low", low, yaw, 35));
        evidence.append("River views: branch273441 fraction=.80, shared water=").append(water)
                .append(" m, heading=").append(yaw).append('\n');
    }

    private boolean nativeRiverReady() {
        if (!views.get(index).name().equals("river-junction-low")) { return true; }
        return game.level.getChunkSource().getChunk(Math.floorDiv((int) Math.floor(game.player.getX()), 16),
                Math.floorDiv((int) Math.floor(game.player.getZ()), 16), ChunkStatus.FULL, false) != null
                && game.levelRenderer.countRenderedSections() > 0;
    }

    private void teleport() {
        var view = views.get(index);
        pending = game.getSingleplayerServer().submit(() -> {
            var server = game.getSingleplayerServer();
            require(dev.lexawhatt.astraengine.server.EarthWorlds.terrainVersion(server) == terrainVersion,
                    "Fresh layer diagnostic world does not use the current preset geography");
            var player = server.getPlayerList().getPlayers().getFirst();
            chart = EarthChart.owner(view.address(), terrainVersion).orElseThrow();
            var level = PlanetSurfaceWorlds.ensure(server, chart);
            var host = chart.resolve(view.address()).orElseThrow();
            feet = new Vec3(host.x(), host.y(), host.z());
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            long noon = Math.floorMod(6000 - Math.round(view.address().longitudeRadians() / (2 * Math.PI) * 24000), 24000);
            server.overworld().setDayTime(noon); level.setDayTime(noon);
            level.setWeatherParameters(100000, 0, false, false);
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(level, feet.x, feet.y, feet.z, view.yaw(), view.pitch());
            player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
        });
        next(3);
    }

    private boolean landscapeReady() throws Exception {
        var owner = landscape();
        var mesh = (ContinentalLandscape) field(owner, "mesh");
        if (mesh == null || field(owner, "target") == null || field(owner, "frameDepth") == null) { return false; }
        var camera = game.gameRenderer.getMainCamera().getPosition();
        return mesh.chart().face() == chart.face() && Math.hypot(mesh.centerX() - camera.x, mesh.centerZ() - camera.z) < 256;
    }

    private Object orbitalCache() throws Exception { return field(field(controller, "renderer"), "continental"); }

    private boolean orbitalReady() throws Exception {
        if (views.get(index).address().altitudeMeters() < 16_000) { return true; }
        Object cache = orbitalCache();
        require(!(boolean) field(cache, "failed"), "Orbital continental bake failed");
        var grid = (SurfaceHeightTile.Grid) field(cache, "grid");
        return (int) field(cache, "globe") != 0 && grid != null
                && grid.contains(views.get(index).address().normal(), .45) && field(cache, "pending") == null;
    }

    private String readiness() throws Exception {
        if (controller == null || chart == null) { return "awaiting controller/chart"; }
        Object owner = landscape();
        var mesh = (ContinentalLandscape) field(owner, "mesh");
        Object cache = orbitalCache();
        var grid = (SurfaceHeightTile.Grid) field(cache, "grid");
        return "landscape=" + (mesh == null ? "missing" : mesh.chart().dimensionId() + ":" + mesh.centerX() + "," + mesh.centerZ())
                + " depth=" + (field(owner, "frameDepth") != null) + " failed=" + field(owner, "failed")
                + " globe=" + field(cache, "globe") + " tiles=" + (field(cache, "grid") != null)
                + " tileDot=" + (grid == null ? "missing" : grid.up().dot(views.get(index).address().normal()))
                + " orbitalPending=" + (field(cache, "pending") != null)
                + " underground=" + dev.lexawhatt.astraengine.client.sky.SkyVisibility.underground(game.level, game.gameRenderer.getMainCamera());
    }

    private Object landscape() throws Exception { return field(field(game.level.effects(), "renderer"), "landscape"); }

    private void capture(String label) throws Exception {
        var prefix = views.get(index).name() + "-" + label;
        var raw = (RenderTarget) field(landscape(), "target");
        long rawMarked = image(raw, prefix + "-landscape-raw");
        long finalMarked = image(game.getMainRenderTarget(), prefix + "-final");
        var mesh = (ContinentalLandscape) field(landscape(), "mesh");
        var edits = field(field(controller, "renderer"), "orbitalEdits");
        var source = (dev.lexawhatt.astraengine.client.surface.orbit.OrbitalSummaryClient) field(edits, "source");
        evidence.append("ObservedAtlas pages=").append(field(edits, "count")).append(" texture=")
                .append(field(edits, "texture")).append(" sourcePatches=").append(source == null ? 0 : source.patches().size()).append('\n');
        evidence.append(prefix).append(" dimension=").append(game.level.dimension().location())
                .append(" geographic=").append(chart.geographic(new dev.lexawhatt.astraengine.cosmos.SpaceVector(
                        game.player.getX(), game.player.getY(), game.player.getZ())))
                .append(" camera=").append(game.gameRenderer.getMainCamera().getPosition())
                .append(" player=").append(game.player.position()).append(" inspection=").append(controller.active())
                .append(" yaw/pitch/roll=").append(controller.yaw()).append('/').append(controller.pitch()).append('/').append(controller.roll())
                .append(" meshCenter=").append(mesh.centerX()).append(',').append(mesh.centerZ())
                .append(" rawMagentaPixels=").append(rawMarked).append(" finalMagentaPixels=").append(finalMarked)
                .append(" hostSections=").append(game.levelRenderer.countRenderedSections()).append(" ").append(readiness()).append('\n')
                .append("Distant Horizons present=").append(net.neoforged.fml.ModList.get().isLoaded("distanthorizons")).append('\n');
        if (views.get(index).name().equals("river-junction-low")) {
            int x = (int) Math.floor(game.player.getX()), z = (int) Math.floor(game.player.getZ());
            var sample = terrain.sample(chart.normal(x + .5, z + .5));
            int bed = (int) Math.floor(sample.heightMeters()) - chart.altitudeOriginMeters();
            int water = (int) Math.floor(sample.waterMeters()) - chart.altitudeOriginMeters();
            require(sample.river() && water > bed, "River camera no longer observes an actual raised channel");
            require(!game.level.getBlockState(new BlockPos(x, bed - 1, z)).isAir(), "Actual river bed is absent");
            require(game.level.getBlockState(new BlockPos(x, water - 1, z)).is(Blocks.WATER),
                    "Actual native water does not match the shared river level");
            require(game.level.getFluidState(new BlockPos(x, water, z)).isEmpty(),
                    "Actual native water exceeds the shared river level");
            evidence.append("Actual river column x/z=").append(x).append('/').append(z)
                    .append(" bed/water=").append(bed).append('/').append(water)
                    .append(" sample=").append(sample).append(" surface=")
                    .append(game.level.getBlockState(new BlockPos(x, bed - 1, z))).append('\n');
        }
        if (marked) { require(rawMarked > 100, "Landscape-only shader replacement did not mark the actual private target"); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Layer capture produced an OpenGL error");
        Files.writeString(output().resolve("layers.txt"), evidence.toString());
        AstraEngine.LOGGER.info("ASTRA_LAYER_DIAGNOSIS {} rawMagenta={} finalMagenta={}", prefix, rawMarked, finalMarked);
    }

    private long image(RenderTarget target, String name) throws Exception {
        long magenta = 0;
        try (var frame = Screenshot.takeScreenshot(target)) {
            frame.writeToFile(output().resolve(name + ".png"));
            for (int y = 0; y < frame.getHeight(); y++) {
                for (int x = 0; x < frame.getWidth(); x++) {
                    int value = frame.getPixelRGBA(x, y);
                    if ((value & 255) > 220 && ((value >>> 8) & 255) < 35 && ((value >>> 16) & 255) > 220) { magenta++; }
                }
            }
        }
        return magenta;
    }

    private void installPack() throws Exception {
        var root = game.gameDirectory.toPath().resolve("resourcepacks/astra-layer-diagnosis");
        var shader = root.resolve("assets/astraengine/shaders/core/earth_landscape.fsh");
        Files.createDirectories(shader.getParent());
        int version = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + version
                + ",\"description\":\"Landscape-only magenta ownership diagnostic\"}}");
        try (var stream = game.getResourceManager().getResource(ResourceLocation.parse(
                "astraengine:shaders/core/earth_landscape.fsh")).orElseThrow().open()) {
            String original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int end = original.lastIndexOf('}');
            require(end >= 0, "Landscape fragment has no final function boundary");
            Files.writeString(shader, original.substring(0, end) + "    fragColor.rgb = vec3(1.0, 0.0, 1.0);\n" + original.substring(end));
        }
        game.getResourcePackRepository().reload();
    }

    private void setPack(boolean enabled) {
        var selected = new ArrayList<>(game.getResourcePackRepository().getSelectedIds());
        selected.remove(PACK); if (enabled) { selected.add(PACK); }
        game.getResourcePackRepository().setSelected(selected); marked = enabled;
        pending = game.reloadResourcePacks();
    }

    private Path output() throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence/layer-diagnosis"); Files.createDirectories(path); return path;
    }
    private double elapsed() { return (System.nanoTime() - since) / 1e9; }
    private void next(int value) { stage = value; since = System.nanoTime(); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
