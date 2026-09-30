package dev.lexawhatt.astraengine.cosmos;

import java.util.List;
import java.util.Random;

/**
 * Additive version-one pulsar landmarks, independent of legacy system, universe and satellite generation.
 * Each galaxy has one public p-index destination. Queries allocate immutable descriptors only and grant no visits.
 * This sparse authored catalog is not an observational population estimate or a stellar evolution simulation.
 */
public final class PulsarGenerator {
    public static final int VERSION = 1;

    private PulsarGenerator() {
    }

    /** Canonical p_0 through p_8 only. Namespaced custom IDs, signed indices and leading zeroes never alias them. */
    public static boolean isPulsarId(String id) {
        return id != null && id.length() == 3 && id.charAt(0) == 'p' && id.charAt(1) == '_'
                && id.charAt(2) >= '0' && id.charAt(2) < '0' + UniverseGenerator.GALAXY_COUNT;
    }

    /** Resolves the owning galaxy for a canonical pulsar identity, otherwise throws without generating content. */
    public static int galaxyIndex(String id) {
        if (!isPulsarId(id)) {
            throw new IllegalArgumentException("A canonical pulsar landmark identity is required: " + id);
        }
        return id.charAt(2) - '0';
    }

    /** Returns one deterministic compact primary in a galaxy's disk; galaxyIndex is in [0,8]. */
    public static CosmosSystem landmark(long universeSeed, int galaxyIndex) {
        GalaxyDescriptor galaxy = UniverseGenerator.galaxy(universeSeed, galaxyIndex);
        long seed = mix(galaxy.seed() ^ 0x50554c5341525631L);
        Random random = new Random(seed);
        double fraction = 0.42 + random.nextDouble() * 0.22;
        double armPhase = galaxy.armCount() > 0 ? Math.PI * 2 * random.nextInt(galaxy.armCount())
                / galaxy.armCount() : random.nextDouble() * Math.PI * 2;
        double angle = galaxy.armTwist() * Math.log(fraction) + armPhase;
        double radius = galaxy.radiusLightYears() * fraction;
        SpaceVector position = galaxy.toUniverseLightYears(new SpaceVector(Math.cos(angle) * radius,
                (random.nextDouble() - 0.5) * galaxy.thicknessLightYears() * 0.3, Math.sin(angle) * radius));
        String name = galaxy.name() + " Pulsar";
        CelestialBody primary = new CelestialBody("primary", name, CelestialBody.Kind.PULSAR,
                10_000 + random.nextDouble() * 4_000, 0, 0, 0, 0, 0,
                new SpaceVector(0.58, 0.8, 1), 0, 0, 0, 0.25 + random.nextDouble() * 0.7);
        return new CosmosSystem("p_" + galaxyIndex, name, seed, CosmosSystem.Kind.PULSAR, position, List.of(primary));
    }

    /** Recreates a canonical public destination from its catalog seed without saving, discovering or allocating worlds. */
    public static CosmosSystem byId(long universeSeed, String id) {
        return landmark(universeSeed, galaxyIndex(id));
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
