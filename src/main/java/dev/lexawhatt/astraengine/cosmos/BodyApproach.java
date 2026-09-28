package dev.lexawhatt.astraengine.cosmos;

import java.util.Optional;

/**
 * Immutable, bounded local camera route in double meters. Planning and sampling are pure;
 * the server session owns elapsed ticks and checks each actual movement segment again.
 * Routes predict the existing observation point at arrival and do not alter celestial descriptors.
 */
public final class BodyApproach {
    public static final int MAX_TICKS = 72_000;
    public static final int AIM_TICKS = 40;
    private static final int PLANNING_SEGMENTS = 256;
    private final CosmosSystem system;
    private final CelestialBody body;
    private final SpaceVector start;
    private final SpaceVector control;
    private final SpaceVector destination;
    private final FlightOrientation initialOrientation;
    private final double startSeconds;
    private final int durationTicks;
    private final double logarithm;
    private final FlightOrientation[] orientationGuide;

    private BodyApproach(CosmosSystem system, CelestialBody body, SpaceVector start, SpaceVector control,
            SpaceVector destination, FlightOrientation initialOrientation, double startSeconds, int durationTicks) {
        this.system = system;
        this.body = body;
        this.start = start;
        this.control = control;
        this.destination = destination;
        this.initialOrientation = initialOrientation;
        this.startSeconds = startSeconds;
        this.durationTicks = durationTicks;
        logarithm = Math.clamp(Math.log1p(start.distance(destination) / Math.max(body.radiusMeters() * 3, 100_000)),
                0.25, 24);
        orientationGuide = null;
    }

    private BodyApproach(BodyApproach checked) {
        system = checked.system;
        body = checked.body;
        start = checked.start;
        control = checked.control;
        destination = checked.destination;
        initialOrientation = checked.initialOrientation;
        startSeconds = checked.startSeconds;
        durationTicks = checked.durationTicks;
        logarithm = checked.logarithm;
        // Only a collision-checked candidate allocates the bounded, immutable orientation guide.
        orientationGuide = buildOrientationGuide();
    }

    /** Position/velocity in meters and meters/second, with the authoritative target-facing orientation. */
    public record Frame(FlightDynamics.State state, FlightOrientation orientation) {
        public Frame {
            if (state == null || orientation == null) {
                throw new IllegalArgumentException("An approach frame requires state and orientation");
            }
        }
    }

    /**
     * Plans a finite route using at most nine quadratic candidates and eight timing refinements each.
     * Empty means no checked route fits the collision, speed, position or one-hour duration bounds.
     * Malformed arguments throw; expected geometric refusal does not mutate the caller's state.
     */
    public static Optional<BodyApproach> plan(CosmosSystem system, CelestialBody body, FlightDynamics.State state,
            FlightOrientation orientation, double startSeconds) {
        if (system == null || body == null || !system.bodies().contains(body) || state == null || orientation == null
                || !Double.isFinite(startSeconds) || startSeconds < 0) {
            throw new IllegalArgumentException("A local approach requires valid system, target, navigation and time");
        }
        if (state.position().length() > FlightDynamics.MAX_POSITION
                || !FlightDynamics.clearSegment(state.position(), state.position(), system.bodies(),
                        startSeconds, startSeconds)) {
            return Optional.empty();
        }
        for (int candidate = 0; candidate < 9; candidate++) {
            int duration = 240;
            BodyApproach route = null;
            for (int refinement = 0; refinement < 8; refinement++) {
                FlightDynamics.Observation arrival;
                try {
                    arrival = FlightDynamics.observation(system, body, startSeconds + duration / 20.0);
                } catch (IllegalArgumentException unreachable) {
                    break;
                }
                SpaceVector destination = arrival.position();
                SpaceVector delta = destination.subtract(state.position());
                SpaceVector midpoint = state.position().add(delta.multiply(0.5));
                SpaceVector control = midpoint;
                if (candidate > 0 && delta.length() > 1) {
                    SpaceVector direction = delta.normalized();
                    SpaceVector axis = Math.abs(direction.y()) < 0.8
                            ? new SpaceVector(0, 1, 0) : new SpaceVector(1, 0, 0);
                    SpaceVector normal = axis.subtract(direction.multiply(axis.dot(direction))).normalized();
                    SpaceVector second = cross(direction, normal);
                    double angle = (candidate - 1) * Math.PI / 4;
                    control = midpoint.add(normal.multiply(Math.cos(angle) * delta.length() * 0.7))
                            .add(second.multiply(Math.sin(angle) * delta.length() * 0.7));
                }
                // A Bezier curve stays in its control hull, making this a conservative position-bound check.
                if (control.length() > FlightDynamics.MAX_POSITION
                        || destination.length() > FlightDynamics.MAX_POSITION) {
                    break;
                }
                BodyApproach proposed = new BodyApproach(system, body, state.position(), control, destination,
                        orientation, startSeconds, duration);
                double curveDerivative = 2 * Math.max(state.position().distance(control),
                        control.distance(destination));
                double minimumSeconds = curveDerivative * maximumProgressDerivative(proposed.logarithm)
                        / FlightDynamics.LOCAL_MAX_SPEED;
                double travelSeconds = Math.max(7 + proposed.logarithm * 0.35, minimumSeconds * 1.001);
                int required = AIM_TICKS + (int) Math.ceil(travelSeconds * 20);
                if (required > MAX_TICKS) {
                    break;
                }
                if (duration < required) {
                    duration = required;
                    continue;
                }
                route = proposed;
                break;
            }
            if (route != null && route.clear()) {
                return Optional.of(new BodyApproach(route));
            }
        }
        return Optional.empty();
    }

