package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraCosmos;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.CosmosDescriptorCodec;
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
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Consumer API integration through actual map widgets and a separate saved-world restart, without renderer overrides. */
final class CelestialApiScenario {
    private static final BlockPos MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private CosmosSystem expectedRing;
    private CosmosSystem expectedHole;
    private CosmosSystem expectedHidden;
    private Properties checkpoint;
    private SpaceVector canceledPosition;
    private long approachEpoch;
    private int step;
    private int ticks;
    private int creationCalls;
    private int approachDuration;
    private boolean approachObserved;
    private boolean capturedMiddle;
    private boolean chartSelected;

    CelestialApiScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        AstraEngine.LOGGER.info("ASTRA_CELESTIAL_API_BEGIN restart={} graphics={} transparency={}",
                restart, minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        require(ticks < 1600, "Celestial API fixture step timed out");
        return restart ? restartTick() : createTick();
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                expectedRing = CelestialApiFixtures.ringSystem(); expectedHole = CelestialApiFixtures.holeSystem();
                expectedHidden = CelestialApiFixtures.simple(CelestialApiFixtures.HIDDEN_SYSTEM, "Private Hidden System");
                server(this::createConsumerContent); next();
            }
            case 1 -> {
                if (ticks < 20) { return false; }
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller(); verifyClientDescriptors();
                if (!chartSelected) {
                    minecraft.options.hideGui = false;
                    click("nearby"); chartSelected = true; ticks = 0; return false;
                }
                shot("celestial-api-01-private-discoveries");
                minecraft.options.hideGui = true;
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!entryReady(30)) { return false; }
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(expectedRing); click("jump"); next();
            }
            case 5 -> {
                if (!arrived(CelestialApiFixtures.RING_SYSTEM, 100)) { return false; }
                verifyClientDescriptors(); verifyObservation("primary");
                shot("celestial-api-02-custom-star"); tap(GLFW.GLFW_KEY_M); next();
            }
            case 6 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("ringworld"); click("approach"); next();
            }
            case 7 -> {
                if (!observeApproach("celestial-api-03-ring-approach")) { return false; }
                next();
            }
            case 8 -> {
                if (ticks < 20) { return false; }
                verifyObservation("ringworld"); shot("celestial-api-04-ring-planet");
                pending = minecraft.reloadResourcePacks(); next();
            }
            case 9 -> {
                if (ticks < 20) { return false; }
                verifyClientDescriptors(); verifyObservation("ringworld");
                shot("celestial-api-05-reloaded-ring-planet"); tap(GLFW.GLFW_KEY_M); next();
            }
            case 10 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(expectedHole); click("jump"); next();
            }
            case 11 -> {
                if (!arrived(CelestialApiFixtures.HOLE_SYSTEM, 100)) { return false; }
                verifyClientDescriptors(); verifyObservation("primary");
                shot("celestial-api-06-custom-black-hole"); tap(GLFW.GLFW_KEY_M); next();
            }
            case 12 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("ice"); click("approach"); next();
            }
            case 13 -> {
                if (!controller.snapshot().approaching() || ticks < 65) { return false; }
                require(controller.snapshot().jumpTarget().equals(CelestialApiFixtures.HOLE_SYSTEM + "/ice"),
                        "Custom-system local approach lost its namespaced destination");
                approachEpoch = controller.snapshot().navigationEpoch();
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 14 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 5) { return false; }
                click("cancel_approach"); next();
            }
            case 15 -> {
                if (controller.snapshot().approaching()) { return false; }
                require(controller.snapshot().navigationEpoch() != approachEpoch && controller.snapshot().velocity().length() == 0,
                        "Custom approach cancellation did not relinquish navigation");
                canceledPosition = controller.snapshot().position(); next();
            }
            case 16 -> {
                if (ticks < 15) { return false; }
                require(controller.snapshot().position().equals(canceledPosition), "Canceled custom-system route kept moving");
                shot("celestial-api-07-canceled-custom-route"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 17 -> {
                if (!home(20)) { return false; }
                require(!controller.snapshot().active() && controller.snapshot().jumpTicks() == 0, "Return retained custom navigation ownership");
                captureCheckpoint(); server(this::verifyServerDescriptors); next();
            }
            case 18 -> {
                StringWriter output = new StringWriter();
                checkpoint.store(output, "Exact consumer-created descriptors and authoritative navigation; no consumer registry persistence");
                Files.writeString(checkpointPath(), output.toString());
                shot("celestial-api-08-returned-home");
                writeScope();
                AstraEngine.LOGGER.info("ASTRA_CELESTIAL_API_PASSED restart=false createCalls={}", creationCalls);
                return true;
            }
            default -> throw new IllegalStateException("Unexpected celestial API create step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint = new Properties(); checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                expectedRing = readDescriptor(checkpoint.getProperty("ring"));
                expectedHole = readDescriptor(checkpoint.getProperty("hole"));
                expectedHidden = readDescriptor(checkpoint.getProperty("hidden"));
                server(this::verifyServerDescriptors); next();
            }
            case 1 -> {
                if (!home(20)) { return false; }
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller(); verifyClientDescriptors(); verifySavedNavigation();
                if (!chartSelected) {
                    minecraft.options.hideGui = false;
                    click("nearby"); chartSelected = true; ticks = 0; return false;
                }
                shot("celestial-api-restart-01-retained-chart");
                minecraft.options.hideGui = true;
                map.onClose(); tap(GLFW.GLFW_KEY_R); next();
            }
            case 3 -> {
                if (!entryReady(30)) { return false; }
                verifySavedNavigation(); tap(GLFW.GLFW_KEY_M); next();
            }
            case 4 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectSystem(expectedRing); click("jump"); next();
            }
            case 5 -> {
                if (!arrived(CelestialApiFixtures.RING_SYSTEM, 100)) { return false; }
                verifyClientDescriptors(); shot("celestial-api-restart-02-star");
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 6 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("ringworld"); click("approach"); next();
            }
            case 7 -> {
                if (!observeApproach("celestial-api-restart-03-approach")) { return false; }
                next();
            }
            case 8 -> {
                if (ticks < 20) { return false; }
                verifyObservation("ringworld"); shot("celestial-api-restart-04-persisted-ring-planet");
                pending = minecraft.reloadResourcePacks(); next();
            }
            case 9 -> {
                if (ticks < 20) { return false; }
                verifyClientDescriptors(); verifyObservation("ringworld");
                shot("celestial-api-restart-05-reloaded"); tap(GLFW.GLFW_KEY_R); next();
            }
            case 10 -> {
                if (!home(20)) { return false; }
                server(this::verifyServerDescriptors); next();
            }
            case 11 -> {
                require(creationCalls == 0, "Restart recreated descriptors through the consumer API");
                writeScope();
                AstraEngine.LOGGER.info("ASTRA_CELESTIAL_API_PASSED restart=true createCalls=0");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected celestial API restart step " + step);
        }
        return false;
    }

    private void createConsumerContent(MinecraftServer server) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) { server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState()); }
        }
        server.overworld().setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var player = server.getPlayerList().getPlayers().getFirst(); player.teleportTo(0.5, 200, 0.5);
        for (CosmosSystem system : List.of(expectedRing, expectedHole, expectedHidden)) {
            creationCalls++;
            require(AstraCosmos.create(server, system) == AstraCosmos.CreateResult.CREATED, "Public API did not create " + system.id());
        }
        creationCalls++;
        require(AstraCosmos.create(server, expectedRing) == AstraCosmos.CreateResult.ALREADY_EXISTS, "Creation replay was not idempotent");
        creationCalls++;
        require(AstraCosmos.create(server, CelestialApiFixtures.simple(expectedRing.id(), "Conflicting replacement"))
                        == AstraCosmos.CreateResult.CONFLICT, "Creation overwrote an existing custom descriptor");
        require(AstraCosmos.discover(player, expectedRing.id()) == AstraCosmos.DiscoverResult.DISCOVERED,
                "Public discovery did not grant the ring system");
        require(AstraCosmos.discover(player, expectedHole.id()) == AstraCosmos.DiscoverResult.DISCOVERED,
                "Public discovery did not grant the black-hole system");
        require(AstraCosmos.discover(player, expectedRing.id()) == AstraCosmos.DiscoverResult.ALREADY_KNOWN,
                "Repeated public discovery was not idempotent");
        require(AstraCosmos.discover(player, "verification:missing") == AstraCosmos.DiscoverResult.UNKNOWN_SYSTEM,
                "Unknown custom system was discoverable");
        verifyServerDescriptors(server);
    }

    private void verifyServerDescriptors(MinecraftServer server) {
        for (CosmosSystem expected : List.of(expectedRing, expectedHole, expectedHidden)) {
            require(AstraCosmos.find(server, expected.id()).orElseThrow().equals(expected),
                    "Saved public descriptor differs from the consumer's exact original: " + expected.id());
        }
        require(server.overworld().getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "Consumer navigation changed the real home marker");
    }

    private void verifyClientDescriptors() {
        require(controller.snapshot() != null && controller.snapshot().discoveredSystems().containsAll(
                        List.of(CelestialApiFixtures.RING_SYSTEM, CelestialApiFixtures.HOLE_SYSTEM)), "Private custom discoveries were not synchronized");
        require(controller.system(expectedRing.id()).equals(expectedRing) && controller.system(expectedHole.id()).equals(expectedHole),
                "Client custom descriptors differ from their server-authored values");
        require(!controller.snapshot().discoveredSystems().contains(CelestialApiFixtures.HIDDEN_SYSTEM), "Undiscovered custom system leaked into the chart");
        boolean hiddenUnavailable = false;
        try { controller.system(CelestialApiFixtures.HIDDEN_SYSTEM); }
        catch (IllegalArgumentException | IllegalStateException expected) { hiddenUnavailable = true; }
        require(hiddenUnavailable, "An undiscovered custom descriptor reached the client cache or procedural fallback");
    }

    private boolean observeApproach(String middleCapture) throws Exception {
        if (controller.snapshot().approaching()) {
            if (!approachObserved) {
                approachObserved = true; approachDuration = controller.snapshot().jumpTicks();
                require(approachDuration > 80, "Custom body approach used the obsolete instant transit");
            }
            if (!capturedMiddle && controller.snapshot().jumpTicks() < approachDuration / 2) {
                shot(middleCapture); capturedMiddle = true;
            }
            return false;
        }
        require(approachObserved || ticks < 100, "Custom body approach never acquired guidance");
        if (!approachObserved) { return false; }
        require(capturedMiddle && controller.snapshot().velocity().length() == 0, "Custom body approach lacks a continuous mid-route view or stationary arrival");
        return true;
    }

    private void verifyObservation(String id) {
        CelestialBody body = controller.currentSystem().bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        double framing = body.kind() == CelestialBody.Kind.BLACK_HOLE ? 24 : body.ringOuterRatio() > 0 ? 8 : 4;
        SpaceVector toward = body.positionAt(controller.timeSeconds()).subtract(controller.visualPosition());
        require(Math.abs(toward.length() / body.radiusMeters() - framing) < 0.2, "Custom body physical framing changed: " + id);
        var look = minecraft.gameRenderer.getMainCamera().getLookVector();
        require(toward.normalized().dot(new SpaceVector(look.x, look.y, look.z)) > 0.995, "Rendered camera does not face custom body " + id);
        require(minecraft.level.dimension().equals(RocketService.FLIGHT)
                        && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Custom-system navigation left the bounded physical anchor: " + minecraft.player.position());
    }

    private void captureCheckpoint() {
        checkpoint = new Properties();
        checkpoint.setProperty("ring", writeDescriptor(expectedRing)); checkpoint.setProperty("hole", writeDescriptor(expectedHole));
        checkpoint.setProperty("hidden", writeDescriptor(expectedHidden));
        var state = controller.snapshot();
        checkpoint.setProperty("system", state.systemId()); checkpoint.setProperty("position", state.position().toString());
        checkpoint.setProperty("orientation", state.orientation().toString()); checkpoint.setProperty("speed", Double.toString(state.speedMetersPerSecond()));
        checkpoint.setProperty("discoveries", String.join(",", state.discoveredSystems()));
    }

    private void verifySavedNavigation() {
        var state = controller.snapshot();
        require(state.systemId().equals(checkpoint.getProperty("system")) && state.position().toString().equals(checkpoint.getProperty("position"))
                        && state.orientation().toString().equals(checkpoint.getProperty("orientation"))
                        && Double.toString(state.speedMetersPerSecond()).equals(checkpoint.getProperty("speed"))
                        && String.join(",", state.discoveredSystems()).equals(checkpoint.getProperty("discoveries")),
                "Restart changed exact custom-system navigation or private discoveries");
    }

    private void selectSystem(CosmosSystem system) { click("nearby"); selectEntry(system.name()); }
    private void selectBody(String id) {
        click("local"); selectEntry(controller.currentSystem().bodies().stream().filter(body -> body.id().equals(id)).findFirst().orElseThrow().name());
    }
    private void selectEntry(String name) {
        for (int page = 0; page < 8; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(name) || button.getMessage().getString().equals("> " + name))
                    .findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            clickLabel(">");
        }
        throw new IllegalStateException("Map did not list custom entry " + name);
    }
    private void click(String key) { clickLabel(Component.translatable("astraengine.map." + key).getString()); }
    private void clickLabel(String label) {
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
        clickWidget(button);
    }
    private void clickWidget(Button button) {
        require(button.active && button.visible, "Custom navigation widget unavailable: " + button.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Custom navigation widget did not handle its click"); screen.mouseReleased(x, y, 0);
    }
    private boolean entryReady(int minimum) {
        if (!controller.active() || ticks < minimum) { return false; }
        boolean ready = minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2;
        require(ready || ticks < 120, "Custom flight entry did not settle at its physical anchor: " + minecraft.player.position());
        return ready;
    }
    private boolean arrived(String system, int minimum) {
        return controller.active() && ticks >= minimum && controller.snapshot().jumpTicks() == 0 && controller.snapshot().systemId().equals(system);
    }
    private boolean home(int minimum) { return minecraft.level.dimension().equals(Level.OVERWORLD) && ticks >= minimum; }
    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png"); Files.createDirectories(path.getParent());
        minecraft.gui.getChat().clearMessages(false);
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_CELESTIAL_API_SCREENSHOT {}", name);
    }
    private void writeScope() throws Exception {
        Path directory = minecraft.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(restart ? "celestial-api-restart-scope.txt" : "celestial-api-scope.txt"),
                "Consumer-created star, ringed planet and black-hole descriptors use the public Java creation/discovery API.\n"
                + "Native map, jump, approach, camera and rendering paths have no presentation or position overrides.\n"
                + "One undiscovered descriptor remains absent from the client cache. Exact originals are retained in the checkpoint.\n"
                + "Restart=" + restart + ", create API calls=" + creationCalls + ", graphics=" + minecraft.options.graphicsMode().get() + ".\n"
                + "No block planet, dimension allocation, consumer economy, remote multiplayer or performance claim.\n");
    }
    private String writeDescriptor(CosmosSystem system) { return CosmosDescriptorCodec.encode(system).toString(); }
    private CosmosSystem readDescriptor(String encoded) throws Exception {
        return CosmosDescriptorCodec.decode(TagParser.parseTag(encoded));
    }
    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("celestial-api-checkpoint.properties"); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() { AstraEngine.LOGGER.info("ASTRA_CELESTIAL_API_STEP {} complete restart={}", step, restart); step++; ticks = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (celestial API step " + step + ", ticks " + ticks + ", restart " + restart + ")"); }
    }
}
