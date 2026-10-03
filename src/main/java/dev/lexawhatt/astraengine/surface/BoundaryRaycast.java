package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded outline ray through canonical chart observations. Source rays are host eye-space meters; each
 * short segment is projected into its real owner before host shape clipping. Reads never request chunks.
 * The same routine is used for client selection and an independent authoritative server validation.
 */
public final class BoundaryRaycast {
    public static final double MAX_REACH_METERS = 16;
    private static final double STEP_METERS = 1.0 / 32;
    private BoundaryRaycast() { }

    /** Canonical target and shape hit, plus its source-view position; no host world or entity is retained. */
    public record Hit(CubeStorageChart owner, BlockPos position, Direction face, Vec3 targetHit,
                      Vec3 sourceHit, double distanceMeters) { }

    /**
     * At most 512 shape segments. Null/invalid arguments fail; absence means no observed collision.
     * The observer function must provide immutable client sections or no-load server chunk observations.
     */
    public static Optional<Hit> pick(CubeStorageChart source, Vec3 eye, Vec3 direction, double reach,
            Function<CubeStorageChart, BlockGetter> observations, Entity entity) {
        if (source == null || eye == null || direction == null || observations == null || entity == null
                || !Double.isFinite(reach) || reach <= 0 || reach > MAX_REACH_METERS
                || !Double.isFinite(eye.lengthSqr()) || !Double.isFinite(direction.lengthSqr())
                || Math.abs(direction.lengthSqr() - 1) > 1e-5) {
            throw new IllegalArgumentException("Boundary ray requires a finite normalized bounded host view");
        }
        int segments = (int) Math.ceil(reach / STEP_METERS);
        CubeStorageChart owner = null;
        BlockGetter blocks = null;
        EarthChartTransform transform = null;
        for (int index = 0; index < segments; index++) {
            double from = reach * index / segments, to = reach * (index + 1) / segments;
            Vec3 a = eye.add(direction.scale(from)), b = eye.add(direction.scale(to));
            Vec3 middle = a.lerp(b, .5);
            var next = source.ownerChart(source.projectedGeographic(vector(middle))).orElse(null);
            if (next == null) { return Optional.empty(); }
            if (!next.equals(owner)) {
                owner = next; blocks = observations.apply(owner); transform = new EarthChartTransform(source, owner);
                if (blocks == null) { return Optional.empty(); }
            }
            Vec3 targetA = vec(transform.position(vector(a))), targetB = vec(transform.position(vector(b)));
            BlockHitResult hit = blocks.clip(new ClipContext(targetA, targetB, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, BoundaryCollision.context(entity,
                            source.altitudeOriginMeters() - owner.altitudeOriginMeters())));
            if (hit.getType() != HitResult.Type.BLOCK) { continue; }
            if (!owner.contains(new SpaceVector(hit.getBlockPos().getX() + .5, hit.getBlockPos().getY() + .5,
                    hit.getBlockPos().getZ() + .5))) { continue; }
            var viewHit = vec(new EarthChartTransform(owner, source).position(vector(hit.getLocation())));
            return Optional.of(new Hit(owner, hit.getBlockPos().immutable(), hit.getDirection(), hit.getLocation(),
                    viewHit, eye.distanceTo(viewHit)));
        }
        return Optional.empty();
    }

    private static SpaceVector vector(Vec3 value) { return new SpaceVector(value.x, value.y, value.z); }
    private static Vec3 vec(SpaceVector value) { return new Vec3(value.x(), value.y(), value.z()); }
}