    /** Bounded total occupied ticks, including the initial stationary aiming interval. */
    public int durationTicks() {
        return durationTicks;
    }

    /** Stable target-body identity within the route's owning system descriptor. */
    public String bodyId() {
        return body.id();
    }

    /** System ephemeris time in seconds at planning; elapsed route ticks advance from this reference. */
    public double startSeconds() {
        return startSeconds;
    }

    /**
     * Exact deterministic state at an integer route tick in [0,durationTicks]. The view follows
     * the target by transporting local up through the route; arrival does not reset roll to world up.
     */
    public Frame frame(int elapsedTicks) {
        if (elapsedTicks < 0 || elapsedTicks > durationTicks) {
            throw new IllegalArgumentException("Approach time is outside its route");
        }
        SpaceVector position = position(elapsedTicks);
        SpaceVector velocity = elapsedTicks == 0 || elapsedTicks == durationTicks ? SpaceVector.ZERO
                : position.subtract(position(elapsedTicks - 1)).multiply(20);
        FlightOrientation view = viewAt(elapsedTicks);
        return new Frame(new FlightDynamics.State(position, velocity), view);
    }

    private FlightOrientation viewAt(int elapsedTicks) {
        if (elapsedTicks == 0) {
            return initialOrientation;
        }
        FlightOrientation guided = orientationAt(elapsedTicks);
        if (elapsedTicks >= AIM_TICKS) {
            return guided;
        }
        FlightOrientation firstAim = orientationGuide[0];
        FlightOrientation aim = initialOrientation.interpolate(firstAim,
                smooth(elapsedTicks / (double) AIM_TICKS));
        FlightOrientation inverseAim = new FlightOrientation(-firstAim.x(), -firstAim.y(),
                -firstAim.z(), firstAim.w());
        // Choose the aiming arc once. Recomputing its shortest side toward a moving target can
        // switch branches around a half-turn; continuous guide transport supplies the later motion.
        FlightOrientation transported = multiply(guided, inverseAim);
        return multiply(transported, aim);
    }

    private SpaceVector position(double elapsedTicks) {
        if (elapsedTicks <= AIM_TICKS) {
            return start;
        }
        if (elapsedTicks >= durationTicks) {
            return destination;
        }
        double progress = progress(elapsedTicks);
        double inverse = 1 - progress;
        // Endpoint-relative offsets avoid rounding a small route through large absolute coordinates.
        // Switching anchors also keeps the last few radii precise after an astronomical traversal.
        if (progress <= 0.5) {
            return start.add(control.subtract(start).multiply(2 * inverse * progress))
                    .add(destination.subtract(start).multiply(progress * progress));
        }
        return destination.add(control.subtract(destination).multiply(2 * inverse * progress))
                .add(start.subtract(destination).multiply(inverse * inverse));
    }

