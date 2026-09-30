package dev.lexawhatt.astraengine.client.rocket;

import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable render-thread assembly snapshot. Origin is the blueprint's local origin in world blocks;
 * yaw rotates local +X toward -Z around +Y. Selection -1 disables the editor highlight.
 * This record retains no entity, level or server state.
 */
public record RocketRenderInstance(RocketBlueprint blueprint, Vec3 worldOrigin, float yawDegrees,
        int selectedPartIndex) {
    public RocketRenderInstance {
        if (blueprint == null || worldOrigin == null || !Double.isFinite(worldOrigin.x)
                || !Double.isFinite(worldOrigin.y) || !Double.isFinite(worldOrigin.z)
                || !Float.isFinite(yawDegrees) || selectedPartIndex < -1
                || selectedPartIndex >= blueprint.parts().size()) {
            throw new IllegalArgumentException("Invalid rocket render snapshot");
        }
    }
}
