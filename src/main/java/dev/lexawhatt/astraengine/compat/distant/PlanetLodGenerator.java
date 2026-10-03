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
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Direct whole-body LOD adapter. Work is exactly 4096 columns per DH request at any supported spacing;
 * no host chunk, world reference, private executor or mutable persistent cache is created.
 */
public final class PlanetLodGenerator implements IDhApiWorldGenerator {
    private final PlanetChunkGenerator terrain;
    private final Map<BlockState, IDhApiBlockStateWrapper> blocks;
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes;

    public PlanetLodGenerator(IDhApiLevelWrapper level, PlanetChunkGenerator terrain) {
        if (level == null || terrain == null || !(level.getWrappedMcObject() instanceof LevelHeightAccessor height)
                || height.getMinBuildHeight() != terrain.getMinY() || height.getHeight() != terrain.getGenDepth()) {
            throw new IllegalArgumentException("Planet LODs require matching host storage bounds");
        }
        this.terrain = terrain;
        var blockCopy = new HashMap<BlockState, IDhApiBlockStateWrapper>();
        var factory = DhApi.Delayed.wrapperFactory;
        for (Block block : new Block[] {Blocks.AIR, Blocks.STONE, Blocks.BEDROCK, Blocks.WATER, Blocks.PACKED_ICE,
                Blocks.SNOW_BLOCK, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.GRAVEL, Blocks.SAND}) {
            var state = block.defaultBlockState();
            blockCopy.put(state, factory.getBlockStateWrapper(new Object[] {state}, level));
        }
        blocks = Map.copyOf(blockCopy);
        var biomeCopy = new HashMap<Holder<Biome>, IDhApiBiomeWrapper>();
        for (var biome : terrain.getBiomeSource().possibleBiomes()) {
            biomeCopy.put(biome, factory.getBiomeWrapper(new Object[] {biome}, level));
        }
        biomes = Map.copyOf(biomeCopy);
    }

    @Override public byte getLargestDataDetailLevel() { return 12; }
    @Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }
    @Override public CompletableFuture<Void> generateLod(int chunkX, int chunkZ, int lodX, int lodZ, byte detail,
            IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode, ExecutorService executor,
            Consumer<IDhApiFullDataSource> consumer) {
        if (detail < 0 || detail > getLargestDataDetailLevel() || data == null || executor == null || consumer == null
                || data.getWidthInDataColumns() != 64) { throw new IllegalArgumentException("Invalid planetary LOD request"); }
        // Initial persisted profiles use one host biome; climate extensions must provide an explicit sampler.
        if (biomes.size() != 1) { throw new IllegalStateException("Planet LOD requires its saved fixed biome source"); }
        var biome = biomes.values().iterator().next();
        int spacing = 1 << detail;
        long originX = chunkX * 16L, originZ = chunkZ * 16L;
        return CompletableFuture.runAsync(() -> {
            var column = new ArrayList<DhApiTerrainDataPoint>(5);
            for (int z = 0; z < 64; z++) {
                if (Thread.currentThread().isInterrupted()) { throw new CancellationException("Planet LOD interrupted"); }
                for (int x = 0; x < 64; x++) {
                    int blockX = Math.toIntExact(originX + (long) x * spacing + spacing / 2);
                    int blockZ = Math.toIntExact(originZ + (long) z * spacing + spacing / 2);
                    column.clear();
                    for (var layer : terrain.terrainLayers(blockX, blockZ)) {
                        var block = blocks.get(layer.state());
                        if (block == null) { throw new IllegalStateException("Uncaptured planet LOD material: " + layer.state()); }
                        int sky = layer.state().isAir() ? 15 : layer.state().is(Blocks.WATER) ? 12 : 0;
                        column.add(DhApiTerrainDataPoint.create((byte) 0, 0, sky,
                                layer.bottomY() - terrain.getMinY(), layer.topY() - terrain.getMinY(), block, biome));
                    }
                    data.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.FEATURES, column);
                }
            }
            consumer.accept(data);
        }, executor);
    }
    @Override public void preGeneratorTaskStart() { }
    /** DH may close and reuse this override when generation is toggled; it owns all tasks and their executor. */
    @Override public void close() { }
}
