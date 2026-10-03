package dev.lexawhatt.astraengine.server.interaction;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.mixin.BoundaryEntityAccessor;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.BoundaryCollision;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One synchronous host block callback in canonical target coordinates. Entity-section ownership and chunk
 * tickets never move: numeric pose fields are scoped directly, without entity move callbacks. A real host
 * teleport/removal closes the scope before performing its lifecycle transition. Finally is always required.
 */
public final class BoundaryActionScope implements AutoCloseable {
    private final ServerPlayer player;
    private final ServerLevel original;
    private final ServerLevel target;
    private final CubeStorageChart source;
    private final CubeStorageChart owner;
    private final Vec3 position;
    private final AABB box;
    private final float yaw;
    private final float pitch;
    private final Vec3 eye;
    private final BoundaryActionScope previous;
    private boolean closed;

    private BoundaryActionScope(ServerPlayer player, ServerLevel target, CubeStorageChart owner) {
        if (player == null || target == null || owner == null || !player.server.isSameThread()
                || ((BoundaryActionLevelAccess) target).astra$levelAction() != null) {
            throw new IllegalStateException("Canonical block callback requires one synchronous server-owned scope");
        }
        this.player = player; this.original = player.serverLevel(); this.target = target; this.owner = owner;
        previous = ((BoundaryActionAccess) player).astra$actionScope();
        source = PlanetSurfaceWorlds.getCube(original).orElseThrow();
        if (!PlanetSurfaceWorlds.getCube(target).filter(owner::equals).isPresent()) {
            throw new IllegalArgumentException("Action target is not its loaded canonical chart");
        }
        var transform = new EarthChartTransform(source, owner);
        position = player.position(); box = player.getBoundingBox(); yaw = player.getYRot(); pitch = player.getXRot();
        eye = player.getEyePosition();
        var feet = new SpaceVector(position.x, position.y, position.z);
        var mapped = transform.position(feet);
        var facing = transform.orientation(feet, FlightOrientation.fromAngles(yaw, pitch, 0));
        ((BoundaryActionAccess) player).astra$actionScope(this);
        ((BoundaryActionLevelAccess) target).astra$levelAction(this);
        setNumericPose(target, new Vec3(mapped.x(), mapped.y(), mapped.z()),
                box.move(mapped.x() - position.x, mapped.y() - position.y, mapped.z() - position.z()), facing.yaw(), facing.pitch());
        player.gameMode.setLevel(target);
    }

    /** The action may invoke ordinary host hooks. It must never schedule work that captures this scope. */
    public static <T> T call(ServerPlayer player, ServerLevel target, CubeStorageChart owner, Supplier<T> action) {
        if (action == null) { throw new IllegalArgumentException("A canonical action callback is required"); }
        try (var scope = new BoundaryActionScope(player, target, owner)) { return action.get(); }
    }

    public CubeStorageChart owner() { return owner; }
    public ServerLevel target() { return target; }

    /** Same source-view block reach used by the verified ray, including host inventory's explicit distance buffer. */
    public boolean canReach(BlockPos pos, double buffer) {
        if (closed || pos == null || !Double.isFinite(buffer) || buffer < 0) { return false; }
        if (owner.radiusMeters() + pos.getY() + owner.altitudeOriginMeters() <= 0) { return false; }
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                if (owner.normal(pos.getX() + x, pos.getZ() + z).dot(source.face().outward()) <= 0) {
                    return false;
                }
            }
        }
        var sourceBounds = BoundaryCollision.bounds(new EarthChartTransform(owner, source), new AABB(pos));
        double x = Math.clamp(eye.x, sourceBounds.minX, sourceBounds.maxX);
        double y = Math.clamp(eye.y, sourceBounds.minY, sourceBounds.maxY);
        double z = Math.clamp(eye.z, sourceBounds.minZ, sourceBounds.maxZ);
        double range = Math.min(16, player.blockInteractionRange()) + buffer;
        return eye.distanceToSqr(x, y, z) <= range * range;
    }

    /** Ends the temporary coordinate observation before the real host transition starts. Safe to call repeatedly. */
    public static void beforeTransition(ServerPlayer player) {
        BoundaryActionScope current;
        while ((current = ((BoundaryActionAccess) player).astra$actionScope()) != null) { current.close(); }
    }

    @Override public void close() {
        if (closed) { return; }
        closed = true;
        if (((BoundaryActionAccess) player).astra$actionScope() == this) {
            ((BoundaryActionAccess) player).astra$actionScope(previous != null && !previous.closed ? previous : null);
        }
        if (((BoundaryActionLevelAccess) target).astra$levelAction() == this) {
            ((BoundaryActionLevelAccess) target).astra$levelAction(null);
        }
        // Genuine transitions close before moving. If another hook already changed ownership, never overwrite it.
        if (player.serverLevel() == target && !player.isRemoved()) {
            setNumericPose(original, position, box, yaw, pitch);
            player.gameMode.setLevel(original);
        }
    }

    private void setNumericPose(ServerLevel level, Vec3 value, AABB bounds, float yRot, float xRot) {
        var access = (BoundaryEntityAccessor) player;
        access.astra$actionLevel(level); access.astra$actionPosition(value);
        var block = BlockPos.containing(value);
        access.astra$actionBlockPosition(block); access.astra$actionChunkPosition(new ChunkPos(block));
        access.astra$actionBlockState(null); player.setBoundingBox(bounds); player.setYRot(yRot); player.setXRot(xRot);
    }
}
