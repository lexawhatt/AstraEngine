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
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/** One compiled diagnostic program, three frozen poses and reversed mode order; no production quality change. */
final class EarthCoreScenario {
    private static final EarthCoreShader.Mode[] ORDER = {EarthCoreShader.Mode.ORIGINAL, EarthCoreShader.Mode.DISABLED,
            EarthCoreShader.Mode.ENABLED, EarthCoreShader.Mode.REGISTERED, EarthCoreShader.Mode.PREDICATE,
            EarthCoreShader.Mode.REGISTERED, EarthCoreShader.Mode.ENABLED, EarthCoreShader.Mode.DISABLED, EarthCoreShader.Mode.ORIGINAL};
    private static final String[] POSES = {"disc-day", "100km-day-horizon", "100km-front", "px-450km", "nx-499999m",
            "pz-500km", "nz-500001m", "north-100km", "south-450km", "below-threshold-99999m", "inside-earth",
            "low-quality", "high-quality", "unmapped", "no-continental", "no-bodies", "lens", "ground", "off-earth", "bad-radius"};
    private static final double[] ALTITUDES = {9556500, 100000, 100000, 450000, 499999, 500000, 500001, 100000, 450000,
            99999, -100, 100000, 100000, 100000, 100000, 100000, 100000, 100000, 100000, 100000};
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
    private final StringBuilder evidence = new StringBuilder("Verification-only relief cost attribution; actual opaque-core predicate and original linear/display image oracle\n");
    private final long started = System.nanoTime();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<FrozenClock> frozenClock;
    private CosmosRenderer renderer;
    private RenderOptions options;
    private EarthCoreShader diagnostic, original;
    private SpaceVector observer, expectedSun;
    private FlightOrientation orientation;
    private RuntimeException failure;
    private int stage, ticks, pose, mode;
    private long settledAt, measuringAt, previousFrame;
    private java.time.Instant windowStarted;
    private boolean measuring, restored, previousAutomatic;
    private float previousExposure = 1;
    private float[] heldWeather;
    private int[] registeredPixels, disabledPixels, enabledPixels;
    private float[] originalLinear, disabledLinear, enabledLinear;

    EarthCoreScenario() {
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
        require(System.nanoTime() - started < 900_000_000_000L, "Earth-core verification timed out at " + stage + "/" + pose + "/" + mode);
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
            diagnostic = new EarthCoreShader(false); original = new EarthCoreShader(true);
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
            if (ticks < (mode == 0 ? 50 : 8) || System.nanoTime() - settledAt < (mode == 0 ? 2_000_000_000L : 250_000_000L) || !ready()) { return false; }
            clearQueries(); gpuMillis.clear(); frameMillis.clear(); previousFrame = 0;
            measuringAt = System.nanoTime(); windowStarted = java.time.Instant.now(); measuring = true; next(); return false;
        }
        if (stage == 4) {
            if (System.nanoTime() - measuringAt < (pose < 3 && mode != 4 ? 4_000_000_000L : 250_000_000L)) { return false; }
            measuring = false; capture();
            if (++mode == 3 && state() != EarthCoreShader.State.NORMAL) { mode++; }
            if (mode == 5 && pose >= 3) { mode = ORDER.length; }
            if (mode == ORDER.length) {
                mode = 0;
                if (++pose == POSES.length) {
                    Files.writeString(output().resolve("earth-core-results.txt"), evidence, StandardOpenOption.CREATE_NEW);
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
        if (pose >= 3 && pose <= 8) {
            var axis = switch (pose) {
                case 3 -> new SpaceVector(1, 0, 0); case 4 -> new SpaceVector(-1, 0, 0);
                case 5 -> new SpaceVector(0, 0, 1); case 6 -> new SpaceVector(0, 0, -1);
                case 7 -> new SpaceVector(0, 1, 0); default -> new SpaceVector(0, -1, 0);
            };
            normal = bodyFrame.toSystemDirection(axis);
        }
        double altitude = ALTITUDES[pose];
        var quality = pose == 11 ? RenderOptions.Quality.LOW : pose == 12 ? RenderOptions.Quality.HIGH : RenderOptions.Quality.BALANCED;
        while (options.quality() != quality) { options.cycleQuality(); }
        observer = center.add(normal.multiply(earth.radiusMeters() + altitude));
        SpaceVector forward = normal.multiply(-1);
        if (pose != 0 && pose != 2 && pose != 10) {
            double horizontal = earth.radiusMeters() / (earth.radiusMeters() + altitude);
            forward = perpendicular(normal).multiply(horizontal).subtract(normal.multiply(Math.sqrt(1 - horizontal * horizontal)));
        } else if (pose == 2) {
            var horizontal = sun.subtract(normal.multiply(normal.dot(sun))).normalized();
            forward = horizontal.multiply(.96).subtract(normal.multiply(.28));
        }
        orientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-forward.x(), forward.z())),
                Math.toDegrees(Math.asin(-forward.y())), 0);
        if (pose != 0 && pose != 10) {
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
            var program = ORDER[mode] == EarthCoreShader.Mode.ORIGINAL ? original : diagnostic;
            program.render(renderer, ORDER[mode], state(), () -> renderer.render(event, system, observer, 0, 0, null, 0, 1));
        } catch (Exception exception) { failure = new IllegalStateException("Earth-core verification render failed", exception); }
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

