package dev.lexawhatt.astraengine.cosmos;

import java.util.List;

/** Pure system-local flight in meters/seconds; no astronomical position becomes a Minecraft coordinate. */
public final class FlightDynamics {
    private static final double AU = 149_597_870_700.0;
    /** Radius of local arrival regions and the publication envelope for celestial descriptors, in meters. */
    public static final double LOCAL_RADIUS = 4096 * AU;
    /** Maximum automatic body-approach speed and selected speed after manual system arrival, in meters/second. */
    public static final double LOCAL_MAX_SPEED = 10 * AU;
    /** Finite inspection-flight extent relative to the current system origin, in meters. */
    public static final double MAX_POSITION = 1_000_000 * CosmosGenerator.LIGHT_YEAR;
    public static final double MIN_SPEED = 1;
    public static final double MAX_SPEED = 10_000 * CosmosGenerator.LIGHT_YEAR;
    private static final double[] SPEEDS = {100, 10_000, 1_000_000, 100_000_000, 0.1 * AU, 10 * AU};
    private static final SpaceVector ZERO = new SpaceVector(0, 0, 0);

    private FlightDynamics() {}

    /** Immutable system-local state; velocity is bounded by the maximum continuous navigation speed. */
    public record State(SpaceVector position, SpaceVector velocity) {
        public State {
            if (position == null || velocity == null || position.length() > MAX_POSITION * 1.000001
                    || velocity.length() > MAX_SPEED * 1.000001) {
                throw new IllegalArgumentException("Flight position or velocity exceeds the navigation bounds");
            }
        }
    }

    /** Camera-relative free translation; positive strafe means local left, and vertical means local up. */
    public record Input(float forward, float strafe, float vertical, FlightOrientation orientation, boolean brake) {
        public Input {
            if (!axis(forward) || !axis(strafe) || !axis(vertical) || orientation == null) {
                throw new IllegalArgumentException("Invalid free-camera control input");
            }
        }
        /** Compatibility conversion for the former yaw/pitch-only contract, preserving its input bounds. */
        public Input(float forward, float strafe, float vertical, float yaw, float pitch, boolean brake) {
            this(forward, strafe, vertical, legacyOrientation(yaw, pitch), brake);
        }
        private static boolean axis(float value) { return Float.isFinite(value) && Math.abs(value) <= 1; }
    }

