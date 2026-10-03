package dev.lexawhatt.astraengine.surface.orbit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable 256-chunk derived page. Updating one chunk replaces its contribution, including removed lights/buildings. */
public record OrbitalPage(Key key, Map<Integer, OrbitalPatch> chunks) {
    public OrbitalPage {
        if (key == null || chunks == null || chunks.size() > 256) {
            throw new IllegalArgumentException("Orbital page requires bounded chunk data");
        }
        chunks.forEach((slot, patch) -> {
            if (slot == null || slot < 0 || slot > 255 || patch == null || patch.level() != 0
                    || !Key.of(patch).equals(key) || slot != slot(patch.x(), patch.z())) {
                throw new IllegalArgumentException("Orbital page contains a foreign chunk");
            }
        });
        chunks = Map.copyOf(chunks);
    }

    /** Replaces one observed chunk without retaining any mutable world data. */
    public OrbitalPage with(OrbitalPatch patch) {
        if (!key.equals(Key.of(patch))) { throw new IllegalArgumentException("Chunk belongs to another orbital page"); }
        Map<Integer, OrbitalPatch> updated = new HashMap<>(chunks);
        updated.put(slot(patch.x(), patch.z()), patch);
        return new OrbitalPage(key, updated);
    }

    /** Aggregates actual observations only; coverage prevents a sparse edited area coloring an entire page. */
    public OrbitalPatch coarse(long revision) {
        return coarse(revision, new java.util.BitSet(256));
    }

    /**
     * Aggregates only observations outside the pending revalidation mask. An unloaded dirty neighbor cannot hide
     * other, valid settlements in this page, and its old height or emission cannot leak into their aggregate.
     */
    public OrbitalPatch coarse(long revision, java.util.BitSet excludedChunks) {
        if (excludedChunks == null || excludedChunks.length() > 256) {
            throw new IllegalArgumentException("Orbital exclusion mask exceeds one page");
        }
        double[][] sums = new double[16][6];
        for (var entry : chunks.entrySet()) {
            if (excludedChunks.get(entry.getKey())) { continue; }
            OrbitalPatch patch = entry.getValue();
            int localX = Math.floorMod(patch.x(), 16), localZ = Math.floorMod(patch.z(), 16);
            int cell = localZ / 4 * 4 + localX / 4;
            for (var value : patch.cells()) {
                double weight = value.coverage();
                sums[cell][0] += value.altitudeMeters() * weight;
                sums[cell][1] += ((value.rgb() >>> 16) & 255) * weight;
                sums[cell][2] += ((value.rgb() >>> 8) & 255) * weight;
                sums[cell][3] += (value.rgb() & 255) * weight;
                sums[cell][4] += value.emission() * weight;
                sums[cell][5] += weight;
            }
        }
        List<OrbitalPatch.Cell> result = new ArrayList<>(16);
        for (double[] sum : sums) {
            double weight = sum[5];
            result.add(weight == 0 ? OrbitalPatch.Cell.EMPTY : new OrbitalPatch.Cell((float) (sum[0] / weight),
                    (int) Math.round(sum[1] / weight) << 16 | (int) Math.round(sum[2] / weight) << 8
                            | (int) Math.round(sum[3] / weight),
                    (float) (sum[4] / weight), (float) (weight / 256)));
        }
        return new OrbitalPatch(key.surface(), key.x(), key.z(), 4, revision, result);
    }

    /** Local chunk slot, with floor semantics at negative chart coordinates. */
    public static int slot(int x, int z) { return Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16); }

    /** Stable identity in the canonical dimension and page grid, never a runtime allocation slot. */
    public record Key(OrbitalSurface surface, int x, int z) {
        public Key {
            if (surface == null || Math.abs((long) x) > 125_000 || Math.abs((long) z) > 125_000) {
                throw new IllegalArgumentException("Invalid orbital page key");
            }
        }
        public static Key of(OrbitalPatch patch) {
            return new Key(patch.surface(), Math.floorDiv(patch.x(), 16), Math.floorDiv(patch.z(), 16));
        }
        public String identity() { return surface.dimensionId() + "/" + x + "/" + z; }
    }
}
