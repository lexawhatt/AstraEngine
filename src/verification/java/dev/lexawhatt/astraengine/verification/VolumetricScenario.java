package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.network.SolarReceivedEvent;
import dev.lexawhatt.astraengine.server.SolarState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Disposable fixed-weather cloud-volume views and real-depth shafts comparisons with presented-frame evidence. */
final class VolumetricScenario {
    private static final String[] CAPTURES = {
            "01-below-noon", "02-inside-volume", "03-above-volume", "04-sunset-shafts-on",
            "05-sunset-shafts-off", "06-sunset-shafts-on-repeat", "07-close-wall-shafts-off",
            "08-close-wall-shafts-on", "09-close-roof-shafts-off", "10-close-roof-shafts-on",
            "11-rain-noon", "12-night-shafts-off", "13-night-shafts-on", "14-clouds-disabled",
            "15-environment-disabled", "16-quality-low", "17-quality-high", "18-after-resource-reload",
            "19-resized-960x540", "20-restored-1280x720", "21-remnant-shafts-off",
            "22-remnant-shafts-on", "23-healthy-restored"
    };
    private final Minecraft minecraft = Minecraft.getInstance();
    private final List<Double> frameMillis = new ArrayList<>();
    private final StringBuilder metrics = new StringBuilder(
            "capture,width,height,quality,shafts,day_time,game_time,camera_y,rain,frames,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,roi_mean\n");
    private final StringBuilder comparisons = new StringBuilder("comparison,mean_absolute_delta,p99_delta\n");
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) {
            long now = System.nanoTime();
            this.renderedFrames++;
            if (this.previousFrameAt > 0 && this.renderedFrames > this.firstFrame + 12 && this.frameMillis.size() < 4096) {
                this.frameMillis.add((now - this.previousFrameAt) / 1_000_000.0);
            }
            this.previousFrameAt = now;
        }
    };
    private final Consumer<SolarReceivedEvent> solarListener = event -> this.received = event.payload().snapshot();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> observedCompletion = pending;
    private StellarEvolutionSnapshot expectedSolar;
    private StellarEvolutionSnapshot received;
    private double[] sunsetOn;
    private double[] sunsetOff;
    private double[] wallOff;
    private double[] roofOff;
    private double[] nightOff;
    private double[] remnantOff;
    private long expectedTime = 6000;
    private long fixedGameTime = -1;
    private long renderedFrames;
    private long firstFrame;
    private long previousFrameAt;
    private int stage;
    private int ticks;
    private int expectedWidth = 1280;
    private int expectedHeight = 720;
    private double cameraY = 200;
    private float pitch = -35;
    private float yaw;
    private float expectedRain;
    private String quality = "balanced";
    private boolean shafts = true;
    private boolean configured;
    private boolean enclosureView;

    VolumetricScenario() {
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
            if (observedCompletion != pending) {
                observedCompletion = pending;
                resetSamples();
            }
            require(++ticks < 1200, "Volumetric fixture stage timed out");
            face();
            if (!configured) {
                configure();
                configured = true;
                resetSamples();
                return false;
            }
            if (!ready()) { return false; }
            double[] pixels = shot(CAPTURES[stage]);
            checkComparison(pixels);
            if (++stage == CAPTURES.length) {
                writeEvidence();
                dispose();
                return true;
            }
            configured = false;
            return false;
        } catch (Exception failure) {
            AstraEngine.LOGGER.error("ASTRA_VOLUMETRIC_FAILURE stage={} ticks={} frame={} expectedTime={} actualTime={}",
                    stage, ticks, renderedFrames, expectedTime, minecraft.level.getDayTime(), failure);
            writeEvidence();
            dispose();
            throw failure;
        }
    }

    private void configure() throws Exception {
        switch (stage) {
            case 0 -> {
                command("environment auto");
                command("exposure 1");
                command("bloom true");
                command("flashlight false");
                command("lighting true");
                command("cloud-cover 0.65");
                command("quality balanced");
                setShafts(true);
                server(this::prepareWorld);
                writeHardware();
            }
            case 1 -> { cameraY = 550; pitch = -3; server(this::setView); }
            case 2 -> { cameraY = 1000; pitch = 28; server(this::setView); }
            case 3 -> { cameraY = 200; expectedTime = 11500; pitch = -8; server(this::setView); }
            case 4 -> setShafts(false);
            case 5 -> setShafts(true);
            case 6 -> {
                setShafts(false);
                enclosureView = true;
                pitch = -12;
                server(server -> shell(server, true));
            }
            case 7 -> setShafts(true);
            case 8 -> {
                setShafts(false);
                pitch = -65;
                server(server -> { shell(server, false); roof(server, true); });
            }
            case 9 -> setShafts(true);
            case 10 -> {
                enclosureView = false;
                expectedTime = 6000;
                pitch = -25;
                expectedRain = 1;
                server(server -> { roof(server, false); setWeather(server); setView(server); });
            }
            case 11 -> {
                setShafts(false);
                expectedTime = 18000;
                expectedRain = 0;
                server(server -> { setWeather(server); setView(server); });
            }
            case 12 -> setShafts(true);
            case 13 -> {
                expectedTime = 6000;
                minecraft.options.cloudStatus().set(CloudStatus.OFF);
                server(this::setView);
            }
            case 14 -> {
                minecraft.options.cloudStatus().set(CloudStatus.FANCY);
                command("environment off");
            }
            case 15 -> { command("environment auto"); setQuality("low"); }
            case 16 -> setQuality("high");
            case 17 -> pending = minecraft.reloadResourcePacks();
            case 18 -> resize(960, 540);
            case 19 -> resize(1280, 720);
            case 20 -> { setShafts(false); server(this::remnant); }
            case 21 -> setShafts(true);
            case 22 -> server(server -> { SolarState.get(server).reset(); publishSolar(server); });
            default -> throw new IllegalStateException("Unknown volumetric stage " + stage);
        }
    }

    private boolean ready() {
        return ticks >= 25 && renderedFrames >= firstFrame + 33 && frameMillis.size() >= 20
                && minecraft.level.getDayTime() == expectedTime && minecraft.level.tickRateManager().isFrozen()
                && Math.abs(minecraft.player.getY() - cameraY) < 0.15
                && Math.abs(minecraft.level.getRainLevel(1) - expectedRain) < 0.01
                && minecraft.getWindow().getWidth() == expectedWidth && minecraft.getWindow().getHeight() == expectedHeight
                && expectedSolar != null && expectedSolar.equals(received);
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        AstraSky.configure(server, PlanetarySkyProfile.EARTH.withLightPollution(0));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 199, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(new BlockPos(0, 199, 0), Blocks.DIAMOND_BLOCK.defaultBlockState());
        setWeather(server);
        setView(server);
        SolarState.get(server).reset();
        server.tickRateManager().setFrozen(true);
        publishSolar(server);
    }

    private void setView(MinecraftServer server) {
        server.overworld().setDayTime(expectedTime);
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(0.5, cameraY, 0.5);
    }

    private void setWeather(MinecraftServer server) {
        var level = server.overworld();
        level.setWeatherParameters(expectedRain == 0 ? 100000 : 0, expectedRain == 0 ? 0 : 100000,
                expectedRain > 0, false);
        level.setRainLevel(expectedRain);
        level.setThunderLevel(0);
        // Frozen worlds do not run the ordinary weather broadcast; send the matching vanilla state explicitly.
        var connection = server.getPlayerList().getPlayers().getFirst().connection;
        connection.send(new ClientboundGameEventPacket(expectedRain > 0
                ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0));
        connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, expectedRain));
        connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0));
    }

    private void shell(MinecraftServer server, boolean enabled) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 200; y <= 208; y++) {
                    if (Math.abs(x) == 3 || Math.abs(z) == 3 || y == 208) {
                        server.overworld().setBlockAndUpdate(new BlockPos(x, y, z),
                                enabled ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private void roof(MinecraftServer server, boolean enabled) {
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                server.overworld().setBlockAndUpdate(new BlockPos(x, 205, z),
                        enabled ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void remnant(MinecraftServer server) {
        SolarState state = SolarState.get(server);
        state.startDemo(10);
        for (int tick = 0; tick < 600; tick++) { state.tick(true); }
        require(state.snapshot().phase() == StellarEvolutionSnapshot.Phase.REMNANT,
                "Actual server evolution did not reach its remnant sample");
        publishSolar(server);
    }

    private void publishSolar(MinecraftServer server) {
        expectedSolar = SolarState.get(server).snapshot();
        PacketDistributor.sendToPlayer(server.getPlayerList().getPlayers().getFirst(), new SolarPayload(expectedSolar));
    }

    private void face() {
        var sun = SkyEphemeris.sample(PlanetarySkyProfile.EARTH, expectedTime, 0).sunDirection();
        yaw = enclosureView ? 0 : (float) Math.toDegrees(Math.atan2(-sun.x(), sun.z()));
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
    }

    private double[] shot(String name) throws Exception {
        if (fixedGameTime < 0) { fixedGameTime = minecraft.level.getGameTime(); }
        require(minecraft.level.getGameTime() == fixedGameTime, "Frozen cloud wind changed during volume comparisons");
        if (enclosureView) {
            require(minecraft.player.pick(10, 1, false).getType() == net.minecraft.world.phys.HitResult.Type.BLOCK,
                    "Near-depth capture has no real opaque wall/roof on its camera ray");
        }
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        double[] roi;
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            image.writeToFile(path);
            int x0 = image.getWidth() * 3 / 10;
            int x1 = image.getWidth() * 7 / 10;
            int y0 = image.getHeight() * 3 / 10;
            int y1 = image.getHeight() * 7 / 10;
            roi = new double[(x1 - x0) * (y1 - y0)];
            int index = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int color = image.getPixelRGBA(x, y);
                    roi[index++] = (0.2126 * (color & 255) + 0.7152 * ((color >>> 8) & 255)
                            + 0.0722 * ((color >>> 16) & 255)) / 255;
                }
            }
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after volumetric capture " + name);
        double[] timings = frameMillis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        metrics.append(String.format(Locale.ROOT, "%s,%d,%d,%s,%s,%d,%d,%.3f,%.2f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.6f%n",
                name, expectedWidth, expectedHeight, quality, shafts, minecraft.level.getDayTime(), fixedGameTime,
                minecraft.gameRenderer.getMainCamera().getPosition().y, expectedRain, timings.length,
                mean(timings), percentile(timings, 0.5), percentile(timings, 0.95), percentile(timings, 0.99),
                timings[timings.length - 1], mean(roi)));
        AstraEngine.LOGGER.info("ASTRA_VOLUMETRIC_CAPTURE {} pose=({}, {}, {}) frames={} meanMs={} p95Ms={} roiMean={}",
                name, yaw, pitch, cameraY, timings.length, mean(timings), percentile(timings, 0.95), mean(roi));
        return roi;
    }

    private void checkComparison(double[] pixels) {
        switch (stage) {
            case 3 -> sunsetOn = pixels;
            case 4 -> { sunsetOff = pixels; compare("sunset-shafts", sunsetOn, pixels, false, true); }
            case 5 -> {
                compare("sunset-repeat", sunsetOn, pixels, true, false);
                compare("sunset-reenabled", sunsetOff, pixels, false, true);
            }
            case 6 -> wallOff = pixels;
            case 7 -> compare("opaque-close-wall", wallOff, pixels, true, false);
            case 8 -> roofOff = pixels;
            case 9 -> compare("opaque-close-roof", roofOff, pixels, true, false);
            case 11 -> nightOff = pixels;
            case 12 -> compare("night-no-solar-shafts", nightOff, pixels, true, false);
            case 20 -> remnantOff = pixels;
            case 21 -> compare("remnant-low-solar-shafts", remnantOff, pixels, true, false);
            default -> { }
        }
    }

    private void compare(String label, double[] before, double[] after, boolean unchanged, boolean effect) {
        require(before.length == after.length, "Volumetric comparison framebuffer changed shape: " + label);
        double[] difference = new double[before.length];
        for (int index = 0; index < before.length; index++) { difference[index] = Math.abs(before[index] - after[index]); }
        Arrays.sort(difference);
        double average = mean(difference);
        double p99 = percentile(difference, 0.99);
        comparisons.append(String.format(Locale.ROOT, "%s,%.8f,%.8f%n", label, average, p99));
        if (unchanged) {
            // These are display-image bounds; no linear radiometric identity is claimed after tonemapping.
            require(average < 0.008 && p99 < 0.03, "Shafts changed protected or repeated pixels: " + label
                    + " mean=" + average + ", p99=" + p99);
        }
        if (effect) {
            require(average > 0.00015, "Enabled shafts produced no measurable response in the open sunset view: " + label);
        }
    }

    private void writeHardware() throws Exception {
        String text = "Volumetric native presentation interval sample; not an isolated GPU benchmark or universal FPS claim.\n"
                + "GL vendor: " + GL11.glGetString(GL11.GL_VENDOR) + "\nGL renderer: " + GL11.glGetString(GL11.GL_RENDERER)
                + "\nGL version: " + GL11.glGetString(GL11.GL_VERSION) + "\nJava: " + System.getProperty("java.runtime.version")
                + "\nMax heap bytes: " + Runtime.getRuntime().maxMemory()
                + "\nGraphics: " + minecraft.options.graphicsMode().get()
                + "\nRender distance: " + minecraft.options.renderDistance().get()
                + "\nFOV: 70; initial window: 1280x720; FPS cap: 60; VSync: false; exposure: 1; bloom: true.\n"
                + "Seed: 20260927; cloud cover override: 0.65; game time frozen; weather applied through vanilla packets.\n"
                + "Frame samples exclude the first twelve presented frames after every change and resource reload.\n"
                + "Intervals include the host renderer, CPU work and presentation; headless gamescope is not normal desktop latency.\n";
        Files.writeString(minecraft.gameDirectory.toPath().resolve("volumetric-hardware.txt"), text);
    }

    private void writeEvidence() throws Exception {
        Files.writeString(minecraft.gameDirectory.toPath().resolve("volumetric-frame-metrics.csv"), metrics.toString());
        Files.writeString(minecraft.gameDirectory.toPath().resolve("volumetric-comparisons.csv"), comparisons.toString());
    }

    private void resize(int width, int height) {
        expectedWidth = width;
        expectedHeight = height;
        GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), width, height);
    }

    private void setShafts(boolean enabled) { shafts = enabled; command("shafts " + enabled); }
    private void setQuality(String value) { quality = value; command("quality " + value); }
    private void command(String command) { minecraft.player.connection.sendCommand("astra-render " + command); }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private void resetSamples() {
        ticks = 0;
        firstFrame = renderedFrames;
        previousFrameAt = 0;
        frameMillis.clear();
    }

    private void dispose() {
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(solarListener);
    }

    private static double mean(double[] values) { return Arrays.stream(values).average().orElseThrow(); }
    private static double percentile(double[] sorted, double fraction) { return sorted[(int) ((sorted.length - 1) * fraction)]; }

    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (volumetric stage " + stage + ", ticks " + ticks + ")"); }
    }
}
