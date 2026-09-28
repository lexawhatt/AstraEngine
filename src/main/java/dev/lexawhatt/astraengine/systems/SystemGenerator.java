package dev.lexawhatt.astraengine.systems;

import dev.lexawhatt.astraengine.api.SystemDescriptor;
import java.util.Random;

/** Version-one deterministic generator; results depend only on the supplied identity and seed. */
public final class SystemGenerator {
    private SystemGenerator() {}

    /** Generates a single-star descriptor without reading global randomness, clocks, or Minecraft state. */
    public static SystemDescriptor generate(String id, long seed) {
        Random random = new Random(seed);
        return new SystemDescriptor(id, seed, 1, 3500 + random.nextInt(5501),
                1 + random.nextInt(4), 1_000_000L);
    }
}
