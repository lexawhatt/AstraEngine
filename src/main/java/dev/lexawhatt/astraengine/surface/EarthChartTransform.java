package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Double-precision change of Earth chart coordinates, including read-only extensions used to view neighboring
 * blocks. It grants no storage ownership. Charts must describe the same terrain version; points must lie in
 * the target face's outward hemisphere. Immutable, worker-safe, with no world or resource references.
 */
public record EarthChartTransform(EarthChart source, EarthChart target) {
    public EarthChartTransform {
        if (source == null || target == null || source.terrainVersion() != target.terrainVersion()) {
            throw new IllegalArgumentException("An Earth chart transform requires matching terrain identities");
        }
    }

    /** Maps chart meters without converting through latitude/longitude or narrowing absolute positions. */
    public SpaceVector position(SpaceVector sourceFeet) {
        SpaceVector plane = plane(sourceFeet);
        double forward = forward(plane);
        return new SpaceVector(plane.dot(target.face().u()) / forward * EarthChart.RADIUS_METERS,
                sourceFeet.y() + (source.band() - target.band()) * EarthChart.HEIGHT,
                plane.dot(target.face().v()) / forward * EarthChart.RADIUS_METERS);
    }

    /** Exact differential at source feet, retaining chart meters per game tick; this is not a rotation. */
    public SpaceVector velocity(SpaceVector sourceFeet, SpaceVector sourceVelocity) {
        if (sourceVelocity == null || !Double.isFinite(sourceVelocity.length())) {
            throw new IllegalArgumentException("Earth chart velocity must be finite");
        }
        SpaceVector plane = plane(sourceFeet);
        double forward = forward(plane);
        SpaceVector motion = source.face().u().multiply(sourceVelocity.x()).add(source.face().v().multiply(sourceVelocity.z()));
        double forwardMotion = motion.dot(target.face().outward());
        double scale = EarthChart.RADIUS_METERS / forward;
        return new SpaceVector(scale * (motion.dot(target.face().u()) - plane.dot(target.face().u()) / forward * forwardMotion),
                sourceVelocity.y(),
                scale * (motion.dot(target.face().v()) - plane.dot(target.face().v()) / forward * forwardMotion));
    }

    /** Rotates a complete tangent-frame view, preserving physical heading, pitch and roll. */
    public FlightOrientation orientation(SpaceVector sourceFeet, FlightOrientation sourceOrientation) {
        if (sourceOrientation == null) { throw new IllegalArgumentException("Earth chart orientation is required"); }
        SpaceVector destination = position(sourceFeet);
        double altitude = sourceFeet.y() + source.altitudeOriginMeters();
        var sourceFrame = source.tangentFrame(sourceFeet.x(), sourceFeet.z(), altitude);
        var targetFrame = target.tangentFrame(destination.x(), destination.z(), altitude);
        return targetFrame.toLocalOrientation(sourceFrame.toBodyOrientation(sourceOrientation));
    }

    private SpaceVector plane(SpaceVector feet) {
        if (feet == null || EarthChart.RADIUS_METERS + feet.y() + source.altitudeOriginMeters() <= 0) {
            throw new IllegalArgumentException("Earth chart position must lie outside the body center");
        }
        return source.face().outward().multiply(EarthChart.RADIUS_METERS)
                .add(source.face().u().multiply(feet.x())).add(source.face().v().multiply(feet.z()));
    }

    private double forward(SpaceVector plane) {
        double forward = plane.dot(target.face().outward());
        if (!Double.isFinite(forward) || forward <= 0) {
            throw new IllegalArgumentException("Earth chart point is outside the target hemisphere");
        }
        return forward;
    }
}
