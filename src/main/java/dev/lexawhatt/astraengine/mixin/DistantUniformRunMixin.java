package dev.lexawhatt.astraengine.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import dev.lexawhatt.astraengine.compat.distant.DistantUniformRun;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH exposes no uniform-run iterator for actual chunk conversion. This exact-version hook keeps its
 * converter, material overrides, compression, lights and column emission; it only advances an unchanged
 * opaque run after proving that the skipped real cells cannot introduce another emitted boundary.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder", remap = false)
abstract class DistantUniformRunMixin {
    @Inject(method = "createFromChunk", at = @At("HEAD"))
    private static void astra$captureBounds(ILevelWrapper level, IChunkWrapper chunk,
            CallbackInfoReturnable<FullDataSourceV2> callback,
            @Share("astra$uniformRun") LocalRef<DistantUniformRun.Context> context) {
        context.set(DistantUniformRun.context(chunk));
    }

    // This point follows both sampled light stores and precedes the unchanged-run decision. A scalar
    // local rewrite avoids allocating a mutable MixinExtras local reference for every scanned voxel.
    @ModifyVariable(method = "createFromChunk", name = "y", at = @At(value = "INVOKE", ordinal = 0, target =
            "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;equals(Ljava/lang/Object;)Z"))
    private static int astra$consumeUniformSection(int y,
            @Share("astra$uniformRun") LocalRef<DistantUniformRun.Context> context,
            @Local(name = "relBlockX") int x, @Local(name = "relBlockZ") int z,
            @Local(name = "minBuildHeight") int minimumY,
            @Local(name = "newSkyLight") byte skyLight,
            @Local(name = "newBlockLight") byte blockLight, @Local(name = "forceSingleBlock") boolean forced,
            @Local(name = "currentBiome") IBiomeWrapper currentBiome,
            @Local(name = "newBiome") IBiomeWrapper sampledBiome,
            @Local(name = "currentBlockState") IBlockStateWrapper currentState,
            @Local(name = "newBlockState") IBlockStateWrapper sampledState) {
        if (context.get() != null && !forced && sampledBiome.equals(currentBiome) && sampledState.equals(currentState)) {
            return DistantUniformRun.bottom(context.get(), x, y, z, minimumY,
                    sampledState, sampledBiome, blockLight, skyLight);
        }
        return y;
    }
}
