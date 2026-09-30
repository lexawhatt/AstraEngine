package dev.lexawhatt.astraengine.api.ship;

import dev.lexawhatt.astraengine.client.ship.ShipCollector;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure contracts for consumer-supplied visual geometry, immutable poses and bounded frame extraction. */
class ShipVisualTest {
    private static final SpaceVector COLOR = new SpaceVector(0.5, 0.6, 0.7);

    @Test
    void visualCopiesCallerListsWithoutRetainingConstructionState() {
        List<ShipVisualPart> parts = new ArrayList<>(List.of(box(0)));
        List<SpaceVector> markers = new ArrayList<>(List.of(new SpaceVector(0, 1, 0)));
        ShipVisual visual = new ShipVisual(parts, markers);
        parts.clear();
        markers.clear();
        assertEquals(1, visual.parts().size());
        assertEquals(1, visual.attachmentMarkers().size());
        assertThrows(UnsupportedOperationException.class, () -> visual.parts().clear());
        assertThrows(UnsupportedOperationException.class, () -> visual.attachmentMarkers().clear());
        assertEquals(new ShipBounds(SpaceVector.ZERO, SpaceVector.ZERO), new ShipVisual(List.of(), List.of()).bounds());
    }

    @Test
    void arbitraryLocalYawBoundsEncloseEveryOrientedBoxCorner() {
        for (double degrees : new double[] {-735, -90, 0, 37, 90, 142, 359}) {
            ShipVisualPart part = box(degrees);
            ShipBounds bounds = part.bounds();
            double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? -1 : 1;
                double y = (corner & 2) == 0 ? -2 : 2;
                double z = (corner & 4) == 0 ? -3 : 3;
                assertEncloses(bounds, new SpaceVector(c * x + s * z, y, -s * x + c * z));
            }
        }
        assertEquals(6, box(90).bounds().size().x(), 1e-12);
        assertEquals(2, box(90).bounds().size().z(), 1e-12);
    }

    @Test
    void frustumVisualPickingTapersTowardItsNarrowEnd() {
        ShipVisualPart part = new ShipVisualPart(ShipVisualPart.Geometry.FRUSTUM, ShipVisualPart.Material.PLAIN,
                SpaceVector.ZERO, new SpaceVector(4, 6, 2), 37, 1, 0.1, COLOR);
        List<ShipBounds> boxes = part.pickingBounds();
        assertEquals(3, boxes.size());
        assertEquals(boxes.get(0).max().y(), boxes.get(1).min().y(), 1e-12);
        assertEquals(boxes.get(1).max().y(), boxes.get(2).min().y(), 1e-12);
        assertTrue(boxes.get(2).size().x() < boxes.get(0).size().x());
        boxes.forEach(bounds -> { assertEncloses(part.bounds(), bounds.min()); assertEncloses(part.bounds(), bounds.max()); });
    }

    @Test
    void fullAssemblyPoseRetainsPitchRollAndOrthonormalAxes() {
        ShipVisual visual = new ShipVisual(List.of(box(25)), List.of());
        ShipRenderInstance instance = new ShipRenderInstance(visual, new SpaceVector(10, 70, -3),
                FlightOrientation.fromAngles(35, 62, 41), -1);
        SpaceVector x = instance.rotateLocal(new SpaceVector(1, 0, 0));
        SpaceVector y = instance.rotateLocal(new SpaceVector(0, 1, 0));
        SpaceVector z = instance.rotateLocal(new SpaceVector(0, 0, 1));
        assertEquals(1, x.length(), 1e-12);
        assertEquals(1, y.length(), 1e-12);
        assertEquals(1, z.length(), 1e-12);
        assertEquals(0, x.dot(y), 1e-12);
        assertEquals(0, x.dot(z), 1e-12);
        assertEquals(0, y.dot(z), 1e-12);
        assertTrue(Math.abs(x.y()) > 0.1 && Math.abs(z.y()) > 0.1);
        SpaceVector oldYaw = new ShipRenderInstance(visual, SpaceVector.ZERO, 90, -1)
                .rotateLocal(new SpaceVector(1, 0, 0));
        assertEquals(0, oldYaw.x(), 1e-12);
        assertEquals(-1, oldYaw.z(), 1e-12);
    }

    @Test
    void collectionSelectsNearestFourAndDiscardsItsMutableLifetime() {
        ShipCollector collector = new ShipCollector(SpaceVector.ZERO);
        for (int distance : new int[] {70, 40, 60, 20, 10, 30}) { collector.add(instance(distance)); }
        assertFalse(collector.add(instance(200)));
        assertFalse(collector.add(instance(10)), "A duplicate selected snapshot is not rendered twice");
        List<ShipRenderInstance> selected = collector.seal();
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0), selected.stream().map(value -> value.worldOrigin().x()).toList());
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
        assertThrows(IllegalStateException.class, () -> collector.add(instance(1)));
    }

    @Test
    void collectionBudgetAndLargeWorldCoordinatesStayBounded() {
        ShipCollector collector = new ShipCollector(SpaceVector.ZERO);
        for (int index = 0; index < ShipCollector.MAX_SUBMISSIONS; index++) { collector.add(instance(200)); }
        assertFalse(collector.add(instance(1)));
        assertEquals(ShipCollector.MAX_SUBMISSIONS, collector.attempts());
        assertTrue(collector.seal().isEmpty());
        ShipCollector distant = new ShipCollector(new SpaceVector(29_000_000, 70, -29_000_000));
        ShipRenderInstance near = new ShipRenderInstance(new ShipVisual(List.of(box(0)), List.of()),
                new SpaceVector(29_000_000.125, 70, -29_000_000), 0, -1);
        assertTrue(distant.add(near));
        assertEquals(List.of(near), distant.seal());
    }

    @Test
    void invalidVisualInputsFailBeforeReachingTheGpu() {
        assertThrows(IllegalArgumentException.class, () -> new ShipVisual(Collections.nCopies(33, box(0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ShipVisual(List.of(), Collections.nCopies(7, SpaceVector.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> new ShipVisual(List.of(), List.of(new SpaceVector(129, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new ShipVisualPart(ShipVisualPart.Geometry.BOX,
                ShipVisualPart.Material.PLAIN, SpaceVector.ZERO, new SpaceVector(0, 1, 1), 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new ShipVisualPart(ShipVisualPart.Geometry.CYLINDER,
                ShipVisualPart.Material.PLAIN, SpaceVector.ZERO, new SpaceVector(1, 1, 1), 0, 1, 0.5, COLOR));
        assertThrows(IllegalArgumentException.class, () -> box(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ShipRenderInstance(
                new ShipVisual(List.of(box(0)), List.of()), SpaceVector.ZERO, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShipBounds(new SpaceVector(1, 0, 0), SpaceVector.ZERO));
    }

    private static ShipVisualPart box(double yaw) {
        return new ShipVisualPart(ShipVisualPart.Geometry.BOX, ShipVisualPart.Material.PANEL,
                SpaceVector.ZERO, new SpaceVector(2, 4, 6), yaw, 1, 1, COLOR);
    }

    private static ShipRenderInstance instance(double x) {
        return new ShipRenderInstance(new ShipVisual(List.of(box(0)), List.of()), new SpaceVector(x, 0, 0), 0, -1);
    }

    private static void assertEncloses(ShipBounds bounds, SpaceVector point) {
        assertTrue(point.x() >= bounds.min().x() - 1e-10 && point.x() <= bounds.max().x() + 1e-10);
        assertTrue(point.y() >= bounds.min().y() - 1e-10 && point.y() <= bounds.max().y() + 1e-10);
        assertTrue(point.z() >= bounds.min().z() - 1e-10 && point.z() <= bounds.max().z() + 1e-10);
    }
}
