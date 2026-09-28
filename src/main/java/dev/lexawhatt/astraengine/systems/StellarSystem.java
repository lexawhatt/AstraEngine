package dev.lexawhatt.astraengine.systems;

import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.api.StellarStage;
import dev.lexawhatt.astraengine.api.SystemDescriptor;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable model owned exclusively by the server thread; has no rendering or Minecraft dependencies. */
public final class StellarSystem {
    /** A full ledger refuses new operations instead of losing replay protection. */
    public static final int MAX_RECEIPTS = 4096;
    private final SystemDescriptor descriptor;
    private final Map<UUID, ExtractionResult> receipts = new LinkedHashMap<>();
    private long remainingResource;
    private long activeTicks;
    private int ticksUntilBurst = 200;
    private long burstCount;
    private long revision;

    /** Creates a pristine system with the descriptor's full resource capacity. */
    public StellarSystem(SystemDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        remainingResource = descriptor.resourceCapacity();
    }

    /** Restores validated state and receipts, rejecting duplicates and broken resource accounting. */
    public static StellarSystem restore(SystemSnapshot snapshot, List<ExtractionResult> savedReceipts) {
        StellarSystem system = new StellarSystem(snapshot.descriptor());
        if (savedReceipts.size() > MAX_RECEIPTS) {
            throw new IllegalArgumentException("Receipt ledger exceeds its limit");
        }
        if (snapshot.revision() - snapshot.activeTicks() != savedReceipts.size()
                || snapshot.burstCount() > snapshot.activeTicks() / 200) {
            throw new IllegalArgumentException("Saved revision or burst count disagrees with system history");
        }
        long total = 0;
        long previousRevision = 0;
        for (ExtractionResult receipt : savedReceipts) {
            if (receipt.status() != ExtractionResult.Status.APPLIED || receipt.revision() > snapshot.revision()
                    || receipt.revision() <= previousRevision
                    || system.receipts.putIfAbsent(receipt.operationId(), receipt) != null) {
                throw new IllegalArgumentException("Invalid saved extraction receipt");
            }
            total = Math.addExact(total, receipt.extracted());
            previousRevision = receipt.revision();
        }
        if (total != snapshot.descriptor().resourceCapacity() - snapshot.remainingResource()) {
            throw new IllegalArgumentException("Saved resource and receipts disagree");
        }
        system.remainingResource = snapshot.remainingResource();
        system.activeTicks = snapshot.activeTicks();
        system.ticksUntilBurst = snapshot.ticksUntilBurst();
        system.burstCount = snapshot.burstCount();
        system.revision = snapshot.revision();
        return system;
    }

    /** Returns an immutable snapshot; future mutations cannot change it. */
    public SystemSnapshot snapshot() {
        return new SystemSnapshot(descriptor, remainingResource, activeTicks, ticksUntilBurst, burstCount, revision);
    }

    /** Returns an immutable copy of the bounded reconciliation ledger. */
    public List<ExtractionResult> receipts() {
        return List.copyOf(receipts.values());
    }

    /** Applies at most the available resource once per operation ID; invalid amounts throw before mutation. */
    public ExtractionResult extract(UUID operationId, long amount) {
        Objects.requireNonNull(operationId, "operationId");
        if (amount <= 0) {
            throw new IllegalArgumentException("Extraction amount must be positive");
        }
        ExtractionResult previous = receipts.get(operationId);
        if (previous != null) {
            if (previous.requested() != amount) {
                return new ExtractionResult(operationId, amount, 0, revision, ExtractionResult.Status.CONFLICT);
            }
            return new ExtractionResult(operationId, amount, previous.extracted(), previous.revision(),
                    ExtractionResult.Status.REPLAY);
        }
        if (receipts.size() == MAX_RECEIPTS) {
            return new ExtractionResult(operationId, amount, 0, revision, ExtractionResult.Status.RECEIPT_LIMIT);
        }
        long nextRevision = Math.incrementExact(revision);
        long extracted = Math.min(amount, remainingResource);
        ExtractionResult result = new ExtractionResult(operationId, amount, extracted, nextRevision,
                ExtractionResult.Status.APPLIED);
        remainingResource -= extracted;
        revision = nextRevision;
        receipts.put(operationId, result);
        return result;
    }

    /** Advances one occupied server tick; returns true only on a newly emitted diagnostic burst. */
    public boolean tick(boolean occupied) {
        if (!occupied) {
            return false;
        }
        long nextTick = Math.incrementExact(activeTicks);
        long nextRevision = Math.incrementExact(revision);
        boolean burst = snapshot().stage() == StellarStage.UNSTABLE && ticksUntilBurst == 1;
        long nextBurstCount = burst ? Math.incrementExact(burstCount) : burstCount;
        activeTicks = nextTick;
        revision = nextRevision;
        if (snapshot().stage() == StellarStage.UNSTABLE) {
            ticksUntilBurst = burst ? 200 : ticksUntilBurst - 1;
        }
        burstCount = nextBurstCount;
        return burst;
    }
}
