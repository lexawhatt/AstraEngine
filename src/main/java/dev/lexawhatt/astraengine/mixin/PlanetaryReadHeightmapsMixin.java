package dev.lexawhatt.astraengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.TerrainHeightmaps;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Repairs missing tall-chart heightmaps from actual palettes before the host publishes the loaded chunk. */
@Mixin(ChunkSerializer.class)
abstract class PlanetaryReadHeightmapsMixin {
    @WrapOperation(method = "read", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"))
    private static void astra$primeMissingMaps(ChunkAccess chunk, Set<Heightmap.Types> types,
            Operation<Void> original, @Local(argsOnly = true) ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        if (chunk.getHeight() == PlanetChart.HEIGHT
                && (generator instanceof EarthChunkGenerator || generator instanceof PlanetChunkGenerator)) {
            // The host invokes this even when every saved map was present. A missing empty-column
            // predicate otherwise scans the entire altitude window for each of its 256 columns.
            TerrainHeightmaps.prime(chunk, types);
        } else {
            original.call(chunk, types);
        }
    }
}
