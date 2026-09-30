package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.GalacticNavigation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.RocketService;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
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
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.opengl.GL11;

/** Native visited authorization, swept manual arrival and external-galaxy views through ordinary controls. */
final class GalacticScenario {
    private static final BlockPos MARKER = new BlockPos(0, 199, 0);
    private static final SpaceVector GALAXY_CENTER = new SpaceVector(26_000, 0, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final StringBuilder samples = new StringBuilder("clock\tsystem\tlocal_meters\tabsolute_light_years\tvelocity_mps\tselected_speed_mps\tvisited\tcharted_count\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ManualSystemVisit manualVisit;
    private ExplorationPayload previous;
    private ExplorationPayload rebaseIncoming;
    private FlightOrientation beforeRebaseOrientation;
    private FlightOrientation beforeRebaseTarget;
    private RuntimeException sampleFailure;
    private Properties checkpoint;
    private String target;
    private SpaceVector unchangedPosition;
    private SpaceVector farPosition;
    private int initialChartCount;
    private int step;
    private int ticks;
    private int manualEntries;
    private boolean observingManualEntry;
    private boolean transitCaptured;

    GalacticScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = true; minecraft.options.fov().set(70);
        minecraft.options.sensitivity().set(0.5); minecraft.options.invertYMouse().set(false);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::beforeReceive);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::receive);
        AstraEngine.LOGGER.info("ASTRA_GALACTIC_BEGIN restart={} graphics={} transparency={}",
                restart, minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
    }

