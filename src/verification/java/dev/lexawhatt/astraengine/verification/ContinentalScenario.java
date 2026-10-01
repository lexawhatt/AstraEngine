package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.client.surface.SurfaceDebugCoordinates;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.ContinentalWorlds;
import dev.lexawhatt.astraengine.surface.ContinentalRegion;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Three physical-altitude voxel windows, native F3, saved edits and independent-process codec/pose retention. */
final class ContinentalScenario {
    private static final ContinentalTerrain FIELD = new ContinentalTerrain(ContinentalTerrain.VERSION, ContinentalTerrain.SEED);
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final boolean restart;
    private final Consumer<RenderFrameEvent.Post> frames = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { this.clearFrames++; }
        else { this.clearFrames = 0; }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int step;
    private int ticks;
    private int clearFrames;
    private int regionIndex;

    ContinentalScenario(String phase) {
        this.phase = phase;
        restart = phase.equals("continental-restart");
        game.options.renderDistance().set(4);
        game.options.bobView().set(false);
        game.options.autoJump().set(false);
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        switch (step) {
            case 0 -> {
                server(server -> {
                    ContinentalWorlds.validate(server);
                    if (restart) {
                        require(read("continental-pose.txt").equals(pose(player(server))), "Retained player pose changed");
                    }
                    server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    server.overworld().getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                    server.overworld().setDayTime(6000);
                    server.overworld().setWeatherParameters(100000, 0, false, false);
                    for (ContinentalRegion region : ContinentalRegion.values()) {
                        var level = server.getLevel(dimension(region));
                        require(level != null && level.getMinBuildHeight() == -2032 && level.getMaxBuildHeight() == 2032,
                                "Continental dimension or its vertical window is missing: " + region);
                        String definition = ChunkGenerator.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,
                                server.registryAccess()), level.getChunkSource().getGenerator()).getOrThrow().toString();
                        String name = "continental-" + region.id() + "-definition.json";
                        if (restart) { require(read(name).equals(definition), "Saved generator identity changed: " + region); }
                        else { write(name, definition); }
                        level.getChunkAt(marker(region));
                        if (!restart) {
                            level.setBlockAndUpdate(marker(region), Blocks.DIAMOND_BLOCK.defaultBlockState());
                            level.setBlockAndUpdate(marker(region).above(), Blocks.CHEST.defaultBlockState());
                            var chest = (ChestBlockEntity) level.getBlockEntity(marker(region).above());
                            require(chest != null, "Fixture chest is missing");
                            chest.clearContent();
                            chest.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 7));
                            chest.setItem(19, new ItemStack(Items.GOLD_INGOT, 11));
                            chest.setChanged();
                        }
                        verifyWorld(server, region);
                    }
                    stage(server, region());
                });
                next();
            }
            case 1 -> {
                if (!settled(100)) { return false; }
                require(game.level.getBlockState(marker(region())).is(Blocks.DIAMOND_BLOCK), "Marker not synchronized");
                var coordinates = SurfaceDebugCoordinates.fromFeet(region().patch(), new SpaceVector(game.player.getX(),
                        game.player.getY() + region().altitudeOriginMeters(), game.player.getZ()));
                require(Math.abs(coordinates.altitudeMeters() - (game.player.getY() + region().altitudeOriginMeters())) < 1e-7,
                        "F3 physical altitude differs from the regional window");
                write("evidence/" + phase + "-" + region().id() + "-coordinates.txt", String.format(Locale.ROOT,
                        "longitude=%s\nlatitude=%s\naltitude=%s\nhost_y=%.6f\norigin_m=%d\n",
                        coordinates.longitudeText(), coordinates.latitudeText(), coordinates.altitudeText(),
                        game.player.getY(), region().altitudeOriginMeters()));
                if (!game.getDebugOverlay().showDebugScreen()) { game.getDebugOverlay().toggleOverlay(); }
                next();
            }
            case 2 -> {
                if (!settled(20)) { return false; }
                shot(region().id() + "-geographic-f3");
                game.getDebugOverlay().toggleOverlay();
                game.options.hideGui = true;
                next();
            }
            case 3 -> {
                if (!settled(30)) { return false; }
                shot(region().id() + "-landscape");
                game.options.hideGui = false;
                if (++regionIndex < ContinentalRegion.values().length) {
                    server(server -> stage(server, region()));
                    step = 1; ticks = 0; clearFrames = 0;
                } else {
                    pending = game.reloadResourcePacks();
                    next();
                }
            }
            case 4 -> {
                if (clearFrames < 8 || ticks < 30) { return false; }
                shot("after-reload");
                server(server -> {
                    for (ContinentalRegion region : ContinentalRegion.values()) { verifyWorld(server, region); }
                    if (!restart) { write("continental-pose.txt", pose(player(server))); }
                    else { write("evidence/continental-restart-final-pose.txt", pose(player(server))); }
                });
                next();
            }
            case 5 -> {
                NeoForge.EVENT_BUS.unregister(frames);
                write("evidence/" + phase + "-scope.txt", "Three fixed windows of a shared procedural field.\n"
                        + "Physical altitude = host Y + stored origin. No relief compression or removal of host height limits.\n"
                        + (restart ? "All three generator definitions, marker blocks and all chest slots compared after process restart.\n"
                                : "All three generator definitions, marker blocks, chest inventories and player pose retained for restart comparison.\n")
                        + "Native F3 and resource reload observed. Abyss uses fixture-only night vision for seabed inspection.\n"
                        + "No automatic band transitions, curved chunk geometry or planet-to-orbit stitching is claimed.\n");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected continental fixture step " + step);
        }
        return false;
    }

    private void stage(MinecraftServer server, ContinentalRegion region) {
        var player = player(server);
        player.setGameMode(GameType.CREATIVE);
        if (region == ContinentalRegion.COAST) {
            player.teleportTo(server.getLevel(dimension(region)), 78.716034, 12, 63.012960, -51.298231f, 22);
        } else if (region == ContinentalRegion.ABYSS) {
            player.teleportTo(server.getLevel(dimension(region)), 0.5, marker(region).getY() + 3, 8.5, 180, 28);
        } else {
            player.teleportTo(server.getLevel(dimension(region)), 0.5, marker(region).getY() + 35, 40.5, 180, 24);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        if (region == ContinentalRegion.ABYSS) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 24000, 0, false, false));
        } else { player.removeEffect(MobEffects.NIGHT_VISION); }
    }

    private static void verifyWorld(MinecraftServer server, ContinentalRegion region) {
        var level = server.getLevel(dimension(region));
        require(level != null && level.getBlockState(marker(region)).is(Blocks.DIAMOND_BLOCK), "Retained marker missing: " + region);
        require(level.getBlockEntity(marker(region).above()) instanceof ChestBlockEntity, "Retained chest missing: " + region);
        var chest = (ChestBlockEntity) level.getBlockEntity(marker(region).above());
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            require(slot == 0 ? stack.is(Items.AMETHYST_SHARD) && stack.getCount() == 7
                    : slot == 19 ? stack.is(Items.GOLD_INGOT) && stack.getCount() == 11 : stack.isEmpty(),
                    "Retained chest inventory changed: " + region + " slot=" + slot);
        }
    }

    private ContinentalRegion region() { return ContinentalRegion.values()[regionIndex]; }
    private static BlockPos marker(ContinentalRegion region) {
        int x = region == ContinentalRegion.COAST ? 96 : 0;
        int z = region == ContinentalRegion.COAST ? 80 : 0;
        return new BlockPos(x, region.firstAir(FIELD, x, z), z);
    }
    private static ResourceKey<Level> dimension(ContinentalRegion region) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(region.dimensionId()));
    }
    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private static String pose(ServerPlayer player) {
        return player.serverLevel().dimension().location() + "\n" + Double.toHexString(player.getX()) + "\n"
                + Double.toHexString(player.getY()) + "\n" + Double.toHexString(player.getZ()) + "\n"
                + Float.toHexString(player.getYRot()) + "\n" + Float.toHexString(player.getXRot());
    }
    private boolean settled(int minimumTicks) {
        return game.level != null && game.level.dimension().equals(dimension(region())) && clearFrames >= 8 && ticks >= minimumTicks;
    }
    private void next() { step++; ticks = 0; clearFrames = 0; }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private Path path(String name) { return game.gameDirectory.toPath().resolve(name); }
    private String read(String name) {
        try { return Files.readString(path(name)); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read continental evidence", failure); }
    }
    private void write(String name, String text) {
        try {
            Files.createDirectories(path(name).getParent());
            Files.writeString(path(name), text, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot retain continental evidence", failure); }
    }
    private void shot(String name) throws Exception {
        var file = path("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to replace retained continental capture " + file);
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after continental capture");
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
