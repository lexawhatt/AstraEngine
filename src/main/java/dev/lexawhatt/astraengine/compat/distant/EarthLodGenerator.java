package dev.lexawhatt.astraengine.compat.distant;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import dev.lexawhatt.astraengine.surface.EarthClimate;
import dev.lexawhatt.astraengine.worldgen.EarthBiomeSource;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Optional DH 7.2 API adapter for immutable Earth geography. DH owns scheduling, pooled data, persistence
 * and disposal. This adapter creates no Minecraft chunks, executor, level cache, or renderer. Actual lit
 * chunk data has higher DH generation priority than this undecorated geographic approximation.
 */
public final class EarthLodGenerator implements IDhApiWorldGenerator {
    private final EarthChunkGenerator terrain;
    private final Map<BlockState, IDhApiBlockStateWrapper> blocks;
    private final Map<EarthClimate, IDhApiBiomeWrapper> biomes;
    private final LongAdder completed = new LongAdder();
    private final LongAdder columns = new LongAdder();
    private final LongAdder nanos = new LongAdder();

    /** Capture registry wrappers at DH level load; the wrapper and its host level are not retained. */
    public EarthLodGenerator(IDhApiLevelWrapper level, EarthChunkGenerator terrain) {
        // DH 3.3.3's ServerLevelWrapper.getMaxHeight() returns host height, not the documented upper Y.
        // Validate the actual pinned host bounds instead of deriving an incorrect negative-Y interval.
        if (level == null || terrain == null || !(level.getWrappedMcObject() instanceof LevelHeightAccessor height)
                || height.getMinBuildHeight() != terrain.getMinY() || height.getHeight() != terrain.getGenDepth()) {
            throw new IllegalArgumentException("DH Earth LODs require the matching generator and height interval");
        }
        this.terrain = terrain;
        var factory = DhApi.Delayed.wrapperFactory;
        Map<BlockState, IDhApiBlockStateWrapper> blockCopy = new HashMap<>();
        for (Block block : new Block[] {Blocks.AIR, Blocks.STONE, Blocks.WATER, Blocks.GRASS_BLOCK, Blocks.DIRT,
                Blocks.GRAVEL, Blocks.SAND, Blocks.SANDSTONE, Blocks.SNOW_BLOCK}) {
            BlockState state = block.defaultBlockState();
            blockCopy.put(state, factory.getBlockStateWrapper(new Object[] {state}, level));
        }
        blocks = Map.copyOf(blockCopy);
        Map<EarthClimate, IDhApiBiomeWrapper> biomeCopy = new HashMap<>();
        var source = (EarthBiomeSource) terrain.getBiomeSource();
        for (EarthClimate climate : EarthClimate.values()) {
            biomeCopy.put(climate, factory.getBiomeWrapper(new Object[] {source.biome(climate)}, level));
        }
        biomes = Map.copyOf(biomeCopy);
    }

    @Override public byte getLargestDataDetailLevel() { return 12; }
    @Override public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
            byte detailLevel, IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode,
            ExecutorService executor, Consumer<IDhApiFullDataSource> resultConsumer) {
        if (detailLevel < 0 || detailLevel > getLargestDataDetailLevel() || data == null || executor == null
                || resultConsumer == null || data.getWidthInDataColumns() != 64) {
            throw new IllegalArgumentException("Invalid DH Earth LOD request");
        }
        // DH requests one 64x64 tile at the desired spacing. Cost is independent of world height and area.
        int spacing = 1 << detailLevel;
        long startX = chunkPosMinX * 16L, startZ = chunkPosMinZ * 16L;
        return CompletableFuture.runAsync(() -> {
            long started = System.nanoTime();
            var values = new ArrayList<DhApiTerrainDataPoint>(5);
            int minY = terrain.getMinY();
            for (int z = 0; z < 64; z++) {
                if (Thread.currentThread().isInterrupted()) { throw new CancellationException("Earth LOD interrupted"); }
                for (int x = 0; x < 64; x++) {
                    int blockX = Math.toIntExact(startX + (long) x * spacing + spacing / 2);
                    int blockZ = Math.toIntExact(startZ + (long) z * spacing + spacing / 2);
                    // Match the source's four-block biome cell center, including negative chart positions.
                    var climate = EarthClimate.at(terrain.terrain().sample(terrain.chart().normal(
                            Math.floorDiv(blockX, 4) * 4.0 + 2, Math.floorDiv(blockZ, 4) * 4.0 + 2)));
                    var biome = biomes.get(climate);
                    values.clear();
                    for (var layer : terrain.terrainLayers(blockX, blockZ)) {
                        var state = blocks.get(layer.state());
                        if (state == null) { throw new IllegalStateException("Uncaptured Earth LOD material: " + layer.state()); }
                        // Air is explicit: DH derives visible-face skylight from the neighboring air run.
                        int sky = layer.state().isAir() ? 15 : layer.state().is(Blocks.WATER) ? 12 : 0;
                        // The data source owns horizontal detail. Its API column validator requires block-unit
                        // vertical runs (detail zero), even when the containing tile has kilometer spacing.
                        values.add(DhApiTerrainDataPoint.create((byte) 0, 0, sky,
                                layer.bottomY() - minY, layer.topY() - minY, state, biome));
                    }
                    // FEATURES completes the override's approximation, avoiding DH's SURFACE refinement loop.
                    // It is deliberately below LIGHT: real illuminated chunks/edits always supersede it.
                    data.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.FEATURES, values);
                }
            }
            resultConsumer.accept(data);
            columns.add(64L * 64);
            completed.increment();
            nanos.add(System.nanoTime() - started);
        }, executor);
    }

    /** No pending queue or thread is owned here. */
    @Override public void preGeneratorTaskStart() { }
    /** DH can close and reuse an override when generation is toggled. There are no owned resources to dispose. */
    @Override public void close() { }

    /** Read-only per-level diagnostic totals; generation time excludes DH database, mesh and draw costs. */
    public Metrics metrics() { return new Metrics(completed.sum(), columns.sum(), nanos.sum()); }
    /** Completed requests, sampled columns and accumulated worker nanoseconds, never an FPS estimate. */
    public record Metrics(long requests, long columns, long workerNanos) { }
}