    boolean tick() throws Exception {
        if (sampleFailure != null) {
            writeSamples(); throw sampleFailure;
        }
        if (!pending.isDone()) { return false; }
        pending.join();
        try {
            if (manualVisit != null) {
                if (!manualVisit.tick()) { return false; }
                manualVisit = null;
            }
            ticks++;
            require(ticks < 1200, "Galactic fixture step timed out");
            return restart ? restartTick() : createTick();
        } catch (RuntimeException failure) {
            sampleFailure = failure; writeSamples(); throw failure;
        }
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> { server(this::prepareHome); next(); }
            case 1 -> { if (ticks < 20) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller();
                require(controller.snapshot().discoveredSystems().size() == 27
                                && controller.snapshot().visitedSystems().equals(List.of("sol")),
                        "New pilot did not chart the neighborhood with only Sol unlocked");
                target = controller.discoveredSystems().stream().filter(system -> !system.id().equals("sol"))
                        .min(Comparator.comparingDouble(system -> system.galaxyPosition().length())).orElseThrow().id();
                initialChartCount = controller.snapshot().discoveredSystems().size();
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> { if (!entryReady()) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(target); require(!button("jump").active, "Visible unvisited target enabled fast travel"); next();
            }
            case 5 -> {
                if (ticks < 10) { return false; }
                shot("galactic-01-visible-locked-chart"); unchangedPosition = controller.snapshot().position();
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, target);
                ((CosmosMapScreen) minecraft.screen).onClose(); next();
            }
            case 6 -> {
                if (ticks < 20) { return false; }
                require(controller.snapshot().systemId().equals("sol") && controller.snapshot().jumpTicks() == 0
                                && controller.snapshot().position().equals(unchangedPosition)
                                && controller.snapshot().visitedSystems().equals(List.of("sol")),
                        "Forged unvisited fast-travel request changed authoritative navigation");
                observingManualEntry = true; manualVisit = new ManualSystemVisit(controller, target, false, true); next();
            }
            case 7 -> {
                observingManualEntry = false;
                require(manualEntries == 1 && controller.snapshot().systemId().equals(target)
                                && controller.snapshot().visitedSystems().contains(target)
                                && controller.snapshot().discoveredSystems().size() > initialChartCount,
                        "Manual swept entry did not unlock exactly one target and reveal its neighborhood");
                verifyAnchor(); shot("galactic-02-manual-entry"); tap(GLFW.GLFW_KEY_M); next();
            }
            case 8 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem("sol"); click("jump"); next();
            }
            case 9 -> { if (!arrived("sol", 100)) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 10 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(target); require(button("jump").active, "Visited target did not unlock fast travel"); next();
            }
            case 11 -> {
                if (ticks < 10) { return false; }
                shot("galactic-03-visited-unlocked-chart"); click("jump"); next();
            }
            case 12 -> {
                if (!transitCaptured && ticks >= 20 && controller.snapshot().interstellarJump()) {
                    shot("galactic-04-fast-travel-without-streaks"); transitCaptured = true;
                }
                if (!arrived(target, 100)) { return false; }
                require(transitCaptured, "Fast-travel transition was not observed");
                shot("galactic-05-unlocked-arrival");
                aimDirection(new SpaceVector(0, 1, 0)); command("astra-flight speed " + BigDecimal.valueOf(FlightDynamics.MAX_SPEED).toPlainString()); next();
            }
            case 13 -> {
                if (!headingReady(new SpaceVector(0, 1, 0))) { return false; }
                hold(GLFW.GLFW_KEY_W, true); next();
            }
            case 14 -> {
                if (absoluteLightYears(controller.snapshot()).y() < 70_000) { return false; }
                hold(GLFW.GLFW_KEY_W, false); hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 15 -> {
                if (ticks < 10) { return false; }
                hold(GLFW.GLFW_KEY_B, false); require(controller.snapshot().velocity().length() == 0, "Far polar flight did not stop");
                farPosition = controller.snapshot().position(); command("astra-flight galaxy aim"); next();
            }
            case 16 -> {
                if (!galaxyAimReady()) { return false; }
                verifyAnchor(); shot("galactic-06-external-face-on"); pending = minecraft.reloadResourcePacks(); next();
            }
            case 17 -> {
                if (ticks < 20) { return false; }
                require(controller.snapshot().position().equals(farPosition), "Reload moved the real virtual observer");
                shot("galactic-07-external-reloaded");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, controller.snapshot().systemId()); next();
            }
            case 18 -> {
                if (!arrived(target, 100)) { return false; }
                require(controller.snapshot().position().length() < GalacticNavigation.arrivalRadiusMeters(controller.currentSystem()),
                        "Fast return from outside the current origin system did not relocate to its observation");
                aimDirection(new SpaceVector(1, 0, 0)); command("astra-flight speed " + BigDecimal.valueOf(FlightDynamics.MAX_SPEED).toPlainString()); next();
            }
            case 19 -> {
                if (!headingReady(new SpaceVector(1, 0, 0))) { return false; }
                hold(GLFW.GLFW_KEY_W, true); next();
            }
            case 20 -> {
                if (absoluteLightYears(controller.snapshot()).x() < 100_000) { return false; }
                hold(GLFW.GLFW_KEY_W, false); hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 21 -> {
                if (ticks < 10) { return false; }
                hold(GLFW.GLFW_KEY_B, false); require(controller.snapshot().velocity().length() == 0, "Far disk-plane flight did not stop");
                command("astra-flight galaxy aim"); next();
            }
            case 22 -> {
                if (!galaxyAimReady()) { return false; }
                verifyAnchor(); shot("galactic-08-external-edge-on"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 23 -> {
                if (!home(20)) { return false; }
                require(!controller.snapshot().active() && controller.snapshot().jumpTicks() == 0, "Return retained flight ownership");
                checkpoint = new Properties(); var state = controller.snapshot();
                checkpoint.setProperty("target", target); checkpoint.setProperty("system", state.systemId());
                checkpoint.setProperty("position", state.position().toString()); checkpoint.setProperty("orientation", state.orientation().toString());
                checkpoint.setProperty("speed", Double.toString(state.speedMetersPerSecond()));
                checkpoint.setProperty("charted", String.join(",", state.discoveredSystems()));
                checkpoint.setProperty("visited", String.join(",", state.visitedSystems()));
                server(this::verifyHome); next();
            }
            case 24 -> {
                StringWriter output = new StringWriter(); checkpoint.store(output, "Actual galactic flight and visited state, no navigation overrides");
                Files.writeString(checkpointPath(), output.toString()); writeSamples();
                AstraEngine.LOGGER.info("ASTRA_GALACTIC_PASSED restart=false manualEntries={} target={}", manualEntries, target); return true;
            }
            default -> throw new IllegalStateException("Unexpected galactic step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint = new Properties(); checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                target = checkpoint.getProperty("target"); server(this::verifyHome); next();
            }
            case 1 -> { if (!home(20)) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller(); verifyCheckpoint(); map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> { if (!entryReady()) { return false; } verifyCheckpoint(); tap(GLFW.GLFW_KEY_M); next(); }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(target); require(button("jump").active, "Restart lost visited fast-travel authorization"); next();
            }
            case 5 -> {
                if (ticks < 10) { return false; }
                shot("galactic-restart-01-retained-unlock"); click("jump"); next();
            }
            case 6 -> {
                if (!arrived(target, 100)) { return false; }
                require(controller.snapshot().position().length() < GalacticNavigation.arrivalRadiusMeters(controller.currentSystem()),
                        "Restart fast travel remained outside the visited system");
                verifyAnchor(); shot("galactic-restart-02-unlocked-return"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 7 -> { if (!home(20)) { return false; } server(this::verifyHome); next(); }
            case 8 -> { writeSamples(); AstraEngine.LOGGER.info("ASTRA_GALACTIC_PASSED restart=true target={}", target); return true; }
            default -> throw new IllegalStateException("Unexpected galactic restart step " + step);
        }
        return false;
    }

    private void beforeReceive(ExplorationReceivedEvent event) {
        if (controller == null || !observingManualEntry || sampleFailure != null) { return; }
        var current = controller.snapshot();
        var incoming = event.payload();
        if (current.active() && incoming.active() && !current.systemId().equals(incoming.systemId())
                && current.jumpTicks() == 0 && incoming.jumpTicks() == 0) {
            rebaseIncoming = incoming;
            beforeRebaseOrientation = controller.orientation();
            beforeRebaseTarget = controller.targetOrientation();
        }
    }

    private void receive(ExplorationReceivedEvent event) {
        if (controller == null || sampleFailure != null) { return; }
        try {
            ExplorationPayload state = event.payload();
            SpaceVector absolute = absoluteLightYears(state);
            samples.append(state.clockTicks()).append('\t').append(state.systemId()).append('\t').append(state.position())
                    .append('\t').append(absolute).append('\t').append(state.velocity()).append('\t').append(state.speedMetersPerSecond())
                    .append('\t').append(String.join(",", state.visitedSystems())).append('\t').append(state.discoveredSystems().size()).append('\n');
            if (observingManualEntry && previous != null && !state.systemId().equals(previous.systemId())) {
                require(state.systemId().equals(target) && previous.jumpTicks() == 0 && state.jumpTicks() == 0,
                        "Manual entry changed to an unexpected system or used a timed jump");
                SpaceVector beforeMeters = absoluteLightYears(previous).multiply(CosmosGenerator.LIGHT_YEAR);
                SpaceVector reachedMeters = absolute.multiply(CosmosGenerator.LIGHT_YEAR);
                SpaceVector travel = reachedMeters.subtract(beforeMeters);
                double elapsed = Math.max(1, state.clockTicks() - previous.clockTicks()) / 20.0;
                require(travel.length() <= previous.speedMetersPerSecond() * elapsed * 1.000001 + 1_000,
                        "Manual boundary crossing exceeded the swept movement budget");
                require(travel.length() > 0 && travel.normalized().dot(previous.orientation().forward()) > 0.99999,
                        "Manual entry relocated away from the held forward ray");
                double radius = GalacticNavigation.arrivalRadiusMeters(controller.system(target));
                require(Math.abs(state.position().length() - radius) < radius * 0.001,
                        "Manual entry teleported from its capture boundary to a body observation");
                require(state.orientation().forward().dot(previous.orientation().forward()) > 0.9999999999,
                        "Manual entry reset the held forward heading");
                require(state == rebaseIncoming, "Manual origin change bypassed the before/after event observation");
                require(!beforeRebaseTarget.equals(state.orientation()),
                        "Boundary roll did not distinguish live camera aim from the authoritative snapshot");
                require(controller.orientation().equals(beforeRebaseOrientation)
                                && controller.targetOrientation().equals(beforeRebaseTarget),
                        "Manual origin change reset the live camera or discarded its pending roll aim: before="
                                + beforeRebaseOrientation + ", after=" + controller.orientation() + ", targetBefore="
                                + beforeRebaseTarget + ", targetAfter=" + controller.targetOrientation());
                AstraEngine.LOGGER.info("ASTRA_GALACTIC_REBASE_CONTINUITY displayed={} target={} authoritative={}",
                        beforeRebaseOrientation, beforeRebaseTarget, state.orientation());
                manualEntries++;
            }
            previous = state;
        } catch (RuntimeException failure) { sampleFailure = failure; }
    }

    private SpaceVector absoluteLightYears(ExplorationPayload state) {
        return controller.system(state.systemId()).galaxyPosition().add(state.position().multiply(1 / CosmosGenerator.LIGHT_YEAR));
    }
    private boolean headingReady(SpaceVector direction) {
        return ticks >= 20 && controller.orientation().forward().dot(direction) > 0.999999
                && controller.snapshot().orientation().forward().dot(direction) > 0.999999
                && controller.snapshot().speedMetersPerSecond() == FlightDynamics.MAX_SPEED;
    }
    private boolean galaxyAimReady() {
        SpaceVector direction = GALAXY_CENTER.subtract(absoluteLightYears(controller.snapshot())).normalized();
        return ticks >= 20 && controller.orientation().forward().dot(direction) > 0.99999
                && controller.snapshot().orientation().forward().dot(direction) > 0.99999;
    }
    private void aimDirection(SpaceVector direction) {
        var orientation = controller.targetOrientation();
        double yaw = Math.toDegrees(Math.atan2(-direction.dot(orientation.left()), direction.dot(orientation.forward())));
        double pitch = -Math.toDegrees(Math.asin(Math.clamp(direction.dot(orientation.up()), -1, 1)));
        double sensitivity = Math.pow(minecraft.options.sensitivity().get() * 0.6 + 0.2, 3) * 8 * 0.15;
        long window = minecraft.getWindow().getWindow();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(window, null);
        require(callback != null, "Host cursor callback is unavailable"); GLFW.glfwSetCursorPosCallback(window, callback);
        callback.invoke(window, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
        callback.invoke(window, minecraft.mouseHandler.xpos() + yaw / sensitivity, minecraft.mouseHandler.ypos() + pitch / sensitivity);
    }
    private void verifyCheckpoint() {
        var state = controller.snapshot();
        require(state.systemId().equals(checkpoint.getProperty("system")) && state.position().toString().equals(checkpoint.getProperty("position"))
                        && state.orientation().toString().equals(checkpoint.getProperty("orientation"))
                        && Double.toString(state.speedMetersPerSecond()).equals(checkpoint.getProperty("speed"))
                        && String.join(",", state.discoveredSystems()).equals(checkpoint.getProperty("charted"))
                        && String.join(",", state.visitedSystems()).equals(checkpoint.getProperty("visited")),
                "Restart changed exact far-flight pose, chart or visited authorization");
    }
    private void selectSystem(String id) {
        click("nearby"); String name = controller.system(id).name();
        for (int page = 0; page < 64; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(value -> value.getMessage().getString().equals(name) || value.getMessage().getString().equals("> " + name))
                    .findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            clickWidget(findButton(">"));
        }
        throw new IllegalStateException("Charted target missing from map " + id);
    }
    private Button button(String key) { return findButton(Component.translatable("astraengine.map." + key).getString()); }
    private Button findButton(String label) {
        return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private void click(String key) { clickWidget(button(key)); }
    private void clickWidget(Button button) {
        require(button.active && button.visible, "Galactic widget unavailable: " + button.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Galactic widget did not handle click"); screen.mouseReleased(x, y, 0);
    }
    private boolean entryReady() {
        if (!controller.active() || ticks < 30) { return false; }
        boolean ready = minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2;
        require(ready || ticks < 120, "Physical galactic-flight entry did not settle: " + minecraft.player.position()); return ready;
    }
    private void verifyAnchor() {
        require(minecraft.level.dimension().equals(RocketService.FLIGHT)
                        && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Virtual galactic movement displaced the physical anchor: " + minecraft.player.position());
    }
    private void prepareHome(MinecraftServer server) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) { server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState()); }
        }
        server.overworld().setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }
    private void verifyHome(MinecraftServer server) {
        require(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "Galactic travel changed the real home marker");
    }
    private boolean arrived(String id, int minimum) { return ticks >= minimum && controller.active() && controller.snapshot().jumpTicks() == 0 && controller.snapshot().systemId().equals(id); }
    private boolean home(int minimum) { return ticks >= minimum && minecraft.level.dimension().equals(Level.OVERWORLD); }
    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png"); Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_GALACTIC_SCREENSHOT {}", name);
    }
    private void writeSamples() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(restart ? "galactic-restart-samples.tsv" : "galactic-samples.tsv"), samples.toString());
        Files.writeString(directory.resolve(restart ? "galactic-restart-scope.txt" : "galactic-scope.txt"),
                "Actual server navigation using map aim, numeric speed, mouse callbacks and held movement; no position/visited/render overrides.\n"
                + "Manual capture boundary and live rolling camera continuity checked before unlocked fast travel. Face/edge views are reached through continuous ordinary flight.\n"
                + "Restart=" + restart + ", graphics=" + minecraft.options.graphicsMode().get() + ", failure=" + sampleFailure + ".\n"
                + "Not a performance benchmark, astronomical census or remote multiplayer test.\n");
    }
    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("galactic-checkpoint.properties"); }
    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) { MinecraftServer server = minecraft.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server); }
    private void next() { AstraEngine.LOGGER.info("ASTRA_GALACTIC_STEP {} complete restart={}", step, restart); step++; ticks = 0; }
    private void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message + " (galactic step " + step + ", ticks " + ticks + ")"); } }
}
