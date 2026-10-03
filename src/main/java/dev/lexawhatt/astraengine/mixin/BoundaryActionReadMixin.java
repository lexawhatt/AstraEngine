package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionLevelAccess;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped host placement observes real neighboring support states without loading or duplicating their storage. */
@Mixin(Level.class)
abstract class BoundaryActionReadMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void astra$neighborState(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        var scope = ((BoundaryActionLevelAccess) this).astra$levelAction();
        if (scope != null && !scope.owner().contains(new SpaceVector(position.getX() + .5,
                position.getY() + .5, position.getZ() + .5))) {
            callback.setReturnValue(BoundaryCollision.observations((Level) (Object) this, scope.owner()).getBlockState(position));
        }
    }
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void astra$neighborFluid(BlockPos position, CallbackInfoReturnable<FluidState> callback) {
        var scope = ((BoundaryActionLevelAccess) this).astra$levelAction();
        if (scope != null && !scope.owner().contains(new SpaceVector(position.getX() + .5,
                position.getY() + .5, position.getZ() + .5))) {
            callback.setReturnValue(BoundaryCollision.observations((Level) (Object) this, scope.owner()).getFluidState(position));
        }
    }
}
