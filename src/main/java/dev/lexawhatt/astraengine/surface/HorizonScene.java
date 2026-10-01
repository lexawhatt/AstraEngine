package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Immutable physical-scale calibration geometry. The ocean world is opt-in and permanent; towers are visual
 * measuring targets, not blocks, entities or consumer gameplay. Host sea surface is Y=64 meters.
 */
public final class HorizonScene {
    public static final String DIMENSION_ID = "astraengine:horizon_ocean";
    public static final double RADIUS_METERS = 6_371_000;
    public static final double SEA_Y = 64;
    public static final double TOWER_HEIGHT_METERS = 120;
    public static final double TOWER_WIDTH_METERS = 200;
    public static final SurfacePatch PATCH = new SurfacePatch(RADIUS_METERS, 0, 0, 131072, (int) SEA_Y);

    private HorizonScene() { }

    /** Fixed patch-space target centers, in meters. Changing the camera never repositions the targets. */
    public static SpaceVector towerCenter(int index) {
        double distance = switch (index) {
            case 0 -> 12000;
            case 1 -> 30000;
            case 2 -> 60000;
            default -> throw new IllegalArgumentException("Horizon target index must be 0..2");
        };
        double bearing = (index - 1) * 0.24;
        return new SpaceVector(Math.sin(bearing) * distance, SEA_Y + TOWER_HEIGHT_METERS * 0.5,
                Math.cos(bearing) * distance);
    }
}
