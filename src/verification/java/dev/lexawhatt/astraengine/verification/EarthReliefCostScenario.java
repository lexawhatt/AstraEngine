package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SkyService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** One compiled diagnostic program, three frozen poses and reversed mode order; no production quality change. */
final class EarthReliefCostScenario {
    private static final ReliefCostShader.Mode[] ORDER = {
            ReliefCostShader.Mode.REGISTERED, ReliefCostShader.Mode.FULL,
            ReliefCostShader.Mode.NOMINAL_SURFACE, ReliefCostShader.Mode.BACKGROUND_ONLY,
            ReliefCostShader.Mode.BACKGROUND_ONLY, ReliefCostShader.Mode.NOMINAL_SURFACE,
            ReliefCostShader.Mode.FULL, ReliefCostShader.Mode.REGISTERED};
    private static final String[] POSES = {"disc-day", "100km-day-horizon", "100km-front"};
    private final Minecraft game = Minecraft.getInstance();
    private final dev.lexawhatt.astraengine.cosmos.CosmosSystem system = CosmosGenerator.sol();
    private final CloudStatus previousClouds = game.options.cloudStatus().get();
    private final Consumer<ViewportEvent.ComputeCameraAngles> camera = event -> {
        if (this.orientation != null) {
            event.setYaw(this.orientation.yaw()); event.setPitch(this.orientation.pitch()); event.setRoll(this.orientation.roll());
        }
    };
    private final Consumer<RenderLevelStageEvent> rendering = this::render;
    private final Consumer<RenderFrameEvent.Post> frames = event -> frame();
    private final ArrayDeque<Integer> queries = new ArrayDeque<>();
    private final List<Double> gpuMillis = new ArrayList<>(), frameMillis = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder("Verification-only relief cost attribution; no accelerated production shader\n");
    private final long started = System.nanoTime();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<FrozenClock> frozenClock;
    private CosmosRenderer renderer;
    private RenderOptions options;
    private ReliefCostShader diagnostic;
    private SpaceVector observer, expectedSun;
    private FlightOrientation orientation;
    private RuntimeException failure;
    private int stage, ticks, pose, mode;
    private long settledAt, measuringAt, previousFrame;
    private boolean measuring, restored, previousAutomatic;
    private float previousExposure = 1;
    private float[] heldWeather;
    private int[] registeredPixels;

