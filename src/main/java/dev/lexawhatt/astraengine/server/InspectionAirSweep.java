package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** A no-load proof over actual canonical section palettes, including player edits. Server thread only. */
public final class InspectionAirSweep {
    private InspectionAirSweep() {}

    /**
     * True only when every intersected section of the bounded swept volume is already FULL and entirely air.
     * This never treats a procedural height estimate or an unloaded chunk as empty. The caller must still
     * use host movement for entity collisions, world borders and movement callbacks.
     */
    public static boolean clear(ServerLevel level, CubeStorageChart chart, AABB sweep) {
        if (level == null || chart == null || sweep == null) {
            throw new IllegalArgumentException("Air sweep requires a level, chart and swept volume");
        }
        if (!level.getServer().isSameThread()) { throw new IllegalStateException("Air sweep requires the server thread"); }
        if (sweep.getXsize() > 132 || sweep.getZsize() > 132 || sweep.getYsize() > 2564
                || sweep.minX < -chart.radiusMeters() || sweep.maxX >= chart.radiusMeters()
                || sweep.minZ < -chart.radiusMeters() || sweep.maxZ >= chart.radiusMeters()
                || sweep.minY < chart.minY() || sweep.maxY >= chart.minY() + chart.height()) { return false; }
        int firstY = level.getSectionIndex((int) Math.floor(sweep.minY));
        int lastY = level.getSectionIndex((int) Math.floor(sweep.maxY));
        for (int x = (int) Math.floor(sweep.minX) >> 4; x <= (int) Math.floor(sweep.maxX) >> 4; x++) {
            for (int z = (int) Math.floor(sweep.minZ) >> 4; z <= (int) Math.floor(sweep.maxZ) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) { return false; }
                for (int y = firstY; y <= lastY; y++) {
                    if (!chunk.getSection(y).hasOnlyAir()) { return false; }
                }
            }
        }
        return true;
    }
}
