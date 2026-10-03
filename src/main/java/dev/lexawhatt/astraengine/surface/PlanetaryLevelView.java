package dev.lexawhatt.astraengine.surface;

/**
 * Transient host-level attachment for geographic prediction. No serialization or process-global registry.
 * Set/read only on the level's owning thread; unloading its Level releases the context. Server blocks remain
 * authoritative. A client snapshot is a bounded observation, not permission to mutate another chart.
 */
public interface PlanetaryLevelView {
    CubeStorageChart astra$chart();
    EarthBoundarySnapshot astra$boundary();
    void astra$geography(CubeStorageChart chart, EarthBoundarySnapshot boundary);
}
