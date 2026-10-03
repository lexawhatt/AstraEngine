package dev.lexawhatt.astraengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import dev.lexawhatt.astraengine.compat.distant.DistantBiomeMemo;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH has no public hook around repeated immutable biome-wrapper lookup. Retains its actual per-cell biome
 * sampling and all block/light scans, but avoids a concurrent-map lookup for the same registry holder.
 * The optional config applies only to DH 3.3.3; no world reference or cache outlives the original wrapper.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge", remap = false)
abstract class DistantChunkBiomeMixin {
    @Shadow @Final private ChunkAccess chunk;
    @Unique private volatile DistantBiomeMemo astra$biomeMemo;

    @WrapOperation(method = "getBiome", at = @At(value = "INVOKE", target =
            "Lcom/seibel/distanthorizons/common/wrappers/block/BiomeWrapper_neoforge;getBiomeWrapper(Lnet/minecraft/core/Holder;Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;)Lcom/seibel/distanthorizons/common/wrappers/block/BiomeWrapper_neoforge;"))
    private BiomeWrapper_neoforge astra$reuseSameBiome(Holder<Biome> holder, ILevelWrapper level,
            Operation<BiomeWrapper_neoforge> original) {
        if (chunk.getHeight() != PlanetChart.HEIGHT) { return original.call(holder, level); }
        var previous = astra$biomeMemo;
        if (previous != null && previous.holder() == holder) { return previous.wrapper(); }
        var result = original.call(holder, level);
        // Volatile publication keeps the key/result pair consistent even if DH reads one wrapper concurrently.
        astra$biomeMemo = new DistantBiomeMemo(holder, result);
        return result;
    }
}
