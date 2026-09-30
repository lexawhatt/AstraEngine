package dev.lexawhatt.astraengine.cosmos;

/** A spatial galaxy feature tied to a real immutable navigation system; geometry uses absolute light-years. */
public record CosmicRegion(int index, String systemId, String name, Kind kind, SpaceVector centerLightYears,
        double radiusLightYears, SpaceVector color, double strength) {
    public enum Kind {
        NUCLEAR_CLUSTER, EMISSION_NEBULA, DARK_NEBULA, OPEN_CLUSTER, GLOBULAR_CLUSTER, SUPERNOVA_SHELL, QUASAR
    }

    public CosmicRegion {
        if (index < 0 || index >= UniverseGenerator.REGION_COUNT || !UniverseGenerator.isAtlasSystemId(systemId)
                || !systemId.endsWith("_" + index) || name == null || name.isBlank() || name.length() > 96
                || kind == null || centerLightYears == null || !Double.isFinite(radiusLightYears)
                || radiusLightYears <= 0 || radiusLightYears > 10_000 || color == null
                || color.x() < 0 || color.x() > 1 || color.y() < 0 || color.y() > 1
                || color.z() < 0 || color.z() > 1 || !Double.isFinite(strength) || strength < 0 || strength > 4) {
            throw new IllegalArgumentException("Invalid cosmic region descriptor");
        }
    }

    /** Smooth spherical influence in [0,1]; zero outside the named region, without changing its identity. */
    public double influence(SpaceVector universePosition) {
        if (universePosition == null) {
            throw new IllegalArgumentException("A cosmic region query requires a position");
        }
        double q = universePosition.distance(centerLightYears) / radiusLightYears;
        if (q >= 1) {
            return 0;
        }
        double remaining = 1 - q * q;
        return remaining * remaining;
    }
}
