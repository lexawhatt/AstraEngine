package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.FlightCamera;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SkyService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Actual Sol pilot and normal renderer observing the existing occupied server diagnostic timeline. */
final class OrbitalSupernovaScenario {
    private static final long[] CAPTURE_TICKS = {100, 180, 240, 285, 340, 520, 600};
    private static final String[] CAPTURE_NAMES = {"distended", "critical", "collapse", "flash", "afterglow", "tail", "remnant"};
    private final Minecraft game = Minecraft.getInstance();
    private final Consumer<RenderFrameEvent.Post> frames = event -> frame();
    private final StringBuilder evidence = new StringBuilder("Actual public ORBIT entry, normal W flight, one production renderer/exposure owner.\n"
            + "The existing operator diagnostic Sun supernova is fictional; no new simulation or source clock.\n");
    private final StringBuilder history = new StringBuilder("run,wall_seconds,active_ticks,phase,phase_ticks,revision,altitude_m,luminosity,flash,cloud_source,auto,adapted_ev,target_ev,meter_luminance\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<Boolean> sourceReady;
    private RocketController controller;
    private CosmosRenderer renderer;
    private RenderOptions options;
    private SolarStateClient solar;
    private FlightCamera camera;
    private String sourceDimension;
    private int stage, ticks, run, captureIndex;
    private long stageStarted = System.nanoTime(), cycleStarted, cycleId, lastActiveTick = -1;
    private RuntimeException failure;
    private boolean sampling, opticsVerified;
    private double targetAltitude, requestedSpeed = -1;
    private float minEv = Float.POSITIVE_INFINITY, maxEv = Float.NEGATIVE_INFINITY;
    private int exposureSamples;

    OrbitalSupernovaScenario() {
        game.options.pauseOnLostFocus = false;
        game.options.hideGui = true;
        game.options.fov().set(70);
        game.options.bobView().set(false);
        game.options.cloudStatus().set(CloudStatus.FANCY);
        game.options.renderDistance().set(6);
        game.options.simulationDistance().set(5);
        game.options.broadcastOptions();
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - stageStarted < 240_000_000_000L, "Orbital supernova timed out at " + stage);
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (stage == 0) {
            if (game.screen != null) { return false; }
            command("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            controller = map.controller(); map.onClose();
            renderer = (CosmosRenderer) field(controller, "renderer");
            options = (RenderOptions) field(controller, "options");
            solar = (SolarStateClient) field(controller, "solar");
            camera = (FlightCamera) field(controller, "flightCamera");
            options.setAutoExposure(false); options.setExposure(1);
            var cover = options.getClass().getDeclaredField("cloudCover"); cover.setAccessible(true); cover.setFloat(options, .55f);
            var server = game.getSingleplayerServer();
            pending = server.submit(() -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                server.overworld().setWeatherParameters(100000, 0, false, false);
                // The occupied cloud head uses body azimuth -28, hence geographic east longitude +28.
                var address = new GeographicPosition(Math.toRadians(48), Math.toRadians(28), 0);
                var profile = dev.lexawhatt.astraengine.api.AstraSky.profile(server);
                long selectedDay = 0;
                double bestError = Double.POSITIVE_INFINITY;
                for (long candidate = 0; candidate < 24000; candidate += 100) {
                    var ephemeris = dev.lexawhatt.astraengine.surface.EarthEphemeris.sample(profile, candidate, 0);
                    var towardSun = ephemeris.frame().centerMeters().multiply(-1).normalized();
                    double cosine = ephemeris.frame().toSystemDirection(address.normal()).dot(towardSun);
                    double error = Math.abs(cosine - Math.sin(Math.toRadians(20)));
                    if (error < bestError) { bestError = error; selectedDay = candidate; }
                }
                server.overworld().setDayTime(selectedDay);
                int version = EarthWorlds.terrainVersion(server);
                var terrain = new ContinentalTerrain(version, ContinentalTerrain.SEED).sample(address.normal());
                address = new GeographicPosition(address.latitudeRadians(), address.longitudeRadians(), terrain.waterMeters() + 30);
                var chart = EarthChart.owner(address, version).orElseThrow();
                var level = PlanetSurfaceWorlds.ensure(server, chart);
                var feet = chart.resolve(address).orElseThrow();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(level, feet.x(), feet.y(), feet.z(), 0, -90);
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                SkyService.sendWeather(player);
                sourceDimension = chart.dimensionId();
            });
            next(); return false;
        }
        if (stage == 2) {
            if (ticks < 40 || game.screen != null || !game.level.dimension().location().toString().equals(sourceDimension)) { return false; }
            if (sourceReady == null || !sourceReady.join()) {
                var server = game.getSingleplayerServer();
                sourceReady = server.submit(() -> dev.lexawhatt.astraengine.server.PreparedPlayerReturn.acceptsSource(
                        server, server.getPlayerList().getPlayers().getFirst()));
                pending = sourceReady;
                return false;
            }
            command("astra-flight orbit"); next(); return false;
        }
        if (stage == 3) {
            if (!controller.active() || !game.level.dimension().equals(RocketService.FLIGHT)
                    || automaticCamera() || ticks < 30) { return false; }
            targetAltitude = 400_000;
            next(); return false;
        }
        if (stage == 4) {
            require(controller.active() && game.level.dimension().equals(RocketService.FLIGHT), "Orbital positioning left real Sol flight");
            var display = controller.view();
            var frame = display.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
            var radial = display.position().subtract(frame.centerMeters());
            double altitude = radial.length() - frame.radiusMeters();
            double error = targetAltitude - altitude;
            if (Math.abs(error) < 180) {
                game.options.keyUp.setDown(false);
                requestedSpeed = -1;
                next(); return false;
            }
            var direction = radial.normalized().multiply(Math.signum(error));
            camera.reset(aim(direction));
            double wantedSpeed = Math.clamp(Math.abs(error) * .45, 100, 20_000);
            if (ticks % 10 == 1 && (requestedSpeed < 0 || Math.abs(requestedSpeed - wantedSpeed) > 20)) {
                require(controller.setSpeed(wantedSpeed), "Normal orbital positioning speed was rejected locally");
                requestedSpeed = wantedSpeed;
            }
            game.mouseHandler.grabMouse(); game.options.keyUp.setDown(true);
            return false;
        }
        if (stage == 5) {
            game.options.keyUp.setDown(false);
            if (ticks < 20 || controller.snapshot().velocity().length() > .01) { return false; }
            aimScene();
            options.setAutoExposure((run & 1) != 0);
            command("astra sun reset");
            minEv = Float.POSITIVE_INFINITY; maxEv = Float.NEGATIVE_INFINITY;
            exposureSamples = 0;
            next(); return false;
        }
        if (stage == 6) {
            aimScene();
            if (ticks < 100 || System.nanoTime() - stageStarted < 6_000_000_000L || !resourcesReady()
                    || solar.snapshot() == null || solar.snapshot().running() || solar.snapshot().extracted() != 0) { return false; }
            require(game.screen == null && game.getOverlay() == null, "Orbital scene was obscured before evolution");
            if (!opticsVerified) {
                evidence.append(EarthCloudFieldGpuVerification.verify(renderer));
                evidence.append(ExposureGpuVerification.verify(renderer)).append('\n');
                opticsVerified = true;
                retain();
                return false;
            }
            capture("healthy");
            cycleId = solar.snapshot().cycle() + 1;
            captureIndex = 0; lastActiveTick = -1;
            cycleStarted = System.nanoTime(); sampling = true;
            command("astra sun demo 10");
            next(); return false;
        }
        if (stage == 7) {
            aimScene();
            var snapshot = solar.snapshot();
            if (snapshot.cycle() != cycleId) { return false; }
            require(snapshot.activeTicks() >= lastActiveTick, "Authoritative solar clock moved backwards");
            lastActiveTick = snapshot.activeTicks();
            if (captureIndex < CAPTURE_TICKS.length && snapshot.activeTicks() >= CAPTURE_TICKS[captureIndex]) {
                if (captureIndex == 3) {
                    // The packet/interpolated source may already advance while the completed image still
                    // contains the previous frame. Gate the actual shader input that produced the PNG.
                    var shader = (ShaderInstance) field(renderer, "shader");
                    float renderedFlash = shader.getUniform("SolarLight").getFloatBuffer().get(1);
                    if (renderedFlash < .6f && snapshot.activeTicks() < 300) { return false; }
                    require(renderedFlash >= .4f, "The actual flash was skipped before a visible frame");
                }
                capture(CAPTURE_NAMES[captureIndex++]);
            }
            if (captureIndex < CAPTURE_TICKS.length) { return false; }
            require(snapshot.phase() == StellarEvolutionSnapshot.Phase.REMNANT && !snapshot.running()
                    && snapshot.activeTicks() == 600, "Real demo10 did not finish after its600 occupied ticks");
            next(); return false;
        }
        if (stage == 8) {
            aimScene();
            if (ticks < 100 || System.nanoTime() - stageStarted < 6_000_000_000L) { return false; }
            capture("settled-remnant"); sampling = false;
            require(exposureSamples >= 80, "The actual source timeline lacks enough exposure history samples");
            require(!options.autoExposure() || maxEv - minEv > .15f,
                    "Automatic exposure did not respond to the real solar timeline");
            evidence.append("run=").append(run).append(" adaptedEVRange=").append(minEv).append("..").append(maxEv).append('\n');
            retain();
            if (++run == 4) {
                command("astra sun reset"); options.setAutoExposure(false);
                NeoForge.EVENT_BUS.unregister(frames);
                return true;
            }
            if (run == 2) {
                targetAltitude = 100_500; stage = 4; ticks = 0; stageStarted = System.nanoTime();
            } else { stage = 5; ticks = 0; stageStarted = System.nanoTime(); }
            return false;
        }
        return false;
    }

