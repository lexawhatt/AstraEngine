package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SkyService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.glfw.GLFW;

/** Actual Earth transport under one source: daylight, both twilight directions and shadow at two altitudes. */
final class EarthAtmosphereVisualScenario {
    private static final int[] PREVIEW_VIEWS = {4, 4, 0, 0, 1, 4, 0, 0};
    private static final int[] PREVIEW_CLOUDS = {0, 1, 0, 1, 1, 1, 0, 1};
    private final Minecraft game = Minecraft.getInstance();
    private final dev.lexawhatt.astraengine.cosmos.CosmosSystem system = CosmosGenerator.sol();
    private final Consumer<ViewportEvent.ComputeCameraAngles> camera = event -> {
        if (this.orientation != null) {
            event.setYaw(this.orientation.yaw()); event.setPitch(this.orientation.pitch()); event.setRoll(this.orientation.roll());
        }
    };
    private final Consumer<RenderLevelStageEvent> rendering = this::render;
    private final Consumer<RenderFrameEvent.Post> frames = event -> frame();
    private final List<Double> frameMillis = new ArrayList<>();
    private final List<Double> gpuMillis = new ArrayList<>();
    private final ArrayDeque<Integer> gpuQueries = new ArrayDeque<>();
    private final StringBuilder evidence = new StringBuilder("Earth spherical optical transport / actual GPU fixed and automatic exposure\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CosmosRenderer renderer;
    private RenderOptions options;
    private SpaceVector observer;
    private SpaceVector expectedCloudSun;
    private FlightOrientation orientation;
    private RuntimeException failure;
    private final double[][][] meanLuminance = new double[2][2][8];
    private final CloudStatus previousClouds = game.options.cloudStatus().get();
    private int stage, ticks, view, automatic, clouds;
    private long settledAt;
    private long measuringAt, previousFrame;
    private boolean measuring, afterReload, fieldVerified;
    private Object previousShader;
    private int regressionBody;
    private final boolean morphologyPreview;
    private int previewPose;
    private CompletableFuture<FrozenClock> frozenClock;
    private float[] heldWeatherUniforms;
    private final long started = System.nanoTime();

    EarthAtmosphereVisualScenario() { this(false); }

    EarthAtmosphereVisualScenario(boolean morphologyPreview) {
        this.morphologyPreview = morphologyPreview;
        if (morphologyPreview) {
            view = PREVIEW_VIEWS[0];
            evidence.append("PREVIEW ONLY: eight fixed-camera morphology comparisons; no reload or GPU lifecycle qualification\n");
        }
        game.options.hideGui = true; game.options.fov().set(70); game.options.bobView().set(false);
        game.options.renderDistance().set(6); game.options.simulationDistance().set(5); game.options.broadcastOptions();
        game.options.framerateLimit().set(60);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, rendering);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 660_000_000_000L, "Atmosphere presentation timed out at " + stage + "/" + view);
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            var controller = map.controller();
            renderer = (CosmosRenderer) field(controller, "renderer"); options = (RenderOptions) field(controller, "options");
            map.onClose(); options.setAutoExposure(false); options.setExposure(1);
            var cover = options.getClass().getDeclaredField("cloudCover");
            cover.setAccessible(true); cover.setFloat(options, .55f);
            if (!morphologyPreview) {
                evidence.append(ExposureGpuVerification.verify(renderer)).append('\n');
                evidence.append(EarthOpticsGpuVerification.verify()).append('\n');
            }
            evidence.append(EarthMaterialGpuVerification.verify()).append('\n');
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
            if (morphologyPreview && frozenClock == null) {
                var server = game.getSingleplayerServer();
                frozenClock = server.submit(() -> {
                    boolean previousFrozen = server.tickRateManager().isFrozen();
                    server.tickRateManager().setFrozen(true);
                    var weather = SkyService.weather(server);
                    SkyService.sendWeather(server.getPlayerList().getPlayers().getFirst());
                    return new FrozenClock(previousFrozen, weather.gameTime(), weather.dayTime());
                });
                pending = frozenClock;
                return false;
            }
            if (morphologyPreview) {
                var sky = (dev.lexawhatt.astraengine.client.sky.SkyStateClient) field(renderer, "skyState");
                var clock = frozenClock.join();
                if (!game.level.tickRateManager().isFrozen() || sky.earthGameTime(game.level) != clock.gameTime()
                        || sky.earthDayTime() != clock.dayTime()) { return false; }
                evidence.append("frozenSource gameTime=").append(clock.gameTime())
                        .append(" dayTime=").append(clock.dayTime()).append('\n');
            }
            renderer.setContinentalEarth(ContinentalTerrain.CURRENT_VERSION);
            var earth = system.bodies().stream().filter(body -> body.id().equals("earth")).findFirst().orElseThrow();
            var center = system.positionAt(earth, 0);
            var sun = center.multiply(-1).normalized();
            var surface = dev.lexawhatt.astraengine.surface.SurfaceDefinition.find("sol", "earth").orElseThrow();
            expectedCloudSun = surface.frame(system, 0, 0).toBodyDirection(sun);
            var tangent = perpendicular(sun);
            int phase = view % 4;
            var normal = phase == 0 ? sun : phase == 3 ? sun.multiply(-1) : tangent;
            if (view >= 4 && phase == 2) { normal = normal.multiply(-1); }
            if (morphologyPreview && previewPose >= 5) {
                double latitude = Math.toRadians(48), longitude = Math.toRadians(-28);
                var bodyNormal = new SpaceVector(Math.cos(latitude) * Math.cos(longitude), Math.sin(latitude),
                        Math.cos(latitude) * Math.sin(longitude));
                var definition = dev.lexawhatt.astraengine.surface.SurfaceDefinition.find("sol", "earth").orElseThrow();
                normal = definition.frame(system, 0, 0).toSystemDirection(bodyNormal);
                require(normal.dot(sun) > .15, "Selected weather front must be in daylight");
                evidence.append("weatherFront bodyLatitude=48 bodyAzimuth=-28 geographicLongitude=28\n");
            }
            double altitude = view < 4 ? 100_000 : earth.radiusMeters() * 1.5;
            observer = center.add(normal.multiply(earth.radiusMeters() + altitude));
            SpaceVector forward;
            if (view < 4) {
                var horizontal = phase == 1 ? sun : phase == 2 ? sun.multiply(-1) : perpendicular(normal);
                double horizontalFactor = earth.radiusMeters() / (earth.radiusMeters() + altitude);
                forward = horizontal.multiply(horizontalFactor).subtract(normal.multiply(Math.sqrt(1 - horizontalFactor * horizontalFactor)));
                if (morphologyPreview && previewPose >= 6) {
                    // Remain over the front head: a slightly downward oblique view intersects its nearby
                    // structure instead of looking through the far horizon into an unrelated clear region.
                    horizontal = sun.subtract(normal.multiply(normal.dot(sun))).normalized();
                    forward = horizontal.multiply(.96).subtract(normal.multiply(.28));
                }
            } else {
                forward = normal.multiply(-1);
            }
            orientation = aim(forward);
            if (view < 4) {
                var up = normal.subtract(forward.multiply(normal.dot(forward))).normalized();
                orientation = orientation.rotateLocal(0, 0,
                        Math.toDegrees(Math.atan2(-up.dot(orientation.left()), up.dot(orientation.up()))));
            }
            options.setAutoExposure(automatic != 0);
            game.options.cloudStatus().set(clouds == 0 ? CloudStatus.OFF : CloudStatus.FANCY);
            evidence.append("view=").append(view).append(" mode=").append(automatic == 0 ? "fixed1" : "auto")
                    .append(" clouds=").append(clouds == 0 ? "off" : "on-cover0.55")
                    .append(" terrainVersion=").append(ContinentalTerrain.CURRENT_VERSION)
                    .append(" altitudeMeters=").append(altitude).append(" normalSunCosine=").append(normal.dot(sun)).append('\n');
            settledAt = System.nanoTime(); next(); return false;
        }
        if (stage == 3) {
            if (ticks < 100 || System.nanoTime() - settledAt < 5_000_000_000L || !ready()) { return false; }
            if (!fieldVerified) {
                evidence.append(EarthCloudFieldGpuVerification.verify(renderer)).append('\n');
                fieldVerified = true;
                // Exclude shader construction/readback from the subsequent frame-time window.
                settledAt = System.nanoTime(); ticks = 0;
                return false;
            }
            clearGpuQueries(); gpuMillis.clear();
            frameMillis.clear(); previousFrame = 0; measuringAt = System.nanoTime(); measuring = true;
            next(); return false;
        }
        if (stage == 4) {
            if (System.nanoTime() - measuringAt < 3_000_000_000L) { return false; }
            measuring = false;
            capture();
            if (morphologyPreview && previewPose == 7) {
                evidence.append(EarthCloudFieldGpuVerification.captureTransport(renderer, output()));
                evidence.append(EarthCloudRayConvergenceVerification.capture(renderer, output()));
            }
            if (morphologyPreview) {
                if (++previewPose == PREVIEW_VIEWS.length) {
                    Files.writeString(output().resolve("earth-cloud-morphology-results.txt"), evidence, StandardOpenOption.CREATE_NEW);
                    restore();
                    return true;
                }
                view = PREVIEW_VIEWS[previewPose];
                clouds = PREVIEW_CLOUDS[previewPose];
                stage = 2; ticks = 40;
                return false;
            }
            if (afterReload) {
                if (++automatic < 2) { stage = 2; ticks = 40; return false; }
                evidence.append("realResourceReload shaderReplaced=").append(previousShader != field(renderer, "shader"))
                        .append(" opticsTextureReady=").append((int) field(field(renderer, "atmosphereOptics"), "texture") != 0)
                        .append(" cloudTextureReady=").append((int) field(field(renderer, "cloudNoise"), "texture") != 0).append('\n');
                require(previousShader != field(renderer, "shader"), "Host reload retained the old shader instance");
                evidence.append(ExposureGpuVerification.verify(renderer)).append('\n');
                evidence.append(EarthOpticsGpuVerification.verify()).append('\n');
                stage = 6; ticks = 0; return false;
            }
            if (++clouds < 2) { stage = 2; ticks = 40; return false; }
            clouds = 0;
            if (++automatic < 2) { stage = 2; ticks = 40; return false; }
            automatic = 0;
            if (++view < 8) { stage = 2; ticks = 40; return false; }
            require(meanLuminance[0][0][0] > meanLuminance[0][0][3] * 3,
                    "Unilluminated near Earth is not substantially darker than the day view at fixed exposure");
            require(meanLuminance[0][0][4] > meanLuminance[0][0][7] * 3,
                    "Unilluminated full Earth is not substantially darker than daylight at fixed exposure");
            previousShader = field(renderer, "shader");
            observer = null; orientation = null;
            pending = game.reloadResourcePacks(); stage = 5; ticks = 0; return false;
        }
        if (stage == 5) {
            if (game.getOverlay() != null || game.screen != null) { return false; }
            afterReload = true; view = 0; automatic = 0; clouds = 1;
            stage = 2; ticks = 40; return false;
        }
        if (stage == 6) {
            String bodyId = regressionBody == 0 ? "mars" : regressionBody == 1 ? "moon" : "earth";
            if (regressionBody == 2) { renderer.setContinentalEarth(0); }
            var body = system.bodies().stream().filter(value -> value.id().equals(bodyId)).findFirst().orElseThrow();
            var center = system.positionAt(body, 0);
            var solar = center.multiply(-1).normalized();
            var normal = solar.multiply(.8).add(perpendicular(solar).multiply(.6)).normalized();
            observer = center.add(normal.multiply(regressionBody == 2 ? body.radiusMeters() + 100_000 : body.radiusMeters() * 2.5));
            orientation = aim(normal.multiply(-1)); options.setAutoExposure(false);
            settledAt = System.nanoTime(); next(); return false;
        }
        if (stage == 7) {
            var cache = field(renderer, regressionBody == 2 ? "earthHeights" : "planets");
            boolean ready = regressionBody == 2 ? ((int[]) field(cache, "textures"))[0] != 0
                    : (int) field(cache, "atlas") != 0;
            if (ticks < 100 || System.nanoTime() - settledAt < 5_000_000_000L
                    || !ready || field(cache, "pending") != null) { return false; }
            captureOtherBody();
            if (++regressionBody < 3) { stage = 6; ticks = 0; return false; }
            Files.writeString(output().resolve("earth-atmosphere-results.txt"), evidence, StandardOpenOption.CREATE_NEW);
            restore();
            return true;
        }
        return false;
    }

    private void restore() {
        clearGpuQueries();
        options.setAutoExposure(false); game.options.cloudStatus().set(previousClouds); observer = null; orientation = null;
        if (frozenClock != null && frozenClock.isDone() && !frozenClock.isCompletedExceptionally()) {
            var clock = frozenClock.join();
            var server = game.getSingleplayerServer();
            server.execute(() -> server.tickRateManager().setFrozen(clock.previousFrozen()));
        }
        NeoForge.EVENT_BUS.unregister(camera); NeoForge.EVENT_BUS.unregister(rendering);
        NeoForge.EVENT_BUS.unregister(frames);
    }

    private void render(RenderLevelStageEvent event) {
        if (renderer == null || observer == null || orientation == null) { return; }
        var renderStage = RenderCompatibility.lateWorldPasses()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (event.getStage() != renderStage || RenderCompatibility.shadowPass()) { return; }
        int query = 0;
        try {
            collectGpuQueries();
            if (measuring && gpuQueries.size() < 64 && gpuMillis.size() + gpuQueries.size() < 2048
                    && GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) == 0) {
                query = GL15.glGenQueries(); GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
            }
            renderer.render(event, system, observer, 0, 0, null, 0, 1);
        } catch (RuntimeException exception) { failure = exception; }
        finally {
            if (query != 0) { GL15.glEndQuery(GL33.GL_TIME_ELAPSED); gpuQueries.addLast(query); }
        }
    }

    private void collectGpuQueries() {
        while (!gpuQueries.isEmpty()
                && GL15.glGetQueryObjecti(gpuQueries.getFirst(), GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            int query = gpuQueries.removeFirst();
            long nanos = GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
            gpuMillis.add(nanos / 1_000_000.0);
            GL15.glDeleteQueries(query);
        }
    }

    private void clearGpuQueries() {
        while (!gpuQueries.isEmpty()) { GL15.glDeleteQueries(gpuQueries.removeFirst()); }
    }
    private boolean ready() throws Exception {
        var optics = field(renderer, "atmosphereOptics");
        var continental = field(renderer, "continental");
        if ((int) field(optics, "texture") == 0 || field(optics, "pending") != null
                || (int) field(continental, "globe") == 0 || field(continental, "pending") != null) { return false; }
        var shader = (ShaderInstance) field(renderer, "shader");
        var actual = shader.getUniform("EarthCloudSun").getFloatBuffer();
        return expectedCloudSun != null && new SpaceVector(actual.get(0), actual.get(1), actual.get(2))
                .distance(expectedCloudSun) < .000002;
    }
    private void capture() throws Exception {
        String[] names = {"day", "twilight-sunward", "twilight-away", "night"};
        String name = (afterReload ? "reload-" : "") + (view < 4 ? "100km-" : "disc-")
                + (morphologyPreview && previewPose >= 5 ? "front-head" : names[view % 4])
                + (automatic == 0 ? "-fixed" : "-auto") + (clouds == 0 ? "-clear" : "-clouds");
        var ordered = frameMillis.stream().sorted().toList();
        require(ordered.size() >= 10, "Insufficient stable presented-frame samples");
        evidence.append(String.format(Locale.ROOT,
                "%s presentedFrames n=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms viewport=%dx%d cap=%d vsync=%s%n",
                name, ordered.size(), ordered.get(ordered.size() / 2), ordered.get((int) Math.ceil(ordered.size() * .95) - 1),
                ordered.get((int) Math.ceil(ordered.size() * .99) - 1), ordered.getLast(),
                game.getWindow().getWidth(), game.getWindow().getHeight(), game.options.framerateLimit().get(),
                game.options.enableVsync().get()));
        collectGpuQueries();
        var gpu = gpuMillis.stream().sorted().toList();
        require(gpu.size() >= 10, "Insufficient asynchronously completed celestial GPU samples");
        evidence.append(String.format(Locale.ROOT,
                "%s celestialPassGpu n=%d p50=%.3fms p95=%.3fms p99=%.3fms pendingDiscarded=%d scope=Cosmos-render-HDR-bloom-exposure-composition%n",
                name, gpu.size(), gpu.get(gpu.size() / 2), gpu.get((int) Math.ceil(gpu.size() * .95) - 1),
                gpu.get((int) Math.ceil(gpu.size() * .99) - 1), gpuQueries.size()));
        clearGpuQueries();
        var shader = (ShaderInstance) field(renderer, "shader");
        var cloudParams = shader.getUniform("EarthCloudParams").getFloatBuffer();
        var cloudPlanet = shader.getUniform("CloudPlanet").getFloatBuffer();
        var renderedSun = shader.getUniform("EarthCloudSun").getFloatBuffer();
        require(new SpaceVector(renderedSun.get(0), renderedSun.get(1), renderedSun.get(2)).distance(expectedCloudSun)
                < .000002, "Atmosphere capture retained a stale rendered Sun direction");
        evidence.append(name).append(" renderedSunMatchesDescriptor=true\n");
        if (morphologyPreview) {
            var wind = shader.getUniform("CloudWind").getFloatBuffer();
            float[] actual = {cloudParams.get(1), cloudParams.get(2), cloudParams.get(3), wind.get(0), wind.get(1)};
            if (heldWeatherUniforms == null) { heldWeatherUniforms = actual; }
            require(java.util.Arrays.equals(heldWeatherUniforms, actual),
                    "Matched cloud comparisons changed their actual season, rain, incident source or wind");
            evidence.append(name).append(" frozenShaderWeatherExact=true\n");
        }
        for (String uniform : new String[] {"EarthCloudParams", "CloudWind", "CloudPlanet", "EarthCloudSun", "CloudLayer", "EarthOpticsRadius"}) {
            var values = shader.getUniform(uniform).getFloatBuffer();
            evidence.append(name).append(" ").append(uniform).append("=");
            for (int i = 0; i < values.limit(); i++) { evidence.append(values.get(i)).append(i + 1 == values.limit() ? "" : ","); }
            evidence.append('\n');
        }
        Files.writeString(output().resolve("earth-atmosphere-progress.txt"), evidence);
        require(clouds == 0 ? cloudParams.get(0) == 0 : Math.abs(cloudParams.get(0) - .55f) < 1e-6
                && cloudPlanet.get(3) > 6000, "Orbital cloud toggle did not reach actual Earth shader; status="
                + game.options.getCloudsType() + ", cover=" + cloudParams.get(0) + ", radius=" + cloudPlanet.get(3));
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("earth-atmosphere-" + name + ".png"));
            double light = 0; int count = 0, neonMagenta = 0;
            for (int y = image.getHeight() / 2; y < image.getHeight() * 4 / 5; y++) {
                for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                    int pixel = image.getPixelRGBA(x, y), red = pixel & 255, green = pixel >>> 8 & 255, blue = pixel >>> 16 & 255;
                    light += (.2126 * red + .7152 * green + .0722 * blue) / 255; count++;
                    if (red > green * 1.7 && blue > green * 1.7 && red > 120 && blue > 120) { neonMagenta++; }
                }
            }
            meanLuminance[clouds][automatic][view] = light / count;
            evidence.append(name).append(" regionDisplayLuminance=").append(light / count)
                    .append(" brightMagentaPixels=").append(neonMagenta).append(" samples=").append(count).append('\n');
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Atmosphere presentation left a GL error");
        Files.writeString(output().resolve("earth-atmosphere-progress.txt"), evidence);
    }
    private void captureOtherBody() throws Exception {
        var shader = (ShaderInstance) field(renderer, "shader");
        int index = shader.getUniform("AtmosphereBodyIndex").getIntBuffer().get(0);
        String name = regressionBody == 0 ? "mars" : regressionBody == 1 ? "moon" : "legacy-earth";
        require(regressionBody == 1 ? index == -1 : index >= 0,
                "Changed Earth transport selected an invalid atmosphere for " + name);
        if (regressionBody == 0) {
            require(shader.getUniform("BodyAtmosphereModel[" + index + "]").getFloatBuffer().get(0) > .5f,
                    "Mars lost its distinct dust atmosphere");
        }
        if (regressionBody == 2) {
            require(shader.getUniform("ContinentalEarth").getIntBuffer().get(0) == 0
                    && shader.getUniform("EarthHeightCacheEnabled").getIntBuffer().get(0) == 1
                    && shader.getUniform("CloudPlanet").getFloatBuffer().get(3) == 0,
                    "Legacy Earth retained canonical cloud or geography state");
            var samplers = (java.util.Map<?, ?>) field(shader, "samplerMap");
            int legacyTexture = ((int[]) field(field(renderer, "earthHeights"), "textures"))[0];
            int cloudTexture = (int) field(field(renderer, "cloudNoise"), "texture");
            require(samplers.get("EarthHeightTile0").equals(legacyTexture) && legacyTexture != cloudTexture,
                    "Canonical cloud alias was not replaced by the legacy Earth height texture");
            evidence.append("legacyEarth heightSamplerRestored=true canonicalCloudDisabled=true\n");
        }
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("earth-atmosphere-regression-" + name + ".png"));
            int rust = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int pixel = image.getPixelRGBA(x, y), red = pixel & 255, green = pixel >>> 8 & 255, blue = pixel >>> 16 & 255;
                    if (red > blue * 1.15 + 10 && red > green * 1.05 && red > 35) { rust++; }
                }
            }
            if (regressionBody == 0) { require(rust > 1000, "Mars lost its visible rusty material"); }
            evidence.append("regression body=").append(name).append(" atmosphereIndex=").append(index)
                    .append(" warmPixels=").append(rust).append('\n');
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Other-body atmosphere regression left a GL error");
    }

    private void frame() {
        long now = System.nanoTime();
        if (measuring && previousFrame != 0 && game.screen == null && game.getOverlay() == null) {
            frameMillis.add((now - previousFrame) / 1e6);
        }
        previousFrame = now;
    }
    private java.nio.file.Path output() throws java.io.IOException {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path); return path;
    }
    private void next() { stage++; ticks = 0; }
    private static FlightOrientation aim(SpaceVector unit) {
        return FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-unit.x(), unit.z())), Math.toDegrees(Math.asin(-unit.y())), 0);
    }
    private static SpaceVector perpendicular(SpaceVector unit) {
        var axis = Math.abs(unit.z()) < .9 ? new SpaceVector(0, 0, 1) : new SpaceVector(1, 0, 0);
        return axis.subtract(unit.multiply(axis.dot(unit))).normalized();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
    private record FrozenClock(boolean previousFrozen, long gameTime, long dayTime) { }
}
