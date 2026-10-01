package dev.lexawhatt.astraengine.client.map;

import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.NavigationPolicy;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;
import java.util.Set;

/**
 * Immutable client presentation data. Position is system-local meters, time is orbital seconds,
 * galaxy descriptors use light-years. Discovery and visits are the last server acknowledgement.
 * Descriptors and vectors are immutable; this value exposes no live world, controller or mutable catalog.
 */
public record NavigationMapSnapshot(long galaxySeed, CosmosSystem system, List<CosmosSystem> discoveredSystems,
        Set<String> visitedSystems, SpaceVector positionMeters, FlightOrientation orientation,
        double orbitalSeconds, boolean flying, boolean canStartRoute, int remainingTicks, String routeTarget, String selectedBody,
        NavigationPolicy policy) {
    /** Copies collection boundaries and rejects absent or invalid presentation data. */
    public NavigationMapSnapshot {
        if (system == null || discoveredSystems == null || visitedSystems == null || positionMeters == null
                || orientation == null || !Double.isFinite(orbitalSeconds) || remainingTicks < 0
                || routeTarget == null || selectedBody == null || policy == null) {
            throw new IllegalArgumentException("A map snapshot requires complete finite navigation data");
        }
        discoveredSystems = List.copyOf(discoveredSystems);
        visitedSystems = Set.copyOf(visitedSystems);
    }
}
