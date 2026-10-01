package dev.lexawhatt.astraengine.worldgen;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalTerrain;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.SubterraneanField;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Worker-local four-meter density sampling; only the host's current, not-yet-decorated chunk is modified. */
final class EarthCaveCarver {
    private static final int STEP = 4;
    private static final int[] CORNER_OFFSETS = {0, 1, 5, 6};
    private static final Set<Heightmap.Types> HEIGHTMAPS = Set.of(Heightmap.Types.WORLD_SURFACE_WG,
            Heightmap.Types.OCEAN_FLOOR_WG);

    private EarthCaveCarver() { }

    static void carve(ChunkAccess chunk, EarthChart chart, ContinentalTerrain terrain, SubterraneanField caves) {
        if (caves.version() == 0) { return; }
        int startX = chunk.getPos().getMinBlockX(), startZ = chunk.getPos().getMinBlockZ();
        if (Math.abs(startX + 8.0) > EarthChart.RADIUS_METERS || Math.abs(startZ + 8.0) > EarthChart.RADIUS_METERS) { return; }
        double[] heights = new double[256];
        boolean[] water = new boolean[256];
        SpaceVector[] normals = new SpaceVector[256];
        double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = x + z * 16;
                normals[index] = chart.normal(startX + x + .5, startZ + z + .5);
                var surface = terrain.sample(normals[index]);
                heights[index] = Math.floor(surface.heightMeters());
                water[index] = terrain.version() >= 3 && surface.water();
                lowest = Math.min(lowest, heights[index]); highest = Math.max(highest, heights[index]);
            }
        }
        int origin = chart.altitudeOriginMeters();
        int minY = Math.max(chunk.getMinBuildHeight(), (int) Math.floor(lowest - caves.maxDepthMeters()) - origin);
        int maxY = Math.min(chunk.getMaxBuildHeight(), (int) Math.ceil(highest) - origin);
        minY = Math.floorDiv(minY, STEP) * STEP;
        if (maxY <= minY) { return; }
        // Two horizontal planes are enough; storage and density work are bounded independently of world height.
        double[] low = new double[25], high = new double[25];
        plane(low, startX, startZ, minY, chart, caves);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int baseY = minY; baseY < maxY; baseY += STEP) {
            plane(high, startX, startZ, baseY + STEP, chart, caves);
            for (int cellZ = 0; cellZ < 4; cellZ++) {
                for (int cellX = 0; cellX < 4; cellX++) {
                    int corner = cellX + cellZ * 5;
                    if (maximum(low, high, corner) < -2) { continue; }
                    for (int dz = 0; dz < STEP; dz++) {
                        for (int dx = 0; dx < STEP; dx++) {
                            int x = cellX * STEP + dx, z = cellZ * STEP + dz, column = x + z * 16;
                            double a = horizontal(low, corner, dx / (double) STEP, dz / (double) STEP);
                            double b = horizontal(high, corner, dx / (double) STEP, dz / (double) STEP);
                            for (int dy = 0; dy < STEP && baseY + dy < maxY; dy++) {
                                int y = baseY + dy;
                                double altitude = y + origin + .5;
                                double density = a + (b - a) * dy / STEP;
                                if (Math.abs(density) < 2) {
                                    var normal = normals[column];
                                    double radius = EarthChart.RADIUS_METERS + altitude;
                                    density = caves.density(normal.x() * radius, normal.y() * radius, normal.z() * radius);
                                }
                                if (water[column] && altitude > heights[column] - 12) { continue; }
                                if (!caves.carves(density, heights[column], altitude)) { continue; }
                                position.set(startX + x, y, startZ + z);
                                var state = chunk.getBlockState(position);
                                if (!state.isAir() && state.getFluidState().isEmpty() && !state.is(Blocks.BEDROCK)) {
                                    chunk.setBlockState(position, Blocks.CAVE_AIR.defaultBlockState(), false);
                                }
                            }
                        }
                    }
                }
            }
            var swap = low; low = high; high = swap;
        }
        TerrainHeightmaps.prime(chunk, HEIGHTMAPS);
    }

    private static void plane(double[] values, int startX, int startZ, int y, EarthChart chart, SubterraneanField caves) {
        double radius = EarthChart.RADIUS_METERS + y + chart.altitudeOriginMeters() + .5;
        for (int z = 0; z <= 4; z++) {
            for (int x = 0; x <= 4; x++) {
                var normal = chart.normal(startX + x * STEP + .5, startZ + z * STEP + .5);
                values[x + z * 5] = caves.density(normal.x() * radius, normal.y() * radius, normal.z() * radius);
            }
        }
    }

    private static double maximum(double[] low, double[] high, int corner) {
        double maximum = -Double.MAX_VALUE;
        for (int offset : CORNER_OFFSETS) {
            maximum = Math.max(maximum, Math.max(low[corner + offset], high[corner + offset]));
        }
        return maximum;
    }

    private static double horizontal(double[] plane, int corner, double x, double z) {
        double near = plane[corner] + (plane[corner + 1] - plane[corner]) * x;
        double far = plane[corner + 5] + (plane[corner + 6] - plane[corner + 5]) * x;
        return near + (far - near) * z;
    }
}
