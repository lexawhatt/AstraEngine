package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SolidPlanetTerrain;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Actual orbital shader and actual v2 host Mars terrain/sky; immutable camera poses do not change production clocks. */
final class MarsVisualScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final dev.lexawhatt.astraengine.cosmos.CosmosSystem system = CosmosGenerator.sol();
    private final SolidPlanetProfile profile = SolidPlanetProfile.create(system, system.bodies().stream()
            .filter(body -> body.id().equals("mars")).findFirst().orElseThrow()).orElseThrow();
    private final Consumer<ViewportEvent.ComputeCameraAngles> camera = event -> {
        if (this.orientation != null) {
            event.setYaw(this.orientation.yaw()); event.setPitch(this.orientation.pitch()); event.setRoll(this.orientation.roll());
        }
    };
    private final Consumer<RenderLevelStageEvent> rendering = this::render;
    private final StringBuilder evidence = new StringBuilder("Sol Mars actual v2 material and thin-dust atmosphere\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private CosmosRenderer renderer;
    private SpaceVector observer;
    private SpaceVector bodySun;
    private FlightOrientation orientation;
    private PlanetChart ground;
    private RuntimeException failure;
    private double seconds;
    private int stage;
    private int ticks;
    private int view;
    private final long started = System.nanoTime();

    MarsVisualScenario() {
        game.options.hideGui = true; game.options.fov().set(70); game.options.bobView().set(false);
        game.options.renderDistance().set(3); game.options.simulationDistance().set(5); game.options.broadcastOptions();
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, camera);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, rendering);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - started < 300_000_000_000L, "Mars presentation timed out at " + stage + "/" + view);
        if (failure != null) { throw failure; }
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            controller = map.controller(); renderer = (CosmosRenderer) field(controller, "renderer"); map.onClose();
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.getLevel(RocketService.FLIGHT), 0, 200, 0, 0, 0);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setNoGravity(true);
            }, server);
            next(); return false;
        }
        if (stage == 2) {
            if (ticks < 80 || !game.level.dimension().equals(RocketService.FLIGHT)) { return false; }
            seconds = controller.surfaceState().orbitalSeconds();
            var frame = profile.frame(system, seconds);
            bodySun = frame.toBodyDirection(frame.centerMeters().multiply(-1)).normalized();
            var tangent = perpendicular(bodySun);
            var normal = bodySun.multiply(.8).add(tangent.multiply(.6)).normalized();
            double altitude = view == 0 ? profile.radiusMeters() * 1.5 : 100_000;
            observer = frame.toSystemPoint(normal.multiply(profile.radiusMeters() + altitude));
            var forward = view == 0 ? frame.centerMeters().subtract(observer).normalized()
                    : frame.toSystemDirection(tangent.subtract(normal.multiply(tangent.dot(normal))).normalized()
                    .subtract(normal.multiply(.26)).normalized());
            orientation = aim(forward);
            evidence.append("orbit view=").append(view).append(" altitudeMeters=").append(altitude)
                    .append(" profileVersion=").append(profile.version()).append(" seconds=").append(seconds).append('\n');
            next(); return false;
        }
        if (stage == 3) {
            if (ticks < 120 || !ready()) { return false; }
            capture(view == 0 ? "orbit" : "100km-limb", view == 0);
            if (++view < 2) { stage = 2; ticks = 80; return false; }
            observer = null; orientation = null; next(); return false;
        }
        if (stage == 4) {
            var normal = view == 2 ? bodySun.multiply(.65).add(perpendicular(bodySun).multiply(Math.sqrt(1 - .65 * .65))).normalized()
                    : bodySun.multiply(.015).add(perpendicular(bodySun).multiply(Math.sqrt(1 - .015 * .015))).normalized();
            var sample = new SolidPlanetTerrain(profile).sample(normal);
            var address = new GeographicPosition(Math.asin(normal.y()), Math.atan2(-normal.z(), normal.x()), sample.heightMeters() + 4);
            ground = PlanetChart.owner(profile, address).orElseThrow();
            var feet = ground.resolve(address).orElseThrow();
            var localSun = ground.tangentFrame(feet.x(), feet.z(), address.altitudeMeters()).toLocalDirection(bodySun);
            var look = aim(new SpaceVector(localSun.x(), view == 2 ? -.35 : .015, localSun.z()).normalized());
            var server = game.getSingleplayerServer();
            pending = CompletableFuture.runAsync(() -> {
                var world = PlanetSurfaceWorlds.ensure(server, ground);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(world, feet.x(), feet.y(), feet.z(), look.yaw(), look.pitch());
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setNoGravity(true);
                var actual = new BlockPos((int) Math.floor(feet.x()), (int) Math.floor(sample.heightMeters())
                        - ground.altitudeOriginMeters() - 1, (int) Math.floor(feet.z()));
                world.getChunk(actual);
                var generated = (dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator) world.getChunkSource().getGenerator();
                actual = new BlockPos(actual.getX(), (int) Math.floor(generated.terrain().sample(ground.normal(
                        actual.getX() + .5, actual.getZ() + .5)).heightMeters()) - ground.altitudeOriginMeters() - 1, actual.getZ());
                var state = world.getBlockState(actual);
                require(state.is(Blocks.RED_SAND) || state.is(Blocks.TERRACOTTA), "Actual Mars ground is not oxidized regolith: " + state);
                evidence.append("ground view=").append(view).append(" dimension=").append(ground.dimensionId())
                        .append(" material=").append(state).append(" altitude=").append(address.altitudeMeters()).append('\n');
            }, server);
            next(); return false;
        }
        if (stage == 5) {
            if (ticks < 140 || !game.level.dimension().location().toString().equals(ground.dimensionId()) || game.screen != null) { return false; }
            require(game.level.effects() instanceof dev.lexawhatt.astraengine.client.surface.PlanetEffects,
                    "Mars walking sky does not use its synchronized planetary effects");
            capture(view == 2 ? "ground-day" : "ground-twilight", view == 2);
            if (++view < 4) { stage = 4; ticks = 0; return false; }
            var output = game.gameDirectory.toPath().resolve("evidence/mars-results.txt");
            Files.writeString(output, evidence, StandardOpenOption.CREATE_NEW);
            NeoForge.EVENT_BUS.unregister(camera); NeoForge.EVENT_BUS.unregister(rendering);
            return true;
        }
        return false;
    }

    private void render(RenderLevelStageEvent event) {
        if (renderer == null || observer == null || orientation == null) { return; }
        try { renderer.render(event, system, observer, seconds, seconds, null, 0, 1); }
        catch (RuntimeException exception) { failure = exception; }
    }
    private boolean ready() throws Exception {
        var cache = field(renderer, "planets");
        return (int) field(cache, "atlas") != 0 && field(cache, "pending") == null;
    }
    private void capture(String name, boolean requireRust) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence/mars-" + name + ".png"); Files.createDirectories(path.getParent());
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path);
            int rust = 0;
            for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                    int pixel = image.getPixelRGBA(x, y), red = pixel & 255, green = pixel >>> 8 & 255, blue = pixel >>> 16 & 255;
                    if (red > blue * 1.15 + 10 && red > green * 1.05 && red > 35) { rust++; }
                }
            }
            evidence.append(name).append(" warmMaterialPixels=").append(rust).append('\n');
            if (requireRust) { require(rust > 1000, "Mars material was not visibly rusty in actual " + name); }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Mars presentation left a GL error");
    }
    private void next() { stage++; ticks = 0; }
    private static FlightOrientation aim(SpaceVector unit) {
        return FlightOrientation.fromAngles(Math.toDegrees(Math.atan2(-unit.x(), unit.z())), Math.toDegrees(Math.asin(-unit.y())), 0);
    }
    private static SpaceVector perpendicular(SpaceVector unit) {
        var axis = Math.abs(unit.z()) < .9 ? new SpaceVector(0, 0, 1) : new SpaceVector(1, 0, 0);
        return axis.subtract(unit.multiply(axis.dot(unit))).normalized();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
