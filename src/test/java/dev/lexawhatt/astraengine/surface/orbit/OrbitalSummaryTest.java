package dev.lexawhatt.astraengine.surface.orbit;

import dev.lexawhatt.astraengine.surface.CubeFace;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OrbitalSummaryTest {
    private static final OrbitalSurface EARTH = new OrbitalSurface("sol", "earth", "minecraft:overworld",
            CubeFace.POSITIVE_X, 6_371_000, 0);

    @Test void sparseObservedAreaDoesNotInventAnEntireCityOrRetainRemovedEmission() {
        var lamp = patch(0, 0, 1, 0xffffff, 1);
        var page = new OrbitalPage(OrbitalPage.Key.of(lamp), Map.of()).with(lamp);
        var coarse = page.coarse(1);
        assertEquals(1f / 16, coarse.cells().getFirst().coverage());
        assertEquals(1, coarse.cells().getFirst().emission());
        assertEquals(0, coarse.cells().get(1).coverage());
        var dark = patch(0, 0, 2, 0x123456, 0);
        var replacement = page.with(dark).coarse(2);
        assertEquals(0, replacement.cells().getFirst().emission());
        assertEquals(0x123456, replacement.cells().getFirst().rgb());
        assertEquals(1, page.coarse(1).cells().getFirst().emission(), "Old immutable snapshot changed");
    }

    @Test void negativeCoordinatesAndForeignBodyIdentitiesNeverAlias() {
        var chunk = patch(-1, -17, 1, 0xff0000, 0);
        var key = OrbitalPage.Key.of(chunk);
        assertEquals(-1, key.x()); assertEquals(-2, key.z()); assertEquals(255, OrbitalPage.slot(-1, -17));
        var page = new OrbitalPage(key, Map.of()).with(chunk);
        assertEquals(1f / 16, page.coarse(1).cells().get(15).coverage());
        assertThrows(IllegalArgumentException.class, () -> page.with(patch(0, 0, 1, 0, 0)));
        var moon = new OrbitalSurface("sol", "moon", "astraengine:moon", CubeFace.POSITIVE_X, 1_737_400, 0);
        assertThrows(IllegalArgumentException.class, () -> page.with(new OrbitalPatch(moon, -1, -17, 0, 1, chunk.cells())));
    }

    @Test void dirtyUnloadedNeighborsDoNotHideCleanSettlementsOrRetainStaleLight() {
        var clean = patch(0, 0, 1, 0xff0000, .5f);
        var stale = patch(1, 0, 2, 0xffffff, 1);
        var page = new OrbitalPage(OrbitalPage.Key.of(clean), Map.of()).with(clean).with(stale);
        var pending = new java.util.BitSet(256);
        pending.set(OrbitalPage.slot(1, 0));
        pending.set(OrbitalPage.slot(8, 8)); // Newly loaded but unobserved neighboring terrain.
        var visible = page.coarse(2, pending).cells().getFirst();
        assertEquals(1f / 16, visible.coverage());
        assertEquals(.5f, visible.emission());
        assertEquals(0xff0000, visible.rgb());
        assertEquals(2, page.chunks().size(), "Filtering must not erase durable pending observations");
    }

    @Test void pageAndCellsDefensivelyCopyAndValidateFiniteWireValues() {
        var cells = new java.util.ArrayList<>(Collections.nCopies(16, OrbitalPatch.Cell.EMPTY));
        var patch = new OrbitalPatch(EARTH, 0, 0, 0, 1, cells); cells.clear();
        assertEquals(16, patch.cells().size()); assertFalse(patch.visible());
        var chunks = new HashMap<Integer, OrbitalPatch>(); chunks.put(0, patch);
        var page = new OrbitalPage(OrbitalPage.Key.of(patch), chunks); chunks.clear();
        assertEquals(1, page.chunks().size());
        assertThrows(IllegalArgumentException.class, () -> new OrbitalPatch.Cell(Float.NaN, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalPatch.Cell(0, 0, 1.1f, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalPatch(EARTH, 0, 0, 3, 1, patch.cells()));
    }

    @Test void sparseAtlasFitsFullBudgetAcrossSignedCoordinatesFacesAndTwoDetailLevels() {
        boolean[] occupied = new boolean[OrbitalHash.SLOTS]; int longest = 0;
        for (int i = 0; i < 4096; i++) {
            int x = i * 7919 % 798000 - 399000, z = i * 3571 % 798000 - 399000;
            int marker = OrbitalHash.marker(i % 12, i % 6, i % 2 == 0 ? 0 : 4);
            int start = OrbitalHash.slot(x, z, marker), probes = 0;
            while (occupied[(start + probes) & (OrbitalHash.SLOTS - 1)]) { probes++; }
            assertTrue(probes < OrbitalHash.MAX_PROBES);
            occupied[(start + probes) & (OrbitalHash.SLOTS - 1)] = true; longest = Math.max(longest, probes);
        }
        assertTrue(longest < 128);
        assertTrue(OrbitalHash.marker(0, 0, 0) != OrbitalHash.marker(0, 0, 4));
    }

    private static OrbitalPatch patch(int x, int z, long revision, int rgb, float emission) {
        return new OrbitalPatch(EARTH, x, z, 0, revision,
                Collections.nCopies(16, new OrbitalPatch.Cell(120, rgb, emission, 1)));
    }
}
