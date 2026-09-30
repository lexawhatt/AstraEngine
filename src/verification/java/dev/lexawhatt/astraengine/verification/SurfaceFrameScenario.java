package dev.lexawhatt.astraengine.verification;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.api.SurfaceFrameChangedEvent;
import dev.lexawhatt.astraengine.api.SurfaceFrameSnapshot;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.PlanetaryGeographyState;
import dev.lexawhatt.astraengine.server.PlanetaryTerrainWorld;
import dev.lexawhatt.astraengine.server.SurfaceFrameTracker;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Actual host walking and collision across logical geographic frames, with exact disposable-save restart checks. */
final class SurfaceFrameScenario {
    private static final int FLOOR_Y = 1699;
    private static final BlockPos NORTH_MARKER = new BlockPos(0, FLOOR_Y, -2);
    private static final BlockPos SOUTH_MARKER = new BlockPos(0, FLOOR_Y, 2);
    private static final BlockPos CHEST = new BlockPos(2, FLOOR_Y + 1, 2);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean restart;
    private final String phase;
    private final StringBuilder cameraSamples = new StringBuilder("frame\tstep\tx\ty\tz\tyaw\tpitch\tdistance\n");
    private final ConcurrentLinkedQueue<String> serverSamples = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> frameEvents = new ConcurrentLinkedQueue<>();
    private final AtomicReference<RuntimeException> observationFailure = new AtomicReference<>();
    private final Consumer<RenderFrameEvent.Post> frameListener = this::onFrame;
    private final Consumer<ServerTickEvent.Post> serverListener = this::onServerTick;
    private final Consumer<SurfaceFrameChangedEvent> geographyListener = this::onGeography;
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private Properties checkpoint = new Properties();
    private volatile Observation observed;
    private volatile boolean tracking;
    private volatile int outwardCrossings;
    private volatile int returnCrossings;
    private Vec3 previousCamera;
    private float previousYaw;
    private float previousPitch;
    private Vec3 previousHost;
    private int clearFrames;
    private int frameCount;
    private int ticks;
    private volatile int step;
    private double maxCameraDelta;
    private volatile double maxHostDelta;
    private double collisionZ;
    private volatile boolean observerProbePassed;
    private boolean retained;

