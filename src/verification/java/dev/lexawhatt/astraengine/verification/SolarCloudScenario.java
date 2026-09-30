package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import dev.lexawhatt.astraengine.server.SolarState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Fixed off-Sun camera compares actual cloud-on/off frames across paused samples of the server evolution model. */
final class SolarCloudScenario {
    private static final String[] NAMES = {"healthy", "depleted", "collapse", "flash", "tail", "remnant"};
    private static final int[] ACTIVE_TICKS = {0, 120, 260, 284, 500, 600};
    private static final long DAY_TIME = 2500;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };
    private final Consumer<SolarReceivedEvent> solarListener = event -> this.received = event.payload().snapshot();
    private final StringBuilder metrics = new StringBuilder(
            "sample,phase,phase_ticks,active_ticks,luminosity,flash,cloud_mean,cloud_p95,clear_mean,positive_delta_mean,positive_delta_p95,absolute_delta_mean\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private StellarEvolutionSnapshot expected;
    private StellarEvolutionSnapshot received;
    private double[] cloudy;
    private double healthyMean;
    private double healthyP95;
    private double healthyCloudDelta;
    private double collapseP95;
    private int step;
    private int sample;
    private int ticks;
    private long renderedFrames;
    private long firstFrame;
    private long fixedGameTime = -1;
    private float yaw;

    SolarCloudScenario() {
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        minecraft.options.cloudStatus().set(CloudStatus.FANCY);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, solarListener);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 600, "Cloud evolution step timed out");
            minecraft.player.setYRot(yaw);
            minecraft.player.setXRot(-27);
            switch (step) {
                case 0 -> {
                    minecraft.player.connection.sendCommand("astra-render environment auto");
                    minecraft.player.connection.sendCommand("astra-render exposure 1");
                    minecraft.player.connection.sendCommand("astra-render bloom true");
                    minecraft.player.connection.sendCommand("astra-render quality balanced");
                    var sun = SkyEphemeris.sample(PlanetarySkyProfile.EARTH, DAY_TIME, 0).sunDirection();
                    yaw = (float) Math.toDegrees(Math.atan2(-sun.x(), sun.z())) + 68;
                    server(this::prepareWorld);
                    next();
                }
                case 1 -> {
                    if (!ready(40)) { return false; }
                    cloudy = shot(NAMES[sample] + "-clouds");
                    minecraft.options.cloudStatus().set(CloudStatus.OFF);
                    next();
                }
                case 2 -> {
                    if (!ready(20)) { return false; }
                    double[] clear = shot(NAMES[sample] + "-clear");
                    compare(clear);
                    if (++sample == NAMES.length) {
                        writeMetrics();
                        NeoForge.EVENT_BUS.unregister(frameListener);
                        NeoForge.EVENT_BUS.unregister(solarListener);
                        return true;
                    }
                    minecraft.options.cloudStatus().set(CloudStatus.FANCY);
                    server(this::advanceSample);
                    step = 1;
                    ticks = 0;
                    firstFrame = renderedFrames;
                }
                default -> throw new IllegalStateException("Unexpected cloud evolution step " + step);
            }
            return false;
        } catch (Exception failure) {
            writeMetrics();
            NeoForge.EVENT_BUS.unregister(frameListener);
            NeoForge.EVENT_BUS.unregister(solarListener);
            throw failure;
        }
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(DAY_TIME);
        level.setWeatherParameters(100000, 0, false, false);
        AstraSky.configure(server, PlanetarySkyProfile.EARTH.withLightPollution(0));
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, 200, 0.5);
        SolarState.get(server).reset();
        server.tickRateManager().setFrozen(true);
        publish(server);
    }

    private void advanceSample(MinecraftServer server) {
        SolarState state = SolarState.get(server);
        if (sample == 1) { state.startDemo(10); }
        else { state.resume(); }
        int limit = 601;
        // Deterministic accelerated fixture samples use the real server model, then its normal pause/snapshot path.
        while (state.snapshot().activeTicks() < ACTIVE_TICKS[sample] && limit-- > 0) {
            require(state.tick(true), "Evolution did not reach the requested diagnostic sample");
        }
        require(state.snapshot().activeTicks() == ACTIVE_TICKS[sample], "Diagnostic sample overshot its occupied tick");
        state.pause();
        publish(server);
    }

    private void publish(MinecraftServer server) {
        expected = SolarState.get(server).snapshot();
        PacketDistributor.sendToPlayer(server.getPlayerList().getPlayers().getFirst(), new SolarPayload(expected));
    }

    private boolean ready(int settle) {
        return ticks >= settle && renderedFrames >= firstFrame + 8 && expected != null && expected.equals(received)
                && !expected.running() && minecraft.level.getDayTime() == DAY_TIME
                && minecraft.level.tickRateManager().isFrozen();
    }

    private double[] shot(String name) throws Exception {
        if (fixedGameTime < 0) { fixedGameTime = minecraft.level.getGameTime(); }
        require(minecraft.level.getGameTime() == fixedGameTime, "Cloud wind moved between frozen diagnostic samples");
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        double[] luminance;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            int x0 = image.getWidth() / 10;
            int x1 = image.getWidth() * 9 / 10;
            int y0 = image.getHeight() / 20;
            int y1 = image.getHeight() * 9 / 20;
            luminance = new double[(x1 - x0) * (y1 - y0)];
            int index = 0;
            // Upper off-Sun sky ROI excludes the stellar disc, terrain and bloom centered on the star.
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int color = image.getPixelRGBA(x, y);
                    luminance[index++] = (0.2126 * (color & 255) + 0.7152 * ((color >>> 8) & 255)
                            + 0.0722 * ((color >>> 16) & 255)) / 255;
                }
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after cloud comparison capture " + name);
        AstraEngine.LOGGER.info("ASTRA_SOLAR_CLOUD_CAPTURE {} snapshot={} frame={} frozenGameTime={} exposure=1 bloom=true quality=balanced",
                name, expected, renderedFrames, fixedGameTime);
        return luminance;
    }

    private void compare(double[] clear) {
        require(clear.length == cloudy.length, "Cloud comparison framebuffer changed size");
        double[] positive = new double[clear.length];
        double absolute = 0;
        for (int index = 0; index < clear.length; index++) {
            positive[index] = Math.max(0, cloudy[index] - clear[index]);
            absolute += Math.abs(cloudy[index] - clear[index]);
        }
        double mean = mean(cloudy);
        double p95 = p95(cloudy);
        double deltaMean = mean(positive);
        double deltaP95 = p95(positive);
        SolarVisual visual = SolarVisual.from(1 - (float) expected.remaining() / StellarEvolutionSnapshot.CAPACITY,
                expected.phase(), expected.phaseTicks());
        metrics.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                NAMES[sample], expected.phase(), expected.phaseTicks(), expected.activeTicks(), visual.luminosity(),
                visual.flash(), mean, p95, mean(clear), deltaMean, deltaP95, absolute / clear.length));
        if (sample == 0) {
            healthyMean = mean;
            healthyP95 = p95;
            healthyCloudDelta = deltaP95;
            require(absolute / clear.length > 0.005, "Fixed cloud probe contains no meaningful cloud coverage");
        }
        if (sample == 2) { collapseP95 = p95; }
        if (sample == 3) {
            require(p95 > collapseP95 * 1.1, "Clouded sky did not respond to the actual supernova flash");
        }
        if (sample >= 4) {
            require(mean < healthyMean * 0.7 && p95 < healthyP95 * 0.8,
                    "Tail/remnant sky and clouds retain healthy-star brightness: mean=" + mean + ", p95=" + p95);
            require(deltaP95 < Math.max(0.035, healthyCloudDelta * 0.65),
                    "Tail/remnant clouds retain an additive healthy-day light contribution: " + deltaP95);
        }
        AstraEngine.LOGGER.info("ASTRA_SOLAR_CLOUD_METRICS {} mean={} p95={} additiveCloudP95={}",
                NAMES[sample], mean, p95, deltaP95);
    }

    private static double mean(double[] values) { return Arrays.stream(values).average().orElseThrow(); }

    private static double p95(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) (sorted.length * 0.95)];
    }

    private void writeMetrics() throws Exception {
        Files.writeString(minecraft.gameDirectory.toPath().resolve("solar-cloud-metrics.csv"), metrics.toString());
    }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private void next() { step++; ticks = 0; firstFrame = renderedFrames; }

    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (cloud sample " + sample + ", step " + step + ")"); }
    }
}
