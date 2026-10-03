package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.surface.PlanetaryInspectionAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inspection has one movement integrator while retaining ordinary host collision and player lifecycle. */
@Mixin(Player.class)
abstract class PlanetaryInspectionMixin implements PlanetaryInspectionAccess {
    @Unique private boolean astra$inspection;
    @Override public boolean astra$inspectionMovement() { return astra$inspection; }
    @Override public void astra$inspectionMovement(boolean active) { astra$inspection = active; }
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void astra$ownedMovement(Vec3 input, CallbackInfo callback) {
        if (astra$inspection) { callback.cancel(); }
    }
}