    EarthReliefCostScenario() {
        game.options.hideGui = true; game.options.fov().set(70); game.options.bobView().set(false);
        game.options.renderDistance().set(6); game.options.simulationDistance().set(5); game.options.broadcastOptions();
        game.options.framerateLimit().set(60);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, rendering);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        try { return tickOwned(); }
        catch (Exception exception) { restore(); throw exception; }
    }

    private boolean tickOwned() throws Exception {
        require(System.nanoTime() - started < 480_000_000_000L, "Relief attribution timed out at " + stage + "/" + pose + "/" + mode);
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            renderer = (CosmosRenderer) field(map.controller(), "renderer");
            options = (RenderOptions) field(map.controller(), "options");
            map.onClose(); previousAutomatic = options.autoExposure(); previousExposure = options.exposure();
            options.setAutoExposure(false); options.setExposure(1); game.options.cloudStatus().set(CloudStatus.OFF);
            diagnostic = new ReliefCostShader();
            evidence.append(diagnostic.sourceHashes()).append(" dynamicSourceSubstitution=true\n");
            var server = game.getSingleplayerServer();
            pending = server.submit(() -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(server.getLevel(RocketService.FLIGHT), 0, 200, 0, 0, 0);
            });
            next(); return false;
        }
        if (stage == 2) {
            if (ticks < 40 || !game.level.dimension().equals(RocketService.FLIGHT)) { return false; }
            if (frozenClock == null) {
                var server = game.getSingleplayerServer();
                frozenClock = server.submit(() -> {
                    boolean previous = server.tickRateManager().isFrozen();
                    server.tickRateManager().setFrozen(true);
                    var weather = SkyService.weather(server);
                    SkyService.sendWeather(server.getPlayerList().getPlayers().getFirst());
                    return new FrozenClock(previous, weather.gameTime(), weather.dayTime());
                });
                pending = frozenClock; return false;
            }
            var sky = (SkyStateClient) field(renderer, "skyState");
            var clock = frozenClock.join();
            if (!game.level.tickRateManager().isFrozen() || sky.earthGameTime(game.level) != clock.gameTime()
                    || sky.earthDayTime() != clock.dayTime()) { return false; }
            evidence.append("frozenSource gameTime=").append(clock.gameTime()).append(" dayTime=").append(clock.dayTime()).append('\n');
            renderer.setContinentalEarth(ContinentalTerrain.CURRENT_VERSION);
            setPose(); next(); return false;
        }
        if (stage == 3) {
            if (ticks < 100 || System.nanoTime() - settledAt < 5_000_000_000L || !ready()) { return false; }
            clearQueries(); gpuMillis.clear(); frameMillis.clear(); previousFrame = 0;
            measuringAt = System.nanoTime(); measuring = true; next(); return false;
        }
        if (stage == 4) {
            if (System.nanoTime() - measuringAt < 4_000_000_000L) { return false; }
            measuring = false; capture();
            if (++mode == ORDER.length) {
                mode = 0;
                if (++pose == POSES.length) {
                    Files.writeString(output().resolve("earth-relief-cost-results.txt"), evidence, StandardOpenOption.CREATE_NEW);
                    restore(); return true;
                }
                setPose();
            } else { settledAt = System.nanoTime(); }
            stage = 3; ticks = 0; return false;
        }
        return false;
    }

    private void setPose() {
        var earth = system.bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
        var center = system.positionAt(earth, 0);
        var sun = center.multiply(-1).normalized();
        var bodyFrame = SurfaceDefinition.find("sol", "earth").orElseThrow().frame(system, 0, 0);
        expectedSun = bodyFrame.toBodyDirection(sun);
        var normal = sun;
        if (pose == 2) {
            double latitude = Math.toRadians(48), azimuth = Math.toRadians(-28);
            normal = bodyFrame.toSystemDirection(new SpaceVector(Math.cos(latitude) * Math.cos(azimuth),
                    Math.sin(latitude), Math.cos(latitude) * Math.sin(azimuth)));
        }
        double altitude = pose == 0 ? earth.radiusMeters() * 1.5 : 100_000;
        observer = center.add(normal.multiply(earth.radiusMeters() + altitude));
        SpaceVector forward = normal.multiply(-1);
        if (pose == 1) {
            double horizontal = earth.radiusMeters() / (earth.radiusMeters() + altitude);
            forward = perpendicular(normal).multiply(horizontal).subtract(normal.multiply(Math.sqrt(1 - horizontal * horizontal)));
        } else if (pose == 2) {
            var horizontal = sun.subtract(normal.multiply(normal.dot(sun))).normalized();
            forward = horizontal.multiply(.96).subtract(normal.multiply(.28));
        }
        orientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-forward.x(), forward.z())),
                Math.toDegrees(Math.asin(-forward.y())), 0);
        if (pose != 0) {
            var up = normal.subtract(forward.multiply(normal.dot(forward))).normalized();
            orientation = orientation.rotateLocal(0, 0,
                    Math.toDegrees(Math.atan2(-up.dot(orientation.left()), up.dot(orientation.up()))));
        }
        evidence.append("pose=").append(POSES[pose]).append(" observer=").append(observer)
                .append(" orientation=").append(orientation).append(" altitudeMeters=").append(altitude)
                .append(" terrainVersion=").append(ContinentalTerrain.CURRENT_VERSION).append('\n');
        settledAt = System.nanoTime();
    }

    private boolean ready() throws Exception {
        var optics = field(renderer, "atmosphereOptics");
        var continental = field(renderer, "continental");
        if ((int) field(optics, "texture") == 0 || field(optics, "pending") != null
                || (int) field(continental, "globe") == 0 || field(continental, "pending") != null) { return false; }
        if (pose != 0 && (field(continental, "grid") == null
                || Arrays.stream((int[]) field(continental, "tiles")).anyMatch(texture -> texture == 0))) { return false; }
        var sun = activeShader().getUniform("EarthCloudSun").getFloatBuffer();
        return new SpaceVector(sun.get(0), sun.get(1), sun.get(2)).distance(expectedSun) < .000002;
    }

    private void render(RenderLevelStageEvent event) {
        if (renderer == null || observer == null || orientation == null || diagnostic == null) { return; }
        var stage = RenderCompatibility.lateWorldPasses() ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (event.getStage() != stage || RenderCompatibility.shadowPass()) { return; }
        int query = 0;
        try {
            collectQueries();
            if (measuring && queries.size() < 64 && gpuMillis.size() + queries.size() < 2048
                    && GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) == 0) {
                query = GL15.glGenQueries(); GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
            }
            diagnostic.render(renderer, ORDER[mode], () -> renderer.render(event, system, observer, 0, 0, null, 0, 1));
        } catch (Exception exception) { failure = new IllegalStateException("Relief attribution render failed", exception); }
        finally { if (query != 0) { GL15.glEndQuery(GL33.GL_TIME_ELAPSED); queries.addLast(query); } }
    }

    private void collectQueries() {
        while (!queries.isEmpty() && GL15.glGetQueryObjecti(queries.getFirst(), GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            int query = queries.removeFirst();
            gpuMillis.add(GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT) / 1_000_000.0);
            GL15.glDeleteQueries(query);
        }
    }

    private void clearQueries() { while (!queries.isEmpty()) { GL15.glDeleteQueries(queries.removeFirst()); } }

    private void capture() throws Exception {
        collectQueries();
        String name = POSES[pose] + "-" + mode + "-" + ORDER[mode].name().toLowerCase(Locale.ROOT);
        summarize(name, "presentedFrames", frameMillis); summarize(name, "cosmosGpu", gpuMillis);
        evidence.append(name).append(" pendingDiscarded=").append(queries.size()).append(" viewport=")
                .append(game.getWindow().getWidth()).append('x').append(game.getWindow().getHeight())
                .append(" vsync=").append(game.options.enableVsync().get()).append(" frameCap=")
                .append(game.options.framerateLimit().get()).append(" quality=").append(options.quality())
                .append(" scope=actual-Cosmos-render-HDR-bloom-fixed-exposure-composition\n");
        clearQueries();
        var shader = activeShader();
        var cloud = shader.getUniform("EarthCloudParams").getFloatBuffer();
        var wind = shader.getUniform("CloudWind").getFloatBuffer();
        float[] weather = {cloud.get(1), cloud.get(2), cloud.get(3), wind.get(0), wind.get(1)};
        if (heldWeather == null) { heldWeather = weather; }
        require(Arrays.equals(heldWeather, weather), "Frozen source/weather uniforms changed across modes");
        require(cloud.get(0) == 0 && !options.autoExposure() && options.exposure() == 1,
                "Attribution requires actual Clouds OFF and fixed exposure1");
        if (ORDER[mode] != ReliefCostShader.Mode.REGISTERED) {
            require(shader.getUniform("VerificationReliefMode").getIntBuffer().get(0)
                    == (ORDER[mode] == ReliefCostShader.Mode.NOMINAL_SURFACE ? 1 : 0), "Wrong actual shader diagnostic mode");
        }
        int bodies = shader.getUniform("BodyCount").getIntBuffer().get(0);
        require(ORDER[mode] == ReliefCostShader.Mode.BACKGROUND_ONLY ? bodies == 0 : bodies > 0, "Wrong actual body count");
        require(shader.getUniform("LensIndex").getIntBuffer().get(0) < 0, "Background comparison must not alter lens selection");
        require(shader.getUniform("SurfaceHorizon").getFloatBuffer().get(3) == 0,
                "BodyCount0 must not retain a ground-only SurfaceHorizon overlay");
        evidence.append(name).append(" surfaceHorizon=0 sameCompiledDiagnostic=true\n");
        evidence.append(name).append(" bodyCount=").append(bodies).append(" frozenWeatherExact=true cloudsOff=true\n");
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("earth-relief-cost-" + name + ".png"));
            if (mode == 0) {
                registeredPixels = new int[image.getWidth() * image.getHeight()];
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) { registeredPixels[y * image.getWidth() + x] = image.getPixelRGBA(x, y); }
                }
            } else if (ORDER[mode] == ReliefCostShader.Mode.FULL || ORDER[mode] == ReliefCostShader.Mode.REGISTERED) {
                long total = 0; int maximum = 0, overOne = 0, count = registeredPixels.length * 3;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int actual = image.getPixelRGBA(x, y), original = registeredPixels[y * image.getWidth() + x];
                        for (int shift = 0; shift < 24; shift += 8) {
                            int error = Math.abs((actual >>> shift & 255) - (original >>> shift & 255));
                            total += error; maximum = Math.max(maximum, error); if (error > 1) { overOne++; }
                        }
                    }
                }
                double mean = (double) total / count, fractionOverOne = (double) overOne / count;
                evidence.append(name).append(" registeredImageError mean255=").append(mean)
                        .append(" max255=").append(maximum).append(" fractionAbove1=").append(fractionOverOne).append('\n');
                require(mean <= .05 && maximum <= 4 && fractionOverOne <= .01,
                        "Diagnostic FULL changed the registered image beyond the retained display tolerance: " + mean + "/" + maximum);
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Relief attribution left a GL error");
        Files.writeString(output().resolve("earth-relief-cost-progress.txt"), evidence);
    }

    private ShaderInstance activeShader() throws ReflectiveOperationException {
        return ORDER[mode] == ReliefCostShader.Mode.REGISTERED ? (ShaderInstance) field(renderer, "shader") : diagnostic;
    }

    private void summarize(String name, String metric, List<Double> data) {
        var values = data.stream().sorted().toList();
        require(values.size() >= 20, "Insufficient " + metric + " samples for " + name);
        evidence.append(String.format(Locale.ROOT, "%s %s n=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n",
                name, metric, values.size(), values.get(values.size() / 2), values.get((int) Math.ceil(values.size() * .95) - 1),
                values.get((int) Math.ceil(values.size() * .99) - 1), values.getLast()));
    }

    private void frame() {
        long now = System.nanoTime();
        if (measuring && previousFrame != 0 && game.screen == null && game.getOverlay() == null) {
            frameMillis.add((now - previousFrame) / 1e6);
        }
        previousFrame = now;
    }

    private void restore() {
        if (restored) { return; }
        restored = true; measuring = false; observer = null; orientation = null; clearQueries();
        if (options != null) { options.setAutoExposure(previousAutomatic); options.setExposure(previousExposure); }
        game.options.cloudStatus().set(previousClouds);
        if (diagnostic != null) { diagnostic.close(); diagnostic = null; }
        if (frozenClock != null && frozenClock.isDone() && !frozenClock.isCompletedExceptionally()) {
            var server = game.getSingleplayerServer();
            if (server != null) { server.execute(() -> server.tickRateManager().setFrozen(frozenClock.join().previous())); }
        }
        NeoForge.EVENT_BUS.unregister(camera); NeoForge.EVENT_BUS.unregister(rendering); NeoForge.EVENT_BUS.unregister(frames);
    }

    private Path output() throws java.io.IOException {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path); return path;
    }
    private void next() { stage++; ticks = 0; }
    private static SpaceVector perpendicular(SpaceVector unit) {
        var axis = Math.abs(unit.z()) < .9 ? new SpaceVector(0, 0, 1) : new SpaceVector(1, 0, 0);
        return axis.subtract(unit.multiply(axis.dot(unit))).normalized();
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
    private record FrozenClock(boolean previous, long gameTime, long dayTime) { }
}
