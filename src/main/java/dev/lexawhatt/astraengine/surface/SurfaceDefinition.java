package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;

import java.util.Optional;

/**
 * Pinned, immutable first-generation surface bindings. Only Sol Earth and Moon own real patches; arbitrary
 * authored descriptors are never mutated or assigned a generated surface implicitly. No world references are held.
 */
public record SurfaceDefinition(String systemId, String bodyId, int version, long seed,
        SurfacePatch patch, SurfaceGeography geography) {
    public static final int VERSION = 1;
    private static final CosmosSystem SOL = CosmosGenerator.sol();
    private static final SurfaceDefinition MOON = create("moon", 0x4D4F4F4EL, SurfaceGeography.Kind.MOON);
    private static final SurfaceDefinition EARTH = create("earth", 0x45415254L, SurfaceGeography.Kind.EARTH);

    public SurfaceDefinition {
        if (!"sol".equals(systemId) || !("moon".equals(bodyId) || "earth".equals(bodyId))
                || version != VERSION || patch == null || geography == null || geography.version() != version
                || geography.seed() != seed) {
            throw new IllegalArgumentException("Invalid or unsupported persistent surface definition");
        }
        boolean moon = "moon".equals(bodyId);
        if (seed != (moon ? 0x4D4F4F4EL : 0x45415254L)
                || geography.kind() != (moon ? SurfaceGeography.Kind.MOON : SurfaceGeography.Kind.EARTH)
                || patch.radiusMeters() != solBody(bodyId).radiusMeters() || patch.latitudeRadians() != 0
                || patch.longitudeRadians() != 0 || patch.halfWidth() != 2048 || patch.seaY() != 64) {
            throw new IllegalArgumentException("Surface identity does not match its pinned version-one patch");
        }
    }

    /** Resolves a supported Sol body; unknown and null IDs fail without a generated fallback. */
    public static SurfaceDefinition byBody(String bodyId) {
        return find("sol", bodyId).orElseThrow(() -> new IllegalArgumentException("Unsupported surface body: " + bodyId));
    }

    /** Empty for an unsupported system/body, including custom systems which reuse a familiar local body ID. */
    public static Optional<SurfaceDefinition> find(String systemId, String bodyId) {
        if (!"sol".equals(systemId)) { return Optional.empty(); }
        return "moon".equals(bodyId) ? Optional.of(MOON) : "earth".equals(bodyId) ? Optional.of(EARTH) : Optional.empty();
    }

    /** First-air solid height at a local column, with sea level y=64. Outside the permanent patch throws. */
    public int terrainY(double x, double z) {
        if (!patch.contains(x, z)) { throw new IllegalArgumentException("Column is outside the permanent surface patch"); }
        return (int) Math.floor(patch.seaY() + geography.sample(patch.normal(x, z)).heightMeters());
    }

    /**
     * Body spin used by both ground and orbital views. Earth has a 24000-tick mean solar day plus the mean
     * physical-orbit increment. Moon uses its orbital mean rate, without claiming a measured libration model.
     * This does not accelerate or reset the existing orbital clock or the independent Overworld calendar.
     */
    public double spinRadians(double orbitalSeconds, double clockTicks) {
        if (!Double.isFinite(orbitalSeconds) || orbitalSeconds < 0 || !Double.isFinite(clockTicks)
                || clockTicks < 0 || clockTicks > 1_000_000_000_001.0) {
            throw new IllegalArgumentException("Surface spin requires finite nonnegative orbital time and occupied ticks");
        }
        CelestialBody body = solBody(bodyId);
        double orbit = Math.IEEEremainder(orbitalSeconds, body.orbitalPeriodSeconds())
                / body.orbitalPeriodSeconds() * Math.PI * 2;
        double daily = "earth".equals(bodyId) ? Math.IEEEremainder(clockTicks, 24000) / 24000 * Math.PI * 2 : 0;
        return Math.IEEEremainder(daily + orbit, Math.PI * 2);
    }

    /** Instantaneous parent-resolved frame for the exact supported system; consumes snapshots and owns no time. */
    public BodyFixedFrame frame(CosmosSystem system, double orbitalSeconds, double clockTicks) {
        CelestialBody body = requireBody(system);
        return BodyFixedFrame.of(system, body, orbitalSeconds, spinRadians(orbitalSeconds, clockTicks));
    }

    /**
     * Calendar-bound Sol frame. Earth takes the authoritative full rotation; Moon retains its synchronous
     * mean-orbit spin at the same signed orbital epoch. Null rotations and changed canonical descriptors fail.
     * The supplied orientation is a snapshot, not permission to reconfigure a server's celestial state.
     */
    public BodyFixedFrame calendarFrame(CosmosSystem system, double orbitalSeconds, FlightOrientation earthOrientation) {
        CelestialBody body = requireBody(system);
        if (earthOrientation == null || !Double.isFinite(orbitalSeconds)) {
            throw new IllegalArgumentException("A calendar surface frame requires finite epoch and Earth orientation");
        }
        if (bodyId.equals("earth")) {
            return new BodyFixedFrame(system.positionAt(body, orbitalSeconds), earthOrientation, body.radiusMeters());
        }
        double spin = Math.IEEEremainder(orbitalSeconds, body.orbitalPeriodSeconds())
                / body.orbitalPeriodSeconds() * Math.PI * 2;
        return BodyFixedFrame.of(system, body, orbitalSeconds, spin);
    }

    private CelestialBody requireBody(CosmosSystem system) {
        if (system == null || !systemId.equals(system.id())) {
            throw new IllegalArgumentException("Surface frame system does not match its persistent binding");
        }
        CelestialBody body = system.bodies().stream().filter(candidate -> candidate.id().equals(bodyId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Surface body is missing from its system"));
        if (!body.equals(solBody(bodyId))) {
            throw new IllegalArgumentException("Surface body does not match its permanent catalog binding");
        }
        return body;
    }

    private static SurfaceDefinition create(String bodyId, long seed, SurfaceGeography.Kind kind) {
        return new SurfaceDefinition("sol", bodyId, VERSION, seed,
                new SurfacePatch(solBody(bodyId).radiusMeters(), 0, 0, 2048, 64),
                new SurfaceGeography(VERSION, seed, kind));
    }

    private static CelestialBody solBody(String bodyId) {
        return SOL.bodies().stream().filter(body -> body.id().equals(bodyId)).findFirst().orElseThrow();
    }
}
