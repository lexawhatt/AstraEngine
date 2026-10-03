package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lexawhatt.astraengine.client.flight.CosmosMapScreen;
import dev.lexawhatt.astraengine.client.flight.FlightCamera;
import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffPayload;
import dev.lexawhatt.astraengine.network.SpaceBoundaryHandoffReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Actual R/W controls and prepared host handoffs, with a full ground-to-space ascent and independent body addresses. */
final class SpaceBoundaryScenario {
    private static final List<Case> CASES = List.of(
            new Case("earth-lowland", "earth", .7661117545732665, 2.783804210211678, true),
            new Case("earth-mountain", "earth", .18192107303316596, .6372012293281402, true),
            new Case("earth-coast", "earth", .3518588510742868, .495659856477681, true),
            new Case("earth-pole", "earth", Math.PI / 2 - .00001, 1.5, false),
            new Case("earth-ocean", "earth", -.35, -1.4, false),
            new Case("moon", "moon", .3, .5, false),
            new Case("europa", "europa", -.45, 2.6, false));
    private final Minecraft game = Minecraft.getInstance();
    private final boolean rotating;
    private final boolean requirePack = System.getProperty("astraengine.verify.phase", "").equals("earth-space-pack");
    private final boolean requireFabulous = System.getProperty("astraengine.verify.graphics", "fancy").equals("fabulous");
    private final List<Case> cases;
    private final Consumer<SpaceBoundaryHandoffReceivedEvent> handoffs = event -> {
        if (!event.payload().cancelled()) { handoff = event.payload(); }
    };
    private final StringBuilder evidence = new StringBuilder("Free physical space-boundary native journey\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private RocketController controller;
    private CubeStorageChart source;
    private GeographicPosition start;
    private SpaceBoundaryHandoffPayload handoff;
    private int index;
    private int stage;
    private int ticks;
    private int loadingFrames;
    private int handoffTicks;
    private long handoffSeenAt;
    private long since = System.nanoTime();
    private double maximumPositionStep;
    private SpaceVector previousBodyEye;
    private SpaceVector previousForward;
    private SpaceVector previousUp;
    private double minimumHeadingDot = 1;
    private double minimumUpDot = 1;
    private boolean monitoring;
    private int reportedStage = -1;
    private long speedRequestedAt;
    private double maximumGroundDrift;
    private GeographicPosition lastSpaceAddress;
    private long lastSpaceAt;

    SpaceBoundaryScenario(boolean rotating) {
        this.rotating = rotating;
        this.cases = rotating ? CASES.stream().filter(value -> value.name.equals("earth-lowland")
                || value.name.equals("earth-ocean")).toList() : CASES;
        NeoForge.EVENT_BUS.addListener(handoffs);
        game.options.bobView().set(false);
        game.options.renderDistance().set(3); game.options.simulationDistance().set(5);
        game.options.broadcastOptions();
        evidence.append("graphics=").append(game.options.graphicsMode().get())
                .append(" transparency=").append(Minecraft.useShaderTransparency())
                .append(" width=").append(game.getWindow().getWidth())
                .append(" height=").append(game.getWindow().getHeight()).append('\n');
    }

    boolean tick() throws Exception {
        if (requireFabulous) {
            require(game.options.graphicsMode().get() == net.minecraft.client.GraphicsStatus.FABULOUS
                    && Minecraft.useShaderTransparency(), "The requested Fabulous renderer is not actually active");
        }
        if (requirePack) {
            var rendering = dev.lexawhatt.astraengine.client.compat.RenderCompatibility.diagnostics();
            require(rendering.shaderPackInUse().orElse(false) && !rendering.conservativeMode(),
                    "The active-pack physical boundary fixture lost its real Iris pack");
        }
        if (reportedStage != stage) {
            dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_SPACE_VERIFY case={} stage={}", index, stage);
            reportedStage = stage;
        }
        require(System.nanoTime() - since < 360_000_000_000L, "Space journey timed out at " + index + "/" + stage);
        if (!pending.isDone()) { return false; }
        pending.join(); ticks++;
        if (monitoring && game.screen instanceof ReceivingLevelScreen) { loadingFrames++; }
        // Start after ordinary login loading; the public command also avoids copied-pack M binding conflicts.
        if (stage == 0) {
            if (game.screen != null) { return false; }
            game.player.connection.sendCommand("astra-flight map"); next(); return false;
        }
        if (stage == 1) {
            if (!(game.screen instanceof CosmosMapScreen map)) { return false; }
            controller = map.controller(); map.onClose(); next(); return false;
        }
        Case current = cases.get(index);
        if (stage == 2) {
            release(); monitoring = false; handoff = null;
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(rotating, server);
                var catalog = ExplorationCatalog.get(server); var system = catalog.system("sol");
                double altitude = 99_900;
                if (current.fullAscent) {
                    var terrain = new ContinentalTerrain(EarthWorlds.terrainVersion(server), ContinentalTerrain.SEED);
                    var normal = new GeographicPosition(current.latitude, current.longitude, 0).normal();
                    var sample = terrain.sample(normal);
                    if (current.name.equals("earth-mountain")) {
                        require(sample.heightMeters() > 9000, "Mountain fixture lost its high terrain identity");
                    } else if (current.name.equals("earth-coast")) {
                        require(!sample.water() && sample.heightMeters() < 1
                                && Math.abs(sample.continentality()) < .01, "Coast fixture is not the sea margin");
                    }
                    altitude = sample.waterMeters() + 30;
                }
                start = new GeographicPosition(current.latitude, current.longitude, altitude);
                if (current.body.equals("earth")) { source = EarthChart.owner(start, EarthWorlds.terrainVersion(server)).orElseThrow(); }
                else {
                    var body = system.bodies().stream().filter(value -> value.id().equals(current.body)).findFirst().orElseThrow();
                    source = PlanetChart.owner(SolidPlanetProfile.create(system, body).orElseThrow(), start).orElseThrow();
                }
                var level = PlanetSurfaceWorlds.ensure(server, source);
                var feet = source.resolve(start).orElseThrow();
                player.teleportTo(level, feet.x(), feet.y(), feet.z(), 0, -90);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
            });
            next(); return false;
        }
        if (stage == 3) {
            if (ticks < 60 || game.level == null || game.screen != null
                    || !game.level.dimension().location().toString().equals(source.dimensionId())) { return false; }
            tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (stage == 4) {
            if (!controller.active() || System.nanoTime() - since < 1_000_000_000L) { return false; }
            require(controller.snapshot().jumpTicks() == 0, "R started a guided route on a canonical planet");
            controller.setSpeed(current.fullAscent ? 2560 : 160);
            speedRequestedAt = System.nanoTime();
            var field = RocketController.class.getDeclaredField("flightCamera"); field.setAccessible(true);
            ((FlightCamera) field.get(controller)).update(0, 0, 37, .016, 0);
            shot("ground"); next(); return false;
        }
        if (stage == 5) {
            if (ticks < 15) { return false; }
            double wanted = current.fullAscent ? 2560 : 160;
            if (controller.snapshot().speedMetersPerSecond() != wanted) {
                require(System.nanoTime() - since < 30_000_000_000L,
                        "Requested speed was not acknowledged before ascent: " + controller.snapshot().speedMetersPerSecond());
                if (System.nanoTime() - speedRequestedAt >= 1_000_000_000L) {
                    controller.setSpeed(wanted); speedRequestedAt = System.nanoTime();
                }
                return false;
            }
            handoff = null; loadingFrames = 0; handoffTicks = 0; handoffSeenAt = 0; monitoring = true;
            maximumGroundDrift = 0;
            resetMetrics(); game.mouseHandler.grabMouse(); game.options.keyUp.setDown(true);
            next(); return false;
        }
        if (stage == 6) {
            observe();
            if (handoff == null || !handoff.target().toString().equals("astraengine:flight")
                    || !game.level.dimension().location().equals(handoff.target())) { return false; }
            release();
            // A shader compile can queue several client ticks in one frame. Require real presentation time,
            // not only a tick count, before checking the normal100ms camera catch-up.
            if (handoffSeenAt == 0) { handoffSeenAt = System.nanoTime(); }
            if (++handoffTicks < 5 || System.nanoTime() - handoffSeenAt < 250_000_000L) { return false; }
            require(loadingFrames == 0, "Ascent displayed Loading/ReceivingLevelScreen");
            var address = address();
            require(address.altitudeMeters() >= 99_999 && address.altitudeMeters() < 100_500,
                    "Ascent used the wrong altitude: " + address.altitudeMeters());
            require(maximumGroundDrift < 20 && (rotating || start.normal().distance(address.normal()) * source.radiusMeters() < 20),
                    "Ascent lost its geographic address");
            require(minimumHeadingDot > .99 && minimumUpDot > .99, "Ascent snapped the full view: "
                    + minimumHeadingDot + "/" + minimumUpDot);
            evidence.append(current.name).append(" ascentStart=").append(start.altitudeMeters())
                    .append(" spaceAltitude=").append(address.altitudeMeters()).append(" loadingFrames=").append(loadingFrames)
                    .append(" maxStepMeters=").append(maximumPositionStep).append(" minHeadingDot=").append(minimumHeadingDot)
                    .append(" minUpDot=").append(minimumUpDot).append(" groundDriftMeters=").append(maximumGroundDrift).append(" rotating=").append(rotating).append('\n');
            shot("space"); monitoring = false; next(); return false;
        }
        if (stage == 7) {
            if (ticks < 20) { return false; }
            controller.setSpeed(160);
            var method = RocketController.class.getDeclaredMethod("aimDirection", SpaceVector.class); method.setAccessible(true);
            var display = controller.view();
            require((boolean) method.invoke(controller, frame(display).centerMeters().subtract(display.position())),
                    "Could not aim at the body");
            next(); return false;
        }
        if (stage == 8) {
            if (ticks < 60) { return false; }
            shot("orbital-down");
            handoff = null; handoffTicks = 0; loadingFrames = 0; monitoring = true; resetMetrics();
            game.options.keyUp.setDown(true); next(); return false;
        }
        if (stage == 9) {
            observe();
            if (game.level.dimension().location().toString().equals("astraengine:flight")) {
                lastSpaceAddress = address(); lastSpaceAt = System.nanoTime();
            }
            if (handoff == null || handoff.chart() == null || !game.level.dimension().location().equals(handoff.target())) { return false; }
            if (++handoffTicks < 5) { return false; }
            release(); require(loadingFrames == 0, "Descent displayed Loading/ReceivingLevelScreen");
            require(controller.active(), "Descent discarded free inspection");
            require(controller.snapshot().jumpTicks() == 0, "Descent started a forced camera route");
            require(handoff.chart().geographyId().equals(source.geographyId()), "Descent chose a different body");
            var address = handoff.chart().geographic(handoff.feet());
            require(Math.abs(address.altitudeMeters() - 99_999.99) < .001, "Descent did not commit at the physical shell");
            if (rotating) {
                require(lastSpaceAddress != null, "Rotating entry has no observed incoming space pose");
                double elapsed = (System.nanoTime() - lastSpaceAt) / 1_000_000_000.0;
                double allowed = 100 + source.radiusMeters() * Math.PI * 2 / 1200 * (elapsed + .15);
                require(lastSpaceAddress.normal().distance(address.normal()) * source.radiusMeters() < allowed,
                        "Rotating descent departed its incoming ray beyond the sampled spin interval");
                require(Math.abs(start.latitudeRadians() - address.latitudeRadians()) * source.radiusMeters() < 50,
                        "Equatorial spin changed the radial descent latitude");
            } else {
                require(start.normal().distance(address.normal()) * source.radiusMeters() < 40, "Descent chose a different location");
            }
            require(minimumHeadingDot > .99 && minimumUpDot > .99, "Descent snapped the full view: "
                    + minimumHeadingDot + "/" + minimumUpDot);
            evidence.append(current.name).append(" descentAltitude=").append(address.altitudeMeters())
                    .append(" loadingFrames=").append(loadingFrames).append(" maxStepMeters=").append(maximumPositionStep)
                    .append(" minHeadingDot=").append(minimumHeadingDot).append(" minUpDot=").append(minimumUpDot).append('\n');
            shot("returned"); monitoring = false; tap(GLFW.GLFW_KEY_R); next(); return false;
        }
        if (ticks < 20 || controller.active()) { return false; }
        if (++index < cases.size()) { stage = 2; ticks = 0; since = System.nanoTime(); return false; }
        release(); NeoForge.EVENT_BUS.unregister(handoffs);
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path);
        Files.writeString(path.resolve("space-boundaries.txt"), evidence, StandardOpenOption.CREATE_NEW);
        return true;
    }

    private BodyFixedFrame frame(RocketController.View view) {
        return source instanceof PlanetChart planet
                ? planet.profile().frame(controller.currentSystem(), view.orbitalSeconds())
                : view.surfaceFrame(controller.currentSystem(), SurfaceDefinition.byBody("earth"));
    }
    private GeographicPosition address() {
        var display = controller.view();
        var eye = frame(display).toBodyPoint(display.position());
        var location = GeographicPosition.fromBody(eye, source.radiusMeters());
        return new GeographicPosition(location.latitudeRadians(), location.longitudeRadians(),
                location.altitudeMeters() - game.player.getEyeHeight());
    }
    private void resetMetrics() {
        previousBodyEye = null; previousForward = null; previousUp = null;
        maximumPositionStep = 0; minimumHeadingDot = 1; minimumUpDot = 1;
    }
    private void observe() {
        var display = controller.view();
        var body = frame(display).toBodyPoint(display.position());
        if (stage == 6 && !game.level.dimension().location().toString().equals("astraengine:flight")) {
            CubeStorageChart chart = EarthChart.forDimension(game.level.dimension().location().toString(),
                    source instanceof EarthChart earth ? earth.terrainVersion() : 3).orElse(null);
            if (chart == null && game.level.dimension().location().toString().equals(source.dimensionId())) { chart = source; }
            var feet = new SpaceVector(game.player.getX(), game.player.getY(), game.player.getZ());
            if (chart != null && chart.contains(feet)) {
                maximumGroundDrift = Math.max(maximumGroundDrift,
                        chart.geographic(feet).normal().distance(start.normal()) * source.radiusMeters());
            }
        }
        if (ticks % 100 == 0) {
            dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_SPACE_POSITION case={} stage={} altitude={} speed={} dimension={}",
                    index, stage, body.length() - source.radiusMeters(), controller.snapshot().speedMetersPerSecond(),
                    game.level.dimension().location());
        }
        if (previousBodyEye != null) { maximumPositionStep = Math.max(maximumPositionStep, body.distance(previousBodyEye)); }
        var forward = controller.orientation().forward();
        if (previousForward != null) { minimumHeadingDot = Math.min(minimumHeadingDot, forward.dot(previousForward)); }
        var up = controller.orientation().up();
        if (previousUp != null) { minimumUpDot = Math.min(minimumUpDot, up.dot(previousUp)); }
        previousBodyEye = body; previousForward = forward; previousUp = up;
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error during the free boundary journey");
    }
    private void shot(String label) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path.resolve(cases.get(index).name + "-" + label + ".png"));
        }
    }
    private void next() { stage++; ticks = 0; since = System.nanoTime(); }
    private void release() { game.options.keyUp.setDown(false); }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer(); pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static void tap(int key) { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(key)); }
    private static void require(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private record Case(String name, String body, double latitude, double longitude, boolean fullAscent) { }
}
