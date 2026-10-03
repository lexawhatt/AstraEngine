package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * Conservative host-voxel approximation of a neighboring chart's block shape. Bands translate exactly.
 * Face projection preserves straight horizontal edges and vertical lines. Its convex horizontal footprint
 * is rasterized into 1/64-meter cells; every included cell intersects the footprint, bounding horizontal
 * excess by sqrt(2)/64 meters (2.21 cm). There are no missing collision cells inside the physical shape.
 */
public final class ChartCollisionProjection {
    public static final double CELL_METERS = 1.0 / 64;
    public static final double MAX_EXCESS_METERS = Math.sqrt(2) * CELL_METERS;

    private ChartCollisionProjection() {}

    /** Immutable collision boxes in target host meters; ordinary block shapes up to16meters per axis only. */
    public static List<AABB> boxes(CubeStorageChart source, CubeStorageChart target, AABB box) {
        if (box == null || box.getXsize() > 16 || box.getYsize() > 16 || box.getZsize() > 16) {
            throw new IllegalArgumentException("Neighbor collision requires a bounded block shape");
        }
        var transform = new EarthChartTransform(source, target);
        if (source.face() == target.face()) {
            return List.of(box.move(0, source.altitudeOriginMeters() - target.altitudeOriginMeters(), 0));
        }
        var polygon = List.of(point(transform, box.minX, box.minY, box.minZ),
                point(transform, box.maxX, box.minY, box.minZ), point(transform, box.maxX, box.minY, box.maxZ),
                point(transform, box.minX, box.minY, box.maxZ));
        double minZ = polygon.stream().mapToDouble(Point::z).min().orElseThrow();
        double maxZ = polygon.stream().mapToDouble(Point::z).max().orElseThrow();
        long first = (long) Math.floor(minZ / CELL_METERS), last = (long) Math.ceil(maxZ / CELL_METERS);
        if (last - first > 4096) { throw new IllegalArgumentException("Neighbor collision projection exceeds its local envelope"); }
        double dy = source.altitudeOriginMeters() - target.altitudeOriginMeters();
        var result = new ArrayList<AABB>();
        for (long row = first; row < last; row++) {
            double z0 = row * CELL_METERS, z1 = z0 + CELL_METERS;
            var clipped = clip(clip(polygon, z0, true), z1, false);
            if (clipped.isEmpty()) { continue; }
            double x0 = clipped.stream().mapToDouble(Point::x).min().orElseThrow();
            double x1 = clipped.stream().mapToDouble(Point::x).max().orElseThrow();
            // All cells between these extrema intersect the convex strip; merge adjacent cells into one box.
            x0 = Math.floor(x0 / CELL_METERS) * CELL_METERS;
            x1 = Math.ceil(x1 / CELL_METERS) * CELL_METERS;
            if (x1 > x0) { result.add(new AABB(x0, box.minY + dy, z0, x1, box.maxY + dy, z1)); }
        }
        return List.copyOf(result);
    }

    private static Point point(EarthChartTransform transform, double x, double y, double z) {
        var mapped = transform.position(new SpaceVector(x, y, z));
        return new Point(mapped.x(), mapped.z());
    }

    private static List<Point> clip(List<Point> input, double edge, boolean keepAbove) {
        if (input.isEmpty()) { return input; }
        var output = new ArrayList<Point>();
        Point previous = input.getLast();
        boolean wasInside = keepAbove ? previous.z >= edge : previous.z <= edge;
        for (Point current : input) {
            boolean inside = keepAbove ? current.z >= edge : current.z <= edge;
            if (inside != wasInside) {
                double fraction = (edge - previous.z) / (current.z - previous.z);
                output.add(new Point(previous.x + (current.x - previous.x) * fraction, edge));
            }
            if (inside) { output.add(current); }
            previous = current; wasInside = inside;
        }
        return output;
    }

    private record Point(double x, double z) {}
}
