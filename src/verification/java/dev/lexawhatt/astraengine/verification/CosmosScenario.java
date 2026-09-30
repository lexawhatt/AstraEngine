package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.opengl.GL11;

/** Actual key/widget navigation, physical anchor, shader frames, and disk recovery in an isolated flight world. */
final class CosmosScenario {
    private static final String NEIGHBOR = "s_-1_-1_0";
    private static final String BLACK_HOLE = "s_-1_-2_0";
    private static final String SUPERNOVA_BRIDGE = "s_-2_-1_0";
    private static final String SUPERNOVA = "s_-3_-1_0";
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ManualSystemVisit manualVisit;
    private String selectedSystem;
    private boolean completedManualVisit;
    private SpaceVector movementStart;
    private Properties checkpoint;
    private long pausedClock;
    private FlightOrientation mouseTarget;
    private double movementMeters;
    private boolean selectionReady;
    private int entryChecks;
    private int step;
    private int ticks;

    CosmosScenario(boolean restart) {
        this.restart = restart;
        AstraEngine.LOGGER.info("ASTRA_COSMOS_GRAPHICS {} transparency={} restart={}",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency(), restart);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (manualVisit != null) {
            if (!manualVisit.tick()) { return false; }
            manualVisit = null; completedManualVisit = true;
        }
        ticks++;
        require(ticks < 1200, "Step timed out");
        return restart ? restartTick() : createTick();
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                server(this::prepareHome);
                GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
                next();
            }
            case 1 -> {
                if (ticks < 40) { return false; }
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map)) { return false; }
                controller = map.controller();
                if (controller.snapshot() == null || ticks < 15) { return false; }
                require(controller.snapshot().discoveredSystems().size() == 27
                                && controller.snapshot().visitedSystems().equals(List.of("sol")),
                        "A new pilot must chart the neighborhood with only Sol visited");
                require(!map.isPauseScreen(), "Cosmos map pauses the integrated server");
                shot("01-map-initial");
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!active() || ticks < (entryChecks == 0 ? 45 : 10)) { return false; }
                require(controller.snapshot().systemId().equals("sol"), "First flight did not begin in Sol");
                if (entryChecks == 0) {
                    controller.action(FlightActionPayload.Action.JUMP_SYSTEM, "s_99_99_99");
                    server(server -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                        try {
                            int result = server.getCommands().getDispatcher().execute("astra visit alpha",
                                    player.createCommandSourceStack());
                            require(result == 0, "Real-world travel command was accepted during Rocket mode");
                        } catch (CommandSyntaxException exception) {
                            throw new IllegalStateException("Travel exclusion check could not execute its command", exception);
                        }
                    });
                    entryChecks = 1; ticks = 0; return false;
                }
                require(controller.snapshot().jumpTicks() == 0
                                && controller.snapshot().discoveredSystems().size() == 27
                                && controller.snapshot().visitedSystems().equals(List.of("sol")),
                        "Uncharted jump changed navigation or discovered an unauthorized system");
                if (entryChecks == 1) {
                    require(controller.snapshot().speedMetersPerSecond() == 100, "New pilot started at an unexpected speed");
                    InputEvent.MouseScrollingEvent wheel = new InputEvent.MouseScrollingEvent(0, 1,
                            false, false, false, minecraft.getWindow().getWidth() / 2.0,
                            minecraft.getWindow().getHeight() / 2.0);
                    NeoForge.EVENT_BUS.post(wheel);
                    require(wheel.isCanceled(), "Rocket mode did not consume the mouse-wheel event");
                    entryChecks = 2; ticks = 0; return false;
                }
                require(controller.snapshot().speedMetersPerSecond() == 150, "Mouse wheel did not increase continuous speed to 150 m/s");
                AstraEngine.LOGGER.info("ASTRA_COSMOS_NEGATIVE_CHECKS unchartedJumpRejected=true realTravelRejected=true wheelMetersPerSecond=150");
                verifyObservation("earth");
                verifyAnchor();
                shot("02-earth-orbit");
                movementStart = controller.snapshot().position();
                hold(GLFW.GLFW_KEY_W, true);
                next();
            }
            case 4 -> {
                if (ticks < 35) { return false; }
                hold(GLFW.GLFW_KEY_W, false);
                hold(GLFW.GLFW_KEY_B, true);
                movementMeters = controller.snapshot().position().distance(movementStart);
                require(movementMeters > 20, "W input did not move virtual position: " + movementMeters);
                verifyAnchor();
                FlightOrientation beforeMouse = controller.orientation();
                mouseTarget = controller.targetOrientation().rotateLocal(36, 12, 0);
                double sensitivity = Math.pow(minecraft.options.sensitivity().get() * 0.6 + 0.2, 3) * 8 * 0.15;
                long window = minecraft.getWindow().getWindow();
                GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(window, null);
                require(callback != null, "Native Minecraft cursor callback is missing");
                GLFW.glfwSetCursorPosCallback(window, callback);
                callback.invoke(window, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
                callback.invoke(window, minecraft.mouseHandler.xpos() + 36 / sensitivity,
                        minecraft.mouseHandler.ypos() + 12 / sensitivity);
                require(controller.orientation().equals(beforeMouse),
                        "Mouse input changed smoothed presentation synchronously");
                next();
            }
            case 5 -> {
                if (ticks < 40) { return false; }
                require(controller.orientation().forward().distance(mouseTarget.forward()) < 0.002
                                && controller.orientation().up().distance(mouseTarget.up()) < 0.002,
                        "Smoothed free camera did not converge toward the native mouse target");
                shot("03-mouse-flight");
                hold(GLFW.GLFW_KEY_B, false);
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 6 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                click("scan");
                next();
            }
            case 7 -> {
                if (controller.snapshot().discoveredSystems().size() < 27 || ticks < 20) { return false; }
                require(controller.snapshot().discoveredSystems().size() == 27, "First scan exceeded its sector bounds");
                require(controller.discoveredSystems().size() == 27, "Map omitted private discoveries");
                if (!selectionReady) {
                    click("nearby"); selectionReady = true; ticks = 0; return false;
                }
                shot("04-map-discoveries");
                selectBody("saturn");
                click("approach");
                closeMap();
                next();
            }
            case 8 -> {
                if (!settled(105)) { return false; }
                verifyObservation("saturn");
                shot("05-saturn-rings");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 9 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                selectBody("sun");
                click("approach");
                closeMap();
                next();
            }
            case 10 -> {
                if (!settled(105)) { return false; }
                verifyObservation("sun");
                shot("06-sun-close");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 11 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                selectBody("earth");
                click("approach");
                closeMap();
                next();
            }
            case 12 -> {
                if (!settled(105)) { return false; }
                verifyObservation("earth");
                shot("07-returned-earth");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 13 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                selectSystem(NEIGHBOR);
                click("jump");
                closeMap();
                next();
            }
            case 14 -> {
                if (ticks < 12) { return false; }
                if (completedManualVisit) {
                    require(controller.snapshot().visitedSystems().contains(NEIGHBOR), "Manual first visit did not unlock the neighbor");
                    shot("08-manual-interstellar-arrival");
                    completedManualVisit = false;
                } else {
                    require(controller.snapshot().jumpTicks() > 0, "System jump did not expose its transit window");
                    shot("08-interstellar-transit");
                }
                next();
            }
            case 15 -> {
                if (!settled(90) || !controller.snapshot().systemId().equals(NEIGHBOR)) { return false; }
                verifyAnchor();
                shot("09-neighbor-system");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 16 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                click("scan");
                next();
            }
            case 17 -> {
                if (!controller.snapshot().discoveredSystems().contains(BLACK_HOLE) || ticks < 20) { return false; }
                require(controller.system(BLACK_HOLE).kind() == CosmosSystem.Kind.BLACK_HOLE,
                        "Expected reproducible rare black-hole system");
                if (!selectionReady) {
                    selectSystem(BLACK_HOLE); selectionReady = true; ticks = 0; return false;
                }
                shot("10-map-black-hole-discovery");
                click("jump");
                closeMap();
                next();
            }
            case 18 -> {
                if (!settled(110) || !controller.snapshot().systemId().equals(BLACK_HOLE)) { return false; }
                verifyObservation("primary");
                verifyAnchor();
                shot("11-black-hole");
                pending = CompletableFuture.allOf(pending, minecraft.reloadResourcePacks());
                next();
            }
            case 19 -> {
                if (ticks < 35) { return false; }
                require(active() && controller.snapshot().systemId().equals(BLACK_HOLE),
                        "Resource reload lost authoritative navigation");
                verifyObservation("primary");
                shot("12-black-hole-reloaded");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 20 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                selectSystem(SUPERNOVA_BRIDGE);
                click("jump");
                closeMap();
                next();
            }
            case 21 -> {
                if (!settled(105) || !controller.snapshot().systemId().equals(SUPERNOVA_BRIDGE)) { return false; }
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 22 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                click("scan");
                next();
            }
            case 23 -> {
                if (!controller.snapshot().discoveredSystems().contains(SUPERNOVA) || ticks < 20) { return false; }
                require(controller.system(SUPERNOVA).kind() == CosmosSystem.Kind.SUPERNOVA,
                        "Expected reproducible rare supernova system");
                selectSystem(SUPERNOVA);
                click("jump");
                closeMap();
                next();
            }
            case 24 -> {
                if (!settled(110) || !controller.snapshot().systemId().equals(SUPERNOVA)) { return false; }
                verifyObservation("primary");
                verifyAnchor();
                shot("13-supernova-remnant");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 25 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 10) { return false; }
                selectSystem(BLACK_HOLE);
                click("jump");
                closeMap();
                next();
            }
            case 26 -> {
                if (!settled(110) || !controller.snapshot().systemId().equals(BLACK_HOLE)) { return false; }
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 27 -> {
                if (ticks < 35) { return false; }
                require(minecraft.getWindow().getWidth() == 960 && minecraft.getWindow().getHeight() == 540,
                        "Native resize did not reach the requested framebuffer dimensions");
                verifyObservation("primary");
                shot("14-black-hole-resized");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                next();
            }
            case 28 -> {
                if (ticks < 35) { return false; }
                require(minecraft.getWindow().getWidth() == 1280 && minecraft.getWindow().getHeight() == 720,
                        "Native restore did not reach the original framebuffer dimensions");
                verifyObservation("primary");
                shot("15-black-hole-restored");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 29 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 30) { return false; }
                server(this::captureCheckpoint);
                next();
            }
            case 30 -> {
                writeCheckpoint();
                shot("16-saved-active-flight");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected cosmos step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 45) { return false; }
                checkpoint = new Properties();
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                require(minecraft.level.dimension().equals(Level.OVERWORLD),
                        "Interrupted flight did not recover to the real home dimension");
                require(minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(0.5, 200, 0.5)) < 0.2,
                        "Interrupted flight returned to the wrong home position");
                server(this::verifyCheckpoint);
                shot("17-recovered-home");
                next();
            }
            case 1 -> {
                if (ticks < 40) { return false; }
                server(server -> require(ExplorationCatalog.get(server).clockTicks() == pausedClock,
                        "Exploration clock advanced without a flight occupant"));
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 20) { return false; }
                controller = map.controller();
                require(controller.snapshot() != null && !controller.active(), "Recovered snapshot still claims active flight");
                require(controller.snapshot().systemId().equals(checkpoint.getProperty("system")),
                        "Client lost persisted virtual system");
                require(String.join(",", controller.snapshot().discoveredSystems()).equals(checkpoint.getProperty("discoveries")),
                        "Client discovery snapshot differs after restart");
                if (!selectionReady) {
                    click("nearby"); selectionReady = true; ticks = 0; return false;
                }
                shot("18-map-persisted-discoveries");
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!active() || ticks < 45) { return false; }
                require(controller.snapshot().systemId().equals(BLACK_HOLE), "R did not resume the persisted virtual system");
                require(controller.snapshot().position().distance(savedPosition()) < 0.01,
                        "R reset the persisted astronomical position");
                verifyObservation("primary");
                verifyAnchor();
                shot("19-resumed-black-hole");
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 4 -> {
                if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 35) { return false; }
                require(!controller.active(), "R exit left the client in Rocket mode");
                server(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                            "Successful exit retained the flight recovery tag");
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                            "Flight/recovery changed the home build");
                });
                shot("20-returned-home");
                next();
            }
            case 5 -> { return true; }
            default -> throw new IllegalStateException("Unexpected cosmos restart step " + step);
        }
        return false;
    }

    private void prepareHome(MinecraftServer server) {
        server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.overworld().setDayTime(6000);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
    }

    private void verifyObservation(String bodyId) {
        CelestialBody body = controller.currentSystem().bodies().stream().filter(value -> value.id().equals(bodyId))
                .findFirst().orElseThrow();
        double distance = controller.snapshot().position().distance(controller.currentSystem().positionAt(body, controller.timeSeconds()));
        boolean remnant = controller.currentSystem().kind() == CosmosSystem.Kind.SUPERNOVA
                && body.id().equals(controller.currentSystem().bodies().getFirst().id());
        double framing = remnant ? 60 : body.kind() == CelestialBody.Kind.BLACK_HOLE ? 24
                : body.ringOuterRatio() > 0 ? 8 : 4;
        require(Math.abs(distance - body.radiusMeters() * framing) < body.radiusMeters() * 0.2,
                "Approach did not reach the expected physical observation radius for " + bodyId + ": " + distance);
        SpaceVector towardBody = controller.currentSystem().positionAt(body, controller.timeSeconds()).subtract(controller.visualPosition()).normalized();
        var actualLook = minecraft.gameRenderer.getMainCamera().getLookVector();
        double alignment = towardBody.dot(new SpaceVector(actualLook.x, actualLook.y, actualLook.z).normalized());
        require(alignment > 0.99, "Rendered camera does not face the approached body " + bodyId
                + ": direction dot=" + alignment + ", camera yaw=" + minecraft.gameRenderer.getMainCamera().getYRot()
                + ", snapshot yaw=" + controller.snapshot().yaw());
        AstraEngine.LOGGER.info("ASTRA_COSMOS_OBSERVATION system={} body={} distanceMeters={} cameraDot={}",
                controller.currentSystem().id(), bodyId, distance, alignment);
    }

    private void verifyAnchor() {
        require(minecraft.level.dimension().equals(RocketService.FLIGHT), "Virtual flight left its physical staging world");
        require(minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                "Rocket input moved the physical Minecraft player away from the bounded anchor");
        server(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            require(RocketService.isFlightWorld(player), "Server player left the flight staging dimension");
            require(player.position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                    "Server physical anchor drifted");
        });
    }

    private void captureCheckpoint(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        require(RocketService.isFlightWorld(player), "Checkpoint must stop with an active flight");
        require(player.getPersistentData().contains("astraengine_flight_recovery"), "Active flight lacks persisted recovery point");
        require(pilot.velocity().length() < 0.001, "Checkpoint requires a stationary virtual observer");
        checkpoint = new Properties();
        checkpoint.setProperty("system", pilot.systemId());
        checkpoint.setProperty("x", Double.toString(pilot.position().x()));
        checkpoint.setProperty("y", Double.toString(pilot.position().y()));
        checkpoint.setProperty("z", Double.toString(pilot.position().z()));
        checkpoint.setProperty("discoveries", String.join(",", pilot.discoveredSystems()));
        checkpoint.setProperty("visited", String.join(",", pilot.visitedSystems()));
        checkpoint.setProperty("clock", Long.toString(catalog.clockTicks()));
        checkpoint.setProperty("speed_mps", Double.toString(pilot.speedMetersPerSecond()));
        checkpoint.setProperty("movement_meters", Double.toString(movementMeters));
        checkpoint.setProperty("mouse_target_yaw", Float.toString(mouseTarget.yaw()));
    }

    private void verifyCheckpoint(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        require(String.join(",", pilot.visitedSystems()).equals(checkpoint.getProperty("visited")), "Persisted visits changed");
        require(pilot.systemId().equals(checkpoint.getProperty("system")), "Saved virtual system was lost");
        require(pilot.position().distance(savedPosition()) < 0.01, "Saved virtual position was lost");
        require(String.join(",", pilot.discoveredSystems()).equals(checkpoint.getProperty("discoveries")),
                "Private discoveries changed across restart");
        require(pilot.speedMetersPerSecond() == Double.parseDouble(checkpoint.getProperty("speed_mps")),
                "Mouse-selected continuous speed was lost across restart");
        require(!player.getPersistentData().contains("astraengine_flight_recovery"), "Recovery tag was not cleared on login");
        require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Home marker was lost across flight/restart");
        pausedClock = catalog.clockTicks();
    }

    private SpaceVector savedPosition() {
        return new SpaceVector(Double.parseDouble(checkpoint.getProperty("x")), Double.parseDouble(checkpoint.getProperty("y")),
                Double.parseDouble(checkpoint.getProperty("z")));
    }

    private void writeCheckpoint() throws Exception {
        StringWriter text = new StringWriter();
        checkpoint.store(text, "Native Cosmos fixture checkpoint; exact double virtual coordinates, private discoveries");
        Files.writeString(checkpointPath(), text.toString());
    }

    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("cosmos-checkpoint.properties"); }
    private boolean active() { return controller != null && controller.active(); }
    private boolean settled(int minimumTicks) {
        return active() && ticks >= minimumTicks && controller.snapshot().jumpTicks() == 0;
    }

    private void selectBody(String id) {
        CelestialBody body = controller.currentSystem().bodies().stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow();
        click("local");
        selectEntry(body.name());
    }

    private void selectSystem(String id) {
        require(controller.snapshot().discoveredSystems().contains(id), "Fixture attempted to select an undiscovered system");
        CosmosSystem system = controller.system(id);
        selectedSystem = id;
        click("nearby");
        selectEntry(system.name());
    }

    private void selectEntry(String name) {
        for (int page = 0; page < 64; page++) {
            Button entry = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(value -> value.getMessage().getString().equals(name)
                            || value.getMessage().getString().equals("> " + name)).findFirst().orElse(null);
            if (entry != null) { clickWidget(entry); return; }
            clickByLabel(">");
        }
        throw new IllegalStateException("Cosmos map pagination did not reach " + name);
    }

    private void click(String key) {
        if (key.equals("jump") && selectedSystem != null && !controller.snapshot().visitedSystems().contains(selectedSystem)) {
            closeMap(); manualVisit = new ManualSystemVisit(controller, selectedSystem, true); return;
        }
        clickByLabel(Component.translatable("astraengine.map." + key).getString());
    }

    private void clickByLabel(String label) {
        require(minecraft.screen instanceof CosmosMapScreen, "Cosmos map is not active for widget selection");
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing cosmos button: " + label + " at step " + step));
        clickWidget(button);
    }

    private void clickWidget(AbstractWidget widget) {
        require(widget.active && widget.visible, "Cosmos widget unavailable: " + widget.getMessage().getString());
        Screen screen = minecraft.screen;
        double x = widget.getX() + widget.getWidth() / 2.0;
        double y = widget.getY() + widget.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Cosmos widget did not handle its click");
        screen.mouseReleased(x, y, 0);
    }

    private void closeMap() {
        if (minecraft.screen instanceof CosmosMapScreen map) { map.onClose(); }
    }

    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }

    private void shot(String name) throws Exception {
        minecraft.gui.getChat().clearMessages(false);
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage frame = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { frame.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_COSMOS_SCREENSHOT {}", name);
    }

    private void next() {
        AstraEngine.LOGGER.info("ASTRA_COSMOS_STEP {} complete", step);
        step++;
        ticks = 0;
        selectionReady = false;
    }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (cosmos step " + step + ", ticks " + ticks + ")"); }
    }
}
