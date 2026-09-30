package dev.lexawhatt.astraengine.client.flight;

/**
 * Screen-owned top-down chart framing. Coordinates use the chart's units (meters or light-years),
 * independent of flight/discovery state. Pixel deltas use GUI coordinates, not framebuffer pixels.
 */
public final class ChartViewport {
    public static final double MIN_ZOOM = 0.25;
    public static final double MAX_ZOOM = 1_000_000;
    private static final double MAX_CENTER = 1.0e30;
    private double centerX;
    private double centerZ;
    private double zoom = 1;

    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }
    public double zoom() { return zoom; }

    /** Returns GUI pixels per chart unit for a positive finite unzoomed scale. */
    public double scale(double baseScale) {
        finite(baseScale);
        return scaleAt(baseScale, zoom);
    }

    private static double scaleAt(double baseScale, double zoom) {
        double result = baseScale * zoom;
        if (baseScale <= 0 || !Double.isFinite(result) || result <= 0 || !Double.isFinite(1 / result)) {
            throw new IllegalArgumentException("Chart scale must remain positive and finite");
        }
        return result;
    }

    /** Drags the view with the pointer; a positive screen delta moves plotted objects right/down. */
    public void pan(double deltaX, double deltaY, double baseScale) {
        finite(deltaX); finite(deltaY);
        double scale = scale(baseScale);
        centerX = bounded(centerX - deltaX / scale);
        centerZ = bounded(centerZ - deltaY / scale);
    }

    /** Changes zoom while retaining the chart point below the cursor relative to canvas center. */
    public void zoomAt(double wheelSteps, double offsetX, double offsetY, double baseScale) {
        finite(wheelSteps); finite(offsetX); finite(offsetY);
        double before = scale(baseScale);
        double nextZoom = Math.exp(Math.clamp(Math.log(zoom) + wheelSteps * Math.log(1.3),
                Math.log(MIN_ZOOM), Math.log(MAX_ZOOM)));
        double after = scaleAt(baseScale, nextZoom);
        zoom = nextZoom;
        centerX = bounded(centerX + offsetX * (1 / before - 1 / after));
        centerZ = bounded(centerZ + offsetY * (1 / before - 1 / after));
    }

    /** Explicit local framing; outlying finite centers and zoom are clamped to safe chart bounds. */
    public void focus(double x, double z, double desiredZoom) {
        finite(x); finite(z); finite(desiredZoom);
        centerX = bounded(x);
        centerZ = bounded(z);
        zoom = Math.clamp(desiredZoom, MIN_ZOOM, MAX_ZOOM);
    }

    /** Restores the system-centered default frame without changing any navigation state. */
    public void reset() { centerX = 0; centerZ = 0; zoom = 1; }

    private static double bounded(double coordinate) { return Math.clamp(coordinate, -MAX_CENTER, MAX_CENTER); }

    private static void finite(double value) {
        if (!Double.isFinite(value)) { throw new IllegalArgumentException("Chart input must be finite"); }
    }
}