    SurfaceFrameScenario(boolean restart) {
        this.restart = restart;
        phase = "surface-frames-" + (restart ? "restart" : "create");
        game.options.autoJump().set(false);
        game.options.bobView().set(false);
        game.options.renderDistance().set(4);
        game.options.hideGui = false;
        GLFW.glfwFocusWindow(game.getWindow().getWindow());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, frameListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, serverListener);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, geographyListener);
    }

    boolean tick() throws Exception {
        try {
            RuntimeException failure = observationFailure.get();
            if (failure != null) { throw failure; }
            if (!pending.isDone()) { return false; }
            pending.join();
            require(++ticks < 1800, "Surface frame fixture step exceeded its bounded timeout");
            boolean complete = restart ? restartTick() : createTick();
            if (complete) {
                retain("passed");
                dispose();
                AstraEngine.LOGGER.info("ASTRA_SURFACE_FRAMES_PASSED restart={} forward={} reverse={} cameraMax={} hostMax={}",
                        restart, outwardCrossings, returnCrossings, maxCameraDelta, maxHostDelta);
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
            case 0 -> { server(this::prepareWalkway); next(); }
            case 1 -> {
                if (!settled() || ticks < 80) { return false; }
                require(observed.snapshot().tile().face() == CubeFace.POSITIVE_Y,
                        "Walkway start is not on the py side of the logical seam");
                require(Math.abs(game.player.getZ() + 8.5) < 1e-6, "Initial staging did not reach its exact start");
                tracking = true;
                game.getDebugOverlay().toggleOverlay();
                next();
            }
            case 2 -> {
                if (ticks < 10 || clearFrames < 8) { return false; }
                shot("01-py-start-f3");
                game.getDebugOverlay().toggleOverlay();
                hold(GLFW.GLFW_KEY_W, true);
                next();
            }
            case 3 -> {
                if (observed.position().z < 2 || game.player.getZ() < 2 || clearFrames < 8) { return false; }
                require(observed.snapshot().tile().face() == CubeFace.POSITIVE_X && outwardCrossings >= 1,
                        "Actual forward walking did not produce a py to px frame event");
                shot("02-manual-forward-crossing");
                next();
            }
            case 4 -> {
                if (ticks < 100) { return false; }
                collisionZ = observed.position().z;
                require(collisionZ > 7.69 && collisionZ < 7.701 && game.player.horizontalCollision,
                        "Forward input did not stop at the real wall collision");
                hold(GLFW.GLFW_KEY_W, false);
                shot("03-real-wall-collision");
                next();
            }
            case 5 -> {
                if (ticks < 20) { return false; }
                require(Math.abs(observed.position().z - collisionZ) < 1e-7,
                        "Player penetrated the obstacle after forward input ended");
                hold(GLFW.GLFW_KEY_S, true);
                next();
            }
            case 6 -> {
                if (game.player.getZ() > -6 || observed.position().z > -6) { return false; }
                hold(GLFW.GLFW_KEY_S, false);
                next();
            }
            case 7 -> {
                if (ticks < 60 || !settled()) { return false; }
                require(returnCrossings >= 1 && observed.snapshot().tile().face() == CubeFace.POSITIVE_Y,
                        "Actual reverse walking did not produce a px to py frame event");
                game.getDebugOverlay().toggleOverlay();
                server(server -> { verifyWorld(server); checkpoint = properties(player(server)); });
                next();
            }
            case 8 -> {
                if (ticks < 10 || clearFrames < 8) { return false; }
                shot("04-manual-return-f3");
                game.getDebugOverlay().toggleOverlay();
                StringWriter text = new StringWriter();
                checkpoint.store(text, "Host-owned pose, real blocks and read-only surface frame after actual walking");
                writeNew(checkpointPath(), text.toString());
                pending = game.reloadResourcePacks();
                next();
            }
            case 9 -> {
                if (ticks < 40 || !settled()) { return false; }
                server(server -> { verifyWorld(server); verifyCheckpoint(player(server)); });
                next();
            }
            case 10 -> {
                if (clearFrames < 8) { return false; }
                shot("05-reload-preserved-pose");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected surface frame create step " + step);
        }
        return false;
    }

    private boolean restartTick() throws Exception {
        switch (step) {
            case 0 -> {
                checkpoint.load(new StringReader(Files.readString(checkpointPath())));
                server(this::verifyWorld);
                next();
            }
            case 1 -> {
                if (ticks < 80 || !settled()) { return false; }
                server(server -> verifyCheckpoint(player(server)));
                tracking = true;
                game.getDebugOverlay().toggleOverlay();
                next();
            }
            case 2 -> {
                if (ticks < 15 || clearFrames < 8) { return false; }
                shot("01-exact-saved-pose-f3");
                game.getDebugOverlay().toggleOverlay();
                pending = game.reloadResourcePacks();
                next();
            }
            case 3 -> {
                if (ticks < 40 || !settled()) { return false; }
                server(server -> { verifyWorld(server); verifyCheckpoint(player(server)); });
                next();
            }
            case 4 -> {
                if (clearFrames < 8) { return false; }
                shot("02-restart-reload-preserved");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected surface frame restart step " + step);
        }
        return false;
    }

    private void prepareWalkway(MinecraftServer server) {
        var level = server.getLevel(PlanetaryTerrainWorld.DIMENSION);
        require(level != null, "Highlands world is missing");
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.overworld().setDayTime(6000);
        server.overworld().setWeatherParameters(100000, 0, false, false);
        // Test construction and the initial teleport are staging. Every later displacement comes from host input.
        for (int x = -3; x <= 3; x++) {
            for (int z = -14; z <= 14; z++) {
                level.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z),
                        (z == 0 ? Blocks.GOLD_BLOCK : Blocks.STONE_BRICKS).defaultBlockState());
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z),
                            (z == 8 ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(NORTH_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(SOUTH_MARKER, Blocks.EMERALD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
        require(level.getBlockEntity(CHEST) instanceof ChestBlockEntity, "Walkway chest has no block entity");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 7));
        chest.setItem(19, new ItemStack(Items.GOLD_INGOT, 11));
        chest.setChanged();
        ServerPlayer player = player(server);
        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(level, 0.5, FLOOR_Y + 1, -8.5, 0, 18);
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        probeIndependentObservers(server);
    }

    private void probeIndependentObservers(MinecraftServer server) {
        var level = server.getLevel(PlanetaryTerrainWorld.DIMENSION);
        var manifest = PlanetaryGeographyState.get(server);
        CompoundTag beforeManifest = manifest.save(new CompoundTag(), server.registryAccess());
        var beforeWorlds = new HashSet<>(server.levelKeys());
        int beforePlayers = server.getPlayerList().getPlayers().size();
        UUID firstId = UUID.fromString("f3a00000-0000-4000-8000-000000000001");
        UUID secondId = UUID.fromString("f3a00000-0000-4000-8000-000000000002");
        ServerPlayer first = new ServerPlayer(server, level, new GameProfile(firstId, "FrameProbeOne"),
                ClientInformation.createDefault());
        ServerPlayer second = new ServerPlayer(server, level, new GameProfile(secondId, "FrameProbeTwo"),
                ClientInformation.createDefault());
        first.moveTo(0.5, FLOOR_Y + 1, -4, 0, 18);
        second.moveTo(1.5, FLOOR_Y + 1, -6, 0, 18);
        var events = new ArrayList<SurfaceFrameChangedEvent>();
        Consumer<SurfaceFrameChangedEvent> listener = event -> {
            if (event.player().getUUID().equals(firstId) || event.player().getUUID().equals(secondId)) {
                events.add(event);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, listener);
        SurfaceFrameTracker tracker = new SurfaceFrameTracker(server);
        try {
            tracker.observe(first);
            tracker.observe(second);
            require(events.size() == 2 && events.stream().allMatch(event -> event.previous().isEmpty()
                            && event.current().isPresent()), "Independent observation contexts did not enter once each");
            first.moveTo(0.5, FLOOR_Y + 1, -3, 0, 18);
            tracker.observe(first);
            SurfaceFrameSnapshot latest = AstraGeography.snapshot(first).orElseThrow();
            require(events.size() == 2, "Movement within one tile replayed a frame event");
            first.moveTo(0.5, FLOOR_Y + 1, 1, 0, 18);
            tracker.observe(first);
            tracker.observe(second);
            require(events.size() == 3 && events.get(2).player() == first
                            && events.get(2).previous().orElseThrow().equals(latest)
                            && events.get(2).current().orElseThrow().tile().face() == CubeFace.POSITIVE_X,
                    "Crossing one mock observer altered the other context or retained an outdated previous pose");
            tracker.forget(first);
            tracker.forget(first);
            require(events.size() == 4 && events.get(3).current().isEmpty(), "Forgetting context replayed its leave event");
            tracker.observe(first);
            require(events.size() == 5 && events.get(4).previous().isEmpty(), "Reentry reused an old observation context");
            second.setHealth(0);
            tracker.observe(second);
            require(events.size() == 6 && events.get(5).current().isEmpty()
                            && AstraGeography.snapshot(second).isEmpty(), "Dead mock player retained a live frame");
            require(CompletableFuture.supplyAsync(() -> {
                try { AstraGeography.snapshot(first); return false; }
                catch (IllegalStateException expected) { return true; }
            }).join(), "Worker thread sampled a live host geography context");
            tracker.close();
            boolean rejected = false;
            try { tracker.observe(first); }
            catch (IllegalStateException expected) { rejected = true; }
            require(rejected, "Closed observation owner remained usable");
            SurfaceFrameTracker reopened = new SurfaceFrameTracker(server);
            try {
                reopened.observe(first);
                require(events.size() == 7 && events.get(6).previous().isEmpty(),
                        "Fresh server observation owner replayed previous context");
                ServerPlayer replacement = new ServerPlayer(server, level, new GameProfile(firstId, "FrameProbeOne"),
                        ClientInformation.createDefault());
                // The pinned host respawn path reuses the numeric entity ID as well as the player's UUID.
                replacement.setId(first.getId());
                replacement.moveTo(first.getX(), first.getY(), first.getZ(), first.getYRot(), first.getXRot());
                reopened.observe(replacement);
                require(events.size() == 9 && events.get(7).current().isEmpty()
                                && events.get(8).previous().isEmpty(), "Replacement host entity reused stale identity context");
                reopened.forget(first);
                reopened.observe(replacement);
                require(events.size() == 9,
                        "A delayed leave callback from the old entity removed the replacement player's live frame");
            } finally { reopened.close(); }
            require(beforeManifest.equals(manifest.save(new CompoundTag(), server.registryAccess()))
                            && beforeWorlds.equals(server.levelKeys())
                            && beforePlayers == server.getPlayerList().getPlayers().size()
                            && !level.getPlayers(player -> true).contains(first)
                            && !level.getPlayers(player -> true).contains(second),
                    "Read-only mock observation changed permanent geography, loaded worlds or connected players");
            observerProbePassed = true;
        } finally {
            tracker.close();
            NeoForge.EVENT_BUS.unregister(listener);
        }
    }

    private void verifyWorld(MinecraftServer server) {
        var level = server.getLevel(PlanetaryTerrainWorld.DIMENSION);
        require(level != null && player(server).serverLevel() == level,
                "Saved player no longer inhabits the permanent highlands world");
        require(level.getBlockState(NORTH_MARKER).is(Blocks.DIAMOND_BLOCK)
                        && level.getBlockState(SOUTH_MARKER).is(Blocks.EMERALD_BLOCK)
                        && level.getBlockState(new BlockPos(0, FLOOR_Y + 1, 8)).is(Blocks.STONE_BRICKS),
                "Cross-seam markers or real collision wall were lost");
        require(level.getBlockEntity(CHEST) instanceof ChestBlockEntity, "Saved chest block entity was lost");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            require(slot == 0 ? stack.is(Items.AMETHYST_SHARD) && stack.getCount() == 7
                            : slot == 19 ? stack.is(Items.GOLD_INGOT) && stack.getCount() == 11 : stack.isEmpty(),
                    "Saved chest inventory changed at slot " + slot);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        try {
            if (event.getServer() != game.getSingleplayerServer()
                    || event.getServer().getPlayerList().getPlayers().isEmpty()) { return; }
            ServerPlayer player = player(event.getServer());
            if (!player.serverLevel().dimension().equals(PlanetaryTerrainWorld.DIMENSION)) { return; }
            SurfaceFrameSnapshot snapshot = AstraGeography.snapshot(player).orElseThrow(
                    () -> new IllegalStateException("Supported live player has no geography snapshot"));
            Vec3 position = player.position();
            SpaceVector feet = vector(position);
            require(snapshot.pose().bodyPositionMeters().distance(PlanetaryTerrain.PATCH.toBody(feet)) < 1e-7,
                    "Read-only body position differs from the actual host feet position");
            var restored = snapshot.localPose().toBody(snapshot.frame());
            require(restored.bodyPositionMeters().distance(snapshot.pose().bodyPositionMeters()) < 1e-7
                            && restored.bodyVelocityMetersPerTick().distance(snapshot.pose().bodyVelocityMetersPerTick()) < 1e-10
                            && restored.bodyOrientation().forward().distance(snapshot.pose().bodyOrientation().forward()) < 1e-10
                            && restored.bodyOrientation().up().distance(snapshot.pose().bodyOrientation().up()) < 1e-10,
                    "Live tangent frame did not retain complete body pose");
            double displacement = previousHost == null ? 0 : previousHost.distanceTo(position);
            if (tracking) {
                require(displacement <= 0.8, "Logical frame crossing displaced the host player");
                require(position.z < 7.701 && Math.abs(position.y - (FLOOR_Y + 1)) < 1e-6,
                        "Manual traversal tunneled through the wall or walkway");
                require(Math.abs(player.getYRot()) < 1e-6 && Math.abs(player.getXRot() - 18) < 1e-6,
                        "Logical frame observation changed the host orientation");
                maxHostDelta = Math.max(maxHostDelta, displacement);
            }
            previousHost = position;
            observed = new Observation(position, snapshot);
            serverSamples.add(event.getServer().getTickCount() + "\t" + step + "\t" + position.x + "\t" + position.y
                    + "\t" + position.z + "\t" + snapshot.tile().key() + "\t" + snapshot.pose() + "\t" + displacement);
        } catch (RuntimeException failure) {
            observationFailure.compareAndSet(null, failure);
        }
    }

    private void onGeography(SurfaceFrameChangedEvent event) {
        try {
            if (event.player().getServer() != game.getSingleplayerServer()) { return; }
            frameEvents.add(event.player().getServer().getTickCount() + "\t" + step + "\t"
                    + event.player().getUUID() + "\t"
                    + (event.player() == player(event.player().getServer()) ? "connected" : "mock") + "\t"
                    + event.previous().map(value -> value.tile().key()).orElse("absent") + "\t"
                    + event.current().map(value -> value.tile().key()).orElse("absent"));
            if (!tracking || event.previous().isEmpty() || event.current().isEmpty()) { return; }
            var before = event.previous().orElseThrow();
            var after = event.current().orElseThrow();
            require(before.pose().bodyPositionMeters().distance(after.pose().bodyPositionMeters()) < 1,
                    "Frame event previous pose was not the last continuous host observation");
            if (before.tile().face() == CubeFace.POSITIVE_Y && after.tile().face() == CubeFace.POSITIVE_X) {
                outwardCrossings++;
            } else if (before.tile().face() == CubeFace.POSITIVE_X && after.tile().face() == CubeFace.POSITIVE_Y) {
                returnCrossings++;
            }
        } catch (RuntimeException failure) {
            observationFailure.compareAndSet(null, failure);
        }
    }

    private void onFrame(RenderFrameEvent.Post event) {
        try {
            if (game.level == null || game.screen != null || game.getOverlay() != null
                    || !game.level.dimension().equals(PlanetaryTerrainWorld.DIMENSION)) {
                clearFrames = 0;
                previousCamera = null;
                return;
            }
            clearFrames++;
            if (!tracking) { return; }
            var camera = game.gameRenderer.getMainCamera();
            Vec3 position = camera.getPosition();
            double distance = previousCamera == null ? 0 : previousCamera.distanceTo(position);
            require(distance <= 1.5, "Camera jumped while crossing a logical geographic seam");
            if (previousCamera != null) {
                require(Math.abs(camera.getYRot() - previousYaw) < 0.01
                                && Math.abs(camera.getXRot() - previousPitch) < 0.01,
                        "Camera orientation jumped while the mouse was stationary");
            }
            maxCameraDelta = Math.max(maxCameraDelta, distance);
            cameraSamples.append(++frameCount).append('\t').append(step).append('\t').append(position.x)
                    .append('\t').append(position.y).append('\t').append(position.z).append('\t')
                    .append(camera.getYRot()).append('\t').append(camera.getXRot()).append('\t').append(distance).append('\n');
            previousCamera = position;
            previousYaw = camera.getYRot();
            previousPitch = camera.getXRot();
        } catch (RuntimeException failure) {
            observationFailure.compareAndSet(null, failure);
        }
    }

    private Properties properties(ServerPlayer player) {
        SurfaceFrameSnapshot snapshot = AstraGeography.snapshot(player).orElseThrow();
        Properties values = new Properties();
        values.setProperty("dimension", player.serverLevel().dimension().location().toString());
        values.setProperty("x", Double.toHexString(player.getX()));
        values.setProperty("y", Double.toHexString(player.getY()));
        values.setProperty("z", Double.toHexString(player.getZ()));
        values.setProperty("yaw", Float.toHexString(player.getYRot()));
        values.setProperty("pitch", Float.toHexString(player.getXRot()));
        values.setProperty("geography", snapshot.geographyId().toString());
        values.setProperty("tile", snapshot.tile().key());
        values.setProperty("body_position", snapshot.pose().bodyPositionMeters().toString());
        values.setProperty("body_orientation", snapshot.pose().bodyOrientation().toString());
        values.setProperty("frame", snapshot.frame().toString());
        return values;
    }

    private void verifyCheckpoint(ServerPlayer player) {
        require(properties(player).equals(checkpoint), "Reload/restart changed exact host pose, tile identity or derived frame");
    }

    private boolean settled() {
        return observed != null && game.level.dimension().equals(PlanetaryTerrainWorld.DIMENSION)
                && clearFrames >= 8 && Math.abs(game.player.getY() - (FLOOR_Y + 1)) < 1e-6;
    }

    private void shot(String name) throws Exception {
        Path file = game.gameDirectory.toPath().resolve("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to overwrite a retained surface frame screenshot: " + file);
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after surface frame capture");
    }

    private void retain(String result) throws Exception {
        if (retained) { return; }
        retained = true;
        Path directory = game.gameDirectory.toPath().resolve("evidence");
        writeNew(directory.resolve(phase + "-camera.tsv"), cameraSamples.toString());
        writeNew(directory.resolve(phase + "-server.tsv"), "tick\tstep\tx\ty\tz\ttile\tbody_pose\tdistance\n"
                + String.join("\n", serverSamples) + "\n");
        writeNew(directory.resolve(phase + "-events.tsv"), "tick\tstep\tplayer\tobserver\tprevious\tcurrent\n"
                + String.join("\n", frameEvents) + "\n");
        writeNew(directory.resolve(phase + "-scope.txt"),
                "Initial test staging creates one high-altitude walkway and teleports once. Subsequent movement uses actual W/S input.\n"
                        + "Minecraft already owns ordinary walking, collision, blocks and player persistence across this logical seam.\n"
                        + "New engine behavior is read-only live spherical poses/frame events; this is not world stitching or whole-globe walking.\n"
                        + "Markers, wall, complete chest inventory, exact host pose and derived frame are checked across reload/restart.\n"
                        + "Independent-observer probe uses two unconnected host ServerPlayer objects, not two network clients.\n"
                        + "independent_observer_probe=" + (restart ? "create-phase-only" : observerProbePassed)
                        + "\n"
                        + "forward_face_events=" + outwardCrossings + "\nreverse_face_events=" + returnCrossings
                        + "\ncamera_frames=" + frameCount + "\nmax_camera_frame_distance=" + maxCameraDelta
                        + "\nmax_host_tick_distance=" + maxHostDelta + "\nresult=" + result + "\n");
    }

    private void dispose() {
        hold(GLFW.GLFW_KEY_W, false);
        hold(GLFW.GLFW_KEY_S, false);
        tracking = false;
        NeoForge.EVENT_BUS.unregister(frameListener);
        NeoForge.EVENT_BUS.unregister(serverListener);
        NeoForge.EVENT_BUS.unregister(geographyListener);
    }

    private static void writeNew(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private static SpaceVector vector(Vec3 value) { return new SpaceVector(value.x, value.y, value.z); }
    private Path checkpointPath() { return game.gameDirectory.toPath().resolve("surface-frames-checkpoint.properties"); }
    private void hold(int key, boolean down) { KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(key), down); }
    private void server(Consumer<MinecraftServer> action) {
        MinecraftServer server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private void next() {
        AstraEngine.LOGGER.info("ASTRA_SURFACE_FRAMES_STEP step={} complete restart={}", step, restart);
        step++;
        ticks = 0;
    }
    private void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message + " (surface frames step " + step + ", ticks " + ticks + ")"); }
    }
    private record Observation(Vec3 position, SurfaceFrameSnapshot snapshot) {}
}