    private void aimScene() throws Exception {
        var display = controller.view();
        var frame = display.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
        var radial = display.position().subtract(frame.centerMeters());
        var up = radial.normalized();
        var sun = display.position().multiply(-1).normalized();
        var horizontal = sun.subtract(up.multiply(up.dot(sun))).normalized();
        double altitude = radial.length() - frame.radiusMeters();
        double horizonDip = Math.acos(frame.radiusMeters() / radial.length());
        double sunAltitude = Math.asin(up.dot(sun));
        double pitch = (sunAltitude - horizonDip) * .5;
        var forward = horizontal.multiply(Math.cos(pitch)).add(up.multiply(Math.sin(pitch)));
        var orientation = aim(forward);
        var screenUp = up.subtract(forward.multiply(forward.dot(up))).normalized();
        orientation = orientation.rotateLocal(0, 0, Math.toDegrees(Math.atan2(-screenUp.dot(orientation.left()), screenUp.dot(orientation.up()))));
        camera.reset(orientation);
        require(Math.abs(altitude - targetAltitude) < 1000, "A stationary orbital scene changed altitude: " + altitude);
        require(sun.dot(orientation.forward()) > Math.cos(Math.toRadians(32)), "The real Sun lies outside the orbital frame");
        var limb = horizontal.multiply(Math.cos(horizonDip)).subtract(up.multiply(Math.sin(horizonDip)));
        require(limb.dot(orientation.forward()) > Math.cos(Math.toRadians(32)), "The Earth limb lies outside the orbital frame");
    }

