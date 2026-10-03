package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.network.BoundaryHandoffReceivedEvent;
import dev.lexawhatt.astraengine.network.EarthBoundaryReceivedEvent;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.EarthChart;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real host movement and prepared respawn packets across face, pole, corner and radial storage seams. */
final class EarthCrossingScenario {
    private static final double R = EarthChart.RADIUS_METERS;
    private static final List<Case> CASES = List.of(
            new Case(CubeFace.POSITIVE_X, 3, new SpaceVector(0, 2030, 0), 0, 1),
            new Case(CubeFace.POSITIVE_X, 4, new SpaceVector(0, -2030, 0), 0, -1),
            new Case(CubeFace.POSITIVE_X, 3, new SpaceVector(R - 2, 500, 0), -90, 0),
            new Case(CubeFace.POSITIVE_Y, 3, new SpaceVector(0, 500, R - 2), 0, 0),
            new Case(CubeFace.NEGATIVE_Y, 3, new SpaceVector(0, 500, -R + 2), 180, 0),
            new Case(CubeFace.NEGATIVE_Z, 3, new SpaceVector(R - 2, 500, R - 2), -45, 0));
    private final Minecraft game = Minecraft.getInstance();
    private final Consumer<EarthBoundaryReceivedEvent> receiver = event -> observation = event.payload().snapshot();
    private final Consumer<BoundaryHandoffReceivedEvent> handoffs = event -> {
        if (!event.payload().cancelled()) { handoff = event.payload(); }
    };
    private final Consumer<RenderLevelStageEvent> frames = event -> {
        if (this.stage == 2 && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            this.renderedFrames++;
            if (game.screen instanceof ReceivingLevelScreen) { this.waitingFrames++; }
        }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private EarthBoundarySnapshot observation;
    private dev.lexawhatt.astraengine.network.BoundaryHandoffPayload handoff;
    private CubeStorageChart source;
    private CubeStorageChart currentChart;
    private SpaceVector previousBody;
    private SpaceVector previousForward;
    private double maximumStep;
    private double minimumHeadingDot = 1;
    private int index;
    private int stage;
    private int stableTicks;
    private int renderedFrames;
    private int waitingFrames;
    private long since = System.nanoTime();
    private final StringBuilder evidence = new StringBuilder();

    EarthCrossingScenario() {
        game.options.renderDistance().set(3); game.options.simulationDistance().set(5);
        game.options.bobView().set(false); game.options.hideGui = true;
        NeoForge.EVENT_BUS.addListener(receiver); NeoForge.EVENT_BUS.addListener(handoffs);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        require(System.nanoTime() - since < 180_000_000_000L, "Crossing timed out: " + index + "/" + stage
                + " level=" + (game.level == null ? null : game.level.dimension())
                + " feet=" + (game.player == null ? null : game.player.position())
                + " screen=" + game.screen + " observation=" + (observation == null ? null
                        : observation.source() + "/" + observation.complete() + "/" + observation.sections().size()));
        if (!pending.isDone()) { return false; }
        pending.join();
        if (stage == 4) { return finish(); }
        Case next = CASES.get(index);
        if (stage == 0) {
            // An unchanged complete snapshot may already describe the reverse crossing; the server correctly
            // does not resend identical data merely because this fixture advances to the next case.
            releaseKeys(); handoff = null; previousBody = null; previousForward = null;
            maximumStep = 0; minimumHeadingDot = 1; renderedFrames = 0; waitingFrames = 0;
            server(server -> {
                source = new EarthChart(next.face, next.band, EarthWorlds.terrainVersion(server));
                var level = PlanetSurfaceWorlds.ensure(server, source);
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(level, next.feet.x(), next.feet.y(), next.feet.z(), next.yaw, 0);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setDeltaMovement(Vec3.ZERO);
            });
            stage = 1; stableTicks = 0; since = System.nanoTime(); return false;
        }
        if (stage == 1) {
            if (game.player != null && System.nanoTime() - since > 10_000_000_000L
                    && System.nanoTime() % 5_000_000_000L < 60_000_000L) {
                dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_CROSSING_WAIT case={} level={} feet={} screen={} snapshot={}",
                        index, game.level.dimension(), game.player.position(), game.screen,
                        observation == null ? "none" : observation.source() + "/" + observation.complete() + "/" + observation.sections().size());
            }
            if (game.level == null || !game.level.dimension().location().toString().equals(source.dimensionId())
                    || observation == null || !observation.source().equals(source) || !observation.complete()
                    || game.screen != null || ++stableTicks < 50) { return false; }
            currentChart = source;
            capture("before");
            game.mouseHandler.grabMouse();
            if (next.vertical > 0) { game.options.keyJump.setDown(true); }
            else if (next.vertical < 0) { game.options.keyShift.setDown(true); }
            else { game.options.keyUp.setDown(true); }
            stage = 2; since = System.nanoTime(); return false;
        }
        if (stage == 2) {
            if (game.screen instanceof ReceivingLevelScreen) { this.waitingFrames++; }
            if (game.level == null || game.player == null) { throw new AssertionError("Prepared crossing lost its host player"); }
            if (handoff != null && game.level.dimension().location().toString().equals(handoff.target().dimensionId())) {
                currentChart = handoff.target();
            }
            var feet = new SpaceVector(game.player.getX(), game.player.getY(), game.player.getZ());
            var geographic = currentChart.projectedGeographic(feet);
            var body = geographic.toBody(currentChart.radiusMeters());
            var forward = currentChart.tangentFrame(feet.x(), feet.z(), geographic.altitudeMeters())
                    .toBodyOrientation(FlightOrientation.fromAngles(game.player.getYRot(), game.player.getXRot(), 0)).forward();
            if (previousBody != null) { maximumStep = Math.max(maximumStep, body.distance(previousBody)); }
            if (previousForward != null) { minimumHeadingDot = Math.min(minimumHeadingDot, forward.dot(previousForward)); }
            previousBody = body; previousForward = forward;
            if (currentChart.equals(source)) { return false; }
            releaseKeys(); capture("after");
            require(waitingFrames == 0, "Prepared crossing displayed a receiving-level frame");
            require(maximumStep < 4, "Crossing changed physical position by " + maximumStep);
            require(minimumHeadingDot > .999, "Crossing snapped physical view: dot=" + minimumHeadingDot);
            require(renderedFrames > 0, "Crossing did not render real world frames");
            evidence.append("case=").append(index).append(" source=").append(source.dimensionId())
                    .append(" target=").append(currentChart.dimensionId()).append(" maxStepMeters=").append(maximumStep)
                    .append(" minHeadingDot=").append(minimumHeadingDot).append(" frames=").append(renderedFrames)
                    .append(" loadingFrames=").append(waitingFrames).append('\n');
            server(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                require(player.serverLevel().dimension().location().toString().equals(currentChart.dimensionId()),
                        "Client handoff disagrees with actual server world");
                require(currentChart.contains(new SpaceVector(player.getX(), player.getY(), player.getZ())),
                        "Transferred player has no canonical storage owner");
            });
            stage = 3; stableTicks = 0; since = System.nanoTime(); return false;
        }
        if (++stableTicks < 20) { return false; }
        if (++index < CASES.size()) { stage = 0; return false; }
        stage = 4; return finish();
    }

    private void capture(String label) throws Exception {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(path.resolve("crossing-" + index + "-" + label + ".png"));
        }
    }

    private boolean finish() throws Exception {
        releaseKeys(); NeoForge.EVENT_BUS.unregister(receiver); NeoForge.EVENT_BUS.unregister(handoffs);
        NeoForge.EVENT_BUS.unregister(frames);
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path);
        Files.writeString(path.resolve("earth-crossings.txt"), evidence, StandardOpenOption.CREATE_NEW);
        return true;
    }
    private void releaseKeys() {
        game.options.keyJump.setDown(false); game.options.keyShift.setDown(false); game.options.keyUp.setDown(false);
    }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private record Case(CubeFace face, int band, SpaceVector feet, float yaw, int vertical) { }
}
