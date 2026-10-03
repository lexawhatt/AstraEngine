package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.client.render.OverworldSkyRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.client.sky.SkyStateClient;
import dev.lexawhatt.astraengine.client.sky.CloudNoiseField;
import dev.lexawhatt.astraengine.client.sky.EarthCloudState;
import dev.lexawhatt.astraengine.client.sky.EarthCloudWeather;
import dev.lexawhatt.astraengine.client.solar.AstralOverworldEffects;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import dev.lexawhatt.astraengine.network.EarthWeatherPayload;
import dev.lexawhatt.astraengine.network.EarthWeatherReceivedEvent;
import dev.lexawhatt.astraengine.network.SolarPayload;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.server.SkyService;
import dev.lexawhatt.astraengine.server.SolarState;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.glfw.GLFW;

/** Actual canonical Earth clouds, chart changes, source weather, remnant radiance and resource reload. */
final class EarthCloudGroundScenario {
    private record View(String name, double altitude, float pitch, long day, float rain, boolean clouds) { }
    private static final double LATITUDE = -0.0651121887842809;
    private static final double LONGITUDE = -0.07914923406754958;
    private static final View[] VIEWS = {
            new View("below-day", 500, -25, 6000, 0, true),
            new View("inside-day", 2500, -3, 6000, 0, true),
            new View("above-day", 9500, 30, 6000, 0, true),
            new View("above-off", 9500, 30, 6000, 0, false),
            new View("below-twilight", 500, -8, 11500, 0, true),
            new View("below-twilight-off", 500, -8, 11500, 0, false),
            new View("below-night", 500, -25, 18000, 0, true),
            new View("below-night-off", 500, -25, 18000, 0, false),
            new View("below-rain", 500, -25, 6000, .8f, true),
            new View("below-healthy", 500, -25, 6000, 0, true),
            new View("below-remnant", 500, -25, 6000, 0, true),
            new View("after-reload-healthy", 500, -25, 6000, 0, true)
    };
    private final Minecraft game = Minecraft.getInstance();
    private final SkyStateClient ordering = new SkyStateClient();
    private final StringBuilder evidence = new StringBuilder("Canonical Earth ground cloud transport\n");
    private final Consumer<RenderFrameEvent.Post> frames = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { this.frameCount++; }
        else { this.frameCount = 0; }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private EarthWeatherPayload sourceWeather;
    private ClientLevel firstLevel;
    private EarthChart expectedChart;
    private int view, step, frameCount;
    private int sourceReadyFrame = -1;
    private long configuredAt;
    private float healthyLinear;
    private long frozenGameTime = -1;
    private ShaderInstance preReloadShader;

