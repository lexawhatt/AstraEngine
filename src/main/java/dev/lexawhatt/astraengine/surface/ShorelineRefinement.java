package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded conforming edge bisection at wet/dry transitions; neighbors share every inserted vertex. */
final class ShorelineRefinement {
    private static final int MAX_VERTICES = 60_000;
    record Mesh(SpaceVector[] directions, ContinentalTerrain.Sample[] samples, int[] triangles) { }
    private ShorelineRefinement() { }

    static Mesh refine(SpaceVector[] source, int[] indices, ContinentalTerrain terrain, BooleanSupplier cancelled) {
        var directions = new ArrayList<>(Arrays.asList(source));
        var samples = new ArrayList<ContinentalTerrain.Sample>(source.length);
        for (int i = 0; i < source.length; i++) {
            if (i % 256 == 0 && cancelled.getAsBoolean()) { throw new CancellationException("Shoreline sampling retired"); }
            samples.add(terrain.sample(source[i]));
        }
        for (int pass = 0; pass < 4; pass++) {
            if (cancelled.getAsBoolean()) { throw new CancellationException("Shoreline refinement retired"); }
            var midpoints = new HashMap<Long, Integer>();
            for (int i = 0; i < indices.length; i += 3) {
                for (int side = 0; side < 3; side++) {
                    int a = indices[i + side], b = indices[i + (side + 1) % 3];
                    if (samples.get(a).water() == samples.get(b).water() || midpoints.containsKey(edge(a, b))
                            || directions.size() >= MAX_VERTICES) { continue; }
                    double meters = directions.get(a).distance(directions.get(b)) * ContinentalTerrain.RADIUS_METERS;
                    if (meters < 16 || meters > 250_000) { continue; }
                    var middle = directions.get(a).add(directions.get(b)).normalized();
                    midpoints.put(edge(a, b), directions.size());
                    directions.add(middle); samples.add(terrain.sample(middle));
                }
            }
            if (midpoints.isEmpty()) { break; }
            var result = new ArrayList<Integer>(indices.length + midpoints.size() * 6);
            for (int i = 0; i < indices.length; i += 3) {
                int a = indices[i], b = indices[i + 1], c = indices[i + 2];
                int ab = midpoints.getOrDefault(edge(a, b), -1), bc = midpoints.getOrDefault(edge(b, c), -1);
                int ca = midpoints.getOrDefault(edge(c, a), -1);
                if (ab >= 0 && bc >= 0 && ca >= 0) {
                    add(result, a, ab, ca); add(result, ab, b, bc); add(result, ca, bc, c); add(result, ab, bc, ca);
                } else if (ab >= 0 && ca >= 0) { two(result, a, b, c, ab, ca); }
                else if (ab >= 0 && bc >= 0) { two(result, b, c, a, bc, ab); }
                else if (bc >= 0 && ca >= 0) { two(result, c, a, b, ca, bc); }
                else if (ab >= 0) { one(result, a, b, c, ab); }
                else if (bc >= 0) { one(result, b, c, a, bc); }
                else if (ca >= 0) { one(result, c, a, b, ca); }
                else { add(result, a, b, c); }
            }
            indices = result.stream().mapToInt(Integer::intValue).toArray();
        }
        return new Mesh(directions.toArray(SpaceVector[]::new), samples.toArray(ContinentalTerrain.Sample[]::new), indices);
    }

    private static long edge(int a, int b) { return (long) Math.min(a, b) << 32 | Math.max(a, b); }
    private static void add(ArrayList<Integer> result, int a, int b, int c) { result.add(a); result.add(b); result.add(c); }
    private static void one(ArrayList<Integer> result, int a, int b, int c, int ab) {
        add(result, a, ab, c); add(result, ab, b, c);
    }
    private static void two(ArrayList<Integer> result, int a, int b, int c, int ab, int ca) {
        add(result, a, ab, ca); add(result, ab, b, c); add(result, ab, c, ca);
    }
}