    private void frame() {
        if (!sampling || failure != null || game.screen != null || game.getOverlay() != null) { return; }
        try {
            var state = solar.snapshot();
            if (state.cycle() != cycleId) { return; }
            var shader = (ShaderInstance) field(renderer, "shader");
            var light = shader.getUniform("SolarLight").getFloatBuffer();
            var cloud = shader.getUniform("EarthCloudParams").getFloatBuffer();
            require(Math.abs(cloud.get(3) - Math.max(0, light.get(0) + light.get(1) * .6f)) < .00001,
                    "Cloud lighting did not use the actual shared solar source");
            float[] meter = meter();
            exposureSamples++;
            minEv = Math.min(minEv, meter[0]); maxEv = Math.max(maxEv, meter[0]);
            var display = controller.view();
            var frame = display.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
            double altitude = display.position().distance(frame.centerMeters()) - frame.radiusMeters();
            history.append(String.format(Locale.ROOT, "%d,%.6f,%d,%s,%d,%d,%.3f,%.6f,%.6f,%.6f,%s,%.6f,%.6f,%.6f%n",
                    run, (System.nanoTime() - cycleStarted) * 1e-9, state.activeTicks(), state.phase(), state.phaseTicks(),
                    state.revision(), altitude, light.get(0), light.get(1), cloud.get(3), options.autoExposure(),
                    meter[0], meter[1], meter[2]));
        } catch (Exception exception) { failure = new IllegalStateException("Orbital supernova frame evidence failed", exception); }
    }

