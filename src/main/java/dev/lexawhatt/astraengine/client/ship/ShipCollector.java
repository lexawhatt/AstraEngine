package dev.lexawhatt.astraengine.client.ship;

import dev.lexawhatt.astraengine.api.ship.ShipRenderInstance;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded render-frame selection. Never retain this collector after its collection event returns. */
public final class ShipCollector {
    public static final int MAX_SHIPS = 4;
    public static final int MAX_SUBMISSIONS = 128;
    public static final double RANGE_BLOCKS = 96;
    private final SpaceVector camera;
    private final List<Candidate> selected = new ArrayList<>();
    private int attempts;
    private boolean sealed;

    /** Creates an empty collector around a non-null, finite camera position in world blocks. */
    public ShipCollector(SpaceVector camera) {
        if (camera == null) { throw new IllegalArgumentException("Ship collection requires a camera position"); }
        this.camera = camera;
    }

    /**
     * Offers one current-frame placement. True means currently selected, not guaranteed to survive
     * later offers. Empty, duplicate, out-of-range and over-budget submissions return false.
     * Calls after seal() fail explicitly. Equal-distance candidates retain submission order.
     */
    public boolean add(ShipRenderInstance instance) {
        if (sealed) { throw new IllegalStateException("Ship collection frame is already sealed"); }
        if (instance == null) { throw new IllegalArgumentException("Ship instance must not be null"); }
        if (attempts >= MAX_SUBMISSIONS) { return false; }
        attempts++;
        if (instance.visual().parts().isEmpty()) { return false; }
        double dx = instance.worldOrigin().x() - camera.x();
        double dy = instance.worldOrigin().y() - camera.y();
        double dz = instance.worldOrigin().z() - camera.z();
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (distance > RANGE_BLOCKS || selected.stream().anyMatch(candidate -> candidate.instance().equals(instance))) {
            return false;
        }
        selected.add(new Candidate(instance, distance));
        selected.sort(Comparator.comparingDouble(Candidate::distance));
        if (selected.size() > MAX_SHIPS) { selected.removeLast(); }
        return selected.stream().anyMatch(candidate -> candidate.instance() == instance);
    }

    /** Seals collection and returns an immutable nearest-first list. No level or consumer is retained. */
    public List<ShipRenderInstance> seal() {
        sealed = true;
        return selected.stream().map(Candidate::instance).toList();
    }

    /** Number of attempted offers, capped at the input budget. */
    public int attempts() { return Math.min(attempts, MAX_SUBMISSIONS); }

    private record Candidate(ShipRenderInstance instance, double distance) {}
}
