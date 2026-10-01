package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** New-preset native creation and independent-process reload, including actual decoration and geographic F3. */
final class EarthGenerationScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final boolean restart;
    private List<String> debugRows = List.of();
    private final Consumer<CustomizeGuiOverlayEvent.DebugText> debug = event -> debugRows = List.copyOf(event.getLeft());
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private int step;
    private int ticks;

    EarthGenerationScenario(String phase) {
        this.phase = phase;
        restart = phase.endsWith("-restart");
        game.options.bobView().set(false);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, debug);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        switch (step) {
            case 0 -> {
                server(server -> {
                    EarthWorlds.validate(server);
                    require(EarthWorlds.active(server), "New world did not use the Earth preset");
                    var level = server.overworld();
                    var player = player(server);
                    require(player.level().dimension().equals(Level.OVERWORLD), "Earth creation did not spawn in Overworld");
                    var generator = (EarthChunkGenerator) level.getChunkSource().getGenerator();
                    require(AstraGeography.planetaryReference(level).orElseThrow().geographyId().equals(generator.chart().geographyId()),
                            "Earth Overworld is missing its authoritative geography binding");
                    var chart = generator.chart();
                    int ground = (int) Math.floor(generator.terrain().sample(chart.normal(8.5, 8.5)).heightMeters());
                    var marker = new BlockPos(8, ground + 3, 8);
                    if (!restart) {
                        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                        level.setDayTime(6000);
                        level.setWeatherParameters(100000, 0, false, false);
                        level.setBlockAndUpdate(marker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                        level.setBlockAndUpdate(marker.above(), Blocks.CHEST.defaultBlockState());
                        var chest = (ChestBlockEntity) level.getBlockEntity(marker.above());
                        require(chest != null, "Earth fixture chest did not create its host block entity");
                        chest.setItem(5, new ItemStack(Items.AMETHYST_SHARD, 17));
                        chest.setChanged();
                        player.teleportTo(level, 8.5, ground + 45, 8.5, 36, 22);
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                    } else {
                        require(read("earth-generation-checkpoint.properties").equals(pose(player)),
                                "Earth reload changed the saved dimension or player pose");
                    }
                    require(level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "Earth lost the saved block edit");
                    var chest = (ChestBlockEntity) level.getBlockEntity(marker.above());
                    require(chest != null && chest.getItem(5).is(Items.AMETHYST_SHARD) && chest.getItem(5).getCount() == 17,
                            "Earth lost persistent block entity inventory");
                    int foliage = 0;
                    int snow = 0;
                    for (int z = -24; z < 24; z += 2) {
                        for (int x = -24; x < 24; x += 2) {
                            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            if (level.getBlockState(new BlockPos(x, top - 1, z)).is(BlockTags.LEAVES)) { foliage++; }
                            if (level.getBlockState(new BlockPos(x, top - 1, z)).is(Blocks.SNOW)) { snow++; }
                        }
                    }
                    require(snow == 0, "Tropical Earth canopy was frozen by host Y: " + snow);
                    require(foliage > 12, "Earth has no native biome tree canopy near spawn: " + foliage);
                    var observed = AstraGeography.snapshot(player).orElseThrow();
                    var expected = chart.geographic(new SpaceVector(player.getX(), player.getY(), player.getZ()));
                    require(Math.abs(observed.geographicPosition().altitudeMeters() - expected.altitudeMeters()) < 1e-8,
                            "Earth snapshot altitude diverges from host storage");
                    write("evidence/" + phase + "-server.txt", "canopy_samples=" + foliage + "\npose=" + pose(player)
                            + "\ngeographic=" + expected + "\n");
                });
                next();
            }
            case 1 -> {
                if (ticks < 100 || game.screen != null || game.getOverlay() != null) { return false; }
                shot("terrain");
                if (!game.getDebugOverlay().showDebugScreen()) { game.getDebugOverlay().toggleOverlay(); }
                next();
            }
            case 2 -> {
                if (ticks < 30 || debugRows.isEmpty()) { return false; }
                require(debugRows.stream().anyMatch(row -> row.startsWith("Longitude: "))
                                && debugRows.stream().anyMatch(row -> row.startsWith("Latitude: "))
                                && debugRows.stream().anyMatch(row -> row.startsWith("Altitude: "))
                                && debugRows.stream().noneMatch(row -> row.startsWith("XYZ: ")),
                        "Earth F3 does not display its server-bound geographic coordinates: " + debugRows);
                write("evidence/" + phase + "-f3.txt", String.join("\n", debugRows));
                shot("geographic-f3");
                game.getDebugOverlay().toggleOverlay();
                server(server -> {
                    if (!restart) { write("earth-generation-checkpoint.properties", pose(player(server))); }
                    server.saveEverything(false, true, true);
                });
                next();
            }
            case 3 -> {
                NeoForge.EVENT_BUS.unregister(debug);
                return true;
            }
            default -> throw new IllegalStateException("Unknown Earth verification step");
        }
        return false;
    }

    private void next() { step++; ticks = 0; }
    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static ServerPlayer player(MinecraftServer server) { return server.getPlayerList().getPlayers().getFirst(); }
    private static String pose(ServerPlayer player) {
        return player.level().dimension().location() + "\n" + Double.toHexString(player.getX()) + "\n"
                + Double.toHexString(player.getY()) + "\n" + Double.toHexString(player.getZ()) + "\n"
                + Float.toHexString(player.getYRot()) + "\n" + Float.toHexString(player.getXRot());
    }
    private Path path(String name) { return game.gameDirectory.toPath().resolve(name); }
    private String read(String name) {
        try { return Files.readString(path(name)); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Cannot read Earth fixture evidence", exception); }
    }
    private void write(String name, String value) {
        try {
            Files.createDirectories(path(name).getParent());
            Files.writeString(path(name), value, StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot retain Earth fixture evidence", exception); }
    }
    private void shot(String name) throws Exception {
        var file = path("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to replace Earth screenshot evidence");
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error in Earth fixture");
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
