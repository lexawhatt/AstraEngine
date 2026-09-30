package dev.lexawhatt.astraengine.cosmos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Additive version-one universe atlas and density-conditioned galaxy-local population.
 * Legacy Sol/s-sector generation remains version one and unchanged. Queries allocate only bounded immutable data.
 */
public final class UniverseGenerator {
    public static final int VERSION = 1;
    public static final int GALAXY_COUNT = 9;
    public static final int REGION_COUNT = 7;
    public static final double SECTOR_LIGHT_YEARS = 4;
    public static final SpaceVector MILKY_WAY_CENTER_LIGHT_YEARS = new SpaceVector(26_000, 0, 0);
    private static final double YEAR_SECONDS = 365.256 * 86_400;
    private static final String[] GALAXY_NAMES = {
        "Milky Way", "Aster Veil", "Cinder Ellipse", "Neris Drift", "Vela Crown",
        "Iona Quasar", "Talos Cloud", "Caelum Reach", "Lumen Halo"
    };

    private UniverseGenerator() {
    }

    /** Nine stable public galaxy descriptors; viewing this atlas does not chart or visit their systems. */
    public static List<GalaxyDescriptor> galaxies(long seed) {
        List<GalaxyDescriptor> result = new ArrayList<>(GALAXY_COUNT);
        for (int index = 0; index < GALAXY_COUNT; index++) {
            result.add(galaxy(seed, index));
        }
        return List.copyOf(result);
    }

    /** Explicit descriptor-list spelling for integrations displaying the same bounded atlas. */
    public static List<GalaxyDescriptor> galaxyDescriptors(long seed) {
        return galaxies(seed);
    }

    /** Stable geometry for one atlas entry; index is in [0,8]. MW retains the previous center and radius. */
    public static GalaxyDescriptor galaxy(long seed, int index) {
        requireGalaxy(index);
        long galaxySeed = mix(seed ^ 0x554e495645525345L ^ mix(index));
        if (index == 0) {
            return new GalaxyDescriptor(0, "g_0", GALAXY_NAMES[0], galaxySeed, GalaxyDescriptor.Kind.SPIRAL,
                    MILKY_WAY_CENTER_LIGHT_YEARS, FlightOrientation.IDENTITY, 50_000, 700, 1600, 4, 1.8, false);
        }
        Random random = new Random(galaxySeed);
        double phase = Math.floorMod(mix(seed), 4096) * (Math.PI * 2 / 4096);
        double angle = phase + (index - 1) * Math.PI / 4 + (random.nextDouble() - 0.5) * 0.08;
        double distance = 360_000 + random.nextDouble() * 30_000;
        SpaceVector center = MILKY_WAY_CENTER_LIGHT_YEARS.add(new SpaceVector(Math.cos(angle) * distance,
                (random.nextDouble() - 0.5) * 80_000, Math.sin(angle) * distance));
        GalaxyDescriptor.Kind kind = GalaxyDescriptor.Kind.values()[(index - 1) % 3];
        double radius = switch (kind) {
            case SPIRAL -> 28_000 + random.nextDouble() * 37_000;
            case ELLIPTICAL -> 18_000 + random.nextDouble() * 27_000;
            case IRREGULAR -> 8000 + random.nextDouble() * 14_000;
        };
        double thickness = radius * switch (kind) {
            case SPIRAL -> 0.025;
            case ELLIPTICAL -> 0.55;
            case IRREGULAR -> 0.18;
        };
        double core = radius * (kind == GalaxyDescriptor.Kind.ELLIPTICAL ? 0.1 : 0.035);
        FlightOrientation orientation = FlightOrientation.fromAngles(random.nextDouble() * 360,
                20 + random.nextDouble() * 140, random.nextDouble() * 360);
        int armCount = kind == GalaxyDescriptor.Kind.SPIRAL ? 2 + random.nextInt(3) : 0;
        double armTwist = kind == GalaxyDescriptor.Kind.SPIRAL ? 1.3 + random.nextDouble() * 1.2
                : 2.6 + random.nextDouble() * 2.4;
        return new GalaxyDescriptor(index, "g_" + index, GALAXY_NAMES[index], galaxySeed, kind, center, orientation,
                radius, thickness, core, armCount, armTwist, index == 5);
    }

