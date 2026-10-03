package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.nio.FloatBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable optical columns for an Earth-like spherical atmosphere, in kilometers. A bounded table is
 * baked off the render thread from radius alone and owns no world, registry, clock or mutable host data.
 * The distance/radius coordinates follow Bruneton's atmosphere-boundary mapping; values are numerical
 * Beer-Lambert columns, not display colors. Mars and airless bodies do not use this model.
 */
public final class EarthAtmosphereOptics {
    public static final double SHELL_KM = 80;
    public static final int WIDTH = 128;
    public static final int HEIGHT = 64;
    public static final int SKY_WIDTH = 48;
    public static final int SKY_HEIGHT = 24;
    public static final int TEXTURE_HEIGHT = HEIGHT + SKY_HEIGHT;
    public static final SpaceVector RAYLEIGH = new SpaceVector(.0058, .0135, .0331);
    public static final SpaceVector MIE_EXTINCTION = new SpaceVector(.0032, .0032, .0032);
    public static final SpaceVector OZONE = new SpaceVector(.00065, .00188, .00008);
    private static final int INTERVALS = 128;
    private final double radiusKm;
    private final float[] columns;

    private EarthAtmosphereOptics(double radiusKm, float[] columns) {
        this.radiusKm = radiusKm;
        this.columns = columns;
    }

    /** Builds optical128x64 and diffuse48x24 tiles in one128x88 atlas; cancellation retires the unpublished result. */
    public static EarthAtmosphereOptics bake(double radiusKm, BooleanSupplier cancelled) {
        requireRadius(radiusKm);
        if (cancelled == null) { throw new IllegalArgumentException("Atmospheric bake requires a cancellation source"); }
        float[] values = new float[WIDTH * TEXTURE_HEIGHT * 4];
        double horizon = Math.sqrt(SHELL_KM * (2 * radiusKm + SHELL_KM));
        for (int y = 0; y < HEIGHT; y++) {
            if (cancelled.getAsBoolean()) { throw new CancellationException("Atmospheric optical table retired"); }
            double rho = horizon * y / (HEIGHT - 1);
            double r = Math.hypot(rho, radiusKm);
            double altitude = rho * rho / (r + radiusKm);
            double minimumDistance = SHELL_KM - altitude;
            double maximumDistance = rho + horizon;
            for (int x = 0; x < WIDTH; x++) {
                double distance = minimumDistance + (maximumDistance - minimumDistance) * x / (WIDTH - 1);
                double mu = distance <= 1e-12 ? 1 : (SHELL_KM * (2 * radiusKm + SHELL_KM)
                        - rho * rho - distance * distance) / (2 * r * distance);
                var optical = integrate(radiusKm, altitude, Math.clamp(mu, -1, 1), distance);
                int index = (y * WIDTH + x) * 4;
                values[index] = (float) optical.x(); values[index + 1] = (float) optical.y();
                values[index + 2] = (float) optical.z(); values[index + 3] = 1;
            }
        }
        var optics = new EarthAtmosphereOptics(radiusKm, values);
        for (int y = 0; y < SKY_HEIGHT; y++) {
            if (cancelled.getAsBoolean()) { throw new CancellationException("Atmospheric irradiance table retired"); }
            double altitude = SHELL_KM * Math.pow((double) y / (SKY_HEIGHT - 1), 2);
            for (int x = 0; x < SKY_WIDTH; x++) {
                double unit = 2.0 * x / (SKY_WIDTH - 1) - 1;
                double cosine = Math.copySign(unit * unit, unit);
                var irradiance = EarthSkyIrradiance.integrate(optics, radiusKm, altitude, cosine);
                int index = ((HEIGHT + y) * WIDTH + x) * 4;
                values[index] = (float) irradiance.x(); values[index + 1] = (float) irradiance.y();
                values[index + 2] = (float) irradiance.z(); values[index + 3] = 1;
            }
        }
        return optics;
    }

    /** Reads shell-local optical lengths; altitude is clamped to [0,80]km, mirroring GPU table interpolation. */
    public SpaceVector columns(double altitudeKm, double solarCosine) {
        requireRay(altitudeKm, solarCosine);
        double[] value = new double[3];
        columns(altitudeKm, solarCosine, value);
        return new SpaceVector(value[0], value[1], value[2]);
    }

