package dev.lexawhatt.astraengine.surface;

/** Stable version-one climate classes used by the new Earth generator and its presentation palette. */
public enum EarthClimate {
    DEEP_OCEAN, OCEAN, FROZEN_OCEAN, BEACH, SNOW, ALPINE, DESERT, SAVANNA, JUNGLE, TAIGA, FOREST, PLAINS;

    /** Classifies the shared immutable field, without reading Minecraft registries or changing terrain height. */
    public static EarthClimate at(ContinentalTerrain.Sample sample) {
        if (sample == null) { throw new IllegalArgumentException("Earth climate requires a terrain sample"); }
        if (sample.heightMeters() < -5) {
            return sample.temperature() < -4 ? FROZEN_OCEAN : sample.heightMeters() < -600 ? DEEP_OCEAN : OCEAN;
        }
        if (sample.temperature() < -2) { return SNOW; }
        if (sample.heightMeters() < 5) { return BEACH; }
        if (sample.heightMeters() > 2400) { return ALPINE; }
        if (sample.temperature() > 18 && sample.moisture() < .30) { return DESERT; }
        if (sample.temperature() > 21 && sample.moisture() < .48) { return SAVANNA; }
        if (sample.temperature() > 21 && sample.moisture() > .59) { return JUNGLE; }
        if (sample.temperature() < 9) { return TAIGA; }
        return sample.moisture() > .43 ? FOREST : PLAINS;
    }
}
