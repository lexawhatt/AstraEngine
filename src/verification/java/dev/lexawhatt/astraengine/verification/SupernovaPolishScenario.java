package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import dev.lexawhatt.astraengine.server.SolarState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Frozen server evolution samples and explicitly controlled production renderer poses; no performance assertions. */
final class SupernovaPolishScenario {
    private static final String[] SAMPLE_NAMES = {"healthy", "flash", "expansion", "filaments", "tail", "remnant"};
    private static final int[] ACTIVE_TICKS = {0, 284, 330, 410, 520, 600};
    private static final long DAY_TIME = 2500;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final RocketController controller;
    private final CelestialRendererProbe renderer;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };
    private final Consumer<SolarReceivedEvent> solarListener = event -> this.received = event.payload().snapshot();
    private final Consumer<ViewportEvent.ComputeCameraAngles> cameraListener = this::camera;
    private final Consumer<RenderLevelStageEvent> renderListener = this::render;
    private final StringBuilder metrics = new StringBuilder(
            "capture,phase,active_ticks,game_time,frames,mean,saturated_fraction,bloom_absolute_delta\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private StellarEvolutionSnapshot expected;
    private StellarEvolutionSnapshot received;
    private CosmosSystem scene;
    private SpaceVector cameraMeters;
    private SpaceVector authoritativePosition;
    private FlightOrientation orientation;
    private RuntimeException renderFailure;
    private int[] bloomReference;
    private int sample;
    private int step;
    private int ticks;
    private int poseFrames;
    private int generatedPose;
    private long renderedFrames;
    private long firstFrame;
    private long fixedGameTime = -1;
    private boolean generated;
    private boolean bloom = true;
    private float yaw;
    private float pitch;

    SupernovaPolishScenario(RocketController controller) {
        this.controller = controller;
        renderer = new CelestialRendererProbe(controller);
        minecraft.options.hideGui = true;
        minecraft.options.cloudStatus().set(CloudStatus.OFF);
        var sun = SkyEphemeris.sample(PlanetarySkyProfile.EARTH, DAY_TIME, 0).sunDirection();
        yaw = (float) Math.toDegrees(Math.atan2(-sun.x(), sun.z()));
        pitch = -(float) Math.toDegrees(Math.asin(sun.y()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, solarListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, cameraListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, renderListener);
    }

    boolean tick() throws Exception {
        try {
            if (renderFailure != null) { throw renderFailure; }
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 1000, "Supernova polish stage timed out");
            if (step <= 2) {
                minecraft.player.setYRot(yaw);
                minecraft.player.setXRot(pitch);
            }
            switch (step) {
                case 0 -> {
                    command("astra-render environment auto");
                    command("astra-render exposure 1");
                    command("astra-render quality balanced");
                    setBloom(true);
                    server(this::prepareOverworld);
                    next();
                }
                case 1 -> {
                    if (!ready(24)) { return false; }
                    shot("nova-overworld-" + SAMPLE_NAMES[sample] + "-bloom-on");
                    setBloom(false);
                    next();
                }
                case 2 -> {
                    if (!ready(12)) { return false; }
                    shot("nova-overworld-" + SAMPLE_NAMES[sample] + "-bloom-off");
                    setBloom(true);
                    if (++sample < SAMPLE_NAMES.length) {
                        server(this::publishSample);
                        moveTo(1);
                    } else {
                        server(server -> server.tickRateManager().setFrozen(false));
                        next();
                    }
                }
                case 3 -> { tap(GLFW.GLFW_KEY_R); next(); }
                case 4 -> {
                    if (!controller.active() || ticks < 25) { return false; }
                    authoritativePosition = controller.snapshot().position();
                    fixedGameTime = -1;
                    sample = 1;
                    scene = CosmosGenerator.sol();
                    setPose(new SpaceVector(0, 0, -CosmosGenerator.AU));
                    server(server -> {
                        server.tickRateManager().setFrozen(true);
                        publishSample(server);
                    });
                    next();
                }
                case 5 -> {
                    if (!ready(24) || poseFrames < 4) { return false; }
                    shot("nova-cosmos-" + SAMPLE_NAMES[sample] + "-bloom-on");
                    setBloom(false);
                    next();
                }
                case 6 -> {
                    if (!ready(12) || poseFrames < 4) { return false; }
                    shot("nova-cosmos-" + SAMPLE_NAMES[sample] + "-bloom-off");
                    setBloom(true);
                    if (++sample < SAMPLE_NAMES.length) {
                        server(this::publishSample);
                        moveTo(5);
                    } else {
                        generated = true;
                        scene = CosmosGenerator.byId(controller.snapshot().galaxySeed(), "s_-3_-1_0");
                        require(scene.kind() == CosmosSystem.Kind.SUPERNOVA, "Seeded native remnant identity changed");
                        generatedPose = 0;
                        generatedPose();
                        next();
                    }
                }
                case 7 -> {
                    if (!ready(12) || poseFrames < 4) { return false; }
                    shot("nova-generated-" + generatedPose + "-bloom-on");
                    setBloom(false);
                    next();
                }
                case 8 -> {
                    if (!ready(12) || poseFrames < 4) { return false; }
                    shot("nova-generated-" + generatedPose + "-bloom-off");
                    setBloom(true);
                    if (++generatedPose < 3) {
                        generatedPose();
                        moveTo(7);
                    } else {
                        require(controller.snapshot().position().equals(authoritativePosition)
                                        && controller.snapshot().systemId().equals("sol"),
                                "Diagnostic renderer inputs changed actual navigation");
                        scene = null;
                        server(server -> {
                            SolarState.get(server).reset();
                            server.tickRateManager().setFrozen(false);
                            PacketDistributor.sendToPlayer(server.getPlayerList().getPlayers().getFirst(),
                                    new SolarPayload(SolarState.get(server).snapshot()));
                        });
                        next();
                    }
                }
                case 9 -> { tap(GLFW.GLFW_KEY_R); next(); }
                case 10 -> {
                    if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 20) { return false; }
                    retain();
                    dispose();
                    AstraEngine.LOGGER.info("ASTRA_SUPERNOVA_POLISH_PASSED frames={} graphics={}",
                            renderedFrames, minecraft.options.graphicsMode().get());
                    return true;
                }
                default -> throw new IllegalStateException("Unknown supernova polish stage " + step);
            }
            return false;
        } catch (Exception failure) {
            retain();
            dispose();
            throw failure;
        }
    }

    private void prepareOverworld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(DAY_TIME);
        level.setWeatherParameters(100000, 0, false, false);
        AstraSky.configure(server, PlanetarySkyProfile.EARTH.withLightPollution(0));
        server.tickRateManager().setFrozen(true);
        publishSample(server);
    }

    private void publishSample(MinecraftServer server) {
        SolarState state = SolarState.get(server);
        state.reset();
        if (ACTIVE_TICKS[sample] > 0) {
            state.startDemo(10);
            for (int tick = 0; tick < ACTIVE_TICKS[sample]; tick++) {
                require(state.tick(true), "Real server evolution stopped before diagnostic sample");
            }
            state.pause();
        }
        expected = state.snapshot();
        require(expected.activeTicks() == ACTIVE_TICKS[sample], "Server evolution sample overshot");
        PacketDistributor.sendToPlayer(server.getPlayerList().getPlayers().getFirst(), new SolarPayload(expected));
    }

    private void generatedPose() {
        double radius = scene.bodies().getFirst().radiusMeters();
        SpaceVector direction = switch (generatedPose) {
            case 0 -> new SpaceVector(0, 0, -1);
            case 1 -> new SpaceVector(0.8, 0.35, -0.48).normalized();
            default -> new SpaceVector(0, 1, 0);
        };
        setPose(direction.multiply(radius * 60));
    }

    private void setPose(SpaceVector position) {
        cameraMeters = position;
        SpaceVector direction = position.multiply(-1).normalized();
        orientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                -Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1))), 0);
        poseFrames = 0;
    }

    private void camera(ViewportEvent.ComputeCameraAngles event) {
        if (scene == null || !controller.active()) { return; }
        event.setYaw(orientation.yaw());
        event.setPitch(orientation.pitch());
        event.setRoll(orientation.roll());
    }

    private void render(RenderLevelStageEvent event) {
        if (scene == null || !controller.active() || expected == null || renderFailure != null
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) { return; }
        try {
            SolarVisual solar = generated ? SolarVisual.HEALTHY : SolarVisual.from(
                    1 - (float) expected.remaining() / StellarEvolutionSnapshot.CAPACITY,
                    expected.phase(), expected.phaseTicks());
            require(renderer.draw(event, scene, cameraMeters, 0, controller.exposure(), solar) > 0,
                    "Production celestial extraction returned no bodies");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error in controlled supernova production draw");
            poseFrames++;
        } catch (RuntimeException failure) { renderFailure = failure; }
    }

    private boolean ready(int settle) {
        return ticks >= settle && renderedFrames >= firstFrame + 6 && expected != null && expected.equals(received)
                && !expected.running() && minecraft.level.tickRateManager().isFrozen();
    }

    private void shot(String name) throws Exception {
        if (fixedGameTime < 0) { fixedGameTime = minecraft.level.getGameTime(); }
        require(minecraft.level.getGameTime() == fixedGameTime, "Shader animation clock advanced between frozen comparisons");
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        minecraft.gui.getChat().clearMessages(false);
        double sum = 0, difference = 0;
        int saturated = 0;
        int[] values;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            values = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int index = y * image.getWidth() + x;
                    int color = image.getPixelRGBA(x, y);
                    values[index] = color;
                    int r = color & 255, g = color >>> 8 & 255, b = color >>> 16 & 255;
                    sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
                    if (r > 250 && g > 250 && b > 250) { saturated++; }
                    if (!bloom && bloomReference != null) {
                        int previous = bloomReference[index];
                        difference += (Math.abs(r - (previous & 255)) + Math.abs(g - (previous >>> 8 & 255))
                                + Math.abs(b - (previous >>> 16 & 255))) / (255.0 * 3);
                    }
                }
            }
        }
        if (bloom) { bloomReference = values; }
        metrics.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.8f,%.8f,%.8f%n", name,
                expected.phase(), expected.activeTicks(), fixedGameTime, renderedFrames, sum / values.length,
                (double) saturated / values.length, difference / values.length));
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after supernova capture");
        AstraEngine.LOGGER.info("ASTRA_POLISH_CAPTURE {} mean={} saturated={} snapshot={}", name,
                sum / values.length, (double) saturated / values.length, expected);
    }

    private void retain() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("supernova-polish-metrics.csv"), metrics.toString());
        Files.writeString(directory.resolve("supernova-polish-scope.txt"),
                "Overworld samples run the real server diagnostic evolution, pause it, and publish normal snapshots.\n"
                + "Host game time remains frozen between comparisons; bloom alone is toggled for each paired capture.\n"
                + "Clouds are disabled to expose the stellar material, exposure=1 and quality=balanced.\n"
                + "Cosmos samples add a controlled production renderer draw at 1 AU; generated remnants use 60 primary radii.\n"
                + "The renderer probe changes presentation inputs only; actual server position/system remain unchanged.\n"
                + "Framebuffer metrics are descriptive visual evidence, not a GPU benchmark or a subjective beauty assertion.\n");
    }

    private void dispose() {
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(solarListener);
        NeoForge.EVENT_BUS.unregister(cameraListener);
        NeoForge.EVENT_BUS.unregister(renderListener);
    }
    private void setBloom(boolean value) { bloom = value; command("astra-render bloom " + value); }
    private void command(String value) { minecraft.player.connection.sendCommand(value); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() { moveTo(step + 1); }
    private void moveTo(int value) { step = value; ticks = 0; firstFrame = renderedFrames; poseFrames = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (supernova polish step " + step + ", sample " + sample + ")"); }
    }
}
