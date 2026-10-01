package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Disposable lowland/coast/mountain/pole/edge far-view, asynchronous GPU timing and reload/resize verification. */
final class EarthLandscapeScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final List<GeographicPosition> observers = new ArrayList<>();
    private final List<Float> headings = new ArrayList<>();
    private final List<Double> samples = new ArrayList<>();
    private final List<Integer> queries = new ArrayList<>();
    private final Consumer<RenderLevelStageEvent> before = this::before;
    private final Consumer<RenderLevelStageEvent> after = this::after;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int index;
    private int step;
    private int frames;
    private int activeQuery;
    private boolean captureTimings;
    private DistantTerrainProbe dh;
    private final boolean expectPack = System.getProperty("astraengine.verify.phase", "").equals("earth-landscape-pack");
    private Throwable failure;

    EarthLandscapeScenario() {
        var field = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        observers.add(new GeographicPosition(0, 0, 610));
        Random random = new Random(41);
        double highest = 0, coastError = Double.POSITIVE_INFINITY;
        SpaceVector mountain = null, coast = null;
        for (int i = 0; i < 20_000; i++) {
            SpaceVector normal = new SpaceVector(random.nextDouble() * 2 - 1,
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1).normalized();
            var sample = field.sample(normal);
            if (sample.heightMeters() > highest) { highest = sample.heightMeters(); mountain = normal; }
            if (sample.heightMeters() >= 0 && Math.abs(sample.heightMeters() - 2) < coastError
                    && sample.temperature() > 5) { coastError = Math.abs(sample.heightMeters() - 2); coast = normal; }
        }
        require(mountain != null && highest > 6000 && coast != null && coastError < 1, "Missing native landscapes");
        var high = GeographicPosition.fromBody(mountain.multiply(EarthChart.RADIUS_METERS + highest + 50), EarthChart.RADIUS_METERS);
        observers.add(high);
        observers.add(new GeographicPosition(high.latitudeRadians(), high.longitudeRadians(), 10000));
        observers.add(GeographicPosition.fromBody(coast.multiply(EarthChart.RADIUS_METERS + 40), EarthChart.RADIUS_METERS));
        observers.add(new GeographicPosition(Math.PI / 2, 0, 4000));
        observers.add(new GeographicPosition(0, Math.PI / 4 - 1e-4, 9000));
        var mountainChart = EarthChart.owner(high, 2).orElseThrow();
        var mountainPoint = mountainChart.resolve(high).orElseThrow();
        double lowest = highest;
        GeographicPosition foothill = high;
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            var normal = mountainChart.normal(mountainPoint.x() + Math.cos(angle) * 12000,
                    mountainPoint.z() + Math.sin(angle) * 12000);
            double elevation = field.sample(normal).heightMeters();
            if (elevation < lowest) {
                lowest = elevation;
                foothill = GeographicPosition.fromBody(normal.multiply(EarthChart.RADIUS_METERS + elevation + 60), EarthChart.RADIUS_METERS);
            }
        }
        observers.add(foothill);
        // Repeat the same lowland view after reload and resize with volumetric clouds enabled.
        observers.add(observers.getFirst());
        for (var observer : observers) { headings.add(36.0f); }
        var coastChart = EarthChart.owner(observers.get(3), 2).orElseThrow();
        var coastPoint = coastChart.resolve(observers.get(3)).orElseThrow();
        double deepest = 0, oceanAngle = 0;
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            double height = field.sample(coastChart.normal(coastPoint.x() + Math.cos(angle) * 20000,
                    coastPoint.z() + Math.sin(angle) * 20000)).heightMeters();
            if (height < deepest) { deepest = height; oceanAngle = angle; }
        }
        require(deepest < 0, "Coast fixture must face an actual ocean");
        double inland = 0, offshore = 20000;
        for (int i = 0; i < 32; i++) {
            double distance = (inland + offshore) * .5;
            var normal = coastChart.normal(coastPoint.x() + Math.cos(oceanAngle) * distance,
                    coastPoint.z() + Math.sin(oceanAngle) * distance);
            if (field.sample(normal).heightMeters() > 0) { inland = distance; } else { offshore = distance; }
        }
        var shoreline = coastChart.normal(coastPoint.x() + Math.cos(oceanAngle) * (inland - 8),
                coastPoint.z() + Math.sin(oceanAngle) * (inland - 8));
        observers.set(3, GeographicPosition.fromBody(shoreline.multiply(EarthChart.RADIUS_METERS + 28), EarthChart.RADIUS_METERS));
        headings.set(3, (float) Math.toDegrees(Math.atan2(-Math.cos(oceanAngle), Math.sin(oceanAngle))));
        var foothillOwner = EarthChart.owner(foothill, 2).orElseThrow();
        var foothillPoint = foothillOwner.resolve(foothill).orElseThrow();
        require(foothillOwner.face() == mountainChart.face(), "Foothill fixture crossed a face");
        headings.set(6, (float) Math.toDegrees(Math.atan2(foothillPoint.x() - mountainPoint.x(), mountainPoint.z() - foothillPoint.z())));
        game.options.bobView().set(false);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, after);
    }

    boolean tick() throws Exception {
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
            if (dh == null) { dh = new DistantTerrainProbe(); }
            if (!dh.configure()) { return false; }
        }
        if (failure != null) { throw new IllegalStateException("Landscape render fixture failed", failure); }
        if (!pending.isDone()) { return false; }
        pending.join();
        if (step == 0) {
            captureTimings = false; frames = 0; samples.clear();
            game.options.cloudStatus().set(index == observers.size() - 1 ? CloudStatus.FANCY : CloudStatus.OFF);
            var observer = observers.get(index);
            server(server -> {
                var chart = EarthChart.owner(observer, 2).orElseThrow();
                var point = chart.resolve(observer).orElseThrow();
                var player = server.getPlayerList().getPlayers().getFirst();
                var rules = server.overworld().getGameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                server.overworld().setDayTime(6000 - Math.round(observer.longitudeRadians() / (2 * Math.PI) * 24000));
                server.overworld().setWeatherParameters(100000, 0, false, false);
                player.teleportTo(server.getLevel(EarthWorlds.dimension(chart)), point.x(), point.y(), point.z(), headings.get(index), index == 6 ? -8 : 8);
                player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            step = 1; return false;
        }
        var effects = (AstralOverworldEffects) game.level.effects();
        Object renderer = field(effects, "renderer"), landscape = field(renderer, "landscape");
        var options = (RenderOptions) field(renderer, "options");
        if (options.quality() != RenderOptions.Quality.HIGH) { options.cycleQuality(); }
        if (step == 1) {
            var chart = EarthChart.owner(observers.get(index), 2).orElseThrow();
            if (!game.level.dimension().equals(EarthWorlds.dimension(chart))) { return false; }
            if (expectPack) {
                require(dev.lexawhatt.astraengine.client.compat.RenderCompatibility.shaderPackActive(), "Expected active Iris pack");
                require(field(landscape, "frameDepth") == null && field(landscape, "vertices") == null,
                        "Native distant terrain competed with the active pack");
                require(!effects.ownsClouds(game.level), "Pack lost atmosphere ownership");
            } else if (field(landscape, "frameDepth") == null) { return false; }
            frames = 0; step = 2; return false;
        }
        if (step == 2) {
            if (frames < 180) { return false; }
            if (dh != null && index == 0 && dh.bufferRenders() < 30) { return false; }
            var chart = EarthChart.owner(observers.get(index), 2).orElseThrow();
            captureTimings = true;
            if (samples.size() < 31 || !queries.isEmpty()) { return false; }
            captureTimings = false;
            var output = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(output);
            String name = "earth-landscape-" + index;
            try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(output.resolve(name + ".png")); }
            var sorted = samples.stream().sorted().toList();
            Files.writeString(output.resolve(name + ".txt"), "observer=" + observers.get(index) + "\nchart=" + chart
                    + "\nviewport=" + game.getWindow().getWidth() + "x" + game.getWindow().getHeight()
                    + "\nAFTER_SKY_gpu_ms=" + samples + "\nmedian=" + sorted.get(sorted.size() / 2)
                    + "\np95=" + sorted.get((int) Math.ceil(sorted.size() * .95) - 1)
                    + "\nrenderer=" + GL11.glGetString(GL11.GL_RENDERER) + "\nDH=" + (dh == null ? "absent" : dh.description()) + "\n", StandardOpenOption.CREATE_NEW);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Landscape leaked an OpenGL error");
            index++;
            if (index == observers.size()) {
                if (dh != null) { dh.close(); }
                NeoForge.EVENT_BUS.unregister(before); NeoForge.EVENT_BUS.unregister(after); return true;
            }
            if (index == observers.size() - 1) {
                GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 960, 540);
                pending = game.reloadResourcePacks();
            }
            step = 0;
        }
        return false;
    }

    private void before(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) { return; }
        try {
            while (!queries.isEmpty() && GL15.glGetQueryObjecti(queries.getFirst(), GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                int query = queries.removeFirst();
                samples.add(GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT) / 1_000_000.0);
                GL15.glDeleteQueries(query);
            }
            if (captureTimings && samples.size() + queries.size() < 31) {
                activeQuery = GL15.glGenQueries(); GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, activeQuery);
            }
        } catch (RuntimeException error) { failure = error; }
    }

    private void after(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) { return; }
        if (activeQuery != 0) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED); queries.add(activeQuery); activeQuery = 0;
        }
        if (game.screen == null && game.getOverlay() == null) { frames++; }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field member = owner.getClass().getDeclaredField(name); member.setAccessible(true); return member.get(owner);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static void require(boolean test, String message) { if (!test) { throw new IllegalStateException(message); } }
}
