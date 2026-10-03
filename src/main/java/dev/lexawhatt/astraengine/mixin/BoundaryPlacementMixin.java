package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryPlacement;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chooses canonical placement storage before host snapshots, permissions, NBT and inventory consumption. */
@Mixin(ItemStack.class)
abstract class BoundaryPlacementMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void astra$routePlacement(UseOnContext context, CallbackInfoReturnable<InteractionResult> callback) {
        var result = BoundaryPlacement.route((ItemStack) (Object) this, context);
        if (result != null) { callback.setReturnValue(result); }
    }
}
