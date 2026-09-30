package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.ship.ShipRenderInstance;
import dev.lexawhatt.astraengine.api.ship.ShipVisual;
import dev.lexawhatt.astraengine.api.ship.ShipVisualPart;
import dev.lexawhatt.astraengine.api.ship.ShipVisualPart.Geometry;
import dev.lexawhatt.astraengine.api.ship.ShipVisualPart.Material;
import dev.lexawhatt.astraengine.client.AstraEngineClient;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.ship.CollectShipsEvent;
import dev.lexawhatt.astraengine.client.ship.ShipRenderer;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.RocketService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** A verification-only visual consumer; no construction catalog, machine, ship entity or saved assembly. */
final class ShipVisualScenario {
    private static final SpaceVector ORIGIN = new SpaceVector(0.5, 201, 8.5);
    private static final ShipVisual VISUAL = new ShipVisual(List.of(
            part(Geometry.CYLINDER, Material.PANEL, new SpaceVector(0, 2, 0), new SpaceVector(2.6, 4, 2.6),
                    0, 1, 1, new SpaceVector(0.82, 0.86, 0.9)),
            part(Geometry.FRUSTUM, Material.WINDOW, new SpaceVector(0, 5, 0), new SpaceVector(2.6, 2, 2.6),
                    0, 1, 0.12, new SpaceVector(0.27, 0.6, 0.8)),
            part(Geometry.FRUSTUM, Material.NOZZLE, new SpaceVector(0, -0.7, 0), new SpaceVector(2.2, 1.4, 2.2),
                    0, 1, 0.5, new SpaceVector(0.55, 0.6, 0.66)),
            part(Geometry.BOX, Material.SOLAR, new SpaceVector(-2.3, 2, 0), new SpaceVector(2, 3, 0.16),
                    12, 1, 1, new SpaceVector(0.07, 0.22, 0.7)),
            part(Geometry.BOX, Material.RADIATOR, new SpaceVector(2.3, 2, 0), new SpaceVector(2, 3, 0.16),
                    -12, 1, 1, new SpaceVector(0.5, 0.24, 0.16))),
            List.of(new SpaceVector(0, 6, 0), new SpaceVector(0, -1.4, 0), new SpaceVector(-3.3, 2, 0)));
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Consumer<CollectShipsEvent> collectionListener = this::collect;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };
    private final StringBuilder observations = new StringBuilder("capture,frame,ships,parts,dimension\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ShipRenderer renderer;
    private int step;
    private int ticks;
    private long renderedFrames;
    private long firstFrame;
    private boolean submit = true;
    private boolean tilted;
    private long collections;
    private int[] clearPixels;
    private int[] coveredPixels;
    private SpaceVector flightStart;
    private Vec3 physicalAnchor;

    ShipVisualScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        renderer = AstraEngineClient.shipRenderer();
        NeoForge.EVENT_BUS.addListener(collectionListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 500, "Visual consumer step timed out");
            if (minecraft.level.dimension().equals(Level.OVERWORLD) && minecraft.screen == null) {
                minecraft.player.setYRot(0);
                minecraft.player.setXRot(-5);
            }
            switch (step) {
                case 0 -> {
                    minecraft.player.connection.sendCommand("astra-render environment off");
                    server(this::prepare);
                    next();
                }
                case 1 -> {
                    if (!ready(40) || !renderer.ready()) { return false; }
                    require(collections > 0 && renderer.worldShipCount() == 1 && renderer.worldPartCount() == VISUAL.parts().size(),
                            "Engine did not collect the consumer world visual with environment off");
                    shot("01-world-consumer");
                    minecraft.setScreen(new PreviewScreen(renderer, VISUAL));
                    next();
                }
                case 2 -> {
                    if (!ready(20)) { return false; }
                    PreviewScreen preview = (PreviewScreen) minecraft.screen;
                    Set<Integer> hits = preview.verifyPicking();
                    require(hits.contains(0) && hits.contains(1) && hits.contains(2) && hits.size() >= 4,
                            "Preview picking did not resolve consumer primitives: " + hits);
                    shot("02-preview-primitives-and-markers");
                    pending = minecraft.reloadResourcePacks();
                    next();
                }
                case 3 -> {
                    if (!ready(30) || !renderer.ready()) { return false; }
                    require(((PreviewScreen) minecraft.screen).verifyPicking().size() >= 4,
                            "Preview selection failed after resource reload");
                    shot("03-preview-resource-reload");
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                    next();
                }
                case 4 -> {
                    if (!ready(25) || minecraft.getWindow().getWidth() != 960) { return false; }
                    require(((PreviewScreen) minecraft.screen).verifyPicking().size() >= 4,
                            "Preview selection failed after resize");
                    shot("04-preview-resized");
                    minecraft.screen.onClose();
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                    submit = false;
                    next();
                }
                case 5 -> {
                    if (!ready(30) || minecraft.getWindow().getWidth() != 1280) { return false; }
                    require(renderer.worldShipCount() == 0, "An unsubmitted visual survived its collection frame");
                    clearPixels = shot("05-world-no-consumer");
                    submit = true;
                    next();
                }
                case 6 -> {
                    if (!ready(20)) { return false; }
                    int[] visualPixels = shot("06-world-consumer-depth");
                    double difference = difference(clearPixels, visualPixels, 0.35, 0.30, 0.65, 0.72);
                    require(difference > 0.015, "World visual changed too few pixels: " + difference);
                    AstraEngine.LOGGER.info("ASTRA_SHIP_VISUAL_DIFFERENCE visibleMean={}", difference);
                    server(server -> wall(server, true));
                    next();
                }
                case 7 -> {
                    if (!ready(30)) { return false; }
                    coveredPixels = shot("07-opaque-wall-in-front");
                    submit = false;
                    next();
                }
                case 8 -> {
                    if (!ready(20)) { return false; }
                    int[] withoutVisual = shot("08-opaque-wall-no-consumer");
                    double coveredDifference = difference(coveredPixels, withoutVisual, 0.48, 0.34, 0.52, 0.64);
                    require(coveredDifference < 0.015, "Ship ignored opaque scene depth: " + coveredDifference);
                    AstraEngine.LOGGER.info("ASTRA_SHIP_VISUAL_OCCLUSION coveredMean={}", coveredDifference);
                    submit = true;
                    tilted = true;
                    server(server -> wall(server, false));
                    next();
                }
                case 9 -> {
                    if (!ready(30)) { return false; }
                    require(renderer.worldShipCount() == 1, "Arbitrary assembly pose was not rendered");
                    shot("09-world-pitch-roll");
                    server(server -> server.tickRateManager().setFrozen(false));
                    tap(GLFW.GLFW_KEY_M);
                    next();
                }
                case 10 -> {
                    if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(10)) { return false; }
                    controller = map.controller();
                    require(controller.snapshot() != null, "Map has no authoritative flight snapshot");
                    map.onClose();
                    tap(GLFW.GLFW_KEY_R);
                    next();
                }
                case 11 -> {
                    if (!controller.active() || !ready(45)) { return false; }
                    require(minecraft.level.dimension().equals(RocketService.FLIGHT), "R did not enter its bounded flight world");
                    require(renderer.worldShipCount() == 1, "Engine did not collect ships in the flight void");
                    shot("10-flight-void-consumer");
                    flightStart = controller.snapshot().position();
                    physicalAnchor = minecraft.player.position();
                    hold(GLFW.GLFW_KEY_W, true);
                    next();
                }
                case 12 -> {
                    if (!ready(30)) { return false; }
                    hold(GLFW.GLFW_KEY_W, false);
                    require(controller.snapshot().position().distance(flightStart) > 20,
                            "Neutral rendering broke free camera W input");
                    require(minecraft.player.position().distanceTo(physicalAnchor) < 0.1,
                            "Virtual inspection moved the physical flight anchor");
                    shot("11-free-camera-still-moving");
                    tap(GLFW.GLFW_KEY_R);
                    next();
                }
                case 13 -> {
                    if (controller.active() || !minecraft.level.dimension().equals(Level.OVERWORLD) || !ready(40)) {
                        return false;
                    }
                    require(minecraft.player.position().distanceTo(new Vec3(0.5, 200, -9.5)) < 0.2,
                            "Free camera did not return to its original physical location");
                    shot("12-returned-to-world");
                    close();
                    return true;
                }
                default -> throw new IllegalStateException("Unexpected ship visual step " + step);
            }
            return false;
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void collect(CollectShipsEvent event) {
        collections++;
        if (!submit) { return; }
        if (event.level().dimension().equals(Level.OVERWORLD)) {
            FlightOrientation pose = tilted ? FlightOrientation.fromAngles(22, 18, 25) : FlightOrientation.IDENTITY;
            event.collector().add(new ShipRenderInstance(VISUAL, ORIGIN, pose, -1));
        } else if (event.level().dimension().equals(RocketService.FLIGHT) && controller != null && controller.active()) {
            // This fake consumer supplies a camera-relative display pose in the real staging world.
            // It deliberately owns no vessel motion or persistence; the flight service still owns navigation.
            FlightOrientation pose = controller.orientation();
            SpaceVector origin = event.camera().add(pose.forward().multiply(18)).subtract(pose.up().multiply(2));
            event.collector().add(new ShipRenderInstance(VISUAL, origin, pose, -1));
        }
    }

    private void prepare(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(100000, 0, false, false);
        for (int x = -8; x <= 8; x++) {
            for (int z = -12; z <= 16; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, -9.5);
        server.tickRateManager().setFrozen(true);
    }

    private void wall(MinecraftServer server, boolean present) {
        for (int x = -1; x <= 1; x++) {
            for (int y = 200; y <= 209; y++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, y, 4),
                        present ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private int[] shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        int[] pixels;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            pixels = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) { pixels[y * image.getWidth() + x] = image.getPixelRGBA(x, y); }
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        observations.append(name).append(',').append(renderedFrames).append(',').append(renderer.worldShipCount())
                .append(',').append(renderer.worldPartCount()).append(',').append(minecraft.level.dimension().location()).append('\n');
        AstraEngine.LOGGER.info("ASTRA_SHIP_VISUAL_CAPTURE {} ships={} parts={} frame={}",
                name, renderer.worldShipCount(), renderer.worldPartCount(), renderedFrames);
        return pixels;
    }

    private double difference(int[] first, int[] second, double left, double top, double right, double bottom) {
        require(first.length == second.length, "Framebuffer dimensions changed during a pixel comparison");
        int width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
        double sum = 0;
        int count = 0;
        for (int y = (int) (height * top); y < height * bottom; y++) {
            for (int x = (int) (width * left); x < width * right; x++) {
                int a = first[y * width + x], b = second[y * width + x];
                sum += (Math.abs((a & 255) - (b & 255)) + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255))
                        + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255))) / 765.0;
                count++;
            }
        }
        return sum / count;
    }

    private void close() throws Exception {
        hold(GLFW.GLFW_KEY_W, false);
        NeoForge.EVENT_BUS.unregister(collectionListener);
        NeoForge.EVENT_BUS.unregister(frameListener);
        renderer.releasePreview();
        Files.writeString(minecraft.gameDirectory.toPath().resolve("ship-visual-observations.csv"), observations.toString());
    }

    private boolean ready(int settle) { return ticks >= settle && renderedFrames >= firstFrame + 8; }
    private void next() { step++; ticks = 0; firstFrame = renderedFrames; }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (ship visual step " + step + ", ticks=" + ticks + ")"); }
    }
    private static ShipVisualPart part(Geometry geometry, Material material, SpaceVector position, SpaceVector size,
                                      double yaw, double bottom, double top, SpaceVector color) {
        return new ShipVisualPart(geometry, material, position, size, yaw, bottom, top, color);
    }

    /** Minimal verification consumer UI; the engine supplies only preview rendering and picking. */
    static final class PreviewScreen extends Screen {
        private final ShipRenderer renderer;
        private final ShipVisual visual;
        private int selected = -1;

        PreviewScreen(ShipRenderer renderer, ShipVisual visual) {
            super(Component.literal("Ship visual API verification"));
            this.renderer = renderer;
            this.visual = visual;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xff09111a);
            graphics.drawString(font, title, 20, 14, 0xffc5e8ff, false);
            renderer.renderPreview(graphics, 20, 36, width - 40, height - 58, visual, selected, 35, 12, 1);
            graphics.drawString(font, "Consumer-owned screen / analytic parts and markers", 20, height - 15, 0xffa3bdcc, false);
        }

        Set<Integer> verifyPicking() {
            Set<Integer> hits = new HashSet<>();
            for (int y = 40; y < height - 24; y += 5) {
                for (int x = 24; x < width - 24; x += 5) {
                    int hit = renderer.pickPart(20, 36, width - 40, height - 58, visual, x, y, 35, 12, 1);
                    if (hit >= 0) { hits.add(hit); }
                }
            }
            if (hits.contains(0)) { selected = 0; }
            if (renderer.pickPart(20, 36, width - 40, height - 58, visual, 0, 0, 35, 12, 1) != -1) {
                throw new IllegalStateException("Preview selected a part outside its viewport");
            }
            return hits;
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public void onClose() { renderer.releasePreview(); super.onClose(); }
    }
}
