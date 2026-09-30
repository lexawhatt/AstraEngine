package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lexawhatt.astraengine.client.compat.RenderCompatibility;
import dev.lexawhatt.astraengine.server.PlanetaryTerrainWorld;
import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Real disposable high-altitude blocks, geographic debug display and optional DH on/off native experiment. */
final class TerrainScenario {
    private static final int X = -14840;
    private static final int Z = -3576;
    private static final BlockPos HIGH_MARKER = new BlockPos(X, 1292, Z);
    private static final BlockPos LOW_MARKER = new BlockPos(X, -240, Z);
    private final Minecraft game = Minecraft.getInstance();
    private final String phase;
    private final Consumer<RenderFrameEvent.Post> frames = event -> {
        if (game.level != null && game.screen == null && game.getOverlay() == null) { this.clearFrames++; }
        else { this.clearFrames = 0; }
    };
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private DistantTerrainProbe distant;
    private int clearFrames;
    private int ticks;
    private int step;
    private long lodStartedAt;

    TerrainScenario(String phase) {
        this.phase = phase;
        NeoForge.EVENT_BUS.addListener(frames);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        ticks++;
        switch (step) {
            case 0 -> {
                boolean withDh = phase.equals("terrain-dh");
                require(withDh == ModList.get().isLoaded("distanthorizons"), "Unexpected DH fixture mod stack");
                if (withDh) {
                    if (distant == null) { distant = new DistantTerrainProbe(); }
                    if (!distant.configure()) { return false; }
                    if (ModList.get().isLoaded("iris")) {
                        require(RenderCompatibility.diagnostics().shaderPackInUse().orElse(false),
                                "Iris terrain fixture requires an actually active shader pack");
                    }
                }
                game.options.renderDistance().set(4);
                server(server -> {
                    PlanetaryTerrainWorld.validate(server);
                    var level = server.getLevel(PlanetaryTerrainWorld.DIMENSION);
                    require(level != null, "Highlands world is missing");
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                    // Additional dimensions use DerivedLevelData: its time/weather setters are no-ops.
                    server.overworld().setDayTime(6000);
                    server.overworld().setWeatherParameters(100000, 0, false, false);
                    require(level.getDayTime() == 6000, "Fixture did not establish shared midday");
                    level.getChunkAt(HIGH_MARKER);
                    require(level.getMinBuildHeight() == -256 && level.getMaxBuildHeight() == 1792,
                            "Loaded highlands vertical range differs from profile");
                    if (phase.equals("terrain-restart")) {
                        require(level.getBlockState(HIGH_MARKER).is(Blocks.DIAMOND_BLOCK)
                                && level.getBlockState(LOW_MARKER).is(Blocks.EMERALD_BLOCK),
                                "High/negative altitude modifications did not survive process restart");
                    } else {
                        require(level.getHeight(Heightmap.Types.OCEAN_FLOOR, X, Z) == 1291,
                                "Kilometer peak differs from versioned field");
                        level.setBlockAndUpdate(HIGH_MARKER, Blocks.DIAMOND_BLOCK.defaultBlockState());
                        level.setBlockAndUpdate(LOW_MARKER, Blocks.EMERALD_BLOCK.defaultBlockState());
                    }
                    var player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(level, X + 0.5, 1350, Z + 40.5, 180, 24);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    require(player.serverLevel() == level, "Highlands teleport was not committed");
                });
                next();
            }
            case 1 -> {
                if (!game.level.dimension().equals(PlanetaryTerrainWorld.DIMENSION)
                        || clearFrames < 8 || ticks < 100
                        || !game.level.hasChunk(HIGH_MARKER.getX() >> 4, HIGH_MARKER.getZ() >> 4)) { return false; }
                require(game.level.getBlockState(HIGH_MARKER).is(Blocks.DIAMOND_BLOCK),
                        "High block not synchronized to the client");
                if (distant != null) { distant.resetCounters(); lodStartedAt = System.nanoTime(); }
                game.getDebugOverlay().toggleOverlay();
                next();
            }
            case 2 -> {
                if (ticks < 20 || clearFrames < 8) { return false; }
                shot("01-geographic-f3");
                game.getDebugOverlay().toggleOverlay();
                game.options.hideGui = true;
                next();
            }
            case 3 -> {
                if (ticks < 50 || clearFrames < 8) { return false; }
                shot("02-kilometer-peak");
                Files.writeString(path("terrain-checkpoint.properties"), "profile=1\npeak=1291\nhigh_marker="
                        + HIGH_MARKER + "\nlow_marker=" + LOW_MARKER + "\n");
                if (distant == null) { return finish(); }
                next();
            }
            case 4 -> {
                // Wait for actual LOD buffers, not just an allocated FBO or a successful mod startup.
                if (ticks % 200 == 0) { Files.writeString(path("dh-progress.txt"), distant.description()); }
                if (System.nanoTime() - lodStartedAt < 80_000_000_000L
                        || distant.bufferRenders() < 100 || clearFrames < 8) { return false; }
                Files.writeString(path("dh-observations.txt"), distant.description() + "\n" + RenderCompatibility.status());
                shot("03-dh-enabled");
                distant.renderEnabled(false);
                next();
            }
            case 5 -> {
                if (ticks < 40 || clearFrames < 8) { return false; }
                shot("04-dh-disabled");
                distant.renderEnabled(true);
                next();
            }
            case 6 -> {
                if (ticks < 40 || clearFrames < 8) { return false; }
                shot("05-dh-restored");
                return finish();
            }
            default -> throw new IllegalStateException("Unexpected terrain fixture step");
        }
        return false;
    }

    private boolean finish() {
        if (distant != null) { distant.close(); }
        game.options.hideGui = false;
        NeoForge.EVENT_BUS.unregister(frames);
        return true;
    }

    private void next() { step++; ticks = 0; clearFrames = 0; }

    private void server(Consumer<MinecraftServer> action) {
        var server = game.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> action.accept(server), server);
    }

    private Path path(String name) { return game.gameDirectory.toPath().resolve(name); }

    private void shot(String name) throws Exception {
        var file = path("evidence/" + phase + "-" + name + ".png");
        require(!Files.exists(file), "Refusing to overwrite a retained terrain capture: " + file);
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after terrain capture");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