    // Bake-only scratch output avoids allocating temporary vectors at each irradiance quadrature point.
    void columns(double altitudeKm, double solarCosine, double[] output) {
        double altitude = Math.clamp(altitudeKm, 0, SHELL_KM);
        double r = radiusKm + altitude;
        double rho = Math.sqrt(altitude * (2 * radiusKm + altitude));
        double horizon = Math.sqrt(SHELL_KM * (2 * radiusKm + SHELL_KM));
        double mu = Math.max(solarCosine, -rho / r);
        double remaining = (SHELL_KM - altitude) * (2 * radiusKm + SHELL_KM + altitude);
        double along = r * mu;
        double root = Math.sqrt(Math.max(0, along * along + remaining));
        double distance = along >= 0 ? remaining / Math.max(1e-12, root + along) : root - along;
        double minimum = SHELL_KM - altitude;
        double x = Math.clamp((distance - minimum) / Math.max(1e-12, rho + horizon - minimum), 0, 1) * (WIDTH - 1);
        double y = Math.clamp(rho / horizon, 0, 1) * (HEIGHT - 1);
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, WIDTH - 1), y1 = Math.min(y0 + 1, HEIGHT - 1);
        double dx = x - x0, dy = y - y0;
        for (int channel = 0; channel < 3; channel++) {
            output[channel] = columns[(y0 * WIDTH + x0) * 4 + channel] * (1 - dx) * (1 - dy)
                    + columns[(y0 * WIDTH + x1) * 4 + channel] * dx * (1 - dy)
                    + columns[(y1 * WIDTH + x0) * 4 + channel] * (1 - dx) * dy
                    + columns[(y1 * WIDTH + x1) * 4 + channel] * dx * dy;
        }
    }

    /** Diffuse upper-hemisphere irradiance divided by incident irradiance; no direct solar beam or ground bounce. */
    public SpaceVector skyIrradiance(double altitudeKm, double solarCosine) {
        requireRay(altitudeKm, solarCosine);
        double x = (0.5 + 0.5 * Math.copySign(Math.sqrt(Math.abs(solarCosine)), solarCosine)) * (SKY_WIDTH - 1);
        double y = Math.sqrt(Math.clamp(altitudeKm / SHELL_KM, 0, 1)) * (SKY_HEIGHT - 1);
        int x0 = (int) x, x1 = Math.min(x0 + 1, SKY_WIDTH - 1);
        int y0 = (int) y, y1 = Math.min(y0 + 1, SKY_HEIGHT - 1);
        double dx = x - x0, dy = y - y0;
        return sample(x0, HEIGHT + y0).multiply((1 - dx) * (1 - dy))
                .add(sample(x1, HEIGHT + y0).multiply(dx * (1 - dy)))
                .add(sample(x0, HEIGHT + y1).multiply((1 - dx) * dy))
                .add(sample(x1, HEIGHT + y1).multiply(dx * dy));
    }

    /** Point-source sunlight after actual atmosphere/shadow; output components are within[0,1]. */
    public SpaceVector transmission(double altitudeKm, double solarCosine) {
        requireRay(altitudeKm, solarCosine);
        double altitude = Math.max(0, altitudeKm);
        double r = radiusKm + altitude;
        double horizon = -Math.sqrt(altitude * (2 * radiusKm + altitude)) / r;
        if (solarCosine < horizon) { return SpaceVector.ZERO; }
        if (altitude >= SHELL_KM) {
            double top = radiusKm + SHELL_KM, along = r * solarCosine;
            double discriminant = along * along + top * top - r * r;
            if (along >= 0 || discriminant <= 0) { return new SpaceVector(1, 1, 1); }
            // At shell entry the radial ray projection equals -sqrt(discriminant).
            solarCosine = -Math.sqrt(discriminant) / top;
            altitude = SHELL_KM;
        }
        var value = columns(altitude, solarCosine);
        return new SpaceVector(Math.exp(-RAYLEIGH.x() * value.x() - MIE_EXTINCTION.x() * value.y() - OZONE.x() * value.z()),
                Math.exp(-RAYLEIGH.y() * value.x() - MIE_EXTINCTION.y() * value.y() - OZONE.y() * value.z()),
                Math.exp(-RAYLEIGH.z() * value.x() - MIE_EXTINCTION.z() * value.y() - OZONE.z() * value.z()));
    }

    /** Writes the fixed RGBA32F payload into caller-owned storage without exposing the immutable backing array. */
    public void writeTo(FloatBuffer target) {
        if (target == null || target.remaining() < columns.length) {
            throw new IllegalArgumentException("Atmospheric upload buffer is too small");
        }
        target.put(columns);
    }

    private SpaceVector sample(int x, int y) {
        int index = (y * WIDTH + x) * 4;
        return new SpaceVector(columns[index], columns[index + 1], columns[index + 2]);
    }

    private static SpaceVector integrate(double radius, double altitude, double mu, double distance) {
        double r = radius + altitude, step = distance / INTERVALS;
        double rayleigh = 0, mie = 0, ozone = 0;
        for (int i = 0; i <= INTERVALS; i++) {
            double d = i * step;
            double squaredDifference = altitude * (2 * radius + altitude) + d * (d + 2 * r * mu);
            double height = Math.max(0, squaredDifference / (Math.sqrt(Math.max(0, radius * radius + squaredDifference)) + radius));
            double weight = i == 0 || i == INTERVALS ? 1 : i % 2 == 0 ? 2 : 4;
            rayleigh += weight * Math.exp(-height / 8);
            mie += weight * Math.exp(-height / 1.2);
            ozone += weight * Math.max(0, 1 - Math.abs(height - 25) / 15);
        }
        return new SpaceVector(rayleigh, mie, ozone).multiply(step / 3);
    }

    private static void requireRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0) { throw new IllegalArgumentException("Atmospheric radius must be finite and positive"); }
    }
    private static void requireRay(double altitude, double cosine) {
        if (!Double.isFinite(altitude) || !Double.isFinite(cosine) || cosine < -1 || cosine > 1) {
            throw new IllegalArgumentException("Atmospheric ray requires finite altitude and cosine in[-1,1]");
        }
    }
}
