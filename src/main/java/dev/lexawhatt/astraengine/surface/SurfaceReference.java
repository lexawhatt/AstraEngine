package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * Immutable geographic reference for one permanent host window. Host X/Z are patch meters; host Y plus
 * altitudeOffsetMeters is the patch Y. Geography identity distinguishes independent terrain realizations,
 * including Earth-sized prototypes. No worlds, chunks, renderer resources or player state are owned here.
 */
public record SurfaceReference(String geographyId, String dimensionId, SurfacePatch patch,
        int altitudeOffsetMeters, PlanetaryTopology topology) {
    public SurfaceReference {
        if (!namespaced(geographyId) || !namespaced(dimensionId) || patch == null || topology == null
                || patch.radiusMeters() != topology.radiusMeters()) {
            throw new IllegalArgumentException("Surface reference requires identities and matching geographic radii");
        }
    }

    /**
     * Reads geographic coordinates at host feet. Rejects null, a column outside this window or a position at/below
     * the body's center. Altitude is computed directly, avoiding subtraction of two astronomical radii.
     * The host storage height and world border are separate caller-owned constraints.
     */
    public GeographicPosition geographic(SpaceVector hostFeetMeters) {
        SpaceVector patchFeet = patchFeet(hostFeetMeters);
        if (!contains(hostFeetMeters)) {
            throw new IllegalArgumentException("Position is outside the geographic surface window");
        }
        GeographicPosition normal = GeographicPosition.fromBody(patch.normal(patchFeet.x(), patchFeet.z()), 1);
        return new GeographicPosition(normal.latitudeRadians(), normal.longitudeRadians(),
                patchFeet.y() - patch.seaY());
    }

    /** True only for a finite supplied host position inside the horizontal patch and above the body's center. */
    public boolean contains(SpaceVector hostFeetMeters) {
        if (hostFeetMeters == null) { return false; }
        double radial = patch.radiusMeters() + hostFeetMeters.y() + altitudeOffsetMeters - patch.seaY();
        return patch.contains(hostFeetMeters.x(), hostFeetMeters.z()) && Double.isFinite(radial) && radial > 0;
    }

    /**
     * Resolves a geographic address in this window, preserving physical altitude exactly. Returns absence for
     * the opposite hemisphere, an outside column or a nonpositive radial distance. Never clips or substitutes
     * a patch center. Null is invalid. This lookup neither allocates nor promises stored blocks at the result.
     */
    public Optional<SpaceVector> resolve(GeographicPosition geographic) {
        if (geographic == null) { throw new IllegalArgumentException("A geographic address is required"); }
        double radial = patch.radiusMeters() + geographic.altitudeMeters();
        if (!Double.isFinite(radial) || radial <= 0) { return Optional.empty(); }
        SpaceVector normal = geographic.normal();
        SpaceVector anchor = new GeographicPosition(patch.latitudeRadians(), patch.longitudeRadians(), 0).normal();
        if (normal.dot(anchor) <= 0) { return Optional.empty(); }
        // A unit direction suffices for the horizontal inverse. Do not recover altitude from its length.
        SpaceVector column = patch.toLocal(normal);
        double y = geographic.altitudeMeters() + patch.seaY() - altitudeOffsetMeters;
        if (!Double.isFinite(y)) { return Optional.empty(); }
        SpaceVector host = new SpaceVector(roundBoundary(column.x()), y, roundBoundary(column.z()));
        return contains(host) ? Optional.of(host) : Optional.empty();
    }

    private double roundBoundary(double column) {
        // Trigonometric inversion can put an exact edge a few radius ULPs outside.
        // Normalize only that representational error, never an outside geographic address.
        double tolerance = 16 * Math.ulp(patch.radiusMeters());
        return Math.abs(Math.abs(column) - patch.halfWidth()) <= tolerance
                ? Math.copySign(patch.halfWidth(), column) : column;
    }

    /** Maps a valid host feet position, stored meters/tick velocity and full orientation to body-fixed values. */
    public PlanetaryPose pose(SpaceVector feet, SpaceVector velocity, FlightOrientation orientation) {
        if (!contains(feet)) { throw new IllegalArgumentException("Pose is outside the geographic surface window"); }
        return PlanetaryPose.fromPatch(patch, patchFeet(feet), velocity, orientation);
    }

    private SpaceVector patchFeet(SpaceVector feet) {
        if (feet == null) { throw new IllegalArgumentException("Host feet position is required"); }
        return new SpaceVector(feet.x(), feet.y() + altitudeOffsetMeters, feet.z());
    }

    private static boolean namespaced(String id) {
        return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }
}
