package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.ChartViewport;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Exercises map input through actual Screen events and Moon navigation through authoritative snapshots. */
final class CelestialPolishScenario {
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final StringBuilder evidence = new StringBuilder("check\tframe\tdetail\n");
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private Point reference;
    private SpaceVector routeStart;
    private SpaceVector lastRoutePosition;
    private long routeEpoch;
    private long renderedFrames;
    private long firstFrame;
    private int routeSnapshots;
    private int routeDuration;
    private int step;
    private int ticks;
    private int selectedPage;
    private boolean routeCaptured;
    private SupernovaPolishScenario supernova;

    CelestialPolishScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.guiScale().set(2);
        minecraft.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            if (supernova != null) { return supernova.tick(); }
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 1800, "Native map stage timed out");
            switch (step) {
                case 0 -> {
                    server(this::prepareHome);
                    command("astra-render quality balanced");
                    command("astra-render bloom true");
                    command("astra-render exposure 1");
                    tap(GLFW.GLFW_KEY_M);
                    next();
                }
                case 1 -> {
                    if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(12)) { return false; }
                    controller = map.controller();
                    require(controller.currentSystem().id().equals("sol"), "Fresh pilot did not start in Sol");
                    require(controller.currentSystem().bodies().get(9).id().equals("moon")
                            && body("moon").parentId().equals("earth"), "Moon is missing its stable Sol index/parent");
                    require(controller.currentSystem().bodies().size() == 30
                                    && controller.currentSystem().bodies().stream().filter(body -> !body.parentId().isEmpty()).count() == 21,
                            "Native catalog is missing the representative planetary satellites");
                    require(!map.mouseClicked(5, 5, 2) && !map.mouseDragged(40, 40, 2, 35, 35),
                            "Middle click outside canvas captured chart input");
                    require(!controller.active(), "Middle click outside canvas activated navigation");
                    shot("map-01-overview");
                    selectBody("moon");
                    selectedPage = (int) read(map, "page");
                    require(selectedPage > 0, "Moon was not reached through paged body widgets");
                    focus();
                    next();
                }
                case 2 -> {
                    if (!ready(8)) { return false; }
                    verifyPair();
                    shot("map-02-moon-focused-parent-orbit");
                    reference = point("moon");
                    require(map().mouseClicked(reference.x(), reference.y(), 2), "Map did not capture middle button");
                    require(map().mouseDragged(reference.x() + 30, reference.y() + 16, 2, 30, 16),
                            "Map did not handle middle drag");
                    require(map().mouseReleased(reference.x() + 30, reference.y() + 16, 2),
                            "Map did not release middle drag");
                    next();
                }
                case 3 -> {
                    if (!ready(6)) { return false; }
                    Point moved = point("moon");
                    require(Math.abs(moved.x() - reference.x() - 30) <= 2
                            && Math.abs(moved.y() - reference.y() - 16) <= 2, "MMB did not translate chart by pointer delta");
                    note("middle-drag", reference + " -> " + moved);
                    reference = moved;
                    require(map().mouseScrolled(reference.x(), reference.y(), 0, 0.75), "Map rejected wheel over chart");
                    next();
                }
                case 4 -> {
                    if (!ready(6)) { return false; }
                    require(point("moon").distance(reference) <= 2, "Wheel zoom moved the object under the cursor");
                    shot("map-03-dragged-anchored-zoom");
                    require(map().mouseClicked(reference.x(), reference.y(), 2), "Second middle capture failed");
                    require(map().mouseDragged(reference.x() - 12, reference.y() - 8, 2, -12, -8), "Second drag failed");
                    require(map().mouseReleased(-40, -40, 2), "Middle release outside chart was not consumed");
                    next();
                }
                case 5 -> {
                    if (!ready(6)) { return false; }
                    reference = point("moon");
                    require(!map().mouseDragged(reference.x() + 40, reference.y() + 20, 2, 40, 20),
                            "Middle capture leaked after release outside");
                    next();
                }
                case 6 -> {
                    if (!ready(6)) { return false; }
                    require(point("moon").distance(reference) <= 1, "Chart moved after released middle drag");
                    Point earth = point("earth");
                    require(map().mouseClicked(earth.x(), earth.y(), 0), "Earth marker click failed after pan");
                    map().mouseReleased(earth.x(), earth.y(), 0);
                    require(read(map(), "selection").equals("earth"), "Panned chart selected the wrong marker");
                    focus();
                    next();
                }
                case 7 -> {
                    if (!ready(6)) { return false; }
                    verifyPair();
                    shot("map-04-earth-focused-moon-visible");
                    Point moon = point("moon");
                    require(map().mouseScrolled(moon.x(), moon.y(), 0, 1000), "Extreme chart wheel was rejected");
                    require(map().mouseClicked(moon.x(), moon.y(), 2), "Extreme chart pan did not capture");
                    require(map().mouseDragged(1_000_000, 1_000_000, 2, 1_000_000, 1_000_000), "Extreme pan failed");
                    map().mouseReleased(-40, -40, 2);
                    moveTo(25);
                }
                case 8 -> {
                    if (!ready(6)) { return false; }
                    require(Math.abs(view().zoom() - 1) < 1e-9, "Home did not restore chart zoom");
                    require(points().stream().anyMatch(value -> value.id().equals("neptune")), "Home did not restore outer planets");
                    shot("map-05-home-reset");
                    selectBody("moon"); focus();
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 640);
                    next();
                }
                case 9 -> {
                    if (!ready(12) || minecraft.getWindow().getWidth() != 960 || minecraft.getWindow().getHeight() != 640) {
                        return false;
                    }
                    require(read(map(), "selection").equals("moon"), "Resize lost selected Moon");
                    focus();
                    next();
                }
                case 10 -> {
                    if (!ready(6)) { return false; }
                    verifyPair();
                    shot("map-06-resized");
                    reference = point("moon");
                    pending = minecraft.reloadResourcePacks();
                    next();
                }
                case 11 -> {
                    if (!ready(12)) { return false; }
                    require(read(map(), "selection").equals("moon") && point("moon").distance(reference) <= 2,
                            "Resource reload moved or lost the selected Moon");
                    shot("map-07-reloaded");
                    GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                    next();
                }
                case 12 -> {
                    if (!ready(12) || minecraft.getWindow().getWidth() != 1280 || minecraft.getWindow().getHeight() != 720) {
                        return false;
                    }
                    click("target");
                    tap(GLFW.GLFW_KEY_M);
                    next();
                }
                case 13 -> {
                    if (!(minecraft.screen instanceof CosmosMapScreen) || !ready(8)) { return false; }
                    require(read(map(), "selection").equals("moon") && controller.targetBody().equals("moon"),
                            "Map reopening lost tracked Moon identity");
                    focus();
                    next();
                }
                case 14 -> {
                    if (!ready(6)) { return false; }
                    verifyPair();
                    shot("map-08-reopened-tracked-moon");
                    selectBody("europa"); focus();
                    moveTo(26);
                }
                case 15 -> {
                    if (!ready(6)) { return false; }
                    reference = point("sol");
                    require(map().mouseClicked(reference.x(), reference.y(), 2), "Known-system chart did not capture MMB");
                    require(map().mouseDragged(reference.x() + 19, reference.y() + 11, 2, 19, 11),
                            "Known-system chart did not pan");
                    map().mouseReleased(reference.x() + 19, reference.y() + 11, 2);
                    next();
                }
                case 16 -> {
                    if (!ready(6)) { return false; }
                    Point moved = point("sol");
                    require(Math.abs(moved.x() - reference.x() - 19) <= 2 && Math.abs(moved.y() - reference.y() - 11) <= 2,
                            "Known-system marker did not follow middle drag");
                    reference = moved;
                    require(map().mouseScrolled(reference.x(), reference.y(), 0, 1), "Known-system zoom failed");
                    next();
                }
                case 17 -> {
                    if (!ready(6)) { return false; }
                    require(point("sol").distance(reference) <= 2, "Known-system zoom lost cursor anchor");
                    shot("map-09-known-systems-pan-zoom");
                    click("local"); selectBody("moon"); click("enter");
                    next();
                }
                case 18 -> {
                    if (!controller.active() || !ready(25)) { return false; }
                    require(controller.snapshot().systemId().equals("sol"), "Entering free camera changed system");
                    routeStart = controller.snapshot().position();
                    routeEpoch = controller.snapshot().navigationEpoch();
                    shot("moon-10-before-approach");
                    tap(GLFW.GLFW_KEY_M);
                    next();
                }
                case 19 -> {
                    if (!(minecraft.screen instanceof CosmosMapScreen) || !ready(6)) { return false; }
                    selectBody("moon"); click("approach");
                    next();
                }
                case 20 -> {
                    if (!controller.snapshot().approaching()) {
                        require(ticks < 100, "Moon map action did not start real server approach"); return false;
                    }
                    require(controller.snapshot().navigationEpoch() != routeEpoch, "Moon approach did not acquire navigation epoch");
                    routeDuration = controller.snapshot().jumpTicks();
                    require(routeDuration > 80, "Moon approach became an interstellar jump or teleport");
                    next();
                }
                case 21 -> {
                    var state = controller.snapshot();
                    require(state.systemId().equals("sol") && !state.interstellarJump(), "Moon approach left its parent system");
                    if (state.approaching()) {
                        if (!state.position().equals(lastRoutePosition)) { routeSnapshots++; lastRoutePosition = state.position(); }
                        if (!routeCaptured && state.jumpTicks() < routeDuration / 2) {
                            shot("moon-11-continuous-approach"); routeCaptured = true;
                        }
                        return false;
                    }
                    require(routeCaptured && routeSnapshots > 20 && state.position().distance(routeStart) > 100_000_000,
                            "Moon route lacks actual intermediate server movement");
                    next();
                }
                case 22 -> {
                    if (!ready(25)) { return false; }
                    CelestialBody moon = body("moon");
                    double seconds = controller.snapshot().clockTicks() / 20.0;
                    SpaceVector moonPosition = controller.currentSystem().positionAt(moon, seconds);
                    double radii = moonPosition.distance(controller.snapshot().position()) / moon.radiusMeters();
                    require(Math.abs(radii - 4) < 0.2, "Approach arrived at Moon's parent-relative offset instead of system position: " + radii);
                    SpaceVector direction = controller.currentSystem().positionAt(moon, controller.timeSeconds())
                            .subtract(controller.visualPosition()).normalized();
                    var look = minecraft.gameRenderer.getMainCamera().getLookVector();
                    require(direction.dot(new SpaceVector(look.x, look.y, look.z)) > 0.995, "Actual camera missed resolved Moon center");
                    note("moon-server-arrival", "radii=" + radii + ", intermediateSnapshots=" + routeSnapshots);
                    shot("moon-12-physical-arrival");
                    tap(GLFW.GLFW_KEY_R);
                    next();
                }
                case 23 -> {
                    if (!minecraft.level.dimension().equals(Level.OVERWORLD) || !ready(15)) { return false; }
                    server(server -> require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                            "Navigation damaged the original home marker"));
                    next();
                }
                case 24 -> {
                    retain();
                    NeoForge.EVENT_BUS.unregister(frameListener);
                    AstraEngine.LOGGER.info("ASTRA_MAP_MOON_PASSED pages={} snapshots={} frames={}",
                            selectedPage + 1, routeSnapshots, renderedFrames);
                    supernova = new SupernovaPolishScenario(controller);
                }
                case 25 -> {
                    if (!ready(6)) { return false; }
                    require(view().zoom() <= ChartViewport.MAX_ZOOM && Double.isFinite(view().centerX())
                            && Double.isFinite(view().centerZ()), "Extreme chart interaction produced unbounded view state");
                    int right = (int) read(map(), "canvasRight");
                    require(points().stream().allMatch(point -> point.x() >= 14 && point.x() < right
                                    && point.y() >= 65 && point.y() < map().height - 71),
                            "Far-panned chart retained off-canvas picking markers");
                    shot("map-extreme-pan-clipped");
                    require(map().keyPressed(GLFW.GLFW_KEY_HOME, 0, 0), "Home was not handled by the chart");
                    moveTo(8);
                }
                case 26 -> {
                    if (!ready(6)) { return false; }
                    require(body("europa").parentId().equals("jupiter")
                            && point("europa").distance(point("jupiter")) > 16,
                            "Focused Europa is not framed with its parent Jupiter");
                    shot("map-satellites-europa-jupiter");
                    selectBody("titan"); focus();
                    next();
                }
                case 27 -> {
                    if (!ready(6)) { return false; }
                    require(body("titan").parentId().equals("saturn")
                            && point("titan").distance(point("saturn")) > 16,
                            "Focused Titan is not framed with its parent Saturn");
                    shot("map-satellites-titan-saturn");
                    click("nearby"); focus();
                    moveTo(15);
                }
                default -> throw new IllegalStateException("Unexpected map polish step " + step);
            }
            return false;
        } catch (Exception failure) {
            note("failure", failure.toString());
            retain();
            NeoForge.EVENT_BUS.unregister(frameListener);
            throw failure;
        }
    }

    private void verifyPair() throws Exception {
        Point moon = point("moon"), earth = point("earth");
        require(moon.distance(earth) > 16, "Earth/Moon focus left indistinguishable map markers");
        note("earth-moon-frame", earth + " / " + moon);
    }

    private void focus() { require(map().keyPressed(GLFW.GLFW_KEY_F, 0, 0), "F was not handled by the map"); }
    private CosmosMapScreen map() { return (CosmosMapScreen) minecraft.screen; }
    private ChartViewport view() throws ReflectiveOperationException { return (ChartViewport) read(map(), "localView"); }
    private CelestialBody body(String id) {
        return controller.currentSystem().bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private List<Point> points() throws Exception {
        List<Point> points = new ArrayList<>();
        for (Object marker : (List<?>) read(map(), "markers")) {
            points.add(new Point((String) read(marker, "id"), ((Number) read(marker, "x")).doubleValue(),
                    ((Number) read(marker, "y")).doubleValue()));
        }
        return points;
    }

    private Point point(String id) throws Exception {
        return points().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Rendered map marker is missing: " + id + " at step " + step));
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void selectBody(String id) {
        String name = body(id).name();
        for (int page = 0; page < 12; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(name) || button.getMessage().getString().equals("> " + name))
                    .findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            Button forward = button(">");
            if (forward.active) { clickWidget(forward); }
            else {
                Button back = button("<");
                while (back.active) { clickWidget(back); back = button("<"); }
            }
        }
        throw new IllegalStateException("Map pages did not expose " + id);
    }

    private Button button(String label) {
        return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    private void click(String key) { clickWidget(button(Component.translatable("astraengine.map." + key).getString())); }
    private void clickWidget(Button button) {
        require(button.active && button.visible, "Map button unavailable: " + button.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Map did not handle widget click");
        screen.mouseReleased(x, y, 0);
    }

    private void prepareHome(MinecraftServer server) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        minecraft.gui.getChat().clearMessages(false);
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        note("capture", name + ", graphics=" + minecraft.options.graphicsMode().get());
        AstraEngine.LOGGER.info("ASTRA_POLISH_CAPTURE {}", name);
    }

    private void retain() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("map-moon-checks.tsv"), evidence.toString());
        Files.writeString(directory.resolve("map-moon-scope.txt"),
                "Actual Screen mouse/key handlers and native rendered map markers; reflection reads private state only.\n"
                + "MMB and wheel use Screen dispatch, not physical OS input injection. No production test counters.\n"
                + "Moon navigation uses the actual map action, server route/snapshots, and untouched renderer/camera.\n"
                + "Disposable world; original home block retained. No user worlds or authoritative descriptors are overwritten.\n");
    }

    private void note(String check, String detail) { evidence.append(check).append('\t').append(renderedFrames).append('\t').append(detail).append('\n'); }
    private boolean ready(int minimum) { return ticks >= minimum && renderedFrames >= firstFrame + 4; }
    private void next() { moveTo(step + 1); }
    private void moveTo(int value) { step = value; ticks = 0; firstFrame = renderedFrames; }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void command(String value) { minecraft.player.connection.sendCommand(value); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (map polish step " + step + ", ticks " + ticks + ")"); }
    }
    private record Point(String id, double x, double y) {
        double distance(Point other) { return Math.hypot(x - other.x, y - other.y); }
    }
}
