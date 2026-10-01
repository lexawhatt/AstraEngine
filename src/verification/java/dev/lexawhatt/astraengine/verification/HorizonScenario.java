package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.client.AstraEngineClient;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Native spherical-horizon calibration; real host blocks and DH LODs remain flat in this first slice. */
final class HorizonScenario {
    private static final ResourceKey<Level> OCEAN = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, "horizon_ocean"));
    private static final BlockPos MARKER = new BlockPos(0, 63, 0);
    private static final BlockPos CHEST = new BlockPos(3, 64, 3);
    private static final double GROUND_FEET_Y = 64.08;
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final Consumer<RenderFrameEvent.Post> frames = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { this.clearFrames++; }
        else { this.clearFrames = 0; }
    };
    private final Consumer<RenderLevelStageEvent> stages = event -> {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            this.projectionM00 = event.getProjectionMatrix().m00();
            this.projectionM11 = event.getProjectionMatrix().m11();
        }
    };
    private final StringBuilder captures = new StringBuilder("capture\twidth\theight\tfov\teye_altitude_m\t"
            + "camera_x\tcamera_y\tcamera_z\tyaw\tpitch\tprojection_m00\tprojection_m11\tradius_m\tdraws\tactive\tflat\ttowers\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private Properties checkpoint = new Properties();
    private DistantTerrainProbe distant;
    private byte[] irisConfiguration;
    private long inactiveDraws;
    private long lodStartedAt;
    private long beforeReloadDraws;
    private int clearFrames;
    private int ticks;
    private int step;
    private boolean flat;
    private boolean towers = true;
    private boolean retained;
    private float projectionM00;
    private float projectionM11;

    HorizonScenario(String phase) {
        this.phase = phase;
        game.options.autoJump().set(false);
        game.options.bobView().set(false);
        game.options.renderDistance().set(4);
        game.options.fov().set(70);
        game.options.hideGui = true;
        GLFW.glfwFocusWindow(game.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(frames);
        NeoForge.EVENT_BUS.addListener(stages);
    }

    boolean tick() throws Exception {
        try {
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 10000, "Horizon fixture exceeded the per-step timeout");
            boolean complete = switch (phase) {
                case "horizon-create" -> createTick();
                case "horizon-restart" -> restartTick();
                case "horizon-dh" -> distantTick();
                case "horizon-iris" -> irisTick();
                default -> throw new IllegalArgumentException("Unsupported horizon fixture phase " + phase);
            };
            if (complete) {
                retain("passed");
                dispose();
                AstraEngine.LOGGER.info("ASTRA_HORIZON_PASSED phase={}", phase);
            }
            return complete;
        } catch (Exception failure) {
            dispose();
            retain(failure.toString());
            throw failure;
        }
    }

    private boolean createTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(!ModList.get().isLoaded("distanthorizons"), "Plain horizon fixture must not include DH");
                require(!packActive(), "Plain horizon fixture must not include an active shader pack");
                configure(true, true, false);
                server(this::prepare);
                next();
            }
            case 1 -> {
                if (!settled(80)) { return false; }
                checkActive(1.7);
                shot("01-ground-curved");
                configure(true, true, true);
                next();
            }
            case 2 -> {
                if (!settled(20)) { return false; }
                shot("02-ground-flat-comparison");
                configure(true, false, false);
                next();
            }
            case 3 -> {
                if (!settled(20)) { return false; }
                shot("03-ground-clean-horizon");
                configure(true, true, false);
                move(162.38, 0);
                next();
            }
            case 4 -> {
                if (!settled(30)) { return false; }
                checkActive(100);
                shot("04-100m-curved");
                configure(true, true, true);
                next();
            }
            case 5 -> {
                if (!settled(20)) { return false; }
                shot("05-100m-flat-comparison");
                configure(true, false, false);
                next();
            }
            case 6 -> {
                if (!settled(20)) { return false; }
                shot("06-100m-clean-horizon");
                configure(true, true, false);
                move(1062.38, 0);
                next();
            }
            case 7 -> {
                if (!settled(30)) { return false; }
                checkActive(1000);
                shot("07-1km-curved");
                configure(true, false, false);
                next();
            }
            case 8 -> {
                if (!settled(20)) { return false; }
                shot("08-1km-clean-horizon");
                move(100062.38, 12);
                next();
            }
            case 9 -> {
                if (!settled(40)) { return false; }
                checkActive(100000);
                shot("09-100km-clean-horizon");
                beforeReloadDraws = AstraEngineClient.horizonRenderer().diagnostics().draws();
                pending = game.reloadResourcePacks();
                next();
            }
            case 10 -> {
                if (!settled(30)) { return false; }
                checkActive(100000);
                require(AstraEngineClient.horizonRenderer().diagnostics().draws() > beforeReloadDraws,
                        "Horizon draws did not resume after resource reload");
                shot("10-after-resource-reload");
                GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 960, 540);
                next();
            }
            case 11 -> {
                if (!settled(30) || game.getMainRenderTarget().width != 960
                        || game.getMainRenderTarget().height != 540) { return false; }
                checkActive(100000);
                shot("11-resized-960x540");
                GLFW.glfwSetWindowSize(game.getWindow().getWindow(), 1280, 720);
                configure(true, true, false);
                move(GROUND_FEET_Y, 20);
                next();
            }
            case 12 -> {
                if (!settled(40) || game.getMainRenderTarget().width != 1280) { return false; }
                checkActive(1.7);
                verifyClientMarker();
                shot("12-real-near-platform");
                AstraEngineClient.horizonRenderer().setEnabled(false);
                inactiveDraws = AstraEngineClient.horizonRenderer().diagnostics().draws();
                next();
            }
            case 13 -> {
                if (!settled(30)) { return false; }
                checkInactive();
                shot("13-pass-disabled");
                AstraEngineClient.horizonRenderer().setEnabled(true);
                next();
            }
            case 14 -> {
                if (!settled(30)) { return false; }
                checkActive(1.7);
                shot("14-pass-restored");
                server(server -> {
                    var player = player(server);
                    player.teleportTo(server.overworld(), 0.5, 180, -8.5, 0, 20);
                    player.setDeltaMovement(Vec3.ZERO);
                });
                next();
            }
            case 15 -> {
                if (game.level.dimension() != Level.OVERWORLD || clearFrames < 8 || ticks < 40) { return false; }
                require(!AstraEngineClient.horizonRenderer().diagnostics().active(),
                        "Horizon pass remained active outside its opt-in ocean dimension");
                shot("15-unrelated-overworld");
                move(GROUND_FEET_Y, 20);
                next();
            }
            case 16 -> {
                if (!settled(50)) { return false; }
                checkActive(1.7);
                shot("16-returned-platform");
                game.options.fov().set(30);
                require(game.options.fov().get() == 30, "Host rejected the supported narrow comparison FOV");
                move(GROUND_FEET_Y, 0);
                next();
            }
            case 17 -> {
                if (!settled(30)) { return false; }
                checkActive(1.7);
                require(game.options.fov().get() == 30, "Ground curved comparison lost its narrow FOV");
                shot("17-ground-curved-fov30");
                configure(true, true, true);
                next();
            }
            case 18 -> {
                if (!settled(20)) { return false; }
                require(game.options.fov().get() == 30, "Ground flat comparison lost its narrow FOV");
                shot("18-ground-flat-fov30");
                configure(true, true, false);
                move(162.38, 0);
                next();
            }
            case 19 -> {
                if (!settled(30)) { return false; }
                checkActive(100);
                require(game.options.fov().get() == 30, "Elevated curved comparison lost its narrow FOV");
                shot("19-100m-curved-fov30");
                configure(true, true, true);
                next();
            }
            case 20 -> {
                if (!settled(20)) { return false; }
                require(game.options.fov().get() == 30, "Elevated flat comparison lost its narrow FOV");
                shot("20-100m-flat-fov30");
                configure(true, true, false);
                game.options.fov().set(70);
                move(GROUND_FEET_Y, 20);
                next();
            }
            case 21 -> {
                if (!settled(30)) { return false; }
                checkActive(1.7);
                server(server -> { verifyWorld(server); checkpoint = properties(player(server)); });
                next();
            }
            case 22 -> {
                if (!settled(20)) { return false; }
                StringWriter text = new StringWriter();
                checkpoint.store(text, "Persistent host-owned ocean platform and exact player pose");
                writeNew(path("horizon-checkpoint.properties"), text.toString());
                shot("21-returned-and-saved");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected horizon create step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(!packActive() && !ModList.get().isLoaded("distanthorizons"), "Restart requires the plain native profile");
                checkpoint.load(new StringReader(Files.readString(path("horizon-checkpoint.properties"))));
                server(server -> {
                    verifyWorld(server);
                    require(properties(player(server)).equals(checkpoint),
                            "Process restart changed the host-owned player pose or dimension");
                });
                next();
            }
            case 1 -> {
                if (!settled(80)) { return false; }
                checkActive(1.7);
                verifyClientMarker();
                shot("01-reopened-platform");
                pending = game.reloadResourcePacks();
                next();
            }
            case 2 -> {
                if (!settled(30)) { return false; }
                checkActive(1.7);
                shot("02-reloaded-platform");
                server(server -> {
                    verifyWorld(server);
                    require(properties(player(server)).equals(checkpoint), "Reload changed retained player pose");
                });
                next();
            }
            case 3 -> { return true; }
            default -> throw new IllegalStateException("Unexpected horizon restart step " + step);
        }
        return false;
    }

    private boolean distantTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(ModList.get().isLoaded("distanthorizons") && !packActive(), "DH horizon fixture requires DH with no active pack");
                if (distant == null) { distant = new DistantTerrainProbe(); }
                if (!distant.configure()) { return false; }
                configure(true, true, false);
                server(server -> { prepare(server); stage(player(server), server.getLevel(OCEAN), 162.38, 12); });
                next();
            }
            case 1 -> {
                if (!settled(80)) { return false; }
                checkActive(100);
                distant.resetCounters();
                lodStartedAt = System.nanoTime();
                next();
            }
            case 2 -> {
                if (ticks % 200 == 0) { Files.writeString(path("horizon-dh-progress.txt"), distant.description()); }
                if (!settled(80) || System.nanoTime() - lodStartedAt < 80_000_000_000L
                        || distant.bufferRenders() < 100) { return false; }
                String observed = distant.description();
                require(Pattern.compile("covered=[1-9][0-9]*/").matcher(observed).find(),
                        "DH callbacks did not establish nonzero actual LOD depth coverage: " + observed);
                require(observed.contains("horizon_ocean"), "DH observation window did not reach the ocean dimension: " + observed);
                writeNew(path("evidence/horizon-dh-observations.txt"), observed + "\n" + RenderCompatibility.status());
                shot("01-lods-on");
                distant.renderEnabled(false);
                next();
            }
            case 3 -> {
                if (!settled(40)) { return false; }
                checkActive(100);
                shot("02-lods-off");
                distant.renderEnabled(true);
                next();
            }
            case 4 -> {
                if (!settled(40)) { return false; }
                checkActive(100);
                shot("03-lods-restored");
                server(this::verifyWorld);
                next();
            }
            case 5 -> { return true; }
            default -> throw new IllegalStateException("Unexpected horizon DH step " + step);
        }
        return false;
    }

    private boolean irisTick() throws Exception {
        switch (step) {
            case 0 -> {
                require(packActive(), "Iris horizon fixture requires an actually active shader pack");
                irisConfiguration = Files.readAllBytes(path("config/iris.properties"));
                configure(true, true, false);
                server(this::prepare);
                next();
            }
            case 1 -> {
                if (!settled(80)) { return false; }
                require(packActive() && !AstraEngineClient.horizonRenderer().diagnostics().active(),
                        "Horizon renderer did not yield to an active pack");
                inactiveDraws = AstraEngineClient.horizonRenderer().diagnostics().draws();
                shot("01-pack-owned-horizon-toggle-on");
                AstraEngineClient.horizonRenderer().setEnabled(false);
                next();
            }
            case 2 -> {
                if (!settled(40)) { return false; }
                checkInactive();
                shot("02-pack-owned-horizon-toggle-off");
                AstraEngineClient.horizonRenderer().setEnabled(true);
                pending = game.reloadResourcePacks();
                next();
            }
            case 3 -> {
                if (!settled(40)) { return false; }
                checkInactive();
                require(packActive(), "Resource reload disabled the user's shader pack");
                require(Arrays.equals(irisConfiguration, Files.readAllBytes(path("config/iris.properties"))),
                        "Horizon fixture modified the shader-pack configuration");
                shot("03-pack-owned-after-reload");
                writeNew(path("evidence/horizon-iris-compatibility.txt"), RenderCompatibility.status());
                server(this::verifyWorld);
                next();
            }
            case 4 -> { return true; }
            default -> throw new IllegalStateException("Unexpected horizon Iris step " + step);
        }
        return false;
    }

    private void prepare(MinecraftServer server) {
        require(!Files.exists(path("horizon-checkpoint.properties")), "Refusing to overwrite an existing horizon fixture");
        var level = server.getLevel(OCEAN);
        require(level != null && level.getMinBuildHeight() == 0 && level.getMaxBuildHeight() == 256,
                "Opt-in horizon ocean is missing or has an unexpected height range");
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.overworld().setDayTime(6000);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        level.getChunkAt(new BlockPos(32, 63, 32));
        require(level.getBlockState(new BlockPos(32, 0, 32)).is(Blocks.BEDROCK)
                        && level.getBlockState(new BlockPos(32, 55, 32)).is(Blocks.SAND)
                        && level.getBlockState(new BlockPos(32, 63, 32)).is(Blocks.WATER)
                        && level.getBlockState(new BlockPos(32, 64, 32)).isAir(),
                "Disposable horizon world does not match the intended flat ocean profile");
        for (int x = -6; x <= 6; x++) {
            for (int z = -12; z <= 7; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
        require(chest != null, "Disposable horizon chest was not created");
        chest.clearContent();
        chest.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 7));
        chest.setItem(19, new ItemStack(Items.GOLD_INGOT, 11));
        chest.setChanged();
        var player = player(server);
        player.setGameMode(GameType.CREATIVE);
        stage(player, level, GROUND_FEET_Y, 0);
        verifyWorld(server);
    }

    private void verifyWorld(MinecraftServer server) {
        var level = server.getLevel(OCEAN);
        require(level != null, "Retained ocean dimension is missing");
        level.getChunkAt(MARKER);
        level.getChunkAt(CHEST);
        require(level.getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK), "Persistent ocean marker is missing");
        require(level.getBlockEntity(CHEST) instanceof ChestBlockEntity, "Persistent ocean chest is missing");
        var chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            require(slot == 0 ? stack.is(Items.AMETHYST_SHARD) && stack.getCount() == 7
                            : slot == 19 ? stack.is(Items.GOLD_INGOT) && stack.getCount() == 11 : stack.isEmpty(),
                    "Persistent ocean chest differs at slot " + slot);
        }
    }

    private void verifyClientMarker() {
        require(game.level.hasChunk(MARKER.getX() >> 4, MARKER.getZ() >> 4)
                        && game.level.getBlockState(MARKER).is(Blocks.DIAMOND_BLOCK),
                "Real near marker did not synchronize to the client");
    }

    private void move(double feetY, float pitch) {
        server(server -> stage(player(server), server.getLevel(OCEAN), feetY, pitch));
    }

    private void stage(ServerPlayer player, net.minecraft.server.level.ServerLevel level, double feetY, float pitch) {
        require(level != null, "Horizon staging world is unavailable");
        player.teleportTo(level, 0.5, feetY, -8.5, 0, pitch);
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    private Properties properties(ServerPlayer player) {
        Properties values = new Properties();
        values.setProperty("dimension", player.serverLevel().dimension().location().toString());
        values.setProperty("x", Double.toHexString(player.getX()));
        values.setProperty("y", Double.toHexString(player.getY()));
        values.setProperty("z", Double.toHexString(player.getZ()));
        values.setProperty("yaw", Float.toHexString(player.getYRot()));
        values.setProperty("pitch", Float.toHexString(player.getXRot()));
        return values;
    }

    private void configure(boolean enabled, boolean showTowers, boolean flatComparison) {
        var renderer = AstraEngineClient.horizonRenderer();
        renderer.setEnabled(enabled);
        renderer.setTowers(showTowers);
        renderer.setFlatComparison(flatComparison);
        towers = showTowers;
        flat = flatComparison;
    }

    private void checkActive(double expectedAltitude) {
        var observed = AstraEngineClient.horizonRenderer().diagnostics();
        require(observed.active() && observed.draws() > 0, "Horizon renderer did not draw in its target world");
        require(Math.abs(observed.radiusMeters() - 6371000) < 0.001, "Horizon renderer changed Earth-scale radius");
        require(Math.abs(observed.eyeAltitudeMeters() - expectedAltitude) < 0.02,
                "Horizon eye altitude differs from camera geometry: " + observed.eyeAltitudeMeters());
    }

    private void checkInactive() {
        var observed = AstraEngineClient.horizonRenderer().diagnostics();
        require(!observed.active() && observed.draws() == inactiveDraws,
                "Disabled or pack-owned horizon still emitted Astra draw calls");
    }

    private boolean settled(int minimumTicks) {
        return game.level != null && game.level.dimension().equals(OCEAN) && clearFrames >= 8 && ticks >= minimumTicks;
    }

    private boolean packActive() { return RenderCompatibility.diagnostics().shaderPackInUse().orElse(false); }

    private void shot(String name) throws Exception {
        Path file = path("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to overwrite retained horizon evidence " + file);
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after horizon capture");
        var observed = AstraEngineClient.horizonRenderer().diagnostics();
        var camera = game.gameRenderer.getMainCamera();
        Vec3 position = camera.getPosition();
        captures.append(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%.9f\t%.9f\t%.9f\t%.9f\t%.6f\t%.6f\t"
                        + "%.9f\t%.9f\t%.3f\t%d\t%s\t%s\t%s%n", name, game.getMainRenderTarget().width,
                game.getMainRenderTarget().height, game.options.fov().get(), observed.eyeAltitudeMeters(),
                position.x, position.y, position.z, camera.getYRot(), camera.getXRot(), projectionM00, projectionM11, observed.radiusMeters(),
                observed.draws(), observed.active(), flat, towers));
    }

    private void retain(String result) throws Exception {
        if (retained) { return; }
        retained = true;
        writeNew(path("evidence/" + phase + "-captures.tsv"), captures.toString());
        writeNew(path("evidence/" + phase + "-scope.txt"),
                "Opt-in analytic spherical ocean/sky and fixed visual calibration towers, Earth radius 6371000 m.\n"
                        + "Nearby Minecraft blocks, collisions, water and DH LOD geometry remain host-flat.\n"
                        + "Towers are visual-only calibration objects, not generated terrain, entities or saved buildings.\n"
                        + "Plain phase observes height-dependent horizon, flat comparison, real near occlusion, reload, resize and deactivation.\n"
                        + "DH phase qualifies only 32 chunks (512 m), with original real DH callbacks, depth and on/off/on captures.\n"
                        + "Iris phase retains actual shader settings and requires this horizon pass to yield to an active pack.\n"
                        + "Exact original host pose, marker and complete chest inventory are checked independently after process restart.\n"
                        + "This does not verify whole-globe traversal, curved DH geometry or terrain-to-orbit stitching.\n"
                        + "phase=" + phase + "\nresult=" + result + "\n");
    }

    private void dispose() {
        if (distant != null) { distant.close(); }
        NeoForge.EVENT_BUS.unregister(frames);
        NeoForge.EVENT_BUS.unregister(stages);
        game.options.hideGui = false;
    }

    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private Path path(String relative) { return game.gameDirectory.toPath().resolve(relative); }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static void writeNew(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_HORIZON_STEP phase={} step={} complete", phase, step);
        step++;
        ticks = 0;
        clearFrames = 0;
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (" + phase + " step " + step + ", ticks " + ticks + ")"); }
    }
}
