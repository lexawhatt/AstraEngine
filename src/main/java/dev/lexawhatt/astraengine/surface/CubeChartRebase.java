package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/** A no-load change to the canonical host representation of the same physical planetary pose. */
public record CubeChartRebase(CubeStorageChart chart, SpaceVector feet, SpaceVector velocity,
        FlightOrientation orientation) {
    public static final double MAX_EXTENSION_METERS = 128;

    public CubeChartRebase {
        if (chart == null || !chart.contains(feet) || velocity == null || orientation == null
                || !Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("A chart rebase requires a canonical complete pose");
        }
    }

    /** Numeric conversion only; an absent result does not authorize clipping or fabricated destination storage. */
    public static Optional<CubeChartRebase> resolve(CubeStorageChart source, SpaceVector feet,
            SpaceVector velocity, FlightOrientation orientation) {
        if (source == null || feet == null || velocity == null || orientation == null
                || !Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("Rebasing requires a chart and finite complete pose");
        }
        if (source.contains(feet)) { return Optional.of(new CubeChartRebase(source, feet, velocity, orientation)); }
        if (Math.abs(feet.x()) > source.radiusMeters() + MAX_EXTENSION_METERS
                || Math.abs(feet.z()) > source.radiusMeters() + MAX_EXTENSION_METERS
                || feet.y() < source.minY() - MAX_EXTENSION_METERS
                || feet.y() >= source.minY() + source.height() + MAX_EXTENSION_METERS
                || source.radiusMeters() + feet.y() + source.altitudeOriginMeters() <= 0) { return Optional.empty(); }
        int shift = feet.y() < source.minY() ? -1 : feet.y() >= source.minY() + source.height() ? 1 : 0;
        var target = source.chart(CubeFace.containing(source.normal(feet.x(), feet.z())), source.band() + shift).orElse(null);
        if (target == null) { return Optional.empty(); }
        var transform = new EarthChartTransform(source, target);
        var destination = transform.position(feet);
        // Exact face ties and the solid core can have no writable destination after rounding. Retain the
        // caller's last valid pose instead of throwing inside its ordinary movement callback.
        if (!target.contains(destination)) { return Optional.empty(); }
        return Optional.of(new CubeChartRebase(target, destination, transform.velocity(feet, velocity),
                transform.orientation(feet, orientation)));
    }
}
