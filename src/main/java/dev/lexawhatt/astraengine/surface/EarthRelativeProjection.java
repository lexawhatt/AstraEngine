package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Camera-relative projective coefficients for small source-chart meshes. No absolute coordinate is sent as a float. */
public record EarthRelativeProjection(SpaceVector xNumerator, SpaceVector zNumerator,
        SpaceVector denominator, double yOffset) {
    /** Source mesh origin and target observer use their respective chart meters. Both charts must share geography. */
    public static EarthRelativeProjection between(EarthChart source, SpaceVector origin, EarthChart target, SpaceVector camera) {
        if (camera == null) { throw new IllegalArgumentException("Relative projection requires a target observer"); }
        var transform = new EarthChartTransform(source, target);
        SpaceVector mapped = transform.position(origin);
        SpaceVector plane = source.face().outward().multiply(EarthChart.RADIUS_METERS)
                .add(source.face().u().multiply(origin.x())).add(source.face().v().multiply(origin.z()));
        double distance = plane.dot(target.face().outward());
        double dx = source.face().u().dot(target.face().outward()) / distance;
        double dz = source.face().v().dot(target.face().outward()) / distance;
        double scale = EarthChart.RADIUS_METERS / distance;
        return new EarthRelativeProjection(new SpaceVector(mapped.x() - camera.x(),
                scale * source.face().u().dot(target.face().u()) - camera.x() * dx,
                scale * source.face().v().dot(target.face().u()) - camera.x() * dz),
                new SpaceVector(mapped.z() - camera.z(),
                        scale * source.face().u().dot(target.face().v()) - camera.z() * dx,
                        scale * source.face().v().dot(target.face().v()) - camera.z() * dz),
                new SpaceVector(1, dx, dz), mapped.y() - camera.y());
    }

    public EarthRelativeProjection {
        if (xNumerator == null || zNumerator == null || denominator == null || !Double.isFinite(yOffset)) {
            throw new IllegalArgumentException("Relative projection requires finite coefficients");
        }
    }

    /** CPU reference for a local mesh vertex in meters. The target must remain in the outward hemisphere. */
    public SpaceVector position(SpaceVector local) {
        var input = new SpaceVector(1, local.x(), local.z());
        double forward = denominator.dot(input);
        if (forward <= 0) { throw new IllegalArgumentException("Relative mesh extends behind its target hemisphere"); }
        return new SpaceVector(xNumerator.dot(input) / forward, yOffset + local.y(), zNumerator.dot(input) / forward);
    }
}
