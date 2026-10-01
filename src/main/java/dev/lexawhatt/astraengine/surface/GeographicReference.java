package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * Immutable geographic interpretation of one permanent host storage chart. Implementations own no worlds or
 * mutable state and may be sampled on workers. Namespaced identities distinguish terrain realizations; matching
 * a radius alone never establishes that two charts represent the same planet. Host feet and velocities are in
 * chart coordinates; physical body-fixed values use double meters and meters per game tick.
 */
public interface GeographicReference {
    /** Stable namespaced terrain realization. */
    String geographyId();
    /** Permanent namespaced host world identity; does not allocate or assert that the world is loaded. */
    String dimensionId();
    /** Immutable logical tile topology for this geography. Tiles are not Minecraft chunk coordinates. */
    PlanetaryTopology topology();
    /** True for a finite host position owned by this chart; null is not contained. */
    boolean contains(SpaceVector feet);
    /** Geographic feet in radians and meters; invalid or outside positions throw. */
    GeographicPosition geographic(SpaceVector feet);
    /** No-load inverse; null throws, an address outside the chart returns absence without clipping. */
    Optional<SpaceVector> resolve(GeographicPosition geographic);
    /** Maps a contained host pose with the actual position differential; invalid values throw. */
    PlanetaryPose pose(SpaceVector feet, SpaceVector velocity, FlightOrientation orientation);
}
