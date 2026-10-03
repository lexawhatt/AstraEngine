package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Immutable normalized cubic Hermite segment; its Bezier hull bounds every candidate query. */
final class RiverSpline {
    private static final double[][] BINOMIAL = binomial();
    private final double ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz;
    private final SpaceVector center;
    private final SpaceVector along;
    private final SpaceVector across;
    private final double candidateCosine;
    private final double minAlong, maxAlong, minAcross, maxAcross;
    private final double endpointDistanceMeters;
    private final double[] nearestDerivative;

    RiverSpline(SpaceVector start, SpaceVector end, SpaceVector startTangent, SpaceVector endTangent,
            double envelopeMeters) {
        if (start == null || end == null || startTangent == null || endTangent == null
                || Math.abs(start.length() - 1) > 1e-9 || Math.abs(end.length() - 1) > 1e-9
                || Math.abs(startTangent.length() - 1) > 1e-9 || Math.abs(endTangent.length() - 1) > 1e-9
                || Math.abs(start.dot(startTangent)) > 1e-9 || Math.abs(end.dot(endTangent)) > 1e-9
                || !Double.isFinite(envelopeMeters) || envelopeMeters < 0 || envelopeMeters > 10_000) {
            throw new IllegalArgumentException("River curve requires unit geographic endpoints, tangent axes and a bounded envelope");
        }
        double length = end.subtract(start).length();
        if (length < 1e-9 || length > .05) { throw new IllegalArgumentException("River endpoints must span a local nonzero edge"); }
        endpointDistanceMeters = length * ContinentalTerrain.RADIUS_METERS;
        // The Bezier handles are <= 0.22 of this edge, even at a sharply angled tributary.
        // Tangent directions are shared at nodes; different edge lengths need not share parameter speed.
        var first = startTangent.multiply(length * .65);
        var last = endTangent.multiply(length * .65);
        var a = start.multiply(2).subtract(end.multiply(2)).add(first).add(last);
        var b = start.multiply(-3).add(end.multiply(3)).subtract(first.multiply(2)).subtract(last);
        ax = a.x(); ay = a.y(); az = a.z(); bx = b.x(); by = b.y(); bz = b.z();
        cx = first.x(); cy = first.y(); cz = first.z(); dx = start.x(); dy = start.y(); dz = start.z();
        center = start.add(end).normalized();
        along = end.subtract(start).normalized();
        across = PlanetaryFrame.cross(center, along).normalized();
        double minimumForward = 1, maximumPerpendicular = 0;
        double lowX = Double.POSITIVE_INFINITY, highX = Double.NEGATIVE_INFINITY;
        double lowY = Double.POSITIVE_INFINITY, highY = Double.NEGATIVE_INFINITY;
        for (var control : new SpaceVector[]{start, start.add(first.multiply(1.0 / 3)),
                end.subtract(last.multiply(1.0 / 3)), end}) {
            double forward = control.dot(center);
            minimumForward = Math.min(minimumForward, forward);
            maximumPerpendicular = Math.max(maximumPerpendicular,
                    control.subtract(center.multiply(forward)).length());
            double x = control.dot(along) / forward, y = control.dot(across) / forward;
            lowX = Math.min(lowX, x); highX = Math.max(highX, x);
            lowY = Math.min(lowY, y); highY = Math.max(highY, y);
        }
        if (minimumForward <= 0) { throw new IllegalArgumentException("River control hull crosses the planet center"); }
        candidateCosine = Math.cos(Math.atan2(maximumPerpendicular, minimumForward)
                + envelopeMeters / ContinentalTerrain.RADIUS_METERS + 1e-10);
        // Positive projective weights keep the normalized curve inside its projected control hull.
        // The sec^2 bound covers the entire geodesic valley offset in this local gnomonic frame.
        double padding = envelopeMeters / ContinentalTerrain.RADIUS_METERS
                / (candidateCosine * candidateCosine) + 1e-10;
        minAlong = lowX - padding; maxAlong = highX + padding;
        minAcross = lowY - padding; maxAcross = highY + padding;
        nearestDerivative = derivativeNumerator(controls());
    }

