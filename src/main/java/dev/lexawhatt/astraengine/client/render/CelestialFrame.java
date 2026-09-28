package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable CPU projection data; no GPU, world or authoritative simulation lifetime. */
record CelestialFrame(List<Body> bodies, int lensIndex) {
    CelestialFrame {
        bodies = List.copyOf(bodies);
    }

    /** Subtracts meter coordinates in double precision, then orders far to near for sky composition. */
    static CelestialFrame extract(CosmosSystem system, SpaceVector cameraMeters, double seconds) {
        if (system == null || cameraMeters == null || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Celestial projection requires a system, camera and finite time");
        }
        var frames = new ArrayList<Body>(system.bodies().size());
        for (CelestialBody body : system.bodies()) {
            SpaceVector position = body.positionAt(seconds);
            SpaceVector relative = position.subtract(cameraMeters);
            double distance = relative.length();
            if (!Double.isFinite(distance)) {
                throw new IllegalArgumentException("Celestial camera distance exceeds the finite meter range");
            }
            SpaceVector direction = distance > 0 ? relative.normalized() : new SpaceVector(0, 0, 1);
            // Navigation prevents entry. Diagnostic cameras still need a finite interior fallback.
            frames.add(new Body(body, position, direction, Math.max(distance, body.radiusMeters() * 0.001)));
        }
        frames.sort(Comparator.comparingDouble(Body::distance).reversed());
        int lensIndex = -1;
        for (int index = 0; index < frames.size(); index++) {
            if (frames.get(index).descriptor().kind() == CelestialBody.Kind.BLACK_HOLE) {
                lensIndex = index;
            }
        }
        return new CelestialFrame(frames, lensIndex);
    }

    /** Camera distance in units of the nearest black hole's distance; one lens is supported per frame. */
    float distanceRatio(int index) {
        if (lensIndex < 0) { return 1; }
        double ratio = bodies.get(index).distance() / bodies.get(lensIndex).distance();
        return (float) Math.clamp(ratio, 1.0e-20, 1.0e24);
    }

    record Body(CelestialBody descriptor, SpaceVector position, SpaceVector direction, double distance) {
        float radiusRatio() { return (float) (descriptor.radiusMeters() / distance); }
    }
}
