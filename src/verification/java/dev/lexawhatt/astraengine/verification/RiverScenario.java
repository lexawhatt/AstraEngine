package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;
import dev.lexawhatt.astraengine.compat.distant.EmptyFlightLodGenerator;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.RocketService;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/** Disposable usual-pack rivers, ocean mouth, relief and actual optional-DH worker verification. */
final class RiverScenario {
    private record View(String name, GeographicPosition observer, float yaw, float pitch) { }
    private final Minecraft game = Minecraft.getInstance();
    private final ContinentalTerrain terrain = new ContinentalTerrain(3, ContinentalTerrain.SEED);
    private final DistantTerrainProbe probe = new DistantTerrainProbe();
    private final List<View> views = new ArrayList<>();
    private final StringBuilder evidence = new StringBuilder("Routed v3 geography / native usual mod pack\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int step, ticks, index;

    RiverScenario(boolean benchmark) {
        if (!benchmark) { step = 1; }
        game.options.renderDistance().set(6);
        game.options.simulationDistance().set(5);
        game.options.cloudStatus().set(CloudStatus.OFF);
        game.options.hideGui = true;
        game.options.bobView().set(false);
        var atlas = terrain.rivers().orElseThrow();
        int channel = -1, mouth = -1;
        double score = 0;
        for (int cell = 0; cell < atlas.cellCount(); cell++) {
            if (!atlas.river(cell)) { continue; }
            var normal = atlas.point(cell, .5);
            if (CubeFace.containing(normal) != CubeFace.POSITIVE_X) { continue; }
            var sample = terrain.sample(normal);
            if (sample.temperature() < 8) { continue; }
            if (sample.waterMeters() > 100 && sample.waterMeters() < 1200 && atlas.drainageArea(cell) < 20000 && atlas.drainageArea(cell) > score) {
                score = atlas.drainageArea(cell); channel = cell;
            }
            if (atlas.downstream(atlas.downstream(cell)) < 0 && atlas.drainageArea(cell) > 3000) { mouth = cell; }
        }
        require(channel >= 0 && mouth >= 0, "Missing warm native river and mouth sites");
        addRiverView("river-valley", channel, .45, 28, 6);
        addRiverView("river-overview", channel, .45, 800, 55);
        double coastT = .5;
        var old = new ContinentalTerrain(2, ContinentalTerrain.SEED);
        double error = Double.MAX_VALUE;
        for (int i = 0; i <= 128; i++) {
            double t = i / 128.0;
            double height = old.sample(atlas.point(mouth, t)).heightMeters();
            if (Math.abs(height - 30) < error) { error = Math.abs(height - 30); coastT = t; }
        }
        addRiverView("river-ocean-mouth", mouth, coastT, 45, 8);
        Random random = new Random(741);
        SpaceVector mountain = null, dryUplands = null; double highest = 0;
        for (int i = 0; i < 12000; i++) {
            var direction = new SpaceVector(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1,
                    random.nextDouble() * 2 - 1).normalized();
            var sample = terrain.sample(direction);
            double height = sample.heightMeters();
            if (height > highest) { highest = height; mountain = direction; }
            if (dryUplands == null && !sample.water() && height > 500 && height < 1800
                    && sample.temperature() > 18 && sample.moisture() < .30) { dryUplands = direction; }
        }
        require(mountain != null && highest > 7500, "Lost large mountains");
        views.add(new View("mountain-ridges", address(mountain, highest + 350), 40, 15));
        require(dryUplands != null, "Lost dry warm uplands");
        views.add(new View("dry-uplands", address(dryUplands, terrain.sample(dryUplands).heightMeters() + 160), 110, 15));
        evidence.append("channelCell=").append(channel).append(" mouthCell=").append(mouth)
                .append(" drainageKm2=").append(score).append(" peakMeters=").append(highest).append('\n');
    }

    private void addRiverView(String name, int cell, double t, double height, float pitch) {
        var atlas = terrain.rivers().orElseThrow();
        var normal = atlas.point(cell, t);
        var downstream = atlas.point(cell, Math.min(1, t + .03));
        if (height < 100) {
            var across = new SpaceVector(normal.y() * downstream.z() - normal.z() * downstream.y(),
                    normal.z() * downstream.x() - normal.x() * downstream.z(),
                    normal.x() * downstream.y() - normal.y() * downstream.x()).normalized();
            normal = normal.multiply(EarthChart.RADIUS_METERS)
                    .add(across.multiply(atlas.channelHalfWidthMeters(cell) + 25)).normalized();
        }
        var sample = terrain.sample(normal);
        var observer = address(normal, sample.waterMeters() + height);
        var chart = EarthChart.owner(observer, 3).orElseThrow();
        var point = chart.resolve(observer).orElseThrow();
        var target = atlas.point(cell, Math.min(1, t + .03));
        double scale = EarthChart.RADIUS_METERS / target.dot(chart.face().outward());
        double dx = target.dot(chart.face().u()) * scale - point.x();
        double dz = target.dot(chart.face().v()) * scale - point.z();
        views.add(new View(name, observer, (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch));
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (!probe.configure()) { return false; }
        if (step == 0) {
            if (++ticks < 120) { return false; }
            IDhApiLevelWrapper earth = null, flight = null;
            for (var candidate : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                if (candidate.getWrappedMcObject() instanceof ServerLevel level) {
                    if (level.dimension().equals(game.level.dimension())) { earth = candidate; }
                    if (level.dimension().equals(RocketService.FLIGHT)) { flight = candidate; }
                }
            }
            if (earth == null || flight == null) { return false; }
            require(WorldGeneratorInjector.INSTANCE.get(flight) instanceof EmptyFlightLodGenerator,
                    "Real flight world did not register its empty generator");
            var capturedEarth = earth; var capturedFlight = flight;
            pending = CompletableFuture.runAsync(() -> {
                String result = RiverLodBenchmark.run(capturedEarth, WorldGeneratorInjector.INSTANCE.get(capturedEarth), capturedFlight);
                synchronized (evidence) { evidence.append(result); }
                try {
                    var folder = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(folder);
                    Files.writeString(folder.resolve("benchmark.txt"), result, StandardOpenOption.CREATE_NEW);
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            step = 1; ticks = 0; return false;
        }
        if (step == 1) {
            if (index >= views.size()) {
                Files.writeString(game.gameDirectory.toPath().resolve("evidence/rivers.txt"), evidence, StandardOpenOption.CREATE_NEW);
                probe.close(); return true;
            }
            var view = views.get(index);
            var chart = EarthChart.owner(view.observer(), 3).orElseThrow();
            var point = chart.resolve(view.observer()).orElseThrow();
            var server = game.getSingleplayerServer();
            var playerId = game.player.getUUID();
            pending = server.submit(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                var rules = server.overworld().getGameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                server.overworld().setDayTime(6000 - Math.round(view.observer().longitudeRadians() / (2 * Math.PI) * 24000));
                server.overworld().setWeatherParameters(100000, 0, false, false);
                player.teleportTo(server.getLevel(EarthWorlds.dimension(chart)), point.x(), point.y(), point.z(), view.yaw(), view.pitch());
                player.getAbilities().flying = true; player.onUpdateAbilities();
            });
            ticks = 0; step = 2; return false;
        }
        if (++ticks < (step == 2 ? 220 : 40) || !game.level.hasChunkAt(game.player.blockPosition())) { return false; }
        var view = views.get(index);
        var folder = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(folder);
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(folder.resolve(view.name() + (step == 2 ? "" : "-dh-off") + ".png"));
        }
        evidence.append(view).append('\n').append(probe.description()).append('\n');
        evidence.append("hostFar=").append(game.gameRenderer.getDepthFar())
                .append(" nativeChunks=").append(game.options.getEffectiveRenderDistance())
                .append(" camera=").append(game.gameRenderer.getMainCamera().getPosition()).append('\n');
        if (step == 2) { probe.renderEnabled(false); step = 3; ticks = 0; return false; }
        probe.renderEnabled(true);
        index++; step = 1; ticks = 0;
        return false;
    }

    private static GeographicPosition address(SpaceVector normal, double altitude) {
        var position = GeographicPosition.fromBody(normal, 1);
        return new GeographicPosition(position.latitudeRadians(), position.longitudeRadians(), altitude);
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalStateException(message); } }
}
