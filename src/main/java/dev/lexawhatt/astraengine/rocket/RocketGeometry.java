package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.ArrayList;
import java.util.List;

/** Shared local-meter geometry for authoring, deployment clearance, and conservative compound collision. */
public final class RocketGeometry {
    private RocketGeometry() {
    }

    /** Bounds for a centered part after its right-handed quarter-turn yaw. */
    public static RocketBounds bounds(RocketPart part) {
        if (part == null) {
            throw new IllegalArgumentException("Rocket part must not be null");
        }
        SpaceVector half = orientedSize(part).multiply(0.5);
        return new RocketBounds(part.position().subtract(half), part.position().add(half));
    }

    /** Bounds of all parts, or a zero box for an empty editor draft. */
    public static RocketBounds bounds(RocketBlueprint blueprint) {
        if (blueprint == null) {
            throw new IllegalArgumentException("Rocket blueprint must not be null");
        }
        if (blueprint.parts().isEmpty()) {
            return new RocketBounds(SpaceVector.ZERO, SpaceVector.ZERO);
        }
        RocketBounds result = bounds(blueprint.parts().getFirst());
        for (int i = 1; i < blueprint.parts().size(); i++) {
            result = result.union(bounds(blueprint.parts().get(i)));
        }
        return result;
    }

    /** One box for a cylinder/box, or three conservative horizontal slices for a frustum. */
    public static List<RocketBounds> collisionBoxes(RocketCatalog catalog, RocketPart part) {
        if (catalog == null || part == null) {
            throw new IllegalArgumentException("Rocket collision requires a catalog and part");
        }
        catalog.validate(part);
        RocketVisual visual = catalog.requireDefinition(part.definitionId()).visual();
        if (visual.geometry() != RocketGeometryKind.FRUSTUM) {
            return List.of(bounds(part));
        }
        SpaceVector size = orientedSize(part);
        List<RocketBounds> slices = new ArrayList<>(3);
        double bottom = part.position().y() - size.y() * 0.5;
        for (int slice = 0; slice < 3; slice++) {
            double start = slice / 3.0;
            double end = (slice + 1) / 3.0;
            double startRadius = visual.bottomRadiusRatio()
                    + (visual.topRadiusRatio() - visual.bottomRadiusRatio()) * start;
            double endRadius = visual.bottomRadiusRatio()
                    + (visual.topRadiusRatio() - visual.bottomRadiusRatio()) * end;
            double ratio = Math.max(startRadius, endRadius);
            double halfX = size.x() * ratio * 0.5;
            double halfZ = size.z() * ratio * 0.5;
            slices.add(new RocketBounds(new SpaceVector(part.position().x() - halfX, bottom + size.y() * start,
                    part.position().z() - halfZ), new SpaceVector(part.position().x() + halfX,
                    bottom + size.y() * end, part.position().z() + halfZ)));
        }
        return List.copyOf(slices);
    }

    /** Immutable flattened compound boxes in the same coordinate space as the blueprint. */
    public static List<RocketBounds> collisionBoxes(RocketCatalog catalog, RocketBlueprint blueprint) {
        if (catalog == null) {
            throw new IllegalArgumentException("Rocket collision requires a catalog");
        }
        catalog.validate(blueprint);
        List<RocketBounds> boxes = new ArrayList<>();
        for (RocketPart part : blueprint.parts()) {
            boxes.addAll(collisionBoxes(catalog, part));
        }
        return List.copyOf(boxes);
    }

    static SpaceVector orientedSize(RocketPart part) {
        return part.yawQuarterTurns() % 2 == 0 ? part.size()
                : new SpaceVector(part.size().z(), part.size().y(), part.size().x());
    }
}
