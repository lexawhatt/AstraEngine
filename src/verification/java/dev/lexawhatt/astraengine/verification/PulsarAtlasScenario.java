package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.flight.UniverseAtlasScreen;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.PulsarGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.RocketService;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Public pulsar atlas widgets, actual manual first arrival, continuous approach and exact process-restart checks. */
final class PulsarAtlasScenario {
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final StringBuilder samples = new StringBuilder("clock\tsystem\tposition\torientation\tspeed\tcharted\tvisited\n");
    private final Consumer<ExplorationReceivedEvent> explorationListener = this::receive;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null && minecraft.screen == null) {
            this.clearFrames++;
        } else {
            this.clearFrames = 0;
        }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ManualSystemVisit manualVisit;
    private SpaceVector stationary;
    private Properties checkpoint = new Properties();
    private int step;
    private int ticks;
    private int clearFrames;
    private boolean sawApproach;

    PulsarAtlasScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, explorationListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            if (manualVisit != null) {
                if (!manualVisit.tick()) { return false; }
                manualVisit = null;
            }
            require(++ticks < 2400, "Pulsar fixture step exceeded its bounded timeout");
            boolean complete = restart ? restartTick() : createTick();
            if (complete) {
                retain("passed");
                dispose();
                AstraEngine.LOGGER.info("ASTRA_PULSAR_ATLAS_PASSED restart={}", restart);
            }
            return complete;
        } catch (Exception failure) {
            retain(failure.toString());
            dispose();
            throw failure;
        }
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> { server(this::prepareHome); next(); }
            case 1 -> { if (ticks < 20) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller();
                require(controller.snapshot().visitedSystems().equals(List.of("sol")), "Fresh pilot has unearned visits");
                require(!controller.snapshot().discoveredSystems().contains("p_0"), "Pulsar was charted before the atlas request");
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!flightReady()) { return false; }
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                clickKey("astraengine.map.atlas"); next();
            }
            case 5 -> {
                if (!(minecraft.screen instanceof UniverseAtlasScreen)) { return false; }
                clickLabel(PulsarGenerator.landmark(controller.snapshot().galaxySeed(), 0).name());
                minecraft.options.hideGui = false; next();
            }
            case 6 -> {
                if (ticks < 10 || minecraft.getOverlay() != null) { return false; }
                shot("01-public-atlas");
                stationary = controller.snapshot().position();
                clickKey("astraengine.atlas.aim"); minecraft.options.hideGui = true; next();
            }
            case 7 -> {
                if (ticks < 15 || !controller.snapshot().discoveredSystems().contains("p_0")) { return false; }
                require(!controller.visited("p_0") && controller.snapshot().position().equals(stationary)
                        && controller.snapshot().jumpTicks() == 0, "Chart-and-aim moved the observer or granted a visit");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, "p_0"); next();
            }
            case 8 -> {
                if (ticks < 15) { return false; }
                require(!controller.visited("p_0") && controller.snapshot().position().equals(stationary)
                        && controller.snapshot().jumpTicks() == 0, "Unvisited pulsar jump bypassed server authorization");
                manualVisit = new ManualSystemVisit(controller, "p_0", true); next();
            }
            case 9 -> {
                if (!flightReady() || clearFrames < 8) { return false; }
                verifyPulsar(); shot("02-manual-entry-unlocked");
                stationary = controller.snapshot().position();
                minecraft.player.connection.sendCommand("astra-flight speed 500000"); next();
            }
            case 10 -> {
                if (ticks < 15 || controller.snapshot().speedMetersPerSecond() != 500000) { return false; }
                hold(GLFW.GLFW_KEY_S, true); next();
            }
            case 11 -> {
                if (controller.snapshot().position().distance(stationary) < 250_000) { return false; }
                hold(GLFW.GLFW_KEY_S, false); hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 12 -> {
                if (ticks < 12 || controller.snapshot().velocity().length() != 0) { return false; }
                hold(GLFW.GLFW_KEY_B, false);
                controller.action(FlightActionPayload.Action.APPROACH_BODY, "primary"); next();
            }
            case 13 -> {
                sawApproach |= controller.snapshot().approaching();
                if (ticks < 15 || controller.snapshot().jumpTicks() != 0 || clearFrames < 8) { return false; }
                require(sawApproach, "Pulsar approach did not enter the continuous route state");
                verifyPulsar();
                double distance = controller.snapshot().position().length();
                require(Math.abs(distance - controller.currentSystem().bodies().getFirst().radiusMeters() * 80) < 1,
                        "Pulsar observation did not retain physical radius and eighty-radius framing");
                shot("03-continuous-approach");
                stationary = controller.snapshot().position(); pending = minecraft.reloadResourcePacks(); next();
            }
            case 14 -> {
                if (ticks < 15 || clearFrames < 8) { return false; }
                require(controller.snapshot().position().equals(stationary), "Reload changed the pulsar observer");
                verifyPulsar(); shot("04-reloaded"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 15 -> {
                if (!home()) { return false; }
                saveCheckpoint(); server(this::verifyHomeAndFormat); next();
            }
            case 16 -> {
                StringWriter text = new StringWriter();
                checkpoint.store(text, "Real pulsar navigation and exact persisted discovery");
                require(!Files.exists(checkpointPath()), "Refusing to replace an existing pulsar checkpoint");
                Files.writeString(checkpointPath(), text.toString()); return true;
            }
            default -> throw new IllegalStateException("Unexpected pulsar create step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                server(this::verifyHomeAndFormat); next();
            }
            case 1 -> { if (!home()) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller(); verifyCheckpoint();
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!flightReady() || clearFrames < 8) { return false; }
                verifyCheckpoint(); verifyPulsar(); shot("01-resumed-pulsar");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, "sol"); next();
            }
            case 4 -> {
                if (ticks < 100 || controller.snapshot().jumpTicks() != 0 || !controller.snapshot().systemId().equals("sol")) {
                    return false;
                }
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, "p_0"); next();
            }
            case 5 -> {
                if (ticks < 100 || controller.snapshot().jumpTicks() != 0 || clearFrames < 8) { return false; }
                verifyPulsar(); shot("02-persisted-fast-travel"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 6 -> { if (!home()) { return false; } server(this::verifyHomeAndFormat); next(); }
            case 7 -> { return true; }
            default -> throw new IllegalStateException("Unexpected pulsar restart step " + step);
        }
        return false;
    }

    private void verifyPulsar() {
        CosmosSystem expected = PulsarGenerator.landmark(controller.snapshot().galaxySeed(), 0);
        require(controller.snapshot().systemId().equals("p_0") && controller.visited("p_0")
                        && controller.currentSystem().equals(expected)
                        && controller.currentSystem().bodies().getFirst().kind() == CelestialBody.Kind.PULSAR,
                "Client catalog, saved identity, physical radius or manual visit does not match the pulsar");
        require(minecraft.level.dimension().equals(RocketService.FLIGHT)
                        && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Virtual pulsar travel displaced the real bounded flight anchor");
    }

    private void saveCheckpoint() {
        var state = controller.snapshot();
        checkpoint.setProperty("seed", Long.toString(state.galaxySeed()));
        checkpoint.setProperty("system", state.systemId());
        checkpoint.setProperty("position", state.position().toString());
        checkpoint.setProperty("orientation", state.orientation().toString());
        checkpoint.setProperty("speed", Double.toString(state.speedMetersPerSecond()));
        checkpoint.setProperty("charted", String.join(",", state.discoveredSystems()));
        checkpoint.setProperty("visited", String.join(",", state.visitedSystems()));
        checkpoint.setProperty("descriptor", controller.currentSystem().toString());
    }

    private void verifyCheckpoint() {
        Properties expected = checkpoint;
        checkpoint = new Properties(); saveCheckpoint();
        require(checkpoint.equals(expected), "Restart changed exact navigation, catalog identity, chart or visits");
        checkpoint = expected;
    }

    private void receive(ExplorationReceivedEvent event) {
        var state = event.payload();
        samples.append(state.clockTicks()).append('\t').append(state.systemId()).append('\t').append(state.position())
                .append('\t').append(state.orientation()).append('\t').append(state.speedMetersPerSecond())
                .append('\t').append(state.discoveredSystems()).append('\t').append(state.visitedSystems()).append('\n');
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

    private void verifyHomeAndFormat(MinecraftServer server) {
        require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Real home marker was lost");
        CompoundTag saved = ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess());
        require(saved.getInt("version") == 7 && saved.getInt("pulsar_version") == PulsarGenerator.VERSION,
                "Pulsar generator and exploration versions were not persisted");
    }

    private boolean home() { return ticks >= 20 && minecraft.level.dimension().equals(Level.OVERWORLD); }
    private boolean flightReady() {
        return controller.active() && ticks >= 30 && minecraft.level.dimension().equals(RocketService.FLIGHT)
                && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2;
    }
    private void clickKey(String key) { clickLabel(Component.translatable(key).getString()); }
    private void clickLabel(String label) {
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label) || value.getMessage().getString().equals("> " + label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing pulsar widget: " + label));
        require(button.active && button.visible, "Unavailable pulsar widget: " + label);
        Screen screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Pulsar widget did not handle click: " + label);
        screen.mouseReleased(x, y, 0);
    }
    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/pulsar-" + (restart ? "restart-" : "create-") + name + ".png");
        Files.createDirectories(path.getParent());
        require(!Files.exists(path), "Refusing to overwrite retained pulsar screenshot: " + path);
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_PULSAR_ATLAS_SCREENSHOT {} restart={}", name, restart);
    }
    private void retain(String result) throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        String prefix = "pulsar-" + (restart ? "restart" : "create");
        Files.writeString(directory.resolve(prefix + "-samples.tsv"), samples.toString());
        Files.writeString(directory.resolve(prefix + "-scope.txt"),
                "Real atlas widgets, server chart authorization, manual first visit, route and reload/restart.\n"
                        + "No injected navigation or visit overrides. Already-earned jumps frame the visited compact body.\n"
                        + "Physical radius and descriptor data remain exact; no performance claim. Result=" + result + ".\n");
    }
    private void dispose() {
        hold(GLFW.GLFW_KEY_S, false); hold(GLFW.GLFW_KEY_W, false); hold(GLFW.GLFW_KEY_B, false);
        NeoForge.EVENT_BUS.unregister(explorationListener); NeoForge.EVENT_BUS.unregister(frameListener);
    }
    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("pulsar-checkpoint.properties"); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_PULSAR_ATLAS_STEP step={} complete restart={}", step, restart);
        step++; ticks = 0;
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (pulsar step " + step + ", ticks " + ticks + ")"); }
    }
}
