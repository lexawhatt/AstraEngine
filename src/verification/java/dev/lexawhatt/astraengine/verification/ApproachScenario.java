package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightControlPayload;
import dev.lexawhatt.astraengine.server.RocketService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Native map-driven local approaches; samples the real server snapshots and production camera without overrides. */
final class ApproachScenario {
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final long startedAt = System.nanoTime();
    private final StringBuilder samples = new StringBuilder("kind\tseconds\tstep\tclock\tepoch\tremaining_ticks\ttarget\tauthoritative_meters\tvisual_meters\tauthoritative_quaternion\tvisual_quaternion\tvelocity_mps\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ExplorationPayload previousSnapshot;
    private ExplorationPayload routeLast;
    private SpaceVector routeStart;
    private SpaceVector stoppedPosition;
    private SpaceVector exitPosition;
    private FlightOrientation manualOrientation;
    private RuntimeException sampleFailure;
    private boolean failureRetained;
    private int step;
    private int ticks;
    private int duration;
    private int captures;
    private int distinctSnapshots;
    private int renderedFrames;
    private int mapRemaining;
    private long routeEpoch;
    private long cancelEpoch;
    private long lastCameraAt;
    private FlightOrientation lastCameraOrientation;
    private SpaceVector beforeReceivePosition;
    private long beforeReceiveAt;
    private boolean wasGuided;
    private boolean verifyPhysicalAnchor;
    private double maxArrivalStepMeters;

    ApproachScenario() {
        minecraft.options.hideGui = true;
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::beforeReceive);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::receive);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::render);
    }

    boolean tick() throws Exception {
        if (sampleFailure != null) {
            retainSampleFailure();
            throw sampleFailure;
        }
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        require(ticks < 1800, "Approach step exceeded 90 seconds of client ticks");
        switch (step) {
            case 0 -> {
                server(this::prepareHome);
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 1 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map)) { return false; }
                controller = map.controller();
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 2 -> {
                if (!idle(20) || !entryAnchorReady()) { return false; }
                boolean fabulous = System.getProperty("astraengine.verify.graphics", "fancy").equals("fabulous");
                require(Minecraft.useShaderTransparency() == fabulous,
                        "Actual transparency mode differs from the requested graphics profile");
                AstraEngine.LOGGER.info("ASTRA_APPROACH_GRAPHICS mode={} transparency={}",
                        minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency());
                verifyPhysicalAnchor = true;
                server(this::verifyServerAnchor);
                shot("approach-01-before");
                routeStart = controller.snapshot().position();
                routeEpoch = controller.snapshot().navigationEpoch();
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 3 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("mars"); click("approach"); next();
            }
            case 4 -> {
                if (!controller.snapshot().approaching()) {
                    require(ticks < 100, "Map did not begin a continuous Mars approach"); return false;
                }
                duration = controller.snapshot().jumpTicks();
                require(duration > 80 && controller.snapshot().navigationEpoch() != routeEpoch,
                        "Local approach did not take navigation ownership for a bounded extended route");
                require(controller.snapshot().position().distance(routeStart) < 1,
                        "Approach teleported before the initial aiming interval");
                routeEpoch = controller.snapshot().navigationEpoch();
                captures = 0;
                shot("approach-02-aiming"); next();
            }
            case 5 -> {
                ExplorationPayload state = controller.snapshot();
                require(state.systemId().equals("sol") && !state.interstellarJump(), "Local approach became a system jump");
                if (state.approaching()) {
                    double progress = 1 - state.jumpTicks() / (double) duration;
                    if (captures < 4 && progress >= new double[]{0.25, 0.55, 0.80, 0.96}[captures]) {
                        shot("approach-0" + (3 + captures) + "-progress-" + (captures + 1)); captures++;
                    }
                    return false;
                }
                require(captures == 4 && distinctSnapshots > 20, "Approach lacks multiple intermediate rendered positions");
                require(state.navigationEpoch() != routeEpoch && state.velocity().length() == 0,
                        "Arrival did not release guidance into stationary manual flight");
                require(routeLast != null && routeLast.jumpTicks() <= 4, "Arrival lacks a recent in-progress snapshot");
                maxArrivalStepMeters = routeLast.position().distance(state.position());
                require(maxArrivalStepMeters < body("mars").radiusMeters() * 0.1,
                        "Final authoritative approach step snapped more than 0.1 target radius: " + maxArrivalStepMeters);
                next();
            }
            case 6 -> {
                if (!idle(20)) { return false; }
                verifyObservation("mars");
                shot("approach-07-mars-arrival");
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 7 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("sun"); click("approach"); next();
            }
            case 8 -> {
                if (!controller.snapshot().approaching() || ticks < 35) { return false; }
                cancelEpoch = controller.snapshot().navigationEpoch();
                mapRemaining = controller.snapshot().jumpTicks();
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 9 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 15) { return false; }
                require(controller.snapshot().approaching() && controller.snapshot().jumpTicks() < mapRemaining,
                        "Opening the non-pausing map canceled or stopped approach");
                shot("approach-map-during-guidance");
                ((CosmosMapScreen) minecraft.screen).onClose();
                pending = minecraft.reloadResourcePacks(); next();
            }
            case 10 -> {
                if (ticks < 8) { return false; }
                require(controller.snapshot().approaching(), "Resource reload canceled the server-owned route");
                shot("approach-08-reloaded-in-flight");
                hold(GLFW.GLFW_KEY_B, true); next();
            }
            case 11 -> {
                if (controller.snapshot().approaching()) { return false; }
                hold(GLFW.GLFW_KEY_B, false);
                require(controller.snapshot().navigationEpoch() != cancelEpoch && controller.snapshot().velocity().length() == 0,
                        "B did not cancel guidance at a stationary safe point");
                stoppedPosition = controller.snapshot().position();
                PacketDistributor.sendToServer(new FlightControlPayload(1, 0, 0,
                        FlightOrientation.fromAngles(170, -70, 45), false, 1_000_000, cancelEpoch));
                next();
            }
            case 12 -> {
                if (ticks < 20) { return false; }
                require(controller.snapshot().position().equals(stoppedPosition),
                        "A delayed old-epoch control moved the canceled pilot");
                manualOrientation = controller.orientation();
                hold(GLFW.GLFW_KEY_W, true); next();
            }
            case 13 -> {
                if (ticks < 12) { return false; }
                hold(GLFW.GLFW_KEY_W, false);
                SpaceVector moved = controller.snapshot().position().subtract(stoppedPosition);
                require(moved.length() > 5 && moved.normalized().dot(manualOrientation.forward()) > 0.95,
                        "Manual forward control did not resume after B/old-epoch rejection");
                shot("approach-09-canceled-manual"); next();
            }
            case 14 -> {
                if (!idle(12)) { return false; }
                require(controller.snapshot().velocity().length() == 0, "Manual release did not stop the pilot");
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 15 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("earth"); click("approach"); next();
            }
            case 16 -> {
                if (!controller.snapshot().approaching() || ticks < 60) { return false; }
                cancelEpoch = controller.snapshot().navigationEpoch();
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 17 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 5) { return false; }
                require(controller.snapshot().approaching(), "Route ended before the map cancellation check");
                click("cancel_approach"); next();
            }
            case 18 -> {
                if (controller.snapshot().approaching()) { return false; }
                require(controller.snapshot().navigationEpoch() != cancelEpoch && controller.snapshot().velocity().length() == 0,
                        "Map Cancel approach did not release guidance at a stationary safe point");
                stoppedPosition = controller.snapshot().position();
                next();
            }
            case 19 -> {
                if (!idle(15)) { return false; }
                require(controller.snapshot().position().equals(stoppedPosition), "Map-canceled route continued moving");
                shot("approach-10-map-canceled");
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 20 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen)) { return false; }
                selectBody("earth"); click("approach"); next();
            }
            case 21 -> {
                if (!controller.snapshot().approaching() || ticks < 45) { return false; }
                verifyPhysicalAnchor = false;
                tap(GLFW.GLFW_KEY_R); next();
            }
            case 22 -> {
                if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 20) { return false; }
                require(!controller.snapshot().active() && controller.snapshot().jumpTicks() == 0,
                        "Leaving flight retained the runtime route");
                exitPosition = controller.snapshot().position();
                server(server -> {
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Home block was changed by approach");
                    require(!server.getPlayerList().getPlayers().getFirst().getPersistentData().contains("astraengine_flight_recovery"),
                            "Successful return retained flight recovery ownership");
                });
                tap(GLFW.GLFW_KEY_R); next();
            }
            case 23 -> {
                if (!idle(30) || !entryAnchorReady()) { return false; }
                server(this::verifyServerAnchor);
                require(controller.snapshot().position().equals(exitPosition), "Reentry resumed or relocated a disposed route");
                shot("approach-11-reentered-without-route");
                tap(GLFW.GLFW_KEY_R); next();
            }
            case 24 -> {
                if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 10) { return false; }
                Path evidence = minecraft.gameDirectory.toPath().resolve("evidence");
                Files.writeString(evidence.resolve("approach-samples.tsv"), samples.toString());
                Files.writeString(evidence.resolve("approach-scope.txt"),
                        "All approaches use actual map widgets, authoritative snapshots and the unmodified production camera/renderer.\n"
                        + "Graphics=" + minecraft.options.graphicsMode().get() + ", transparency="
                        + Minecraft.useShaderTransparency() + ".\n"
                        + "No virtual position, descriptor or rendered-pose overrides are installed.\n"
                        + "Fresh disposable Sol world; map/reload continuation, B and map-button cancellation, stale epoch, manual movement and leave/reentry checked.\n"
                        + "Captured snapshots=" + distinctSnapshots + ", rendered frames=" + renderedFrames
                        + ", final authoritative movement meters=" + maxArrivalStepMeters + ".\n"
                        + "This is continuity/lifecycle evidence, not a frame-time benchmark or remote multiplayer test.\n");
                AstraEngine.LOGGER.info("ASTRA_APPROACH_PASSED snapshots={} frames={} finalStepMeters={}",
                        distinctSnapshots, renderedFrames, maxArrivalStepMeters);
                return true;
            }
            default -> throw new IllegalStateException("Unknown approach step " + step);
        }
        return false;
    }

    private void beforeReceive(ExplorationReceivedEvent event) {
        beforeReceivePosition = null;
        wasGuided = false;
        if (controller == null || !controller.active()) { return; }
        beforeReceivePosition = controller.visualPosition();
        beforeReceiveAt = System.nanoTime();
        wasGuided = controller.snapshot().approaching();
    }

    private void receive(ExplorationReceivedEvent event) {
        if (controller == null || sampleFailure != null) { return; }
        try {
            ExplorationPayload state = event.payload();
            if (state.active() && state.systemId().equals("sol")) {
                append("snapshot", state);
                if ((wasGuided || state.approaching()) && beforeReceivePosition != null) {
                    double elapsed = (System.nanoTime() - beforeReceiveAt) / 1_000_000_000.0;
                    double speed = Math.max(state.velocity().length(), previousSnapshot == null ? 0 : previousSnapshot.velocity().length());
                    require(controller.visualPosition().distance(beforeReceivePosition) <= 1 + speed * elapsed * 4,
                            "A local snapshot/epoch change snapped the visual observer");
                }
                if (previousSnapshot != null && state.approaching() && previousSnapshot.approaching()
                        && state.navigationEpoch() == previousSnapshot.navigationEpoch()) {
                    require(state.jumpTicks() <= previousSnapshot.jumpTicks()
                                    && (state.clockTicks() == previousSnapshot.clockTicks() || state.jumpTicks() < previousSnapshot.jumpTicks()),
                            "Approach snapshots did not advance remaining time");
                    if (!state.position().equals(previousSnapshot.position())) { distinctSnapshots++; }
                }
                if (step <= 5 && state.approaching()) { routeLast = state; }
            }
            previousSnapshot = state;
        } catch (RuntimeException failure) { sampleFailure = failure; }
    }

    private void camera(ViewportEvent.ComputeCameraAngles event) {
        if (controller == null || !controller.active() || sampleFailure != null) { return; }
        try {
            ExplorationPayload state = controller.snapshot();
            long now = System.nanoTime();
            FlightOrientation current = controller.orientation();
            append("camera", state);
            if (lastCameraOrientation != null && state.approaching() && minecraft.screen == null) {
                double dot = Math.abs(current.x() * lastCameraOrientation.x() + current.y() * lastCameraOrientation.y()
                        + current.z() * lastCameraOrientation.z() + current.w() * lastCameraOrientation.w());
                double angle = Math.toDegrees(2 * Math.acos(Math.clamp(dot, 0, 1)));
                double elapsed = (now - lastCameraAt) / 1_000_000_000.0;
                double limit = 2 + elapsed * 200;
                require(angle <= limit, "Guided camera orientation snapped between camera updates: angleDegrees=" + angle
                        + ", elapsedSeconds=" + elapsed + ", limitDegrees=" + limit
                        + ", previous=" + lastCameraOrientation + ", current=" + current
                        + ", epoch=" + state.navigationEpoch() + ", remainingTicks=" + state.jumpTicks());
            }
            lastCameraOrientation = current; lastCameraAt = now;
        } catch (RuntimeException failure) { sampleFailure = failure; }
    }

    private void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || controller == null
                || !controller.active() || sampleFailure != null) { return; }
        try {
            ExplorationPayload state = controller.snapshot();
            append("render", state);
            require(minecraft.level.dimension().equals(RocketService.FLIGHT), "Approach escaped its physical staging room");
            if (verifyPhysicalAnchor) {
                require(minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                        "Approach/manual input displaced the settled physical anchor: position=" + minecraft.player.position()
                                + ", dimension=" + minecraft.level.dimension().location());
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Native GL error during approach");
            renderedFrames++;
        } catch (RuntimeException failure) { sampleFailure = failure; }
    }

    private void append(String kind, ExplorationPayload state) {
        samples.append(kind).append('\t').append((System.nanoTime() - startedAt) / 1_000_000_000.0)
                .append('\t').append(step).append('\t').append(state.clockTicks()).append('\t').append(state.navigationEpoch())
                .append('\t').append(state.jumpTicks()).append('\t').append(state.jumpTarget()).append('\t').append(state.position())
                .append('\t').append(controller.visualPosition()).append('\t').append(state.orientation())
                .append('\t').append(controller.orientation()).append('\t').append(state.velocity()).append('\n');
    }

    private void retainSampleFailure() {
        if (failureRetained) { return; }
        failureRetained = true;
        Path evidence = minecraft.gameDirectory.toPath().resolve("evidence");
        try {
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("approach-samples.tsv"), samples.toString());
            Files.writeString(evidence.resolve("approach-failure.txt"),
                    "Incomplete native approach fixture; retained telemetry includes the failing sample.\n"
                    + "All samples use authoritative snapshots and the unmodified production camera/renderer.\n"
                    + "No virtual-position, descriptor or rendered-pose overrides are installed.\n"
                    + "Step=" + step + ", ticks=" + ticks + ", renderedFrames=" + renderedFrames + ".\n"
                    + sampleFailure + "\n");
        } catch (IOException failure) {
            sampleFailure.addSuppressed(failure);
        }
    }

    private void verifyObservation(String id) {
        CelestialBody target = body(id);
        double distance = target.positionAt(controller.timeSeconds()).distance(controller.snapshot().position());
        require(Math.abs(distance / target.radiusMeters() - 4) < 0.2, "Arrival is outside the physical four-radius observation point");
        SpaceVector toward = target.positionAt(controller.timeSeconds()).subtract(controller.visualPosition()).normalized();
        var look = minecraft.gameRenderer.getMainCamera().getLookVector();
        require(toward.dot(new SpaceVector(look.x, look.y, look.z)) > 0.995, "Actual arrival camera is not aimed at the selected planet");
    }

    private CelestialBody body(String id) {
        return controller.currentSystem().bodies().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private void selectBody(String id) {
        click("local");
        String name = body(id).name();
        for (int page = 0; page < 8; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(name) || button.getMessage().getString().equals("> " + name))
                    .findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            clickLabel(">");
        }
        throw new IllegalStateException("Map did not expose target " + id);
    }

    private void click(String key) { clickLabel(Component.translatable("astraengine.map." + key).getString()); }
    private void clickLabel(String label) {
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst().orElseThrow();
        clickWidget(button);
    }
    private void clickWidget(Button widget) {
        require(widget.active && widget.visible, "Map button unavailable: " + widget.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Map did not handle widget click"); screen.mouseReleased(x, y, 0);
    }
    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent()); minecraft.gui.getChat().clearMessages(false);
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error after screenshot");
        AstraEngine.LOGGER.info("ASTRA_APPROACH_SCREENSHOT {}", name);
    }
    private void prepareHome(MinecraftServer server) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) { server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState()); }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }
    private boolean entryAnchorReady() {
        boolean ready = minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2;
        require(ready || ticks < 100, "Client entry teleport did not settle: position=" + minecraft.player.position()
                + ", dimension=" + minecraft.level.dimension().location());
        return ready;
    }
    private void verifyServerAnchor(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        require(RocketService.isFlightWorld(player)
                        && player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Server entry anchor did not settle: position=" + player.position()
                        + ", dimension=" + player.serverLevel().dimension().location());
    }
    private boolean idle(int minimum) { return controller.active() && ticks >= minimum && controller.snapshot().jumpTicks() == 0; }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() { AstraEngine.LOGGER.info("ASTRA_APPROACH_STEP {} complete", step); step++; ticks = 0; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (approach step " + step + ", tick " + ticks + ")"); }
    }
}
