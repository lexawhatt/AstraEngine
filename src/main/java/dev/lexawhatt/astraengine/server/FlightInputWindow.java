package dev.lexawhatt.astraengine.server;

/** Session-owned input ordering and relocation epoch; all calls run on the owning server thread. */
final class FlightInputWindow {
    private long navigationEpoch;
    private long sequence = -1;
    private int inputTick = Integer.MIN_VALUE;
    private boolean received;

    long navigationEpoch() { return navigationEpoch; }

    void relocate(long epoch) {
        if (epoch < 0) { throw new IllegalArgumentException("Navigation epoch must be nonnegative"); }
        navigationEpoch = epoch;
        sequence = -1;
        inputTick = Integer.MIN_VALUE;
        received = false;
    }

    boolean accept(long candidateSequence, long candidateEpoch, int tick) {
        if (candidateSequence < 0 || candidateEpoch != navigationEpoch || candidateSequence <= sequence
                || (received && inputTick == tick)) {
            return false;
        }
        sequence = candidateSequence;
        inputTick = tick;
        received = true;
        return true;
    }

    boolean expired(int tick) { return !received || tick - inputTick > 10; }
}
