package dev.lexawhatt.astraengine.cosmos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Stateless version-one descriptor generator. A galaxy seed and canonical sector ID reproduce a system
 * independently of visit order. This is a bounded catalog query, not dimension or chunk generation.
 */
public final class CosmosGenerator {
    public static final int VERSION = 1;
    public static final double AU = 149_597_870_700d;
    public static final double LIGHT_YEAR = 9_460_730_472_580_800d;
    public static final double SECTOR_LIGHT_YEARS = 4;

    private static final double DAY_SECONDS = 86_400;
    private static final double YEAR_SECONDS = 365.256 * DAY_SECONDS;
    private static final double TWO_PI = Math.PI * 2;
    private static final String[] NAME_ROOTS = {
        "Aster", "Vela", "Neris", "Caelum", "Lyra", "Orion", "Talos", "Cinder", "Iona", "Helix", "Lumen", "Rhea"
    };
    private static final CosmosSystem SOL = createSol();

    private CosmosGenerator() {
    }

    /**
     * Sun and eight planets with NASA fact-sheet mean radii and orbital parameters in SI units.
     * Initial phases and rendered colors are illustrative. See docs/SOLAR_REFERENCE.md for source precision.
     */
    public static CosmosSystem sol() {
        return SOL;
    }

    /**
     * Generates one system per centered four-light-year sector. Sector (0,0,0) is always our Solar System.
     * Other sectors use fixed 2% black-hole, 2% supernova-remnant, 18% binary and 78% single-star thresholds.
     * Those are authored gameplay weights, not measured astronomical population frequencies.
     */
    public static CosmosSystem generate(long galaxySeed, int sectorX, int sectorY, int sectorZ) {
        if (sectorX == 0 && sectorY == 0 && sectorZ == 0) {
            return SOL;
        }
        String id = sectorId(sectorX, sectorY, sectorZ);
        long seed = mix(galaxySeed ^ mix(sectorX * 0x9E3779B97F4A7C15L)
                ^ mix(sectorY * 0xD1B54A32D192ED03L) ^ mix(sectorZ * 0x94D049BB133111EBL));
        Random random = new Random(seed);
        SpaceVector position = new SpaceVector(sectorX * SECTOR_LIGHT_YEARS + jitter(random),
                sectorY * SECTOR_LIGHT_YEARS + jitter(random), sectorZ * SECTOR_LIGHT_YEARS + jitter(random));
        double roll = random.nextDouble();
        CosmosSystem.Kind kind = roll < 0.02 ? CosmosSystem.Kind.BLACK_HOLE
                : roll < 0.04 ? CosmosSystem.Kind.SUPERNOVA
                : roll < 0.22 ? CosmosSystem.Kind.BINARY : CosmosSystem.Kind.SINGLE;
        String suffix = Long.toUnsignedString(seed, 36);
        String name = NAME_ROOTS[random.nextInt(NAME_ROOTS.length)] + "-"
                + suffix.substring(0, Math.min(5, suffix.length())).toUpperCase(java.util.Locale.ROOT);
        List<CelestialBody> bodies = new ArrayList<>();
        double stellarMass = 0.6 + random.nextDouble() * 1.8;
        SpaceVector starColor = random.nextBoolean() ? new SpaceVector(1, 0.73, 0.42)
                : new SpaceVector(0.69, 0.83, 1);
        if (kind == CosmosSystem.Kind.BLACK_HOLE) {
            stellarMass = 5 + random.nextDouble() * 35;
            bodies.add(body("primary", name + " Black Hole", CelestialBody.Kind.BLACK_HOLE,
                    2_953 * stellarMass, 0, 0, 0, 0, 0, new SpaceVector(1, 0.45, 0.12), 0, 0, 0, 0));
        } else if (kind == CosmosSystem.Kind.BINARY) {
            double orbit = AU * (0.015 + random.nextDouble() * 0.025);
            double period = YEAR_SECONDS * Math.pow(3 * orbit / AU, 1.5) / Math.sqrt(stellarMass * 1.5);
            bodies.add(body("primary", name + " A", CelestialBody.Kind.STAR, 695_700_000 * stellarMass,
                    orbit, period, 0, 0.03, 0, starColor, 0, 0, 0, 0));
            bodies.add(body("secondary", name + " B", CelestialBody.Kind.STAR, 450_000_000 * stellarMass,
                    orbit * 2, period, Math.PI, 0.03, 0, new SpaceVector(1, 0.45, 0.25), 0, 0, 0, 0));
            stellarMass *= 1.5;
        } else {
            bodies.add(body("primary", name, CelestialBody.Kind.STAR, 695_700_000 * stellarMass,
                    0, 0, 0, 0, 0, starColor, 0, 0, 0, 0));
        }
        int count = 2 + random.nextInt(8);
        double orbitAu = 1 + random.nextDouble();
        for (int index = 0; index < count; index++) {
            CelestialBody.Kind material = planetKind(random, orbitAu);
            double radius = material == CelestialBody.Kind.GAS_GIANT ? 22_000_000 + random.nextDouble() * 60_000_000
                    : 1_800_000 + random.nextDouble() * 8_000_000;
            SpaceVector color = planetColor(random, material);
            float atmosphere = switch (material) {
                case OCEAN -> 0.7f;
                case GAS_GIANT -> 0.45f;
                case ICE -> 0.2f;
                default -> random.nextFloat() * 0.15f;
            };
            boolean rings = material == CelestialBody.Kind.GAS_GIANT && random.nextDouble() < 0.7;
            float inner = rings ? 1.25f + random.nextFloat() * 0.3f : 0;
            float outer = rings ? 2.1f + random.nextFloat() * 0.8f : 0;
            double period = YEAR_SECONDS * Math.pow(orbitAu, 1.5) / Math.sqrt(stellarMass);
            bodies.add(body("planet_" + (index + 1), name + " " + (index + 1), material, radius, orbitAu * AU,
                    period, random.nextDouble() * TWO_PI, random.nextDouble() * 0.18 - 0.09,
                    random.nextDouble() * 0.1, color, atmosphere, inner, outer, random.nextDouble() * 1.5));
            orbitAu *= 1.45 + random.nextDouble() * 0.15;
        }
        return new CosmosSystem(id, name, seed, kind, position, bodies);
    }

