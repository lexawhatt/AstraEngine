package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Bounded immutable distant-surface mesh from the saved continental field. Worker-safe numeric data only;
 * no chunks, worlds, edits or simulation state are read. The local presentation join does not change geography.
 */
public final class ContinentalLandscape {
    public static final int SECTORS = 256;
    public static final int RINGS = 128;
    public static final double FIRST_RADIUS_METERS = 8;
    public static final double LAST_RADIUS_METERS = 2_000_000;
    public static final double JOIN_START_METERS = 512;
    public static final double JOIN_END_METERS = 32_768;
    private final CubeStorageChart chart;
    private final double centerX;
    private final double centerZ;
    private final PlanetaryFrame frame;
    private final float[] positions;
    private final float[] colors;
    private final float[] normals;
    private final float[] joinWeights;
    private final boolean[] liquid;
    private final int[] triangles;

    private ContinentalLandscape(CubeStorageChart chart, double centerX, double centerZ, PlanetaryFrame frame,
            float[] positions, float[] colors, float[] normals, float[] joinWeights, boolean[] liquid, int[] triangles) {
        this.chart = chart;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.frame = frame;
        this.positions = positions;
        this.colors = colors;
        this.normals = normals;
        this.joinWeights = joinWeights;
        this.liquid = liquid;
        this.triangles = triangles;
    }

    /**
     * Builds about 2 MiB of numeric mesh data. Center coordinates are host chart meters; height samples are
     * physical sea-level meters. Checks cancellation once per ring. No GL calls or asynchronous ownership here.
     */
    public static ContinentalLandscape bake(EarthChart chart, double centerX, double centerZ,
            BooleanSupplier cancelled) {
        return bake(chart, centerX, centerZ, EarthSurfacePalette.DEFAULT, cancelled);
    }

    /** Builds with a captured immutable host appearance; no worker reads textures or biome registries. */
    public static ContinentalLandscape bake(EarthChart chart, double centerX, double centerZ,
            EarthSurfacePalette palette, BooleanSupplier cancelled) {
        if (chart == null || palette == null) { throw new IllegalArgumentException("Landscape chart and palette are required"); }
        return bake(chart, centerX, centerZ, palette, null,
                new ContinentalTerrain(chart.terrainVersion(), ContinentalTerrain.SEED), null, cancelled);
    }

    /** Shares the same bounded geometry path with full solid-body charts and their immutable material palette. */
    public static ContinentalLandscape bake(PlanetChart chart, double centerX, double centerZ,
            SolidPlanetPalette palette, BooleanSupplier cancelled) {
        if (chart == null || palette == null) { throw new IllegalArgumentException("Landscape chart and palette are required"); }
        return bake(chart, centerX, centerZ, null, palette, null, new SolidPlanetTerrain(chart.profile()), cancelled);
    }

