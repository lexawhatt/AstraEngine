package dev.lexawhatt.astraengine.cosmos;

import java.util.HashSet;
import java.util.List;

/**
 * Immutable celestial system. Galaxy position is in LIGHT-YEARS, while each body's local orbit is in meters.
 * The descriptor neither allocates a dimension nor owns saved evolution, player discovery, or rendering resources.
 */
public record CosmosSystem(String id, String name, long seed, Kind kind, SpaceVector galaxyPosition,
        List<CelestialBody> bodies) {
    public static final int MAX_BODIES = 64;
    public enum Kind {
        SINGLE, BINARY, BLACK_HOLE, SUPERNOVA
    }

    public CosmosSystem {
        if (id == null || !(id.matches("[a-z0-9_-]{1,64}") || CosmosIds.isCustom(id)) || name == null || name.isBlank()
                || name.length() > 96 || kind == null || galaxyPosition == null || bodies == null
                || bodies.isEmpty() || bodies.size() > MAX_BODIES || bodies.stream().anyMatch(body -> body == null)) {
            throw new IllegalArgumentException("Invalid cosmos system descriptor");
        }
        HashSet<String> ids = new HashSet<>();
        for (CelestialBody body : bodies) {
            if (!ids.add(body.id())) {
                throw new IllegalArgumentException("Duplicate celestial body ID: " + body.id());
            }
        }
        for (CelestialBody body : bodies) {
            // Traversing each bounded chain validates forward references as well as missing/cyclic parents.
            CelestialOrbits.maximumDistance(bodies, body);
        }
        bodies = List.copyOf(bodies);
    }

    /** System-local meters including the complete parent chain. The body must belong to this immutable system. */
    public SpaceVector positionAt(CelestialBody body, double seconds) {
        return CelestialOrbits.positionAt(bodies, body, seconds);
    }

    /** Resolves a local body identity to system-local meters; unknown/null IDs and nonfinite time throw. */
    public SpaceVector positionAt(String bodyId, double seconds) {
        for (CelestialBody body : bodies) {
            if (body.id().equals(bodyId)) {
                return positionAt(body, seconds);
            }
        }
        throw new IllegalArgumentException("Unknown celestial body ID: " + bodyId);
    }
}
