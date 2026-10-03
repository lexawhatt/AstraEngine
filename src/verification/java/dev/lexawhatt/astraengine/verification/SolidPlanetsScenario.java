package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.server.PlanetSurfaceBindings;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.opengl.GL11;

/** Real independently stored lunar/icy terrain, chest edits, departure/return and process-restart fixture. */
final class SolidPlanetsScenario {
    private static final TicketType<String> TICKET = TicketType.create("astra_verify_planet", String::compareTo);
    private final Minecraft game = Minecraft.getInstance();
    private final boolean restart;
    private final List<PlanetChart> charts = List.of(SolidPlanetGameTests.chart("moon"), SolidPlanetGameTests.chart("europa"));
    private final StringBuilder evidence = new StringBuilder("A4 permanent solid-planet storage and presentation\n");
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> chunks;
    private int step;
    private int frames;
    private int view;

    SolidPlanetsScenario(String phase) { restart = phase.endsWith("-restart"); }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        var server = game.getSingleplayerServer();
        if (step == 0) {
            game.options.hideGui = false;
            pending = CompletableFuture.runAsync(() -> {
                var futures = new ArrayList<CompletableFuture<?>>();
                for (var chart : charts) {
                    if (restart) {
                        require(server.getLevel(PlanetSurfaceWorlds.dimension(chart)) != null,
                                "Persistent chart was not restored before login: " + chart.profile().bodyId());
                    }
                    var level = PlanetSurfaceWorlds.ensure(server, chart);
                    require(PlanetSurfaceWorlds.getCube(level).orElseThrow().equals(chart), "Loaded chart ownership disagrees");
                    for (int z = -1; z <= 1; z++) {
                        for (int x = -1; x <= 1; x++) {
                            level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 1, chart.dimensionId());
                            futures.add(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true));
                        }
                    }
                }
                chunks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            }, server);
            step++; return false;
        }
        if (step == 1) {
            if (chunks == null || !chunks.isDone()) { return false; }
            chunks.join();
            pending = CompletableFuture.runAsync(() -> {
                for (int index = 0; index < charts.size(); index++) {
                    var chart = charts.get(index);
                    var level = PlanetSurfaceWorlds.level(server, chart).orElseThrow();
                    var position = landmark(level);
                    if (!restart) {
                        level.setBlock(position.below(), (index == 0 ? Blocks.RED_CONCRETE : Blocks.BLUE_CONCRETE).defaultBlockState(), 3);
                        level.setBlock(position, Blocks.CHEST.defaultBlockState(), 3);
                        var chest = (ChestBlockEntity) level.getBlockEntity(position);
                        require(chest != null, "Host did not create a chest block entity");
                        chest.setItem(0, new ItemStack(Items.DIAMOND, index + 3)); chest.setChanged();
                    }
                    verify(level, index);
                    evidence.append(chart.profile().bodyId()).append(" dimension=").append(chart.dimensionId())
                            .append(" radius=").append(chart.radiusMeters()).append(" altitude=")
                            .append(position.getY() + chart.altitudeOriginMeters()).append(" chestItems=")
                            .append(index + 3).append(" restart=").append(restart).append('\n');
                }
                require(PlanetSurfaceBindings.get(server).bindings().size() >= 2, "Persistent manifest lost planet ownership");
            }, server);
            step++; return false;
        }
        if (step == 2) {
            int index = view % charts.size();
            game.options.hideGui = view >= 3;
            pending = CompletableFuture.runAsync(() -> {
                var level = PlanetSurfaceWorlds.level(server, charts.get(index)).orElseThrow();
                var player = server.getPlayerList().getPlayers().getFirst();
                var position = landmark(level);
                verify(level, index);
                player.teleportTo(level, position.getX() + .5, position.getY() + 10, position.getZ() + 15, 180, view >= 3 ? -70 : 28);
                player.getAbilities().flying = true; player.onUpdateAbilities(); player.setNoGravity(true);
            }, server);
            frames = 0; step++; return false;
        }
        if (step == 3) {
            var chart = charts.get(view % charts.size());
            if (!game.level.dimension().location().toString().equals(chart.dimensionId())) { return false; }
            if (++frames < 100) { return false; }
            require(game.level.effects() instanceof dev.lexawhatt.astraengine.client.surface.PlanetEffects,
                    "Planet surface uses the wrong client dimension effects");
            var output = game.gameDirectory.toPath().resolve("evidence"); Files.createDirectories(output);
            try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
                image.writeToFile(output.resolve((restart ? "restart-" : "create-") + view + "-" + chart.profile().bodyId() + ".png"));
                if (view >= 3) {
                    int visible = 0;
                    for (int y = 0; y < image.getHeight() / 2; y++) {
                        for (int x = image.getWidth() / 3; x < image.getWidth() * 2 / 3; x++) {
                            int pixel = image.getPixelRGBA(x, y);
                            if (Math.max(pixel & 255, Math.max(pixel >>> 8 & 255, pixel >>> 16 & 255)) > 20) { visible++; }
                        }
                    }
                    require(visible > 8, "Generic walking sky contains no celestial radiance");
                    evidence.append("sky ").append(chart.profile().bodyId()).append(" visiblePixels=").append(visible).append('\n');
                }
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL failed while rendering a generic planet");
            if (++view < 6) { step = 2; return false; }
            Files.writeString(output.resolve((restart ? "restart" : "create") + "-planet-results.txt"), evidence,
                    StandardOpenOption.CREATE_NEW);
            if (!restart) {
                Files.writeString(game.gameDirectory.toPath().resolve("solid-planets-checkpoint.properties"),
                        "version=1\nbodies=moon,europa\nchestCounts=3,4\n", StandardOpenOption.CREATE_NEW);
            }
            pending = CompletableFuture.runAsync(() -> {
                for (var owner : charts) {
                    var level = PlanetSurfaceWorlds.level(server, owner).orElseThrow();
                    for (int z = -1; z <= 1; z++) { for (int x = -1; x <= 1; x++) {
                        level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(x, z), 1, owner.dimensionId());
                    } }
                }
                var player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(server.overworld(), 0, 100, 0, 0, 0);
            }, server);
            step++; return false;
        }
        return step == 4;
    }

    private static BlockPos landmark(ServerLevel level) {
        var generator = (PlanetChunkGenerator) level.getChunkSource().getGenerator();
        var chart = generator.chart();
        int y = (int) Math.floor(generator.terrain().sample(chart.normal(8.5, 8.5)).heightMeters()) - chart.altitudeOriginMeters();
        return new BlockPos(8, y, 8);
    }

    private static void verify(ServerLevel level, int index) {
        var position = landmark(level);
        require(level.getBlockState(position.below()).is(index == 0 ? Blocks.RED_CONCRETE : Blocks.BLUE_CONCRETE),
                "Independent planet edit disappeared or was replaced by another body");
        require(level.getBlockEntity(position) instanceof ChestBlockEntity, "Planet return lost block entity");
        var chest = (ChestBlockEntity) level.getBlockEntity(position);
        require(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == index + 3,
                "Planet return/restart changed chest inventory");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
