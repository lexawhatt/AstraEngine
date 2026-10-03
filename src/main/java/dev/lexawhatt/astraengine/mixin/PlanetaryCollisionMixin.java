package dev.lexawhatt.astraengine.mixin;

import com.google.common.collect.Iterables;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The host collision iterator cannot see another permanent Level; add only prepared geographic neighbors. */
@Mixin(CollisionGetter.class)
interface PlanetaryCollisionMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void astra$neighborCollisions(Entity entity, AABB box, CallbackInfoReturnable<Iterable<VoxelShape>> result) {
        if ((Object) this instanceof Level level) {
            var additional = BoundaryCollision.shapes(level, entity, box);
            if (!additional.isEmpty()) { result.setReturnValue(Iterables.concat(result.getReturnValue(), additional)); }
        }
    }
}
