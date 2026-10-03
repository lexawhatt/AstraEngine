package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Native landscape/host coverage and corner-join regression, plus repeated bounded streaming measurements. */
final class HorizonJoinScenario {
    private record View(String name, GeographicPosition position, float yaw, float pitch) { }
    private static final int TERRAIN_VERSION = ContinentalTerrain.CURRENT_VERSION;
    private final Minecraft game = Minecraft.getInstance();
    private final boolean usual = System.getProperty("astraengine.verify.phase", "").endsWith("-usual");
    private final boolean residentQualification = System.getProperty("astraengine.verify.phase", "").contains("-resident-");
    private final boolean targetQualification = System.getProperty("astraengine.verify.phase", "").contains("-target-");
    private final boolean profileRoutes = !System.getProperty("astraengine.verify.phase", "").contains("-unprofiled-");
    private final ContinentalTerrain terrain = new ContinentalTerrain(TERRAIN_VERSION, ContinentalTerrain.SEED);
    private final List<View> views = new ArrayList<>();
    private final List<Double> frames = new ArrayList<>();
    private final List<Double> serverTicks = Collections.synchronizedList(new ArrayList<>());
    private final Consumer<RenderFrameEvent.Post> render = event -> frame();
    private final Consumer<ServerTickEvent.Pre> tickStart = event -> tickStarted = System.nanoTime();
    private final Consumer<ServerTickEvent.Post> tickEnd = event -> {
        if (this.measure && this.tickStarted != 0) { serverTicks.add((System.nanoTime() - this.tickStarted) / 1e6); }
    };
    private final StringBuilder evidence = new StringBuilder("Native geographic join / copied mod pack without DH\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private volatile boolean measure;
    private long frameStarted, tickStarted, windowStarted, gcMillis, allocatedBytes;
    private int viewIndex, step, ticks, routeTick, routePass;
    private EarthChart routeChart;
    private SpaceVector routeStart;
    private Object landscape;
    private Recording routeProfile;
    private RouteWindow routeWindow;
    private CompletableFuture<Residency> residencyProbe;
    private Residency clientResidency;
    private long settleStarted, stableStarted, nextResidencyProbe;
    private int clientMissingSamples;
    private volatile int serverMissingSamples;
    private int residentSamples;
    private boolean settleThreadsCaptured;
    private CompletableFuture<Boolean> targetInformation;
    private boolean targetInformationReady;
    private final long targetInformationStarted = System.nanoTime();
    private final Coverage targetClient = new Coverage();
    private final Coverage targetServer = new Coverage();

    private record Residency(int visible, int expectedVisible, int route, int expectedRoute,
                             int requestedRadius, int effectiveRadius) {
        boolean complete() {
            return visible == expectedVisible && route == expectedRoute && requestedRadius == effectiveRadius;
        }
    }

    HorizonJoinScenario() {
        require(!net.neoforged.fml.ModList.get().isLoaded("distanthorizons"),
                "Native landscape qualification requires the copied mod pack without Distant Horizons");
        game.options.renderDistance().set(usual ? 12 : 6);
        game.options.simulationDistance().set(usual ? 12 : 5);
        if (targetQualification) { game.options.broadcastOptions(); }
        game.options.cloudStatus().set(CloudStatus.OFF);
        game.options.hideGui = true;
        game.options.bobView().set(false);
        game.options.fov().set(70);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        var atlas = terrain.rivers().orElseThrow();
        int channel = -1; double area = 0;
        for (int cell = 0; cell < atlas.cellCount(); cell++) {
            if (!atlas.river(cell)) { continue; }
            var normal = atlas.point(cell, .5);
            var sample = terrain.sample(normal);
            if (CubeFace.containing(normal) == CubeFace.POSITIVE_X && sample.temperature() > 8
                    && sample.waterMeters() > 100 && sample.waterMeters() < 1200
                    && atlas.drainageArea(cell) < 20000 && atlas.drainageArea(cell) > area) {
                channel = cell; area = atlas.drainageArea(cell);
            }
        }
        require(channel >= 0, "Native A1 river fixture missing");
        var river = atlas.point(channel, .45);
        var downstream = atlas.point(channel, .48);
        var address = address(river, terrain.sample(river).waterMeters() + 800);
        var chart = EarthChart.owner(address, TERRAIN_VERSION).orElseThrow();
        var point = chart.resolve(address).orElseThrow();
        double scale = EarthChart.RADIUS_METERS / downstream.dot(chart.face().outward());
        float yaw = (float) Math.toDegrees(Math.atan2(-(downstream.dot(chart.face().u()) * scale - point.x()),
                downstream.dot(chart.face().v()) * scale - point.z()));
        views.add(new View("river-valley", address(river, terrain.sample(river).waterMeters() + 28), yaw, 6));
        views.add(new View("river-overhead", address, yaw, 55));
        var cornerChart = new EarthChart(CubeFace.POSITIVE_X, 2, TERRAIN_VERSION);
        views.add(new View("cube-corner", address(cornerChart.normal(EarthChart.RADIUS_METERS - 4,
                EarthChart.RADIUS_METERS - 4), 10156), 45, 8));
        views.add(new View("polar-ice", new GeographicPosition(Math.PI / 2, 0, 11000), 36, 15));
        var random = new Random(41);
        SpaceVector forest = null, mountain = null; double height = 0;
        for (int i = 0; i < 16000; i++) {
            var normal = new SpaceVector(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1,
                    random.nextDouble() * 2 - 1).normalized();
            var sample = terrain.sample(normal);
            if (sample.heightMeters() > height) { height = sample.heightMeters(); mountain = normal; }
            if (forest == null && EarthClimate.at(sample) == EarthClimate.FOREST
                    && sample.heightMeters() > 100 && sample.heightMeters() < 1000) { forest = normal; }
        }
        require(forest != null && mountain != null, "Native A1 forest/mountain fixtures missing");
        views.add(new View("forest", address(forest, terrain.sample(forest).heightMeters() + 50), 30, 15));
        views.add(new View("summit", address(mountain, height + 180), 30, 15));
        views.add(views.get(1));
        evidence.append("terrainVersion=").append(TERRAIN_VERSION).append(" Java=").append(System.getProperty("java.version"))
                .append(" heapMax=").append(Runtime.getRuntime().maxMemory())
                .append(" GPU=").append(GL11.glGetString(GL11.GL_RENDERER))
                .append(" driver=").append(GL11.glGetString(GL11.GL_VERSION))
                .append(" renderChunks=").append(game.options.renderDistance().get())
                .append(" simulationChunks=").append(game.options.simulationDistance().get())
                .append(" maxFps=").append(game.options.framerateLimit().get())
                .append(" vsync=").append(game.options.enableVsync().get()).append('\n');
        NeoForge.EVENT_BUS.addListener(render);
        NeoForge.EVENT_BUS.addListener(tickStart);
        NeoForge.EVENT_BUS.addListener(tickEnd);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (targetQualification && !targetInformationReady) {
            if (targetInformation == null) {
                targetInformation = game.getSingleplayerServer().submit(() -> {
                    var server = game.getSingleplayerServer();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    return player.requestedViewDistance() == 12 && server.getPlayerList().getViewDistance() == 12
                            && player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned view
                            && view.viewDistance() == 12;
                });
                return false;
            }
            if (!targetInformation.isDone()) { return false; }
            targetInformationReady = targetInformation.join(); targetInformation = null;
            require(targetInformationReady || System.nanoTime() - targetInformationStarted < 30_000_000_000L,
                    "Actual requested/server-tracked12-radius target was not acknowledged before the itinerary");
            if (!targetInformationReady) { return false; }
            evidence.append("Target setup: actual requested/server-tracked12 verified before the first scene; "
                    + "original geographic itinerary and two240m/600tick routes, one30s ordinary settle; native pipeline only.\n");
        }
        if (step == 0) {
            var view = views.get(viewIndex);
            var chart = EarthChart.owner(view.position(), TERRAIN_VERSION).orElseThrow();
            var point = chart.resolve(view.position()).orElseThrow();
            pending = game.getSingleplayerServer().submit(() -> {
                var server = game.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                var rules = server.overworld().getGameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                server.overworld().setDayTime(localNoon(view));
                server.overworld().setWeatherParameters(100000, 0, false, false);
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(server.getLevel(EarthWorlds.dimension(chart)), point.x(), point.y(), point.z(), view.yaw(), view.pitch());
            });
            step = 1; ticks = 0; return false;
        }
        if (step == 1) {
            if (++ticks < 180) { return false; }
            Object renderer = field(game.level.effects(), "renderer");
            landscape = field(renderer, "landscape");
            if (field(landscape, "frameDepth") == null) { return false; }
            capture("native-landscape");
            step = viewIndex == 1 ? 2 : 3;
            ticks = 0; return false;
        }
        if (step == 2) {
            if (++ticks < 50) { return false; }
            capture("native-settled");
            step = 3; ticks = 0; return false;
        }
        if (step == 3) {
            if (++ticks < 50) { return false; }
            capture("native-after-settle");
            if (++viewIndex < views.size()) {
                if (viewIndex == views.size() - 1) {
                    GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 960, 540);
                    pending = game.reloadResourcePacks();
                }
                step = 0; return false;
            }
            GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
            routeChart = EarthChart.owner(views.get(4).position(), TERRAIN_VERSION).orElseThrow();
            routeStart = routeChart.resolve(views.get(4).position()).orElseThrow();
            if (targetQualification) {
                pending = game.getSingleplayerServer().submit(() -> {
                    var server = game.getSingleplayerServer();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    server.overworld().setDayTime(localNoon(views.get(4)));
                    player.teleportTo(server.getLevel(EarthWorlds.dimension(routeChart)), routeStart.x(), routeStart.y(),
                            routeStart.z(), -90, 12);
                });
                settleStarted = System.nanoTime(); nextResidencyProbe = settleStarted; step = 8;
                evidence.append("Target settle: bounded30s; no force-loading or queue-drain precondition.\n");
            } else { step = 4; }
            ticks = 0; return false;
        }
        if (step == 4) {
            if (profileRoutes && routeProfile == null) {
                routeProfile = new Recording(Configuration.getConfiguration("profile"));
                routeProfile.setName(routePass == 2 ? "Astra resident forest route" : "Astra usual forest routes");
                routeProfile.setMaxAge(Duration.ofMinutes(3));
                routeProfile.setMaxSize(64L * 1024 * 1024);
                routeProfile.setDuration(Duration.ofMinutes(3));
                routeProfile.setDestination(output().resolve(routePass == 2 ? "horizon-resident.jfr" : "horizon-routes.jfr"));
                routeProfile.setDumpOnExit(true);
                routeProfile.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
                routeProfile.start();
                evidence.append("JFR=profile,ExecutionSample20ms,maxAge180s,maxBytes67108864 ")
                        .append("file=").append(routePass == 2 ? "horizon-resident.jfr" : "horizon-routes.jfr")
                        .append("; profiling overhead is included in measured routes\n");
            }
            pending = game.getSingleplayerServer().submit(() -> {
                var server = game.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                server.overworld().setDayTime(localNoon(views.get(4)));
                player.teleportTo(server.getLevel(EarthWorlds.dimension(routeChart)), routeStart.x(), routeStart.y(),
                        routeStart.z(), -90, 12);
            });
            frames.clear(); serverTicks.clear(); frameStarted = 0; routeTick = 0;
            if (targetQualification) {
                targetClient.clear(); targetServer.clear();
                nativeMetrics();
            }
            windowStarted = System.nanoTime(); gcMillis = gcMillis(); allocatedBytes = allocatedBytes(); measure = true;
            if (profileRoutes) {
                routeWindow = new RouteWindow(); routeWindow.pass = routePass; routeWindow.begin();
            } else {
                evidence.append("JFR=disabled; ordinary frame/tick/allocation/GC/native residency counters retained; ")
                        .append("absolute acceptance only, not a profiler-matched speedup comparison\n");
            }
            evidence.append("routePass=").append(routePass).append(" startNanos=").append(windowStarted)
                    .append(" wallTime=").append(Instant.now())
                    .append(" source=").append(routeChart).append(" origin=").append(routeStart).append('\n');
            step = 5; return false;
        }
        if (step == 5) {
            if (routeTick++ % 5 == 0) {
                final double dx = routeTick * .4;
                final boolean checkingResident = routePass == 2;
                if (targetQualification) { targetClient.add(clientResidency(routeStart.x() + dx, routeStart.z())); }
                if (checkingResident) {
                    residentSamples++;
                    if (!clientResidency(routeStart.x() + dx, routeStart.z()).complete()) { clientMissingSamples++; }
                }
                pending = game.getSingleplayerServer().submit(() -> {
                    var server = game.getSingleplayerServer();
                    var player = server.getPlayerList().getPlayers().getFirst();
                    if (targetQualification) { targetServer.add(serverResidency(routeStart.x() + dx, routeStart.z())); }
                    if (checkingResident && !serverResidency(routeStart.x() + dx, routeStart.z()).complete()) {
                        serverMissingSamples++;
                    }
                    player.connection.teleport(routeStart.x() + dx, routeStart.y(), routeStart.z(), -90, 12);
                });
            }
            if (routeTick % 100 == 0) { nativeMetrics(); }
            if (routeTick < 600) { return false; }
            measure = false;
            if (routeWindow != null) { routeWindow.end(); routeWindow.commit(); routeWindow = null; }
            evidence.append("routePass=").append(routePass).append(" seconds=")
                    .append((System.nanoTime() - windowStarted) / 1e9)
                    .append(" frame=").append(summary(frames)).append(" tick=").append(summary(serverTicks))
                    .append(" GC_ms=").append(gcMillis() - gcMillis)
                    .append(" allocatedBytes=").append(allocatedBytes < 0 ? -1 : allocatedBytes() - allocatedBytes)
                    .append(" usedHeap=").append(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).append('\n');
            if (targetQualification) {
                nativeMetrics();
                evidence.append("Target route=").append(routePass).append(" clientCoverage=").append(targetClient)
                        .append(" serverCoverage=").append(targetServer)
                        .append(" frameP95Target50ms=").append(percentile95(frames) <= 50)
                        .append(" serverTickP95Target50ms=").append(percentile95(serverTicks) <= 50)
                        .append("; missing moving-view edges are streaming; route-center residency is separate.\n");
            }
            if (routePass == 1) {
                evidence.append("Repeated-streaming target50ms: frameP95=").append(percentile95(frames) <= 50)
                        .append(" serverTickP95=").append(percentile95(serverTicks) <= 50)
                        .append("; fixture completion is not a performance acceptance claim\n");
            }
            if (routePass == 2) {
                boolean allResident = clientMissingSamples == 0 && serverMissingSamples == 0 && residentSamples > 0;
                evidence.append("Resident moving-view checks=").append(residentSamples)
                        .append(" clientMissing=").append(clientMissingSamples)
                        .append(" serverMissing=").append(serverMissingSamples)
                        .append(" fullyResident=").append(allResident)
                        .append(" frameP95Target50ms=").append(percentile95(frames) <= 50)
                        .append(" serverTickP95Target50ms=").append(percentile95(serverTicks) <= 50)
                        .append("; any missing view/route chunk makes this a streaming result\n");
            }
            if (targetQualification) { captureRoute(); }
            if (++routePass < 2) { step = 4; return false; }
            closeProfile();
            if (residentQualification && routePass == 2) {
                // The first two passes deliberately preserve the historical streaming workload.
                // Changing the local option does not update the server's per-player chunk tracking.
                game.options.broadcastOptions();
                pending = game.getSingleplayerServer().submit(() -> {
                    var player = game.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.connection.teleport(routeStart.x() + 120, routeStart.y(), routeStart.z(), -90, 12);
                });
                step = 6; return false;
            }
            return finish();
        }
        if (step == 6) {
            settleStarted = System.nanoTime(); nextResidencyProbe = settleStarted; step = 7;
            evidence.append("Resident settle: max180s, stable20s, complete current native view and all route-center chunks,")
                    .append(" requested client distance broadcast through the host packet,")
                    .append(" no forced chunk loads/tickets; native-only residency qualification\n");
            return false;
        }
        if (step == 7) {
            long now = System.nanoTime();
            if (residencyProbe != null) {
                var server = residencyProbe.join(); residencyProbe = null;
                boolean settled = clientResidency.complete() && server.complete();
                if (!settled) { stableStarted = 0; }
                else if (stableStarted == 0) { stableStarted = now; }
                evidence.append("settleSeconds=").append((now - settleStarted) / 1e9)
                        .append(" client=").append(clientResidency).append(" server=").append(server)
                        .append(" stableSeconds=").append(stableStarted == 0 ? 0 : (now - stableStarted) / 1e9)
                        .append('\n');
                Files.writeString(output().resolve("resident-settle.txt"), evidence);
                if (!settleThreadsCaptured && now - settleStarted >= 90_000_000_000L) {
                    var stacks = new StringBuilder("Bounded settle observation; measured routes are not running\n");
                    for (var info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                        stacks.append(info.getThreadName()).append(" state=").append(info.getThreadState())
                                .append(" lock=").append(info.getLockName()).append(" owner=").append(info.getLockOwnerName())
                                .append('\n');
                        for (var frame : info.getStackTrace()) { stacks.append("    ").append(frame).append('\n'); }
                    }
                    Files.writeString(output().resolve("resident-threads.txt"), stacks, StandardOpenOption.CREATE_NEW);
                    settleThreadsCaptured = true;
                }
                if (stableStarted != 0 && now - stableStarted >= 20_000_000_000L) {
                    evidence.append("Resident settle PASS; moving-view residency remains independently checked\n");
                    step = 4; return false;
                }
            }
            if (now - settleStarted >= 180_000_000_000L) {
                evidence.append("Resident settle FAILED: timeout; no resident performance acceptance\n");
                finish();
                throw new IllegalStateException("Resident route did not become ready within 180 seconds; evidence retained");
            }
            if (now >= nextResidencyProbe) {
                double x = routeStart.x() + 120, z = routeStart.z();
                clientResidency = clientResidency(x, z);
                residencyProbe = game.getSingleplayerServer().submit(() -> serverResidency(x, z));
                pending = residencyProbe; nextResidencyProbe = now + 1_000_000_000L;
            }
            return false;
        }
        if (step == 8) {
            long now = System.nanoTime();
            if (residencyProbe != null) {
                evidence.append("targetSettleSeconds=").append((now - settleStarted) / 1e9)
                        .append(" client=").append(clientResidency).append(" server=").append(residencyProbe.join())
                        .append('\n');
                residencyProbe = null;
                Files.writeString(output().resolve("target-settle.txt"), evidence);
            }
            if (now - settleStarted >= 30_000_000_000L) { step = 4; return false; }
            if (now >= nextResidencyProbe) {
                clientResidency = clientResidency(routeStart.x(), routeStart.z());
                residencyProbe = game.getSingleplayerServer().submit(() -> serverResidency(routeStart.x(), routeStart.z()));
                pending = residencyProbe; nextResidencyProbe = now + 1_000_000_000L;
            }
            return false;
        }
        throw new IllegalStateException("Unexpected A1 fixture state");
    }

