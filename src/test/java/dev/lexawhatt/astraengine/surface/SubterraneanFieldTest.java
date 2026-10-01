package dev.lexawhatt.astraengine.surface;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubterraneanFieldTest {
    @Test
    void currentPassagesRetainRockAndHumanScaleClearance() {
        var field = new SubterraneanField(2, ContinentalTerrain.SEED);
        int n = 128, total = n * n * n, count = 0, longest = 0;
        int[] clearance = new int[129];
        // At the +X equator, physical X is vertical; this is a 256-m cube on a two-meter grid.
        for (int z = 0; z < n; z++) {
            for (int y = 0; y < n; y++) {
                int run = 0;
                for (int x = 0; x <= n; x++) {
                    boolean open = x < n && field.density(EarthChart.RADIUS_METERS - 350 + x * 2,
                            y * 2 - 128, z * 2 - 128) > 0;
                    if (open) { count++; run++; }
                    else if (run > 0) { clearance[run]++; longest = Math.max(longest, run); run = 0; }
                }
            }
        }
        int humanScale = 0, passages = 0;
        for (int i = 1; i < clearance.length; i++) {
            passages += clearance[i];
            if (i * 2 <= 14) { humanScale += clearance[i]; }
        }
        assertTrue(count > total * .005 && count < total * .06, "Open fraction: " + (double) count / total);
        assertTrue(humanScale > passages * .95, "Ordinary passage ceilings became giant chambers");
        assertTrue(longest * 2 < 80, "Sample opened an unbounded vertical room");
        assertEquals(1200, field.maxDepthMeters());
        assertEquals(2400, new SubterraneanField(1, field.seed()).maxDepthMeters());
    }

    @Test
    void currentEnvelopeKeepsSeaRoofsAndClosesWithinItsSavedDepth() {
        var field = new SubterraneanField(2, ContinentalTerrain.SEED);
        assertFalse(field.carves(4, 0, -63));
        assertTrue(field.carves(4, 0, -65));
        assertFalse(field.carves(1, 200, 198));
        assertTrue(field.carves(3, 200, 198));
        assertTrue(field.carves(3, 200, -900));
        assertFalse(field.carves(3, 200, -970));
        assertFalse(field.carves(30, 200, -1001));
        assertFalse(field.carves(30, 200, 201));
        assertThrows(IllegalArgumentException.class, () -> field.carves(Double.NaN, 0, 0));
    }

    @Test
    void preservesLegacyAndRejectsUnsupportedOrNonFiniteInputs() {
        var legacy = new SubterraneanField(0, 5);
        assertFalse(SubterraneanField.isVoid(legacy.density(1, 2, 3), 700, 0));
        assertThrows(IllegalArgumentException.class, () -> new SubterraneanField(3, 5));
        assertThrows(IllegalArgumentException.class, () -> legacy.density(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SubterraneanField.isVoid(1, 2, Double.POSITIVE_INFINITY));
    }

    @Test
    void protectsSeabedsAndClosesDeepEnvelopeButPermitsBroadDryEntrances() {
        assertFalse(SubterraneanField.isVoid(30, -1500, -1505));
        assertFalse(SubterraneanField.isVoid(30, 0, -63));
        assertTrue(SubterraneanField.isVoid(30, 0, -65));
        assertFalse(SubterraneanField.isVoid(8, 100, 95));
        assertTrue(SubterraneanField.isVoid(20, 100, 95));
        assertFalse(SubterraneanField.isVoid(20, 100, 101));
        assertFalse(SubterraneanField.isVoid(20, 100, -2300));
        assertTrue(SubterraneanField.isVoid(20, 100, -2200));
    }

    @Test
    void physicalFieldDoesNotDependOnChartOrBandAndIsDeterministic() {
        var first = new SubterraneanField(1, ContinentalTerrain.SEED);
        var second = new SubterraneanField(1, ContinentalTerrain.SEED);
        double r = EarthChart.RADIUS_METERS;
        var a = new EarthChart(CubeFace.POSITIVE_X, 0).normal(r, 150);
        for (CubeFace adjacent : CubeFace.values()) {
            if (a.dot(adjacent.outward()) <= 0) { continue; }
            double x = a.dot(adjacent.u()) * r / a.dot(adjacent.outward());
            double z = a.dot(adjacent.v()) * r / a.dot(adjacent.outward());
            for (int band = -2; band <= 3; band++) {
                var chart = new EarthChart(adjacent, band);
                for (double altitude : new double[]{-2032, -2031, 620, 2032}) {
                    var p = a.multiply(r + altitude);
                    var q = chart.normal(x, z).multiply(r + altitude);
                    assertEquals(first.density(p.x(), p.y(), p.z()), second.density(q.x(), q.y(), q.z()), 1e-7);
                }
            }
        }
        assertNotEquals(first.density(r, 50, 70), new SubterraneanField(1, ContinentalTerrain.SEED + 1).density(r, 50, 70));
    }

    @Test
    void connectsLargeChambersAcrossHundredsOfMetersWithoutRemovingMostRock() {
        var field = new SubterraneanField(1, ContinentalTerrain.SEED);
        int n = 96, total = n * n * n, count = 0, largest = 0;
        boolean[] open = new boolean[total];
        int[] queue = new int[total];
        for (int z = 0; z < n; z++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int i = x + n * (y + n * z);
                    open[i] = field.density(EarthChart.RADIUS_METERS - 900 + y * 8, x * 8 - 384, z * 8 - 384) > 0;
                    if (open[i]) { count++; }
                }
            }
        }
        for (int i = 0; i < total; i++) {
            if (!open[i]) { continue; }
            int size = 1, start = 0;
            queue[0] = i; open[i] = false;
            while (start < size) {
                int a = queue[start++], x = a % n, y = a / n % n, z = a / (n * n);
                int[] neighbors = {x > 0 ? a - 1 : -1, x + 1 < n ? a + 1 : -1,
                        y > 0 ? a - n : -1, y + 1 < n ? a + n : -1,
                        z > 0 ? a - n * n : -1, z + 1 < n ? a + n * n : -1};
                for (int b : neighbors) {
                    if (b >= 0 && open[b]) { open[b] = false; queue[size++] = b; }
                }
            }
            largest = Math.max(largest, size);
        }
        assertTrue(count > total * .1 && count < total * .35, "Cave fraction: " + count + "/" + total);
        assertTrue(largest > count * .8, "Disconnected bubbles: " + largest + "/" + count);
        // Version-one fixture locks the accepted topology independently of later surface geology changes.
        assertEquals(186501, count);
        assertEquals(171412, largest);
    }
}
