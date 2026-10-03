package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.ExplorationReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.InspectionFlightStep;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** One speed request and a continuously held W through all Earth bands; no test-directed route or re-aim loop. */
final class InspectionAscentScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final StringBuilder evidence = new StringBuilder("One speed request; held W ascent from real Earth to100km.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private EarthChart initialChart;
    private GeographicPosition initialAddress;
    private Vec3 shortcutSource;
    private String shortcutDimension;
    private GeographicPosition shortcutAddress;
    private int stage;
    private int ticks;
    private int loadingFrames;
    private final int[] loadingByStage = new int[12];
    private int zeroSnapshots;
    private int movementSnapshots;
    private long observedRevision = -1;
    private long started;
    private long stageStarted = System.nanoTime();
    private double previousAltitude;
    private double maximumSpeed;
    private boolean measuring;
    private boolean firstSpaceSnapshot;
    private final Consumer<ExplorationReceivedEvent> navigation = event -> {
        if (measuring && stage == 5 && !firstSpaceSnapshot && event.payload().active()
                && game.level != null && game.level.dimension().equals(RocketService.FLIGHT)) {
            firstSpaceSnapshot = true;
            evidence.append("firstSpaceSnapshotVelocity=").append(event.payload().velocity())
                    .append(" speed=").append(event.payload().velocity().length())
                    .append(" selected=").append(event.payload().speedMetersPerSecond()).append('\n');
        }
    };

    InspectionAscentScenario() {
        game.options.pauseOnLostFocus = false;
        game.options.renderDistance().set(4); game.options.simulationDistance().set(5);
        game.options.broadcastOptions(); game.options.bobView().set(false);
        NeoForge.EVENT_BUS.addListener(navigation);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - stageStarted < 240_000_000_000L, "Held-W ascent timed out at stage" + stage);
        if (measuring && game.screen instanceof ReceivingLevelScreen) { loadingFrames++; loadingByStage[stage]++; }
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            controller = map.controller(); map.onClose();
            server(server -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                int version = EarthWorlds.terrainVersion(server);
                var normal = new GeographicPosition(.7661117545732665, 2.783804210211678, 0).normal();
                var sample = new ContinentalTerrain(version, ContinentalTerrain.SEED).sample(normal);
                initialAddress = new GeographicPosition(.7661117545732665, 2.783804210211678, sample.waterMeters() + 30);
                initialChart = EarthChart.owner(initialAddress, version).orElseThrow();
                var level = PlanetSurfaceWorlds.ensure(server, initialChart);
                var feet = initialChart.resolve(initialAddress).orElseThrow();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(level, feet.x(), feet.y(), feet.z(), 0, -90);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
                evidence.append("initialAltitude=").append(initialAddress.altitudeMeters())
                        .append(" initialChart=").append(initialChart.dimensionId()).append('\n');
            });
            next(); return false;
        }
        if (stage == 2) {
            if (ticks < 60 || game.screen != null || !game.level.dimension().location().toString().equals(initialChart.dimensionId())) {
                return false;
            }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (stage == 3) {
            if (!controller.active() || ticks < 20) { return false; }
            require(controller.setSpeed(InspectionFlightStep.AIR_SPEED), "Could not request ascent speed once");
            var view = controller.view(); var frame = view.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
            var aim = RocketController.class.getDeclaredMethod("aimDirection", SpaceVector.class); aim.setAccessible(true);
            require((boolean) aim.invoke(controller, view.position().subtract(frame.centerMeters())), "Could not aim outward once");
            next(); return false;
        }
        if (stage == 4) {
            if (controller.snapshot().speedMetersPerSecond() != InspectionFlightStep.AIR_SPEED || ticks < 20) { return false; }
            capture("start"); game.mouseHandler.grabMouse(); game.options.keyUp.setDown(true);
            started = System.nanoTime(); measuring = true; previousAltitude = initialAddress.altitudeMeters(); next(); return false;
        }
        if (stage == 5) {
            require(controller.active(), "An ordinary altitude seam disabled inspection");
            require(controller.snapshot().speedMetersPerSecond() == InspectionFlightStep.AIR_SPEED, "Selected speed reset during held-W ascent");
            if (controller.snapshot().revision() != observedRevision) {
                observedRevision = controller.snapshot().revision();
                double speed = controller.snapshot().velocity().length();
                if (speed < .01) { zeroSnapshots++; } else { movementSnapshots++; }
                if (!game.level.dimension().equals(RocketService.FLIGHT)) { maximumSpeed = Math.max(maximumSpeed, speed); }
                var address = address();
                require(address.altitudeMeters() >= previousAltitude - 2, "Ascent lost physical altitude at a storage seam");
                previousAltitude = address.altitudeMeters();
                evidence.append("ascent elapsedMs=").append((System.nanoTime() - started) / 1_000_000)
                        .append(" altitude=").append(address.altitudeMeters()).append(" speed=").append(speed)
                        .append(" dimension=").append(game.level.dimension().location()).append('\n');
            }
            if (!game.level.dimension().equals(RocketService.FLIGHT)) { return false; }
            game.options.keyUp.setDown(false); next(); return false;
        }
        if (stage == 6) {
            if (ticks < 15) { return false; }
            require(firstSpaceSnapshot, "No authoritative space-entry velocity snapshot was recorded");
            require(maximumSpeed > 40_000, "Held-W ascent never exceeded the old terrestrial speed cap");
            require(loadingFrames == 0, "Manual ascent displayed a receiving-level screen");
            require(initialAddress.normal().distance(address().normal()) * initialChart.radiusMeters() < 20,
                    "Manual ascent drifted to a different geographic address");
            evidence.append("maximumPhysicalGroundSpeed=").append(maximumSpeed).append(" zeroSnapshots=").append(zeroSnapshots)
                    .append(" movementSnapshots=").append(movementSnapshots).append(" elapsedMs=")
                    .append((System.nanoTime() - started) / 1_000_000).append('\n');
            capture("manual-orbit"); tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (stage == 7) {
            if (controller.active() || game.level.dimension().equals(RocketService.FLIGHT) || ticks < 30) { return false; }
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                shortcutSource = player.position(); shortcutDimension = player.serverLevel().dimension().location().toString();
                shortcutAddress = PlanetSurfaceWorlds.getCube(player.serverLevel()).orElseThrow()
                        .geographic(new SpaceVector(player.getX(), player.getY(), player.getZ()));
            });
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 8) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            var orbit = map.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals(net.minecraft.network.chat.Component
                            .translatable("astraengine.map.orbit").getString())).findFirst();
            require(orbit.isPresent() && orbit.get().active, "Surface map has no usable To orbit button");
            orbit.get().onPress(); map.onClose();
            next(); return false;
        }
        if (stage == 9) {
            if (!controller.active() || !game.level.dimension().equals(RocketService.FLIGHT) || ticks < 20) { return false; }
            var address = address();
            require(Math.abs(address.altitudeMeters() - 150_000) < 5, "Orbit shortcut did not start at150km");
            require(shortcutAddress.normal().distance(address.normal()) * initialChart.radiusMeters() < 20,
                    "Orbit shortcut changed the requested geographic address");
            require(controller.snapshot().speedMetersPerSecond() == InspectionFlightStep.AIR_SPEED, "Orbit shortcut reset selected speed");
            capture("shortcut-orbit"); tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (stage == 10) {
            if (controller.active() || !game.level.dimension().location().toString().equals(shortcutDimension) || ticks < 20) { return false; }
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                require(player.position().distanceTo(shortcutSource) < .1, "Leaving shortcut orbit lost the actual recovery position");
            });
            next(); return false;
        }
        evidence.append("receivingLevelFramesByStage=").append(java.util.Arrays.toString(loadingByStage)).append('\n');
        require(loadingByStage[8] + loadingByStage[9] == 0, "Direct orbit entry emitted a receiving-level screen");
        // R is an explicit recovery teleport, retaining the host's ordinary destination waiting screen.
        // Manual space-boundary motion and the prepared orbit shortcut are checked separately above.
        evidence.append("oneSpeedSelection=true heldWAscent=true directOrbit150km=true exactRecovery=true\n");
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("inspection-ascent.txt"), evidence, StandardOpenOption.CREATE_NEW);
        NeoForge.EVENT_BUS.unregister(navigation);
        return true;
    }

    private GeographicPosition address() {
        var view = controller.view(); var frame = view.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
        return GeographicPosition.fromBody(frame.toBodyPoint(view.position()), frame.radiusMeters());
    }
    private void next() throws Exception {
        stage++; ticks = 0; stageStarted = System.nanoTime(); AstraEngine.LOGGER.info("ASTRA_ASCENT_VERIFY stage={}", stage);
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("inspection-ascent-progress.txt"), "stage=" + stage
                + " receivingLevelFramesByStage=" + java.util.Arrays.toString(loadingByStage) + '\n' + evidence,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void tap(int key) { game.mouseHandler.grabMouse(); KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void capture(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(directory.resolve("inspection-" + name + ".png"));
        }
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
