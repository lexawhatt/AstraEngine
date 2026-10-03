package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Bake-only first-order sky irradiance. All lengths are kilometers; incident irradiance is normalized to one. */
final class EarthSkyIrradiance {
    private static final double[] COSINES = {.0198550717512319, .101666761293187, .237233795041836,
            .408282678752175, .591717321247825, .762766204958164, .898333238706813, .980144928248768};
    private static final double[] WEIGHTS = {.0506142681451881, .111190517226687, .156853322938944,
            .181341891689181, .181341891689181, .156853322938944, .111190517226687, .0506142681451881};
    private static final int AZIMUTHS = 24;
    private static final int STEPS = 24;
    private static final double[] RAYLEIGH = {.0058, .0135, .0331};
    private static final double[] OZONE = {.00065, .00188, .00008};

    private EarthSkyIrradiance() { }

    static SpaceVector integrate(EarthAtmosphereOptics optics, double radius, double height, double solarCosine) {
        if (height >= EarthAtmosphereOptics.SHELL_KM) { return SpaceVector.ZERO; }
        double[] result = new double[3], columns = new double[3], transmission = new double[3];
        double r = radius + height;
        double sunX = Math.sqrt(Math.max(0, 1 - solarCosine * solarCosine));
        for (int m = 0; m < COSINES.length; m++) {
            double mu = COSINES[m], horizontal = Math.sqrt(1 - mu * mu);
            double remaining = (80 - height) * (2 * radius + 80 + height);
            double distance = remaining / (Math.sqrt(r * r * mu * mu + remaining) + r * mu);
            double angularWeight = WEIGHTS[m] * mu * (2 * Math.PI / AZIMUTHS);
            for (int a = 0; a < AZIMUTHS; a++) {
                double rayX = horizontal * Math.cos(2 * Math.PI * (a + .5) / AZIMUTHS);
                double raySun = rayX * sunX + mu * solarCosine;
                double rayleighPhase = 3 / (16 * Math.PI) * (1 + raySun * raySun);
                double miePhase = (1 - .76 * .76) / (4 * Math.PI
                        * Math.pow(1 + .76 * .76 - 1.52 * raySun, 1.5));
                transmission[0] = 1; transmission[1] = 1; transmission[2] = 1;
                for (int s = 0; s < STEPS; s++) {
                    double start = distance * s * s / (STEPS * STEPS);
                    double finish = distance * (s + 1) * (s + 1) / (STEPS * STEPS);
                    double d = (start + finish) * .5, step = finish - start;
                    double squaredDifference = height * (2 * radius + height) + d * (d + 2 * r * mu);
                    double localRadius = Math.sqrt(radius * radius + squaredDifference);
                    double altitude = squaredDifference / (localRadius + radius);
                    double localSun = (r * solarCosine + d * raySun) / localRadius;
                    double horizon = -Math.sqrt(altitude * (2 * radius + altitude)) / localRadius;
                    double molecular = Math.exp(-altitude / 8), aerosol = Math.exp(-altitude / 1.2);
                    double ozone = Math.max(0, 1 - Math.abs(altitude - 25) / 15);
                    if (localSun >= horizon) { optics.columns(altitude, localSun, columns); }
                    for (int c = 0; c < 3; c++) {
                        double extinction = RAYLEIGH[c] * molecular + .0032 * aerosol + OZONE[c] * ozone;
                        double stepTransmission = Math.exp(-extinction * step);
                        if (localSun >= horizon) {
                            double solar = Math.exp(-RAYLEIGH[c] * columns[0] - .0032 * columns[1] - OZONE[c] * columns[2]);
                            double source = (RAYLEIGH[c] * molecular * rayleighPhase + .0032 * .9 * aerosol * miePhase) * solar;
                            result[c] += angularWeight * transmission[c] * source
                                    * -Math.expm1(-extinction * step) / Math.max(1e-15, extinction);
                        }
                        transmission[c] *= stepTransmission;
                    }
                }
            }
        }
        return new SpaceVector(result[0], result[1], result[2]);
    }
}