    private Residency clientResidency(double x, double z) {
        int radius = game.options.renderDistance().get();
        return residency(x, z, radius, radius, game.options.getEffectiveRenderDistance(),
                (cx, cz) -> game.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null);
    }

    private Residency serverResidency(double x, double z) {
        var server = game.getSingleplayerServer();
        var level = server.getLevel(EarthWorlds.dimension(routeChart));
        int radius = server.getPlayerList().getViewDistance();
        var player = server.getPlayerList().getPlayers().getFirst();
        int trackingRadius = player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned positioned
                ? positioned.viewDistance() : -1;
        return residency(x, z, radius, player.requestedViewDistance(), trackingRadius,
                (cx, cz) -> level.getChunkSource().getChunkNow(cx, cz) != null);
    }

    private Residency residency(double x, double z, int radius, int requestedRadius, int effectiveRadius,
                                java.util.function.BiPredicate<Integer, Integer> loaded) {
        int centerX = Math.floorDiv((int) Math.floor(x), 16), centerZ = Math.floorDiv((int) Math.floor(z), 16);
        int expected = 0, present = 0;
        for (int cx = centerX - radius - 1; cx <= centerX + radius + 1; cx++) {
            for (int cz = centerZ - radius - 1; cz <= centerZ + radius + 1; cz++) {
                if (!ChunkTrackingView.isInViewDistance(centerX, centerZ, radius, cx, cz)) { continue; }
                expected++;
                if (loaded.test(cx, cz)) { present++; }
            }
        }
        int routeZ = Math.floorDiv((int) Math.floor(routeStart.z()), 16);
        int routeMin = Math.floorDiv((int) Math.floor(routeStart.x()), 16);
        int routeMax = Math.floorDiv((int) Math.floor(routeStart.x() + 240), 16);
        int routePresent = 0;
        for (int cx = routeMin; cx <= routeMax; cx++) { if (loaded.test(cx, routeZ)) { routePresent++; } }
        return new Residency(present, expected, routePresent, routeMax - routeMin + 1, requestedRadius, effectiveRadius);
    }

