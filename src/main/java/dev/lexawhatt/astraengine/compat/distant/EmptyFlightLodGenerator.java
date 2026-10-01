package dev.lexawhatt.astraengine.compat.distant;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import dev.lexawhatt.astraengine.server.RocketService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;

/** Exact air LODs for the engine's verified empty staging generator; saved LIGHT edits still take precedence. */
public final class EmptyFlightLodGenerator implements IDhApiWorldGenerator {
    private final DhApiTerrainDataPoint air;

    /** Checks actual generator content, not just the dimension name; altered datapacks retain their own generator. */
    public static boolean supports(ServerLevel level) {
        if (!level.dimension().equals(RocketService.FLIGHT)
                || !(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat)) { return false; }
        var settings = flat.settings();
        return settings.getLayers().isEmpty() && settings.getBiome().is(Biomes.THE_VOID)
                && settings.structureOverrides().isPresent() && settings.structureOverrides().orElseThrow().size() == 0
                && settings.adjustGenerationSettings(settings.getBiome()).features().stream().allMatch(set -> set.size() == 0);
    }

    /** Capture immutable wrappers on level load; no level or executor is retained. Rejects nonempty definitions. */
    public EmptyFlightLodGenerator(IDhApiLevelWrapper wrapper) {
        if (wrapper == null || !(wrapper.getWrappedMcObject() instanceof ServerLevel level) || !supports(level)) {
            throw new IllegalArgumentException("Empty LOD override requires the unchanged flight staging generator");
        }
        var factory = DhApi.Delayed.wrapperFactory;
        var flat = (FlatLevelSource) level.getChunkSource().getGenerator();
        air = DhApiTerrainDataPoint.create((byte) 0, 0, 15, 0, level.getHeight(),
                factory.getBlockStateWrapper(new Object[] {Blocks.AIR.defaultBlockState()}, wrapper),
                factory.getBiomeWrapper(new Object[] {flat.settings().getBiome()}, wrapper));
    }

    @Override public byte getLargestDataDetailLevel() { return 12; }
    @Override public EDhApiWorldGeneratorReturnType getReturnType() { return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES; }

    @Override
    public CompletableFuture<Void> generateLod(int chunkX, int chunkZ, int lodX, int lodZ, byte detail,
            IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode, ExecutorService executor,
            Consumer<IDhApiFullDataSource> consumer) {
        if (detail < 0 || detail > getLargestDataDetailLevel() || data == null || executor == null
                || consumer == null || data.getWidthInDataColumns() != 64) {
            throw new IllegalArgumentException("Invalid empty flight LOD request");
        }
        return CompletableFuture.runAsync(() -> {
            var column = new ArrayList<>(List.of(air));
            for (int z = 0; z < 64; z++) {
                if (Thread.currentThread().isInterrupted()) { throw new CancellationException("Flight LOD interrupted"); }
                for (int x = 0; x < 64; x++) {
                    data.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.FEATURES, column);
                }
            }
            consumer.accept(data);
        }, executor);
    }

    @Override public void preGeneratorTaskStart() { }
    @Override public void close() { }
}
