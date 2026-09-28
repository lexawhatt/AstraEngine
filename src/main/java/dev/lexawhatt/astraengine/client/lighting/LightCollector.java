package dev.lexawhatt.astraengine.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded per-frame selection. Never retain this collector after the collection event returns. */
public final class LightCollector {
    public static final int MAX_LIGHTS = 16;
    public static final int MAX_SUBMISSIONS = 256;
    private final LightVector camera;
    private final int capacity;
    private final double viewDistance;
    private final List<Candidate> selected = new ArrayList<>();
    private final Set<String> submitted = new HashSet<>();
    private int attempts;
    private boolean sealed;

    /** Selects up to capacity lights, ranking directional sources first, then estimated local contribution. */
    public LightCollector(LightVector camera, int capacity) {
        this(camera, capacity, 64);
    }

    /** Uses a bounded visible-world radius; sources may illuminate geometry even outside their own camera radius. */
    public LightCollector(LightVector camera, int capacity, double viewDistance) {
        if (camera == null || capacity < 1 || capacity > MAX_LIGHTS
                || !Double.isFinite(viewDistance) || viewDistance < 1 || viewDistance > 1024) {
            throw new IllegalArgumentException("Invalid light collection budget");
        }
        this.camera = camera;
        this.capacity = capacity;
        this.viewDistance = viewDistance;
    }

    /**
     * Offers a light once per ID. True means currently selected, not guaranteed to survive later offers.
     * Out-of-range, zero-intensity, duplicate and over-budget submissions return false.
     */
    public boolean add(SceneLight light) {
        if (sealed) { throw new IllegalStateException("Light frame is already sealed"); }
        if (light == null) { throw new IllegalArgumentException("Light cannot be null"); }
        if (attempts >= MAX_SUBMISSIONS) { return false; }
        attempts++;
        if (!submitted.add(light.id()) || light.intensity() == 0) { return false; }
        double distance = Math.sqrt(light.position().distanceSquared(camera));
        if (light.kind() != SceneLight.Kind.DIRECTIONAL && distance > light.range() + viewDistance) { return false; }
        double score = light.kind() == SceneLight.Kind.DIRECTIONAL ? 1000 + light.intensity()
                : light.intensity() * light.range() * light.range()
                        / (light.range() * light.range() + distance * distance);
        if (score <= 0) { return false; }
        selected.add(new Candidate(light, score));
        selected.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(c -> c.light().id()));
        if (selected.size() > capacity) { selected.removeLast(); }
        return selected.stream().anyMatch(candidate -> candidate.light().id().equals(light.id()));
    }

    /** Freezes and returns immutable selected lights. Further mutation fails explicitly. */
    public List<SceneLight> seal() {
        sealed = true;
        return selected.stream().map(Candidate::light).toList();
    }

    /** Number of submitted candidates, including rejected ones. */
    public int attempts() { return attempts; }

    private record Candidate(SceneLight light, double score) {}
}