    /** Reconstructs a canonical ID: "sol", or "s_" followed by three signed decimal sector integers. */
    public static CosmosSystem byId(long galaxySeed, String id) {
        if (UniverseGenerator.isUniverseId(id)) {
            return UniverseGenerator.byId(galaxySeed, id);
        }
        if ("sol".equals(id)) {
            return SOL;
        }
        if (id == null || id.length() > 64 || !id.startsWith("s_")) {
            throw new IllegalArgumentException("Unknown cosmos system ID: " + id);
        }
        String[] components = id.substring(2).split("_", -1);
        if (components.length != 3) {
            throw new IllegalArgumentException("Expected three cosmos sector coordinates: " + id);
        }
        try {
            int x = Integer.parseInt(components[0]);
            int y = Integer.parseInt(components[1]);
            int z = Integer.parseInt(components[2]);
            if (!sectorId(x, y, z).equals(id) || x == 0 && y == 0 && z == 0) {
                throw new IllegalArgumentException("Noncanonical cosmos system ID: " + id);
            }
            return generate(galaxySeed, x, y, z);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid cosmos sector coordinate: " + id, exception);
        }
    }

    /**
     * Queries a cube of nearby sectors at a light-year position. Radius 0..2 returns 1..125 immutable
     * descriptors sorted by actual distance, then ID. Coordinates outside the signed-int sector domain
     * are rejected; edge queries clip to that domain without wrapping.
     */
    public static List<CosmosSystem> nearby(long seed, SpaceVector galacticLY, int radiusSectors) {
        if (galacticLY == null || radiusSectors < 0 || radiusSectors > 2) {
            throw new IllegalArgumentException("Nearby query requires a position and sector radius 0..2");
        }
        int centerX = sectorCoordinate(galacticLY.x());
        int centerY = sectorCoordinate(galacticLY.y());
        int centerZ = sectorCoordinate(galacticLY.z());
        List<CosmosSystem> systems = new ArrayList<>();
        for (long x = (long) centerX - radiusSectors; x <= (long) centerX + radiusSectors; x++) {
            for (long y = (long) centerY - radiusSectors; y <= (long) centerY + radiusSectors; y++) {
                for (long z = (long) centerZ - radiusSectors; z <= (long) centerZ + radiusSectors; z++) {
                    if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE && y >= Integer.MIN_VALUE
                            && y <= Integer.MAX_VALUE && z >= Integer.MIN_VALUE && z <= Integer.MAX_VALUE) {
                        systems.add(generate(seed, (int) x, (int) y, (int) z));
                    }
                }
            }
        }
        systems.sort(Comparator.comparingDouble((CosmosSystem system) -> system.galaxyPosition().distance(galacticLY))
                .thenComparing(CosmosSystem::id));
        return List.copyOf(systems);
    }

    private static CosmosSystem createSol() {
        List<CelestialBody> bodies = List.of(
                body("sun", "Sun", CelestialBody.Kind.STAR, 695_700_000, 0, 0, 0, 0, 0,
                        new SpaceVector(1, 0.93, 0.78), 0, 0, 0, Math.toRadians(7.25)),
                solarPlanet("mercury", "Mercury", CelestialBody.Kind.ROCKY, 2439.7, 57.909, 87.969,
                        0.7, 7.004, 0.2056, new SpaceVector(0.57, 0.54, 0.49), 0, 0, 0, 0.034),
                solarPlanet("venus", "Venus", CelestialBody.Kind.ROCKY, 6051.8, 108.210, 224.701,
                        1.6, 3.395, 0.0068, new SpaceVector(0.87, 0.69, 0.42), 0.9f, 0, 0, 177.36),
                solarPlanet("earth", "Earth", CelestialBody.Kind.OCEAN, 6371.0, 149.598, 365.256,
                        2.3, 0, 0.0167, new SpaceVector(0.13, 0.4, 0.8), 0.7f, 0, 0, 23.44),
                solarPlanet("mars", "Mars", CelestialBody.Kind.ROCKY, 3389.5, 227.956, 686.980,
                        3.5, 1.848, 0.0935, new SpaceVector(0.72, 0.28, 0.12), 0.13f, 0, 0, 25.19),
                solarPlanet("jupiter", "Jupiter", CelestialBody.Kind.GAS_GIANT, 69911, 778.479, 4332.589,
                        4.2, 1.304, 0.0487, new SpaceVector(0.83, 0.62, 0.44), 0.4f, 1.35f, 1.85f, 3.13),
                solarPlanet("saturn", "Saturn", CelestialBody.Kind.GAS_GIANT, 58232, 1432.041, 10755.699,
                        5.1, 2.486, 0.0520, new SpaceVector(0.9, 0.79, 0.54), 0.4f, 1.24f, 2.3f, 26.73),
                solarPlanet("uranus", "Uranus", CelestialBody.Kind.ICE, 25362, 2867.043, 30685.400,
                        5.8, 0.770, 0.0469, new SpaceVector(0.48, 0.8, 0.86), 0.35f, 1.65f, 2.1f, 97.77),
                solarPlanet("neptune", "Neptune", CelestialBody.Kind.ICE, 24622, 4514.953, 60189.018,
                        0.2, 1.770, 0.0097, new SpaceVector(0.18, 0.36, 0.82), 0.4f, 1.6f, 2.54f, 28.32));
        return new CosmosSystem("sol", "Solar System", 0, CosmosSystem.Kind.SINGLE, SpaceVector.ZERO, bodies);
    }

    private static CelestialBody solarPlanet(String id, String name, CelestialBody.Kind kind, double radiusKm,
            double orbitMillionKm, double periodDays, double phase, double inclinationDegrees, double eccentricity,
            SpaceVector color, float atmosphere, float ringInner, float ringOuter, double tiltDegrees) {
        return body(id, name, kind, radiusKm * 1000, orbitMillionKm * 1.0e9, periodDays * DAY_SECONDS,
                phase, Math.toRadians(inclinationDegrees), eccentricity, color, atmosphere,
                ringInner, ringOuter, Math.toRadians(tiltDegrees));
    }

    private static CelestialBody body(String id, String name, CelestialBody.Kind kind, double radius, double orbit,
            double period, double phase, double inclination, double eccentricity, SpaceVector color,
            float atmosphere, float ringInner, float ringOuter, double tilt) {
        return new CelestialBody(id, name, kind, radius, orbit, period, phase, inclination, eccentricity,
                color, atmosphere, ringInner, ringOuter, tilt);
    }

    private static CelestialBody.Kind planetKind(Random random, double orbitAu) {
        double roll = random.nextDouble();
        if (orbitAu > 2 && roll < 0.46) {
            return CelestialBody.Kind.GAS_GIANT;
        }
        if (orbitAu > 6 && roll < 0.78) {
            return CelestialBody.Kind.ICE;
        }
        return roll > 0.7 ? CelestialBody.Kind.OCEAN : CelestialBody.Kind.ROCKY;
    }

    private static SpaceVector planetColor(Random random, CelestialBody.Kind kind) {
        return switch (kind) {
            case OCEAN -> new SpaceVector(0.08 + random.nextDouble() * 0.12, 0.3, 0.65 + random.nextDouble() * 0.25);
            case ICE -> new SpaceVector(0.35 + random.nextDouble() * 0.35, 0.65, 0.8);
            case GAS_GIANT -> new SpaceVector(0.6 + random.nextDouble() * 0.35,
                    0.4 + random.nextDouble() * 0.3, 0.3 + random.nextDouble() * 0.4);
            default -> new SpaceVector(0.35 + random.nextDouble() * 0.45,
                    0.2 + random.nextDouble() * 0.35, 0.12 + random.nextDouble() * 0.3);
        };
    }

    private static int sectorCoordinate(double lightYears) {
        double cell = Math.floor(lightYears / SECTOR_LIGHT_YEARS + 0.5);
        if (cell < Integer.MIN_VALUE || cell > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Galaxy position is outside the signed-int sector domain");
        }
        return (int) cell;
    }

    private static String sectorId(int x, int y, int z) {
        return "s_" + x + "_" + y + "_" + z;
    }

    private static double jitter(Random random) {
        return (random.nextDouble() - 0.5) * 2.4;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
