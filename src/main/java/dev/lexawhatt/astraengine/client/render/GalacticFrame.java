package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Pure observer extraction for one spatial, artistic Milky Way. Sol is the galactic coordinate origin;
 * body and flight coordinates remain local double meters. This is presentation data, not a galaxy catalog.
 */
public record GalacticFrame(SpaceVector observerRadii, float backgroundSeed) {
    public static final SpaceVector CENTER_LIGHT_YEARS = new SpaceVector(26_000, 0, 0);
    public static final double RADIUS_LIGHT_YEARS = 50_000;
    private static final double MAX_SHADER_COORDINATE = 1_000_000;

    /** Creates a finite GPU-scale frame; the bound covers all signed-int generated sectors plus supported flight. */
    public GalacticFrame {
        if (observerRadii == null || observerRadii.length() > MAX_SHADER_COORDINATE
                || !Float.isFinite(backgroundSeed) || backgroundSeed < 0 || backgroundSeed >= 4096) {
            throw new IllegalArgumentException("Invalid bounded galactic presentation frame");
        }
    }

    /**
     * Converts local meters to light-years and combines them with the system origin before narrowing on GPU upload.
     * Changing systems at the same absolute position preserves this background. Only the connection's galaxy seed
     * selects its structure; system visual seeds and time do not. Null and unsupported numeric ranges are rejected.
     */
    public static GalacticFrame extract(CosmosSystem system, SpaceVector cameraMeters, long galaxySeed) {
        if (system == null || cameraMeters == null) {
            throw new IllegalArgumentException("Galactic rendering requires a system and virtual camera");
        }
        SpaceVector absoluteLightYears = system.galaxyPosition()
                .add(cameraMeters.multiply(1.0 / CosmosGenerator.LIGHT_YEAR));
        SpaceVector observer = absoluteLightYears.subtract(CENTER_LIGHT_YEARS).multiply(1.0 / RADIUS_LIGHT_YEARS);
        long mixed = galaxySeed ^ galaxySeed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return new GalacticFrame(observer, Math.floorMod(mixed, 4096));
    }
}
