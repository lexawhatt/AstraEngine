package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/** Permanent full-face storage band of one saved solid body, independent of a loaded level instance. */
public record PlanetChart(SolidPlanetProfile profile, CubeFace face, int band) implements CubeStorageChart {
    public static final int MIN_Y = -2032;
    public static final int HEIGHT = 4064;
    public static final int MIN_BAND = -2;
    public static final int MAX_BAND = 25;

    public PlanetChart {
        if (profile == null || face == null || band < MIN_BAND || band > MAX_BAND) {
            throw new IllegalArgumentException("A planetary chart requires a solid profile, face and band in [-2,25]");
        }
        if ((long) band * HEIGHT + MIN_Y + HEIGHT <= Math.ceil(1 - profile.radiusMeters())) {
            throw new IllegalArgumentException("A planetary chart cannot lie entirely below its solid body's core");
        }
    }

    @Override public double radiusMeters() { return profile.radiusMeters(); }
    @Override public int altitudeOriginMeters() { return band * HEIGHT; }
    @Override public int minY() { return MIN_Y; }
    @Override public int height() { return HEIGHT; }
    @Override public String geographyId() { return profile.geographyId(); }
    @Override public String dimensionId() {
        return "astraengine:planet/" + profile.bindingKey() + "/" + face.id() + "/"
                + (band < 0 ? "below_" : "above_") + Math.abs(band);
    }
    @Override public PlanetaryTopology topology() { return new PlanetaryTopology(PlanetaryTopology.VERSION, 12, radiusMeters()); }
    @Override public Optional<CubeStorageChart> chart(CubeFace face, int band) {
        if (face == null) { throw new IllegalArgumentException("A cube face is required"); }
        return band < MIN_BAND || band > MAX_BAND
                || (long) band * HEIGHT + MIN_Y + HEIGHT <= Math.ceil(1 - radiusMeters())
                ? Optional.empty() : Optional.of(new PlanetChart(profile, face, band));
    }

    /** Lowest radial block with a nonsingular physical position; smaller bodies receive a solid core here. */
    public int coreFloorY() { return (int) Math.ceil(1 - radiusMeters()) - altitudeOriginMeters(); }

    @Override public boolean contains(SpaceVector feet) {
        return CubeStorageChart.super.contains(feet) && feet.y() >= coreFloorY();
    }

    @Override public Optional<CubeStorageChart> ownerChart(GeographicPosition address) {
        if (address == null) { throw new IllegalArgumentException("A geographic address is required"); }
        return address.altitudeMeters() < Math.ceil(1 - radiusMeters())
                ? Optional.empty() : CubeStorageChart.super.ownerChart(address);
    }

    /** Pure canonical ownership lookup; it never allocates a world or changes a saved binding. */
    public static Optional<PlanetChart> owner(SolidPlanetProfile profile, GeographicPosition address) {
        return new PlanetChart(profile, CubeFace.POSITIVE_X, 0).ownerChart(address).map(value -> (PlanetChart) value);
    }
}
