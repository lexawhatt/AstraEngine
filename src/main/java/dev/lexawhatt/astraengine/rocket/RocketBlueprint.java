package dev.lexawhatt.astraengine.rocket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable bounded attachment tree. Empty editor drafts are valid; catalog validation resolves all definitions. */
public record RocketBlueprint(String name, List<RocketPart> parts) {
    public static final int MAX_PARTS = RocketModelLimits.MAX_PARTS;

    public RocketBlueprint {
        RocketModelLimits.requireText(name, "Rocket blueprint name", 64);
        if (parts == null || parts.size() > MAX_PARTS || parts.stream().anyMatch(part -> part == null)) {
            throw new IllegalArgumentException("Rocket blueprint requires at most 32 non-null parts");
        }
        parts = List.copyOf(parts);
        Map<Integer, RocketPart> byId = new HashMap<>();
        int rootCount = 0;
        RocketBounds bounds = null;
        for (RocketPart part : parts) {
            if (byId.put(part.id(), part) != null) {
                throw new IllegalArgumentException("Duplicate rocket part ID " + part.id());
            }
            if (part.parentId() == -1) {
                rootCount++;
            }
            RocketBounds partBounds = RocketGeometry.bounds(part);
            bounds = bounds == null ? partBounds : bounds.union(partBounds);
        }
        if (!parts.isEmpty() && rootCount != 1) {
            throw new IllegalArgumentException("A nonempty rocket blueprint requires exactly one root");
        }
        for (RocketPart part : parts) {
            Set<Integer> ancestors = new HashSet<>();
            RocketPart current = part;
            while (current.parentId() != -1) {
                if (!ancestors.add(current.id())) {
                    throw new IllegalArgumentException("Rocket attachment tree contains a cycle");
                }
                current = byId.get(current.parentId());
                if (current == null) {
                    throw new IllegalArgumentException("Rocket attachment tree references a missing parent");
                }
            }
        }
        if (bounds != null && (bounds.size().x() > RocketModelLimits.MAX_WIDTH_METERS
                || bounds.size().y() > RocketModelLimits.MAX_HEIGHT_METERS
                || bounds.size().z() > RocketModelLimits.MAX_WIDTH_METERS)) {
            throw new IllegalArgumentException("Rocket assembly exceeds 16 x 24 x 16 meter bounds");
        }
    }

    /** Returns the part with the stable local ID, rejecting missing IDs. */
    public RocketPart requirePart(int id) {
        return parts.stream().filter(part -> part.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown rocket part ID " + id));
    }

    /** Replaces an existing part atomically, revalidating the complete attachment tree and bounds. */
    public RocketBlueprint withPart(RocketPart replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("Replacement rocket part must not be null");
        }
        requirePart(replacement.id());
        return new RocketBlueprint(name, parts.stream()
                .map(part -> part.id() == replacement.id() ? replacement : part).toList());
    }
}