    private float[] meter() throws Exception {
        var pipeline = field(renderer, "bloom");
        var composite = (ShaderInstance) field(pipeline, "composite");
        require(composite.getUniform("AutoExposure").getIntBuffer().get(0) == (options.autoExposure() ? 1 : 0),
                "Actual final composition does not match requested exposure mode");
        if (!options.autoExposure()) { return new float[] {0, 0, 0}; }
        var meter = field(pipeline, "exposureMeter");
        var histories = (Object[]) field(meter, "history");
        require(histories.length == 2 && (boolean) field(meter, "initialized"), "Normal exposure history is unavailable");
        var target = histories[(int) field(meter, "previous")];
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] names = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] values = new int[names.length];
        for (int i = 0; i < names.length; i++) { values[i] = GL11.glGetInteger(names[i]); }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < names.length; i++) { GL11.glPixelStorei(names[i], i == 0 ? 1 : 0); }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, (int) field(target, "framebuffer"));
            var pixel = stack.mallocFloat(4);
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            float[] result = {pixel.get(0), pixel.get(1), pixel.get(2)};
            require(Float.isFinite(result[0]) && result[0] >= -6.01f && result[0] <= 2.01f
                    && Float.isFinite(result[1]) && result[1] >= -6.01f && result[1] <= 2.01f
                    && Float.isFinite(result[2]) && result[2] >= 0, "Actual exposure history is unbounded");
            return result;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            for (int i = 0; i < names.length; i++) { GL11.glPixelStorei(names[i], values[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }

    private void capture(String label) throws Exception {
        var state = solar.snapshot();
        var display = controller.renderedView();
        require(display != null, "Orbital solar capture has no completed production sky draw");
        var frame = display.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
        String name = (run < 2 ? "400km" : "100km") + ((run & 1) == 0 ? "-fixed-" : "-auto-") + label;
        var shader = (ShaderInstance) field(renderer, "shader");
        var renderedSun = shader.getUniform("EarthCloudSun").getFloatBuffer();
        var expectedSun = frame.toBodyDirection(frame.centerMeters().multiply(-1).normalized());
        double sunError = new SpaceVector(renderedSun.get(0), renderedSun.get(1), renderedSun.get(2)).distance(expectedSun);
        require(sunError < .00001, "Orbital solar capture retained a stale rendered Sun direction: " + sunError);
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("orbital-sun-" + name + ".png"));
        }
        evidence.append(name).append(" phase=").append(state.phase()).append(" activeTicks=").append(state.activeTicks())
                .append(" phaseTicks=").append(state.phaseTicks()).append(" cycle=").append(state.cycle())
                .append(" revision=").append(state.revision()).append(" altitudeMeters=")
                .append(display.position().distance(frame.centerMeters()) - frame.radiusMeters())
                .append(" bodyNormal=").append(frame.toBodyDirection(display.position().subtract(frame.centerMeters()).normalized()))
                .append(" source=").append(solar.visual()).append('\n');
        evidence.append(name).append(" renderedSunDirectionError=").append(sunError).append('\n');
        for (String uniform : new String[] {"SolarLight", "Evolution", "EarthCloudParams", "CloudWind", "CloudPlanet"}) {
            var values = shader.getUniform(uniform).getFloatBuffer();
            evidence.append(name).append(' ').append(uniform).append('=');
            for (int i = 0; i < values.limit(); i++) { evidence.append(values.get(i)).append(i + 1 == values.limit() ? "" : ","); }
            evidence.append('\n');
        }
        evidence.append(name).append(" actualExposureHistory=").append(java.util.Arrays.toString(meter())).append('\n');
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Orbital supernova capture left a GL error");
        retain();
    }

    private boolean automaticCamera() throws ReflectiveOperationException {
        var method = RocketController.class.getDeclaredMethod("automaticCamera");
        method.setAccessible(true);
        return (boolean) method.invoke(controller);
    }

    private boolean resourcesReady() throws Exception {
        var optics = field(renderer, "atmosphereOptics");
        var continents = field(renderer, "continental");
        var shader = (ShaderInstance) field(renderer, "shader");
        return (int) field(optics, "texture") != 0 && field(optics, "pending") == null
                && (int) field(continents, "globe") != 0 && field(continents, "pending") == null
                && shader.getUniform("CloudPlanet").getFloatBuffer().get(3) > 6000;
    }
    private void retain() throws Exception {
        Files.writeString(output().resolve("orbital-supernova-results.txt"), evidence);
        Files.writeString(output().resolve("orbital-supernova-history.csv"), history);
    }
    private java.nio.file.Path output() throws java.io.IOException {
        var result = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(result); return result;
    }
    private void command(String command) { game.player.connection.sendCommand(command); }
    private void next() throws Exception {
        stage++; ticks = 0; stageStarted = System.nanoTime();
        evidence.append("stage=").append(stage).append(" run=").append(run).append('\n');
        retain();
    }
    private static FlightOrientation aim(SpaceVector direction) {
        return FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                -Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1))), 0);
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalStateException(message); } }
}
