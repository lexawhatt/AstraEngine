package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.PulsarGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Controlled production sky inputs; atlas navigation is covered by a separate real travel scenario. */
final class LunarPulsarVisualScenario {
    private static final String[] POSES = {"moon-full", "moon-quarter", "moon-crescent", "moon-near-terminator",
            "pulsar-side", "pulsar-quarter-turn", "pulsar-beam", "pulsar-near", "pulsar-subpixel"};
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.frames++; }
    };
    private final Consumer<ViewportEvent.ComputeCameraAngles> cameraListener = this::camera;
    private final Consumer<RenderLevelStageEvent> renderListener = this::render;
    private final StringBuilder evidence = new StringBuilder("capture\tframes\tscene_seconds\tmean_rgb\tsaturated_pixels\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private CelestialRendererProbe renderer;
    private CosmosSystem scene;
    private SpaceVector cameraMeters;
    private SpaceVector authoritativePosition;
    private FlightOrientation orientation;
    private RuntimeException renderFailure;
    private double sceneSeconds;
    private long frames;
    private long startFrame;
    private int renderedPoseFrames;
    private int pose;
    private int step;
    private int ticks;

    LunarPulsarVisualScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, cameraListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, renderListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            if (renderFailure != null) { throw renderFailure; }
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 1000, "Lunar/pulsar native stage timed out");
            switch (step) {
                case 0 -> {
                    server(server -> {
                        for (int x = -2; x <= 2; x++) {
                            for (int z = -2; z <= 2; z++) {
                                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE.defaultBlockState());
                            }
                        }
                        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
                    });
                    command("astra-render quality balanced");
                    command("astra-render bloom true");
                    command("astra-render exposure 1");
                    next();
                }
                case 1 -> { if (!ready(20)) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
                case 2 -> {
                    if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(8)) { return false; }
                    controller = map.controller();
                    renderer = new CelestialRendererProbe(controller);
                    map.onClose();
                    tap(GLFW.GLFW_KEY_R);
                    next();
                }
                case 3 -> {
                    if (!controller.active() || !ready(30)) { return false; }
                    authoritativePosition = controller.snapshot().position();
                    setPose(0);
                    server(server -> server.tickRateManager().setFrozen(true));
                    next();
                }
                case 4 -> {
                    if (!ready(16) || renderedPoseFrames < 6) { return false; }
                    shot(POSES[pose] + "-bloom-on");
                    command("astra-render bloom false");
                    next();
                }
                case 5 -> {
                    if (!ready(12) || renderedPoseFrames < 6) { return false; }
                    shot(POSES[pose] + "-bloom-off");
                    command("astra-render bloom true");
                    if (++pose < POSES.length) {
                        setPose(pose);
                        go(4);
                    } else {
                        setPose(4);
                        pending = minecraft.reloadResourcePacks();
                        next();
                    }
                }
                case 6 -> {
                    if (!ready(16) || renderedPoseFrames < 6) { return false; }
                    shot("pulsar-reloaded");
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 640);
                    next();
                }
                case 7 -> {
                    if (!ready(16) || renderedPoseFrames < 6 || minecraft.getWindow().getWidth() != 960
                            || minecraft.getWindow().getHeight() != 640) { return false; }
                    shot("pulsar-resized");
                    require(controller.snapshot().position().equals(authoritativePosition)
                                    && controller.snapshot().systemId().equals("sol"),
                            "Presentation probe altered real flight navigation");
                    scene = null;
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                    server(server -> server.tickRateManager().setFrozen(false));
                    next();
                }
                case 8 -> { tap(GLFW.GLFW_KEY_R); next(); }
                case 9 -> {
                    if (!ready(20) || !minecraft.level.dimension().equals(Level.OVERWORLD)) { return false; }
                    retain();
                    dispose();
                    AstraEngine.LOGGER.info("ASTRA_LUNAR_PULSAR_VISUAL_PASSED frames={} graphics={} compatibility={}",
                            frames, minecraft.options.graphicsMode().get(), RenderCompatibility.status());
                    return true;
                }
                default -> throw new IllegalStateException("Unknown lunar/pulsar visual step " + step);
            }
            return false;
        } catch (Exception failure) {
            retain();
            dispose();
            throw failure;
        }
    }

    private void setPose(int index) {
        pose = index;
        sceneSeconds = 0;
        SpaceVector direction;
        if (index < 4) {
            scene = CosmosGenerator.sol();
            CelestialBody moon = scene.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
            SpaceVector center = scene.positionAt(moon, sceneSeconds);
            SpaceVector sun = center.multiply(-1).normalized();
            SpaceVector side = new SpaceVector(-sun.z(), 0, sun.x()).normalized();
            SpaceVector offset = switch (index) {
                case 0 -> sun;
                case 1, 3 -> side;
                default -> sun.multiply(-0.75).add(side.multiply(0.66)).normalized();
            };
            cameraMeters = center.add(offset.multiply(moon.radiusMeters() * (index == 3 ? 1.0002 : 3.5)));
            direction = index == 3 ? offset.multiply(-0.04).add(sun).normalized() : offset.multiply(-1);
        } else {
            scene = PulsarGenerator.landmark(controller.snapshot().galaxySeed(), 0);
            CelestialBody primary = scene.bodies().getFirst();
            float seed = Math.floorMod(primary.id().hashCode() ^ (int) scene.seed(), 1024);
            double period = 1.2 + seed / 1024.0 * 2;
            sceneSeconds = index == 5 ? period * 0.25 : 0;
            SpaceVector offset = new SpaceVector(0, 0, -1);
            if (index == 6) {
                double obliquity = 0.38 + (seed * 0.173 - Math.floor(seed * 0.173)) * 0.56;
                offset = new SpaceVector(Math.sin(obliquity), Math.cos(primary.axialTiltRadians()) * Math.cos(obliquity),
                        Math.sin(primary.axialTiltRadians()) * Math.cos(obliquity));
            }
            cameraMeters = offset.multiply(primary.radiusMeters() * (index == 7 ? 2 : index == 8 ? 1e7 : 80));
            direction = offset.multiply(-1);
        }
        orientation = FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-direction.x(), direction.z())),
                -Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1))), 0);
        renderedPoseFrames = 0;
    }

    private void camera(ViewportEvent.ComputeCameraAngles event) {
        if (scene == null || controller == null || !controller.active()) { return; }
        event.setYaw(orientation.yaw());
        event.setPitch(orientation.pitch());
        event.setRoll(orientation.roll());
    }

    private void render(RenderLevelStageEvent event) {
        var stage = RenderCompatibility.lateWorldPasses()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_SKY;
        if (scene == null || controller == null || !controller.active() || renderFailure != null
                || event.getStage() != stage || RenderCompatibility.shadowPass()) { return; }
        try {
            require(renderer.draw(event, scene, cameraMeters, sceneSeconds, controller.exposure()) > 0,
                    "Production lunar/pulsar extraction returned no bodies");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error during lunar/pulsar draw");
            renderedPoseFrames++;
        } catch (RuntimeException failure) { renderFailure = failure; }
    }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/lunar-pulsar-" + name + ".png");
        Files.createDirectories(path.getParent());
        require(!Files.exists(path), "Refusing to replace original visual evidence " + name);
        minecraft.gui.getChat().clearMessages(false);
        double sum = 0;
        int saturated = 0;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int color = image.getPixelRGBA(x, y);
                    int r = color & 255, g = color >>> 8 & 255, b = color >>> 16 & 255;
                    sum += (r + g + b) / (255.0 * 3);
                    if (r > 250 && g > 250 && b > 250) { saturated++; }
                }
            }
            sum /= (double) image.getWidth() * image.getHeight();
            image.writeToFile(path);
        }
        evidence.append(name).append('\t').append(frames).append('\t').append(sceneSeconds)
                .append('\t').append(sum).append('\t').append(saturated).append('\n');
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after lunar/pulsar capture");
    }

    private void retain() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("lunar-pulsar-metrics.tsv"), evidence.toString());
        Files.writeString(directory.resolve("lunar-pulsar-scope.txt"),
                "Frozen shared production CosmosRenderer, physical Sol Moon/pulsar descriptors, controlled presentation cameras.\n"
                + "No flight navigation or authoritative celestial state is changed by render probes.\n"
                + "Bloom pairs, resource reload and window resize; Iris uses AFTER_LEVEL outside shadow passes.\n"
                + "Framebuffer values are descriptive visual evidence, not a performance or physical accuracy benchmark.\n"
                + RenderCompatibility.status() + "\n");
    }

    private void dispose() {
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(cameraListener);
        NeoForge.EVENT_BUS.unregister(renderListener);
    }
    private boolean ready(int settle) { return ticks >= settle && frames >= startFrame + 6 && minecraft.getOverlay() == null; }
    private void command(String value) { minecraft.player.connection.sendCommand(value); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() { go(step + 1); }
    private void go(int value) { step = value; ticks = 0; startFrame = frames; renderedPoseFrames = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (lunar/pulsar step " + step + ", pose " + pose + ")"); }
    }
}