    boolean containsCandidate(SpaceVector normal) {
        double forward = center.dot(normal);
        if (forward < candidateCosine) { return false; }
        double x = along.dot(normal) / forward, y = across.dot(normal) / forward;
        return x >= minAlong && x <= maxAlong && y >= minAcross && y <= maxAcross;
    }

    double endpointDistanceMeters() { return endpointDistanceMeters; }

    SpaceVector point(double t) {
        return new SpaceVector(((ax * t + bx) * t + cx) * t + dx,
                ((ay * t + by) * t + cy) * t + dy, ((az * t + bz) * t + cz) * t + dz).normalized();
    }

    /** Unit geographic tangent, independent of the edge's parameter speed. */
    SpaceVector tangent(double t) {
        var point = point(t);
        var derivative = new SpaceVector((3 * ax * t + 2 * bx) * t + cx,
                (3 * ay * t + 2 * by) * t + cy, (3 * az * t + 2 * bz) * t + cz);
        return derivative.subtract(point.multiply(derivative.dot(point))).normalized();
    }

    /** Every stationary distance is considered; a single coarse bracket can miss a folded tributary. */
    double closestFraction(SpaceVector normal) {
        double x = normal.x(), y = normal.y(), z = normal.z();
        double best = distanceSquared(normal, 0) <= distanceSquared(normal, 1) ? 0 : 1;
        return closestInterval(normal,
                nearestDerivative[0] * x + nearestDerivative[1] * y + nearestDerivative[2] * z,
                nearestDerivative[3] * x + nearestDerivative[4] * y + nearestDerivative[5] * z,
                nearestDerivative[6] * x + nearestDerivative[7] * y + nearestDerivative[8] * z,
                nearestDerivative[9] * x + nearestDerivative[10] * y + nearestDerivative[11] * z,
                nearestDerivative[12] * x + nearestDerivative[13] * y + nearestDerivative[14] * z,
                nearestDerivative[15] * x + nearestDerivative[16] * y + nearestDerivative[17] * z,
                nearestDerivative[18] * x + nearestDerivative[19] * y + nearestDerivative[20] * z,
                nearestDerivative[21] * x + nearestDerivative[22] * y + nearestDerivative[23] * z,
                nearestDerivative[24] * x + nearestDerivative[25] * y + nearestDerivative[26] * z,
                0, 1, 24, best);
    }

