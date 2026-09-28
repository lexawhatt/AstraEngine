package dev.lexawhatt.astraengine.api;

import java.util.Objects;

/**
 * Immutable, versioned single-star system definition. Temperature is in kelvin;
 * capacity uses integral engine resource units, independent of a consumer's economy.
 */
public record SystemDescriptor(String id, long seed, int generatorVersion, int temperatureKelvin,
                               int planetCount, long resourceCapacity) {
    public SystemDescriptor {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z][a-z0-9_]{0,63}") || generatorVersion != 1
                || temperatureKelvin < 2500 || temperatureKelvin > 12000
                || planetCount < 1 || planetCount > 4 || resourceCapacity <= 0
                || resourceCapacity > 1_000_000_000L) {
            throw new IllegalArgumentException("Invalid or unsupported system descriptor");
        }
    }
}
