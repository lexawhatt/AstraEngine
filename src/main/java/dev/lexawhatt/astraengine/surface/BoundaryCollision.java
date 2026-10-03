package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.api.AstraGeography;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.server.EarthWorlds;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Adds real canonical neighboring block collision to the host's normal collision iterator. Reads only loaded
 * server chunks or immutable client observations; an unavailable neighbor is a solid recoverable boundary.
 * No chunk generation, mutation, global state, or authoritative use of the procedural background occurs here.
 */
public final class BoundaryCollision {
    private BoundaryCollision() {}

    /** No-load block observations in the requested canonical chart; unavailable cells are opaque barriers. */
    public static BlockGetter observations(Level level, CubeStorageChart chart) {
        if (level == null || chart == null) { throw new IllegalArgumentException("Boundary observations require a level and chart"); }
        return new Neighbors(level, chart);
    }

    /** Nullable immutable context of an actually bound host level. Requires the level's owning thread. */
    public static CubeStorageChart chart(Level level) {
        if (level instanceof ServerLevel server) {
            return AstraGeography.planetaryReference(server).filter(CubeStorageChart.class::isInstance)
                    .map(CubeStorageChart.class::cast).orElse(null);
        }
        return level instanceof PlanetaryLevelView view ? view.astra$chart() : null;
    }

