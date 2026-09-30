package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Immutable body-fixed surface kinematics in double meters and meters per game tick, retaining full orientation.
 * The body identity and simulation sample time belong to the caller. This value owns no world, tile, movement,
 * persistence or clock; calculations are worker-safe. Frame changes are coordinate representations of one pose,
 * not transfers of a Minecraft player or block grid. No orbital/spin transport velocity is included.
 */
public record PlanetaryPose(SpaceVector bodyPositionMeters, SpaceVector bodyVelocityMetersPerTick,
        FlightOrientation bodyOrientation) {
    public PlanetaryPose {
        requireValues(bodyPositionMeters, bodyVelocityMetersPerTick, bodyOrientation);
        if (bodyPositionMeters.length() == 0) {
            throw new IllegalArgumentException("Planetary pose position must lie outside the body's center");
        }
    }

    /**
     * Samples a patch's analytic position, exact differential velocity and tangent camera orientation.
     * The caller supplies local feet meters and host velocity in meters per game tick and enforces playable
     * bounds/side/thread policy. Null values, center/below-center altitude and nonrepresentable results fail.
     */
    public static PlanetaryPose fromPatch(SurfacePatch patch, SpaceVector localFeetMeters,
            SpaceVector localVelocityMetersPerTick, FlightOrientation localOrientation) {
        if (patch == null || localFeetMeters == null || localOrientation == null) {
            throw new IllegalArgumentException("Planetary pose requires a patch, feet position and orientation");
        }
        return new PlanetaryPose(patch.toBody(localFeetMeters),
                patch.toBodyVelocity(localFeetMeters, localVelocityMetersPerTick),
                patch.toBodyOrientation(localFeetMeters.x(), localFeetMeters.z(), localOrientation));
    }

    /**
     * Represents the same point, velocity and full orientation in an affine tangent frame. The local position
     * is not a gnomonic block address; a caller must retain the exact frame to invert it. Null frames fail.
     */
    public LocalPose inFrame(PlanetaryFrame frame) {
        requireFrame(frame);
        return new LocalPose(frame.toLocalPoint(bodyPositionMeters),
                frame.toLocalDirection(bodyVelocityMetersPerTick), frame.toLocalOrientation(bodyOrientation));
    }

    /**
     * Immutable affine-frame pose; position may be the frame origin. Units match {@link PlanetaryPose}.
     * A local pose has meaning only together with the exact frame used to create it; no implicit tile lookup
     * or frame ownership exists. Rebase through body-fixed space before interpolating different frames.
     */
    public record LocalPose(SpaceVector positionMeters, SpaceVector velocityMetersPerTick,
            FlightOrientation orientation) {
        public LocalPose {
            requireValues(positionMeters, velocityMetersPerTick, orientation);
        }

        /** Restores the body-fixed pose using its source frame, preserving roll; rejects null/invalid results. */
        public PlanetaryPose toBody(PlanetaryFrame frame) {
            requireFrame(frame);
            return new PlanetaryPose(frame.toBodyPoint(positionMeters), frame.toBodyDirection(velocityMetersPerTick),
                    frame.toBodyOrientation(orientation));
        }
    }

    private static void requireValues(SpaceVector position, SpaceVector velocity, FlightOrientation orientation) {
        if (position == null || velocity == null || orientation == null
                || !Double.isFinite(position.length()) || !Double.isFinite(velocity.length())) {
            throw new IllegalArgumentException("Planetary pose requires finite position, velocity and orientation");
        }
    }

    private static void requireFrame(PlanetaryFrame frame) {
        if (frame == null) { throw new IllegalArgumentException("Planetary pose requires a tangent frame"); }
    }
}
