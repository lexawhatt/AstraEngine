package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.TerrainHeightmaps;
import java.util.Set;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The generator has no hook before FEATURES' unconditional heightmap scan; other generators retain host behavior. */
@Mixin(ChunkStatusTasks.class)
abstract class EarthHeightmapMixin {
    @Redirect(method = "generateFeatures", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"))
    private static void astra$primeTerrain(ChunkAccess chunk, Set<Heightmap.Types> types, WorldGenContext context,
            ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess owner) {
        if (context.generator() instanceof EarthChunkGenerator || context.generator() instanceof PlanetChunkGenerator) {
            TerrainHeightmaps.prime(chunk, types);
        }
        else { Heightmap.primeHeightmaps(chunk, types); }
    }
}
