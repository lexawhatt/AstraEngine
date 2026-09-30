package dev.lexawhatt.astraengine.client.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChartViewportTest {
    @Test
    void dragMovesObjectsWithPointerAndZoomKeepsCursorPoint() {
        ChartViewport view = new ChartViewport();
        double base = 2.0e-10;
        view.focus(1.49e11, -2.0e10, 1000);
        double object = view.centerX();
        view.pan(83, -47, base);
        assertEquals(83, (object - view.centerX()) * view.scale(base), 1e-9);
        double anchoredX = view.centerX() + 120 / view.scale(base);
        double anchoredZ = view.centerZ() - 80 / view.scale(base);
        view.zoomAt(4, 120, -80, base);
        assertEquals(anchoredX, view.centerX() + 120 / view.scale(base), 0.0001);
        assertEquals(anchoredZ, view.centerZ() - 80 / view.scale(base), 0.0001);
    }

    @Test
    void zoomLimitsDoNotMoveAnchorAndResetReframes() {
        ChartViewport view = new ChartViewport();
        view.focus(42, 81, ChartViewport.MAX_ZOOM);
        view.zoomAt(1e8, 50, 90, 1);
        assertEquals(42, view.centerX(), 1e-9);
        assertEquals(81, view.centerZ(), 1e-9);
        assertEquals(ChartViewport.MAX_ZOOM, view.zoom(), 1e-8);
        view.zoomAt(-1e8, 0, 0, 1);
        assertEquals(ChartViewport.MIN_ZOOM, view.zoom(), 1e-12);
        view.reset();
        assertEquals(0, view.centerX()); assertEquals(0, view.centerZ()); assertEquals(1, view.zoom());
    }

    @Test
    void finiteInputsCannotCreateNonfiniteView() {
        ChartViewport view = new ChartViewport();
        view.pan(Double.MAX_VALUE, -Double.MAX_VALUE, 1e-20);
        assertTrue(Double.isFinite(view.centerX()) && Double.isFinite(view.centerZ()));
        assertThrows(IllegalArgumentException.class, () -> view.pan(Double.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> view.zoomAt(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> view.zoomAt(1, 0, 0, Double.MIN_VALUE));
        assertEquals(1, view.zoom(), "Rejected scale must not change zoom");
        assertThrows(IllegalArgumentException.class, () -> view.focus(0, Double.POSITIVE_INFINITY, 1));
    }
}
