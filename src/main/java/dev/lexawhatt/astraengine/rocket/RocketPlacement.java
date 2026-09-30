package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure bounded tree editing with conservative face attachments and radial symmetry. */
public final class RocketPlacement {
    /** Axial attachments require symmetry one; radial attachments support one, two, or four copies. */
    public enum Attachment {
        TOP,
        BOTTOM,
        RADIAL
    }

    /** A local-meter face socket and outward unit normal; radial quarter turns are relative to the parent yaw. */
    public record AttachmentPoint(Attachment attachment, int radialQuarterTurn, SpaceVector position,
                                  SpaceVector normal) {
        public AttachmentPoint {
            if (attachment == null || radialQuarterTurn < 0 || radialQuarterTurn > 3
                    || position == null || normal == null || Math.abs(normal.length() - 1) > 1.0e-12) {
                throw new IllegalArgumentException("Rocket attachment requires a face, position, and unit normal");
            }
        }
    }

    private RocketPlacement() {
    }

    /**
     * Appends default-valued parts touching their parent's bounding faces. Empty drafts require parent -1
     * and symmetry one. Failure leaves the input unchanged; a catalog schema or assembly-bound violation throws.
     */
    public static RocketBlueprint attach(RocketCatalog catalog, RocketBlueprint blueprint, int parentId,
                                         String definitionId, Attachment attachment, int symmetry) {
        if (catalog == null || blueprint == null || attachment == null
                || (symmetry != 1 && symmetry != 2 && symmetry != 4)
                || (attachment != Attachment.RADIAL && symmetry != 1)) {
            throw new IllegalArgumentException("Rocket attachment requires valid inputs and symmetry 1, 2, or 4");
        }
        catalog.validate(blueprint);
        RocketPartDefinition definition = catalog.requireDefinition(definitionId);
        List<RocketPart> parts = new ArrayList<>(blueprint.parts());
        if (parts.isEmpty()) {
            if (parentId != -1 || symmetry != 1) {
                throw new IllegalArgumentException("The first rocket part requires parent -1 and symmetry one");
            }
            parts.add(new RocketPart(0, -1, definitionId, SpaceVector.ZERO, definition.defaultSize(), 0,
                    catalog.defaultValues(definitionId)));
        } else {
            RocketPart parent = blueprint.requirePart(parentId);
            if (parts.size() + symmetry > RocketModelLimits.MAX_PARTS) {
                throw new IllegalArgumentException("Rocket attachment exceeds the 32-part limit");
            }
            for (int copy = 0; copy < symmetry; copy++) {
                int radialQuarter = copy * 4 / symmetry;
                AttachmentPoint socket = attachmentPoints(parent).stream()
                        .filter(point -> point.attachment() == attachment
                                && (attachment != Attachment.RADIAL || point.radialQuarterTurn() == radialQuarter))
                        .findFirst().orElseThrow();
                int yaw = (parent.yawQuarterTurns() + (attachment == Attachment.RADIAL ? radialQuarter : 0)) % 4;
                SpaceVector size = definition.defaultSize();
                SpaceVector oriented = yaw % 2 == 0 ? size : new SpaceVector(size.z(), size.y(), size.x());
                SpaceVector normal = socket.normal();
                double halfExtent = (Math.abs(normal.x()) * oriented.x() + Math.abs(normal.y()) * oriented.y()
                        + Math.abs(normal.z()) * oriented.z()) * 0.5;
                parts.add(new RocketPart(nextId(parts), parentId, definitionId,
                        socket.position().add(normal.multiply(halfExtent)), size, yaw,
                        catalog.defaultValues(definitionId)));
            }
        }
        RocketBlueprint result = new RocketBlueprint(blueprint.name(), parts);
        catalog.validate(result);
        return result;
    }

    /** Removes the requested part and all descendants; removing the root produces an empty draft. */
    public static RocketBlueprint removeSubtree(RocketBlueprint blueprint, int partId) {
        if (blueprint == null) {
            throw new IllegalArgumentException("Rocket blueprint must not be null");
        }
        blueprint.requirePart(partId);
        Set<Integer> removed = new HashSet<>();
        removed.add(partId);
        boolean changed;
        do {
            changed = false;
            for (RocketPart part : blueprint.parts()) {
                if (removed.contains(part.parentId()) && removed.add(part.id())) {
                    changed = true;
                }
            }
        } while (changed);
        return new RocketBlueprint(blueprint.name(), blueprint.parts().stream()
                .filter(part -> !removed.contains(part.id())).toList());
    }

    /** Six conservative AABB-face sockets shared with editor markers; no consumer compatibility rule is implied. */
    public static List<AttachmentPoint> attachmentPoints(RocketPart part) {
        RocketBounds bounds = RocketGeometry.bounds(part);
        SpaceVector center = part.position();
        List<AttachmentPoint> points = new ArrayList<>(6);
        points.add(new AttachmentPoint(Attachment.TOP, 0,
                new SpaceVector(center.x(), bounds.max().y(), center.z()), new SpaceVector(0, 1, 0)));
        points.add(new AttachmentPoint(Attachment.BOTTOM, 0,
                new SpaceVector(center.x(), bounds.min().y(), center.z()), new SpaceVector(0, -1, 0)));
        for (int quarter = 0; quarter < 4; quarter++) {
            SpaceVector normal = switch ((part.yawQuarterTurns() + quarter) % 4) {
                case 0 -> new SpaceVector(1, 0, 0);
                case 1 -> new SpaceVector(0, 0, -1);
                case 2 -> new SpaceVector(-1, 0, 0);
                default -> new SpaceVector(0, 0, 1);
            };
            double halfExtent = (Math.abs(normal.x()) * bounds.size().x()
                    + Math.abs(normal.z()) * bounds.size().z()) * 0.5;
            points.add(new AttachmentPoint(Attachment.RADIAL, quarter,
                    center.add(normal.multiply(halfExtent)), normal));
        }
        return List.copyOf(points);
    }

    private static int nextId(List<RocketPart> parts) {
        Set<Integer> used = new HashSet<>();
        for (RocketPart part : parts) {
            used.add(part.id());
        }
        for (int candidate = 0; candidate <= RocketModelLimits.MAX_PARTS; candidate++) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No free rocket part ID remains");
    }
}
