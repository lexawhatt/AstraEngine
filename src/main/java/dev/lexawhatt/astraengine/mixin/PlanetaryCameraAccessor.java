package dev.lexawhatt.astraengine.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Applies an interpolated server-owned inspection view in its current real host chart. */
@Mixin(Camera.class)
public interface PlanetaryCameraAccessor {
    @Invoker("setPosition") void astra$position(Vec3 position);
}
