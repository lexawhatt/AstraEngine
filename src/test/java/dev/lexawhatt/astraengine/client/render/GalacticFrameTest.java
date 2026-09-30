package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalacticFrameTest {
    @Test
    void solAndExternalCamerasUseOnePhysicalGalacticFrame() {
        GalacticFrame local = GalacticFrame.extract(CosmosGenerator.sol(), SpaceVector.ZERO, 17);
        assertEquals(-0.52, local.observerRadii().x(), 1e-15);
        assertEquals(0, local.observerRadii().y());
        assertEquals(0, local.observerRadii().z());
        SpaceVector outside = GalacticFrame.CENTER_LIGHT_YEARS.add(new SpaceVector(0, 75_000, 0))
                .multiply(CosmosGenerator.LIGHT_YEAR);
        GalacticFrame external = GalacticFrame.extract(CosmosGenerator.sol(), outside, 17);
        assertEquals(0, external.observerRadii().x(), 1e-15);
        assertEquals(1.5, external.observerRadii().y(), 1e-15);
        assertEquals(0, external.observerRadii().z());
        assertEquals(local.backgroundSeed(), external.backgroundSeed());
    }

    @Test
    void rebasingSystemsPreservesTheSameAbsoluteObserverAndBackgroundSeed() {
        CosmosSystem first = system(new SpaceVector(-40, 17, 3), 1);
        CosmosSystem second = system(new SpaceVector(400, -9, 7), Long.MAX_VALUE);
        SpaceVector absolute = new SpaceVector(12_345, 76_543, -15_555);
        GalacticFrame frameA = GalacticFrame.extract(first,
                absolute.subtract(first.galaxyPosition()).multiply(CosmosGenerator.LIGHT_YEAR), 427);
        GalacticFrame frameB = GalacticFrame.extract(second,
                absolute.subtract(second.galaxyPosition()).multiply(CosmosGenerator.LIGHT_YEAR), 427);
        assertEquals(0, frameA.observerRadii().distance(frameB.observerRadii()), 1e-14);
        assertEquals(frameA.backgroundSeed(), frameB.backgroundSeed());
    }

    @Test
    void systemSeedsCannotChangeTheSharedBackgroundAtTheSamePosition() {
        GalacticFrame first = GalacticFrame.extract(system(SpaceVector.ZERO, Long.MIN_VALUE), SpaceVector.ZERO, 18);
        GalacticFrame second = GalacticFrame.extract(system(SpaceVector.ZERO, Long.MAX_VALUE), SpaceVector.ZERO, 18);
        assertEquals(first, second);
        for (long seed : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            GalacticFrame frame = GalacticFrame.extract(CosmosGenerator.sol(), SpaceVector.ZERO, seed);
            assertTrue(Float.isFinite(frame.backgroundSeed()));
            assertTrue(frame.backgroundSeed() >= 0 && frame.backgroundSeed() < 4096);
        }
    }

    @Test
    void diskCrossingChangesPositionContinuouslyWithoutAnInsideOutsideSwitch() {
        GalacticFrame below = GalacticFrame.extract(CosmosGenerator.sol(),
                new SpaceVector(0, -CosmosGenerator.LIGHT_YEAR, 0), 7);
        GalacticFrame above = GalacticFrame.extract(CosmosGenerator.sol(),
                new SpaceVector(0, CosmosGenerator.LIGHT_YEAR, 0), 7);
        assertEquals(2 / GalacticFrame.RADIUS_LIGHT_YEARS,
                below.observerRadii().distance(above.observerRadii()), 1e-15);
        assertEquals(below.backgroundSeed(), above.backgroundSeed());
    }

    @Test
    void maximumSupportedFlightAndGeneratedSectorOffsetsRemainFiniteOnGpu() {
        CosmosSystem remote = CosmosGenerator.generate(7, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        GalacticFrame frame = GalacticFrame.extract(remote,
                new SpaceVector(0, 1_000_000 * CosmosGenerator.LIGHT_YEAR, 0), 7);
        for (double coordinate : new double[] {frame.observerRadii().x(), frame.observerRadii().y(),
                frame.observerRadii().z()}) {
            assertTrue(Float.isFinite((float) coordinate));
            assertTrue(Math.abs(coordinate) < 1_000_000);
        }
    }

    @Test
    void malformedProjectionInputsAreRejectedBeforeGpuUpload() {
        assertThrows(IllegalArgumentException.class, () -> GalacticFrame.extract(null, SpaceVector.ZERO, 7));
        assertThrows(IllegalArgumentException.class, () -> GalacticFrame.extract(CosmosGenerator.sol(), null, 7));
        assertThrows(IllegalArgumentException.class, () -> GalacticFrame.extract(CosmosGenerator.sol(),
                new SpaceVector(Double.MAX_VALUE, 0, 0), 7));
        assertThrows(IllegalArgumentException.class, () -> new GalacticFrame(SpaceVector.ZERO, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GalacticFrame(SpaceVector.ZERO, 4096));
    }

    private static CosmosSystem system(SpaceVector galaxyPosition, long systemSeed) {
        return new CosmosSystem("fixture", "Fixture", systemSeed, CosmosSystem.Kind.SINGLE,
                galaxyPosition, CosmosGenerator.sol().bodies());
    }
}
