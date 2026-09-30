package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import java.util.Optional;

/** Pure swept arrival queries. Galactic descriptor positions use light-years; flight segments use local meters. */
public final class GalacticNavigation {
    private GalacticNavigation() {
    }

    /**
     * First charted system entered by a manual movement segment, excluding the current origin.
     * Position is rebased to the destination's origin in meters; fraction is the consumed segment in [0, 1].
     * This result does not grant discovery, mutate a pilot, or choose a body observation point.
     */
    public record Arrival(CosmosSystem system, SpaceVector position, double fraction) {
        public Arrival {
            if (system == null || position == null || !Double.isFinite(fraction) || fraction < 0 || fraction > 1
                    || position.length() > FlightDynamics.LOCAL_RADIUS * 1.000001) {
                throw new IllegalArgumentException("Invalid swept galactic arrival");
            }
        }
    }

    /**
     * Finds the earliest outside-to-inside crossing of a charted system's local arrival sphere.
     * At most 256 immutable descriptors are considered. A tangent counts as contact; ties use system ID.
     * Already-inside candidates, including an eight-ULP boundary tolerance, do not trigger. This prevents
     * repeated transfers between overlapping custom regions after the destination-local rebase.
     * No movement means no arrival. Inputs outside the finite flight envelope or null inputs are rejected.
     */
    public static Optional<Arrival> firstArrival(CosmosSystem origin, SpaceVector start, SpaceVector end,
            List<CosmosSystem> charted) {
        if (origin == null || start == null || end == null || charted == null || charted.size() > 256
                || start.length() > FlightDynamics.MAX_POSITION * 1.000001
                || end.length() > FlightDynamics.MAX_POSITION * 1.000001
                || charted.stream().anyMatch(system -> system == null)) {
            throw new IllegalArgumentException("Invalid galactic arrival segment or chart");
        }
        SpaceVector motion = end.subtract(start);
        double length = motion.length();
        if (length == 0) {
            return Optional.empty();
        }
        SpaceVector direction = motion.multiply(1 / length);
        Arrival closest = null;
        double closestEntry = Double.POSITIVE_INFINITY;
        for (CosmosSystem candidate : charted) {
            if (candidate.id().equals(origin.id())) {
                continue;
            }
            double radius = arrivalRadiusMeters(candidate);
            SpaceVector center = candidate.galaxyPosition().subtract(origin.galaxyPosition())
                    .multiply(CosmosGenerator.LIGHT_YEAR);
            SpaceVector relative = center.subtract(start);
            if (relative.length() <= radius + Math.ulp(radius) * 8) {
                continue;
            }
            double projection = relative.dot(direction);
            if (projection < 0) {
                continue;
            }
            SpaceVector perpendicular = relative.subtract(direction.multiply(projection));
            double perpendicularSquared = perpendicular.dot(perpendicular);
            if (perpendicularSquared > radius * radius) {
                continue;
            }
            double halfChord = Math.sqrt(Math.max(0, radius * radius - perpendicularSquared));
            double entry = projection - halfChord;
            if (entry < 0 || entry > length || entry > closestEntry
                    || entry == closestEntry && closest != null
                    && candidate.id().compareTo(closest.system().id()) >= 0) {
                continue;
            }
            // Galactic projection can leave a small residual along the direction in "perpendicular".
            // Normalize the small local offset back to the boundary so that residual cannot cause reentry.
            SpaceVector position = direction.multiply(-halfChord).subtract(perpendicular).normalized().multiply(radius);
            closest = new Arrival(candidate, position, entry / length);
            closestEntry = entry;
        }
        return Optional.ofNullable(closest);
    }

    /**
     * Arrival envelope in meters: 1.5 times the largest orbital collision extent, at least 64 AU and
     * at most the 4096-AU publication envelope. Normal generated systems are entered near their outer bodies.
     */
    public static double arrivalRadiusMeters(CosmosSystem system) {
        if (system == null) {
            throw new IllegalArgumentException("An arrival envelope requires a system");
        }
        double extent = system.bodies().stream().mapToDouble(body -> body.orbitMeters() * (1 + body.eccentricity())
                + FlightDynamics.safeRadius(body)).max().orElseThrow();
        return Math.min(FlightDynamics.LOCAL_RADIUS, Math.max(64 * CosmosGenerator.AU, extent * 1.5));
    }
}
