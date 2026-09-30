package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.rocket.RocketAssemblyRenderer;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import dev.lexawhatt.astraengine.rocket.RocketGeometryKind;
import dev.lexawhatt.astraengine.rocket.RocketMaterialStyle;
import dev.lexawhatt.astraengine.rocket.RocketPart;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import dev.lexawhatt.astraengine.rocket.RocketVisual;
import java.util.List;
import java.util.Map;

/** Production camera/picking checks require Minecraft's Vec3 runtime but never allocate a GL attachment. */
final class RocketPickingChecks {
    private static final String PART = "verification:pick_box";

    private RocketPickingChecks() {
    }

    static void verify(RocketAssemblyRenderer renderer) {
        var catalog = new RocketCatalog(List.of(new RocketPartDefinition(PART,
                "Test box", "verification:structure", new RocketVisual(RocketGeometryKind.BOX, 1, 1,
                new SpaceVector(0.5, 0.5, 0.5), RocketMaterialStyle.PLAIN), new SpaceVector(2, 2, 2), List.of())));
        var offset = new RocketBlueprint("Offset box", List.of(part(0, -1, new SpaceVector(12, -8, 5))));
        for (int width : new int[] {240, 800}) {
            for (double yaw : new double[] {-720, -30, 0, 90, 180}) {
                for (double pitch : new double[] {-85, 12, 85}) {
                    for (double zoom : new double[] {0.25, 1, 4}) {
                        require(renderer.pickPart(20, 30, width, 300, catalog, offset,
                                20 + width * 0.5, 180, yaw, pitch, zoom) == 0,
                                "Center selection lost the translated box across orbit/aspect/zoom");
                    }
                }
            }
        }

        RocketPart far = part(0, -1, new SpaceVector(0, 0, -2));
        RocketPart near = part(1, 0, new SpaceVector(0, 0, 2));
        require(renderer.pickPart(0, 0, 400, 300, catalog,
                new RocketBlueprint("Near last", List.of(far, near)), 200, 150, 0, 0, 1) == 1,
                "Picking did not choose the nearest overlapping part");
        require(renderer.pickPart(0, 0, 400, 300, catalog,
                new RocketBlueprint("Near first", List.of(near, far)), 200, 150, 0, 0, 1) == 0,
                "Picking depends on part list order");

        var box = new RocketBlueprint("Box", List.of(part(0, -1, SpaceVector.ZERO)));
        require(renderer.pickPart(20, 30, 400, 300, catalog, box, 19, 150, 0, 0, 1) == -1,
                "Picking escaped the left viewport boundary");
        require(renderer.pickPart(20, 30, 400, 300, catalog, box, 420, 150, 0, 0, 1) == -1,
                "Picking escaped the right viewport boundary");
        require(renderer.pickPart(20, 30, 400, 300, catalog, box, 21, 31, 0, 0, 1) == -1,
                "Empty viewport background selected a box");
        require(renderer.pickPart(20, 30, 400, 300, catalog,
                new RocketBlueprint("Empty", List.of()), 220, 180, 0, 0, 1) == -1,
                "Empty blueprint produced a selection");
        reject(() -> renderer.pickPart(0, 0, 400, 300, catalog, box, 200, 150, Double.NaN, 0, 1));
        reject(() -> renderer.pickPart(0, 0, 400, 300, catalog, box, 200, 150, 0, 0, 0));
    }

    private static RocketPart part(int id, int parent, SpaceVector position) {
        return new RocketPart(id, parent, PART, position, new SpaceVector(2, 2, 2), 0, Map.of());
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Picking accepted an invalid camera input");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
