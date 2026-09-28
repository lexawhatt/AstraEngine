package dev.lexawhatt.astraengine.cosmos;

import java.util.HashSet;
import java.util.List;

/**
 * Immutable celestial system. Galaxy position is in LIGHT-YEARS, while each body's local orbit is in meters.
 * The descriptor neither allocates a dimension nor owns saved evolution, player discovery, or rendering resources.
 */
public record CosmosSystem(String id, String name, long seed, Kind kind, SpaceVector galaxyPosition,
        List<CelestialBody> bodies) {
    public enum Kind {
        SINGLE, BINARY, BLACK_HOLE, SUPERNOVA
    }

    public CosmosSystem {
        if (id == null || !(id.matches("[a-z0-9_-]{1,64}") || CosmosIds.isCustom(id)) || name == null || name.isBlank()
                || name.length() > 96 || kind == null || galaxyPosition == null || bodies == null
                || bodies.isEmpty() || bodies.size() > 12 || bodies.stream().anyMatch(body -> body == null)) {
            throw new IllegalArgumentException("Invalid cosmos system descriptor");
        }
        HashSet<String> ids = new HashSet<>();
        for (CelestialBody body : bodies) {
            if (!ids.add(body.id())) {
                throw new IllegalArgumentException("Duplicate celestial body ID: " + body.id());
            }
        }
        bodies = List.copyOf(bodies);
    }
}