    /** Seven stable spatial landmarks per galaxy; every systemId resolves to a real local body descriptor. */
    public static List<CosmicRegion> regions(long seed, int galaxyIndex) {
        return regions(galaxy(seed, galaxyIndex));
    }

    private static List<CosmicRegion> regions(GalaxyDescriptor galaxy) {
        double radius = galaxy.radiusLightYears();
        double core = galaxy.coreRadiusLightYears();
        List<CosmicRegion> result = new ArrayList<>(REGION_COUNT);
        result.add(region(galaxy, 0, "Nucleus", galaxy.activeNucleus() ? CosmicRegion.Kind.QUASAR
                : CosmicRegion.Kind.NUCLEAR_CLUSTER, SpaceVector.ZERO, Math.max(24, core * 0.08),
                new SpaceVector(0.8, 0.87, 1), galaxy.activeNucleus() ? 2.2 : 0));
        result.add(region(galaxy, 1, "Emission Veil", CosmicRegion.Kind.EMISSION_NEBULA,
                diskPoint(galaxy, 0.58, 0, 0.2), Math.max(80, radius * 0.006), new SpaceVector(1, 0.2, 0.46), 1.1));
        result.add(region(galaxy, 2, "Dark Rift", CosmicRegion.Kind.DARK_NEBULA,
                diskPoint(galaxy, 0.48, 1, 0.1), Math.max(100, radius * 0.008), new SpaceVector(0.14, 0.1, 0.18), 1.4));
        result.add(region(galaxy, 3, "Open Cluster", CosmicRegion.Kind.OPEN_CLUSTER,
                diskPoint(galaxy, 0.36, 2, 0.1), Math.max(18, radius * 0.001), new SpaceVector(0.5, 0.72, 1), 1));
        result.add(region(galaxy, 4, "Halo Cluster", CosmicRegion.Kind.GLOBULAR_CLUSTER,
                new SpaceVector(radius * 0.32, radius * 0.28, -radius * 0.22),
                Math.max(30, radius * 0.0015), new SpaceVector(1, 0.75, 0.4), 1.3));
        result.add(region(galaxy, 5, "Supernova Remnant", CosmicRegion.Kind.SUPERNOVA_SHELL,
                diskPoint(galaxy, 0.68, 3, 0.12), Math.max(8, radius * 0.0003), new SpaceVector(0.3, 0.72, 1), 1.2));
        result.add(region(galaxy, 6, "Nuclear Cluster", CosmicRegion.Kind.NUCLEAR_CLUSTER,
                new SpaceVector(core * 0.2, core * 0.08, -core * 0.1),
                Math.clamp(core * 0.03, 15, 60), new SpaceVector(1, 0.84, 0.55), 1.2));
        return List.copyOf(result);
    }

    private static CosmicRegion region(GalaxyDescriptor galaxy, int index, String suffix, CosmicRegion.Kind kind,
            SpaceVector local, double radius, SpaceVector color, double strength) {
        return new CosmicRegion(index, landmarkId(galaxy.index(), index), galaxy.name() + " " + suffix, kind,
                galaxy.toUniverseLightYears(local), radius, color, strength);
    }

    private static SpaceVector diskPoint(GalaxyDescriptor galaxy, double fraction, int arm, double height) {
        double angle = galaxy.armTwist() * Math.log(fraction)
                + arm * Math.PI * 2 / (galaxy.armCount() > 0 ? galaxy.armCount() : 3);
        double radius = galaxy.radiusLightYears() * fraction;
        return new SpaceVector(Math.cos(angle) * radius, galaxy.thicknessLightYears() * height,
                Math.sin(angle) * radius);
    }

    /** Real navigable systems corresponding one-to-one with the galaxy's public landmarks, in region order. */
    public static List<CosmosSystem> landmarkSystems(long seed, int galaxyIndex) {
        GalaxyDescriptor galaxy = galaxy(seed, galaxyIndex);
        return regions(galaxy).stream().map(region -> landmark(galaxy, region)).toList();
    }

