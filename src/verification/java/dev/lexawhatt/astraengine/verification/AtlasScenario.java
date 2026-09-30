package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.flight.UniverseAtlasScreen;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmicRegion;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.RocketService;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
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
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Real atlas widgets, chart acknowledgements, manual visits and spatial region views; no navigation overrides. */
final class AtlasScenario {
    private static final BlockPos MARKER = new BlockPos(0, 199, 0);
    private static final int[] GALAXIES = {0, 0, 0, 5};
    private static final int[] REGIONS = {0, 1, 5, 0};
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final StringBuilder samples = new StringBuilder("clock\tsystem\tlocal_meters\torientation\tspeed_mps\tcharted\tvisited\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ManualSystemVisit manualVisit;
    private Properties checkpoint;
    private CosmicRegion region;
    private SpaceVector stationary;
    private double outwardDistanceMeters;
    private double requestedSpeed;
    private int route;
    private int step;
    private int ticks;
    private String failure = "none";

    AtlasScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = true; minecraft.options.fov().set(70);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::receive);
        AstraEngine.LOGGER.info("ASTRA_ATLAS_BEGIN restart={} graphics={}", restart, minecraft.options.graphicsMode().get());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            if (manualVisit != null) {
                if (!manualVisit.tick()) { return false; }
                manualVisit = null;
            }
            ticks++;
            require(ticks < 1600, "Atlas step timed out");
            return restart ? restartTick() : createTick();
        } catch (Exception exception) {
            failure = exception.toString(); writeSamples(); throw exception;
        }
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> { server(this::prepareHome); next(); }
            case 1 -> { if (ticks < 20) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller();
                require(controller.snapshot().visitedSystems().equals(List.of("sol")), "Fresh atlas pilot has unearned visits");
                require(controller.snapshot().discoveredSystems().stream().noneMatch(UniverseGenerator::isAtlasSystemId),
                        "Fresh atlas anchors were silently charted");
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!entryReady()) { return false; }
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                clickKey("astraengine.map.atlas"); next();
            }
            case 5 -> {
                if (!(minecraft.screen instanceof UniverseAtlasScreen)) { return false; }
                var galaxy = UniverseGenerator.galaxy(controller.snapshot().galaxySeed(), GALAXIES[route]);
                region = UniverseGenerator.regions(controller.snapshot().galaxySeed(), GALAXIES[route]).get(REGIONS[route]);
                clickLabel(galaxy.name()); clickLabel(region.name());
                minecraft.options.hideGui = false; next();
            }
            case 6 -> {
                if (ticks < 10) { return false; }
                require(minecraft.screen instanceof UniverseAtlasScreen, "Atlas screenshot lacks its visible UI");
                shot("atlas-" + route + "-01-catalog");
                stationary = controller.snapshot().position();
                clickKey("astraengine.atlas.aim"); minecraft.options.hideGui = true; next();
            }
            case 7 -> {
                if (ticks < 15 || !controller.snapshot().discoveredSystems().contains(region.systemId())) { return false; }
                require(!controller.snapshot().visitedSystems().contains(region.systemId())
                                && controller.snapshot().position().equals(stationary) && controller.snapshot().jumpTicks() == 0,
                        "Chart-and-aim moved the observer or granted a visit");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, region.systemId()); next();
            }
            case 8 -> {
                if (ticks < 15) { return false; }
                require(controller.snapshot().position().equals(stationary) && controller.snapshot().jumpTicks() == 0
                                && !controller.snapshot().visitedSystems().contains(region.systemId()),
                        "Forged unvisited atlas jump changed navigation");
                manualVisit = new ManualSystemVisit(controller, region.systemId(), REGIONS[route] == 0); next();
            }
            case 9 -> {
                if (ticks < 15) { return false; }
                require(controller.snapshot().systemId().equals(region.systemId())
                                && controller.snapshot().visitedSystems().contains(region.systemId()),
                        "Manual atlas entry did not preserve the target's identity and visit");
                if (REGIONS[route] == 0) {
                    require(controller.currentSystem().bodies().stream().anyMatch(body -> body.kind() == CelestialBody.Kind.BLACK_HOLE),
                            "Nucleus anchor lacks its real local black-hole descriptor");
                }
                verifyAnchor(); shot("atlas-" + route + "-02-interior");
                beginOutward(region.radiusLightYears() * 1.8); next();
            }
            case 10 -> {
                if (!outwardReady()) { return false; }
                hold(GLFW.GLFW_KEY_S, true); next();
            }
            case 11 -> {
                if (controller.snapshot().position().length() < outwardDistanceMeters) { return false; }
                hold(GLFW.GLFW_KEY_S, false); hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 12 -> {
                if (ticks < 12) { return false; }
                hold(GLFW.GLFW_KEY_B, false);
                require(controller.snapshot().velocity().length() == 0, "Region-edge flight did not stop");
                verifyAnchor(); shot("atlas-" + route + "-03-region-edge");
                if (++route < GALAXIES.length) {
                    tap(GLFW.GLFW_KEY_M); go(4);
                } else {
                    beginOutward(UniverseGenerator.galaxy(controller.snapshot().galaxySeed(), 5).radiusLightYears() * 1.8);
                    next();
                }
            }
            case 13 -> { if (!outwardReady()) { return false; } hold(GLFW.GLFW_KEY_S, true); next(); }
            case 14 -> {
                if (controller.snapshot().position().length() < outwardDistanceMeters) { return false; }
                hold(GLFW.GLFW_KEY_S, false); hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 15 -> {
                if (ticks < 12) { return false; }
                hold(GLFW.GLFW_KEY_B, false); require(controller.snapshot().velocity().length() == 0, "External galaxy flight did not stop");
                verifyAnchor(); shot("atlas-4-second-galaxy-exterior");
                stationary = controller.snapshot().position(); pending = minecraft.reloadResourcePacks(); next();
            }
            case 16 -> {
                if (ticks < 15) { return false; }
                require(controller.snapshot().position().equals(stationary), "Reload changed the actual external observer");
                require(UniverseGenerator.galaxy(controller.snapshot().galaxySeed(), 5).activeNucleus(), "Quasar fixture selected an inactive nucleus");
                shot("atlas-5-exterior-reloaded"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 17 -> {
                if (!home(20)) { return false; }
                checkpoint = new Properties(); var state = controller.snapshot();
                checkpoint.setProperty("seed", Long.toString(state.galaxySeed()));
                checkpoint.setProperty("system", state.systemId()); checkpoint.setProperty("position", state.position().toString());
                checkpoint.setProperty("orientation", state.orientation().toString()); checkpoint.setProperty("speed", Double.toString(state.speedMetersPerSecond()));
                checkpoint.setProperty("charted", String.join(",", state.discoveredSystems())); checkpoint.setProperty("visited", String.join(",", state.visitedSystems()));
                checkpoint.setProperty("galaxies", UniverseGenerator.galaxies(state.galaxySeed()).toString());
                checkpoint.setProperty("regions", descriptorRegions(state.galaxySeed()));
                server(this::verifyHomeAndFormat); next();
            }
            case 18 -> {
                StringWriter text = new StringWriter(); checkpoint.store(text, "Real atlas travel, immutable descriptors and exact persisted navigation");
                Files.writeString(checkpointPath(), text.toString()); writeSamples();
                AstraEngine.LOGGER.info("ASTRA_ATLAS_PASSED restart=false"); return true;
            }
            default -> throw new IllegalStateException("Unexpected atlas step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint = new Properties(); checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                server(this::verifyHomeAndFormat); next();
            }
            case 1 -> { if (!home(20)) { return false; } tap(GLFW.GLFW_KEY_M); next(); }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller(); verifyCheckpoint(); clickKey("astraengine.map.atlas"); next();
            }
            case 3 -> {
                if (!(minecraft.screen instanceof UniverseAtlasScreen)) { return false; }
                clickLabel(UniverseGenerator.galaxy(controller.snapshot().galaxySeed(), 5).name());
                clickLabel(UniverseGenerator.regions(controller.snapshot().galaxySeed(), 5).getFirst().name());
                minecraft.options.hideGui = false; next();
            }
            case 4 -> {
                if (ticks < 10) { return false; }
                shot("atlas-restart-01-persisted-atlas"); minecraft.screen.onClose(); minecraft.options.hideGui = true;
                tap(GLFW.GLFW_KEY_R); next();
            }
            case 5 -> {
                if (!entryReady()) { return false; }
                verifyCheckpoint(); shot("atlas-restart-02-persisted-exterior");
                controller.action(FlightActionPayload.Action.JUMP_SYSTEM, "u_5_0"); next();
            }
            case 6 -> {
                if (ticks < 100 || controller.snapshot().jumpTicks() != 0) { return false; }
                require(controller.snapshot().systemId().equals("u_5_0") && controller.snapshot().position().length() < 4096 * CosmosGenerator.AU,
                        "Restart lost the unlocked quasar return");
                verifyAnchor(); shot("atlas-restart-03-unlocked-nucleus"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 7 -> { if (!home(20)) { return false; } server(this::verifyHomeAndFormat); next(); }
            case 8 -> { writeSamples(); AstraEngine.LOGGER.info("ASTRA_ATLAS_PASSED restart=true"); return true; }
            default -> throw new IllegalStateException("Unexpected atlas restart step " + step);
        }
        return false;
    }

    private void beginOutward(double lightYears) {
        outwardDistanceMeters = lightYears * CosmosGenerator.LIGHT_YEAR;
        requestedSpeed = Math.clamp((outwardDistanceMeters - controller.snapshot().position().length()) / 2,
                FlightDynamics.MIN_SPEED, FlightDynamics.MAX_SPEED);
        require(controller.aimAtSystem(controller.snapshot().systemId()), "Could not aim back at the visited anchor");
        minecraft.player.connection.sendCommand("astra-flight speed " + BigDecimal.valueOf(requestedSpeed).toPlainString());
    }
    private boolean outwardReady() {
        return ticks >= 20 && controller.snapshot().speedMetersPerSecond() == requestedSpeed
                && controller.orientation().equals(controller.targetOrientation())
                && controller.snapshot().orientation().equals(controller.targetOrientation());
    }
    private void receive(ExplorationReceivedEvent event) {
        var state = event.payload();
        samples.append(state.clockTicks()).append('\t').append(state.systemId()).append('\t').append(state.position())
                .append('\t').append(state.orientation()).append('\t').append(state.speedMetersPerSecond())
                .append('\t').append(String.join(",", state.discoveredSystems())).append('\t').append(String.join(",", state.visitedSystems())).append('\n');
    }
    private void verifyCheckpoint() {
        var state = controller.snapshot(); long seed = Long.parseLong(checkpoint.getProperty("seed"));
        require(state.galaxySeed() == seed && state.systemId().equals(checkpoint.getProperty("system"))
                        && state.position().toString().equals(checkpoint.getProperty("position"))
                        && state.orientation().toString().equals(checkpoint.getProperty("orientation"))
                        && Double.toString(state.speedMetersPerSecond()).equals(checkpoint.getProperty("speed"))
                        && String.join(",", state.discoveredSystems()).equals(checkpoint.getProperty("charted"))
                        && String.join(",", state.visitedSystems()).equals(checkpoint.getProperty("visited")),
                "Restart changed exact atlas navigation or private authorization");
        require(UniverseGenerator.galaxies(seed).toString().equals(checkpoint.getProperty("galaxies"))
                        && descriptorRegions(seed).equals(checkpoint.getProperty("regions")), "Restart changed the atlas descriptors");
    }
    private String descriptorRegions(long seed) {
        StringBuilder result = new StringBuilder();
        for (int galaxy = 0; galaxy < UniverseGenerator.GALAXY_COUNT; galaxy++) { result.append(UniverseGenerator.regions(seed, galaxy)); }
        return result.toString();
    }
    private void clickKey(String key) { clickLabel(Component.translatable(key).getString()); }
    private void clickLabel(String label) {
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label) || value.getMessage().getString().equals("> " + label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing atlas widget: " + label));
        require(button.active && button.visible, "Unavailable atlas widget: " + label);
        Screen screen = minecraft.screen; double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Atlas widget did not handle click: " + label); screen.mouseReleased(x, y, 0);
    }
    private boolean entryReady() {
        if (!controller.active() || ticks < 30) { return false; }
        boolean ready = minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2;
        require(ready || ticks < 120, "Physical atlas entry did not settle: " + minecraft.player.position()); return ready;
    }
    private void verifyAnchor() {
        require(minecraft.level.dimension().equals(RocketService.FLIGHT)
                        && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Atlas flight displaced the physical anchor: " + minecraft.player.position());
    }
    private void prepareHome(MinecraftServer server) {
        for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) { server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState()); } }
        server.overworld().setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }
    private void verifyHomeAndFormat(MinecraftServer server) {
        require(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "Atlas flight changed the real home marker");
        CompoundTag saved = ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess());
        require(saved.getInt("version") == 7 && saved.getInt("universe_version") == UniverseGenerator.VERSION,
                "Atlas ownership format or generation version was not persisted");
    }
    private boolean home(int minimum) { return ticks >= minimum && minecraft.level.dimension().equals(Level.OVERWORLD); }
    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png"); Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_ATLAS_SCREENSHOT {}", name);
    }
    private void writeSamples() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        String prefix = restart ? "atlas-restart" : "atlas";
        Files.writeString(directory.resolve(prefix + "-samples.tsv"), samples.toString());
        Files.writeString(directory.resolve(prefix + "-scope.txt"), "Real atlas widgets, server chart acknowledgements, held manual flight and backward region/exterior legs.\n"
                + "No navigation, visit, descriptor or rendering override. Ordinary unlocked jumps frame local nuclear bodies.\n"
                + "Restart=" + restart + ", graphics=" + minecraft.options.graphicsMode().get() + ", failure=" + failure + ".\n"
                + "This is bounded native functional/visual evidence, not a performance or astrophysical accuracy benchmark.\n");
    }
    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("atlas-checkpoint.properties"); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) { MinecraftServer server = minecraft.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server); }
    private void next() { go(step + 1); }
    private void go(int value) { AstraEngine.LOGGER.info("ASTRA_ATLAS_STEP step={} route={} complete restart={}", step, route, restart); step = value; ticks = 0; }
    private void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message + " (atlas step " + step + ", route " + route + ", ticks " + ticks + ")"); } }
}