    private static ContinentalLandscape bake(CubeStorageChart chart, double centerX, double centerZ,
            EarthSurfacePalette palette, SolidPlanetPalette planetPalette, ContinentalTerrain terrain,
            SolidPlanetTerrain solidTerrain, BooleanSupplier cancelled) {
        if (chart == null || cancelled == null || !Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || Math.abs(centerX) > chart.radiusMeters() || Math.abs(centerZ) > chart.radiusMeters()) {
            throw new IllegalArgumentException("Landscape requires a chart, contained center and cancellation flag");
        }
        PlanetaryFrame frame = chart.tangentFrame(centerX, centerZ, 0);
        int count = 1 + RINGS * SECTORS;
        SpaceVector[] directions = new SpaceVector[count];
        directions[0] = frame.upAxis();
        for (int ring = 0; ring < RINGS; ring++) {
            if (cancelled.getAsBoolean()) { throw new CancellationException("Distant surface retired"); }
            double radius = ringRadius(ring) * projectionScale(chart);
            for (int sector = 0; sector < SECTORS; sector++) {
                double angle = sector * Math.PI * 2 / SECTORS;
                directions[1 + ring * SECTORS + sector] = frame.upAxis().multiply(chart.radiusMeters())
                        .add(frame.xAxis().multiply(Math.cos(angle) * radius))
                        .add(frame.zAxis().multiply(Math.sin(angle) * radius)).normalized();
            }
        }
        int[] triangles = new int[(SECTORS + (RINGS - 1) * SECTORS * 2) * 3];
        int offset = 0;
        for (int sector = 0; sector < SECTORS; sector++) {
            triangles[offset++] = 0;
            triangles[offset++] = 1 + (sector + 1) % SECTORS;
            triangles[offset++] = 1 + sector;
        }
        for (int ring = 1; ring < RINGS; ring++) {
            for (int sector = 0; sector < SECTORS; sector++) {
                int next = (sector + 1) % SECTORS;
                int a = 1 + (ring - 1) * SECTORS + sector, b = 1 + (ring - 1) * SECTORS + next;
                int c = 1 + ring * SECTORS + sector, d = 1 + ring * SECTORS + next;
                triangles[offset++] = a; triangles[offset++] = b; triangles[offset++] = c;
                triangles[offset++] = b; triangles[offset++] = d; triangles[offset++] = c;
            }
        }
        ContinentalTerrain.Sample[] observations = null;
        if (terrain != null && terrain.version() >= 3) {
            var refined = ShorelineRefinement.refine(directions, triangles, terrain, cancelled);
            directions = refined.directions(); triangles = refined.triangles(); observations = refined.samples();
            count = directions.length;
        }
        float[] positions = new float[count * 3], colors = new float[count * 3], normals = new float[count * 3];
        float[] weights = new float[count];
        boolean[] liquid = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (i % SECTORS == 0 && cancelled.getAsBoolean()) { throw new CancellationException("Distant surface retired"); }
            var direction = directions[i];
            double arc = chart.radiusMeters() * Math.atan2(PlanetaryFrame.cross(direction, frame.upAxis()).length(),
                    direction.dot(frame.upAxis()));
            weights[i] = (float) joinAmount(arc / projectionScale(chart));
            if (terrain != null) {
                var sample = observations == null ? terrain.sample(direction) : observations[i];
                sampleVertex(i, direction, chart, centerX, centerZ, frame, sample, palette, positions, colors, liquid);
            } else {
                var sample = solidTerrain.sample(direction);
                double elevation = sample.water() ? Math.floor(sample.topMeters()) - 1.0 / 9.0 : Math.floor(sample.heightMeters());
                put(positions, i, project(chart, centerX, centerZ, frame, direction, elevation));
                put(colors, i, planetPalette.color(sample));
                liquid[i] = sample.water();
            }
        }
        for (int i = 0; i < triangles.length; i += 3) {
            int a = triangles[i], b = triangles[i + 1], c = triangles[i + 2];
            SpaceVector normal = PlanetaryFrame.cross(vector(positions, b).subtract(vector(positions, a)),
                    vector(positions, c).subtract(vector(positions, a)));
            addNormal(normals, a, normal); addNormal(normals, b, normal); addNormal(normals, c, normal);
        }
        for (int i = 0; i < count; i++) { put(normals, i, vector(normals, i).normalized()); }
        return new ContinentalLandscape(chart, centerX, centerZ, frame, positions, colors, normals, weights, liquid, triangles);
    }

    /** Strictly increasing ring radius; shared angular vertices make every ring seam watertight. */
    public static double ringRadius(int ring) {
        if (ring < 0 || ring >= RINGS) { throw new IllegalArgumentException("Invalid landscape ring"); }
        return FIRST_RADIUS_METERS * Math.pow(LAST_RADIUS_METERS / FIRST_RADIUS_METERS, ring / (RINGS - 1.0));
    }

    /**
     * Camera-relative presentation point at sea-level anchor. Inside 512 m, retain the host's flat chart;
     * outside 32.768 km, use the exact physical tangent projection. Smoothly joins the two in between.
     * Smaller bodies scale both intervals by radius/EarthRadius; Earth keeps its historical join.
     * This affects neither stored heights nor collision. Direction must be a unit body-fixed vector.
     */
    public static SpaceVector project(CubeStorageChart chart, double centerX, double centerZ, PlanetaryFrame frame,
            SpaceVector direction, double altitudeMeters) {
        if (chart == null || frame == null || direction == null || !Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || Math.abs(direction.length() - 1) > 1e-9
                || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Landscape projection requires finite geographic data");
        }
        double radius = chart.radiusMeters();
        SpaceVector point = direction.multiply(radius + altitudeMeters);
        SpaceVector physical = frame.toLocalPoint(point);
        double arc = radius * Math.atan2(PlanetaryFrame.cross(direction, frame.upAxis()).length(),
                direction.dot(frame.upAxis()));
        double amount = joinAmount(arc / projectionScale(chart));
        double forward = direction.dot(chart.face().outward());
        if (amount >= 1 || forward <= .1) { return physical; }
        SpaceVector flat = new SpaceVector(direction.dot(chart.face().u()) * radius / forward - centerX,
                altitudeMeters, direction.dot(chart.face().v()) * radius / forward - centerZ);
        return flat.multiply(1 - amount).add(physical.multiply(amount));
    }

    private static double projectionScale(CubeStorageChart chart) {
        return Math.min(1, chart.radiusMeters() / EarthChart.RADIUS_METERS);
    }

    private static double joinAmount(double arc) {
        // A cube-corner chart stretches radial distances by up to three. A linear-radius blend can
        // reverse that distance inside the annulus, folding triangles and producing a false horizon.
        // Spreading the transition in log radius bounds d(weight)/d(log radius) below 0.361.
        double amount = Math.clamp(Math.log(Math.max(arc, JOIN_START_METERS) / JOIN_START_METERS)
                / Math.log(JOIN_END_METERS / JOIN_START_METERS), 0, 1);
        return amount * amount * (3 - 2 * amount);
    }

    private static void sampleVertex(int index, SpaceVector direction, CubeStorageChart chart, double centerX,
            double centerZ, PlanetaryFrame frame, ContinentalTerrain.Sample sample, EarthSurfacePalette palette,
            float[] positions, float[] colors, boolean[] liquid) {
        boolean water = sample.water();
        // The generator's first-air coordinate is the top of the last solid block, not its block index.
        double height = water ? Math.floor(sample.waterMeters()) - 1.0 / 9.0 : Math.floor(sample.heightMeters());
        put(positions, index, project(chart, centerX, centerZ, frame, direction, height));
        liquid[index] = EarthSurfacePalette.liquid(sample);
        put(colors, index, palette.color(sample));
    }

    private static void addNormal(float[] data, int index, SpaceVector value) {
        data[index * 3] += (float) value.x(); data[index * 3 + 1] += (float) value.y(); data[index * 3 + 2] += (float) value.z();
    }

    private static void put(float[] data, int index, SpaceVector value) {
        data[index * 3] = (float) value.x(); data[index * 3 + 1] = (float) value.y(); data[index * 3 + 2] = (float) value.z();
    }

    private static SpaceVector vector(float[] data, int index) {
        return new SpaceVector(data[index * 3], data[index * 3 + 1], data[index * 3 + 2]);
    }

    /** Saved source geography for this derived mesh. */
    public CubeStorageChart chart() { return chart; }
    /** Anchor host chart X in meters. */
    public double centerX() { return centerX; }
    /** Anchor host chart Z in meters. */
    public double centerZ() { return centerZ; }
    /** Body-fixed sea-level frame of the retained float positions. */
    public PlanetaryFrame frame() { return frame; }
    /** Number of immutable vertices. */
    public int vertexCount() { return positions.length / 3; }
    /** Number of triangle indices, divisible by three. */
    public int indexCount() { return triangles.length; }
    /** Vertex index for a triangle corner; array bounds are checked. */
    public int vertexIndex(int index) { return triangles[index]; }
    /** Immutable anchor-relative presentation position in meters. */
    public SpaceVector position(int index) { return vector(positions, index); }
    /** Unlit display material color, each channel in [0,1]. */
    public SpaceVector color(int index) { return vector(colors, index); }
    /** Far-projection weight for rebasing the near flat mesh without a moving-origin offset. */
    public float joinWeight(int index) { return joinWeights[index]; }
    /** True for liquid water; ice must not inherit reflection or blue-water shading. */
    public boolean liquid(int index) { return liquid[index]; }
    /** Unit smoothed presentation normal. */
    public SpaceVector normal(int index) { return vector(normals, index); }
}
