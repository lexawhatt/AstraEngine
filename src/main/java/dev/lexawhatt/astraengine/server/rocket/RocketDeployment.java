package dev.lexawhatt.astraengine.server.rocket;

import dev.lexawhatt.astraengine.api.rocket.RocketEditorHost;
import dev.lexawhatt.astraengine.network.RocketEditorStatePayload.Status;
import dev.lexawhatt.astraengine.rocket.RocketGeometry;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loaded-space, non-destructive deployment of one immutable stationary assembly per persistent host. */
final class RocketDeployment {
    private RocketDeployment() {}
    static Status deploy(ServerLevel level, BlockPos owner, RocketEditorHost host) {
        var blueprint = host.blueprint();
        try { RocketWorkshop.catalog().validate(blueprint); }
        catch (IllegalArgumentException invalid) { return Status.INVALID; }
        if (blueprint.parts().isEmpty()) { return Status.INVALID; }
        RocketAssemblyEntity existing = null;
        if (host.deployedAssembly().isPresent()) {
            Entity found = level.getEntity(host.deployedAssembly().get());
            // An absent UUID may belong to an unloaded assembly. Never manufacture a duplicate in that case.
            if (!(found instanceof RocketAssemblyEntity assembly) || !assembly.hostPosition().equals(owner)) { return Status.OBSTRUCTED; }
            existing = assembly;
        }
        var bounds = RocketGeometry.bounds(blueprint);
        Vec3 origin = new Vec3(owner.getX() + 2 - bounds.min().x(), owner.getY() - bounds.min().y(),
                owner.getZ() + 0.5 - (bounds.min().z() + bounds.max().z()) * 0.5);
        var boxes = RocketAssemblyEntity.collisionBoxes(blueprint, origin);
        for (AABB box : boxes) {
            if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                    || !level.getWorldBorder().isWithinBounds(box)) { return Status.OBSTRUCTED; }
            for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
                for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                    if (!level.hasChunk(x, z)) { return Status.OBSTRUCTED; }
                }
            }
            if (level.getBlockCollisions(existing, box).iterator().hasNext()) { return Status.OBSTRUCTED; }
            RocketAssemblyEntity replaced = existing;
            if (!level.getEntities(existing, box, entity -> !entity.isSpectator() && !entity.isRemoved()
                    && !(entity instanceof RocketAssemblyEntity)
                    && !(entity instanceof RocketHitboxPart part && (!part.active() || part.getParent() == replaced)))
                    .isEmpty()) {
                return Status.OBSTRUCTED;
            }
        }
        if (existing != null) {
            existing.setPos(origin.x, origin.y, origin.z); existing.configure(owner, blueprint); return Status.DEPLOYED;
        }
        RocketAssemblyEntity assembly = new RocketAssemblyEntity(RocketWorkshop.ASSEMBLY.get(), level);
        assembly.setPos(origin.x, origin.y, origin.z); assembly.configure(owner, blueprint);
        if (!level.addFreshEntity(assembly)) { return Status.OBSTRUCTED; }
        host.setDeployedAssembly(Optional.of(assembly.getUUID())); return Status.DEPLOYED;
    }
}
