package dev.lexawhatt.astraengine.surface.orbit;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeFace;
import java.util.regex.Pattern;

/** Immutable identity of one canonical chart; contains no level, renderer or mutable world data. */
public record OrbitalSurface(String systemId, String bodyId, String dimensionId, CubeFace face,
                             double radiusMeters, int altitudeOriginMeters) {
    private static final Pattern SYSTEM_ID = Pattern.compile("[a-z0-9_:/.-]{1,128}");
    private static final Pattern BODY_ID = Pattern.compile("[a-z0-9_-]{1,128}");
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public OrbitalSurface {
        if (systemId == null || !SYSTEM_ID.matcher(systemId).matches() || bodyId == null
                || !BODY_ID.matcher(bodyId).matches() || dimensionId == null
                || !DIMENSION_ID.matcher(dimensionId).matches() || dimensionId.length() > 256
                || face == null || !Double.isFinite(radiusMeters) || radiusMeters <= 0
                || radiusMeters > 30_000_000 || Math.abs((long) altitudeOriginMeters) > 1_000_000) {
            throw new IllegalArgumentException("Invalid orbital surface identity");
        }
    }

    /** Body-fixed direction for chart coordinates in meters, including its projection extension. */
    public SpaceVector normal(double x, double z) {
        return face.outward().multiply(radiusMeters).add(face.u().multiply(x)).add(face.v().multiply(z)).normalized();
    }
}
