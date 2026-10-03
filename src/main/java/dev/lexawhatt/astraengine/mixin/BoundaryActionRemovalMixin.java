package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryActionScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removal must notify the entity's actual host level, never a temporary neighboring action frame. */
@Mixin(Entity.class)
abstract class BoundaryActionRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void astra$restoreBeforeRemoval(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player) { BoundaryActionScope.beforeTransition(player); }
    }
}
