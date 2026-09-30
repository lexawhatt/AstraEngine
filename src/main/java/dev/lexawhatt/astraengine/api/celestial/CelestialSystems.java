package dev.lexawhatt.astraengine.api.celestial;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CelestialOrbits;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightDynamics;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.List;

/** Pure custom-system construction and shared publication validation; no worlds or saved state are allocated. */
public final class CelestialSystems {
    public static final int MAX_CUSTOM_SYSTEMS = 64;
    public static final int MAX_BODIES = CosmosSystem.MAX_BODIES;
    public static final double MAX_GALAXY_COORDINATE_LIGHT_YEARS = 1_000_000;

    private CelestialSystems() {
    }

    /**
     * Creates a caller-owned, non-thread-safe draft. ID must be a custom namespace:single_segment, at most 64 ASCII
     * characters. Defaults are SINGLE kind, seed zero and galactic origin; no bodies are inserted automatically.
     */
    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    /**
     * Validates a complete custom descriptor for creation, storage and synchronization, on any thread.
     * Throws IllegalArgumentException for null, a reserved/noncanonical ID or unsupported bounds.
     * Galaxy coordinates are bounded per axis to +/-1e6 light-years. Every body's full parent-chain apoapsis plus its standard
     * observation margin must fit within the 4096-AU navigation sphere for all orbital phases.
     * This does not prove collision-free routes, assign gameplay permissions, or create a dimension.
     */
    public static void validateCustom(CosmosSystem system) {
        if (system == null || !CosmosIds.isCustom(system.id()) || system.bodies().size() > MAX_BODIES) {
            throw new IllegalArgumentException("A custom system requires a namespaced ID and 1..64 bodies");
        }
        SpaceVector galaxy = system.galaxyPosition();
        if (Math.abs(galaxy.x()) > MAX_GALAXY_COORDINATE_LIGHT_YEARS
                || Math.abs(galaxy.y()) > MAX_GALAXY_COORDINATE_LIGHT_YEARS
                || Math.abs(galaxy.z()) > MAX_GALAXY_COORDINATE_LIGHT_YEARS) {
            throw new IllegalArgumentException("Custom galaxy coordinates exceed +/-1e6 light-years per axis");
        }
        for (int index = 0; index < system.bodies().size(); index++) {
            CelestialBody body = system.bodies().get(index);
            // Match FlightDynamics.observation: a supernova primary takes precedence over body kind.
            double radii = index == 0 && system.kind() == CosmosSystem.Kind.SUPERNOVA ? 60
                    : body.kind() == CelestialBody.Kind.BLACK_HOLE ? 24 : body.kind() == CelestialBody.Kind.PULSAR ? 80
                    : body.ringOuterRatio() > 0 ? 8 : 4;
            double margin = Math.max(body.radiusMeters() * radii, 100_000);
            double apoapsis = CelestialOrbits.maximumDistance(system.bodies(), body);
            if (apoapsis + margin > FlightDynamics.LOCAL_RADIUS) {
                throw new IllegalArgumentException("Body observation exceeds the navigation boundary: " + body.id());
            }
        }
    }

    /** Mutable draft whose built results own immutable copies of the body list. */
    public static final class Builder {
        private final String id;
        private final String name;
        private long seed;
        private CosmosSystem.Kind kind = CosmosSystem.Kind.SINGLE;
        private SpaceVector galaxyPosition = SpaceVector.ZERO;
        private final List<CelestialBody> bodies = new ArrayList<>();

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        /** Sets the descriptor's deterministic visual seed; every long value is supported. */
        public Builder seed(long value) {
            seed = value;
            return this;
        }

        /** Sets the existing system template kind; this does not infer bodies or start resource evolution. */
        public Builder kind(CosmosSystem.Kind value) {
            kind = value;
            return this;
        }

        /** Sets finite galactic coordinates in light-years, each within +/-1e6 when built. */
        public Builder galaxyPositionLightYears(double x, double y, double z) {
            galaxyPosition = new SpaceVector(x, y, z);
            return this;
        }

        /** Adds one immutable descriptor in render/catalog order; the first body is the primary. Null is rejected. */
        public Builder body(CelestialBody body) {
            if (body == null || bodies.size() >= MAX_BODIES) {
                throw new IllegalArgumentException("A system draft accepts at most 64 non-null bodies");
            }
            bodies.add(body);
            return this;
        }

        /** Builds a validated immutable descriptor; duplicate local body IDs and invalid fields are rejected. */
        public CosmosSystem build() {
            CosmosSystem system = new CosmosSystem(id, name, seed, kind, galaxyPosition, bodies);
            validateCustom(system);
            return system;
        }
    }
}
