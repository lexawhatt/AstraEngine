package dev.lexawhatt.astraengine.api.ship;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable analytic visual primitive, with no part identity, propulsion or engineering data.
 * Position and full size are local blocks; +Y is up and positive yaw maps +X toward -Z.
 * Centers are bounded to +/-128 blocks, sizes to [0.01,64] per axis, and linear RGB to [0,4].
 * Endpoint radius ratios are in (0,1], with at least one equal to 1; only frustums may taper.
 * Values are safe to construct on either logical side; rendering consumes client snapshots only.
 */
public record ShipVisualPart(Geometry geometry, Material material, SpaceVector position, SpaceVector size,
        double yawDegrees, double bottomRadiusRatio, double topRadiusRatio, SpaceVector color) {
    public ShipVisualPart {
        if (geometry == null || material == null || position == null || size == null || color == null
                || !Double.isFinite(yawDegrees) || !Double.isFinite(bottomRadiusRatio)
                || !Double.isFinite(topRadiusRatio) || bottomRadiusRatio <= 0 || bottomRadiusRatio > 1
                || topRadiusRatio <= 0 || topRadiusRatio > 1 || Math.max(bottomRadiusRatio, topRadiusRatio) != 1
                || Math.abs(position.x()) > 128 || Math.abs(position.y()) > 128 || Math.abs(position.z()) > 128
                || size.x() < 0.01 || size.y() < 0.01 || size.z() < 0.01
                || size.x() > 64 || size.y() > 64 || size.z() > 64
                || color.x() < 0 || color.x() > 4 || color.y() < 0 || color.y() > 4
                || color.z() < 0 || color.z() > 4) {
            throw new IllegalArgumentException("Ship visual primitive requires bounded geometry, transform and color");
        }
        if (geometry != Geometry.FRUSTUM && (bottomRadiusRatio != 1 || topRadiusRatio != 1)) {
            throw new IllegalArgumentException("Only visual frustums can taper their endpoint radius");
        }
        yawDegrees %= 360;
    }

    /** Conservative local-block box enclosing the primitive at arbitrary yaw. Not a gameplay collision shape. */
    public ShipBounds bounds() { return boundsForSlice(-0.5, 0.5, 1); }

    /**
     * Immutable conservative visual picking boxes: one for boxes/cylinders, three horizontal slices
     * for frustums. The consumer remains responsible for authoritative collisions and interaction.
     */
    public List<ShipBounds> pickingBounds() {
        if (geometry != Geometry.FRUSTUM) { return List.of(bounds()); }
        List<ShipBounds> slices = new ArrayList<>(3);
        for (int slice = 0; slice < 3; slice++) {
            double start = slice / 3.0;
            double end = (slice + 1) / 3.0;
            double startRadius = bottomRadiusRatio + (topRadiusRatio - bottomRadiusRatio) * start;
            double endRadius = bottomRadiusRatio + (topRadiusRatio - bottomRadiusRatio) * end;
            slices.add(boundsForSlice(start - 0.5, end - 0.5, Math.max(startRadius, endRadius)));
        }
        return List.copyOf(slices);
    }

    private ShipBounds boundsForSlice(double bottom, double top, double radius) {
        double angle = Math.toRadians(yawDegrees);
        double cosine = Math.abs(Math.cos(angle)), sine = Math.abs(Math.sin(angle));
        double halfX = (cosine * size.x() + sine * size.z()) * radius * 0.5;
        double halfZ = (sine * size.x() + cosine * size.z()) * radius * 0.5;
        return new ShipBounds(new SpaceVector(position.x() - halfX, position.y() + size.y() * bottom,
                position.z() - halfZ), new SpaceVector(position.x() + halfX, position.y() + size.y() * top,
                position.z() + halfZ));
    }

    /** Closed analytic solids implemented by the engine's current shader. */
    public enum Geometry { CYLINDER, FRUSTUM, BOX }

    /** Shader surface treatments only; these values do not imply gameplay components or capabilities. */
    public enum Material { PLAIN, PANEL, WINDOW, TANK, NOZZLE, SOLAR, RADIATOR }
}