    EarthCloudGroundScenario() {
        game.options.renderDistance().set(6); game.options.simulationDistance().set(5); game.options.broadcastOptions();
        game.options.hideGui = true; game.options.bobView().set(false); game.options.fov().set(70);
        game.options.pauseOnLostFocus = false;
        game.options.framerateLimit().set(60);
        GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1920, 1080);
        evidence.append("GL_VENDOR=").append(GL11.glGetString(GL11.GL_VENDOR))
                .append("\nGL_RENDERER=").append(GL11.glGetString(GL11.GL_RENDERER))
                .append("\nGL_VERSION=").append(GL11.glGetString(GL11.GL_VERSION))
                .append("\nJava=").append(System.getProperty("java.version"))
                .append(" maxHeap=").append(Runtime.getRuntime().maxMemory())
                .append(" renderDistance=6 simulationDistance=5 FOV=70 frameLimit=60 quality=balanced\n");
        game.player.connection.sendCommand("astra-render cloud-cover 0.65");
        game.player.connection.sendCommand("astra-render auto-exposure false");
        game.player.connection.sendCommand("astra-render exposure 1");
        game.player.connection.sendCommand("astra-render shafts false");
        game.player.connection.sendCommand("astra-render quality balanced");
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (step == 0) {
            configure(); step = 1; frameCount = 0; sourceReadyFrame = -1;
            configuredAt = System.nanoTime(); return false;
        }
        require(System.nanoTime() - configuredAt < 120_000_000_000L, "Cloud ground view timed out: " + VIEWS[view].name);
        if (game.level.effects() instanceof AstralOverworldEffects activeEffects) {
            var activeSky = activeEffects.skyState();
            var direction = activeSky.toHostDirection(game.level, activeSky.sample(game.level, 0).sunDirection());
            game.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x(), direction.z())));
            game.player.setXRot(VIEWS[view].pitch);
        }
        if (game.screen != null || game.getOverlay() != null || frameCount < 30
                || !game.level.dimension().equals(EarthWorlds.dimension(expectedChart))) { return false; }
        require(game.level.effects() instanceof AstralOverworldEffects, "Canonical Earth sky effects missing");
        var effects = (AstralOverworldEffects) game.level.effects();
        var sky = effects.skyState();
        View current = VIEWS[view];
        if (game.level.getDayTime() != current.day || sky.earthDayTime() != current.day
                || Math.abs(sky.earthRain() - current.rain) > .0001) { sourceReadyFrame = -1; return false; }
        var renderer = (OverworldSkyRenderer) field(effects, "renderer");
        var program = (ShaderInstance) field(renderer, "shader");
        var targetSun = sky.toHostDirection(game.level, sky.sample(game.level, 0).sunDirection());
        float[] renderedSun = values(program, "SunDirection", 3);
        double sourceError = Math.max(Math.abs(renderedSun[0] - targetSun.x()),
                Math.max(Math.abs(renderedSun[1] - targetSun.y()), Math.abs(renderedSun[2] - targetSun.z())));
        // ClientTick can accept a time packet before rendering its first frame. Capture only the
        // converged rendered source, never the previous frame merely because ClientLevel is current.
        if (sourceError > .0001) { sourceReadyFrame = -1; return false; }
        if (sourceReadyFrame < 0) { sourceReadyFrame = frameCount; }
        if (frameCount - sourceReadyFrame < 3) { return false; }
        require(Math.abs(values(program, "Weather", 2)[0] - sourceWeather.rain()) < .0001,
                "Canonical atmospheric sky retained chart-private rain interpolation");
        require(effects.ownsClouds(game.level), "Canonical Earth restored duplicate host clouds");
        require(program.getUniform("VolumeEnabled").getIntBuffer().get(0) == (current.clouds ? 1 : 0),
                "Cloud enable control did not select the actual transport path");
        Object volumes = field(renderer, "volumes");
        var transport = (ShaderInstance) field(volumes, "transport");
        float linear = 0;
        if (current.clouds) {
            if (transport.getUniform("SkyAccess").getFloatBuffer().get(0) <= 0) { return false; }
            float[] cloudSun = values(transport, "SunDirection", 3);
            require(Math.abs(cloudSun[0] - renderedSun[0]) < .0001
                    && Math.abs(cloudSun[1] - renderedSun[1]) < .0001
                    && Math.abs(cloudSun[2] - renderedSun[2]) < .0001,
                    "Rendered sky and cloud transport disagree on the current Sun direction");
            float[] planet = values(transport, "CloudPlanet", 4), params = values(transport, "EarthCloudParams", 4);
            require(Math.abs(planet[3] - 6371) < .01 && Math.abs(params[0] - .65) < .0001,
                    "Canonical spherical cloud field was not bound on the ground");
            require(Math.abs(params[2] - sourceWeather.rain()) < .0001
                    && Math.abs(params[1] - Math.sin(sourceWeather.seasonPhase() * Math.PI * 2)) < .0001,
                    "Ground clouds sampled chart-private weather/calendar");
            require(transport.getUniform("EarthOpticsReady").getIntBuffer().get(0) == 1,
                    "Ground cloud lighting has no ready optical columns");
            linear = sampleLinear(field(volumes, "sky"));
            evidence.append(current.name).append(" chart=").append(expectedChart.dimensionId())
                    .append(" planet=").append(Arrays.toString(planet)).append(" params=").append(Arrays.toString(params))
                    .append(" wind=").append(Arrays.toString(values(transport, "CloudWind", 2)))
                    .append(" bodySun=").append(Arrays.toString(values(transport, "EarthCloudSun", 3)))
                    .append(" linearCloudMean=").append(linear).append('\n');
        }
        if (frozenGameTime < 0) { frozenGameTime = sky.earthGameTime(game.level); }
        require(sky.earthGameTime(game.level) == frozenGameTime, "Frozen source wind drifted across cloud comparisons");
        verifyWeatherOrdering(sky);
        if (view == 9) {
            healthyLinear = linear; require(linear > .00001, "Healthy cloud probe contained no scattering");
            verifyZeroSource(transport);
        }
        if (view == 10) {
            require(linear < healthyLinear * .06, "Remnant clouds retained an independent bright source");
            evidence.append("remnantToHealthyLinear=").append(linear / healthyLinear).append('\n');
            preReloadShader = transport;
        }
        if (view == 11) {
            require(transport != preReloadShader, "Resource reload reused the disposed cloud shader");
            require(Math.abs(linear / healthyLinear - 1) < .08, "Reload changed canonical cloud radiance");
        }
        evidence.append(current.name).append(" dayTime=").append(game.level.getDayTime())
                .append(" renderedSun=").append(Arrays.toString(renderedSun))
                .append(" sourceError=").append(sourceError).append(" settledFrames=")
                .append(frameCount - sourceReadyFrame).append('\n');
        var directory = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(directory);
        try (NativeImage capture = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            capture.writeToFile(directory.resolve("earth-cloud-ground-" + current.name + ".png"));
        }
        Files.writeString(directory.resolve("earth-cloud-ground.txt"), evidence, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "Ground clouds left an OpenGL error");
        if (++view == VIEWS.length) {
            ordering.logout(null); firstLevel = null; NeoForge.EVENT_BUS.unregister(frames); return true;
        }
        if (view == 11) { pending = game.reloadResourcePacks(); }
        step = 0; return false;
    }

    private void configure() {
        View current = VIEWS[view];
        game.options.cloudStatus().set(current.clouds ? CloudStatus.FANCY : CloudStatus.OFF);
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            var source = server.overworld(); var player = server.getPlayerList().getPlayers().getFirst();
            source.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            source.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            source.setDayTime(current.day); source.setRainLevel(current.rain); source.setThunderLevel(0);
            var address = new GeographicPosition(LATITUDE, LONGITUDE, current.altitude);
            var ground = new ContinentalTerrain(EarthWorlds.terrainVersion(server), ContinentalTerrain.SEED)
                    .sample(address.normal());
            require(ground.heightMeters() < 420 && ground.waterMeters() < 420,
                    "Ground cloud fixture location is no longer below its lowest camera");
            if (view == 1) {
                var weather = SkyService.weather(server);
                var state = EarthCloudState.sample(weather.gameTime(), 0, .65f, weather.seasonPhase(), 0, 0, 1);
                var point = address.normal().multiply(6371 + current.altitude * .001);
                var noise = new CloudNoiseField();
                var region = EarthCloudWeather.sample(noise, state, point);
                double density = state.density(noise, point, 6371);
                require(density > .005 && current.altitude * .001 < EarthCloudWeather.tops(region).y(),
                        "Inside-cloud fixture missed its actual occupied regional volume: density=" + density
                                + ", region=" + region + ", weather=" + weather);
            }
            expectedChart = EarthChart.owner(address,
                    EarthWorlds.terrainVersion(server)).orElseThrow();
            var level = PlanetSurfaceWorlds.ensure(server, expectedChart);
            var feet = expectedChart.resolve(address).orElseThrow();
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(level, feet.x(), feet.y(), feet.z(), 0, current.pitch);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            server.tickRateManager().setFrozen(true);
            SolarState solar = SolarState.get(server);
            if (view == 10) {
                solar.startDemo(10); for (int i = 0; i < 600; i++) { solar.tick(true); }
                require(solar.snapshot().phase() == StellarEvolutionSnapshot.Phase.REMNANT, "Real remnant fixture failed");
            } else { solar.reset(); }
            PacketDistributor.sendToPlayer(player, new SolarPayload(solar.snapshot()));
            sourceWeather = SkyService.weather(server); SkyService.sendWeather(player);
        }, server);
    }

    private void verifyWeatherOrdering(SkyStateClient sky) throws Exception {
        if (view == 0) {
            firstLevel = game.level;
            ordering.receiveWeather(new EarthWeatherReceivedEvent(new EarthWeatherPayload(987654321,
                    Long.MAX_VALUE - 13, .7654321, .4f, .1f)));
            require(ordering.earthGameTime(firstLevel) == 987654321, "Source game clock did not anchor to current level");
        } else if (view == 1) {
            require(firstLevel != game.level, "Weather-order test did not cross an actual ClientLevel boundary");
            require(ordering.earthGameTime(game.level) == 987654321 && ordering.earthSeasonPhase() == .7654321
                    && ordering.earthDayTime() == Long.MAX_VALUE - 13,
                    "A new chart reused the old level's clock offset or replaced Earth's calendar");
            ordering.receiveWeather(new EarthWeatherReceivedEvent(new EarthWeatherPayload(43219876, -912345,
                    .125, .8f, .3f)));
            require(ordering.earthGameTime(game.level) == 43219876 && ordering.earthDayTime() == -912345,
                    "New source weather did not re-anchor the replacement level");
            ordering.logout(null);
            require(ordering.earthGameTime(game.level) == 0 && ordering.earthRain() == 0
                    && field(ordering, "weatherLevel") == null, "Disconnect retained source weather or its old level");
            firstLevel = null;
            evidence.append("actualClientLevelOrdering=true exactLongCalendar=true logoutClearsLevel=true\n");
        }
        require(sky.earthSeasonPhase() == sourceWeather.seasonPhase(), "Live weather changed the authoritative source season");
    }

    private static float sampleLinear(Object target) throws Exception {
        return sampleTransport(target)[0];
    }

    private void verifyZeroSource(ShaderInstance shader) throws Exception {
        var constructor = Class.forName("dev.lexawhatt.astraengine.client.render.HdrColorTarget")
                .getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        var maskConstructor = Class.forName("dev.lexawhatt.astraengine.client.render.CelestialBloomPipeline$ColorState")
                .getDeclaredConstructor();
        maskConstructor.setAccessible(true);
        float[] original = values(shader, "EarthCloudParams", 4);
        int geometry = shader.getUniform("GeometryPass").getIntBuffer().get(0);
        float shaft = values(shader, "ShaftStrength", 1)[0];
        try (var saved = new FullscreenPass(4);
             var masks = (AutoCloseable) maskConstructor.newInstance();
             var output = (AutoCloseable) constructor.newInstance(128, 72)) {
            var bind = output.getClass().getDeclaredMethod("bind"); bind.setAccessible(true); bind.invoke(output);
            shader.safeGetUniform("GeometryPass").set(0);
            for (float shaftStrength : new float[] {0, .6f}) {
                shader.safeGetUniform("ShaftStrength").set(shaftStrength);
                shader.safeGetUniform("EarthCloudParams").set(original[0], original[1], original[2], original[3]);
                FullscreenPass.draw(shader);
                float[] healthyPixels = readTransport(output);
                float[] healthy = meanTransport(healthyPixels);
                require(healthy[0] > .00001 && healthy[1] < .999,
                        "Source-scaling GPU fixture did not contain illuminated cloud volume");
                for (float scale : new float[] {0, .25f, 2}) {
                    shader.safeGetUniform("EarthCloudParams").set(original[0], original[1], original[2], original[3] * scale);
                    FullscreenPass.draw(shader);
                    float[] actual = readTransport(output);
                    for (int pixel = 0; pixel < actual.length; pixel += 4) {
                        require(Math.abs(actual[pixel + 3] - healthyPixels[pixel + 3]) < .0001,
                                "Stellar source changed physical cloud opacity at sample " + pixel / 4);
                        for (int channel = 0; channel < 3; channel++) {
                            float expected = healthyPixels[pixel + channel] * scale;
                            // Both images are stored in RGBA16F. Allow quantization, not a separate dark-stage gain.
                            float tolerance = scale == 0 ? 1e-7f : Math.max(2e-6f, Math.abs(expected) * .008f);
                            require(Float.isFinite(actual[pixel + channel])
                                            && Math.abs(actual[pixel + channel] - expected) <= tolerance,
                                    "Cloud radiance did not follow stellar source at scale " + scale
                                            + ", sample " + pixel / 4 + ", channel " + channel);
                        }
                    }
                    evidence.append("sourceScalingGpu shafts=").append(shaftStrength).append(" scale=").append(scale)
                            .append(" healthy=").append(Arrays.toString(healthy))
                            .append(" actual=").append(Arrays.toString(meanTransport(actual)))
                            .append(" pixels=64 opacityUnchanged=true\n");
                }
            }
        } finally {
            shader.safeGetUniform("EarthCloudParams").set(original[0], original[1], original[2], original[3]);
            shader.safeGetUniform("GeometryPass").set(geometry);
            shader.safeGetUniform("ShaftStrength").set(shaft);
        }
    }

    private static float[] sampleTransport(Object target) throws Exception {
        return meanTransport(readTransport(target));
    }

    private static float[] meanTransport(float[] pixels) {
        float total = 0, transmission = 0;
        for (int at = 0; at < pixels.length; at += 4) {
            total += pixels[at] * .2126f + pixels[at + 1] * .7152f + pixels[at + 2] * .0722f;
            transmission += pixels[at + 3];
        }
        return new float[] {total * 4 / pixels.length, transmission * 4 / pixels.length};
    }

    private static float[] readTransport(Object target) throws Exception {
        int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] parameters = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] previousParameters = new int[parameters.length];
        for (int i = 0; i < parameters.length; i++) { previousParameters[i] = GL11.glGetInteger(parameters[i]); }
        int width = (int) field(target, "width"), height = (int) field(target, "height");
        float[] pixels = new float[64 * 4];
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int parameter : parameters) { GL11.glPixelStorei(parameter, parameter == GL11.GL_PACK_ALIGNMENT ? 4 : 0); }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, (int) field(target, "framebuffer"));
            float[] pixel = new float[4];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    GL11.glReadPixels(width * (2 * x + 1) / 16, height * (2 * y + 1) / 16,
                            1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                    System.arraycopy(pixel, 0, pixels, (y * 8 + x) * 4, 4);
                }
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
            for (int i = 0; i < parameters.length; i++) { GL11.glPixelStorei(parameters[i], previousParameters[i]); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
        return pixels;
    }

    private static float[] values(ShaderInstance shader, String name, int size) {
        float[] result = new float[size]; var values = shader.getUniform(name).getFloatBuffer();
        for (int i = 0; i < size; i++) { result[i] = values.get(i); }
        return result;
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean value, String message) {
        if (!value) { throw new IllegalStateException(message); }
    }
}
