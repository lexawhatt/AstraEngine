package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.Optional;

/**
 * Immutable geographic landing proposal from the saved continental field. This is not a collision or travel
 * authorization: the logical server must load the destination's bounded neighborhood, inspect actual blocks
 * and validate a standing volume before committing. No world, chunk, player or renderer state is held.
 */
public record EarthLandingTarget(EarthChart chart, SpaceVector localFeet) {
    public EarthLandingTarget {
        if (chart == null || !chart.contains(localFeet)) {
            throw new IllegalArgumentException("Earth landing proposal must belong to its canonical storage chart");
        }
    }

    /**
     * Intersects a forward body-fixed ray with terrain or sea level. Units are double meters. An immutable,
     * bounded march (at most 2048 samples plus 20 refinements) returns absence on a miss, an interior start,
     * unsupported distance or unresolved grazing feature; it never substitutes a pole or patch center.
     * Worker-safe. Null inputs and a zero direction fail explicitly. The caller owns thread and request lifetime.
     */
    public static Optional<EarthLandingTarget> aim(ContinentalTerrain terrain, SpaceVector origin,
            SpaceVector direction) {
        if (terrain == null || origin == null || direction == null) {
            throw new IllegalArgumentException("Earth aiming requires terrain, origin and direction");
        }
        SpaceVector ray = direction.normalized();
        double radius = EarthChart.RADIUS_METERS;
        if (origin.length() < radius || origin.length() > radius * 6) { return Optional.empty(); }
        double projection = origin.dot(ray);
        double shell = radius + ContinentalTerrain.MAX_ELEVATION + 1;
        double discriminant = projection * projection - origin.dot(origin) + shell * shell;
        if (projection >= 0 || discriminant < 0) { return Optional.empty(); }
        double start = Math.max(0, -projection - Math.sqrt(discriminant));
        double end = -projection + Math.sqrt(discriminant);
        double previous = start;
        double distance = start;
        double clearance = clearance(terrain, origin.add(ray.multiply(distance)));
        if (clearance <= 0) { return Optional.empty(); }
        for (int sample = 0; sample < 2048 && distance < end; sample++) {
            previous = distance;
            distance = Math.min(end, distance + Math.clamp(clearance * .35, 2, 8192));
            SpaceVector point = origin.add(ray.multiply(distance));
            clearance = clearance(terrain, point);
            if (clearance > 0) { continue; }
            for (int refinement = 0; refinement < 20; refinement++) {
                double middle = (previous + distance) * .5;
                if (clearance(terrain, origin.add(ray.multiply(middle))) > 0) { previous = middle; }
                else { distance = middle; }
            }
            GeographicPosition address = GeographicPosition.fromBody(origin.add(ray.multiply(distance)), radius);
            // The actual heightmap chooses the standing height later. This point chooses persistent storage only.
            double surface = terrain.sample(address.normal()).waterMeters();
            address = new GeographicPosition(address.latitudeRadians(), address.longitudeRadians(), Math.floor(surface));
            Optional<EarthChart> owner = EarthChart.owner(address, terrain.version());
            if (owner.isEmpty()) { return Optional.empty(); }
            return Optional.of(new EarthLandingTarget(owner.get(), owner.get().resolve(address).orElseThrow()));
        }
        return Optional.empty();
    }

    private static double clearance(ContinentalTerrain terrain, SpaceVector point) {
        if (point.length() < EarthChart.RADIUS_METERS) { return point.length() - EarthChart.RADIUS_METERS; }
        return point.length() - EarthChart.RADIUS_METERS - terrain.sample(point).waterMeters();
    }

    /**
     * Validates a geographic proposal from a possibly delayed view against the current body-fixed observer.
     * Only an exposed terrain/sea point in the normal approach envelope is accepted. The bounded first-hit
     * march rejects the far side and intervening terrain; the small tolerance covers its meter-scale steps.
     * No client altitude is accepted and a valid proposal keeps its exact latitude/longitude as Earth rotates.
     */
    public static Optional<EarthLandingTarget> visible(ContinentalTerrain terrain, SpaceVector origin,
            SpaceVector normal) {
        if (terrain == null || origin == null || normal == null || Math.abs(normal.length() - 1) > 1e-6) {
            throw new IllegalArgumentException("Earth landing visibility requires terrain, observer and unit normal");
        }
        SpaceVector unit = normal.normalized();
        double height = terrain.sample(unit).waterMeters();
        SpaceVector point = unit.multiply(EarthChart.RADIUS_METERS + height);
        SpaceVector relative = point.subtract(origin);
        if (relative.length() < 1) { return Optional.empty(); }
        var intersection = aim(terrain, origin, relative);
        if (intersection.isEmpty() || intersection.get().chart().normal(intersection.get().localFeet().x(),
                intersection.get().localFeet().z()).distance(unit) * EarthChart.RADIUS_METERS > 32) {
            return Optional.empty();
        }
        var direction = GeographicPosition.fromBody(unit, 1);
        var address = new GeographicPosition(direction.latitudeRadians(), direction.longitudeRadians(), Math.floor(height));
        return EarthChart.owner(address, terrain.version())
                .map(chart -> new EarthLandingTarget(chart, chart.resolve(address).orElseThrow()));
    }
}
