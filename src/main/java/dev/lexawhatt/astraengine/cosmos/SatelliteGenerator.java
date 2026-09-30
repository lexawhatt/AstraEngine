package dev.lexawhatt.astraengine.cosmos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Additive version-one satellite population; it never consumes or modifies the legacy parent generator stream. */
public final class SatelliteGenerator {
    public static final int VERSION = 1;
    private static final double DAY_SECONDS = 86_400;

    private SatelliteGenerator() {
    }

    /** Package-owned generation only; consumer-authored systems are never implicitly populated. */
    static List<CelestialBody> procedural(List<CelestialBody> parents, long seed, double stellarSolarMasses) {
        List<CelestialBody> bodies = new ArrayList<>(parents);
        for (CelestialBody planet : parents) {
            if (planet.kind() == CelestialBody.Kind.STAR || planet.kind() == CelestialBody.Kind.BLACK_HOLE
                    || !planet.parentId().isEmpty() || planet.orbitMeters() == 0) { continue; }
            Random random = new Random(seed ^ 0x534154454c4c4954L ^ planet.id().hashCode() * 0x9E3779B97F4A7C15L);
            boolean giant = planet.kind() == CelestialBody.Kind.GAS_GIANT;
            int count = giant ? 1 + random.nextInt(4) : random.nextInt(3);
            double density = giant ? 1300 : planet.kind() == CelestialBody.Kind.ICE ? 2500 : 4500;
            double mass = 4 * Math.PI / 3 * Math.pow(planet.radiusMeters(), 3) * density;
            double hill = planet.orbitMeters() * (1 - planet.eccentricity())
                    * Math.cbrt(mass / (3 * stellarSolarMasses * 1.98847e30));
            double limit = Math.min(hill * 0.25, planet.orbitMeters() * (1 - planet.eccentricity()) * 0.03);
            double orbit = planet.radiusMeters() * (5 + random.nextDouble() * 2);
            for (int index = 0; index < count && bodies.size() < CosmosSystem.MAX_BODIES; index++) {
                double radius = planet.radiusMeters() * (giant ? 0.006 + random.nextDouble() * 0.05
                        : 0.025 + random.nextDouble() * 0.15);
                double eccentricity = random.nextDouble() * 0.035;
                if (orbit * (1 + eccentricity) + radius > limit) { break; }
                double period = Math.PI * 2 * Math.sqrt(orbit * orbit * orbit / (6.67430e-11 * mass));
                CelestialBody.Kind kind = random.nextBoolean() ? CelestialBody.Kind.ICE : CelestialBody.Kind.ROCKY;
                double shade = 0.34 + random.nextDouble() * 0.4;
                bodies.add(new CelestialBody(planet.id() + "_moon_" + (index + 1),
                        planet.name() + " Moon " + (index + 1), kind, radius, orbit, period,
                        random.nextDouble() * Math.PI * 2,
                        Math.IEEEremainder(planet.axialTiltRadians() + random.nextDouble() * 0.14 - 0.07, Math.PI * 2),
                        eccentricity, new SpaceVector(shade, shade * 0.97, shade * 0.92),
                        0, 0, 0, 0, planet.id()));
                orbit *= 1.85 + random.nextDouble() * 0.25;
            }
        }
        return List.copyOf(bodies);
    }

