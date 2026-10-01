package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.network.ExplorationPayload;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.network.FlightActionPayload;
import dev.lexawhatt.astraengine.network.SurfacePayload;
import dev.lexawhatt.astraengine.network.SurfaceReceivedEvent;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.PreparedPlayerReturn;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.server.SurfaceBindings;
import dev.lexawhatt.astraengine.server.SurfaceWorlds;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfaceGeography;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Disposable real packet-driven planetary handoffs, saved terrain, cancellation and process-restart checks. */
final class SurfaceScenario {
    private static final BlockPos HOME_MARKER = new BlockPos(0, 199, 0);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final String phase;
    private final StringBuilder observations = new StringBuilder(
            "event\tframe\tstep\tbody\tphase\tremaining\tclock\tdimension\tphysical_position\tvirtual_position\n");
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
        if (minecraft.level != null && minecraft.getOverlay() == null && minecraft.screen == null) {
            this.clearWorldFrames++;
        } else {
            this.clearWorldFrames = 0;
        }
    };
    private final Consumer<SurfaceReceivedEvent> surfaceListener = event -> {
        this.surface = event.payload();
        if (this.rejectPostDeathCommit && this.surface.phase() == SurfacePayload.Phase.SURFACE) {
            this.unexpectedPostDeathCommit = true;
        }
        if (this.landingDefinition != null && this.landingAcceptedClock < 0
                && this.surface.bodyId().equals(this.landingDefinition.bodyId())
                && this.surface.phase() == SurfacePayload.Phase.PREPARING) {
            this.landingAcceptedClock = this.surface.clockTicks();
            this.landingBodyStart = this.landingDefinition.frame(this.controller.currentSystem(),
                    this.landingAcceptedClock / 20.0, this.landingAcceptedClock).toBodyPoint(this.landingStart);
        }
        append("snapshot");
    };
    private final Consumer<ExplorationReceivedEvent> explorationListener = event -> {
        ExplorationPayload snapshot = event.payload();
        if (this.awaitLandingCancellation && snapshot.active() && snapshot.jumpTicks() == 0
                && snapshot.navigationEpoch() != this.landingEpoch) {
            this.canceledLandingSnapshot = snapshot;
            this.awaitLandingCancellation = false;
        }
    };
    private final Consumer<EntityTravelToDimensionEvent> travelListener = this::rejectOneLanding;
    private final Set<String> transitionCaptures = new HashSet<>();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private SurfacePayload surface;
    private Properties checkpoint = new Properties();
    private String upgradeReport;
    private BlockPos moonMarker;
    private BlockPos earthMarker;
    private SpaceVector landingStart;
    private SpaceVector landingBodyStart;
    private SurfaceDefinition landingDefinition;
    private ExplorationPayload canceledLandingSnapshot;
    private long landingSendClock;
    private long landingAcceptedClock = -1;
    private long landingEpoch;
    private boolean awaitLandingCancellation;
    private final UUID pilotId;
    private volatile boolean rejectNextLanding;
    private volatile int rejectedLandings;
    private Vec3 takeoffStart;
    private BlockPos returnObstruction;
    private final Map<BlockPos, BlockState> returnNeighborhood = new LinkedHashMap<>();
    private boolean obstructionReturnVerified;
    private boolean rejectPostDeathCommit;
    private boolean unexpectedPostDeathCommit;
    private boolean deathCleanupVerified;
    private int boundaryCase;
    private int boundaryRejections;
    private Vec3 boundarySource;
    private final Map<BlockPos, BlockState> quarryColumns = new LinkedHashMap<>();
    private long renderedFrames;
    private int clearWorldFrames;
    private long firstFrame;
    private int step;
    private int ticks;
    private int operation;
    private boolean observedDescent;
    private boolean observedAscent;

    SurfaceScenario(String phase, SurfacePayload initialSurface) {
        this.phase = phase;
        pilotId = minecraft.player.getUUID();
        surface = initialSurface;
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, surfaceListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, explorationListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, travelListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 2400, "Surface fixture stage exceeded its bounded timeout");
            captureTransition();
            boolean complete = switch (phase) {
                case "surface-restart" -> restartTick();
                case "surface-recover" -> recoverTick();
                case "surface-upgrade" -> upgradeTick();
                case "surface-boundaries" -> boundariesTick();
                default -> createTick();
            };
            if (complete) {
                retain();
                dispose();
                AstraEngine.LOGGER.info("ASTRA_SURFACE_PASSED phase={} frames={} graphics={}",
                        phase, renderedFrames, minecraft.options.graphicsMode().get());
            }
            return complete;
        } catch (Exception failure) {
            append("failure:" + failure.getMessage());
            retain();
            dispose();
            throw failure;
        }
    }

    private boolean createTick() throws Exception {
        if (phase.equals("surface-failures") && step >= 90) { return failuresTick(); }
        switch (step) {
            case 0 -> {
                server(this::prepareHome);
                command("astra-render environment auto");
                command("astra-render quality balanced");
                command("astra-render bloom true");
                command("astra-render exposure 1");
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 1 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(10)) { return false; }
                controller = map.controller(); map.onClose();
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 2 -> {
                if (!manualFlight() || !ready(30)) { return false; }
                action(FlightActionPayload.Action.APPROACH_BODY, "moon");
                next();
            }
            case 3 -> {
                if (!controller.snapshot().approaching()) {
                    require(ticks < 100, "Server did not begin the requested Moon approach"); return false;
                }
                next();
            }
            case 4 -> {
                if (!manualFlight() || !ready(15)) { return false; }
                shot("01-moon-orbit-before-entry");
                land("moon");
                next();
            }
            case 5 -> {
                if (phase.equals("surface-interrupt") && descending("moon") && surface.remainingTicks() <= 400) {
                    shot("interrupt-in-authoritative-descent");
                    checkpoint.setProperty("expected_recovery", "overworld");
                    server(server -> server.saveEverything(false, true, true));
                    moveTo(80); return false;
                }
                if (phase.equals("surface-cancel") && descending("moon") && surface.remainingTicks() <= 400) {
                    shot("cancel-before-landing-commit");
                    awaitLandingCancellation = true;
                    action(FlightActionPayload.Action.BRAKE, "");
                    moveTo(60); return false;
                }
                if (!onSurface("moon") || !ready(20)) { return false; }
                require(observedDescent, "Arrival lacked authoritative DESCENDING samples");
                server(server -> moonMarker = placeMarker(server, "moon", Blocks.DIAMOND_BLOCK.defaultBlockState()));
                groundLook(25, -8);
                next();
            }
            case 6 -> {
                if (!ready(30)) { return false; }
                groundLook(25, -8);
                shot("02-moon-real-terrain-marker");
                takeoffStart = minecraft.player.position();
                takeOff();
                if (phase.equals("surface-failures")) { moveTo(90); } else { next(); }
            }
            case 7 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                require(observedAscent, "Departure lacked authoritative ASCENDING samples");
                shot("03-moon-orbit-after-departure");
                land("moon"); next();
            }
            case 8 -> {
                if (!onSurface("moon") || !ready(25)) { return false; }
                server(server -> verifyMarker(server, "moon", moonMarker, Blocks.DIAMOND_BLOCK.defaultBlockState()));
                next();
            }
            case 9 -> {
                if (!ready(25)) { return false; }
                shot("04-moon-return-retained-marker");
                takeOff(); next();
            }
            case 10 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                action(FlightActionPayload.Action.APPROACH_BODY, "earth"); next();
            }
            case 11 -> {
                if (!controller.snapshot().approaching()) {
                    require(ticks < 100, "Server did not begin the requested Earth approach"); return false;
                }
                next();
            }
            case 12 -> {
                if (!manualFlight() || !ready(15)) { return false; }
                shot("05-earth-orbit-before-entry");
                land("earth"); next();
            }
            case 13 -> {
                if (!onSurface("earth") || !ready(25)) { return false; }
                server(server -> earthMarker = placeMarker(server, "earth", Blocks.EMERALD_BLOCK.defaultBlockState()));
                groundLook(55, -8);
                next();
            }
            case 14 -> {
                if (!ready(25)) { return false; }
                groundLook(55, -8);
                shot("06-earth-real-terrain-marker");
                pending = minecraft.reloadResourcePacks(); next();
            }
            case 15 -> {
                if (!onSurface("earth") || !ready(25)) { return false; }
                shot("07-earth-resource-reload");
                server(server -> verifyMarker(server, "earth", earthMarker, Blocks.EMERALD_BLOCK.defaultBlockState()));
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 960, 540);
                next();
            }
            case 16 -> {
                if (!ready(25) || minecraft.getWindow().getWidth() != 960) { return false; }
                shot("08-earth-resized");
                GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 720);
                next();
            }
            case 17 -> {
                if (!ready(25) || minecraft.getWindow().getWidth() != 1280) { return false; }
                takeOff(); next();
            }
            case 18 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                shot("09-earth-orbit-after-departure");
                land("earth"); next();
            }
            case 19 -> {
                if (!onSurface("earth") || !ready(25)) { return false; }
                server(server -> {
                    verifyMarker(server, "earth", earthMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                    verifyMarker(server, "moon", moonMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    server.tickRateManager().setFrozen(true);
                    recordCheckpoint(server);
                    server.saveEverything(false, true, true);
                });
                next();
            }
            case 20 -> {
                if (!ready(15)) { return false; }
                shot("10-earth-final-persisted-surface");
                writeCheckpoint("surface-checkpoint.properties");
                return true;
            }
            case 60 -> {
                if (!manualFlight() || !ready(15)) { return false; }
                verifyCanceledLanding();
                shot("cancel-restored-orbital-source");
                rejectNextLanding = true;
                land("moon");
                awaitLandingCancellation = true;
                next();
            }
            case 61 -> {
                if (rejectedLandings != 1 || !manualFlight() || !ready(15)) { return false; }
                verifyCanceledLanding();
                require(!rejectNextLanding, "Host travel veto was not consumed exactly once");
                server(server -> {
                    ServerPlayer player = player(server);
                    require(RocketService.isFlightWorld(player), "Canceled host travel committed a surface world");
                    require(player.getPersistentData().contains("astraengine_flight_recovery"),
                            "Canceled host travel discarded recovery before a real dimension commit");
                });
                shot("host-veto-kept-flight-and-safe-source");
                moveTo(65);
            }
            case 65 -> { land("moon"); next(); }
            case 66 -> {
                if (!onSurface("moon") || !ready(25)) { return false; }
                require(rejectedLandings == 1, "One-shot veto rejected the legitimate retry");
                shot("host-veto-retry-committed-real-surface");
                takeoffStart = minecraft.player.position();
                takeOff(); moveTo(62);
            }
            case 62 -> {
                if (surface == null || surface.phase() != SurfacePayload.Phase.ASCENDING
                        || surface.remainingTicks() > 400) { return false; }
                shot("cancel-during-authoritative-ascent");
                action(FlightActionPayload.Action.BRAKE, ""); next();
            }
            case 63 -> {
                if (!onSurface("moon") || !ready(25)) { return false; }
                require(minecraft.player.position().distanceTo(takeoffStart) < 0.5,
                        "Canceled takeoff did not restore the committed surface source");
                shot("cancel-restored-surface-source");
                server(server -> require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                        "Canceled transition changed the original world"));
                next();
            }
            case 64 -> { return true; }
            case 80 -> {
                writeCheckpoint("surface-interrupt-checkpoint.properties");
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface creation step " + step);
        }
        return false;
    }

    private boolean failuresTick() throws Exception {
        switch (step) {
            case 90 -> {
                if (surface == null || surface.phase() != SurfacePayload.Phase.ASCENDING
                        || surface.remainingTicks() > 400) { return false; }
                server(this::obstructReturnSource);
                next();
            }
            case 91 -> {
                shot("failures-01-ascent-source-obstructed");
                action(FlightActionPayload.Action.BRAKE, "");
                next();
            }
            case 92 -> {
                if (!onSurface("moon") || !ready(20)) { return false; }
                server(this::verifyObstructedReturn);
                next();
            }
            case 93 -> {
                if (!ready(12)) { return false; }
                groundLook(135, 55);
                shot("failures-02-safe-return-retained-obstruction");
                takeOff();
                next();
            }
            case 94 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                land("moon");
                next();
            }
            case 95 -> {
                if (!descending("moon") || surface.remainingTicks() > 400) { return false; }
                shot("failures-03-descent-before-death");
                rejectPostDeathCommit = true;
                server(server -> {
                    ServerPlayer player = player(server);
                    require(RocketService.isFlightWorld(player) && player.isAlive(),
                            "Death fixture did not reach active flight staging");
                    player.setRespawnPosition(Level.OVERWORLD, new BlockPos(0, 200, 0), 0, true, false);
                    player.kill();
                    require(!player.isAlive(), "Verification kill did not invoke real host death");
                });
                next();
            }
            case 96 -> {
                if (!(minecraft.screen instanceof DeathScreen) || !ready(25)) { return false; }
                require(!controller.active() && surface != null && surface.phase() == SurfacePayload.Phase.NONE,
                        "Dead observer retained an active landing presentation");
                server(server -> {
                    ServerPlayer player = player(server);
                    require(!player.isAlive() && RocketService.isFlightWorld(player),
                            "Death committed the unfinished landing to a real surface");
                    require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                            "Death retained a stale flight recovery owner");
                    verifyReturnBlocks(server);
                    deathCleanupVerified = true;
                });
                shot("failures-04-host-death-screen-no-surface-commit");
                next();
            }
            case 97 -> {
                require(minecraft.screen instanceof DeathScreen, "Host death screen disappeared before Respawn");
                DeathScreen screen = (DeathScreen) minecraft.screen;
                Button respawn = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getMessage().getString().equals(
                                Component.translatable("deathScreen.respawn").getString()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Host Respawn button is missing"));
                if (!respawn.active || !respawn.visible) { return false; }
                double x = respawn.getX() + respawn.getWidth() / 2.0;
                double y = respawn.getY() + respawn.getHeight() / 2.0;
                require(screen.mouseClicked(x, y, 0), "Host Respawn button did not handle its click");
                screen.mouseReleased(x, y, 0);
                next();
            }
            case 98 -> {
                if (!minecraft.player.isAlive() || minecraft.screen != null
                        || !minecraft.level.dimension().equals(Level.OVERWORLD) || !ready(30)) { return false; }
                require(!controller.active() && surface != null && surface.phase() == SurfacePayload.Phase.NONE,
                        "Respawn retained an orphaned surface route");
                server(this::verifyFailureHome);
                groundLook(135, 65);
                shot("failures-05-respawn-original-world-without-recovery");
                next();
            }
            case 99 -> {
                if (!ready(80)) { return false; }
                require(!unexpectedPostDeathCommit, "Canceled dead observer later committed a surface transition");
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 100 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                shot("failures-06-new-flight-after-respawn");
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 101 -> {
                if (controller.active() || !minecraft.level.dimension().equals(Level.OVERWORLD)
                        || surface == null || surface.phase() != SurfacePayload.Phase.NONE || !ready(25)) { return false; }
                server(this::verifyFailureHome);
                shot("failures-07-new-session-return-completed");
                next();
            }
            case 102 -> {
                require(obstructionReturnVerified && deathCleanupVerified && !unexpectedPostDeathCommit,
                        "Failure phase did not complete both obstruction and death contracts");
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface failure step " + step);
        }
        return false;
    }

    private boolean boundariesTick() throws Exception {
        switch (step) {
            case 0 -> {
                server(server -> { prepareHome(server); prepareBoundaryFixtures(server); });
                tap(GLFW.GLFW_KEY_M);
                next();
            }
            case 1 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(10)) { return false; }
                controller = map.controller(); map.onClose();
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 2 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 3 -> {
                if (controller.active() || !minecraft.level.dimension().equals(Level.OVERWORLD) || !ready(20)) { return false; }
                server(server -> configureBoundarySource(server, boundaryCase));
                next();
            }
            case 4 -> {
                if (!ready(10)) { return false; }
                server(server -> verifyInvalidBoundarySource(server, boundaryCase));
                next();
            }
            case 5 -> { action(FlightActionPayload.Action.TOGGLE, ""); next(); }
            case 6 -> {
                if (!ready(20)) { return false; }
                require(!controller.active() && minecraft.level.dimension().equals(Level.OVERWORLD),
                        "Invalid source acquired live flight presentation: " + boundaryCase);
                server(server -> {
                    verifyInvalidBoundarySource(server, boundaryCase);
                    require(!player(server).getPersistentData().contains("astraengine_flight_recovery"),
                            "Rejected source retained an entered flight recovery owner");
                    boundaryRejections++;
                    AstraEngine.LOGGER.info("ASTRA_SURFACE_SOURCE_REJECTED case={} actual={}",
                            boundaryCase, player(server).position());
                });
                shot("boundaries-source-" + boundaryCase + "-rejected");
                next();
            }
            case 7 -> {
                if (++boundaryCase < 4) {
                    minecraft.options.keyShift.setDown(boundaryCase == 3);
                    server(server -> configureBoundarySource(server, boundaryCase));
                    moveTo(4);
                } else {
                    minecraft.options.keyShift.setDown(false);
                    server(server -> {
                        ServerPlayer player = player(server);
                        player.setShiftKeyDown(false); player.setNoGravity(false); player.setPose(Pose.STANDING);
                        player.getAbilities().flying = false; player.onUpdateAbilities();
                        player.teleportTo(0.5, 200, 0.5);
                    });
                    next();
                }
            }
            case 8 -> {
                if (!ready(20)) { return false; }
                action(FlightActionPayload.Action.TOGGLE, "");
                next();
            }
            case 9 -> {
                if (!manualFlight() || !ready(20)) { return false; }
                require(boundaryRejections == 4, "Not all invalid source boundaries were exercised");
                shot("boundaries-valid-source-still-enters");
                server(this::prepareQuarry);
                next();
            }
            case 10 -> { action(FlightActionPayload.Action.APPROACH_BODY, "moon"); next(); }
            case 11 -> {
                if (!controller.snapshot().approaching()) {
                    require(ticks < 100, "Boundary fixture did not begin the real Moon approach"); return false;
                }
                next();
            }
            case 12 -> {
                if (!manualFlight() || !ready(15)) { return false; }
                require(minecraft.screen == null && minecraft.isWindowActive(),
                        "Host landing-key check requires an active window without a screen");
                require(controller.targetBody().equals("moon"), "Actual Moon approach did not select its landing target");
                recordLandingStart("moon");
                minecraft.keyboardHandler.keyPress(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_L, 0, GLFW.GLFW_PRESS, 0);
                minecraft.keyboardHandler.keyPress(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_L, 0, GLFW.GLFW_RELEASE, 0);
                next();
            }
            case 13 -> {
                require(minecraft.screen == null, "Landing key opened a host screen instead of starting descent");
                if (ticks >= 80) { require(observedDescent, "Actual host L dispatch did not start the requested Moon descent"); }
                if (!onSurface("moon") || !ready(20)) { return false; }
                server(this::verifyQuarryLanding);
                groundLook(135, 45);
                next();
            }
            case 14 -> {
                if (!ready(15)) { return false; }
                shot("boundaries-moon-landed-beside-retained-quarry");
                require(boundaryRejections == 4 && !quarryColumns.isEmpty(), "Boundary phase lacks required evidence");
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface boundary step " + step);
        }
        return false;
    }

    private void prepareBoundaryFixtures(MinecraftServer server) {
        var level = server.overworld();
        // A closed deep-water chamber and a 1.5-meter top-slab tunnel are verification-only edits.
        for (int x = 6; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 199; y <= 205; y++) {
                    boolean wall = x == 6 || x == 10 || z == -2 || z == 2 || y == 199 || y == 205;
                    level.setBlockAndUpdate(new BlockPos(x, y, z),
                            (wall ? Blocks.STONE : Blocks.WATER).defaultBlockState());
                }
            }
        }
        for (int x = 15; x <= 17; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 200, z), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 201, z),
                        Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            }
        }
    }

    private void configureBoundarySource(MinecraftServer server, int index) {
        ServerPlayer player = player(server);
        require(player.serverLevel() == server.overworld(), "Boundary source left the retained home dimension");
        boundarySource = switch (index) {
            case 0 -> new Vec3(0.5, 400, 0.5);
            case 1 -> new Vec3(8.5, 200, 0.5);
            case 2 -> new Vec3(29_999_984.5, 200, 0.5);
            case 3 -> new Vec3(16.5, 200, 0.5);
            default -> throw new IllegalArgumentException("Unknown boundary source " + index);
        };
        player.setNoGravity(true);
        player.setShiftKeyDown(index == 3);
        player.getAbilities().flying = index != 3; player.onUpdateAbilities();
        player.setPose(index == 3 ? Pose.CROUCHING : Pose.STANDING);
        player.teleportTo(boundarySource.x, boundarySource.y, boundarySource.z);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void verifyInvalidBoundarySource(MinecraftServer server, int index) {
        ServerPlayer player = player(server);
        var level = player.serverLevel();
        require(level == server.overworld() && player.position().distanceTo(boundarySource) < 0.5,
                "Host did not retain the intended boundary source: " + index + " at " + player.position());
        switch (index) {
            case 0 -> require(player.getY() > level.getMaxBuildHeight(), "High-Y source is inside build height");
            case 1 -> require(level.containsAnyLiquid(player.getBoundingBox()), "Deep-water source is not submerged");
            case 2 -> require(player.getX() >= 29_999_984, "Horizontal source did not reach the return bound");
            case 3 -> {
                require(player.getPose() == Pose.CROUCHING && level.noCollision(player, player.getBoundingBox()),
                        "Tunnel does not admit the intended crouched source pose");
                require(!level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(player.position())),
                        "Tunnel unexpectedly admits a standing return volume");
            }
            default -> throw new IllegalArgumentException("Unknown boundary source " + index);
        }
        require(!PreparedPlayerReturn.acceptsSource(server, player), "Unsafe source passed the production admission guard");
    }

    private void prepareQuarry(MinecraftServer server) {
        var level = server.getLevel(SurfaceWorlds.dimension(SurfaceDefinition.byBody("moon")));
        // Bounded setup of four host chunks only; this fixture does not measure preparation latency.
        for (int x = -1; x <= 0; x++) {
            for (int z = -1; z <= 0; z++) { level.getChunk(x, z); }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                require(level.getBlockState(new BlockPos(x, 0, z)).is(Blocks.BEDROCK), "Quarry lacks original bedrock");
                for (int y = 1; y < 256; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y < 256; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    quarryColumns.put(position, level.getBlockState(position));
                }
            }
        }
        AstraEngine.LOGGER.info("ASTRA_SURFACE_QUARRY_PREPARED columns=9 firstAirY=1 retainedColumns=49");
    }

    private void verifyQuarryLanding(MinecraftServer server) {
        ServerPlayer player = player(server);
        SurfaceDefinition definition = SurfaceDefinition.byBody("moon");
        var level = player.serverLevel();
        require(SurfaceBindings.get(server).matches(level, definition), "Quarry arrival selected the wrong geographic world");
        require(player.getEyeY() >= definition.patch().seaY() + SurfaceGeography.MIN_HEIGHT_METERS,
                "Landing committed below supported geographic altitude");
        int ring = Math.max(Math.abs(player.blockPosition().getX()), Math.abs(player.blockPosition().getZ()));
        require(ring >= 2 && ring <= 14, "Landing did not choose a nearby column outside the unsupported deep pit");
        require(level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(player.position()))
                        && !level.containsAnyLiquid(player.getBoundingBox()),
                "Quarry fallback did not admit a dry standing observer");
        for (var entry : quarryColumns.entrySet()) {
            require(level.getBlockState(entry.getKey()).equals(entry.getValue()),
                    "Landing mutated the retained quarry or neighboring block at " + entry.getKey());
        }
        require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                "Committed quarry fallback retained old recovery ownership");
        AstraEngine.LOGGER.info("ASTRA_SURFACE_QUARRY_LANDING_PASSED position={} ring={} unchangedBlocks={}",
                player.position(), ring, quarryColumns.size());
    }

    private void obstructReturnSource(MinecraftServer server) {
        ServerPlayer player = player(server);
        require(RocketService.isFlightWorld(player), "Obstruction must be placed after the actual ascent handoff");
        CompoundTag recovery = player.getPersistentData().getCompound("astraengine_flight_recovery");
        var definition = SurfaceDefinition.byBody("moon");
        require(recovery.getString("dimension").equals(SurfaceWorlds.dimension(definition).location().toString())
                        && recovery.contains("x", Tag.TAG_DOUBLE) && recovery.contains("y", Tag.TAG_DOUBLE)
                        && recovery.contains("z", Tag.TAG_DOUBLE),
                "Ascent lacks an exact saved Moon return point");
        takeoffStart = new Vec3(recovery.getDouble("x"), recovery.getDouble("y"), recovery.getDouble("z"));
        returnObstruction = BlockPos.containing(takeoffStart);
        var level = server.getLevel(SurfaceWorlds.dimension(definition));
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos position = returnObstruction.offset(x, y, z);
                    returnNeighborhood.put(position, level.getBlockState(position));
                }
            }
        }
        require(level.getBlockState(returnObstruction).isAir() && level.getBlockState(returnObstruction.above()).isAir(),
                "Original return point was obstructed before the verification intervention");
        level.setBlockAndUpdate(returnObstruction, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(returnObstruction.above(), Blocks.STONE.defaultBlockState());
        AstraEngine.LOGGER.info("ASTRA_SURFACE_RETURN_OBSTRUCTED source={} pillar={}", takeoffStart, returnObstruction);
    }

    private void verifyObstructedReturn(MinecraftServer server) {
        ServerPlayer player = player(server);
        var level = player.serverLevel();
        var definition = SurfaceDefinition.byBody("moon");
        require(SurfaceBindings.get(server).matches(level, definition), "Obstructed return entered a different real world");
        double distance = player.position().distanceTo(takeoffStart);
        require(distance > 0.25 && distance <= 13,
                "Obstructed source was used unchanged or escaped the bounded nearby search: " + distance);
        require(level.noCollision(player, player.getBoundingBox()) && !level.containsAnyLiquid(player.getBoundingBox()),
                "Prepared return left the real player inside a collision or fluid");
        require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                "Successful safe return retained stale recovery state");
        verifyReturnBlocks(server);
        obstructionReturnVerified = true;
        AstraEngine.LOGGER.info("ASTRA_SURFACE_RETURN_OBSTRUCTION_PASSED source={} returned={} distance={}",
                takeoffStart, player.position(), distance);
    }

    private void verifyReturnBlocks(MinecraftServer server) {
        var level = server.getLevel(SurfaceWorlds.dimension(SurfaceDefinition.byBody("moon")));
        for (var entry : returnNeighborhood.entrySet()) {
            boolean obstruction = entry.getKey().equals(returnObstruction) || entry.getKey().equals(returnObstruction.above());
            BlockState expected = obstruction ? Blocks.STONE.defaultBlockState() : entry.getValue();
            require(level.getBlockState(entry.getKey()).equals(expected),
                    "Safe return or death changed retained source terrain at " + entry.getKey());
        }
        verifyMarker(server, "moon", moonMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
    }

    private void verifyFailureHome(MinecraftServer server) {
        ServerPlayer player = player(server);
        require(player.isAlive() && player.serverLevel() == server.overworld(),
                "Respawn or renewed session left an orphaned staging observer");
        require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                "Completed host respawn or renewed return retained recovery state");
        require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                "Failure cleanup changed the original home world marker");
        verifyReturnBlocks(server);
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint.load(new StringReader(Files.readString(checkpointPath("surface-checkpoint.properties"))));
                moonMarker = markerFromCheckpoint("moon"); earthMarker = markerFromCheckpoint("earth");
                CompoundTag expectedBindings = TagParser.parseTag(checkpoint.getProperty("bindings"));
                server(server -> {
                    server.tickRateManager().setFrozen(true);
                    ServerPlayer player = player(server);
                    require(player.serverLevel().dimension().equals(SurfaceWorlds.dimension(SurfaceDefinition.byBody("earth"))),
                            "Restart did not retain the committed Earth surface world");
                    require(player.position().distanceTo(savedPosition()) < 0.1, "Restart changed the surface-local position");
                    require(SurfaceBindings.get(server).save(new CompoundTag(), server.registryAccess()).equals(expectedBindings),
                            "Restart changed the saved geographic binding manifest");
                    verifyMarker(server, "moon", moonMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    verifyMarker(server, "earth", earthMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK), "Restart changed Overworld terrain");
                });
                next();
            }
            case 1 -> {
                if (!onSurface("earth") || !ready(35)) { return false; }
                shot("restart-01-earth-retained-world-position-marker");
                tap(GLFW.GLFW_KEY_M); next();
            }
            case 2 -> {
                if (!(minecraft.screen instanceof CosmosMapScreen map) || !ready(10)) { return false; }
                controller = map.controller(); map.onClose();
                server(server -> server.tickRateManager().setFrozen(false));
                next();
            }
            case 3 -> { takeOff(); next(); }
            case 4 -> {
                if (!manualFlight() || !ready(25)) { return false; }
                shot("restart-02-departed-persisted-earth");
                land("earth"); next();
            }
            case 5 -> {
                if (!onSurface("earth") || !ready(30)) { return false; }
                server(server -> verifyMarker(server, "earth", earthMarker, Blocks.EMERALD_BLOCK.defaultBlockState()));
                next();
            }
            case 6 -> {
                shot("restart-03-reentered-retained-earth");
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface restart step " + step);
        }
        return false;
    }

    private boolean recoverTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(Files.isRegularFile(checkpointPath("surface-interrupt-checkpoint.properties")),
                        "Interrupted surface recovery lacks its original fixture checkpoint");
                server(server -> {
                    ServerPlayer player = player(server);
                    require(player.serverLevel().dimension().equals(Level.OVERWORLD),
                            "Interrupted descent did not recover its original real world");
                    require(player.position().distanceTo(new Vec3(0.5, 200, 0.5)) < 0.25,
                            "Interrupted descent changed its original real position");
                    require(!player.getPersistentData().contains("astraengine_flight_recovery"),
                            "Completed interruption recovery retained flight ownership");
                    require(server.overworld().getBlockState(HOME_MARKER).is(Blocks.DIAMOND_BLOCK),
                            "Interrupted entry or recovery changed the original marker");
                });
                next();
            }
            case 1 -> {
                if (!ready(35)) { return false; }
                shot("recover-safe-source-after-interrupted-descent");
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface recovery step " + step);
        }
        return false;
    }

    private boolean upgradeTick() throws Exception {
        switch (step) {
            case 0 -> {
                Path fixture = minecraft.gameDirectory.toPath();
                Properties manifest = upgradeManifest(fixture);
                Path preimage = fixture.resolve(manifest.getProperty("exploration_preimage")).normalize();
                CompoundTag stored = NbtIo.readCompressed(preimage, NbtAccounter.create(16 * 1024 * 1024));
                require(stored.contains("data", Tag.TAG_COMPOUND), "Exploration preimage lacks SavedData payload");
                CompoundTag original = stored.getCompound("data");
                server(server -> verifyUpgrade(server, manifest, original));
                next();
            }
            case 1 -> {
                if (!ready(20)) { return false; }
                require(minecraft.level.dimension().equals(Level.OVERWORLD),
                        "Upgrade unexpectedly transferred the existing home observer");
                groundLook(135, 65);
                next();
            }
            case 2 -> {
                if (!ready(12)) { return false; }
                shot("upgrade-old-overworld-platform-retained");
                Path evidence = minecraft.gameDirectory.toPath().resolve("evidence");
                Files.writeString(evidence.resolve("surface-upgrade-bindings-and-exploration.txt"), upgradeReport);
                return true;
            }
            default -> throw new IllegalStateException("Unknown surface upgrade step " + step);
        }
        return false;
    }

    private void verifyUpgrade(MinecraftServer server, Properties manifest, CompoundTag original) {
        require(player(server).serverLevel() == server.overworld(), "Prior fixture did not reopen its Overworld");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos position = new BlockPos(x, 199, z);
                var expected = x == 0 && z == 0 ? Blocks.DIAMOND_BLOCK : Blocks.STONE_BRICKS;
                require(server.overworld().getBlockState(position).is(expected),
                        "Upgrade changed existing home block at " + position);
            }
        }
        StringBuilder report = new StringBuilder("fixture_id=").append(manifest.getProperty("fixture_id"))
                .append("\nExisting Overworld platform: 25 original blocks retained; no fixture block writes.\n");
        SurfaceBindings bindings = SurfaceBindings.get(server);
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            var dimension = SurfaceWorlds.dimension(definition);
            var level = server.getLevel(dimension);
            require(level != null && bindings.matches(level, definition),
                    "Prior save did not load the new persistent geographic world: " + dimension.location());
            require(level != server.overworld(), "Surface upgrade rebound the existing Overworld");
            report.append("loaded=").append(dimension.location()).append(" version=")
                    .append(definition.version()).append(" seed=").append(definition.seed()).append('\n');
        }

        CompoundTag expected = ExplorationCatalog.decode(original.copy())
                .save(new CompoundTag(), server.registryAccess());
        CompoundTag actual = ExplorationCatalog.get(server).save(new CompoundTag(), server.registryAccess());
        for (String field : new String[]{"version", "generator_version", "universe_version", "satellite_version",
                "seed", "landing", "custom_systems"}) {
            require(expected.get(field).equals(actual.get(field)), "Upgrade changed exploration field: " + field);
        }
        require(actual.getLong("clock_ticks") >= original.getLong("clock_ticks"), "Upgrade reset the saved exploration clock");
        var oldPlayers = expected.getList("players", Tag.TAG_COMPOUND);
        var newPlayers = actual.getList("players", Tag.TAG_COMPOUND);
        require(!oldPlayers.isEmpty(), "Prior exploration preimage has no retained pilot to verify");
        for (int index = 0; index < oldPlayers.size(); index++) {
            CompoundTag previous = oldPlayers.getCompound(index);
            CompoundTag current = null;
            for (int candidate = 0; candidate < newPlayers.size(); candidate++) {
                CompoundTag value = newPlayers.getCompound(candidate);
                if (previous.getUUID("uuid").equals(value.getUUID("uuid"))) { current = value; break; }
            }
            require(current != null, "Upgrade lost a saved exploration pilot: " + previous.getUUID("uuid"));
            for (String field : new String[]{"system", "discovered", "visited"}) {
                require(previous.get(field).equals(current.get(field)), "Upgrade changed pilot " + field);
            }
            report.append("pilot=").append(previous.getUUID("uuid")).append(" system=")
                    .append(previous.getString("system")).append(" discovered=").append(previous.get("discovered"))
                    .append(" visited=").append(previous.get("visited")).append('\n');
        }
        report.append("exploration_format_before=").append(original.getInt("version"))
                .append(" after=").append(actual.getInt("version"))
                .append(" clock_before=").append(original.getLong("clock_ticks"))
                .append(" clock_after=").append(actual.getLong("clock_ticks")).append('\n');
        upgradeReport = report.toString();
        AstraEngine.LOGGER.info("ASTRA_SURFACE_UPGRADE_WORLDS_RETAINED fixture={} pilots={}",
                manifest.getProperty("fixture_id"), oldPlayers.size());
    }

    /** Restricts upgrade loading and experimental confirmation to an explicitly prepared disposable copy. */
    static Properties upgradeManifest(Path fixture) throws IOException {
        Path root = fixture.toAbsolutePath().normalize();
        Path manifestPath = root.resolve("pre-upgrade-fixture.txt");
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(root.resolve("verified-celestial-polish.txt"))
                || !Files.isRegularFile(root.resolve("saves/first-slice/level.dat"))) {
            throw new IllegalStateException("Surface upgrade requires the copied completed baseline and unique manifest");
        }
        Properties manifest = new Properties();
        try (var reader = Files.newBufferedReader(manifestPath)) { manifest.load(reader); }
        String sourcePhase = manifest.getProperty("source_phase", "");
        String sourceWorld = manifest.getProperty("source_world", "");
        String fixtureId = manifest.getProperty("fixture_id", "");
        String preimage = manifest.getProperty("exploration_preimage", "");
        Path preimagePath = root.resolve(preimage).normalize();
        if (!sourcePhase.equals("celestial-polish") || !sourceWorld.equals("first-slice") || fixtureId.isBlank()
                || preimage.isBlank() || !preimagePath.startsWith(root.resolve("pre-upgrade"))
                || !Files.isRegularFile(preimagePath)) {
            throw new IllegalStateException("Surface upgrade manifest does not identify its retained baseline preimage");
        }
        return manifest;
    }

    private void land(String body) {
        recordLandingStart(body);
        action(FlightActionPayload.Action.LAND_BODY, body);
    }

    private void recordLandingStart(String body) {
        landingStart = controller.snapshot().position();
        landingDefinition = SurfaceDefinition.byBody(body);
        landingSendClock = controller.snapshot().clockTicks();
        landingBodyStart = landingDefinition.frame(controller.currentSystem(), landingSendClock / 20.0,
                landingSendClock).toBodyPoint(landingStart);
        landingAcceptedClock = -1;
        landingEpoch = controller.snapshot().navigationEpoch();
        canceledLandingSnapshot = null;
        awaitLandingCancellation = false;
        observedDescent = false;
        operation++;
    }

    private void verifyCanceledLanding() {
        require(canceledLandingSnapshot != null && landingAcceptedClock >= landingSendClock,
                "Canceled landing lacks its authoritative start/restore clock samples");
        long clock = canceledLandingSnapshot.clockTicks();
        var frame = landingDefinition.frame(controller.currentSystem(), clock / 20.0, clock);
        SpaceVector expected = frame.toSystemPoint(landingBodyStart);
        double error = canceledLandingSnapshot.position().distance(expected);
        require(error < 250, "Canceled landing missed its orbit/spin-tracked safe source by " + error + " meters");
        var body = controller.currentSystem().bodies().stream()
                .filter(value -> value.id().equals(landingDefinition.bodyId())).findFirst().orElseThrow();
        require(canceledLandingSnapshot.position().distance(frame.centerMeters()) > FlightDynamics.safeRadius(body)
                        && canceledLandingSnapshot.velocity().length() == 0,
                "Canceled landing restored an unsafe or moving orbital source");
        append("body-fixed-restore-error-meters=" + error + ", accepted-clock=" + landingAcceptedClock
                + ", restored-clock=" + clock);
    }

    private void rejectOneLanding(EntityTravelToDimensionEvent event) {
        if (!rejectNextLanding || !(event.getEntity() instanceof ServerPlayer player)
                || !player.getUUID().equals(pilotId) || !RocketService.isFlightWorld(player)
                || !event.getDimension().equals(SurfaceWorlds.dimension(SurfaceDefinition.byBody("moon")))) { return; }
        event.setCanceled(true);
        rejectNextLanding = false;
        rejectedLandings++;
        AstraEngine.LOGGER.info("ASTRA_SURFACE_HOST_VETO target={} source={} count={}",
                event.getDimension().location(), player.serverLevel().dimension().location(), rejectedLandings);
    }
    private void takeOff() {
        observedAscent = false;
        operation++;
        action(FlightActionPayload.Action.TAKE_OFF, "");
    }

    private void captureTransition() throws Exception {
        if (surface == null || minecraft.level == null || minecraft.screen != null || clearWorldFrames < 4) { return; }
        if (surface.phase() == SurfacePayload.Phase.DESCENDING) { observedDescent = true; }
        if (surface.phase() == SurfacePayload.Phase.ASCENDING) { observedAscent = true; }
        if (surface.phase() != SurfacePayload.Phase.DESCENDING && surface.phase() != SurfacePayload.Phase.ASCENDING) { return; }
        String key = String.format(java.util.Locale.ROOT, "transition-%02d-%s-%s-%02d", operation,
                surface.bodyId(), surface.phase().name().toLowerCase(java.util.Locale.ROOT), surface.remainingTicks() / 120);
        if (renderedFrames >= firstFrame + 3 && transitionCaptures.add(key)) { shot(key); }
        if (surface.phase() == SurfacePayload.Phase.DESCENDING && surface.remainingTicks() > 0) {
            for (int remaining : new int[]{20, 4}) {
                String late = String.format(java.util.Locale.ROOT, "transition-%02d-%s-descending-final-%02d-ticks",
                        operation, surface.bodyId(), remaining);
                if (surface.remainingTicks() <= remaining && renderedFrames >= firstFrame + 3
                        && transitionCaptures.add(late)) { shot(late); }
            }
        }
    }

    private boolean descending(String body) {
        return surface != null && surface.bodyId().equals(body) && surface.phase() == SurfacePayload.Phase.DESCENDING;
    }
    private boolean onSurface(String body) {
        return minecraft.screen == null && clearWorldFrames >= 4
                && surface != null && surface.bodyId().equals(body) && surface.phase() == SurfacePayload.Phase.SURFACE
                && minecraft.level.dimension().equals(SurfaceWorlds.dimension(SurfaceDefinition.byBody(body)));
    }
    private boolean manualFlight() {
        return minecraft.screen == null && clearWorldFrames >= 4
                && controller != null && controller.active() && controller.snapshot().jumpTicks() == 0
                && minecraft.level.dimension().equals(RocketService.FLIGHT)
                && (surface == null || surface.phase() == SurfacePayload.Phase.NONE);
    }

    private void prepareHome(MinecraftServer server) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.overworld().setBlockAndUpdate(HOME_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        player(server).teleportTo(0.5, 200, 0.5);
        SurfaceBindings.get(server);
    }

    private BlockPos placeMarker(MinecraftServer server, String body, BlockState state) {
        ServerPlayer player = player(server);
        var definition = SurfaceDefinition.byBody(body);
        var level = player.serverLevel();
        require(level.dimension().equals(SurfaceWorlds.dimension(definition))
                        && SurfaceBindings.get(server).matches(level, definition),
                "Committed surface does not match its permanent geographic binding");
        require(definition.patch().contains(player.getX(), player.getZ()), "Landing escaped its geographic patch");
        var geographicSnapshot = dev.lexawhatt.astraengine.api.AstraGeography.snapshot(player).orElseThrow();
        var reference = dev.lexawhatt.astraengine.api.AstraGeography.reference(player.serverLevel()).orElseThrow();
        var geographic = reference.geographic(new SpaceVector(player.getX(), player.getY(), player.getZ()));
        require(geographicSnapshot.geographicPosition().toBody(definition.patch().radiusMeters())
                .distance(geographic.toBody(definition.patch().radiusMeters())) < 1e-7,
                "Landing and geographic server observation diverged");
        BlockPos position = player.blockPosition().below();
        require(!level.getBlockState(position).getCollisionShape(level, position).isEmpty(),
                "Landing committed without a real supporting terrain block");
        level.setBlockAndUpdate(position, state);
        return position.immutable();
    }

    private void verifyMarker(MinecraftServer server, String body, BlockPos position, BlockState state) {
        require(position != null && server.getLevel(SurfaceWorlds.dimension(SurfaceDefinition.byBody(body)))
                .getBlockState(position).equals(state), "Real " + body + " marker was lost or regenerated");
    }

    private void recordCheckpoint(MinecraftServer server) {
        ServerPlayer player = player(server);
        checkpoint.setProperty("x", Double.toString(player.getX()));
        checkpoint.setProperty("y", Double.toString(player.getY()));
        checkpoint.setProperty("z", Double.toString(player.getZ()));
        checkpoint.setProperty("clock", Long.toString(ExplorationCatalog.get(server).clockTicks()));
        checkpoint.setProperty("bindings", SurfaceBindings.get(server).save(new CompoundTag(), server.registryAccess()).toString());
        recordMarker("moon", moonMarker); recordMarker("earth", earthMarker);
    }
    private void recordMarker(String body, BlockPos position) {
        checkpoint.setProperty(body + ".x", Integer.toString(position.getX()));
        checkpoint.setProperty(body + ".y", Integer.toString(position.getY()));
        checkpoint.setProperty(body + ".z", Integer.toString(position.getZ()));
    }
    private BlockPos markerFromCheckpoint(String body) {
        return new BlockPos(Integer.parseInt(checkpoint.getProperty(body + ".x")),
                Integer.parseInt(checkpoint.getProperty(body + ".y")), Integer.parseInt(checkpoint.getProperty(body + ".z")));
    }
    private Vec3 savedPosition() {
        return new Vec3(Double.parseDouble(checkpoint.getProperty("x")), Double.parseDouble(checkpoint.getProperty("y")),
                Double.parseDouble(checkpoint.getProperty("z")));
    }
    private void writeCheckpoint(String file) throws Exception {
        StringWriter text = new StringWriter();
        checkpoint.store(text, "Disposable surface fixture: actual blocks, permanent geographic identity and local position");
        Files.writeString(checkpointPath(file), text.toString());
    }
    private Path checkpointPath(String file) { return minecraft.gameDirectory.toPath().resolve(file); }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        minecraft.gui.getChat().clearMessages(false);
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error during surface capture " + name);
        append("capture:" + name);
        AstraEngine.LOGGER.info("ASTRA_SURFACE_CAPTURE {} phase={} dimension={} position={}", name,
                surface, minecraft.level.dimension().location(), minecraft.player.position());
    }

    private void append(String event) {
        observations.append(event).append('\t').append(renderedFrames).append('\t').append(step).append('\t')
                .append(surface == null ? "" : surface.bodyId()).append('\t')
                .append(surface == null ? "" : surface.phase()).append('\t')
                .append(surface == null ? 0 : surface.remainingTicks()).append('\t')
                .append(surface == null ? 0 : surface.clockTicks()).append('\t')
                .append(minecraft.level == null ? "" : minecraft.level.dimension().location()).append('\t')
                .append(minecraft.player == null ? "" : minecraft.player.position()).append('\t')
                .append(controller == null || controller.snapshot() == null ? "" : controller.snapshot().position()).append('\n');
    }
    private void retain() throws Exception {
        Path evidence = minecraft.gameDirectory.toPath().resolve("evidence");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(phase + "-transitions.tsv"), observations.toString());
        if (phase.equals("surface-upgrade")) {
            Files.writeString(evidence.resolve(phase + "-scope.txt"),
                    "Opens an explicitly copied, previously completed celestial-polish world.\n"
                    + "Checks new persistent Moon/Earth worlds and geographic bindings through the actual integrated server.\n"
                    + "Reads the existing 5x5 Overworld platform without prepareHome, marker writes or terrain replacement.\n"
                    + "Compares retained compressed exploration preimage: format/generator identities, seed, custom definitions,\n"
                    + "and each prior pilot's exact system, charted list and visited list. Clock may advance but cannot reset.\n"
                    + "Does not repeat landing or create surface blocks; ordinary host save-on-close remains enabled.\n");
            return;
        }
        Files.writeString(evidence.resolve(phase + "-scope.txt"),
                "Actual APPROACH_BODY, LAND_BODY, TAKE_OFF and BRAKE packets drive the normal server service.\n"
                + "No renderer pose, authoritative pilot or terrain-generator overrides are installed.\n"
                + "Fixture block writes are limited to disposable home/markers, the failures-only pillar, and boundaries-only chamber/tunnel/quarry.\n"
                + "Create/restart retain real dimension blocks and versioned geographic binding; ground rendering reload/resize is exercised.\n"
                + "Cancel covers active descent/ascent; interrupt/recover cover ordinary process shutdown during entry.\n"
                + "Cancel also vetoes one actual EntityTravelToDimensionEvent and verifies physical world/recovery before a successful retry.\n"
                + "Failures places a two-block source pillar during ascent, cancels to a clear nearby pose, and retains all surrounding blocks.\n"
                + "Failures then invokes host death before landing commits, uses the real Respawn button, and verifies a fresh flight/return.\n"
                + "Boundaries uses high/outer/underwater/crouched source poses and an explicitly excavated Moon pit; actual requests must fail safely or select retained nearby terrain.\n"
                + "Late descent captures retain the <=20 and <=4 remaining-tick views before real-world commitment.\n"
                + "This fixture does not simulate a process crash, remote multiplayer, or artificially delayed chunk I/O.\n");
    }

    private void dispose() {
        if (phase.equals("surface-boundaries")) { minecraft.options.keyShift.setDown(false); }
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(surfaceListener);
        NeoForge.EVENT_BUS.unregister(explorationListener);
        NeoForge.EVENT_BUS.unregister(travelListener);
    }
    private void groundLook(float yaw, float pitch) { minecraft.player.setYRot(yaw); minecraft.player.setXRot(pitch); }
    private boolean ready(int count) { return ticks >= count && renderedFrames >= firstFrame + 4; }
    private void action(FlightActionPayload.Action action, String target) {
        PacketDistributor.sendToServer(new FlightActionPayload(action, target));
    }
    private void command(String command) { minecraft.player.connection.sendCommand(command); }
    private void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private void next() { moveTo(step + 1); }
    private void moveTo(int value) { step = value; ticks = 0; firstFrame = renderedFrames; }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (" + phase + ", step " + step + ", ticks " + ticks + ")"); }
    }
}
