package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Arrays;
import java.util.Optional;

/**
 * Permanent inspection windows into one continental field. Host blocks remain one meter locally;
 * physical altitude is host Y plus altitudeOriginMeters. These windows do not stitch storage or gravity.
 * Immutable, worker-safe definitions are independent of loaded worlds and client resources.
 */
public enum ContinentalRegion {
    COAST("coast", -0.08572201151496467, -2.925633766709961, 16384, 0),
    ALPINE("alpine", -0.5452579002925138, -3.1402788132428276, 8192, 7168),
    ABYSS("abyss", -0.48544106217054683, -0.01221846648034751, 16384, -5120);

    public static final int MIN_Y = -2032;
    public static final int HEIGHT = 4064;
    public static final int REGION_VERSION = 1;
    private final String id;
    private final SurfacePatch patch;
    private final int altitudeOriginMeters;

    ContinentalRegion(String id, double latitude, double longitude, int halfWidth, int altitudeOriginMeters) {
        this.id = id;
        patch = new SurfacePatch(ContinentalTerrain.RADIUS_METERS, latitude, longitude, halfWidth, 0);
        this.altitudeOriginMeters = altitudeOriginMeters;
    }

    /** Stable region key within the version-one continental geography. */
    public String id() { return id; }
    /** Permanent namespaced dimension identity; unloading is never deletion. */
    public String dimensionId() { return "astraengine:continental_" + id; }
    /** The spherical horizontal mapping; its Y values are physical altitude, not this world's host Y. */
    public SurfacePatch patch() { return patch; }
    /** Physical meters above sea level at local host Y=0. Does not change radius or scale. */
    public int altitudeOriginMeters() { return altitudeOriginMeters; }
    /** Host coordinate of global sea level; it may lie outside this inspection window. */
    public int seaY() { return -altitudeOriginMeters; }

    /** Converts host meters to body-fixed meters without changing the permanent horizontal mapping. */
    public SpaceVector toBody(SpaceVector hostMeters) {
        if (hostMeters == null) { throw new IllegalArgumentException("Continental host position is required"); }
        return patch.toBody(new SpaceVector(hostMeters.x(), hostMeters.y() + altitudeOriginMeters, hostMeters.z()));
    }

    /** Inverts a body-fixed position into this window's host coordinates; callers must enforce its bounds. */
    public SpaceVector toLocal(SpaceVector bodyMeters) {
        SpaceVector physical = patch.toLocal(bodyMeters);
        return new SpaceVector(physical.x(), physical.y() - altitudeOriginMeters, physical.z());
    }

    /** First air above the physical surface, expressed in host Y; may be outside the storage window. */
    public int firstAir(ContinentalTerrain field, int x, int z) {
        if (field == null) { throw new IllegalArgumentException("Continental height field is required"); }
        return (int) Math.floor(field.sample(patch.normal(x + 0.5, z + 0.5)).heightMeters()) - altitudeOriginMeters;
    }

    /** Strict saved key lookup. Unknown or null keys fail instead of choosing a different region. */
    public static ContinentalRegion byId(String id) {
        return Arrays.stream(values()).filter(region -> region.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown continental region: " + id));
    }

    /** Read-only dimension lookup; unsupported or null identifiers have no continental binding. */
    public static Optional<ContinentalRegion> forDimension(String dimensionId) {
        return Arrays.stream(values()).filter(region -> region.dimensionId().equals(dimensionId)).findFirst();
    }
}