    private void closeProfile() {
        if (routeProfile == null) { return; }
        if (routeProfile.getState() == jdk.jfr.RecordingState.RUNNING) { routeProfile.stop(); }
        routeProfile.close(); routeProfile = null;
    }

    private boolean finish() throws java.io.IOException {
        closeProfile();
        Files.writeString(output().resolve("measurements.txt"), evidence, StandardOpenOption.CREATE_NEW);
        NeoForge.EVENT_BUS.unregister(render); NeoForge.EVENT_BUS.unregister(tickStart);
        NeoForge.EVENT_BUS.unregister(tickEnd); return true;
    }

    private void captureRoute() throws Exception {
        // Pixel/depth readback is deliberately outside the timed frame/tick window.
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve("route-" + routePass + "-end.png"));
        }
        evidence.append("Route end pass=").append(routePass).append(" camera=")
                .append(game.gameRenderer.getMainCamera().getPosition()).append(" dimension=").append(game.level.dimension())
                .append(" hostRenderedSections=").append(game.levelRenderer.countRenderedSections())
                .append(" sectionStatistics=").append(game.levelRenderer.getSectionStatistics())
                .append(" clientCoverage=").append(clientResidency(game.player.getX(), game.player.getZ()))
                .append('\n');
        Files.writeString(output().resolve("route-progress.txt"), evidence);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Route-end capture leaked OpenGL error");
    }

    private void capture(String mode) throws Exception {
        var view = views.get(viewIndex);
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(output().resolve(viewIndex + "-" + view.name() + "-" + mode + ".png"));
        }
        evidence.append(view).append(" mode=").append(mode).append(" viewport=")
                .append(game.getWindow().getWidth()).append('x').append(game.getWindow().getHeight())
                .append(" hostRenderedSections=").append(game.levelRenderer.countRenderedSections())
                .append(" sectionStatistics=").append(game.levelRenderer.getSectionStatistics())
                .append('\n');
        Files.writeString(output().resolve("progress.txt"), evidence);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "A1 capture leaked OpenGL error");
    }

    private void frame() {
        long now = System.nanoTime();
        if (measure && frameStarted != 0 && game.screen == null && game.getOverlay() == null) {
            frames.add((now - frameStarted) / 1e6);
        }
        frameStarted = now;
    }

    private void nativeMetrics() {
        evidence.append("routeTick=").append(routeTick)
                .append(" hostRenderedSections=").append(game.levelRenderer.countRenderedSections())
                .append(" sectionStatistics=").append(game.levelRenderer.getSectionStatistics())
                .append(" clientChunks=").append(game.level.getChunkSource().gatherStats()).append('\n');
    }

    private static long localNoon(View view) {
        // The host time packet reserves a negative sign for frozen time. Keep its transmitted day nonnegative.
        // Polar summer gives the ice fixture a sun above the horizon; other views retain equinox daylight.
        return (view.name().equals("polar-ice") ? 91L * 24000 : 0)
                + Math.floorMod(6000 - Math.round(view.position().longitudeRadians() / (2 * Math.PI) * 24000), 24000);
    }

    private java.nio.file.Path output() throws java.io.IOException {
        var path = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(path); return path;
    }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(value -> Math.max(0, value.getCollectionTime())).sum();
    }
    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()
                || !allocation.isThreadAllocatedMemoryEnabled()) { return -1; }
        return allocation.getTotalThreadAllocatedBytes();
    }
    private static String summary(List<Double> values) {
        List<Double> copy;
        synchronized (values) { copy = values.stream().sorted().toList(); }
        require(!copy.isEmpty(), "No performance samples captured");
        return String.format(Locale.ROOT, "n=%d,p50=%.3fms,p95=%.3fms,p99=%.3fms,max=%.3fms", copy.size(),
                copy.get(copy.size() / 2), copy.get((int) Math.ceil(copy.size() * .95) - 1),
                copy.get((int) Math.ceil(copy.size() * .99) - 1), copy.getLast());
    }
    private static double percentile95(List<Double> values) {
        synchronized (values) {
            var ordered = values.stream().sorted().toList();
            require(!ordered.isEmpty(), "No performance samples captured");
            return ordered.get((int) Math.ceil(ordered.size() * .95) - 1);
        }
    }
    private static GeographicPosition address(SpaceVector normal, double altitude) {
        var position = GeographicPosition.fromBody(normal, 1);
        return new GeographicPosition(position.latitudeRadians(), position.longitudeRadians(), altitude);
    }
    private static Field member(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException { return member(owner, name).get(owner); }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalStateException(message); } }

    private static final class Coverage {
        private int samples, viewMissing, routeMissing, radiusMismatch;
        private int minimumVisible = Integer.MAX_VALUE, minimumRoute = Integer.MAX_VALUE;
        void clear() { samples = 0; viewMissing = 0; routeMissing = 0; radiusMismatch = 0;
            minimumVisible = Integer.MAX_VALUE; minimumRoute = Integer.MAX_VALUE; }
        void add(Residency value) {
            samples++;
            if (value.visible < value.expectedVisible) { viewMissing++; }
            if (value.route < value.expectedRoute) { routeMissing++; }
            if (value.requestedRadius != 12 || value.effectiveRadius != 12) { radiusMismatch++; }
            minimumVisible = Math.min(minimumVisible, value.visible); minimumRoute = Math.min(minimumRoute, value.route);
        }
        @Override public String toString() {
            return "[samples=" + samples + ",missingView=" + viewMissing + ",missingRoute=" + routeMissing
                    + ",radiusMismatch=" + radiusMismatch + ",minimumVisible=" + minimumVisible
                    + ",minimumRoute=" + minimumRoute + "]";
        }
    }

    /** Exact measurement windows inside the bounded native recording; verification only. */
    @Name("astraengine.HorizonRoute")
    @Label("Astra forest measurement route")
    private static final class RouteWindow extends Event {
        @Label("Route pass")
        public int pass;
    }
}
