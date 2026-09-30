package dev.lexawhatt.astraengine.cosmos;

/** Immutable version-one galaxy geometry. All positions and lengths use light-years, independent of body meters. */
public record GalaxyDescriptor(int index, String id, String name, long seed, Kind kind,
        SpaceVector centerLightYears, FlightOrientation orientation, double radiusLightYears,
        double thicknessLightYears, double coreRadiusLightYears, int armCount, double armTwist,
        boolean activeNucleus) {
    public enum Kind { SPIRAL, ELLIPTICAL, IRREGULAR }

    public GalaxyDescriptor {
        if (index < 0 || index >= UniverseGenerator.GALAXY_COUNT || !("g_" + index).equals(id)
                || name == null || name.isBlank() || name.length() > 96 || kind == null
                || centerLightYears == null || orientation == null
                || !Double.isFinite(radiusLightYears) || radiusLightYears < 1000 || radiusLightYears > 100_000
                || !Double.isFinite(thicknessLightYears) || thicknessLightYears < 1
                || thicknessLightYears > radiusLightYears || !Double.isFinite(coreRadiusLightYears)
                || coreRadiusLightYears < 1 || coreRadiusLightYears > radiusLightYears
                || !Double.isFinite(armTwist) || armTwist < 0 || armTwist > 12
                || armCount < 0 || armCount > 6 || kind == Kind.SPIRAL && armCount < 2) {
            throw new IllegalArgumentException("Invalid bounded galaxy descriptor");
        }
    }

    /** Converts an absolute universe position to this galaxy's oriented local XYZ light-years. */
    public SpaceVector toLocalLightYears(SpaceVector universePosition) {
        if (universePosition == null) {
            throw new IllegalArgumentException("Galaxy coordinates require a position");
        }
        SpaceVector relative = universePosition.subtract(centerLightYears);
        return new SpaceVector(relative.dot(orientation.left()), relative.dot(orientation.up()),
                relative.dot(orientation.forward()));
    }

    /** Converts oriented galaxy-local XYZ light-years to an absolute universe position. */
    public SpaceVector toUniverseLightYears(SpaceVector localPosition) {
        if (localPosition == null) {
            throw new IllegalArgumentException("Galaxy coordinates require a position");
        }
        return centerLightYears.add(orientation.left().multiply(localPosition.x()))
                .add(orientation.up().multiply(localPosition.y())).add(orientation.forward().multiply(localPosition.z()));
    }

    /**
     * Smooth population envelope in [0,1] at an absolute position; it is an authored density, not physical mass.
     * Spirals use a thin XZ disk and logarithmic arms, ellipticals a thick ellipsoid, irregulars seeded lobes.
     * The CPU sector population and renderer share this coordinate frame and these shape parameters.
     */
    public double density(SpaceVector universePosition) {
        SpaceVector point = toLocalLightYears(universePosition);
        double x = point.x() / radiusLightYears;
        double y = point.y() / radiusLightYears;
        double z = point.z() / radiusLightYears;
        double radial = Math.hypot(x, z);
        if (kind == Kind.ELLIPTICAL) {
            double ellipsoid = Math.sqrt(x * x + y * y / 0.3025 + z * z / 0.5184);
            return Math.exp(-3 * ellipsoid) * (1 - smooth(0.8, 1, ellipsoid));
        }
        double height = Math.abs(point.y()) / thicknessLightYears;
        if (radial >= 1 || height >= 6) {
            return 0;
        }
        double disk = Math.exp(-3 * radial - height) * (1 - smooth(0.82, 1, radial));
        double core = Math.exp(-point.length() / coreRadiusLightYears);
        if (kind == Kind.SPIRAL) {
            double phase = Math.atan2(z, x) - armTwist * Math.log(Math.max(radial, 0.035));
            double arm = Math.pow(0.5 + 0.5 * Math.cos(armCount * phase), 4);
            return Math.clamp(disk * (0.15 + 0.85 * arm) + core * 0.75, 0, 1);
        }
        double phase = Math.floorMod(seed, 4096) * (Math.PI * 2 / 4096);
        double lobes = 0.5 + 0.5 * Math.sin(x * 8 + phase) * Math.cos(z * 7 - phase * 0.6);
        return Math.clamp(disk * (0.25 + 0.75 * lobes) + core * 0.35, 0, 1);
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
