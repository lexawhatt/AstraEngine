package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraSky;
import dev.lexawhatt.astraengine.client.AstraEngineClient;
import dev.lexawhatt.astraengine.client.compat.DistantCloudCompatibility;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Actual DH cloud callbacks with Astra + DH + Zume and no Sodium, plus an optional active-pack regression. */
final class DhCloudScenario {
    private static final String OWNER = "AstraEngine native cloud verification";
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final boolean iris;
    private final Consumer<RenderFrameEvent.Post> frames = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { this.clearFrames++; }
        else { this.clearFrames = 0; }
    };
    private final StringBuilder evidence = new StringBuilder("capture\tcloud_option\tbound\tobserved\tcancelled\tpack\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private DistantTerrainProbe terrain;
    private IDhApiConfigValue<Boolean> cloudSetting;
    private Boolean priorCloudApi;
    private DistantCloudCompatibility.Diagnostics previous;
    private byte[] irisConfiguration;
    private int clearFrames;
    private int step;
    private int ticks;
    private boolean retained;

    DhCloudScenario(boolean iris) {
        this.iris = iris;
        phase = iris ? "dh-clouds-iris" : "dh-clouds";
        game.options.hideGui = true;
        game.options.bobView().set(false);
        game.options.fov().set(70);
        game.options.renderDistance().set(6);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 6000, "DH cloud verification step timed out");
            switch (step) {
                case 0 -> {
                    require(ModList.get().isLoaded("distanthorizons") && ModList.get().isLoaded("zume"),
                            "DH cloud fixture requires original DH and Zume artifacts");
                    require(iris || !ModList.get().isLoaded("sodium"), "User-stack cloud fixture must not include Sodium");
                    require(packActive() == iris, "Unexpected shader-pack state for DH cloud fixture");
                    if (terrain == null) { terrain = new DistantTerrainProbe(); }
                    if (!terrain.configure()) { return false; }
                    cloudSetting = DhApi.Delayed.configs.graphics().genericRendering().cloudRenderingEnabled();
                    priorCloudApi = cloudSetting.getApiValue();
                    require(cloudSetting.setValue(true, OWNER), "DH rejected disposable cloud enable override");
                    if (iris) { irisConfiguration = Files.readAllBytes(path("config/iris.properties")); }
                    command("environment auto");
                    command("cloud-cover 0.65");
                    command("quality balanced");
                    server(this::prepare);
                    next();
                }
                case 1 -> {
                    if (!ready(100)) { return false; }
                    var observed = diagnostics();
                    require(observed.bound() && observed.failure().isEmpty(), "Optional DH cloud bridge did not bind");
                    checkSingleBinding();
                    if (!iris && observed.observedCloudGroups() < 100) { return false; }
                    // DH may switch the host option off during its own first LOD setup. Explicitly test the user's ON choice.
                    game.options.cloudStatus().set(CloudStatus.FANCY);
                    previous = observed;
                    next();
                }
                case 2 -> {
                    if (!ready(40)) { return false; }
                    require(game.options.cloudStatus().get() == CloudStatus.FANCY, "Cloud ownership changed the user's ON option");
                    checkWindow(!iris);
                    shot("01-clouds-on-single-owner");
                    game.options.cloudStatus().set(CloudStatus.OFF);
                    next();
                }
                case 3 -> {
                    if (!ready(30)) { return false; }
                    require(game.options.cloudStatus().get() == CloudStatus.OFF, "Cloud ownership changed the user's OFF option");
                    checkWindow(!iris);
                    shot("02-clouds-off");
                    game.options.cloudStatus().set(CloudStatus.FAST);
                    next();
                }
                case 4 -> {
                    if (!ready(30)) { return false; }
                    require(game.options.cloudStatus().get() == CloudStatus.FAST,
                            "Cloud ownership changed the user's FAST option");
                    checkWindow(!iris);
                    shot("03-clouds-fast-single-owner");
                    game.options.cloudStatus().set(CloudStatus.FANCY);
                    command("environment off");
                    next();
                }
                case 5 -> {
                    if (!ready(40)) { return false; }
                    checkWindow(false);
                    shot("04-astra-disabled-dh-clouds-restored");
                    command("environment auto");
                    next();
                }
                case 6 -> {
                    if (!ready(40)) { return false; }
                    checkWindow(!iris);
                    shot("05-astra-restored");
                    pending = game.reloadResourcePacks();
                    next();
                }
                case 7 -> {
                    if (!ready(50)) { return false; }
                    checkWindow(!iris);
                    shot("06-after-resource-reload");
                    require(Boolean.TRUE.equals(cloudSetting.getApiValue()) && cloudSetting.getValue(),
                            "Engine changed the DH cloud configuration instead of using frame cancellation");
                    require(packActive() == iris, "Cloud integration changed actual shader-pack ownership");
                    if (iris) {
                        require(Arrays.equals(irisConfiguration, Files.readAllBytes(path("config/iris.properties"))),
                                "Cloud integration modified the shader-pack settings");
                    }
                    retain("passed");
                    dispose();
                    return true;
                }
                default -> throw new IllegalStateException("Unknown DH cloud fixture step");
            }
            return false;
        } catch (Exception failure) {
            retain(failure.toString());
            dispose();
            throw failure;
        }
    }

    private void prepare(MinecraftServer server) {
        var level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(100000, 0, false, false);
        AstraSky.configure(server, PlanetarySkyProfile.EARTH.withLightPollution(0));
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 159, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        var player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(level, 0.5, 160, 0.5, 0, -22);
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        server.tickRateManager().setFrozen(true);
    }

    private void checkWindow(boolean expectCancellation) {
        checkSingleBinding();
        var current = diagnostics();
        require(current.bound() && current.failure().isEmpty(), "DH cloud event bridge failed during rendering");
        long observed = current.observedCloudGroups() - previous.observedCloudGroups();
        long cancelled = current.cancelledCloudGroups() - previous.cancelledCloudGroups();
        if (!iris) { require(observed > 0, "No real DH cloud group callbacks reached the current comparison"); }
        require(expectCancellation ? cancelled == observed && cancelled > 0 : cancelled == 0,
                "Cloud ownership mismatch: observed=" + observed + ", cancelled=" + cancelled);
        previous = current;
    }

    private void checkSingleBinding() {
        long bindings = DhApi.events.getAll(DhApiBeforeGenericObjectRenderEvent.class).stream()
                .filter(handler -> handler != null && handler.getClass().getName().equals(
                        "dev.lexawhatt.astraengine.client.compat.DistantCloudBridge$CloudListener"))
                .count();
        require(bindings == 1, "Expected one connection-owned DH cloud callback, found " + bindings);
    }

    private void shot(String name) throws Exception {
        Path file = path("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to replace retained DH cloud evidence");
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "GL error after DH cloud capture");
        var observed = diagnostics();
        evidence.append(name).append('\t').append(game.options.cloudStatus().get()).append('\t')
                .append(observed.bound()).append('\t').append(observed.observedCloudGroups()).append('\t')
                .append(observed.cancelledCloudGroups()).append('\t').append(packActive()).append('\n');
    }

    private void retain(String result) throws Exception {
        if (retained) { return; }
        retained = true;
        Path file = path("evidence/" + phase + "-observations.tsv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, evidence, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.writeString(path("evidence/" + phase + "-scope.txt"),
                "Actual DH public generic-object callbacks; only exact DistantHorizons:Clouds is suppressed.\n"
                        + "The user-stack phase requires Astra + DH + Zume and explicitly excludes Sodium.\n"
                        + "Native host Clouds FANCY/FAST/OFF are preserved; disabling Astra restores DH cloud ownership.\n"
                        + "Fixture API overrides enable real DH clouds and restore their original API value at disposal.\n"
                        + "Reload retains one callback binding. Active Iris pack phase requires zero Astra cancellation.\n"
                        + "This verifies callbacks and captures, not a GPU draw-call count or all DH versions.\n"
                        + RenderCompatibility.status() + "\nresult=" + result + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void dispose() {
        if (cloudSetting != null) {
            require(cloudSetting.setValue(priorCloudApi, OWNER), "DH rejected restoring original cloud API override");
            cloudSetting = null;
        }
        if (terrain != null) { terrain.close(); }
        NeoForge.EVENT_BUS.unregister(frames);
        game.options.hideGui = false;
    }

    private DistantCloudCompatibility.Diagnostics diagnostics() {
        return AstraEngineClient.distantCloudCompatibility().diagnostics();
    }
    private boolean packActive() { return RenderCompatibility.diagnostics().shaderPackInUse().orElse(false); }
    private boolean ready(int minimum) { return ticks >= minimum && clearFrames >= 8; }
    private Path path(String relative) { return game.gameDirectory.toPath().resolve(relative); }
    private void command(String command) { game.player.connection.sendCommand("astra-render " + command); }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_DH_CLOUD_STEP phase={} step={} complete", phase, step);
        step++;
        ticks = 0;
        clearFrames = 0;
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (" + phase + " step " + step + ", tick " + ticks + ")"); }
    }
}