    /** Extra shapes for ordinary queries; larger sweeps stop at the storage seam instead of tunnelling through it. */
    public static List<VoxelShape> shapes(Level level, Entity entity, AABB query) {
        CubeStorageChart source = chart(level);
        if (source == null) { return List.of(); }
        var result = new ArrayList<VoxelShape>();
        if (source instanceof PlanetChart planet && planet.coreFloorY() >= source.minY()
                && query.minY < planet.coreFloorY()) {
            result.add(Shapes.create(new AABB(query.minX, query.minY, query.minZ,
                    query.maxX, Math.min(query.maxY, planet.coreFloorY()), query.maxZ)));
            if (query.maxY <= planet.coreFloorY()) { return result; }
            query = new AABB(query.minX, planet.coreFloorY(), query.minZ, query.maxX, query.maxY, query.maxZ);
        }
        double r = source.radiusMeters();
        if (query.minX >= -r && query.maxX <= r && query.minZ >= -r && query.maxZ <= r
                && query.minY >= source.minY() && query.maxY < source.minY() + source.height()) { return result; }
        if (query.getXsize() > 16 || query.getYsize() > 16 || query.getZsize() > 16) {
            result.addAll(boundedBarrier(source, query));
            return result;
        }
        var owners = new LinkedHashSet<CubeStorageChart>();
        for (double x : new double[]{query.minX, query.maxX}) {
            for (double y : new double[]{query.minY, query.maxY}) {
                for (double z : new double[]{query.minZ, query.maxZ}) {
                    source.ownerChart(source.projectedGeographic(new SpaceVector(x, y, z))).ifPresent(owners::add);
                }
            }
        }
        owners.remove(source);
        for (CubeStorageChart owner : owners) {
            var transform = new EarthChartTransform(source, owner);
            AABB target = bounds(transform, query).inflate(.000001);
            var blocks = new Neighbors(level, owner);
            CollisionContext context = context(entity, source.altitudeOriginMeters() - owner.altitudeOriginMeters());
            var cursor = new BlockPos.MutableBlockPos();
            for (int x = (int) Math.floor(target.minX) - 1; x <= Math.floor(target.maxX) + 1; x++) {
                for (int y = (int) Math.floor(target.minY) - 1; y <= Math.floor(target.maxY) + 1; y++) {
                    for (int z = (int) Math.floor(target.minZ) - 1; z <= Math.floor(target.maxZ) + 1; z++) {
                        if (!owner.contains(new SpaceVector(x + .5, y + .5, z + .5))) { continue; }
                        cursor.set(x, y, z);
                        BlockState state = blocks.getBlockState(cursor);
                        if (state.isAir()) { continue; }
                        VoxelShape shape = state.getCollisionShape(blocks, cursor, context);
                        for (AABB box : shape.toAabbs()) {
                            for (AABB mapped : ChartCollisionProjection.boxes(owner, source, box.move(x, y, z))) {
                                if (query.intersects(mapped)) { result.add(Shapes.create(mapped)); }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<VoxelShape> boundedBarrier(CubeStorageChart chart, AABB query) {
        var result = new ArrayList<VoxelShape>(6);
        double r = chart.radiusMeters(), bottom = chart.minY(), top = bottom + chart.height();
        if (query.minX < -r) { result.add(Shapes.create(new AABB(query.minX, query.minY, query.minZ,
                Math.min(-r, query.maxX), query.maxY, query.maxZ))); }
        if (query.maxX > r) { result.add(Shapes.create(new AABB(Math.max(r, query.minX), query.minY, query.minZ,
                query.maxX, query.maxY, query.maxZ))); }
        if (query.minZ < -r) { result.add(Shapes.create(new AABB(query.minX, query.minY, query.minZ,
                query.maxX, query.maxY, Math.min(-r, query.maxZ)))); }
        if (query.maxZ > r) { result.add(Shapes.create(new AABB(query.minX, query.minY, Math.max(r, query.minZ),
                query.maxX, query.maxY, query.maxZ))); }
        if (query.minY < bottom) { result.add(Shapes.create(new AABB(query.minX, query.minY, query.minZ,
                query.maxX, Math.min(bottom, query.maxY), query.maxZ))); }
        if (query.maxY > top) { result.add(Shapes.create(new AABB(query.minX, Math.max(top, query.minY), query.minZ,
                query.maxX, query.maxY, query.maxZ))); }
        return result;
    }

    /** Exact enclosing bounds of a projective horizontal box in the shared outward hemisphere. */
    public static AABB bounds(EarthChartTransform transform, AABB box) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    var p = transform.position(new SpaceVector(x, y, z));
                    minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
                    maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Read-only host shape context expressed in a neighboring band's vertical coordinates. */
    public static CollisionContext context(Entity entity, double altitudeOffset) {
        if (entity == null) { return CollisionContext.empty(); }
        CollisionContext original = CollisionContext.of(entity);
        return new CollisionContext() {
            @Override public boolean isDescending() { return original.isDescending(); }
            @Override public boolean isAbove(VoxelShape shape, BlockPos pos, boolean ascend) {
                return entity.getY() + altitudeOffset > pos.getY() + shape.max(Direction.Axis.Y) - 1e-5;
            }
            @Override public boolean isHoldingItem(Item item) { return original.isHoldingItem(item); }
            @Override public boolean canStandOnFluid(FluidState a, FluidState b) { return original.canStandOnFluid(a, b); }
        };
    }

    private static final class Neighbors implements BlockGetter {
        private final Level level;
        private final CubeStorageChart chart;
        private Neighbors(Level level, CubeStorageChart chart) { this.level = level; this.chart = chart; }
        @Override public BlockState getBlockState(BlockPos pos) {
            CubeStorageChart owner = chart;
            var center = new SpaceVector(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (!chart.contains(center)) {
                owner = chart.ownerChart(chart.projectedGeographic(center)).orElse(null);
                if (owner == null) { return Blocks.BEDROCK.defaultBlockState(); }
                var mapped = new EarthChartTransform(chart, owner).position(center);
                pos = BlockPos.containing(mapped.x(), mapped.y(), mapped.z());
            }
            if (level instanceof ServerLevel server) {
                var target = server.getServer().getLevel(EarthWorlds.dimension(owner));
                var chunk = target == null ? null : target.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
            }
            if (owner.dimensionId().equals(level.dimension().location().toString())) {
                var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
            }
            var snapshot = ((PlanetaryLevelView) level).astra$boundary();
            if (snapshot != null) {
                long section = SectionPos.asLong(pos);
                for (var data : snapshot.sections()) {
                    if (data.chart().equals(owner) && data.section().asLong() == section) {
                        return Block.stateById(data.state(((pos.getY() & 15) * 16 + (pos.getZ() & 15)) * 16 + (pos.getX() & 15)));
                    }
                }
            }
            return Blocks.BEDROCK.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return chart.height(); }
        @Override public int getMinBuildHeight() { return chart.minY(); }
    }
}
