package dev.lexawhatt.astraengine.verification;

import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;
import dev.lexawhatt.astraengine.compat.distant.EarthLodGenerator;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Real DH override/worker/column/update checks. DH internals are used only here to inspect the real registered
 * implementation and its pooled storage, not by production integration. Never runs in an existing user world.
 */
final class EarthDistantScenario {
    private final Minecraft game = Minecraft.getInstance();
    private final DistantTerrainProbe probe = new DistantTerrainProbe();
    private final StringBuilder evidence = new StringBuilder("Direct Earth LOD / DH 3.3.3 API 7.2\n");
    private final List<Double> frameMillis = new ArrayList<>();
    private CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private EarthLodGenerator generator;
    private IDhApiLevelWrapper wrapper;
    private int step;
    private int ticks;
    private long lastFrame;
    private final Consumer<RenderLevelStageEvent> frameObserver = event -> {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) { frame(); }
    };

    EarthDistantScenario() {
        game.options.renderDistance().set(4);
        game.options.simulationDistance().set(5);
        game.options.cloudStatus().set(CloudStatus.OFF);
        game.options.hideGui = true;
        NeoForge.EVENT_BUS.addListener(frameObserver);
    }

    boolean tick() throws Exception {
        if (!pending.isDone()) { return false; }
        pending.join();
        if (!probe.configure()) { return false; }
        if (step == 0) {
            for (var candidate : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                if (candidate.getWrappedMcObject() instanceof ServerLevel level && level.dimension().equals(game.level.dimension())) {
                    wrapper = candidate;
                    break;
                }
            }
            if (wrapper == null) { return false; }
            require(wrapper.getWrappedMcObject() instanceof ServerLevel, "DH did not expose the integrated server wrapper");
            var found = WorldGeneratorInjector.INSTANCE.get(wrapper);
            require(found instanceof EarthLodGenerator, "Actual DH level is not using Astra's override: " + found);
            generator = (EarthLodGenerator) found;
            pending = CompletableFuture.runAsync(this::checkColumns);
            game.player.setYRot(120);
            game.player.setXRot(3);
            step = 1;
            return false;
        }
        if (step == 1) {
            if (++ticks < 200 || generator.metrics().requests() < 12 || probe.bufferRenders() < 20) { return false; }
            evidence.append("before reload ").append(generator.metrics()).append('\n').append(probe.description()).append('\n');
            capture("direct-earth-dh");
            probe.renderEnabled(false);
            ticks = 0;
            step = 2;
            return false;
        }
        if (++ticks < 30) { return false; }
        if (step == 2) {
            capture("direct-earth-dh-off");
            probe.renderEnabled(true);
            pending = game.reloadResourcePacks();
            step = 3;
            ticks = 0;
            return false;
        }
        require(WorldGeneratorInjector.INSTANCE.get(wrapper) == generator, "Reload replaced or duplicated the world generator");
        capture("direct-earth-dh-reloaded");
        evidence.append("after reload ").append(generator.metrics()).append('\n').append(probe.description()).append('\n');
        if (!frameMillis.isEmpty()) {
            var sorted = frameMillis.stream().sorted().toList();
            evidence.append(String.format(Locale.ROOT, "Measured render intervals n=%d mean=%.2f ms p95=%.2f ms p99=%.2f ms\n",
                    sorted.size(), sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    sorted.get((int) ((sorted.size() - 1) * .95)), sorted.get((int) ((sorted.size() - 1) * .99))));
        }
        Files.writeString(game.gameDirectory.toPath().resolve("evidence/direct-earth-dh.txt"), evidence, StandardOpenOption.CREATE_NEW);
        probe.close();
        NeoForge.EVENT_BUS.unregister(frameObserver);
        return true;
    }

    /** Called once for each real rendered frame, not client ticks. Loading/readback/reload stages are excluded. */
    void frame() {
        long now = System.nanoTime();
        if (step == 1 && lastFrame != 0) { frameMillis.add((now - lastFrame) / 1e6); }
        lastFrame = step == 1 ? now : 0;
    }

    private void checkColumns() {
        var level = (ServerLevel) wrapper.getWrappedMcObject();
        var earth = (EarthChunkGenerator) level.getChunkSource().getGenerator();
        var executor = ThreadPoolUtil.getWorldGenExecutor();
        require(executor != null, "DH worker executor unavailable");
        for (byte detail : new byte[] {0, 4, 8, 12}) {
            int sectionX = detail == 12 ? -1 : -23, sectionZ = detail == 12 ? 2 : 47;
            int startX = sectionX * (64 << detail), startZ = sectionZ * (64 << detail);
            long pos = DhSectionPos.encode((byte) (6 + detail), sectionX, sectionZ);
            try (var data = FullDataSourceV2.createEmpty(pos); var edited = FullDataSourceV2.createEmpty(pos)) {
                data.setRunApiSetterValidation(true);
                long start = System.nanoTime();
                generator.generateLod(startX / 16, startZ / 16, sectionX, sectionZ, detail, data,
                        EDhApiDistantGeneratorMode.INTERNAL_SERVER, executor, result -> require(result == data, "Replaced DH pooled data")).join();
                evidence.append("detail=").append(detail).append(" tileBlocks=").append(64 << detail)
                        .append(" queueAndGenerationMs=").append((System.nanoTime() - start) / 1e6).append('\n');
                for (int z : new int[] {0, 31, 63}) {
                    for (int x : new int[] {0, 17, 63}) {
                        int blockX = startX + (x << detail) + (1 << detail) / 2;
                        int blockZ = startZ + (z << detail) + (1 << detail) / 2;
                        var base = earth.getBaseColumn(blockX, blockZ,
                                LevelHeightAccessor.create(earth.getMinY(), earth.getGenDepth()), level.getChunkSource().randomState());
                        var points = data.getApiDataPointColumn(x, z);
                        int total = 0;
                        for (var point : points) {
                            total += point.topYBlockPos - point.bottomYBlockPos;
                            for (int y = point.bottomYBlockPos; y < point.topYBlockPos; y++) {
                                var expected = base.getBlock(y + earth.getMinY());
                                require(point.blockStateWrapper.isAir() ? expected.isAir()
                                                : expected.equals(point.blockStateWrapper.getWrappedMcObject()),
                                        "DH LOD differs from the actual Earth column");
                            }
                            if (point.blockStateWrapper.isAir()) { require(point.skyLightLevel == 15, "Missing explicit lit air"); }
                        }
                        require(total == earth.getGenDepth(), "DH lost a band interval");
                    }
                }
                // A real LIGHT-stage saved edit must survive a later procedural FEATURES-stage update.
                var gold = DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {Blocks.GOLD_BLOCK.defaultBlockState()}, wrapper);
                var biome = data.getApiDataPointColumn(0, 0).getFirst().biomeWrapper;
                edited.setApiDataPointColumn(0, 0, EDhApiWorldGenerationStep.LIGHT,
                        new ArrayList<>(List.of(DhApiTerrainDataPoint.create((byte) 0, 15, 15, 0, 4, gold, biome))));
                edited.updateFromDataSource(data);
                require(edited.getApiDataPointColumn(0, 0).getFirst().blockStateWrapper.equals(gold), "Procedural LOD erased a lit player edit");
            }
        }
        evidence.append("Real DH validated four resolutions; exact base materials, full band coverage and LIGHT edit priority passed.\n");
    }

    private void capture(String name) throws Exception {
        var file = game.gameDirectory.toPath().resolve("evidence/" + name + ".png");
        Files.createDirectories(file.getParent());
        try (NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(file); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
