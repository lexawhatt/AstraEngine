package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosIds;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Immutable versioned surface realization for a solid celestial body. Radius and tilt retain the descriptor's
 * physical units. Rotation is a seeded presentation parameter (satellites use their orbital period), not an
 * ephemeris. Profiles own no levels, clocks or mutable caches and may be sampled on generation workers.
 */
public record SolidPlanetProfile(int version, String systemId, String bodyId, long seed, double radiusMeters,
        CelestialBody.Kind kind, double rotationSeconds, double axialTiltRadians, float atmosphereStrength) {
    /** Existing generic bodies retain v1. Only newly bound Sol Mars uses the explicit v2 material realization. */
    public static final int VERSION = 1;
    public static final int MARS_VERSION = 2;
    /** The host horizontal-coordinate envelope; unsupported larger solids retain their astronomical descriptor. */
    public static final double MAX_RADIUS_METERS = 29_000_000;

    public SolidPlanetProfile {
        if ((version != VERSION && (version != MARS_VERSION || !"sol".equals(systemId) || !"mars".equals(bodyId)))
                || systemId == null || !(systemId.matches("[a-z0-9_-]{1,64}")
                || CosmosIds.isCustom(systemId)) || bodyId == null || !bodyId.matches("[a-z0-9_-]{1,64}")
                || kind != CelestialBody.Kind.ROCKY && kind != CelestialBody.Kind.OCEAN && kind != CelestialBody.Kind.ICE
                || !Double.isFinite(radiusMeters) || radiusMeters < 16 || radiusMeters > MAX_RADIUS_METERS
                || !Double.isFinite(rotationSeconds) || rotationSeconds <= 0 || rotationSeconds > 1e15
                || !Double.isFinite(axialTiltRadians) || Math.abs(axialTiltRadians) > Math.PI
                || !Float.isFinite(atmosphereStrength) || atmosphereStrength < 0 || atmosphereStrength > 1) {
            throw new IllegalArgumentException("Invalid solid-planet surface profile");
        }
        if (systemId.equals("sol") && (bodyId.equals("uranus") || bodyId.equals("neptune"))) {
            throw new IllegalArgumentException("An ice giant has no exposed solid surface");
        }
    }

    /**
     * Creates the pinned realization of a member body. Absence means no supported solid surface, including gas
     * giants and the Solar ice giants whose legacy shader material is ICE. This does not allocate storage.
     */
    public static Optional<SolidPlanetProfile> create(CosmosSystem system, CelestialBody body) {
        if (system == null || body == null || !system.bodies().contains(body)) {
            throw new IllegalArgumentException("Surface creation requires the exact member descriptor");
        }
        if (body.kind() != CelestialBody.Kind.ROCKY && body.kind() != CelestialBody.Kind.OCEAN
                && body.kind() != CelestialBody.Kind.ICE || body.radiusMeters() < 16
                || body.radiusMeters() > MAX_RADIUS_METERS || system.id().equals("sol")
                && (body.id().equals("uranus") || body.id().equals("neptune"))) { return Optional.empty(); }
        long seed = mix(system.seed() ^ stableHash(system.id() + "/" + body.id()));
        double rotation = !body.parentId().isEmpty() && body.orbitalPeriodSeconds() > 0
                ? body.orbitalPeriodSeconds() : 18_000 + unit(seed) * 140_000;
        return Optional.of(new SolidPlanetProfile(system.id().equals("sol") && body.id().equals("mars") ? MARS_VERSION : VERSION,
                system.id(), body.id(), seed, body.radiusMeters(),
                body.kind(), rotation, body.axialTiltRadians(), body.atmosphere()));
    }

    /** Stable full SHA-256 body key used in permanent dimension paths; does not include runtime load order. */
    public String bindingKey() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((systemId + "/" + bodyId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime lacks required SHA-256 support", exception);
        }
    }

    /** Namespaced geography identity. Different saved realizations of the same body must never be substituted. */
    public String geographyId() { return "astraengine:planet/" + bindingKey() + "/v" + version; }

    /** Instantaneous physical frame at a finite orbital epoch; a changed descriptor rejects the binding. */
    public BodyFixedFrame frame(CosmosSystem system, double orbitalSeconds) {
        if (system == null || !Double.isFinite(orbitalSeconds) || !system.id().equals(systemId)) {
            throw new IllegalArgumentException("Surface frame requires the saved system and a finite epoch");
        }
        var body = system.bodies().stream().filter(value -> value.id().equals(bodyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Surface body is absent from its saved system"));
        if (create(system, body).map(value -> new SolidPlanetProfile(version, value.systemId(), value.bodyId(),
                value.seed(), value.radiusMeters(), value.kind(), value.rotationSeconds(), value.axialTiltRadians(),
                value.atmosphereStrength())).filter(this::equals).isEmpty()) {
            throw new IllegalArgumentException("Surface descriptor differs from its saved realization");
        }
        return BodyFixedFrame.of(system, body, orbitalSeconds,
                Math.IEEEremainder(orbitalSeconds, rotationSeconds) / rotationSeconds * Math.PI * 2);
    }

    /** Sol Mars presentation identity, independent of its saved terrain-material version. */
    public boolean mars() { return systemId.equals("sol") && bodyId.equals("mars"); }

    /** Whether the saved terrain includes the explicitly versioned oxidized surface materials. */
    public boolean oxidizedMars() { return mars() && version == MARS_VERSION; }

    /** Exponential density relative to the surface, a presentation observation rather than survival policy. */
    public double atmosphereDensity(double altitudeMeters) {
        if (!Double.isFinite(altitudeMeters)) { throw new IllegalArgumentException("Atmosphere altitude must be finite"); }
        return atmosphereStrength * Math.exp(-Math.max(0, altitudeMeters) / (mars() ? 10_800 : 2000 + atmosphereStrength * 8000));
    }

    private static long stableHash(String value) {
        long result = 0xcbf29ce484222325L;
        for (byte element : value.getBytes(StandardCharsets.UTF_8)) { result = (result ^ (element & 255)) * 0x100000001b3L; }
        return result;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static double unit(long value) { return (value >>> 11) * 0x1.0p-53; }
}