    private EarthCoreShader.State state() {
        return switch (pose) {
            case 13 -> EarthCoreShader.State.UNMAPPED; case 14 -> EarthCoreShader.State.NO_CONTINENTAL;
            case 15 -> EarthCoreShader.State.NO_BODIES; case 16 -> EarthCoreShader.State.LENS;
            case 17 -> EarthCoreShader.State.GROUND; case 18 -> EarthCoreShader.State.OFF_EARTH;
            case 19 -> EarthCoreShader.State.INVALID_RADIUS; default -> EarthCoreShader.State.NORMAL;
        };
    }

    private void capture() throws Exception {
        var windowEnded = java.time.Instant.now();
        collectQueries();
        String name = POSES[pose] + "-" + mode + "-" + ORDER[mode].name().toLowerCase(Locale.ROOT);
        evidence.append(name).append(" windowStartUtc=").append(windowStarted).append(" windowEndUtc=").append(windowEnded).append('\n');
        if (pose < 3 && mode != 4) { summarize(name, "presentedFrames", frameMillis); summarize(name, "cosmosGpu", gpuMillis); }
        evidence.append(name).append(" pendingDiscarded=").append(queries.size()).append(" quality=").append(options.quality())
                .append(" state=").append(state()).append(" viewport=").append(game.getWindow().getWidth()).append('x')
                .append(game.getWindow().getHeight()).append(" scope=actual-Cosmos-HDR-bloom-composition\n");
        clearQueries();
        float[] linear = readLinear();
        int[] pixels;
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("earth-core-" + name + ".png"));
            pixels = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) { pixels[y * image.getWidth() + x] = image.getPixelRGBA(x, y); }
            }
        }
        if (mode == 0) { registeredPixels = pixels; originalLinear = linear; }
        if (mode == 1) { disabledPixels = pixels; disabledLinear = linear; }
        if (mode == 2) { enabledPixels = pixels; enabledLinear = linear; }
        if (mode > 0 && mode != 4 && state() == EarthCoreShader.State.NORMAL) {
            compare(name + "-original", originalLinear, linear, registeredPixels, pixels, null);
        }
        if (mode == 4) {
            int guarded = 0, covered = 0, partial = 0;
            for (int pixel = 0; pixel < linear.length / 4; pixel++) {
                int at = pixel * 4;
                require(Float.isFinite(linear[at]) && Float.isFinite(linear[at + 3]), "Nonfinite predicate output");
                if (linear[at] == 1) {
                    guarded++;
                    require(linear[at + 1] == 1 && linear[at + 2] == 1,
                            "FALSE OPAQUE GUARD " + name + " pixel=" + pixel + " hit=" + linear[at + 1] + " exactlyCovered=" + linear[at + 2] + " coverage=" + linear[at + 3]);
                }
                if (linear[at + 2] == 1) { covered++; }
                if (linear[at + 3] > 0 && linear[at + 3] < 1) { partial++; }
            }
            boolean disabled = pose == 9 || pose == 10 || pose >= 14;
            require(disabled ? guarded == 0 : guarded > 1000, "Unexpected guard eligibility " + name + ": " + guarded);
            evidence.append(name).append(" guardPixels=").append(guarded).append(" exactlyOpaquePixels=").append(covered)
                    .append(" partialPixels=").append(partial).append(" falseGuards=0\n");
            compare(name + "-same-program", disabledLinear, enabledLinear, disabledPixels, enabledPixels, linear);
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Earth-core oracle left a GL error");
        Files.writeString(output().resolve("earth-core-progress.txt"), evidence);
    }

    private float[] readLinear() throws Exception {
        Object scene = field(field(renderer, "bloom"), "scene");
        int width = (int) field(scene, "width"), height = (int) field(scene, "height");
        int oldFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER), oldPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] names = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_ROWS};
        int[] previous = Arrays.stream(names).map(GL11::glGetInteger).toArray();
        var data = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, (int) field(scene, "framebuffer"));
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0); GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int name : names) { GL11.glPixelStorei(name, name == GL11.GL_PACK_ALIGNMENT ? 1 : 0); }
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, data);
            float[] result = new float[data.remaining()]; data.get(result); return result;
        } finally {
            MemoryUtil.memFree(data); GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldFramebuffer); GL11.glReadBuffer(oldBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPack);
            for (int i = 0; i < names.length; i++) { GL11.glPixelStorei(names[i], previous[i]); }
        }
    }

    private void compare(String name, float[] reference, float[] actual, int[] referencePixels, int[] pixels, float[] mask) {
        double maxLinear = 0, maxRelative = 0; long displaySum = 0; int maxDisplay = 0, differingOutside = 0;
        require(reference.length == actual.length && pixels.length * 4 == actual.length, "Mismatched oracle resolution");
        for (int pixel = 0; pixel < pixels.length; pixel++) {
            for (int channel = 0; channel < 3; channel++) {
                int at = pixel * 4 + channel; float a = reference[at], b = actual[at];
                require(Float.isFinite(a) && Float.isFinite(b), "Nonfinite linear color " + name);
                double error = Math.abs(a - b), scale = Math.max(Math.abs(a), Math.abs(b));
                maxLinear = Math.max(maxLinear, error); maxRelative = Math.max(maxRelative, error / Math.max(.001, scale));
                // One RGBA16F quantization step plus tiny source-roundoff allowance. Display cap below is independent.
                require(error <= .001 * scale + .000002, "Linear color exceeded one-half-precision-step enclosure " + name + ": " + error);
                if (mask != null && mask[pixel * 4] == 0 && Float.floatToIntBits(a) != Float.floatToIntBits(b)) { differingOutside++; }
                int displayError = Math.abs((referencePixels[pixel] >>> (channel * 8) & 255) - (pixels[pixel] >>> (channel * 8) & 255));
                displaySum += displayError; maxDisplay = Math.max(maxDisplay, displayError);
            }
        }
        double mean = (double) displaySum / (pixels.length * 3);
        evidence.append(name).append(" maxLinear=").append(maxLinear).append(" maxRelative=").append(maxRelative)
                .append(" displayMean255=").append(mean).append(" displayMax255=").append(maxDisplay)
                .append(" linearOutsideGuardChanged=").append(differingOutside).append('\n');
        require(maxDisplay <= 1 && mean <= .005, "Display or PSF mismatch " + name + ": " + maxDisplay + "/" + mean);
        require(differingOutside == 0, "Star/limb pixels outside guard changed in same-program comparison: " + name);
    }

    private ShaderInstance activeShader() throws ReflectiveOperationException {
        return ORDER[mode] == EarthCoreShader.Mode.REGISTERED ? (ShaderInstance) field(renderer, "shader")
                : ORDER[mode] == EarthCoreShader.Mode.ORIGINAL ? original : diagnostic;
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
        if (original != null) { original.close(); original = null; }
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