    private double closestInterval(SpaceVector normal, double n0, double n1, double n2, double n3,
            double n4, double n5, double n6, double n7, double n8,
            double lower, double upper, int depth, double best) {
        int changes = 0, first = 0, previous = 0;
        for (int index = 0; index < 9; index++) {
            double value = switch (index) {
                case 0 -> n0;
                case 1 -> n1;
                case 2 -> n2;
                case 3 -> n3;
                case 4 -> n4;
                case 5 -> n5;
                case 6 -> n6;
                case 7 -> n7;
                default -> n8;
            };
            int sign = value > 0 ? 1 : value < 0 ? -1 : 0;
            if (sign == 0) { continue; }
            if (first == 0) { first = sign; }
            if (previous != 0 && sign != previous) { changes++; }
            previous = sign;
        }
        // Bernstein sign variation bounds the number of roots in this complete interval.
        if (changes == 0 || changes == 1 && first < 0) { return best; }
        if (changes == 1) {
            double a = 0, b = 1;
            for (int i = 0; i < 36; i++) {
                double middle = (a + b) * .5;
                if (bernstein(n0, n1, n2, n3, n4, n5, n6, n7, n8, middle) > 0) {
                    a = middle;
                } else {
                    b = middle;
                }
            }
            double candidate = lower + (upper - lower) * (a + b) * .5;
            return distanceSquared(normal, candidate) < distanceSquared(normal, best) ? candidate : best;
        }
        double middle = (lower + upper) * .5;
        if (distanceSquared(normal, middle) < distanceSquared(normal, best)) { best = middle; }
        if (depth == 0) {
            return best;
        }
        // Fixed degree-eight de Casteljau subdivision uses primitive stack values: terrain queries
        // neither allocate coefficient arrays nor retain thread-local/world scratch state.
        double b80 = (n0 + n1) * .5, b81 = (n1 + n2) * .5, b82 = (n2 + n3) * .5;
        double b83 = (n3 + n4) * .5, b84 = (n4 + n5) * .5, b85 = (n5 + n6) * .5;
        double b86 = (n6 + n7) * .5, b87 = (n7 + n8) * .5;
        double b70 = (b80 + b81) * .5, b71 = (b81 + b82) * .5, b72 = (b82 + b83) * .5;
        double b73 = (b83 + b84) * .5, b74 = (b84 + b85) * .5, b75 = (b85 + b86) * .5;
        double b76 = (b86 + b87) * .5;
        double b60 = (b70 + b71) * .5, b61 = (b71 + b72) * .5, b62 = (b72 + b73) * .5;
        double b63 = (b73 + b74) * .5, b64 = (b74 + b75) * .5, b65 = (b75 + b76) * .5;
        double b50 = (b60 + b61) * .5, b51 = (b61 + b62) * .5, b52 = (b62 + b63) * .5;
        double b53 = (b63 + b64) * .5, b54 = (b64 + b65) * .5;
        double b40 = (b50 + b51) * .5, b41 = (b51 + b52) * .5, b42 = (b52 + b53) * .5, b43 = (b53 + b54) * .5;
        double b30 = (b40 + b41) * .5, b31 = (b41 + b42) * .5, b32 = (b42 + b43) * .5;
        double b20 = (b30 + b31) * .5, b21 = (b31 + b32) * .5;
        double b10 = (b20 + b21) * .5;
        best = closestInterval(normal, n0, b80, b70, b60, b50, b40, b30, b20, b10,
                lower, middle, depth - 1, best);
        return closestInterval(normal, b10, b21, b32, b43, b54, b65, b76, b87, n8,
                middle, upper, depth - 1, best);
    }

    private static double bernstein(double n0, double n1, double n2, double n3, double n4,
            double n5, double n6, double n7, double n8, double t) {
        double complement = 1 - t, ratio = t / complement;
        double squared = complement * complement, fourth = squared * squared;
        return fourth * fourth * (n0 + ratio * (8 * n1 + ratio * (28 * n2 + ratio * (56 * n3
                + ratio * (70 * n4 + ratio * (56 * n5 + ratio * (28 * n6 + ratio * (8 * n7 + ratio * n8))))))));
    }

    private double distanceSquared(SpaceVector normal, double t) {
        double x = ((ax * t + bx) * t + cx) * t + dx;
        double y = ((ay * t + by) * t + cy) * t + dy;
        double z = ((az * t + bz) * t + cz) * t + dz;
        double inverse = 1 / Math.sqrt(x * x + y * y + z * z);
        x = x * inverse - normal.x(); y = y * inverse - normal.y(); z = z * inverse - normal.z();
        return x * x + y * y + z * z;
    }

    private double[] controls() {
        return new double[]{dx, dy, dz, dx + cx / 3, dy + cy / 3, dz + cz / 3,
                dx + (2 * cx + bx) / 3, dy + (2 * cy + by) / 3, dz + (2 * cz + bz) / 3,
                ax + bx + cx + dx, ay + by + cy + dy, az + bz + cz + dz};
    }

    private static double[] derivativeNumerator(double[] controls) {
        double[] derivative = new double[9];
        for (int i = 0; i < derivative.length; i++) { derivative[i] = 3 * (controls[i + 3] - controls[i]); }
        double[] squared = dotProduct(controls, controls), radial = dotProduct(controls, derivative);
        double[] result = new double[27];
        for (int component = 0; component < 3; component++) {
            double[] values = new double[4], slopes = new double[3];
            for (int i = 0; i < values.length; i++) { values[i] = controls[i * 3 + component]; }
            for (int i = 0; i < slopes.length; i++) { slopes[i] = derivative[i * 3 + component]; }
            double[] first = product(slopes, squared), second = product(values, radial);
            for (int i = 0; i < first.length; i++) { result[i * 3 + component] = first[i] - second[i]; }
        }
        return result;
    }

