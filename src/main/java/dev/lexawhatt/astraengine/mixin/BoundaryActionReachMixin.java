package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryActionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped host reach uses the source camera frame; a gnomonic face change is not permission to reach farther. */
@Mixin(Player.class)
abstract class BoundaryActionReachMixin {
    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void astra$canonicalReach(BlockPos position, double buffer, CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof BoundaryActionAccess access && access.astra$actionScope() != null) {
            callback.setReturnValue(access.astra$actionScope().canReach(position, buffer));
        }
    }
}
