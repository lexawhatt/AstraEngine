package dev.lexawhatt.astraengine.client.sky;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Immutable presentation inputs for one body-fixed Earth cloud field. Kilometers; no clock is advanced here. */
public record EarthCloudState(float cover, float season, float rain, float thunder, float incident,
                              float windX, float windZ) {
    public static final double BASE_KM = .85;
    public static final double TOP_KM = 8.5;
    public static final long WIND_PERIOD_TICKS = 432_000;

    /** Cover is -1 for seasonal weather, otherwise [0,1]; zero disables every cloud contribution. */
    public EarthCloudState {
        if (!Float.isFinite(cover) || cover < -1 || cover > 1 || cover < 0 && cover != -1 || !Float.isFinite(season)
                || Math.abs(season) > 1 || !Float.isFinite(rain) || rain < 0 || rain > 1
                || !Float.isFinite(thunder) || thunder < 0 || thunder > 1
                || !Float.isFinite(incident) || incident < 0 || !Float.isFinite(windX) || !Float.isFinite(windZ)) {
            throw new IllegalArgumentException("Invalid Earth cloud presentation inputs");
        }
    }

    /** Samples existing synchronized host game ticks, separate from the seasonal day-time clock. */
    public static EarthCloudState sample(long gameTicks, double partialTick, float cover, double seasonPhase,
                                         float rain, float thunder, float incident) {
        if (!Double.isFinite(partialTick) || partialTick < 0 || partialTick > 1
                || !Double.isFinite(seasonPhase)) { throw new IllegalArgumentException("Finite cloud clock required"); }
        double angle = (Math.floorMod(gameTicks, WIND_PERIOD_TICKS) + partialTick) / WIND_PERIOD_TICKS * Math.PI * 2;
        return new EarthCloudState(cover, (float) Math.sin(seasonPhase * Math.PI * 2), rain, thunder, incident,
                (float) (12 * Math.sin(angle)), (float) (12 * (Math.cos(angle) - 1)));
    }

    /** Same latitude-dependent coverage as the ground sky, evaluated at the actual cloud rather than the camera. */
    public double coverage(SpaceVector bodyPointKm) {
        if (bodyPointKm == null || bodyPointKm.length() == 0) { throw new IllegalArgumentException("Cloud point required"); }
        if (cover >= 0) { return cover; }
        double latitude = Math.asin(Math.clamp(bodyPointKm.y() / bodyPointKm.length(), -1, 1));
        double warmth = .5 + .5 * season * Math.clamp(latitude / (Math.PI / 6), -1, 1);
        return Math.clamp(.38 + (1 - warmth) * .09 + rain * .48, 0, 1);
    }

    /** Shared field coordinates never depend on cube-face identity or an altitude storage band. */
    public SpaceVector advected(SpaceVector bodyPointKm) {
        if (bodyPointKm == null) { throw new IllegalArgumentException("Cloud point required"); }
        return bodyPointKm.add(new SpaceVector(windX * 10, 0, windZ * 10));
    }

    /** CPU reference for the shader's resolved density, using the same immutable noise atlas. */
    public double density(CloudNoiseField noise, SpaceVector bodyPointKm, double radiusKm) {
        if (noise == null || bodyPointKm == null || !Double.isFinite(radiusKm) || radiusKm <= 0) {
            throw new IllegalArgumentException("Cloud noise and positive radius required");
        }
        double altitude = bodyPointKm.length() - radiusKm;
        if (cover == 0 || altitude <= BASE_KM || altitude >= TOP_KM) { return 0; }
        var region = EarthCloudWeather.sample(noise, this, bodyPointKm);
        SpaceVector p = advected(bodyPointKm);
        SpaceVector tops = EarthCloudWeather.tops(region);
        double broad = noise.sample(p.x() * .18, p.y() * .18 + 11.7, p.z() * .18);
        double billow = 1 - Math.abs(noise.sample(p.x() * .75, p.y() * .75 + 31.1, p.z() * .75) * 2 - 1);
        double body = smooth(.30, .72, broad * .72 + billow * .28);
        double erosion = noise.sample(p.x() * 3, p.y() * 3 + 71.3, p.z() * 3);
        body *= .72 + erosion * .28;
        double sheet = .075 * region.stratus() * (.65 + broad * .70)
                * EarthCloudWeather.profile(.85, tops.x(), altitude, altitude);
        double cumulus = .18 * region.cumulus() * body
                * EarthCloudWeather.profile(1.3, tops.y(), altitude, altitude);
        double convection = .14 * region.convection() * body
                * EarthCloudWeather.profile(1.4, tops.z(), altitude, altitude);
        return sheet + cumulus + convection;
    }

    private static double smooth(double low, double high, double value) {
        double x = Math.clamp((value - low) / (high - low), 0, 1);
        return x * x * (3 - 2 * x);
    }
}
