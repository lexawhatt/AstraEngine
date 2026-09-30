package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Pure, bounded resolution of parent-relative orbits and conservative whole-chain navigation envelopes. */
public final class CelestialOrbits {
    private CelestialOrbits() {
    }

    /** Resolves system-local meters without changing body order; missing parents and cycles throw. */
    public static SpaceVector positionAt(List<CelestialBody> bodies, CelestialBody body, double seconds) {
        requireBody(bodies, body);
        if (body.parentId().isEmpty()) { return body.positionAt(seconds); }
        SpaceVector position = SpaceVector.ZERO;
        CelestialBody current = body;
        for (int depth = 0; depth < bodies.size(); depth++) {
            position = position.add(current.positionAt(seconds));
            if (current.parentId().isEmpty()) {
                return position;
            }
            current = parent(bodies, current);
        }
        throw new IllegalArgumentException("Cyclic celestial parent chain: " + body.id());
    }

    /** Maximum distance from the system origin, including every ancestor's apoapsis, in meters. */
    public static double maximumDistance(List<CelestialBody> bodies, CelestialBody body) {
        return sum(bodies, body, value -> value.orbitMeters() * (1 + value.eccentricity()));
    }

    /** Conservative orbital displacement over a finite nonnegative interval, including all moving ancestors. */
    public static double displacementBound(List<CelestialBody> bodies, CelestialBody body, double seconds) {
        requireInterval(seconds);
        return sum(bodies, body, value -> {
            if (value.orbitalPeriodSeconds() == 0 || seconds == 0) { return 0; }
            double maximumSpeed = Math.PI * 2 / value.orbitalPeriodSeconds() * value.orbitMeters()
                    * Math.sqrt((1 + value.eccentricity()) / (1 - value.eccentricity()));
            return Math.min(value.orbitMeters() * (1 + value.eccentricity()) * 2, maximumSpeed * seconds);
        });
    }

    /** Bounds deviation from an endpoint chord over seconds, summing each orbit's acceleration envelope. */
    public static double curvatureBound(List<CelestialBody> bodies, CelestialBody body, double seconds) {
        requireInterval(seconds);
        return sum(bodies, body, value -> {
            if (value.orbitalPeriodSeconds() == 0 || seconds == 0) { return 0; }
            double frequency = Math.PI * 2 / value.orbitalPeriodSeconds();
            double acceleration = frequency * frequency * value.orbitMeters()
                    / Math.pow(1 - value.eccentricity(), 2);
            return Math.min(value.orbitMeters() * (1 + value.eccentricity()) * 2,
                    acceleration * seconds * seconds / 8);
        });
    }

    private static double sum(List<CelestialBody> bodies, CelestialBody body, ToDoubleFunction<CelestialBody> term) {
        requireBody(bodies, body);
        double result = 0;
        CelestialBody current = body;
        for (int depth = 0; depth < bodies.size(); depth++) {
            result += term.applyAsDouble(current);
            if (current.parentId().isEmpty()) {
                return result;
            }
            current = parent(bodies, current);
        }
        throw new IllegalArgumentException("Cyclic celestial parent chain: " + body.id());
    }

    private static CelestialBody parent(List<CelestialBody> bodies, CelestialBody child) {
        for (CelestialBody candidate : bodies) {
            if (candidate.id().equals(child.parentId())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Missing celestial parent " + child.parentId() + " for " + child.id());
    }

    private static void requireBody(List<CelestialBody> bodies, CelestialBody body) {
        if (bodies == null || bodies.isEmpty() || bodies.size() > CosmosSystem.MAX_BODIES || body == null
                || bodies.stream().anyMatch(value -> value == null) || !bodies.contains(body)) {
            throw new IllegalArgumentException("Orbital resolution requires a body in a complete list of 1..64 bodies");
        }
    }

    private static void requireInterval(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Orbital interval seconds must be finite and nonnegative");
        }
    }
}
