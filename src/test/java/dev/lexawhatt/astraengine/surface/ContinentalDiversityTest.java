package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinentalDiversityTest {
    @Test
    void equalAreaSurveyRetainsLowlandsDryRegionsForestsAndHighMountains() {
        var terrain = new ContinentalTerrain(3, ContinentalTerrain.SEED);
        var counts = new EnumMap<EarthClimate, Integer>(EarthClimate.class);
        int land = 0, lowlands = 0, uplands = 0, peaks = 0;
        double minimum = 0, maximum = 0;
        for (int i = 0; i < 8000; i++) {
            double y = 1 - 2 * (i + .5) / 8000, angle = i * Math.PI * (3 - Math.sqrt(5));
            double r = Math.sqrt(1 - y * y);
            var sample = terrain.sample(new SpaceVector(Math.cos(angle) * r, y, Math.sin(angle) * r));
            counts.merge(EarthClimate.at(sample), 1, Integer::sum);
            minimum = Math.min(minimum, sample.heightMeters()); maximum = Math.max(maximum, sample.heightMeters());
            if (sample.water()) { continue; }
            land++;
            if (sample.heightMeters() < 600) { lowlands++; }
            if (sample.heightMeters() > 1000 && sample.heightMeters() < 2500) { uplands++; }
            if (sample.heightMeters() > 6000) { peaks++; }
        }
        assertTrue(minimum < -5500 && maximum > 8000, "Lost deep basins or major ranges");
        assertTrue(lowlands > land * .1 && uplands > land * .1 && peaks > 5, "Collapsed physiographic range");
        for (var climate : new EarthClimate[]{EarthClimate.DESERT, EarthClimate.JUNGLE, EarthClimate.FOREST,
                EarthClimate.PLAINS, EarthClimate.TAIGA, EarthClimate.SNOW, EarthClimate.ALPINE}) {
            assertTrue(counts.getOrDefault(climate, 0) > 50, "Lost regional climate: " + climate);
        }
        assertTrue(counts.get(EarthClimate.SNOW) < land * .4, "Snow again dominates the continental area");
    }
}