    /** Resolves all built-in identities; canonical but unpopulated v-sector identities return empty. */
    public static Optional<CosmosSystem> find(long seed, String id) {
        ParsedId parsed = parse(id);
        if (parsed != null) {
            GalaxyDescriptor galaxy = galaxy(seed, parsed.galaxy());
            List<CosmicRegion> regions = regions(galaxy);
            return parsed.landmark() ? Optional.of(landmark(galaxy, regions.get(parsed.region())))
                    : sector(galaxy, regions, parsed.x(), parsed.y(), parsed.z());
        }
        if (CosmosIds.isLegacyBuiltin(id)) {
            return Optional.of(CosmosGenerator.byId(seed, id));
        }
        throw new IllegalArgumentException("Unsupported built-in universe system ID: " + id);
    }

    /** Requires a populated built-in descriptor; malformed and canonical empty-sector IDs throw without mutation. */
    public static CosmosSystem byId(long seed, String id) {
        return find(seed, id).orElseThrow(() -> new IllegalArgumentException("Unpopulated universe sector: " + id));
    }

    /** Canonical u-landmark or v-sector syntax only; this never generates or grants access to a system. */
    public static boolean isUniverseId(String id) {
        return parse(id) != null;
    }

    /** True only for the bounded public atlas's u-landmark identities, never arbitrary sectors or custom systems. */
    public static boolean isAtlasSystemId(String id) {
        ParsedId parsed = parse(id);
        return parsed != null && parsed.landmark();
    }

    /** Owning galaxy index for a canonical u/v identity; legacy and custom identities are rejected. */
    public static int galaxyIndex(String systemId) {
        ParsedId parsed = parse(systemId);
        if (parsed == null) {
            throw new IllegalArgumentException("A universe system identity is required");
        }
        return parsed.galaxy();
    }

    /**
     * Reveals no state: queries a bounded neighborhood for a current descriptor. Legacy and custom contexts retain
     * the original absolute s-sector query; u/v contexts use only their galaxy's density-conditioned v population.
     */
    public static List<CosmosSystem> nearby(long seed, CosmosSystem current, int radiusSectors) {
        if (current == null) {
            throw new IllegalArgumentException("A neighborhood query requires a current system");
        }
        ParsedId parsed = parse(current.id());
        return parsed == null ? CosmosGenerator.nearby(seed, current.galaxyPosition(), radiusSectors)
                : nearby(seed, parsed.galaxy(), current.galaxyPosition(), radiusSectors);
    }

    /**
     * Same bounded population query for renderer/map extraction at an absolute light-year position.
     * Radius 0..2 examines at most 125 galaxy-local four-light-year sectors; empty sectors allocate no bodies.
     */
    public static List<CosmosSystem> nearby(long seed, int galaxyIndex, SpaceVector absoluteLightYears, int radiusSectors) {
        if (absoluteLightYears == null || radiusSectors < 0 || radiusSectors > 2) {
            throw new IllegalArgumentException("A universe neighborhood requires a position and sector radius 0..2");
        }
        GalaxyDescriptor galaxy = galaxy(seed, galaxyIndex);
        List<CosmicRegion> regions = regions(galaxy);
        SpaceVector local = galaxy.toLocalLightYears(absoluteLightYears);
        int x = sectorCoordinate(local.x());
        int y = sectorCoordinate(local.y());
        int z = sectorCoordinate(local.z());
        List<CosmosSystem> systems = new ArrayList<>();
        for (long sx = (long) x - radiusSectors; sx <= (long) x + radiusSectors; sx++) {
            for (long sy = (long) y - radiusSectors; sy <= (long) y + radiusSectors; sy++) {
                for (long sz = (long) z - radiusSectors; sz <= (long) z + radiusSectors; sz++) {
                    if (sx >= Integer.MIN_VALUE && sx <= Integer.MAX_VALUE && sy >= Integer.MIN_VALUE
                            && sy <= Integer.MAX_VALUE && sz >= Integer.MIN_VALUE && sz <= Integer.MAX_VALUE) {
                        sector(galaxy, regions, (int) sx, (int) sy, (int) sz).ifPresent(systems::add);
                    }
                }
            }
        }
        systems.sort(Comparator.comparingDouble((CosmosSystem system) -> system.galaxyPosition().distance(absoluteLightYears))
                .thenComparing(CosmosSystem::id));
        return List.copyOf(systems);
    }

