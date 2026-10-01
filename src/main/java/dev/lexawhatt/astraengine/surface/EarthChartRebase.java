package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * A canonical Earth storage representation of the same physical pose. Position is host chart meters;
 * velocity is chart meters per game tick, using the projection differential rather than a camera rotation.
 * Immutable numeric data only: resolution does not load chunks, move entities or authorize a transition.
 */
public record EarthChartRebase(EarthChart chart, SpaceVector feet, SpaceVector velocity,
        FlightOrientation orientation) {
    /** Maximum supported excursion past a source chart edge, in host chart meters. */
    public static final double MAX_EXTENSION_METERS = 128;

    public EarthChartRebase {
        if (chart == null || !chart.contains(feet) || velocity == null || orientation == null
                || !Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("Rebased Earth pose requires canonical feet and finite motion");
        }
    }

    /**
     * Worker-safe conversion from a source chart or its bounded extension into the unique saved owner.
     * Retains terrain version, radial altitude, physical velocity and complete view orientation. Canonical
     * source poses are returned unchanged. Empty means outside the supported extension or stored altitude;
     * callers must handle that outcome without inventing a destination. Null or invalid motion throws.
     * This method does not imply that a traversable route exists between source and destination blocks.
     */
    public static Optional<EarthChartRebase> resolve(EarthChart source, SpaceVector feet,
            SpaceVector velocity, FlightOrientation orientation) {
        if (source == null || feet == null || velocity == null || orientation == null
                || !Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("Earth rebasing requires a chart and finite complete pose");
        }
        if (source.contains(feet)) { return Optional.of(new EarthChartRebase(source, feet, velocity, orientation)); }
        double radius = EarthChart.RADIUS_METERS;
        if (Math.abs(feet.x()) > radius + MAX_EXTENSION_METERS || Math.abs(feet.z()) > radius + MAX_EXTENSION_METERS
                || feet.y() < EarthChart.MIN_Y - MAX_EXTENSION_METERS
                || feet.y() >= EarthChart.MIN_Y + EarthChart.HEIGHT + MAX_EXTENSION_METERS) {
            return Optional.empty();
        }
        // Compare the original local Y. Adding half a band can round nextDown(upper) into the next band.
        int shift = feet.y() < EarthChart.MIN_Y ? -1 : feet.y() >= EarthChart.MIN_Y + EarthChart.HEIGHT ? 1 : 0;
        int band = source.band() + shift;
        if (band < EarthChart.MIN_BAND || band > EarthChart.MAX_BAND) { return Optional.empty(); }
        SpaceVector up = source.normal(feet.x(), feet.z());
        var destination = new EarthChart(CubeFace.containing(up), band, source.terrainVersion());
        double y = feet.y() - shift * EarthChart.HEIGHT;
        if (source.face() == destination.face()) {
            return Optional.of(new EarthChartRebase(destination, new SpaceVector(feet.x(), y, feet.z()),
                    velocity, orientation));
        }
        var transform = new EarthChartTransform(source, destination);
        return Optional.of(new EarthChartRebase(destination, transform.position(feet), transform.velocity(feet, velocity),
                transform.orientation(feet, orientation)));
    }
}
