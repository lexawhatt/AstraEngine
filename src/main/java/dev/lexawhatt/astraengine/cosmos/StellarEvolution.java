package dev.lexawhatt.astraengine.cosmos;

import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot.Phase;

/** Pure owned stellar state: deterministic gradual depletion, pausable collapse/nova, and a terminal remnant. */
public final class StellarEvolution {
    private StellarEvolutionSnapshot snapshot;

    /** Starts pristine and paused; creating the model never starts extraction. */
    public StellarEvolution() { snapshot = pristine(0, 0); }

    private StellarEvolution(StellarEvolutionSnapshot snapshot) {
        if (snapshot == null) { throw new IllegalArgumentException("Restored stellar snapshot must not be null"); }
        this.snapshot = snapshot;
    }

    /** Restores a validated immutable snapshot; no elapsed wall-clock time is applied. */
    public static StellarEvolution restore(StellarEvolutionSnapshot snapshot) { return new StellarEvolution(snapshot); }

    /** Current immutable state. */
    public StellarEvolutionSnapshot snapshot() { return snapshot; }

    /** Resets full energy and starts a new diagnostic cycle whose drain lasts 10..3600 occupied seconds. */
    public void startDemo(int seconds) {
        if (seconds < 10 || seconds > 3600) { throw new IllegalArgumentException("Demo drain duration must be 10..3600 seconds"); }
        snapshot = new StellarEvolutionSnapshot(StellarEvolutionSnapshot.CAPACITY, 0, 0, Phase.STABLE, 0,
                seconds * 20, 0, true, Math.incrementExact(snapshot.revision()), Math.incrementExact(snapshot.cycle()));
    }

    /** Pauses both depletion and dangerous phase clocks; returns whether state changed. */
    public boolean pause() {
        if (!snapshot.running()) { return false; }
        setRunning(false); return true;
    }

    /** Resumes a configured unfinished cycle. A pristine star or completed remnant does not start itself. */
    public boolean resume() {
        if (snapshot.running() || snapshot.drainTicks() == 0 || snapshot.phase() == Phase.REMNANT) { return false; }
        setRunning(true); return true;
    }

    /** Returns to pristine paused state with increasing cycle and revision identities. */
    public void reset() {
        snapshot = pristine(Math.incrementExact(snapshot.revision()), Math.incrementExact(snapshot.cycle()));
    }

    /** Advances one occupied tick, preserving exact energy accounting and pausing all time otherwise. */
    public boolean tick(boolean occupied) {
        if (!occupied || !snapshot.running()) { return false; }
        int elapsed = snapshot.drainElapsed();
        long extracted = snapshot.extracted();
        Phase phase = snapshot.phase();
        int phaseTicks = snapshot.phaseTicks() + 1;
        boolean running = true;
        if (elapsed < snapshot.drainTicks()) {
            elapsed++;
            extracted = StellarEvolutionSnapshot.CAPACITY * elapsed / snapshot.drainTicks();
            long remaining = StellarEvolutionSnapshot.CAPACITY - extracted;
            Phase next = remaining == 0 ? Phase.COLLAPSING
                    : remaining > StellarEvolutionSnapshot.CAPACITY * 65 / 100 ? Phase.STABLE
                    : remaining > StellarEvolutionSnapshot.CAPACITY / 5 ? Phase.DISTENDED : Phase.CRITICAL;
            if (next != phase) { phase = next; phaseTicks = 0; }
        } else if (phase == Phase.COLLAPSING && phaseTicks == StellarEvolutionSnapshot.COLLAPSE_TICKS) {
            phase = Phase.SUPERNOVA; phaseTicks = 0;
        } else if (phase == Phase.SUPERNOVA && phaseTicks == StellarEvolutionSnapshot.NOVA_TICKS) {
            phase = Phase.REMNANT; phaseTicks = 0; running = false;
        }
        snapshot = new StellarEvolutionSnapshot(StellarEvolutionSnapshot.CAPACITY - extracted, extracted,
                snapshot.activeTicks() + 1, phase, phaseTicks, snapshot.drainTicks(), elapsed, running,
                Math.incrementExact(snapshot.revision()), snapshot.cycle());
        return true;
    }

    private void setRunning(boolean running) {
        snapshot = new StellarEvolutionSnapshot(snapshot.remaining(), snapshot.extracted(), snapshot.activeTicks(),
                snapshot.phase(), snapshot.phaseTicks(), snapshot.drainTicks(), snapshot.drainElapsed(), running,
                Math.incrementExact(snapshot.revision()), snapshot.cycle());
    }

    private static StellarEvolutionSnapshot pristine(long revision, long cycle) {
        return new StellarEvolutionSnapshot(StellarEvolutionSnapshot.CAPACITY, 0, 0, Phase.STABLE, 0, 0, 0,
                false, revision, cycle);
    }
}
