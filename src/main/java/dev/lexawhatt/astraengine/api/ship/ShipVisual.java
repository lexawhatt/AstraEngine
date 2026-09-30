package dev.lexawhatt.astraengine.api.ship;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;

/**
 * Immutable consumer-authored visual snapshot: at most 32 primitives and six optional local-block
 * marker positions. Lists are defensively copied; null elements and out-of-bounds markers fail.
 * Markers carry no attachment rules and are shown only by preview rendering. This value owns no
 * GPU resources, entity, world, catalog or construction state and may be retained by its consumer.
 */
public record ShipVisual(List<ShipVisualPart> parts, List<SpaceVector> attachmentMarkers) {
    public static final int MAX_PARTS = 32;
    public static final int MAX_MARKERS = 6;

    public ShipVisual {
        if (parts == null || attachmentMarkers == null || parts.size() > MAX_PARTS
                || attachmentMarkers.size() > MAX_MARKERS || parts.stream().anyMatch(part -> part == null)
                || attachmentMarkers.stream().anyMatch(marker -> marker == null || Math.abs(marker.x()) > 128
                        || Math.abs(marker.y()) > 128 || Math.abs(marker.z()) > 128)) {
            throw new IllegalArgumentException("Ship visual requires bounded non-null primitive and marker lists");
        }
        parts = List.copyOf(parts);
        attachmentMarkers = List.copyOf(attachmentMarkers);
    }

    /** Encloses all primitives; an empty snapshot has a zero-size box at its origin. */
    public ShipBounds bounds() {
        if (parts.isEmpty()) { return new ShipBounds(SpaceVector.ZERO, SpaceVector.ZERO); }
        ShipBounds bounds = parts.getFirst().bounds();
        for (int index = 1; index < parts.size(); index++) { bounds = bounds.union(parts.get(index).bounds()); }
        return bounds;
    }
}
