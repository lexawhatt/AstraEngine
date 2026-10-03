package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Immutable hydraulic head shared by overlapping tributaries at one geographic junction. */
final class RiverJunction {
    private static final double RADIUS = ContinentalTerrain.RADIUS_METERS;
    private final SpaceVector outlet;
    private final double mergeRadius;
    private final double foldRadius;
    private final double outletWater;
    private final double slope;

    private RiverJunction(SpaceVector outlet, double mergeRadius, double foldRadius,
            double outletWater, double slope) {
        this.outlet = outlet;
        this.mergeRadius = mergeRadius;
        this.foldRadius = foldRadius;
        this.outletWater = outletWater;
        this.slope = slope;
    }

    /** Called downstream to upstream; lower an upstream node only if already inside this shared head. */
    static RiverJunction prepare(int parent, int[] incoming, RiverSpline[] curves,
            double[] water, double[] widths) {
        SpaceVector outlet = curves[incoming[0]].point(1);
        var spans = new RiverSpline.Span[incoming.length][];
        double fold = 0, merge = 0;
        for (int i = 0; i < incoming.length; i++) {
            spans[i] = curves[incoming[i]].spans(widths[i]);
            for (var span : spans[i]) {
                if (!span.radiallyDecreasing()) { fold = Math.max(fold, span.outletBoundMeters()); }
            }
        }
        // Normalized Bezier cones contain the complete curve, including its water-width offset.
        // Intersecting span caps conservatively enclose every possible sibling ownership change.
        for (int first = 0; first < spans.length; first++) {
            for (int second = first + 1; second < spans.length; second++) {
                for (var a : spans[first]) {
                    for (var b : spans[second]) {
                        if (a.center().subtract(b.center()).length() <= a.radius() + b.radius()) {
                            merge = Math.max(merge, Math.min(a.outletBoundMeters(), b.outletBoundMeters()));
                        }
                    }
                }
            }
        }
        merge = Math.max(merge, fold);
        double slope = Double.POSITIVE_INFINITY;
        for (int cell : incoming) {
            double distance = curves[cell].endpointDistanceMeters();
            if (distance > fold) {
                slope = Math.min(slope, Math.max(0, water[cell] - water[parent]) / (distance - fold));
            }
        }
        if (!Double.isFinite(slope)) { slope = 0; }
        var result = new RiverJunction(outlet, merge, fold, water[parent], slope);
        for (int cell : incoming) {
            double distance = curves[cell].endpointDistanceMeters();
            if (distance <= merge) { water[cell] = result.commonHead(distance); }
        }
        return result;
    }

    double head(SpaceVector normal, double sourceDistance, double sourceWater) {
        double distance = normal.subtract(outlet).length() * RADIUS;
        if (distance <= mergeRadius) { return commonHead(distance); }
        if (sourceDistance <= mergeRadius) { return commonHead(distance); }
        double atMerge = commonHead(mergeRadius);
        return atMerge + (sourceWater - atMerge)
                * Math.clamp((distance - mergeRadius) / (sourceDistance - mergeRadius), 0, 1);
    }

    private double commonHead(double distance) {
        return outletWater + slope * Math.max(0, distance - foldRadius);
    }
}
