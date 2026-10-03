package dev.lexawhatt.astraengine.client.flight;

import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.surface.BodyFixedFrame;

/** Connection-owned free look. Quaternion smoothing never constrains pitch or introduces a world-up axis. */
public final class FlightCamera {
    private static final double GUIDED_RADIANS_PER_SECOND = Math.PI;
    private FlightOrientation target = FlightOrientation.IDENTITY;
    private FlightOrientation orientation = FlightOrientation.IDENTITY;

    public FlightOrientation orientation() { return orientation; }
    public FlightOrientation target() { return target; }

    /** Carries the complete smoothed view with an occupied rotating body, preserving its local look and roll. */
    public void transport(BodyFixedFrame previous, BodyFixedFrame current) {
        if (previous == null || current == null) { throw new IllegalArgumentException("Camera transport requires both frames"); }
        if (previous.bodyToSystem().equals(current.bodyToSystem())) { return; }
        orientation = current.toSystemOrientation(previous.toBodyOrientation(orientation));
        target = current.toSystemOrientation(previous.toBodyOrientation(target));
    }

    /** Relocation and reconnect apply an authoritative pose immediately, without blending from the old view. */
    public void reset(FlightOrientation value) {
        if (value == null) { throw new IllegalArgumentException("Camera orientation is required"); }
        target = value; orientation = value;
    }

    /** Selects a presentation-only heading for normal free-camera smoothing, without moving the observer. */
    public void aim(FlightOrientation value) {
        if (value == null) { throw new IllegalArgumentException("Camera aim orientation is required"); }
        target = value;
    }

    /**
     * Follows a server-derived presentation pose at no more than 180 degrees per second.
     * Bounds catch-up after batched snapshots; it does not predict a route or mutate server state.
     * A stationary target settles exactly, allowing a continuous handoff to manual input.
     */
    public void follow(FlightOrientation value, double seconds) {
        if (value == null || !Double.isFinite(seconds) || seconds < 0 || seconds > 0.1) {
            throw new IllegalArgumentException("Invalid guided camera pose or frame interval");
        }
        target = value;
        if (seconds == 0 || orientation.equals(target)) { return; }
        double dot = Math.abs(orientation.x() * target.x() + orientation.y() * target.y()
                + orientation.z() * target.z() + orientation.w() * target.w());
        double angle = 2 * Math.acos(Math.clamp(dot, 0, 1));
        double step = GUIDED_RADIANS_PER_SECOND * seconds;
        orientation = angle <= step ? target : orientation.interpolate(target, step / angle);
    }

    /** Deltas are local degrees for this frame; seconds controls smoothing only, not mouse sensitivity. */
    public void update(double yawDegrees, double pitchDegrees, double rollDegrees, double seconds, float smoothing) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 0.1
                || !Float.isFinite(smoothing) || smoothing < 0 || smoothing > 0.95f) {
            throw new IllegalArgumentException("Invalid camera frame interval or smoothing");
        }
        target = target.rotateLocal(yawDegrees, pitchDegrees, rollDegrees);
        if (orientation.equals(target)) { return; }
        double blend = smoothing == 0 ? 1 : -Math.expm1(-seconds / (0.02 + smoothing * 0.16));
        orientation = orientation.interpolate(target, blend);
        // End sub-microdegree convergence exactly, so an idle saved view cannot drift in its last bits.
        double sign = orientation.x() * target.x() + orientation.y() * target.y()
                + orientation.z() * target.z() + orientation.w() * target.w() < 0 ? -1 : 1;
        double dx = orientation.x() - sign * target.x(), dy = orientation.y() - sign * target.y();
        double dz = orientation.z() - sign * target.z(), dw = orientation.w() - sign * target.w();
        if (dx * dx + dy * dy + dz * dz + dw * dw < 1e-16) { orientation = target; }
    }
}
