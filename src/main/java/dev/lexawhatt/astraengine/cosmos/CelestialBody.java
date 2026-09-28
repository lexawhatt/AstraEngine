package dev.lexawhatt.astraengine.cosmos;

/**
 * Immutable shader-cosmos descriptor, independent of Minecraft or GPU lifetime.
 * Distances are meters, periods are seconds, angles are radians, and color is linear RGB in [0, 1].
 * Atmosphere and rings are artistic presentation parameters, not atmospheric simulations.
 * Orbits are independent fixed Kepler ellipses with a shared ascending node, not an ephemeris or N-body solver.
 */
public record CelestialBody(String id, String name, Kind kind, double radiusMeters, double orbitMeters,
        double orbitalPeriodSeconds, double phaseRadians, double inclinationRadians, double eccentricity,
        SpaceVector color, float atmosphere, float ringInnerRatio, float ringOuterRatio, double axialTiltRadians) {
    private static final double TWO_PI = Math.PI * 2;

    public enum Kind {
        STAR, BLACK_HOLE, ROCKY, OCEAN, GAS_GIANT, ICE
    }

    public CelestialBody {
        if (id == null || !id.matches("[a-z0-9_-]{1,64}") || name == null || name.isBlank()
                || name.length() > 96 || kind == null || color == null) {
            throw new IllegalArgumentException("Invalid celestial body identity or material");
        }
        bounded(radiusMeters, 1, 1.0e12, "Body radius");
        bounded(orbitMeters, 0, 1.0e15, "Orbit semimajor axis");
        bounded(orbitalPeriodSeconds, 0, 1.0e15, "Orbital period");
        if ((orbitMeters == 0) != (orbitalPeriodSeconds == 0)) {
            throw new IllegalArgumentException("A stationary body must have both zero orbit and zero period");
        }
        bounded(phaseRadians, -TWO_PI, TWO_PI, "Mean anomaly phase");
        bounded(inclinationRadians, -Math.PI, Math.PI, "Orbit inclination");
        bounded(eccentricity, 0, 0.3, "Orbit eccentricity");
        bounded(axialTiltRadians, -Math.PI, Math.PI, "Axial tilt");
        bounded(atmosphere, 0, 1, "Atmosphere strength");
        bounded(color.x(), 0, 1, "Red color");
        bounded(color.y(), 0, 1, "Green color");
        bounded(color.z(), 0, 1, "Blue color");
        if (orbitMeters > 0 && orbitMeters * (1 - eccentricity) <= radiusMeters) {
            throw new IllegalArgumentException("Body orbit must clear its own radius");
        }
        bounded(ringInnerRatio, 0, 10, "Ring inner ratio");
        bounded(ringOuterRatio, 0, 10, "Ring outer ratio");
        if ((ringInnerRatio != 0 || ringOuterRatio != 0)
                && (ringInnerRatio <= 1 || ringOuterRatio <= ringInnerRatio)) {
            throw new IllegalArgumentException("Rings must lie outside the body with increasing radii");
        }
    }

    /**
     * Position in local system meters at a finite elapsed time. The XZ plane is the reference plane;
     * inclination rotates about X. A stationary primary is at the origin. Ten bounded Newton steps
     * solve Kepler's equation for eccentricities <= 0.3; no elapsed simulation state is changed.
     */
    public SpaceVector positionAt(double seconds) {
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Orbital elapsed seconds must be finite");
        }
        if (orbitMeters == 0) {
            return SpaceVector.ZERO;
        }
        // Reduce before multiplying by 2pi so long sessions do not overflow angular arithmetic.
        double meanAnomaly = Math.IEEEremainder(seconds, orbitalPeriodSeconds)
                / orbitalPeriodSeconds * TWO_PI + phaseRadians;
        double eccentricAnomaly = meanAnomaly;
        for (int step = 0; step < 10; step++) {
            eccentricAnomaly -= (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly)
                    / (1 - eccentricity * Math.cos(eccentricAnomaly));
        }
        double x = orbitMeters * (Math.cos(eccentricAnomaly) - eccentricity);
        double z = orbitMeters * Math.sqrt(1 - eccentricity * eccentricity) * Math.sin(eccentricAnomaly);
        return new SpaceVector(x, z * Math.sin(inclinationRadians), z * Math.cos(inclinationRadians));
    }

    private static void bounded(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(label + " must be finite and in [" + min + ", " + max + "]");
        }
    }
}