    /** Population probability envelope in [0,1], shared by galaxy geometry and its named spatial regions. */
    public static double populationDensity(long seed, int galaxyIndex, SpaceVector absoluteLightYears) {
        GalaxyDescriptor galaxy = galaxy(seed, galaxyIndex);
        return density(galaxy, regions(galaxy), absoluteLightYears);
    }

    private static double density(GalaxyDescriptor galaxy, List<CosmicRegion> regions, SpaceVector position) {
        double density = galaxy.density(position);
        for (CosmicRegion region : regions) {
            double weight = switch (region.kind()) {
                case NUCLEAR_CLUSTER, GLOBULAR_CLUSTER, OPEN_CLUSTER, QUASAR -> 0.95;
                case EMISSION_NEBULA -> 0.7;
                case DARK_NEBULA -> 0.45;
                case SUPERNOVA_SHELL -> 0.25;
            };
            density = Math.max(density, region.influence(position) * weight);
        }
        return Math.clamp(density, 0, 1);
    }

    private static Optional<CosmosSystem> sector(GalaxyDescriptor galaxy, List<CosmicRegion> regions, int x, int y, int z) {
        long seed = mix(galaxy.seed() ^ mix(x * 0x9E3779B97F4A7C15L)
                ^ mix(y * 0xD1B54A32D192ED03L) ^ mix(z * 0x94D049BB133111EBL));
        Random random = new Random(seed);
        SpaceVector local = new SpaceVector(x * SECTOR_LIGHT_YEARS + (random.nextDouble() - 0.5) * 2,
                y * SECTOR_LIGHT_YEARS + (random.nextDouble() - 0.5) * 2,
                z * SECTOR_LIGHT_YEARS + (random.nextDouble() - 0.5) * 2);
        SpaceVector absolute = galaxy.toUniverseLightYears(local);
        double density = density(galaxy, regions, absolute);
        if (density <= 0 || random.nextDouble() >= density) {
            return Optional.empty();
        }
        CosmosSystem generated = CosmosGenerator.generate(seed, x == 0 && y == 0 && z == 0 ? 1 : x, y, z);
        String id = "v_" + galaxy.index() + "_" + x + "_" + y + "_" + z;
        return Optional.of(new CosmosSystem(id, galaxy.name() + " / " + generated.name(), seed, generated.kind(),
                absolute, generated.bodies()));
    }

