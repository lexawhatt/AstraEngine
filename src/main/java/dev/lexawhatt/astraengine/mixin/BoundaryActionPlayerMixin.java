package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.server.interaction.BoundaryActionAccess;
import dev.lexawhatt.astraengine.server.interaction.BoundaryActionScope;
import dev.lexawhatt.astraengine.server.interaction.BoundaryMenus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped canonical interaction ownership, with explicit escape before real player lifecycle transitions. */
@Mixin(ServerPlayer.class)
abstract class BoundaryActionPlayerMixin implements BoundaryActionAccess {
    @Unique private BoundaryActionScope astra$action;
    @Override public BoundaryActionScope astra$actionScope() { return astra$action; }
    @Override public void astra$actionScope(BoundaryActionScope scope) { astra$action = scope; }

    @Inject(method = {"teleportTo(DDD)V", "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
            "setServerLevel", "die"}, at = @At("HEAD"))
    private void astra$beforeTransition(CallbackInfo callback) {
        BoundaryActionScope.beforeTransition((ServerPlayer) (Object) this);
    }

    @Inject(method = "changeDimension", at = @At("HEAD"))
    private void astra$beforeDimension(CallbackInfoReturnable<Entity> callback) {
        BoundaryActionScope.beforeTransition((ServerPlayer) (Object) this);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"))
    private void astra$beforeRelativeTeleport(CallbackInfoReturnable<Boolean> callback) {
        BoundaryActionScope.beforeTransition((ServerPlayer) (Object) this);
    }

    @ModifyVariable(method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
            at = @At("HEAD"), argsOnly = true)
    private MenuProvider astra$canonicalMenu(MenuProvider provider) {
        return BoundaryMenus.wrap(provider, astra$action);
    }
}