    /** Validates the former encoded yaw/pitch schema before migration into an unrestricted quaternion. */
    public static FlightOrientation legacyOrientation(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || yaw < -180 || yaw > 180 || !Float.isFinite(pitch) || Math.abs(pitch) > 89.9f) {
            throw new IllegalArgumentException("Invalid legacy flight orientation");
        }
        return FlightOrientation.fromAngles(yaw, pitch, 0);
    }

    /** System-local observation point and Minecraft-compatible view angles in degrees. */
    public record Observation(SpaceVector position, float yaw, float pitch) {
        public Observation {
            new State(position, ZERO);
            new Input(0, 0, 0, yaw, pitch, false);
        }
        /** Target-facing orientation with neutral roll at an explicit navigation arrival. */
        public FlightOrientation orientation() { return FlightOrientation.fromAngles(yaw, pitch, 0); }
    }

    /**
     * Frames a body from its illuminated side. Planets use two radii, rings eight, black holes twenty-four,
     * a supernova primary sixty and a pulsar eighty. No body size or orbital distance is changed for this framing.
     */
    public static Observation observation(CosmosSystem system, CelestialBody body, double seconds) {
        return observation(system, body, seconds, 0);
    }

    /**
     * Applies an additional minimum center distance in meters, for a caller-owned physical entry envelope.
     * This pure framing request never changes body radii or movement authority. Nonfinite, negative or
     * out-of-bounds minimum distances throw; ordinary framing remains unchanged when it is farther away.
     */
    public static Observation observation(CosmosSystem system, CelestialBody body, double seconds,
            double minimumCenterDistanceMeters) {
        if (system == null || body == null || !system.bodies().contains(body)
                || !Double.isFinite(seconds) || !Double.isFinite(minimumCenterDistanceMeters)
                || minimumCenterDistanceMeters < 0 || minimumCenterDistanceMeters > MAX_POSITION) {
            throw new IllegalArgumentException("Invalid body observation request");
        }
        boolean primary = body.id().equals(system.bodies().getFirst().id());
        boolean remnant = primary && system.kind() == CosmosSystem.Kind.SUPERNOVA;
        boolean blackHole = body.kind() == CelestialBody.Kind.BLACK_HOLE;
        boolean stellar = body.kind() == CelestialBody.Kind.STAR || body.kind() == CelestialBody.Kind.PULSAR
                || blackHole || remnant;
        double radii = remnant ? 60 : blackHole ? 24 : body.kind() == CelestialBody.Kind.PULSAR ? 80
                : body.ringOuterRatio() > 0 ? 8 : stellar ? 4 : 2;
        SpaceVector center = system.positionAt(body, seconds);
        SpaceVector observerDirection = new SpaceVector(0, 0.3, -1).normalized();
        if (!stellar) {
            CelestialBody source = system.bodies().stream().filter(value -> value.kind() == CelestialBody.Kind.STAR
                    || value.kind() == CelestialBody.Kind.PULSAR)
                    .findFirst().orElse(system.bodies().getFirst());
            SpaceVector towardSource = system.positionAt(source, seconds).subtract(center);
            if (towardSource.length() > 1) {
                double elevation = body.ringOuterRatio() > 0 ? 0.55 : 0.22;
                observerDirection = towardSource.normalized().add(new SpaceVector(0, elevation, 0)).normalized();
            }
        }
        SpaceVector position = center.add(observerDirection.multiply(
                Math.max(Math.max(body.radiusMeters() * radii, 100_000), minimumCenterDistanceMeters)));
        SpaceVector facing = observerDirection.multiply(-1);
        float yaw = (float) Math.toDegrees(Math.atan2(-facing.x(), facing.z()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(facing.y(), -1, 1)));
        return new Observation(position, yaw, pitch);
    }

    /** Historical version-one gears in meters per second, retained for exact save migration and callers. */
    public static double speed(int index) {
        if (index < 0 || index >= SPEEDS.length) { throw new IllegalArgumentException("Invalid flight speed index"); }
        return SPEEDS[index];
    }

    /** Number of historical navigation gears. New controls use continuous meters per second. */
    public static int speedCount() { return SPEEDS.length; }

    /** Validates continuous navigation speed in meters/second. */
    public static double validateSpeed(double metersPerSecond) {
        if (!Double.isFinite(metersPerSecond) || metersPerSecond < MIN_SPEED || metersPerSecond > MAX_SPEED) {
            throw new IllegalArgumentException("Free-camera speed must be finite and between 1 m/s and 10000 ly/s");
        }
        return metersPerSecond;
    }

    /** Nearest former gear, for compatibility diagnostics only; continuous speed remains authoritative. */
    public static int legacySpeedIndex(double metersPerSecond) {
        validateSpeed(metersPerSecond);
        int closest = 0;
        for (int i = 1; i < SPEEDS.length; i++) {
            if (Math.abs(Math.log(metersPerSecond / SPEEDS[i])) < Math.abs(Math.log(metersPerSecond / SPEEDS[closest]))) {
                closest = i;
            }
        }
        return closest;
    }

    /** Compatibility step for a former gear index, using free-camera movement semantics. */
    public static State step(State state, Input input, int speedIndex, double seconds,
            List<CelestialBody> bodies, double clockSeconds) {
        return step(state, input, speed(speedIndex), seconds, bodies, clockSeconds);
    }

    /** Advances at most 0.1 seconds; released input/brake immediately yields zero velocity, without inertia. */
    public static State step(State state, Input input, double speedMetersPerSecond, double seconds,
            List<CelestialBody> bodies, double clockSeconds) {
        return step(state, input, speedMetersPerSecond, seconds, bodies, clockSeconds, FlightDynamics::safeRadius);
    }

    /**
     * Server boundary adapter with explicit body collision radii in meters. A prepared solid-surface owner can
     * stop at its physical space boundary; stars and unsupported bodies retain their ordinary envelopes.
     * The synchronous resolver must return a finite radius at least as large as the canonical body radius.
     */
    public static State step(State state, Input input, double speedMetersPerSecond, double seconds,
            List<CelestialBody> bodies, double clockSeconds, java.util.function.ToDoubleFunction<CelestialBody> envelope) {
        if (envelope == null) { throw new IllegalArgumentException("A collision envelope resolver is required"); }
        if (state == null || input == null || bodies == null || bodies.size() > CosmosSystem.MAX_BODIES
                || !Double.isFinite(seconds) || seconds < 0 || seconds > 0.1
                || !Double.isFinite(clockSeconds)) {
            throw new IllegalArgumentException("Invalid free-camera simulation step");
        }
        validateSpeed(speedMetersPerSecond);
        if (seconds == 0) { return state; }
        SpaceVector velocity = desiredVelocity(input, speedMetersPerSecond);
        SpaceVector start = state.position();
        // Orbit motion can overtake an idle observer. Resolve penetration before the sweep.
        for (CelestialBody body : bodies) {
            SpaceVector center = CelestialOrbits.positionAt(bodies, body, clockSeconds);
            SpaceVector offset = start.subtract(center);
            double radius = envelope.applyAsDouble(body);
            validateEnvelope(body, radius);
            if (offset.length() < radius) {
                SpaceVector normal = offset.length() < 1 ? new SpaceVector(0, 0, -1) : offset.normalized();
                start = center.add(normal.multiply(radius + 1));
                velocity = ZERO;
            }
        }
        SpaceVector motion = velocity.multiply(seconds);
        double length = motion.length();
        double fraction = 1;
        if (length > 0) {
            SpaceVector direction = motion.multiply(1 / length);
            for (CelestialBody body : bodies) {
                SpaceVector relative = CelestialOrbits.positionAt(bodies, body, clockSeconds).subtract(start);
                double projection = dot(relative, direction);
                double radius = envelope.applyAsDouble(body);
                validateEnvelope(body, radius);
                SpaceVector perpendicular = relative.subtract(direction.multiply(projection));
                double perpendicularSquared = dot(perpendicular, perpendicular);
                if (projection < 0 || perpendicularSquared > radius * radius) { continue; }
                double entry = projection - Math.sqrt(radius * radius - perpendicularSquared);
                if (entry >= 0 && entry <= length) { fraction = Math.min(fraction, Math.max(0, entry - 1) / length); }
            }
        }
        SpaceVector position = start.add(motion.multiply(fraction));
        if (fraction < 1) { velocity = ZERO; }
        if (position.length() > MAX_POSITION) {
            position = position.normalized().multiply(MAX_POSITION);
            velocity = ZERO;
        }
        return new State(position, velocity);
    }

    /** Requested free-camera physical velocity before collision; released controls have no inertia. */
    public static SpaceVector desiredVelocity(Input input, double speedMetersPerSecond) {
        if (input == null) { throw new IllegalArgumentException("Flight input is required"); }
        validateSpeed(speedMetersPerSecond);
        var view = input.orientation();
        SpaceVector desired = view.forward().multiply(input.forward()).add(view.left().multiply(input.strafe()))
                .add(view.up().multiply(input.vertical()));
        return !input.brake() && desired.length() > .00001 ? desired.normalized().multiply(speedMetersPerSecond) : ZERO;
    }

    private static void validateEnvelope(CelestialBody body, double radius) {
        if (!Double.isFinite(radius) || radius < body.radiusMeters() || radius > MAX_POSITION) {
            throw new IllegalArgumentException("Collision envelope must contain its physical body");
        }
    }

    /** Collision envelope in system-local meters, shared by free flight and the local approach planner. */
    public static double safeRadius(CelestialBody body) {
        if (body == null) { throw new IllegalArgumentException("A collision body is required"); }
        return body.radiusMeters() * 1.03 + 10_000;
    }

    /**
     * Carries a nearby free observer by the nearest body's orbital translation, without rotating the view.
     * The bounded reference ends at six body radii. Epochs may run backward after a calendar command;
     * this is a change of reference, not a swept flight or a discovery. All coordinates use system meters.
     */
    public static SpaceVector followOrbitalMotion(SpaceVector position, List<CelestialBody> bodies,
            double previousSeconds, double currentSeconds) {
        return followOrbitalMotion(position, bodies, previousSeconds, currentSeconds, body -> body.radiusMeters() * 6);
    }

    /**
     * Updates the nearest local orbital reference within caller-supplied center distances in meters.
     * The resolver is used only during this pure call; finite ranges must enclose their physical bodies.
     * It allows a small solid body's surface-entry preparation region to remain in that body's reference.
     */
    public static SpaceVector followOrbitalMotion(SpaceVector position, List<CelestialBody> bodies,
            double previousSeconds, double currentSeconds, java.util.function.ToDoubleFunction<CelestialBody> range) {
        if (position == null || bodies == null || bodies.size() > CosmosSystem.MAX_BODIES
                || !Double.isFinite(previousSeconds) || !Double.isFinite(currentSeconds) || range == null) {
            throw new IllegalArgumentException("Invalid orbital reference update");
        }
        CelestialBody nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (CelestialBody body : bodies) {
            double distance = position.distance(CelestialOrbits.positionAt(bodies, body, previousSeconds));
            double radius = range.applyAsDouble(body);
            validateEnvelope(body, radius);
            if (distance <= radius && distance < nearestDistance) {
                nearest = body; nearestDistance = distance;
            }
        }
        return nearest == null ? position : position.add(CelestialOrbits.positionAt(bodies, nearest, currentSeconds)
                .subtract(CelestialOrbits.positionAt(bodies, nearest, previousSeconds)));
    }

    /**
     * Checks a linear camera segment against moving orbital envelopes during a finite forward interval.
     * A conservative acceleration allowance covers orbital curvature between endpoint samples.
     * This method never projects a blocked camera through an object or changes its position.
     */
    public static boolean clearSegment(SpaceVector start, SpaceVector end, List<CelestialBody> bodies,
            double startSeconds, double endSeconds) {
        return clearSegment(start, end, bodies, startSeconds, endSeconds, 0);
    }

    static boolean clearSegment(SpaceVector start, SpaceVector end, List<CelestialBody> bodies,
            double startSeconds, double endSeconds, double curveAllowance) {
        return clearSegment(start, end, bodies, startSeconds, endSeconds, curveAllowance, null, FlightDynamics::safeRadius);
    }

    /**
     * Tests all moving envelopes except the authorized landing body, retaining the full parent graph.
     * Only a prepared surface-route owner may bypass that body's ordinary free-flight envelope.
     */
    public static boolean clearSurfaceSegment(SpaceVector start, SpaceVector end, List<CelestialBody> bodies,
            double startSeconds, double endSeconds, String landingBodyId) {
        if (landingBodyId == null || bodies == null
                || bodies.stream().noneMatch(body -> body.id().equals(landingBodyId))) {
            throw new IllegalArgumentException("Landing collision exclusion requires a member body");
        }
        return clearSegment(start, end, bodies, startSeconds, endSeconds, 0, landingBodyId, FlightDynamics::safeRadius);
    }

    /** Tests moving bodies against explicit validated radii; no body is excluded from collision. */
    static boolean clearSegment(SpaceVector start, SpaceVector end, List<CelestialBody> bodies,
            double startSeconds, double endSeconds, double curveAllowance,
            java.util.function.ToDoubleFunction<CelestialBody> envelope) {
        return clearSegment(start, end, bodies, startSeconds, endSeconds, curveAllowance, null, envelope);
    }

    private static boolean clearSegment(SpaceVector start, SpaceVector end, List<CelestialBody> bodies,
            double startSeconds, double endSeconds, double curveAllowance, String landingBodyId,
            java.util.function.ToDoubleFunction<CelestialBody> envelope) {
        if (start == null || end == null || bodies == null || envelope == null || bodies.size() > CosmosSystem.MAX_BODIES
                || !Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)
                || !Double.isFinite(curveAllowance) || curveAllowance < 0
                || endSeconds < startSeconds
                || start.length() > MAX_POSITION || end.length() > MAX_POSITION) {
            throw new IllegalArgumentException("Invalid orbital collision segment");
        }
        double seconds = endSeconds - startSeconds;
        for (CelestialBody body : bodies) {
            if (body.id().equals(landingBodyId)) { continue; }
            SpaceVector relativeStart = start.subtract(CelestialOrbits.positionAt(bodies, body, startSeconds));
            SpaceVector relativeEnd = end.subtract(CelestialOrbits.positionAt(bodies, body, endSeconds));
            SpaceVector relativeMotion = relativeEnd.subtract(relativeStart);
            double squareLength = relativeMotion.dot(relativeMotion);
            double fraction = squareLength > 0
                    ? Math.clamp(-relativeStart.dot(relativeMotion) / squareLength, 0, 1) : 0;
            double allowance = CelestialOrbits.curvatureBound(bodies, body, seconds);
            double radius = envelope.applyAsDouble(body);
            validateEnvelope(body, radius);
            if (relativeStart.add(relativeMotion.multiply(fraction)).length() <= radius + allowance + curveAllowance) {
                return false;
            }
        }
        return true;
    }

    private static double dot(SpaceVector a, SpaceVector b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
}
