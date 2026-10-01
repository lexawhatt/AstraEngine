package dev.lexawhatt.astraengine.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fixed references derived from existing version-pinned surface definitions. These are geographic views of
 * existing storage, not a dimension allocator or consumer registration API. Ordinary Overworld is unbound
 * until it has an authoritative planetary generator; an Earth-sized prototype is not implicitly Sol Earth.
 */
public final class SurfaceReferences {
    private static final List<SurfaceReference> BUILT_INS = create();

    private SurfaceReferences() {}

    /** Immutable built-in references, safe to retain across client resource reloads or server restarts. */
    public static List<SurfaceReference> builtIns() { return BUILT_INS; }

    /** Read-only exact lookup. Unsupported and null IDs have no inferred geographic reference. */
    public static Optional<SurfaceReference> forDimension(String dimensionId) {
        return BUILT_INS.stream().filter(reference -> reference.dimensionId().equals(dimensionId)).findFirst();
    }

    private static List<SurfaceReference> create() {
        List<SurfaceReference> references = new ArrayList<>();
        for (String body : List.of("moon", "earth")) {
            var definition = SurfaceDefinition.byBody(body);
            references.add(reference("astraengine:sol/" + body + "/surface_v1",
                    "astraengine:surface_" + body, definition.patch(), 0));
        }
        references.add(new SurfaceReference("astraengine:highlands", PlanetaryTerrain.DIMENSION_ID,
                PlanetaryTerrain.PATCH, 0, PlanetaryTerrain.TOPOLOGY));
        for (ContinentalRegion region : ContinentalRegion.values()) {
            references.add(reference("astraengine:continental/v1", region.dimensionId(), region.patch(),
                    region.altitudeOriginMeters()));
        }
        return List.copyOf(references);
    }

    private static SurfaceReference reference(String geography, String dimension, SurfacePatch patch, int offset) {
        return new SurfaceReference(geography, dimension, patch, offset,
                new PlanetaryTopology(PlanetaryTopology.VERSION, 12, patch.radiusMeters()));
    }
}
