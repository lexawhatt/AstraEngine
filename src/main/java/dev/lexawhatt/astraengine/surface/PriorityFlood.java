package dev.lexawhatt.astraengine.surface;

import java.util.Arrays;
import java.util.PriorityQueue;

/** Ocean-seeded Priority-Flood flow routing, after Barnes, Lehman and Mulla (2014), doi:10.1016/j.cageo.2013.04.024. */
final class PriorityFlood {
    private PriorityFlood() { }

    record Routing(int[] downstream, int[] order) { }
    private record Cell(int index, double spill) implements Comparable<Cell> {
        @Override public int compareTo(Cell other) {
            int height = Double.compare(spill, other.spill);
            return height == 0 ? Integer.compare(index, other.index) : height;
        }
    }

    /** Caller-owned arrays, eight neighbors per cell. Returns a forest whose roots are ocean cells. */
    static Routing route(double[] elevation, int[] neighbors) {
        int size = elevation.length;
        if (size == 0 || neighbors.length != size * 8) { throw new IllegalArgumentException("Invalid drainage graph"); }
        int[] parent = new int[size], order = new int[size];
        Arrays.fill(parent, -2);
        var queue = new PriorityQueue<Cell>();
        for (int i = 0; i < size; i++) {
            if (!Double.isFinite(elevation[i])) { throw new IllegalArgumentException("Nonfinite drainage elevation"); }
            if (elevation[i] <= 0) { parent[i] = -1; queue.add(new Cell(i, 0)); }
        }
        if (queue.isEmpty()) { throw new IllegalArgumentException("Drainage requires an ocean outlet"); }
        int visited = 0;
        while (!queue.isEmpty()) {
            Cell current = queue.remove();
            order[visited++] = current.index();
            for (int k = 0; k < 8; k++) {
                int next = neighbors[current.index() * 8 + k];
                if (next < 0 || next >= size) { throw new IllegalArgumentException("Drainage neighbor outside graph"); }
                if (parent[next] != -2) { continue; }
                parent[next] = current.index();
                queue.add(new Cell(next, Math.max(current.spill(), elevation[next])));
            }
        }
        if (visited != size) { throw new IllegalArgumentException("Drainage graph has a disconnected component"); }
        return new Routing(parent, order);
    }
}