    private boolean clear() {
        SpaceVector previous = start;
        double previousTick = 0;
        double previousProgress = 0;
        double curvature = start.subtract(control.multiply(2)).add(destination).length();
        for (int sample = 1; sample <= PLANNING_SEGMENTS; sample++) {
            double tick = durationTicks * sample / (double) PLANNING_SEGMENTS;
            SpaceVector next = position(tick);
            double progress = progress(tick);
            double curveAllowance = curvature * Math.pow(progress - previousProgress, 2) / 4;
            // The easing traverses each chord nonlinearly in time. Cover obstacle movement over the
            // full interval, in addition to the geometric bow, before using a linear sweep as a bound.
            double timingAllowance = 0;
            for (CelestialBody obstacle : system.bodies()) {
                if (obstacle.orbitalPeriodSeconds() == 0) {
                    continue;
                }
                double maximumSpeed = Math.PI * 2 / obstacle.orbitalPeriodSeconds() * obstacle.orbitMeters()
                        * Math.sqrt((1 + obstacle.eccentricity()) / (1 - obstacle.eccentricity()));
                double displacement = Math.min(obstacle.orbitMeters() * (1 + obstacle.eccentricity()) * 2,
                        maximumSpeed * (tick - previousTick) / 20);
                timingAllowance = Math.max(timingAllowance, displacement);
            }
            if (!FlightDynamics.clearSegment(previous, next, system.bodies(), startSeconds + previousTick / 20,
                    startSeconds + tick / 20, curveAllowance + timingAllowance)) {
                return false;
            }
            previous = next;
            previousTick = tick;
            previousProgress = progress;
        }
        return true;
    }

    private double progress(double elapsedTicks) {
        double time = Math.clamp((elapsedTicks - AIM_TICKS) / (durationTicks - AIM_TICKS), 0, 1);
        return -Math.expm1(-logarithm * smooth(time)) / -Math.expm1(-logarithm);
    }

    private FlightOrientation[] buildOrientationGuide() {
        FlightOrientation[] guide = new FlightOrientation[PLANNING_SEGMENTS + 1];
        guide[0] = turnToward(initialOrientation, targetDirection(0));
        for (int sample = 1; sample < guide.length; sample++) {
            double elapsedTicks = durationTicks * sample / (double) PLANNING_SEGMENTS;
            guide[sample] = turnToward(guide[sample - 1], targetDirection(elapsedTicks));
        }
        return guide;
    }

    private FlightOrientation orientationAt(int elapsedTicks) {
        int sample = Math.min(PLANNING_SEGMENTS - 1,
                (int) (elapsedTicks * (double) PLANNING_SEGMENTS / durationTicks));
        return turnToward(orientationGuide[sample], targetDirection(elapsedTicks));
    }

    private SpaceVector targetDirection(double elapsedTicks) {
        return body.positionAt(startSeconds + elapsedTicks / 20.0).subtract(position(elapsedTicks));
    }

    private static FlightOrientation turnToward(FlightOrientation from, SpaceVector toward) {
        if (toward.length() == 0) {
            return from;
        }
        SpaceVector forward = from.forward().normalized();
        SpaceVector target = toward.normalized();
        double cosine = Math.clamp(forward.dot(target), -1, 1);
        SpaceVector axis = cross(forward, target);
        FlightOrientation rotation;
        if (axis.length() < 1e-12) {
            if (cosine > 0) {
                return from;
            }
            // An exact half-turn has no unique shortest axis. Preserve the camera's own up.
            SpaceVector up = from.up().normalized();
            rotation = new FlightOrientation(up.x(), up.y(), up.z(), 0);
        } else {
            rotation = FlightOrientation.normalized(axis.x(), axis.y(), axis.z(), 1 + cosine);
        }
        // World-space minimal rotation precedes the existing camera-local orientation.
        return multiply(rotation, from);
    }

    private static FlightOrientation multiply(FlightOrientation left, FlightOrientation right) {
        return FlightOrientation.normalized(
                left.w() * right.x() + left.x() * right.w() + left.y() * right.z() - left.z() * right.y(),
                left.w() * right.y() - left.x() * right.z() + left.y() * right.w() + left.z() * right.x(),
                left.w() * right.z() + left.x() * right.y() - left.y() * right.x() + left.z() * right.w(),
                left.w() * right.w() - left.x() * right.x() - left.y() * right.y() - left.z() * right.z());
    }

    private static double smooth(double value) {
        return value * value * (3 - 2 * value);
    }

    private static double maximumProgressDerivative(double logarithm) {
        // The derivative has one maximum before t=0.5. Its logarithmic derivative is monotone there.
        double low = 0;
        double high = 0.5;
        for (int iteration = 0; iteration < 48; iteration++) {
            double middle = (low + high) * 0.5;
            double derivative = 1 / middle - 1 / (1 - middle) - logarithm * 6 * middle * (1 - middle);
            if (derivative > 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double time = (low + high) * 0.5;
        return logarithm * 6 * time * (1 - time) * Math.exp(-logarithm * smooth(time)) / -Math.expm1(-logarithm);
    }

    private static SpaceVector cross(SpaceVector a, SpaceVector b) {
        return new SpaceVector(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }
}