    /** Conservative normalized-curve caps and a Bernstein proof of radial descent for each subcurve. */
    Span[] spans(double halfWidthMeters) {
        double[] controls = controls();
        var outlet = point(1);
        double[] first = project(nearestDerivative, outlet);
        var result = new Span[32];
        subdivide(controls, first, outlet, halfWidthMeters / ContinentalTerrain.RADIUS_METERS, 5, 0, result);
        return result;
    }

    private static void subdivide(double[] controls, double[] radial, SpaceVector outlet, double width,
            int depth, int offset, Span[] output) {
        if (depth > 0) {
            double[][] geometry = split(controls, 3), proof = split(radial, 1);
            subdivide(geometry[0], proof[0], outlet, width, depth - 1, offset, output);
            subdivide(geometry[1], proof[1], outlet, width, depth - 1, offset + (1 << (depth - 1)), output);
            return;
        }
        var center = new SpaceVector(controls[0] + controls[9], controls[1] + controls[10],
                controls[2] + controls[11]).normalized();
        double radius = 0;
        for (int i = 0; i < controls.length; i += 3) {
            var direction = new SpaceVector(controls[i], controls[i + 1], controls[i + 2]).normalized();
            radius = Math.max(radius, direction.subtract(center).length());
        }
        radius += width + 2e-12;
        boolean decreasing = true;
        // Positive Bernstein coefficients prove d(dot(outlet,q)/|q|)/dt > 0 throughout the span.
        // Treat cancellation-scale uncertainty as unproved and include it in the flat inner head.
        for (double coefficient : radial) { decreasing &= coefficient > 1e-14; }
        output[offset] = new Span(center, radius,
                (center.subtract(outlet).length() + radius) * ContinentalTerrain.RADIUS_METERS, decreasing);
    }

    private static double[] project(double[] controls, SpaceVector axis) {
        double[] result = new double[controls.length / 3];
        for (int i = 0; i < result.length; i++) {
            result[i] = controls[i * 3] * axis.x() + controls[i * 3 + 1] * axis.y()
                    + controls[i * 3 + 2] * axis.z();
        }
        return result;
    }

    private static double[] dotProduct(double[] first, double[] second) {
        int n = first.length / 3 - 1, m = second.length / 3 - 1;
        double[] result = new double[n + m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                double dot = first[i * 3] * second[j * 3] + first[i * 3 + 1] * second[j * 3 + 1]
                        + first[i * 3 + 2] * second[j * 3 + 2];
                result[i + j] += dot * BINOMIAL[n][i] * BINOMIAL[m][j] / BINOMIAL[n + m][i + j];
            }
        }
        return result;
    }

    private static double[] product(double[] first, double[] second) {
        int n = first.length - 1, m = second.length - 1;
        double[] result = new double[n + m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                result[i + j] += first[i] * second[j] * BINOMIAL[n][i] * BINOMIAL[m][j]
                        / BINOMIAL[n + m][i + j];
            }
        }
        return result;
    }

    private static double[][] split(double[] input, int stride) {
        double[] work = input.clone(), left = new double[input.length], right = new double[input.length];
        int degree = input.length / stride - 1;
        for (int step = 0; step <= degree; step++) {
            for (int component = 0; component < stride; component++) {
                left[step * stride + component] = work[component];
                right[(degree - step) * stride + component] = work[(degree - step) * stride + component];
            }
            for (int i = 0; i < (degree - step) * stride; i++) { work[i] = (work[i] + work[i + stride]) * .5; }
        }
        return new double[][]{left, right};
    }

    private static double[][] binomial() {
        double[][] result = new double[9][];
        for (int n = 0; n < result.length; n++) {
            result[n] = new double[n + 1];
            result[n][0] = result[n][n] = 1;
            for (int i = 1; i < n; i++) { result[n][i] = result[n - 1][i - 1] + result[n - 1][i]; }
        }
        return result;
    }

    record Span(SpaceVector center, double radius, double outletBoundMeters, boolean radiallyDecreasing) { }
}
