package dev.lexawhatt.astraengine.surface;

/** Transient host-player movement ownership; never serialized, and cleared when inspection ends. */
public interface PlanetaryInspectionAccess {
    boolean astra$inspectionMovement();
    void astra$inspectionMovement(boolean active);
}
