package dev.lexawhatt.astraengine.systems;

import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.api.StellarStage;
import dev.lexawhatt.astraengine.api.SystemDescriptor;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarSystemTest {
    private StellarSystem system() {
        return new StellarSystem(SystemGenerator.generate("alpha", 123456789L));
    }

    @Test
    void generationIsIndependentOfVisitOrder() {
        SystemDescriptor alpha = SystemGenerator.generate("alpha", 123456789L);
        SystemGenerator.generate("beta", -123L);
        assertEquals(alpha, SystemGenerator.generate("alpha", 123456789L));
        assertNotEquals(alpha, SystemGenerator.generate("alpha", 123456788L));
        assertEquals(1_000_000L, alpha.resourceCapacity());
        assertTrue(alpha.temperatureKelvin() >= 3500 && alpha.temperatureKelvin() <= 9000);
    }

    @Test
    void phaseBoundariesAndPartialExtractionAreExact() {
        StellarSystem system = system();
        system.extract(UUID.randomUUID(), 649_999);
        assertEquals(StellarStage.ACTIVE, system.snapshot().stage());
        system.extract(UUID.randomUUID(), 1);
        assertEquals(StellarStage.UNSTABLE, system.snapshot().stage());
        ExtractionResult last = system.extract(UUID.randomUUID(), Long.MAX_VALUE);
        assertEquals(350_000, last.extracted());
        assertEquals(StellarStage.BLACK_HOLE, system.snapshot().stage());
        assertEquals(0, system.snapshot().remainingResource());
    }

    @Test
    void pausedTimeDoesNotAdvanceDangerOrRevision() {
        StellarSystem system = system();
        system.extract(UUID.randomUUID(), 700_000);
        SystemSnapshot before = system.snapshot();
        for (int i = 0; i < 10000; i++) {
            assertFalse(system.tick(false));
        }
        assertEquals(before, system.snapshot());
        for (int i = 0; i < 199; i++) {
            assertFalse(system.tick(true));
        }
        assertTrue(system.tick(true));
        assertEquals(1, system.snapshot().burstCount());
        assertEquals(200, system.snapshot().activeTicks());
        assertEquals(300_000, system.snapshot().remainingResource());
    }

    @Test
    void replayAndConflictingReuseCannotDebitTwice() {
        StellarSystem system = system();
        UUID id = UUID.randomUUID();
        ExtractionResult applied = system.extract(id, 700_000);
        SystemSnapshot before = system.snapshot();
        ExtractionResult replay = system.extract(id, 700_000);
        assertEquals(ExtractionResult.Status.REPLAY, replay.status());
        assertEquals(applied.extracted(), replay.extracted());
        assertEquals(ExtractionResult.Status.CONFLICT, system.extract(id, 700_001).status());
        assertEquals(before, system.snapshot());
    }

    @Test
    void restoredSystemRetainsReceiptsAndReactionWindow() {
        StellarSystem system = system();
        UUID operation = UUID.randomUUID();
        system.extract(operation, 700_000);
        for (int i = 0; i < 120; i++) {
            system.tick(true);
        }
        StellarSystem restored = StellarSystem.restore(system.snapshot(), system.receipts());
        assertEquals(system.snapshot(), restored.snapshot());
        assertEquals(ExtractionResult.Status.REPLAY, restored.extract(operation, 700_000).status());
        for (int i = 0; i < 79; i++) {
            assertFalse(restored.tick(true));
        }
        assertTrue(restored.tick(true));
    }

    @Test
    void invalidInputAndDamagedAccountingFailBeforeMutation() {
        StellarSystem system = system();
        SystemSnapshot before = system.snapshot();
        assertThrows(IllegalArgumentException.class, () -> system.extract(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> system.extract(UUID.randomUUID(), -1));
        assertEquals(before, system.snapshot());
        system.extract(UUID.randomUUID(), 1);
        assertThrows(IllegalArgumentException.class, () -> StellarSystem.restore(system.snapshot(), List.of()));
        List<ExtractionResult> duplicate = new ArrayList<>(system.receipts());
        duplicate.add(duplicate.getFirst());
        assertThrows(IllegalArgumentException.class, () -> StellarSystem.restore(system.snapshot(), duplicate));
    }

    @Test
    void snapshotsAndReceiptCopiesRemainIsolated() {
        StellarSystem system = system();
        SystemSnapshot before = system.snapshot();
        List<ExtractionResult> receipts = system.receipts();
        system.extract(UUID.randomUUID(), 10);
        assertEquals(1_000_000, before.remainingResource());
        assertTrue(receipts.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> system.receipts().clear());
    }

    @Test
    void restorationRejectsImpossibleTimeAndReceiptOrder() {
        StellarSystem system = system();
        system.extract(UUID.randomUUID(), 10);
        system.extract(UUID.randomUUID(), 20);
        SystemSnapshot valid = system.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> StellarSystem.restore(valid, system.receipts().reversed()));
        SystemSnapshot impossibleRevision = new SystemSnapshot(valid.descriptor(), valid.remainingResource(),
                20, 200, 0, valid.revision());
        assertThrows(IllegalArgumentException.class,
                () -> StellarSystem.restore(impossibleRevision, system.receipts()));
        SystemSnapshot impossibleBursts = new SystemSnapshot(valid.descriptor(), valid.remainingResource(),
                0, 200, 1, valid.revision());
        assertThrows(IllegalArgumentException.class,
                () -> StellarSystem.restore(impossibleBursts, system.receipts()));
    }

    @Test
    void fullLedgerRejectsNewWorkButStillRecognizesOldOperations() {
        StellarSystem system = system();
        UUID first = new UUID(0, 0);
        for (int i = 0; i < StellarSystem.MAX_RECEIPTS; i++) {
            system.extract(new UUID(0, i), 1);
        }
        SystemSnapshot before = system.snapshot();
        assertEquals(ExtractionResult.Status.RECEIPT_LIMIT, system.extract(UUID.randomUUID(), 1).status());
        assertEquals(ExtractionResult.Status.REPLAY, system.extract(first, 1).status());
        assertEquals(before, system.snapshot());
    }

    @Test
    void separateSystemsNeverShareResourceOrTime() {
        StellarSystem alpha = system();
        StellarSystem beta = new StellarSystem(SystemGenerator.generate("beta", 123456789L));
        SystemSnapshot untouched = beta.snapshot();
        alpha.extract(UUID.randomUUID(), 1_000_000);
        alpha.tick(true);
        assertEquals(untouched, beta.snapshot());
    }
}
