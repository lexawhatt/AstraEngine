package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionLevelAccess;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionScope;
import dev.lexawhatt.astraengine.worldgen.CanonicalBlockOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ordinary and scoped host actions cannot create writable aliases beyond a bound chart. */
@Mixin(Level.class)
abstract class BoundaryActionWriteMixin implements BoundaryActionLevelAccess {
    @Unique private BoundaryActionScope astra$levelAction;
    @Override public BoundaryActionScope astra$levelAction() { return astra$levelAction; }
    @Override public void astra$levelAction(BoundaryActionScope scope) { astra$levelAction = scope; }
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void astra$denyAliasWrite(BlockPos position, BlockState state, int flags, int recursion,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ServerLevel server
                && !CanonicalBlockOwnership.permits(server.getChunkSource().getGenerator(), position)
                || astra$levelAction != null && !astra$levelAction.owner().contains(
                new SpaceVector(position.getX() + .5, position.getY() + .5, position.getZ() + .5))) {
            callback.setReturnValue(false);
        }
    }
}