    /** Major Solar satellites. Existing Sun/planet descriptors and their order are retained exactly. */
    static List<CelestialBody> solar(List<CelestialBody> planets) {
        List<CelestialBody> bodies = new ArrayList<>(planets);
        bodies.add(new CelestialBody("moon", "Moon", CelestialBody.Kind.ROCKY, 1_737_400, 384_400_000,
                27.3217 * DAY_SECONDS, 1.1, Math.toRadians(5.145), 0.0549,
                new SpaceVector(0.54, 0.52, 0.49), 0, 0, 0, Math.toRadians(6.68), "earth"));
        add(bodies, "mars", "phobos", "Phobos", equivalentRadius(13.0, 11.4, 9.1), 9.378, 0.31891, 1.08, 0.0151);
        add(bodies, "mars", "deimos", "Deimos", equivalentRadius(7.8, 6.0, 5.1), 23.459, 1.26244, 1.79, 0.0005);
        add(bodies, "jupiter", "io", "Io", 1821.5, 421.8, 1.769138, 0.04, 0.004);
        add(bodies, "jupiter", "europa", "Europa", 1560.8, 671.1, 3.551181, 0.47, 0.009);
        add(bodies, "jupiter", "ganymede", "Ganymede", 2631.2, 1070.4, 7.154553, 0.18, 0.001);
        add(bodies, "jupiter", "callisto", "Callisto", 2410.3, 1882.7, 16.689017, 0.19, 0.007);
        add(bodies, "saturn", "mimas", "Mimas", equivalentRadius(208, 197, 191), 185.52, 0.9424218, 1.53, 0.0202);
        add(bodies, "saturn", "enceladus", "Enceladus", equivalentRadius(257, 251, 248), 238.02, 1.370218, 0, 0.0045);
        add(bodies, "saturn", "tethys", "Tethys", equivalentRadius(538, 528, 526), 294.66, 1.887802, 1.86, 0);
        add(bodies, "saturn", "dione", "Dione", equivalentRadius(563, 561, 560), 377.40, 2.736915, 0.02, 0.0022);
        add(bodies, "saturn", "rhea", "Rhea", equivalentRadius(765, 763, 762), 527.04, 4.517500, 0.35, 0.0010);
        add(bodies, "saturn", "titan", "Titan", 2575, 1221.87, 15.945421, 0.33, 0.0292);
        add(bodies, "saturn", "iapetus", "Iapetus", equivalentRadius(746, 746, 712), 3560.85, 79.330183, 14.72, 0.0283);
        add(bodies, "uranus", "miranda", "Miranda", equivalentRadius(240, 234.2, 232.9), 129.90, 1.413479, 4.34, 0.0013);
        add(bodies, "uranus", "ariel", "Ariel", equivalentRadius(581.1, 577.9, 577.7), 190.90, 2.520379, 0.04, 0.0012);
        add(bodies, "uranus", "umbriel", "Umbriel", 584.7, 266, 4.144176, 0.13, 0.0039);
        add(bodies, "uranus", "titania", "Titania", 788.9, 436.30, 8.705867, 0.08, 0.0011);
        add(bodies, "uranus", "oberon", "Oberon", 761.4, 583.50, 13.463234, 0.07, 0.0014);
        add(bodies, "neptune", "triton", "Triton", 1353.4, 354.76, 5.876854, 157.345, 0.000016);
        add(bodies, "neptune", "proteus", "Proteus", equivalentRadius(220, 208, 202), 117.647, 1.122315, 0.04, 0.0004);
        return List.copyOf(bodies);
    }

    private static void add(List<CelestialBody> bodies, String parentId, String id, String name, double radiusKm,
            double orbitThousandKm, double days, double equatorialInclinationDegrees, double eccentricity) {
        CelestialBody parent = bodies.stream().filter(body -> body.id().equals(parentId)).findFirst().orElseThrow();
        // The shared-node approximation maps the parent's equatorial inclination into system axes.
        double inclination = Math.IEEEremainder(parent.axialTiltRadians()
                + Math.toRadians(equatorialInclinationDegrees), Math.PI * 2);
        boolean rocky = parentId.equals("mars") || id.equals("io") || id.equals("titan");
        SpaceVector color = id.equals("io") ? new SpaceVector(0.84, 0.66, 0.25)
                : id.equals("titan") ? new SpaceVector(0.76, 0.56, 0.28) : new SpaceVector(0.63, 0.62, 0.59);
        bodies.add(new CelestialBody(id, name, rocky ? CelestialBody.Kind.ROCKY : CelestialBody.Kind.ICE,
                radiusKm * 1000, orbitThousandKm * 1e6, days * DAY_SECONDS,
                Math.floorMod(id.hashCode(), 6283) / 1000.0, inclination, eccentricity, color,
                id.equals("titan") ? 0.6f : 0, 0, 0, 0, parentId));
    }

    private static double equivalentRadius(double xKm, double yKm, double zKm) {
        return Math.cbrt(xKm * yKm * zKm);
    }
}
