package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SolarState;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.opengl.GL11;

/** Native input, independent HDR comparisons and persisted six-axis view in a disposable world. */
final class CameraScenario {
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private static final String NEIGHBOR = "s_-1_-1_0";
    private static final String BLACK_HOLE = "s_-1_-2_0";
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private ManualSystemVisit manualVisit;
    private FlightOrientation expectedOrientation;
    private FlightOrientation beforeRoll;
    private SpaceVector movementStart;
    private SpaceVector movementDirection;
    private SpaceVector stoppedPosition;
    private Properties checkpoint;
    private StellarEvolutionSnapshot solar;
    private long pausedClock;
    private int step;
    private int ticks;
    private int poleTurns;
    private int movementAxis;
    private int wallX;

    CameraScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = false;
        minecraft.options.fov().set(70);
        minecraft.options.sensitivity().set(0.5);
        minecraft.options.invertYMouse().set(false);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        AstraEngine.LOGGER.info("ASTRA_CAMERA_GRAPHICS {} transparency={} restart={}",
                minecraft.options.graphicsMode().get(), Minecraft.useShaderTransparency(), restart);
    }

    boolean tick() throws Exception {
        if (minecraft.screen instanceof ReceivingLevelScreen) { return false; }
        require(minecraft.screen == null || minecraft.screen instanceof CosmosMapScreen,
                "Flight input opened an unexpected screen: " + minecraft.screen);
        if (!pending.isDone()) { return false; }
        pending.join();
        if (manualVisit != null) {
            if (!manualVisit.tick()) { return false; }
            manualVisit = null;
        }
        ticks++;
        require(ticks < 1600, "Camera fixture step timed out");
        if (restart && minecraft.level.dimension().equals(Level.OVERWORLD) && step >= 5) { faceSun(); }
        return restart ? restartTick() : createTick();
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                server(this::prepareHome);
                command("astra-render bloom true");
                command("astra-render exposure 1");
                next();
            }
            case 1 -> {
                if (ticks < 35) { return false; }
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller();
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!active() || ticks < 35) { return false; }
                require(minecraft.mouseHandler.isMouseGrabbed() && minecraft.isWindowActive(),
                        "Native host did not capture flight input");
                require(controller.snapshot().speedMetersPerSecond() == 100, "Unexpected initial free-camera speed");
                wheel(1);
                expectedOrientation = controller.targetOrientation().rotateLocal(0, 120, 0);
                mouseDegrees(0, 120);
                next();
            }
            case 4 -> {
                if (ticks < 25) { return false; }
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.00001, "Raw mouse pole crossing");
                requireOrientation(controller.orientation(), expectedOrientation, 0.002, "Smoothed mouse pole crossing");
                verifyRenderedBasis();
                require(Math.abs(controller.snapshot().speedMetersPerSecond() - 150) < 0.000001,
                        "Wheel did not select the continuous 150 m/s speed");
                if (++poleTurns < 3) {
                    expectedOrientation = expectedOrientation.rotateLocal(0, 120, 0);
                    mouseDegrees(0, 120); ticks = 0; return false;
                }
                shot("camera-01-full-pitch-revolution");
                beforeRoll = controller.orientation();
                key(GLFW.GLFW_KEY_Q, true);
                next();
            }
            case 5 -> {
                if (ticks < 13 || controller.targetOrientation().up().dot(beforeRoll.up()) >= 0.9) {
                    require(ticks < 120, "Q did not roll the camera while its mapping was held");
                    return false;
                }
                AstraEngine.LOGGER.info("ASTRA_CAMERA_ROLL key=Q ticks={} upDot={}",
                        ticks, controller.targetOrientation().up().dot(beforeRoll.up()));
                key(GLFW.GLFW_KEY_Q, false);
                expectedOrientation = controller.targetOrientation();
                next();
            }
            case 6 -> {
                if (ticks < 15) { return false; }
                requireOrientation(controller.orientation(), expectedOrientation, 0.002, "Q roll convergence");
                verifyRenderedBasis();
                beforeRoll = controller.orientation();
                key(GLFW.GLFW_KEY_E, true);
                next();
            }
            case 7 -> {
                if (ticks < 7 || controller.targetOrientation().up().dot(beforeRoll.up()) >= 0.98) {
                    require(ticks < 120, "E did not reverse the camera roll while its mapping was held");
                    return false;
                }
                AstraEngine.LOGGER.info("ASTRA_CAMERA_ROLL key=E ticks={} upDot={}",
                        ticks, controller.targetOrientation().up().dot(beforeRoll.up()));
                key(GLFW.GLFW_KEY_E, false);
                expectedOrientation = controller.targetOrientation();
                server(server -> {
                    ServerPlayer player = player(server);
                    require(player.getMainHandItem().is(Items.DIAMOND) && player.getMainHandItem().getCount() == 16,
                            "Q dropped the held fixture diamonds");
                    require(player.serverLevel().getEntitiesOfClass(ItemEntity.class,
                            player.getBoundingBox().inflate(32)).isEmpty(), "Roll keys spawned a dropped item");
                });
                next();
            }
            case 8 -> {
                if (ticks < 15) { return false; }
                requireOrientation(controller.orientation(), expectedOrientation, 0.002, "E roll convergence");
                verifyRenderedBasis();
                shot("camera-02-rolled-view");
                beginMovement();
                next();
            }
            case 9 -> {
                if (ticks < 12) { return false; }
                key(movementKey(), false);
                next();
            }
            case 10 -> {
                if (ticks < 7) { return false; }
                SpaceVector traveled = controller.snapshot().position().subtract(movementStart);
                require(traveled.length() > 30, "Local translation did not move the virtual camera");
                require(traveled.normalized().dot(movementDirection) > 0.995,
                        "Translation did not follow the rolled local basis for axis " + movementAxis);
                require(controller.snapshot().velocity().length() == 0, "Released free-camera movement retained inertia");
                stoppedPosition = controller.snapshot().position();
                server(server -> {
                    ExplorationCatalog.Pilot pilot = ExplorationCatalog.get(server).player(player(server).getUUID());
                    require(pilot.velocity().length() == 0, "Server retained velocity after movement release");
                    require(player(server).position().distanceTo(new net.minecraft.world.phys.Vec3(8.5, 80, 8.5)) < 0.2,
                            "Free movement displaced the physical staging anchor");
                });
                AstraEngine.LOGGER.info("ASTRA_CAMERA_TRANSLATION axis={} meters={} directionDot={}",
                        movementAxis, traveled.length(), traveled.normalized().dot(movementDirection));
                next();
            }
            case 11 -> {
                if (ticks < 7) { return false; }
                require(controller.snapshot().position().equals(stoppedPosition), "Released camera drifted between snapshots");
                if (++movementAxis < 3) { beginMovement(); step = 9; ticks = 0; return false; }
                expectedOrientation = controller.targetOrientation();
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 12 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 8) { return false; }
                mouseDegrees(160, -140);
                next();
            }
            case 13 -> {
                if (ticks < 10) { return false; }
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.000001, "Map mouse isolation");
                ((CosmosMapScreen) minecraft.screen).onClose();
                next();
            }
            case 14 -> {
                if (ticks < 15) { return false; }
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.000001, "Mouse capture baseline reset");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 15 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 8) { return false; }
                click(Component.translatable("astraengine.map.local").getString());
                click("Sun");
                click(Component.translatable("astraengine.map.approach").getString());
                next();
            }
            case 16 -> {
                if (!settled(100)) { return false; }
                verifyTarget("sun");
                require(Math.abs(controller.roll()) < 0.1, "Approach did not clear the previous rolled attitude");
                command("astra-render bloom false");
                next();
            }
            case 17 -> {
                if (ticks < 12) { return false; }
                shot("camera-03-sun-bloom-off");
                command("astra-render bloom true");
                next();
            }
            case 18 -> {
                if (ticks < 12) { return false; }
                shot("camera-04-sun-bloom-on");
                command("astra-render exposure 0.5");
                next();
            }
            case 19 -> {
                if (ticks < 12) { return false; }
                shot("camera-05-sun-exposure-half");
                command("astra-render exposure 1.5");
                next();
            }
            case 20 -> {
                if (ticks < 12) { return false; }
                shot("camera-06-sun-exposure-high");
                command("astra-render exposure 1");
                controller.action(FlightActionPayload.Action.APPROACH_BODY, "earth");
                next();
            }
            case 21 -> {
                if (!settled(100)) { return false; }
                verifyTarget("earth");
                command("astra-render bloom false");
                next();
            }
            case 22 -> {
                if (ticks < 12) { return false; }
                shot("camera-07-earth-bloom-off");
                command("astra-render bloom true");
                next();
            }
            case 23 -> {
                if (ticks < 12) { return false; }
                shot("camera-08-earth-bloom-on");
                tap(GLFW.GLFW_KEY_C);
                next();
            }
            case 24 -> {
                if (ticks < 12 || !controller.snapshot().discoveredSystems().contains(NEIGHBOR)) { return false; }
                manualVisit = new ManualSystemVisit(controller, NEIGHBOR, true);
                next();
            }
            case 25 -> {
                if (!settled(100) || !controller.snapshot().systemId().equals(NEIGHBOR)) { return false; }
                tap(GLFW.GLFW_KEY_C);
                next();
            }
            case 26 -> {
                if (ticks < 12 || !controller.snapshot().discoveredSystems().contains(BLACK_HOLE)) { return false; }
                require(controller.system(BLACK_HOLE).kind() == CosmosSystem.Kind.BLACK_HOLE,
                        "Fixture's discovered black-hole descriptor changed");
                manualVisit = new ManualSystemVisit(controller, BLACK_HOLE, true);
                next();
            }
            case 27 -> {
                if (!settled(100) || !controller.snapshot().systemId().equals(BLACK_HOLE)) { return false; }
                verifyTarget("primary");
                command("astra-render bloom false");
                next();
            }
            case 28 -> {
                if (ticks < 12) { return false; }
                shot("camera-09-black-hole-bloom-off");
                command("astra-render bloom true");
                next();
            }
            case 29 -> {
                if (ticks < 12) { return false; }
                shot("camera-10-black-hole-bloom-on");
                expectedOrientation = controller.targetOrientation();
                diagnoseCamera("before-reload");
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 30 -> {
                if (ticks < 20) { return false; }
                diagnoseCamera("after-reload");
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.00001,
                        "Resource reload changed the free-camera target");
                verifyTarget("primary");
                shot("camera-11-hdr-resource-reload");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 31 -> {
                if (ticks < 20) { return false; }
                require(minecraft.getWindow().getWidth() == 960 && minecraft.getWindow().getHeight() == 540,
                        "HDR framebuffer resize did not reach its requested size");
                diagnoseCamera("after-resize");
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.00001,
                        "Framebuffer resize changed the free-camera target");
                verifyTarget("primary");
                shot("camera-12-hdr-resize");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                next();
            }
            case 32 -> {
                if (ticks < 20) { return false; }
                require(minecraft.getWindow().getWidth() == 1280 && minecraft.getWindow().getHeight() == 720,
                        "HDR framebuffer did not restore its original size");
                diagnoseCamera("after-size-restore");
                requireOrientation(controller.targetOrientation(), expectedOrientation, 0.00001,
                        "Framebuffer restoration changed the free-camera target");
                verifyTarget("primary");
                beforeRoll = controller.orientation();
                key(GLFW.GLFW_KEY_Q, true);
                next();
            }
            case 33 -> {
                if (ticks < 10 || controller.targetOrientation().up().dot(beforeRoll.up()) >= 0.95) {
                    require(ticks < 120, "Checkpoint Q hold did not create meaningful roll");
                    return false;
                }
                key(GLFW.GLFW_KEY_Q, false);
                next();
            }
            case 34 -> {
                if (ticks < 20) { return false; }
                require(controller.orientation().up().dot(beforeRoll.up()) < 0.95, "Checkpoint has no meaningful camera roll");
                shot("camera-13-rolled-black-hole");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 35 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen) || ticks < 20) { return false; }
                if (!controller.orientation().equals(controller.targetOrientation())
                        || !controller.snapshot().orientation().equals(controller.targetOrientation())) {
                    require(ticks < 120, "Checkpoint did not reach an exactly settled, server-acknowledged orientation");
                    return false;
                }
                diagnoseCamera("settled-checkpoint");
                server(this::captureCheckpoint);
                next();
            }
            case 36 -> {
                StringWriter text = new StringWriter();
                checkpoint.store(text, "Native free camera checkpoint: quaternion, continuous speed and private discoveries");
                Files.writeString(checkpointPath(), text.toString());
                return true;
            }
            default -> throw new IllegalStateException("Unexpected free camera step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                if (ticks < 35) { return false; }
                checkpoint = new Properties();
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                require(minecraft.level.dimension().equals(Level.OVERWORLD), "Interrupted camera did not recover its real home");
                server(this::verifyCheckpoint);
                shot("camera-14-recovered-home");
                next();
            }
            case 1 -> {
                if (ticks < 25) { return false; }
                server(server -> require(ExplorationCatalog.get(server).clockTicks() == pausedClock,
                        "Unoccupied camera clock advanced after restart"));
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || ticks < 10) { return false; }
                controller = map.controller();
                require(controller.snapshot() != null && !controller.active(), "Recovered camera retained active flight");
                require(controller.snapshot().orientation().equals(savedOrientation()), "Client snapshot lost the saved quaternion");
                require(controller.snapshot().speedMetersPerSecond() == Double.parseDouble(checkpoint.getProperty("speed")),
                        "Client snapshot lost continuous speed");
                map.onClose();
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 3 -> {
                if (!active() || ticks < 35) { return false; }
                require(controller.snapshot().position().equals(savedPosition()), "Re-entry reset the saved virtual position");
                requireOrientation(controller.orientation(), savedOrientation(), 0.00001, "Re-entry camera orientation");
                verifyRenderedBasis();
                shot("camera-15-fabulous-restored-roll");
                tap(GLFW.GLFW_KEY_R);
                next();
            }
            case 4 -> {
                if (!minecraft.level.dimension().equals(Level.OVERWORLD) || ticks < 25) { return false; }
                require(!controller.active(), "Normal camera exit retained flight rendering");
                server(server -> {
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Home build changed");
                    require(!player(server).getPersistentData().contains("astraengine_flight_recovery"), "Exit retained recovery data");
                    server.overworld().setDayTime(1000);
                });
                minecraft.options.hideGui = true;
                command("astra-render bloom false");
                next();
            }
            case 5 -> {
                if (ticks < 25) { return false; }
                shot("camera-16-overworld-bloom-off");
                command("astra-render bloom true");
                next();
            }
            case 6 -> {
                if (ticks < 12) { return false; }
                shot("camera-17-overworld-bloom-on");
                wallX = sunDirection().x() > 0 ? 12 : -12;
                server(server -> wall(server, true));
                next();
            }
            case 7 -> {
                if (ticks < 25) { return false; }
                require(minecraft.player.pick(40, 1, false).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK,
                        "Opaque HDR occlusion fixture does not intersect the Sun ray");
                shot("camera-18-hdr-foreground-occlusion");
                server(server -> wall(server, false));
                command("astra sun demo 10");
                next();
            }
            case 8 -> {
                if (solar == null || solar.phase() != StellarEvolutionSnapshot.Phase.CRITICAL) {
                    server(server -> solar = SolarState.get(server).snapshot()); return false;
                }
                shot("camera-19-hdr-critical");
                next();
            }
            case 9 -> {
                if (solar.phase() != StellarEvolutionSnapshot.Phase.SUPERNOVA || solar.phaseTicks() < 5) {
                    server(server -> solar = SolarState.get(server).snapshot()); return false;
                }
                shot("camera-20-hdr-supernova-flash");
                next();
            }
            case 10 -> {
                if (solar.phase() != StellarEvolutionSnapshot.Phase.SUPERNOVA || solar.phaseTicks() < 90) {
                    server(server -> solar = SolarState.get(server).snapshot()); return false;
                }
                shot("camera-21-hdr-supernova-ejecta");
                command("astra-render bloom false");
                next();
            }
            case 11 -> {
                if (ticks < 12) { return false; }
                shot("camera-22-supernova-bloom-off");
                command("astra-render bloom true");
                command("astra sun reset");
                next();
            }
            case 12 -> {
                if (ticks < 25) { return false; }
                shot("camera-23-fabulous-restored-sun");
                server(server -> {
                    require(SolarState.get(server).snapshot().remaining() == StellarEvolutionSnapshot.CAPACITY,
                            "Final reset did not restore the diagnostic Sun");
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                            "HDR event damaged the real build");
                });
                next();
            }
            case 13 -> { return true; }
            default -> throw new IllegalStateException("Unexpected free camera restart step " + step);
        }
        return false;
    }

    private void prepareHome(MinecraftServer server) {
        server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.overworld().setDayTime(1000);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        ServerPlayer player = player(server);
        player.teleportTo(0.5, 200, 0.5);
        player.getInventory().setItem(player.getInventory().selected, new ItemStack(Items.DIAMOND, 16));
        player.containerMenu.broadcastChanges();
    }

    private void beginMovement() {
        movementStart = controller.snapshot().position();
        movementDirection = switch (movementAxis) {
            case 0 -> controller.orientation().forward();
            case 1 -> controller.orientation().left();
            default -> controller.orientation().up();
        };
        key(movementKey(), true);
    }

    private int movementKey() {
        return switch (movementAxis) {
            case 0 -> GLFW.GLFW_KEY_W;
            case 1 -> GLFW.GLFW_KEY_A;
            default -> GLFW.GLFW_KEY_SPACE;
        };
    }

    private void verifyRenderedBasis() {
        var camera = minecraft.gameRenderer.getMainCamera();
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        require(controller.orientation().forward().dot(new SpaceVector(look.x, look.y, look.z)) > 0.9999,
                "Host camera forward differs from the six-axis orientation");
        require(controller.orientation().up().dot(new SpaceVector(up.x, up.y, up.z)) > 0.9999,
                "Host camera up differs from the rolled orientation");
    }

    private void verifyTarget(String id) {
        CelestialBody body = controller.currentSystem().bodies().stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow();
        SpaceVector direction = body.positionAt(controller.timeSeconds()).subtract(controller.visualPosition()).normalized();
        double alignment = direction.dot(controller.orientation().forward());
        if (alignment <= 0.999) { diagnoseCamera("target-mismatch-" + id); }
        require(alignment > 0.999, "Approach failed to face " + id + ", dot=" + alignment);
        verifyRenderedBasis();
    }

    private void diagnoseCamera(String stage) {
        var camera = minecraft.gameRenderer.getMainCamera();
        AstraEngine.LOGGER.info("ASTRA_CAMERA_DIAGNOSTIC stage={} target={} displayed={} server={} hostLook={} hostUp={} "
                        + "mouse=({}, {}) grabbed={} focused={} overlay={} screen={} position={} epoch={} revision={}",
                stage, controller.targetOrientation(), controller.orientation(), controller.snapshot().orientation(),
                camera.getLookVector(), camera.getUpVector(), minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos(),
                minecraft.mouseHandler.isMouseGrabbed(), minecraft.isWindowActive(), minecraft.getOverlay(), minecraft.screen,
                controller.snapshot().position(), controller.snapshot().navigationEpoch(), controller.snapshot().revision());
    }

    private void captureCheckpoint(MinecraftServer server) {
        ServerPlayer player = player(server);
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        require(RocketService.isFlightWorld(player) && player.getPersistentData().contains("astraengine_flight_recovery"),
                "Active checkpoint lacks its real-world recovery point");
        require(pilot.velocity().equals(SpaceVector.ZERO), "Checkpoint requires a stationary camera");
        require(Math.abs(pilot.orientation().roll()) > 10, "Checkpoint does not retain nonzero roll");
        checkpoint = new Properties();
        checkpoint.setProperty("system", pilot.systemId());
        checkpoint.setProperty("x", Double.toString(pilot.position().x()));
        checkpoint.setProperty("y", Double.toString(pilot.position().y()));
        checkpoint.setProperty("z", Double.toString(pilot.position().z()));
        checkpoint.setProperty("qx", Double.toString(pilot.orientation().x()));
        checkpoint.setProperty("qy", Double.toString(pilot.orientation().y()));
        checkpoint.setProperty("qz", Double.toString(pilot.orientation().z()));
        checkpoint.setProperty("qw", Double.toString(pilot.orientation().w()));
        checkpoint.setProperty("speed", Double.toString(pilot.speedMetersPerSecond()));
        checkpoint.setProperty("discoveries", String.join(",", pilot.discoveredSystems()));
        checkpoint.setProperty("visited", String.join(",", pilot.visitedSystems()));
        checkpoint.setProperty("clock", Long.toString(catalog.clockTicks()));
    }

    private void verifyCheckpoint(MinecraftServer server) {
        ServerPlayer player = player(server);
        ExplorationCatalog catalog = ExplorationCatalog.get(server);
        ExplorationCatalog.Pilot pilot = catalog.player(player.getUUID());
        require(String.join(",", pilot.visitedSystems()).equals(checkpoint.getProperty("visited")), "Persisted visits changed");
        require(pilot.systemId().equals(checkpoint.getProperty("system")), "Persisted system changed");
        require(pilot.position().equals(savedPosition()), "Persisted double position changed");
        require(pilot.orientation().equals(savedOrientation()), "Persisted quaternion changed");
        require(pilot.speedMetersPerSecond() == Double.parseDouble(checkpoint.getProperty("speed")), "Persisted speed changed");
        require(String.join(",", pilot.discoveredSystems()).equals(checkpoint.getProperty("discoveries")), "Discoveries changed");
        require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Persisted real home build changed");
        require(player.position().distanceTo(new net.minecraft.world.phys.Vec3(0.5, 200, 0.5)) < 0.2,
                "Interrupted flight did not restore its real home position");
        require(!player.getPersistentData().contains("astraengine_flight_recovery"), "Recovery tag survived successful login recovery");
        pausedClock = catalog.clockTicks();
        AstraEngine.LOGGER.info("ASTRA_CAMERA_PERSISTENCE quaternion={} speed={} discoveries={}",
                pilot.orientation(), pilot.speedMetersPerSecond(), pilot.discoveredSystems().size());
    }

    private FlightOrientation savedOrientation() {
        return new FlightOrientation(value("qx"), value("qy"), value("qz"), value("qw"));
    }
    private SpaceVector savedPosition() { return new SpaceVector(value("x"), value("y"), value("z")); }
    private double value(String key) { return Double.parseDouble(checkpoint.getProperty(key)); }
    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("camera-checkpoint.properties"); }
    private boolean active() { return controller != null && controller.active(); }
    private boolean settled(int minimum) { return active() && ticks >= minimum && controller.snapshot().jumpTicks() == 0; }
    private ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }

    private void wall(MinecraftServer server, boolean enabled) {
        for (int z = -7; z <= 7; z++) {
            for (int y = 199; y <= 222; y++) {
                server.overworld().setBlockAndUpdate(new BlockPos(wallX, y, z),
                        enabled ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private SpaceVector sunDirection() {
        double angle = minecraft.level.getSunAngle(1);
        return new SpaceVector(-Math.sin(angle), Math.cos(angle), 0);
    }

    private void faceSun() {
        SpaceVector direction = sunDirection();
        minecraft.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x(), direction.z())));
        minecraft.player.setXRot((float) -Math.toDegrees(Math.asin(direction.y())));
    }

    private void key(int key, boolean down) {
        minecraft.keyboardHandler.keyPress(minecraft.getWindow().getWindow(), key, 0,
                down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        if (key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_E) {
            String name = "key.astraengine.flight." + (key == GLFW.GLFW_KEY_Q ? "roll_left" : "roll_right");
            var mapping = java.util.Arrays.stream(minecraft.options.keyMappings)
                    .filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
            require(mapping.isDown() == down, "Host key dispatch did not update the roll mapping: " + name);
        }
    }
    private void tap(int key) { key(key, true); key(key, false); }

    /** Invokes Minecraft's installed cursor callback; no private fields or production input shortcuts. */
    private void mouseDegrees(double yaw, double pitch) {
        double sensitivity = Math.pow(minecraft.options.sensitivity().get() * 0.6 + 0.2, 3) * 8 * 0.15;
        long window = minecraft.getWindow().getWindow();
        GLFWCursorPosCallback callback = GLFW.glfwSetCursorPosCallback(window, null);
        require(callback != null, "Minecraft cursor callback is missing");
        GLFW.glfwSetCursorPosCallback(window, callback);
        callback.invoke(window, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
        callback.invoke(window, minecraft.mouseHandler.xpos() + yaw / sensitivity,
                minecraft.mouseHandler.ypos() + pitch / sensitivity);
    }

    private void wheel(double delta) {
        InputEvent.MouseScrollingEvent event = new InputEvent.MouseScrollingEvent(0, delta, false, false, false,
                minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
        NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "Free camera did not consume the speed wheel");
    }

    private void click(String label) {
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)
                        || value.getMessage().getString().equals("> " + label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing camera fixture map button " + label));
        require(button.active && button.visible, "Camera fixture map button is disabled");
        var screen = minecraft.screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Camera fixture map click was not consumed");
        screen.mouseReleased(x, y, 0);
    }

    private void shot(String name) throws Exception {
        minecraft.gui.getChat().clearMessages(false);
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after " + name);
        AstraEngine.LOGGER.info("ASTRA_CAMERA_SCREENSHOT {}", name);
    }

    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_CAMERA_STEP {} complete", step);
        step++;
        ticks = 0;
    }
    private void requireOrientation(FlightOrientation actual, FlightOrientation expected, double tolerance, String name) {
        require(actual.forward().distance(expected.forward()) < tolerance && actual.up().distance(expected.up()) < tolerance,
                name + " differs: actual=" + actual + ", expected=" + expected);
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (camera step " + step + ", ticks " + ticks + ")"); }
    }
}
