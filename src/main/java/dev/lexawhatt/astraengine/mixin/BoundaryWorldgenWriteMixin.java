package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.worldgen.CanonicalBlockOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Decorations crossing a face must never persist a second copy in the source chart's read-only extension. */
@Mixin(WorldGenRegion.class)
abstract class BoundaryWorldgenWriteMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void astra$denyAliasDecoration(BlockPos position, BlockState state, int flags, int recursion,
            CallbackInfoReturnable<Boolean> callback) {
        var region = (WorldGenRegion) (Object) this;
        if (!CanonicalBlockOwnership.permits(region.getLevel().getChunkSource().getGenerator(), position)) {
            callback.setReturnValue(false);
        }
    }
}