    private static CosmosSystem landmark(GalaxyDescriptor galaxy, CosmicRegion region) {
        long seed = mix(galaxy.seed() ^ mix(0x524547494f4eL + region.index()));
        if (region.index() == 0) {
            double solarMasses = galaxy.index() == 0 ? 4_300_000 : galaxy.activeNucleus() ? 200_000_000
                    : 1_000_000 + Math.floorMod(seed, 20_000_000);
            double horizon = 2953 * solarMasses;
            List<CelestialBody> bodies = new ArrayList<>();
            bodies.add(body("primary", galaxy.name() + " Central Black Hole", CelestialBody.Kind.BLACK_HOLE,
                    horizon, 0, 0, 0, new SpaceVector(1, 0.58, 0.22)));
            double orbit = Math.max(30 * CosmosGenerator.AU, horizon * 48);
            for (int star = 0; star < 7; star++) {
                bodies.add(body("nuclear_" + star, galaxy.name() + " Nuclear Star " + (star + 1), CelestialBody.Kind.STAR,
                        450_000_000 + star * 70_000_000, orbit,
                        YEAR_SECONDS * Math.pow(orbit / CosmosGenerator.AU, 1.5) / Math.sqrt(solarMasses),
                        star * 2.399963229728653, new SpaceVector(0.7 + star * 0.04, 0.8, 1)));
                orbit *= 1.23;
            }
            return new CosmosSystem(region.systemId(), region.name(), seed, CosmosSystem.Kind.BLACK_HOLE,
                    region.centerLightYears(), bodies);
        }
        if (region.kind() == CosmicRegion.Kind.OPEN_CLUSTER || region.kind() == CosmicRegion.Kind.GLOBULAR_CLUSTER
                || region.kind() == CosmicRegion.Kind.NUCLEAR_CLUSTER) {
            List<CelestialBody> stars = new ArrayList<>();
            int count = region.kind() == CosmicRegion.Kind.OPEN_CLUSTER ? 6 : 10;
            stars.add(body("primary", region.name() + " A", CelestialBody.Kind.STAR, 695_700_000,
                    0, 0, 0, region.color()));
            for (int star = 1; star < count; star++) {
                double orbit = (2 + star * star * 2.5) * CosmosGenerator.AU;
                stars.add(body("star_" + star, region.name() + " " + (star + 1), CelestialBody.Kind.STAR,
                        (0.5 + star * 0.12) * 695_700_000, orbit,
                        YEAR_SECONDS * Math.pow(orbit / CosmosGenerator.AU, 1.5) / 3,
                        star * 2.399963229728653, region.color()));
            }
            return new CosmosSystem(region.systemId(), region.name(), seed, CosmosSystem.Kind.BINARY,
                    region.centerLightYears(), stars);
        }
        CosmosSystem generated = CosmosGenerator.generate(seed, 1, region.index(), galaxy.index());
        List<CelestialBody> bodies = new ArrayList<>();
        bodies.add(body("primary", region.name() + " Primary", CelestialBody.Kind.STAR, 695_700_000,
                0, 0, 0, region.kind() == CosmicRegion.Kind.SUPERNOVA_SHELL
                        ? new SpaceVector(0.55, 0.75, 1) : new SpaceVector(1, 0.85, 0.65)));
        generated.bodies().stream().filter(value -> value.kind() != CelestialBody.Kind.STAR
                && value.kind() != CelestialBody.Kind.BLACK_HOLE).forEach(bodies::add);
        return new CosmosSystem(region.systemId(), region.name(), seed,
                region.kind() == CosmicRegion.Kind.SUPERNOVA_SHELL ? CosmosSystem.Kind.SUPERNOVA : CosmosSystem.Kind.SINGLE,
                region.centerLightYears(), bodies);
    }

    private static CelestialBody body(String id, String name, CelestialBody.Kind kind, double radius, double orbit,
            double period, double phase, SpaceVector color) {
        return new CelestialBody(id, name, kind, radius, orbit, period, Math.IEEEremainder(phase, Math.PI * 2),
                orbit == 0 ? 0 : 0.08,
                0, color, 0, 0, 0, 0);
    }

    private static String landmarkId(int galaxy, int region) {
        return "u_" + galaxy + "_" + region;
    }

    private static ParsedId parse(String id) {
        if (id == null || id.length() > 64) {
            return null;
        }
        String[] parts = id.split("_", -1);
        boolean landmark = parts.length == 3 && parts[0].equals("u");
        if (!landmark && !(parts.length == 5 && parts[0].equals("v"))) {
            return null;
        }
        try {
            int galaxy = Integer.parseInt(parts[1]);
            if (galaxy < 0 || galaxy >= GALAXY_COUNT || !parts[1].equals(Integer.toString(galaxy))) {
                return null;
            }
            if (landmark) {
                int region = Integer.parseInt(parts[2]);
                return region >= 0 && region < REGION_COUNT && id.equals(landmarkId(galaxy, region))
                        ? new ParsedId(galaxy, region, 0, 0, 0, true) : null;
            }
            int x = Integer.parseInt(parts[2]), y = Integer.parseInt(parts[3]), z = Integer.parseInt(parts[4]);
            return id.equals("v_" + galaxy + "_" + x + "_" + y + "_" + z)
                    ? new ParsedId(galaxy, -1, x, y, z, false) : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int sectorCoordinate(double lightYears) {
        double sector = Math.floor(lightYears / SECTOR_LIGHT_YEARS + 0.5);
        if (sector < Integer.MIN_VALUE || sector > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Galaxy-local sector coordinate exceeds the signed-int domain");
        }
        return (int) sector;
    }

    private static void requireGalaxy(int index) {
        if (index < 0 || index >= GALAXY_COUNT) {
            throw new IllegalArgumentException("Galaxy index must be in [0,8]");
        }
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private record ParsedId(int galaxy, int region, int x, int y, int z, boolean landmark) {
    }
}
