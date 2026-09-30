package dev.lexawhatt.astraengine.api.celestial;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Pure creation helpers for the existing immutable celestial descriptors and their six built-in visual materials.
 * Builders are caller-owned mutable drafts, require no game thread, and must not be shared between threads.
 * Nothing is registered, saved or synchronized until a consumer submits a complete system through AstraCosmos.
 */
public final class CelestialBodies {
    private CelestialBodies() {
    }

    /** Creates a stationary star draft with its physical radius in meters and a white linear RGB color. */
    public static Builder star(String id, String name, double radiusMeters) {
        return new Builder(id, name, CelestialBody.Kind.STAR, radiusMeters);
    }

    /** Creates a stationary black-hole draft; radiusMeters is its physical horizon radius, not its visible shadow. */
    public static Builder blackHole(String id, String name, double radiusMeters) {
        return new Builder(id, name, CelestialBody.Kind.BLACK_HOLE, radiusMeters);
    }

    /**
     * Creates a planet draft using ROCKY, OCEAN, GAS_GIANT or ICE; stellar kinds and null are rejected.
     * Radius is in meters. The default orbit is stationary, with no atmosphere or rings and white linear RGB.
     */
    public static Builder planet(String id, String name, CelestialBody.Kind kind, double radiusMeters) {
        if (kind == null || kind == CelestialBody.Kind.STAR || kind == CelestialBody.Kind.BLACK_HOLE) {
            throw new IllegalArgumentException("A planet requires ROCKY, OCEAN, GAS_GIANT or ICE material");
        }
        return new Builder(id, name, kind, radiusMeters);
    }

    /**
     * Caller-owned body draft. Setters store values; build validates all fields together and returns a new immutable
     * value. Reusing or editing the builder never changes an earlier result. No implicit unit conversions occur.
     */
    public static final class Builder {
        private final String id;
        private final String name;
        private final CelestialBody.Kind kind;
        private final double radiusMeters;
        private double orbitMeters;
        private double orbitalPeriodSeconds;
        private double phaseRadians;
        private double inclinationRadians;
        private double eccentricity;
        private SpaceVector color = new SpaceVector(1, 1, 1);
        private float atmosphere;
        private float ringInnerRatio;
        private float ringOuterRatio;
        private double axialTiltRadians;
        private String parentId = "";

        private Builder(String id, String name, CelestialBody.Kind kind, double radiusMeters) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.radiusMeters = radiusMeters;
        }

        /** Sets semimajor axis in meters and period in seconds, relative to the parent or the system origin. */
        public Builder orbit(double semimajorAxisMeters, double periodSeconds) {
            orbitMeters = semimajorAxisMeters;
            orbitalPeriodSeconds = periodSeconds;
            return this;
        }

        /** Sets a local parent-body ID; empty selects the system origin. System construction validates the chain. */
        public Builder parent(String bodyId) {
            parentId = bodyId;
            return this;
        }

        /** Sets initial mean anomaly in radians, in [-2pi, 2pi]. */
        public Builder phaseRadians(double value) {
            phaseRadians = value;
            return this;
        }

        /** Sets orbital inclination about X in radians, in [-pi, pi]. */
        public Builder inclinationRadians(double value) {
            inclinationRadians = value;
            return this;
        }

        /** Sets orbital eccentricity in [0, 0.3], the existing bounded Kepler model's supported range. */
        public Builder eccentricity(double value) {
            eccentricity = value;
            return this;
        }

        /** Sets finite linear RGB components in [0, 1]; does not select a new shader material. */
        public Builder color(double red, double green, double blue) {
            color = new SpaceVector(red, green, blue);
            return this;
        }

        /** Sets the artistic atmosphere strength in [0, 1], without simulating gas or block lighting. */
        public Builder atmosphere(float value) {
            atmosphere = value;
            return this;
        }

        /** Sets inner/outer radii as body-radius ratios: both zero disables rings; otherwise 1 < inner < outer <= 10. */
        public Builder rings(float innerRatio, float outerRatio) {
            ringInnerRatio = innerRatio;
            ringOuterRatio = outerRatio;
            return this;
        }

        /** Sets axial tilt in radians, in [-pi, pi]; ring orientation uses this same existing descriptor field. */
        public Builder axialTiltRadians(double value) {
            axialTiltRadians = value;
            return this;
        }

        /**
         * Builds an immutable descriptor or throws IllegalArgumentException for invalid identity, units or ranges.
         * Body IDs are local lowercase [a-z0-9_-], 1..64 characters; names are nonblank, at most 96 characters.
         * Radius is 1..1e12 meters; orbital axis and period are 0..1e15 in their stated units.
         */
        public CelestialBody build() {
            return new CelestialBody(id, name, kind, radiusMeters, orbitMeters, orbitalPeriodSeconds,
                    phaseRadians, inclinationRadians, eccentricity, color, atmosphere, ringInnerRatio,
                    ringOuterRatio, axialTiltRadians, parentId);
        }
    }
}
