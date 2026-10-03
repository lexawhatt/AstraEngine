package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContinentalTerrainV4Test {
    private static final ContinentalTerrain LEGACY = new ContinentalTerrain(3, ContinentalTerrain.SEED);
    private static final ContinentalTerrain CURRENT = new ContinentalTerrain(4, ContinentalTerrain.SEED);
    private static final RiverAtlas ATLAS = CURRENT.rivers().orElseThrow();
    private static final SpaceVector REPORTED_VALLEY = new SpaceVector(
            .8268180005447172, .3394324720038389, -.4485059541685025);

    @Test
    void splinePrimitivesRequireLocalUnitGeographyAndKeepFiniteUnitPoints() {
        var start = new SpaceVector(1, 0, 0);
        var end = new SpaceVector(Math.cos(.008), 0, Math.sin(.008));
        var first = new SpaceVector(0, 0, 1);
        var last = new SpaceVector(-Math.sin(.008), 0, Math.cos(.008));
        var curve = new RiverSpline(start, end, first, last, 2100);
        for (int step = 0; step <= 128; step++) {
            var point = curve.point(step / 128.0);
            assertEquals(1, point.length(), 1e-12);
            assertEquals(1, curve.tangent(step / 128.0).length(), 1e-12);
            double nearest = curve.closestFraction(point);
            assertTrue(Double.isFinite(nearest) && nearest >= 0 && nearest <= 1);
            assertTrue(curve.point(nearest).subtract(point).length() * ContinentalTerrain.RADIUS_METERS < .01);
        }
        assertThrows(IllegalArgumentException.class, () -> new RiverSpline(start, start, first, first, 10));
        assertThrows(IllegalArgumentException.class, () -> new RiverSpline(start, end, start, last, 10));
        assertThrows(IllegalArgumentException.class, () -> new RiverSpline(start, end, first, last, Double.NaN));
    }

    @Test
    void junctionCapsContainDenseGeometryAndDoNotApproveRadialBacktracking() {
        var start = new SpaceVector(1, 0, 0);
        var end = new SpaceVector(Math.cos(.008), 0, Math.sin(.008));
        var curve = new RiverSpline(start, end, new SpaceVector(0, 0, 1),
                new SpaceVector(Math.sin(.008), 0, -Math.cos(.008)), 2100);
        var spans = curve.spans(200);
        int rises = 0;
        for (int index = 0; index < spans.length; index++) {
            var span = spans[index];
            double previous = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 128; step++) {
                var point = curve.point((index + step / 128.0) / spans.length);
                assertTrue(point.subtract(span.center()).length() <= span.radius());
                double distance = point.subtract(end).length() * ContinentalTerrain.RADIUS_METERS;
                assertTrue(distance <= span.outletBoundMeters());
                if (distance > previous + 1e-6) {
                    assertTrue(!span.radiallyDecreasing(), "Bernstein proof incorrectly approved a reversing interval");
                    rises++;
                }
                previous = distance;
            }
        }
        assertTrue(rises > 0, "Adversarial endpoint tangent did not exercise a radial reversal");
    }

    @Test
    void savedV3ValleyRetainsItsExactHistoricalHeightTemperatureAndDesert() {
        var sample = LEGACY.sample(REPORTED_VALLEY);
        assertEquals(75.92767817810952, sample.heightMeters(), 1e-9);
        assertEquals(22.52284685967906, sample.temperature(), 1e-9);
        assertEquals(.2909205991420974, sample.moisture(), 1e-12);
        assertEquals(EarthClimate.DESERT, EarthSurfacePalette.material(sample));
        assertTrue(EarthSurfacePalette.material(CURRENT.sample(REPORTED_VALLEY)) != EarthClimate.DESERT,
                "The river revision still leaves the reported artificial desert finger");
    }

    @Test
    void curveRevisionIsLocalizedAndDoesNotMutateExistingVersions() {
        int changed = 0, unchanged = 0;
        for (int i = 0; i < 8000; i++) {
            double y = 1 - 2 * (i + .5) / 8000, angle = i * Math.PI * (3 - Math.sqrt(5));
            double radius = Math.sqrt(1 - y * y);
            var normal = new SpaceVector(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            var before = LEGACY.sample(normal);
            var after = CURRENT.sample(normal);
            var base = ContinentalTerrainV3.base(normal, ContinentalTerrain.SEED);
            assertTrue(after.heightMeters() <= base.heightMeters() + 1e-6);
            assertTrue(after.moisture() >= base.moisture() - 1e-12);
            if (before.equals(after)) { unchanged++; } else { changed++; }
            assertEquals(before, LEGACY.sample(normal));
        }
        assertTrue(changed > 5 && unchanged > 7000, "River correction escaped its bounded valleys");
    }

    @Test
    void reportedCoastNoLongerGrowsArtificialDesertFingersAlongRivers() {
        var grid = SurfaceHeightTile.Grid.at(new GeographicPosition(
                .3518588510742868, .495659856477681, 0).normal(), ContinentalTerrain.RADIUS_METERS, 193, 1024);
        int oldDesert = 0, newDesert = 0;
        for (int z = 0; z < grid.resolution(); z++) {
            for (int x = 0; x < grid.resolution(); x++) {
                var normal = grid.direction((x - 96) * 1024.0, (z - 96) * 1024.0);
                var original = ContinentalTerrainV3.base(normal, ContinentalTerrain.SEED);
                if (EarthSurfacePalette.material(original) == EarthClimate.DESERT) { continue; }
                if (EarthSurfacePalette.material(LEGACY.sample(normal)) == EarthClimate.DESERT) { oldDesert++; }
                if (EarthSurfacePalette.material(CURRENT.sample(normal)) == EarthClimate.DESERT) { newDesert++; }
            }
        }
        assertTrue(oldDesert > 40, "The geographic regression no longer covers the reported defect");
        assertTrue(newDesert < oldDesert * .1, "Most river-induced desert survived: " + newDesert + "/" + oldDesert);
    }

    @Test
    void allConfluencesSharePositionsAndTangentDirectionsWithDownhillWater() {
        int joins = 0;
        for (int cell = 0; cell < ATLAS.cellCount(); cell++) {
            int parent = ATLAS.downstream(cell);
            if (parent >= 0) { assertTrue(ATLAS.waterMeters(parent) <= ATLAS.waterMeters(cell)); }
            if (!ATLAS.river(cell) || !ATLAS.river(parent)) { continue; }
            assertTrue(ATLAS.curve(cell).tangent(0).dot(ATLAS.point(cell, 1).subtract(ATLAS.point(cell, 0))) >= -1e-12,
                    "A main channel initially reverses against its own outgoing route");
            assertTrue(ATLAS.point(cell, 1).subtract(ATLAS.point(parent, 0)).length() < 1e-12);
            assertTrue(ATLAS.curve(cell).tangent(1).dot(ATLAS.curve(parent).tangent(0)) > 1 - 1e-10);
            joins++;
        }
        assertTrue(joins > 1000);
    }

    @Test
    void overlappingDryBanksCannotDrainAnActualTributaryAndConfluenceHasOneWaterLevel() {
        int tributary = 28594;
        int confluence = 28850;
        assertEquals(confluence, ATLAS.downstream(tributary));
        var point = ATLAS.point(tributary, 15 / 16.0);
        var sample = CURRENT.sample(point);
        double tributaryLevel = ATLAS.waterMeters(tributary) / 16 + ATLAS.waterMeters(confluence) * 15 / 16;
        // This location lies inside the narrow tributary and three neighboring dry valley cuts. The
        // lowest dry bank used to overwrite water with the solid bed, leaving a 28 m deep dry gap.
        assertTrue(sample.waterMeters() <= tributaryLevel + 1e-6);
        assertTrue(sample.waterMeters() >= ATLAS.waterMeters(confluence) - 1e-6);
        assertTrue(sample.waterMeters() > sample.heightMeters() + 1);
        for (int cell : new int[]{28593, 28594, 28595, 28851}) {
            assertEquals(confluence, ATLAS.downstream(cell));
            assertEquals(ATLAS.waterMeters(confluence), CURRENT.sample(ATLAS.point(cell, 1)).waterMeters(), 1e-6);
            for (int step = 0; step <= 128; step++) {
                assertTrue(CURRENT.sample(ATLAS.point(cell, step / 128.0)).water(),
                        "An overlapping confluence incision left a dry channel");
            }
        }
        assertEquals(ATLAS.waterMeters(confluence),
                CURRENT.sample(ATLAS.point(confluence, 0)).waterMeters(), 1e-6);
    }

    @Test
    void composedWaterNeverRisesThroughAnyBranchOrNestedConfluence() {
        int checked = 0;
        for (int cell = 0; cell < ATLAS.cellCount(); cell++) {
            if (!ATLAS.river(cell)) { continue; }
            double previous = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 16; step++) {
                var sample = CURRENT.sample(ATLAS.point(cell, step / 16.0));
                assertTrue(sample.water(), "Composed channel has a dry gap at " + cell + "/" + step);
                assertTrue(sample.waterMeters() <= previous + 1e-6,
                        "Composed water rises downstream at " + cell + "/" + step);
                previous = sample.waterMeters();
            }
            checked++;
        }
        assertEquals(ATLAS.riverCount(), checked);
        // The measured ownership change occurs between these samples; inspect entry and exit densely.
        for (int cell : new int[]{273441, 273697, 273442}) {
            double previous = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 1024; step++) {
                var sample = CURRENT.sample(ATLAS.point(cell, step / 1024.0));
                assertTrue(sample.waterMeters() <= previous + 1e-6,
                        "Wet overlap entry/exit raises water at " + cell + "/" + step);
                previous = sample.waterMeters();
            }
        }
    }

    @Test
    void denseCurveAndValleySamplesStayInsideBothCandidateBoundsIncludingFaceSeams() {
        int checked = 0, seams = 0;
        for (int cell = 0; cell < ATLAS.cellCount(); cell += 29) {
            if (!ATLAS.river(cell)) { continue; }
            var curve = ATLAS.curve(cell);
            double previousWater = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 16; step++) {
                double t = step / 16.0;
                var point = curve.point(t);
                var sample = CURRENT.sample(point);
                assertTrue(sample.water(), "Shared sampler lost a curved channel at " + cell + "/" + step);
                assertTrue(sample.waterMeters() <= previousWater + 1e-6,
                        "Composed channel water rises downstream at " + cell + "/" + step);
                previousWater = sample.waterMeters();
                var across = PlanetaryFrame.cross(point, curve.tangent(t)).normalized();
                for (int sign : new int[]{-1, 1}) {
                    var normal = point.add(across.multiply(sign * 2100 / ContinentalTerrain.RADIUS_METERS)).normalized();
                    assertTrue(curve.containsCandidate(normal), "Bezier bound discarded a real curve neighborhood");
                    assertTrue(ATLAS.candidateNeighborhoodContains(cell, normal), "Cube candidate neighborhood lost a river");
                }
                if (CubeFace.containing(point) != CubeFace.containing(curve.point(0))) { seams++; }
            }
            checked++;
        }
        assertTrue(checked > 1000 && seams > 0);
    }

    @Test
    void closestPointAgreesWithIndependentDenseProjectionAndCenterlinesStayWet() {
        var reversing = ATLAS.curve(333672);
        var missed = reversing.point(13 / 16.0);
        assertTrue(reversing.point(reversing.closestFraction(missed)).subtract(missed).length()
                        * ContinentalTerrain.RADIUS_METERS < .01,
                "A late local minimum hid the exact point on a reversing tributary");
        int checked = 0;
        for (int cell = 7; cell < ATLAS.cellCount() && checked < 32; cell += 61) {
            if (!ATLAS.river(cell) || ATLAS.waterMeters(cell) < 30) { continue; }
            var curve = ATLAS.curve(cell);
            for (int step = 1; step < 16; step++) {
                double t = step / 16.0;
                var point = curve.point(t);
                var normal = point.add(PlanetaryFrame.cross(point, curve.tangent(t)).normalized()
                        .multiply(73 / ContinentalTerrain.RADIUS_METERS)).normalized();
                double actual = curve.point(curve.closestFraction(normal)).subtract(normal).length();
                double dense = Double.POSITIVE_INFINITY;
                for (int i = 0; i <= 8192; i++) {
                    dense = Math.min(dense, curve.point(i / 8192.0).subtract(normal).length());
                }
                assertTrue((actual - dense) * ContinentalTerrain.RADIUS_METERS < 1,
                        "Closest curve projection diverges from independently sampled geometry");
                assertTrue(CURRENT.sample(point).water(), "A curved channel has a dry gap at " + cell + "/" + step);
            }
            checked++;
        }
        assertEquals(32, checked);
    }

    @Test
    void immutableChartLookupsKeepEverySavedVersionIndependent() {
        for (int version = 1; version <= 4; version++) {
            assertEquals(36, EarthChart.all(version).size());
            for (var chart : EarthChart.all(version)) {
                assertEquals(version, chart.terrainVersion());
                assertEquals(chart, EarthChart.forDimension(chart.dimensionId(), version).orElseThrow());
            }
            var upper = new EarthChart(CubeFace.POSITIVE_Y, EarthChart.MAX_BAND, version);
            assertEquals(upper, EarthChart.forDimension(upper.dimensionId(), version).orElseThrow());
        }
    }
}
