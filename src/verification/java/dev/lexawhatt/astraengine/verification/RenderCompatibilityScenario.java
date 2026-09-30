package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.lighting.CollectSceneLightsEvent;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.RenderOptions;
import dev.lexawhatt.astraengine.client.ship.CollectShipsEvent;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.server.RocketService;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Real optional-mod integration; the fixture never substitutes a fake shader pack or renderer. */
final class RenderCompatibilityScenario {
    private final Minecraft minecraft = Minecraft.getInstance();
    private long renderedFrames;
    private long stageEvents;
    private long shadowStageEvents;
    private long shipCollections;
    private long lightCollections;
    private long shadowShipCollections;
    private long shadowLightCollections;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { renderedFrames++; }
    };
    private final Consumer<RenderLevelStageEvent> stageListener = event -> {
        stageEvents++;
        if (IrisApi.getInstance().isRenderingShadowPass()) { shadowStageEvents++; }
    };
    private final Consumer<CollectShipsEvent> shipsListener = event -> {
        shipCollections++;
        if (IrisApi.getInstance().isRenderingShadowPass()) { shadowShipCollections++; }
    };
    private final Consumer<CollectSceneLightsEvent> lightsListener = event -> {
        lightCollections++;
        if (IrisApi.getInstance().isRenderingShadowPass()) { shadowLightCollections++; }
    };
    private final StringBuilder observations = new StringBuilder(
            "capture,frame,pack_active,environment,astra_hdr,stage_events,shadow_stage_events,ship_collections,"
                    + "light_collections,shadow_ship_collections,shadow_light_collections,lower_mean,lower_variance\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private ShipVisualScenario visualScenario;
    private int step;
    private int ticks;
    private long firstFrame;
    private long flightLateFrames;
    private long lastFlightFrame = -1;
    private boolean closed;

    RenderCompatibilityScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, stageListener);
        NeoForge.EVENT_BUS.addListener(shipsListener);
        NeoForge.EVENT_BUS.addListener(lightsListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            require(shadowShipCollections == 0 && shadowLightCollections == 0,
                    "Astra consumer collection ran inside an Iris shadow pass");
            if (!pending.isDone()) { return false; }
            pending.join();
            if (visualScenario != null) {
                require(IrisApi.getInstance().isShaderPackInUse(), "Shader pack stopped during visual integration");
                boolean visualComplete = visualScenario.tick();
                observeFlightComposition();
                if (!visualComplete) { return false; }
                require(shipCollections > 0, "No actual consumer collection occurred during compatibility verification");
                require(flightLateFrames >= 8,
                        "Flight never demonstrated live late Cosmos composition on eight presented frames");
                Files.writeString(minecraft.gameDirectory.toPath().resolve("render-compatibility-status.txt"),
                        RenderCompatibility.status() + "\n" + "Observed native shadow stage events: " + shadowStageEvents
                                + "\nShadow ship collections: " + shadowShipCollections
                                + "\nShadow light collections: " + shadowLightCollections
                                + "\nPresented flight frames with live late Cosmos targets: " + flightLateFrames
                                + "\nShadow event count zero means this pack did not expose shadow stages to NeoForge.\n");
                close();
                return true;
            }
            require(++ticks < 1600, "Compatibility step timed out");
            if (minecraft.screen == null) {
                minecraft.player.setYRot(0);
                minecraft.player.setXRot(6);
            }
            switch (step) {
                case 0 -> {
                    assertPack(true);
                    minecraft.player.connection.sendCommand("astra-render environment auto");
                    minecraft.player.connection.sendCommand("astra-render quality low");
                    server(this::prepare);
                    next();
                }
                case 1 -> {
                    if (!ready(40)) { return false; }
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-01-active-pack-overworld");
                    IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
                    next();
                }
                case 2 -> {
                    if (!ready(40)) { return false; }
                    assertPack(false);
                    assertOverworldOwnership(false);
                    shot("compat-02-pack-off-astra-sky");
                    IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true);
                    next();
                }
                case 3 -> {
                    if (!ready(40)) { return false; }
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-03-pack-restored");
                    pending = minecraft.reloadResourcePacks();
                    next();
                }
                case 4 -> {
                    if (!ready(30)) { return false; }
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-04-active-pack-reload");
                    minecraft.player.connection.sendCommand("astra-render environment space");
                    next();
                }
                case 5 -> {
                    if (!ready(30)) { return false; }
                    assertEnvironment("space");
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-05-forced-space-pack-sky");
                    minecraft.player.connection.sendCommand("astra-render environment planet");
                    next();
                }
                case 6 -> {
                    if (!ready(30)) { return false; }
                    assertEnvironment("planet");
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-06-forced-planet-pack-sky");
                    minecraft.player.connection.sendCommand("astra-render environment auto");
                    next();
                }
                case 7 -> {
                    if (!ready(30)) { return false; }
                    assertEnvironment("auto");
                    assertPack(true);
                    assertOverworldOwnership(true);
                    shot("compat-07-restored-auto-pack-sky");
                    // Reuse the real visual consumer, its preview/reload/resize and opaque-depth comparisons,
                    // then M/R/W/R navigation through the bounded flight world with the pack still active.
                    visualScenario = new ShipVisualScenario();
                }
                default -> throw new IllegalStateException("Unexpected compatibility fixture step");
            }
            return false;
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void assertPack(boolean active) {
        var diagnostics = RenderCompatibility.diagnostics();
        require(diagnostics.irisState() == RenderCompatibility.IrisState.AVAILABLE
                        && !diagnostics.conservativeMode(),
                "Public Iris adapter is unavailable: " + RenderCompatibility.status());
        require(IrisApi.getInstance().isShaderPackInUse() == active
                        && diagnostics.shaderPackInUse().orElseThrow() == active
                        && RenderCompatibility.shaderPackActive() == active,
                "Public API and adapter disagree after shader toggle");
        require(!IrisApi.getInstance().isRenderingShadowPass() && !RenderCompatibility.shadowPass(),
                "Shadow state escaped its render pass into the client tick");
    }

    private void assertOverworldOwnership(boolean pack) throws ReflectiveOperationException {
        require(minecraft.level.effects() instanceof AstralOverworldEffects,
                "Expected the registered Astra Overworld effects");
        var effects = (AstralOverworldEffects) minecraft.level.effects();
        var camera = minecraft.gameRenderer.getMainCamera();
        var identity = new Matrix4f();
        require(hdrAllocated() != pack, "Actual presented frames did not transfer Astra HDR sky ownership");
        if (pack) {
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            require(!effects.renderSky(minecraft.level, 0, 0, identity, camera, identity, false,
                            () -> { throw new IllegalStateException("Astra sky set up fog inside a pack-owned frame"); }),
                    "Astra sky hook did not yield to the active shader pack");
            require(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == draw
                            && GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == read,
                    "Yielding the sky changed host framebuffer bindings");
        }
        require(effects.renderClouds(minecraft.level, 0, 0, new PoseStack(), camera.getPosition().x,
                        camera.getPosition().y, camera.getPosition().z, identity, identity) != pack,
                "Cloud ownership did not follow the live shader-pack state");
        Vector3f colors = new Vector3f(0.6f, 0.7f, 0.8f);
        effects.adjustLightmapColors(minecraft.level, 0, 0.8f, 0, 0, 0, 0, new Vector3f(colors));
        effects.adjustLightmapColors(minecraft.level, 0, 0.8f, 0, 0.7f, 0, 15, colors);
        double difference = colors.distance(new Vector3f(0.6f, 0.7f, 0.8f));
        require(pack ? difference == 0 : difference > 0.0001,
                "Seasonal lightmap ownership did not follow the live shader-pack state");
    }

    private boolean hdrAllocated() throws ReflectiveOperationException {
        // Read only existing production ownership, rather than adding a shipped test counter or GPU owner.
        Object renderer = field(minecraft.level.effects(), "renderer");
        return field(field(renderer, "bloom"), "scene") != null;
    }

    private void assertEnvironment(String expected) throws ReflectiveOperationException {
        require(environment().equals(expected), "Environment command did not apply: " + expected);
    }

    private String environment() throws ReflectiveOperationException {
        return ((RenderOptions) field(minecraft.level.effects(), "options")).environment();
    }

    private void observeFlightComposition() throws ReflectiveOperationException {
        if (!minecraft.level.dimension().equals(RocketService.FLIGHT) || renderedFrames == lastFlightFrame) {
            return;
        }
        RocketController controller = (RocketController) field(visualScenario, "controller");
        if (controller == null || !controller.active()) { return; }
        CosmosRenderer renderer = (CosmosRenderer) field(controller, "renderer");
        Object lateSky = field(renderer, "lateSky");
        RenderTarget scene = (RenderTarget) field(lateSky, "scene");
        RenderTarget sky = (RenderTarget) field(lateSky, "sky");
        if (scene == null || sky == null) { return; }
        RenderTarget main = minecraft.getMainRenderTarget();
        require(scene.width == main.width && scene.height == main.height
                        && sky.width == main.width && sky.height == main.height
                        && GL11.glIsTexture(scene.getColorTextureId()) && GL11.glIsTexture(sky.getColorTextureId())
                        && renderer.bodyCount() > 0,
                "Flight late Cosmos composition has stale targets or no extracted bodies");
        lastFlightFrame = renderedFrames;
        flightLateFrames++;
        if (flightLateFrames == 8) {
            AstraEngine.LOGGER.info("ASTRA_RENDER_COMPAT_COSMOS_LATE frames={} viewport={}x{} bodies={}",
                    flightLateFrames, main.width, main.height, renderer.bodyCount());
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void prepare(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(100000, 0, false, false);
        for (int x = -12; x <= 12; x++) {
            for (int z = -16; z <= 22; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z),
                        ((x + z) % 6 == 0 ? Blocks.STONE_BRICKS : Blocks.GRASS_BLOCK).defaultBlockState());
            }
        }
        for (int x : new int[] {-7, 7}) {
            for (int y = 200; y <= 205; y++) {
                level.setBlockAndUpdate(new BlockPos(x, y, 10), Blocks.OAK_LOG.defaultBlockState());
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(x + dx, 206, 10 + dz), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, -9.5);
        server.tickRateManager().setFrozen(true);
    }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        double sum = 0;
        double squares = 0;
        int samples = 0;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            for (int y = image.getHeight() * 2 / 3; y < image.getHeight(); y += 4) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x += 4) {
                    int pixel = image.getPixelRGBA(x, y);
                    double value = ((pixel & 255) + (pixel >>> 8 & 255) + (pixel >>> 16 & 255)) / 765.0;
                    sum += value;
                    squares += value * value;
                    samples++;
                }
            }
        }
        double mean = sum / samples;
        double variance = squares / samples - mean * mean;
        require(mean > 0.005 && mean < 0.995 && variance > 0.00001,
                "Native terrain frame is blank or clipped: mean=" + mean + ", variance=" + variance);
        finiteFramebuffer();
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        observations.append(name).append(',').append(renderedFrames).append(',')
                .append(IrisApi.getInstance().isShaderPackInUse()).append(',').append(environment()).append(',')
                .append(hdrAllocated()).append(',')
                .append(stageEvents).append(',').append(shadowStageEvents).append(',').append(shipCollections).append(',')
                .append(lightCollections).append(',').append(shadowShipCollections).append(',')
                .append(shadowLightCollections).append(',').append(mean).append(',').append(variance).append('\n');
        Files.writeString(minecraft.gameDirectory.toPath().resolve("render-compatibility-observations.csv"),
                observations.toString());
        AstraEngine.LOGGER.info("ASTRA_RENDER_COMPAT_CAPTURE {} frame={} pack={} shadowStages={} terrainMean={}",
                name, renderedFrames, IrisApi.getInstance().isShaderPackInUse(), shadowStageEvents, mean);
    }

    private void finiteFramebuffer() {
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, minecraft.getMainRenderTarget().frameBufferId);
            FloatBuffer pixel = stack.mallocFloat(4);
            int width = minecraft.getMainRenderTarget().width;
            int height = minecraft.getMainRenderTarget().height;
            for (int y = 1; y <= 3; y++) {
                for (int x = 1; x <= 3; x++) {
                    pixel.clear();
                    GL11.glReadPixels(width * x / 4, height * y / 4, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                    for (int i = 0; i < 4; i++) {
                        require(Float.isFinite(pixel.get(i)), "Nonfinite presented framebuffer color");
                    }
                    pixel.clear();
                    GL11.glReadPixels(width * x / 4, height * y / 4, 1, 1,
                            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
                    require(Float.isFinite(pixel.get(0)) && pixel.get(0) >= 0 && pixel.get(0) <= 1,
                            "Invalid presented framebuffer depth");
                }
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
        }
    }

    private boolean ready(int settleTicks) {
        return ticks >= settleTicks && renderedFrames >= firstFrame + 20;
    }

    private void next() { step++; ticks = 0; firstFrame = renderedFrames; }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private void close() {
        if (closed) { return; }
        closed = true;
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(stageListener);
        NeoForge.EVENT_BUS.unregister(shipsListener);
        NeoForge.EVENT_BUS.unregister(lightsListener);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message + " (render compatibility step " + step + ", ticks=" + ticks + ")");
        }
    }
}
