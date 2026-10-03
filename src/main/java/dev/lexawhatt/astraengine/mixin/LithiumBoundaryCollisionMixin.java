package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import java.util.Iterator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithium's movement and intersection solvers bypass CollisionGetter.getBlockCollisions. Supply the same
 * canonical neighbor shapes to its iterator, retaining its local block/entity/step solver and user settings.
 * The iterator owns this bounded observation for one collision query; no world or entity is retained.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper", remap = false)
abstract class LithiumBoundaryCollisionMixin {
    @Unique private Iterator<VoxelShape> astra$neighborShapes;

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Z)V",
            at = @At("RETURN"))
    private void astra$observeNeighbors(Level level, Entity entity, AABB box, boolean hideLastCollision,
            CallbackInfo callback) {
        var shapes = BoundaryCollision.shapes(level, entity, box);
        if (!shapes.isEmpty()) { astra$neighborShapes = shapes.iterator(); }
    }

    @Inject(method = "computeNext()Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private void astra$includeNeighbors(CallbackInfoReturnable<VoxelShape> result) {
        if (astra$neighborShapes == null) { return; }
        // Emit before the host reaches AbstractIterator.endOfData(), which permanently marks it exhausted.
        if (astra$neighborShapes.hasNext()) { result.setReturnValue(astra$neighborShapes.next()); }
        else { astra$neighborShapes = null; }
    }
}
