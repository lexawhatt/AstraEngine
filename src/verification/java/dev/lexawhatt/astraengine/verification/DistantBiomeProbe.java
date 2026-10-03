package dev.lexawhatt.astraengine.verification;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper_neoforge;
import dev.lexawhatt.astraengine.surface.EarthChart;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Actual optional-DH wrapper checks on a disposable in-memory chunk; no saved terrain is changed. */
final class DistantBiomeProbe {
    private DistantBiomeProbe() { }

    static void verify(ServerLevel level) {
        if (!level.getServer().isSameThread()) { throw new IllegalStateException("Probe requires the server thread"); }
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            var height = LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT);
            var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height, registry, null);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            var world = ServerLevelWrapper_neoforge.getWrapper(level);
            var wrapper = new ChunkWrapper_neoforge(chunk, world);
            var field = wrapper.getClass().getDeclaredField("astra$biomeMemo"); field.setAccessible(true);
            var biome = registry.getHolderOrThrow(Biomes.DESERT);
            var palette = (PalettedContainer<Holder<Biome>>) chunk.getSection(0).getBiomes();
            palette.getAndSet(0, 0, 0, biome);
            var first = wrapper.getBiome(0, EarthChart.MIN_Y, 0);
            var memo = field.get(wrapper);
            require(memo != null && first.equals(BiomeWrapper_neoforge.getBiomeWrapper(biome, world)),
                    "DH memo did not wrap the actual biome");
            require(wrapper.getBiome(0, EarthChart.MIN_Y + 1, 0) == first && field.get(wrapper) == memo,
                    "DH repeated biome did not reuse its immutable lookup");
            var changed = registry.getHolderOrThrow(Biomes.FROZEN_OCEAN);
            palette.getAndSet(0, 0, 0, changed);
            require(wrapper.getBiome(0, EarthChart.MIN_Y, 0).equals(BiomeWrapper_neoforge.getBiomeWrapper(changed, world))
                    && field.get(wrapper) != memo, "DH biome edit retained a stale wrapper");
            var ordinary = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(-64, 384), registry, null);
            ordinary.setPersistedStatus(ChunkStatus.BIOMES);
            var ordinaryWrapper = new ChunkWrapper_neoforge(ordinary, world);
            ordinaryWrapper.getBiome(0, -64, 0);
            require(field.get(ordinaryWrapper) == null, "DH memo changed ordinary-height chunk handling");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned DH wrapper optimization was not applied", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
