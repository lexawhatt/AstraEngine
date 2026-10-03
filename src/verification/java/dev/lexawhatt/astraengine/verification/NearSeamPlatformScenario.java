package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Actual host collision with a player-built platform three meters below a vertical chart seam. */
final class NearSeamPlatformScenario {
    private static final BlockPos PLATFORM = new BlockPos(507149, 2029, 1285053);
    private final Minecraft game = Minecraft.getInstance();
    private final StringBuilder evidence = new StringBuilder("Actual platform near a vertical chart seam; ordinary inspection and gravity.\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private EarthChart source;
    private EarthChart target;
    private RocketController controller;
    private int step;
    private int ticks;
    private int settle;
    private int loadingFrames;
    private boolean landed;
    private boolean measuring;
    private long since = System.nanoTime();

    NearSeamPlatformScenario() {
        game.options.renderDistance().set(4); game.options.simulationDistance().set(5);
        game.options.bobView().set(false); game.options.pauseOnLostFocus = false;
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - since < 90_000_000_000L, "Near-seam platform timed out at " + step);
        if (!pending.isDone()) { return false; } pending.join(); ticks++;
        if (measuring && game.screen instanceof ReceivingLevelScreen) { loadingFrames++; }
        if (step == 0) {
            if (game.screen != null) { return false; }
            server(server -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                int version = EarthWorlds.terrainVersion(server);
                target = new EarthChart(CubeFace.NEGATIVE_Z, 0, version);
                source = new EarthChart(CubeFace.NEGATIVE_Z, 1, version);
                var level = PlanetSurfaceWorlds.ensure(server, target);
                for (int x = -5; x <= 5; x++) {
                    for (int z = -5; z <= 5; z++) { level.setBlock(PLATFORM.offset(x, 0, z), Blocks.RED_CONCRETE.defaultBlockState(), 3); }
                }
                arrangeSource(server);
            }); next(); return false;
        }
        if (step == 1) {
            if (game.level == null || !game.level.dimension().location().toString().equals(source.dimensionId())
                    || !obtainController() || ticks < 40) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (step == 2) {
            if (!controller.active() || ticks < 20) { return false; }
            controller.setSpeed(2560);
            var view = controller.view();
            var direction = view.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"))
                    .centerMeters().subtract(view.position());
            var aim = RocketController.class.getDeclaredMethod("aimDirection", SpaceVector.class); aim.setAccessible(true);
            require((boolean) aim.invoke(controller, direction), "Could not aim the ordinary surface inspection view");
            next(); return false;
        }
        if (step == 3) {
            if (controller.snapshot().speedMetersPerSecond() != 2560) {
                if (ticks % 20 == 0) { controller.setSpeed(2560); } return false;
            }
            if (ticks < 30) { return false; }
            measuring = true; game.options.keyUp.setDown(true); next(); return false;
        }
        if (step == 4 || step == 8) {
            if (!landed) {
                server(server -> observe(server, step == 4 ? "inspection" : "ordinary-gravity")); return false;
            }
            game.options.keyUp.setDown(false);
            if (++settle < 10 || !game.level.dimension().location().toString().equals(target.dimensionId())) { return false; }
            measuring = false;
            require(loadingFrames == 0, "Platform crossing displayed a loading screen");
            capture(step == 4 ? "inspection-platform" : "gravity-platform");
            evidence.append(step == 4 ? "inspection" : "ordinaryGravity").append("StoppedOnCanonicalPlatform=true\n");
            if (step == 4) { tap(GLFW.GLFW_KEY_R); next(); return false; }
            var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
            Files.writeString(directory.resolve("near-seam-platform.txt"), evidence, StandardOpenOption.CREATE_NEW);
            return true;
        }
        if (step == 5) {
            if (controller.active() || ticks < 20) { return false; }
            server(this::arrangeSource); next(); return false;
        }
        if (step == 6) {
            if (game.screen != null || game.level == null
                    || !game.level.dimension().location().toString().equals(source.dimensionId()) || ticks < 40) { return false; }
            next(); return false;
        }
        if (step == 7) {
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = false; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
            });
            measuring = true; next(); return false;
        }
        throw new IllegalStateException("Unknown platform fixture step " + step);
    }

    private void arrangeSource(MinecraftServer server) {
        var player = server.getPlayerList().getPlayers().getFirst();
        var level = PlanetSurfaceWorlds.ensure(server, source);
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level, PLATFORM.getX() + .6351252345, -2010, PLATFORM.getZ() + .3309597727, 0, 90);
        player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
    }

    private void observe(MinecraftServer server, String mode) {
        var player = server.getPlayerList().getPlayers().getFirst();
        var chart = EarthWorlds.chart(player.serverLevel()).orElseThrow();
        double altitude = player.getY() + chart.altitudeOriginMeters();
        var targetLevel = server.getLevel(EarthWorlds.dimension(target));
        var chunk = targetLevel.getChunkSource().getChunkNow(PLATFORM.getX() >> 4, PLATFORM.getZ() >> 4);
        String state = chunk == null ? "unloaded" : chunk.getBlockState(PLATFORM).toString();
        if (ticks < 30 || ticks % 20 == 0 || altitude < 2029.9) {
            AstraEngine.LOGGER.info("ASTRA_PLATFORM mode={} chart={} feet={} motion={} altitude={} noPhysics={} target={}",
                    mode, chart.dimensionId(), player.position(), player.getDeltaMovement(), altitude, player.noPhysics, state);
        }
        require(altitude >= 2029.99, "Player passed through the actual canonical RED platform: " + mode
                + " altitude=" + altitude + " chart=" + chart.dimensionId() + " savedTarget=" + state);
        landed = chart.equals(target) && Math.abs(player.getY() - 2030) < .001
                && (mode.equals("ordinary-gravity") ? player.onGround() : player.getDeltaMovement().lengthSqr() < .0001);
        if (landed) {
            require(chunk != null && chunk.getBlockState(PLATFORM).is(Blocks.RED_CONCRETE), "Platform was not actual RED at collision");
            evidence.append(mode).append(" feet=").append(player.position()).append(" chart=").append(chart.dimensionId()).append('\n');
        }
    }

    private boolean obtainController() {
        if (controller != null) { return true; }
        if (game.screen instanceof CosmosMapScreen map) { controller = map.controller(); map.onClose(); return true; }
        if (game.screen == null) { game.player.connection.sendCommand("astra-flight map"); } return false;
    }
    private void server(Consumer<MinecraftServer> action) { var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server); }
    private void next() { step++; ticks = 0; settle = 0; landed = false; since = System.nanoTime(); AstraEngine.LOGGER.info("ASTRA_PLATFORM_VERIFY step={}", step); }
    private void tap(int key) { game.mouseHandler.grabMouse(); KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private void capture(String name) throws Exception {
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(directory.resolve(name + ".png")); }
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
