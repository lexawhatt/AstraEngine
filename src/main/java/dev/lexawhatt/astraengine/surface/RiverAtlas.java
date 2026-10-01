package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/**
 * Immutable regional drainage on a closed cube sphere. Preparation is CPU work for startup/workers, never a
 * tick or render callback. Arrays contain derived geographic data only, shared safely across logical sides.
 * Priority-Flood routes depressions to ocean; reverse accumulation and breaching produce downhill channels.
 */
public final class RiverAtlas {
    public static final int RESOLUTION = 256;
    private static final double RADIUS = ContinentalTerrain.RADIUS_METERS;
    private static final CubeFace[] FACES = CubeFace.values();
    private static final double MAX_VALLEY = 6000;
    private static final double MAX_MEANDER = 1200;
    private final double[] nx, ny, nz, water, area, bends;
    private final int[] downstream, contributors;
    private final int riverCount;

    private RiverAtlas(long seed) {
        int size = 6 * RESOLUTION * RESOLUTION;
        nx = new double[size]; ny = new double[size]; nz = new double[size];
        water = new double[size]; bends = new double[size]; area = new double[size]; contributors = new int[size];
        double[] elevation = new double[size];
        int[] neighbors = new int[size * 8];
        for (int face = 0; face < 6; face++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    int i = index(face, x, z);
                    double u = -1 + 2 * (x + .5 + jitter(i, 17) * .25) / RESOLUTION;
                    double v = -1 + 2 * (z + .5 + jitter(i, 91) * .25) / RESOLUTION;
                    SpaceVector normal = FACES[face].outward().add(FACES[face].u().multiply(u))
                            .add(FACES[face].v().multiply(v)).normalized();
                    nx[i] = normal.x(); ny[i] = normal.y(); nz[i] = normal.z();
                    var sample = ContinentalTerrainV3.base(normal, seed);
                    elevation[i] = sample.heightMeters();
                    water[i] = Math.max(0, elevation[i] - 64 - sample.mountainMask() * 250);
                    bends[i] = MAX_MEANDER * Math.clamp(1 - Math.max(0, elevation[i]) / 1600, .12, 1);
                    contributors[i] = elevation[i] > 0 ? 1 : 0;
                    area[i] = elevation[i] > 0 ? 4 * RADIUS * RADIUS / (RESOLUTION * RESOLUTION * 1e6)
                            / Math.pow(1 + u * u + v * v, 1.5) : 0;
                    int k = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx != 0 || dz != 0) { neighbors[i * 8 + k++] = neighbor(face, x + dx, z + dz); }
                        }
                    }
                }
            }
        }
        var routing = PriorityFlood.route(elevation, neighbors);
        downstream = routing.downstream();
        int[] order = routing.order();
        for (int i = size - 1; i >= 0; i--) {
            int node = order[i], parent = downstream[node];
            if (parent >= 0) { area[parent] += area[node]; contributors[parent] += contributors[node]; }
        }
        int count = 0;
        // Sample the actual curved route, not just its coarse endpoints, before assigning water levels.
        for (int node = 0; node < size; node++) {
            if (!river(node)) { continue; }
            count++;
            for (int step = 1; step <= 24; step++) {
                var sample = ContinentalTerrainV3.base(point(node, step / 24.0), seed);
                water[node] = Math.min(water[node], Math.max(0, sample.heightMeters() - 64 - sample.mountainMask() * 250));
            }
        }
        for (int i = size - 1; i >= 0; i--) {
            int node = order[i], parent = downstream[node];
            if (parent >= 0) { water[parent] = Math.min(water[parent], Math.max(0, water[node] - .05)); }
        }
        riverCount = count;
    }

    /** Build on an owner-selected startup/worker thread. No global seed cache or world references are retained. */
    public static RiverAtlas prepare(long seed) { return new RiverAtlas(seed); }

    /** Number of regional channel segments; small sub-grid streams are not inferred from this count. */
    public int riverCount() { return riverCount; }
    /** Read-only graph size for geographic tooling and invariant checks. */
    public int cellCount() { return downstream.length; }
    /** Downstream cell, or -1 at an ocean outlet; array bounds are enforced. */
    public int downstream(int cell) { return downstream[cell]; }
    /** Routed physical water elevation at a cell, in meters above sea level. */
    public double waterMeters(int cell) { return water[cell]; }
    /** Contributing drainage area in square kilometers. */
    public double drainageArea(int cell) { return area[cell]; }
    /** Bank-to-center distance in physical meters, derived from upstream catchment area. */
    public double channelHalfWidthMeters(int cell) { return Math.clamp(8 + Math.sqrt(area[cell]) * .5, 12, 500); }
    /** Whether this cell's outgoing edge is a regional river. */
    public boolean river(int cell) { return downstream[cell] >= 0 && contributors[cell] >= 3; }

    /** Geographic centerline point, source at zero and confluence/outlet at one. No world access. */
    public SpaceVector point(int cell, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1 || downstream[cell] < 0) {
            throw new IllegalArgumentException("River point requires an outgoing edge and a fraction in [0,1]");
        }
        int parent = downstream[cell];
        double ax = nx[cell], ay = ny[cell], az = nz[cell];
        double dx = nx[parent] - ax, dy = ny[parent] - ay, dz = nz[parent] - az;
        double px = ay * nz[parent] - az * ny[parent];
        double py = az * nx[parent] - ax * nz[parent];
        double pz = ax * ny[parent] - ay * nx[parent];
        double length = Math.sqrt(px * px + py * py + pz * pz);
        double bend = meander(cell) * wave(cell, fraction) / RADIUS / length;
        return new SpaceVector(ax + dx * fraction + px * bend, ay + dy * fraction + py * bend,
                az + dz * fraction + pz * bend).normalized();
    }

    /** Applies the shared river bed/water/valley cross-section to source relief. Safe for concurrent sampling. */
    ContinentalTerrain.Sample shape(SpaceVector normal, ContinentalTerrain.Sample base) {
        if (base.heightMeters() < -40) { return base; }
        CubeFace face = CubeFace.containing(normal);
        double forward = normal.dot(face.outward());
        int ix = (int) Math.floor((normal.dot(face.u()) / forward + 1) * .5 * RESOLUTION);
        int iz = (int) Math.floor((normal.dot(face.v()) / forward + 1) * .5 * RESOLUTION);
        double height = base.heightMeters(), level = Math.max(0, height);
        for (int z = iz - 2; z <= iz + 2; z++) {
            for (int x = ix - 2; x <= ix + 2; x++) {
                int node = neighbor(face.ordinal(), x, z);
                if (!river(node)) { continue; }
                int parent = downstream[node];
                double ax = nx[node], ay = ny[node], az = nz[node];
                double dx = nx[parent] - ax, dy = ny[parent] - ay, dz = nz[parent] - az;
                double length2 = dx * dx + dy * dy + dz * dz;
                double t = Math.clamp(((normal.x() - ax) * dx + (normal.y() - ay) * dy + (normal.z() - az) * dz) / length2, 0, 1);
                double qx = ax + t * dx, qy = ay + t * dy, qz = az + t * dz;
                double dot = normal.x() * qx + normal.y() * qy + normal.z() * qz;
                double distance2 = Math.max(0, 1 - dot * dot / (qx * qx + qy * qy + qz * qz)) * RADIUS * RADIUS;
                if (distance2 > (MAX_VALLEY + MAX_MEANDER + 1000) * (MAX_VALLEY + MAX_MEANDER + 1000)) { continue; }
                double px = ay * nz[parent] - az * ny[parent];
                double py = az * nx[parent] - ax * nz[parent];
                double pz = ax * ny[parent] - ay * nx[parent];
                double length = Math.sqrt(px * px + py * py + pz * pz);
                px /= length; py /= length; pz /= length;
                double bend = meander(node) / RADIUS;
                // Project onto the curved centerline; the envelope bounds the candidates before trigonometry.
                for (int iteration = 0; iteration < 3; iteration++) {
                    double w = bend * wave(node, t), derivative = bend * waveDerivative(node, t);
                    qx = ax + t * dx + px * w; qy = ay + t * dy + py * w; qz = az + t * dz + pz * w;
                    double norm = Math.sqrt(qx * qx + qy * qy + qz * qz);
                    qx /= norm; qy /= norm; qz /= norm;
                    double tx = dx + px * derivative, ty = dy + py * derivative, tz = dz + pz * derivative;
                    double radial = tx * qx + ty * qy + tz * qz;
                    tx -= radial * qx; ty -= radial * qy; tz -= radial * qz;
                    t = Math.clamp(t + ((normal.x() - qx) * tx + (normal.y() - qy) * ty + (normal.z() - qz) * tz)
                            / (tx * tx + ty * ty + tz * tz), 0, 1);
                }
                double w = bend * wave(node, t);
                qx = ax + t * dx + px * w; qy = ay + t * dy + py * w; qz = az + t * dz + pz * w;
                dot = normal.x() * qx + normal.y() * qy + normal.z() * qz;
                double distance = Math.sqrt(Math.max(0, 1 - dot * dot / (qx * qx + qy * qy + qz * qz))) * RADIUS;
                double waterHeight = water[node] * (1 - t) + water[parent] * t;
                double halfWidth = channelHalfWidthMeters(node);
                double depth = Math.clamp(halfWidth * .10, 3, 22);
                double valley = Math.clamp(220 + halfWidth * 4 + Math.max(0, base.heightMeters() - waterHeight) * 3, 220, MAX_VALLEY);
                double candidate;
                boolean wet = distance < halfWidth;
                if (wet) {
                    candidate = Math.min(base.heightMeters(), waterHeight - depth * (1 - distance * distance / (halfWidth * halfWidth)));
                } else {
                    double fraction = Math.clamp((distance - halfWidth) / valley, 0, 1);
                    double bank = waterHeight + Math.min(12, (distance - halfWidth) * .12);
                    candidate = bank + (base.heightMeters() - bank) * fraction * fraction * (3 - 2 * fraction);
                }
                // A pre-existing depression may already lie below the routed bed. It still contains water;
                // requiring a height change here would punch dry gaps into otherwise connected channels.
                if (candidate < height || wet && candidate == height && level <= Math.max(0, height)) {
                    height = candidate;
                    level = wet ? Math.max(height, waterHeight) : Math.max(0, height);
                }
            }
        }
        return ContinentalTerrainV3.climate(base, height, level);
    }

    private double meander(int node) { return bends[node]; }
    private static double wave(int node, double t) { return Math.sin(Math.PI * t) * Math.sin(6 * Math.PI * t + jitter(node, 393) * Math.PI); }
    private static double waveDerivative(int node, double t) {
        double phase = 6 * Math.PI * t + jitter(node, 393) * Math.PI;
        return Math.PI * Math.cos(Math.PI * t) * Math.sin(phase) + 6 * Math.PI * Math.sin(Math.PI * t) * Math.cos(phase);
    }
    private static double jitter(int node, int salt) {
        long value = ((long) node + salt) * 0x9E3779B97F4A7C15L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return ((value ^ value >>> 31) >>> 11) * 0x1.0p-53 - .5;
    }
    private static int index(int face, int x, int z) { return (face * RESOLUTION + z) * RESOLUTION + x; }
    private static int neighbor(int face, int x, int z) {
        if (x >= 0 && x < RESOLUTION && z >= 0 && z < RESOLUTION) { return index(face, x, z); }
        SpaceVector direction = FACES[face].outward().add(FACES[face].u().multiply(-1 + 2 * (x + .5) / RESOLUTION))
                .add(FACES[face].v().multiply(-1 + 2 * (z + .5) / RESOLUTION));
        CubeFace other = CubeFace.containing(direction);
        double forward = direction.dot(other.outward());
        int ox = Math.clamp((int) Math.floor((direction.dot(other.u()) / forward + 1) * .5 * RESOLUTION), 0, RESOLUTION - 1);
        int oz = Math.clamp((int) Math.floor((direction.dot(other.v()) / forward + 1) * .5 * RESOLUTION), 0, RESOLUTION - 1);
        return index(other.ordinal(), ox, oz);
    }
}
