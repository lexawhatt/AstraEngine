package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.SkyState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import dev.lexawhatt.astraengine.sky.SkySample;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Disposable native seasonal sky, weather, light pollution, render fallback and exact restart fixture. */
final class SeasonalScenario {
    private static final String[] SEASONS = {"spring", "summer", "autumn", "winter"};
    private static final String[] TIMES = {"dawn", "noon", "dusk", "afterglow", "night"};
    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean restart;
    private final Consumer<RenderFrameEvent.Post> frameListener = event -> {
        if (minecraft.level != null && minecraft.getOverlay() == null) { this.renderedFrames++; }
    };
    private final StringBuilder observations = new StringBuilder(
            "capture,day_time,season_phase,sun_altitude_degrees,daylight_hours,distance_au,pollution,sun_scale,frame\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> observedCompletion = pending;
    private PlanetarySkyProfile expectedProfile = PlanetarySkyProfile.EARTH;
    private long expectedTime;
    private long renderedFrames;
    private long firstFrame;
    private long minimumPresentedFrame;
    private long expectedRevision;
    private int step;
    private int ticks;
    private int slot;
    private boolean night;
    private int groundY;
    private int localPollutionStage;
    private SkySample frozenSample;
    private Vector3f winterLight;
    private Vector3f summerLight;
    private Properties checkpoint;

    SeasonalScenario(boolean restart) {
        this.restart = restart;
        minecraft.options.hideGui = true;
        minecraft.options.fov().set(70);
        GLFW.glfwFocusWindow(minecraft.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        AstraEngine.LOGGER.info("ASTRA_SEASONAL_BEGIN restart={} graphics={}", restart,
                minecraft.options.graphicsMode().get());
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 900, "Seasonal fixture step timed out");
            if (pending != observedCompletion) {
                observedCompletion = pending;
                minimumPresentedFrame = renderedFrames + 3;
            }
            faceSky();
            if (renderedFrames < minimumPresentedFrame) { return false; }
            boolean complete = restart ? restartTick() : createTick();
            if (complete) {
                NeoForge.EVENT_BUS.unregister(frameListener);
                writeObservations();
            }
            return complete;
        } catch (Exception failure) {
            NeoForge.EVENT_BUS.unregister(frameListener);
            AstraEngine.LOGGER.error("ASTRA_SEASONAL_FAILURE step={} ticks={} dayTime={} expected={} frames={}",
                    step, ticks, minecraft.level.getDayTime(), expectedTime, renderedFrames, failure);
            writeObservations();
            throw failure;
        }
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                command("astra-render environment auto");
                server(this::prepareWorld);
                next();
            }
            case 1 -> {
                if (ticks < 40) { return false; }
                configureSlot(0);
                next();
            }
            case 2 -> {
                if (!ready(26)) { return false; }
                verifyBlockLight();
                shot(String.format(Locale.ROOT, "%02d-%s-%s", slot + 1, SEASONS[slot / 5], TIMES[slot % 5]));
                if (++slot < 20) {
                    configureSlot(slot);
                    ticks = 0;
                    firstFrame = renderedFrames;
                } else {
                    expectedTime = 91L * 24000 + 18000;
                    night = true;
                    configure(PlanetarySkyProfile.EARTH.withLightPollution(0), expectedTime);
                    next();
                }
            }
            case 3 -> {
                if (!ready(26)) { return false; }
                frozenSample = sky().sample(minecraft.level, 1);
                require(frozenSample.equals(sky().sample(minecraft.level, 0)),
                        "Frozen host time still advances through render partial ticks");
                shot("17-night-zero-light-pollution");
                next();
            }
            case 4 -> {
                if (!ready(60)) { return false; }
                require(sky().sample(minecraft.level, 1).equals(frozenSample),
                        "Frozen vanilla time still advances orbital/seasonal presentation");
                if (localPollutionStage == 0) {
                    require(sky().pollution() < 0.08, "Dark rural fixture unexpectedly contains strong local pollution");
                    server(server -> localLights(server, true));
                    localPollutionStage = 1;
                    ticks = 0;
                    firstFrame = renderedFrames;
                    return false;
                }
                if (localPollutionStage == 1) {
                    require(sky().pollution() > 0.12, "Nearby emitted block light did not contribute to sky pollution");
                    shot("17b-night-nearby-emitted-light-pollution");
                    server(server -> localLights(server, false));
                    localPollutionStage = 2;
                    ticks = 0;
                    firstFrame = renderedFrames;
                    return false;
                }
                require(sky().pollution() < 0.08, "Removing local emitters did not clear their visual pollution");
                command("astra season pollution 1");
                expectedProfile = expectedProfile.withLightPollution(1);
                next();
            }
            case 5 -> {
                if (!ready(26)) { return false; }
                shot("18-night-high-light-pollution");
                command("astra season pollution 0");
                expectedProfile = expectedProfile.withLightPollution(0);
                server(server -> server.overworld().setWeatherParameters(0, 10000, true, false));
                next();
            }
            case 6 -> {
                if (!ready(140)) { return false; }
                require(minecraft.level.getRainLevel(1) > 0.9, "Native rain did not reach its steady weather state");
                shot("19-night-rain-cloud-occlusion");
                server(server -> server.overworld().setWeatherParameters(10000, 0, false, false));
                configure(PlanetarySkyProfile.EARTH.withLightPollution(0), horizonTime(273, false));
                night = false;
                next();
            }
            case 7 -> {
                if (!ready(140)) { return false; }
                require(minecraft.level.getRainLevel(1) < 0.1, "Weather did not clear before the sunset comparison");
                shot("20-sunset-before-resource-reload");
                frozenSample = sky().sample(minecraft.level, 1);
                pending = minecraft.reloadResourcePacks();
                next();
            }
            case 8 -> {
                if (!ready(26)) { return false; }
                require(sky().sample(minecraft.level, 1).equals(frozenSample), "Reload lost the received sky ephemeris");
                shot("21-sunset-after-resource-reload");
                command("astra-render environment off");
                next();
            }
            case 9 -> {
                if (!ready(26)) { return false; }
                Vector3f input = new Vector3f(0.4f, 0.3f, 0.2f);
                Vector3f output = adjustLight(0, 15, 1, input);
                require(input.distance(output) < 0.000001f, "Explicit environment off retains custom lightmap correction");
                shot("22-vanilla-sky-fallback");
                command("astra-render environment auto");
                minecraft.options.cloudStatus().set(CloudStatus.OFF);
                expectedProfile = expectedProfile.withSunSizeMultiplier(1);
                command("astra season sun-size 1");
                expectedTime = 273L * 24000 + 6000;
                server(server -> server.overworld().setDayTime(expectedTime));
                next();
            }
            case 10 -> {
                if (!ready(26)) { return false; }
                shot("23-physical-sun-disc-scale-one");
                expectedProfile = expectedProfile.withSunSizeMultiplier(3);
                command("astra season sun-size 3");
                next();
            }
            case 11 -> {
                if (!ready(26)) { return false; }
                shot("24-default-sun-disc-scale-three");
                minecraft.options.cloudStatus().set(CloudStatus.FANCY);
                configure(expectedProfile, 273L * 24000 + 11000);
                next();
            }
            case 12 -> {
                if (!ready(26)) { return false; }
                verifyBlockLight();
                winterLight = adjustedSky();
                shot("25-winter-short-day-late-afternoon");
                configure(expectedProfile, 91L * 24000 + 11000);
                next();
            }
            case 13 -> {
                if (!ready(26)) { return false; }
                verifyBlockLight();
                summerLight = adjustedSky();
                require(summerLight.lengthSquared() > winterLight.lengthSquared() + 0.03f,
                        "Seasonal winter sky remained as bright as the summer sky at the same host clock: winter="
                                + winterLight + ", summer=" + summerLight);
                shot("26-summer-long-day-same-clock");
                server(server -> server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server));
                next();
            }
            case 14 -> {
                if (ticks < 35) { return false; }
                require(minecraft.level.getDayTime() > expectedTime, "Enabling host daylight did not resume the sky clock");
                SkySample start = sky().sample(minecraft.level, 0);
                SkySample end = sky().sample(minecraft.level, 1);
                SkySample expectedEnd = SkyEphemeris.sample(expectedProfile, minecraft.level.getDayTime() + 1, 0);
                require(!start.equals(end) && end.sunDirection().subtract(expectedEnd.sunDirection()).length() < 1.0e-10,
                        "Running default host day-time rate does not interpolate forward by one tick");
                server(server -> {
                    server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    expectedTime = server.overworld().getDayTime();
                });
                next();
            }
            case 15 -> {
                if (!ready(26)) { return false; }
                expectedProfile = expectedProfile.withLatitudeDegrees(52).withLightPollution(0.18)
                        .withSunSizeMultiplier(3.5).withSeasonOffsetDays(17.25);
                configure(expectedProfile, 273L * 24000 + 9800);
                next();
            }
            case 16 -> {
                if (!ready(26)) { return false; }
                shot("27-custom-settings-before-full-restart");
                writeCheckpoint();
                server(server -> {
                    expectedRevision = SkyState.get(server).revision();
                    require(SkyState.get(server).profile().equals(expectedProfile), "Server did not retain custom settings");
                    server.saveEverything(false, true, true);
                });
                next();
            }
            case 17 -> {
                checkpoint.setProperty("revision", Long.toString(expectedRevision));
                saveCheckpoint();
                return true;
            }
            default -> throw new IllegalStateException("Unexpected seasonal fixture step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint = new Properties();
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                expectedTime = Long.parseLong(checkpoint.getProperty("time"));
                expectedRevision = Long.parseLong(checkpoint.getProperty("revision"));
                groundY = Integer.parseInt(checkpoint.getProperty("ground_y"));
                expectedProfile = PlanetarySkyProfile.EARTH.withLatitudeDegrees(52).withLightPollution(0.18)
                        .withSunSizeMultiplier(3.5).withSeasonOffsetDays(17.25);
                server(server -> {
                    require(SkyState.get(server).profile().equals(expectedProfile), "Restart replaced the persisted sky profile");
                    require(SkyState.get(server).revision() == expectedRevision, "Restart changed the sky revision");
                    require(server.overworld().getDayTime() == expectedTime, "Offline time advanced the calendar");
                    require(server.overworld().getBlockState(new BlockPos(0, groundY, 0)).is(Blocks.DIAMOND_BLOCK),
                            "Sky settings or restart changed real terrain");
                });
                next();
            }
            case 1 -> {
                if (!ready(35)) { return false; }
                require(sky().revision() == expectedRevision, "Login did not synchronize the persisted sky revision");
                verifyBlockLight();
                shot("28-full-restart-retained-settings");
                frozenSample = sky().sample(minecraft.level, 1);
                next();
            }
            case 2 -> {
                if (!ready(60)) { return false; }
                require(sky().sample(minecraft.level, 1).equals(frozenSample), "Restart resumed a frozen celestial clock");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected seasonal restart step " + step);
        }
        return false;
    }

    private void prepareWorld(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(100000, 0, false, false);
        groundY = Math.min(220, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0) + 22);
        // A small real overlook gives every seasonal capture the same terrain and local lights.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlockAndUpdate(new BlockPos(x, groundY, z), Blocks.STONE_BRICKS.defaultBlockState());
                for (int y = 1; y <= 5; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, groundY + y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(new BlockPos(0, groundY, 0), Blocks.DIAMOND_BLOCK.defaultBlockState());
        server.getPlayerList().getPlayers().getFirst().teleportTo(0.5, groundY + 1, 0.5);
    }

    private void localLights(MinecraftServer server, boolean enabled) {
        for (int x : new int[]{-4, 0, 4}) {
            for (int z : new int[]{-4, 0, 4}) {
                if (x == 0 && z == 0) { continue; }
                server.overworld().setBlockAndUpdate(new BlockPos(x, groundY + 2, z),
                        enabled ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void configureSlot(int index) {
        int day = switch (index / 5) { case 0 -> 0; case 1 -> 91; case 2 -> 182; default -> 273; };
        long time = switch (index % 5) {
            case 0 -> horizonTime(day, true);
            case 1 -> day * 24000L + 6000;
            case 2 -> horizonTime(day, false);
            case 3 -> horizonTime(day, false, -1.1);
            default -> day * 24000L + 18000;
        };
        night = index % 5 == 4;
        configure(PlanetarySkyProfile.EARTH.withLightPollution(0), time);
    }

    private long horizonTime(int day, boolean dawn) {
        return horizonTime(day, dawn, 1.5);
    }

    private long horizonTime(int day, boolean dawn, double altitudeDegrees) {
        long closest = day * 24000L;
        double difference = Double.POSITIVE_INFINITY;
        for (int tick = dawn ? -5000 : 6000; tick <= (dawn ? 6000 : 18000); tick += 10) {
            long candidate = day * 24000L + tick;
            if (candidate < 0) { continue; }
            double delta = Math.abs(SkyEphemeris.sample(PlanetarySkyProfile.EARTH, candidate, 0)
                    .solarAltitudeRadians() - Math.toRadians(altitudeDegrees));
            if (delta < difference) { closest = candidate; difference = delta; }
        }
        return closest;
    }

    private void configure(PlanetarySkyProfile profile, long time) {
        expectedProfile = profile;
        expectedTime = time;
        server(server -> {
            AstraSky.configure(server, profile);
            server.overworld().setDayTime(time);
        });
    }

    private SkyStateClient sky() {
        require(minecraft.level.effects() instanceof AstralOverworldEffects, "Overworld sky integration is not installed");
        return ((AstralOverworldEffects) minecraft.level.effects()).skyState();
    }

    private boolean ready(int settleTicks) {
        return ticks >= settleTicks && renderedFrames >= firstFrame + 8
                && minecraft.level.getDayTime() == expectedTime && sky().profile().equals(expectedProfile);
    }

    private void faceSky() {
        if (!(minecraft.level.effects() instanceof AstralOverworldEffects)) { return; }
        SpaceVector direction = sky().sample(minecraft.level, 0).sunDirection();
        double altitude = Math.toDegrees(Math.asin(Math.clamp(direction.y(), -1, 1)));
        minecraft.player.setYRot(night ? 25 : (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z())));
        minecraft.player.setXRot(night ? -42 : (float) -Math.max(8, altitude - 12));
    }

    private void verifyBlockLight() {
        adjustLight(0, 0, 0, new Vector3f(0.04f));
        for (int block = 0; block <= 15; block++) {
            Vector3f input = new Vector3f(0.08f + block * 0.04f, 0.06f + block * 0.025f, 0.05f + block * 0.015f);
            Vector3f result = adjustLight(block, 0, 0, input);
            require(result.distance(input) < 0.000001f, "Seasonal sky altered local emitted block light at level " + block);
        }
        Vector3f emission = new Vector3f(0.25f, 0.16f, 0.08f);
        Vector3f skyOnly = adjustLight(0, 15, 1, new Vector3f(0.96f));
        Vector3f lit = adjustLight(15, 15, 1, new Vector3f(0.96f).add(emission));
        require(new Vector3f(lit).sub(skyOnly).distance(emission) < 0.000001f,
                "Seasonal lighting attenuated the block-light summand in an open-sky cell");
    }

    private Vector3f adjustedSky() {
        adjustLight(0, 0, 0, new Vector3f(0.04f));
        return adjustLight(0, 15, 1, new Vector3f(0.96f));
    }

    private Vector3f adjustLight(int x, int y, float skyLight, Vector3f input) {
        Vector3f result = new Vector3f(input);
        minecraft.level.effects().adjustLightmapColors(minecraft.level, 1, 1, 1, skyLight, x, y, result);
        return result;
    }

    private void shot(String name) throws Exception {
        Path path = minecraft.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(path.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after seasonal capture " + name);
        SkySample sample = sky().sample(minecraft.level, 0);
        observations.append(String.format(Locale.ROOT, "%s,%d,%.9f,%.6f,%.6f,%.9f,%.4f,%.4f,%d%n",
                name, minecraft.level.getDayTime(), sample.seasonPhase(), Math.toDegrees(sample.solarAltitudeRadians()),
                sample.daylightHours(), sample.orbitalDistanceAu(), expectedProfile.lightPollution(),
                expectedProfile.sunSizeMultiplier(), renderedFrames));
        AstraEngine.LOGGER.info("ASTRA_SEASONAL_SCREENSHOT {} time={} sample={} profile={} frame={}",
                name, minecraft.level.getDayTime(), sample, expectedProfile, renderedFrames);
    }

    private void writeCheckpoint() throws Exception {
        checkpoint = new Properties();
        checkpoint.setProperty("time", Long.toString(expectedTime));
        checkpoint.setProperty("ground_y", Integer.toString(groundY));
        checkpoint.setProperty("profile", expectedProfile.toString());
    }

    private void saveCheckpoint() throws Exception {
        StringWriter text = new StringWriter();
        checkpoint.store(text, "Disposable seasonal sky native restart checkpoint");
        Files.writeString(checkpointPath(), text.toString());
    }

    private void writeObservations() throws Exception {
        Files.writeString(minecraft.gameDirectory.toPath().resolve(
                restart ? "seasonal-restart-observations.csv" : "seasonal-observations.csv"), observations.toString());
    }

    private Path checkpointPath() { return minecraft.gameDirectory.toPath().resolve("seasonal-checkpoint.properties"); }

    private void command(String command) { minecraft.player.connection.sendCommand(command); }

    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        CompletableFuture<?> prior = pending;
        pending = prior.thenRunAsync(() -> action.accept(server), server);
    }

    private void next() {
        AstraEngine.LOGGER.info("ASTRA_SEASONAL_STEP {} complete frames={}", step, renderedFrames);
        step++;
        ticks = 0;
        firstFrame = renderedFrames;
    }

    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (seasonal step " + step + ", ticks " + ticks + ")"); }
    }
}
