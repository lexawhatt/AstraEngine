package dev.lexawhatt.astraengine.verification;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import dev.lexawhatt.astraengine.compat.distant.EarthLodGenerator;
import dev.lexawhatt.astraengine.compat.distant.EmptyFlightLodGenerator;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;

/** Equal-data alternating worker requests; queue-inclusive latency, not a renderer/FPS benchmark. */
final class RiverLodBenchmark {
    private RiverLodBenchmark() { }

    static String run(IDhApiLevelWrapper wrapper, IDhApiWorldGenerator registered, IDhApiLevelWrapper flight) {
        var terrain = (EarthChunkGenerator) ((ServerLevel) wrapper.getWrappedMcObject()).getChunkSource().getGenerator();
        var baseline = new BaselineEarthLodGenerator(wrapper, terrain);
        var optimized = new EarthLodGenerator(wrapper, terrain);
        StringBuilder report = new StringBuilder("Same v3 field / DH 3.3.3 / supplied executor / pooled 64x64 columns\n");
        require(registered instanceof EarthLodGenerator, "Actual Earth level lacks the direct override");
        for (byte detail : new byte[] {0, 1, 4, 12}) {
            List<Double> before = new ArrayList<>(), after = new ArrayList<>();
            List<Double> beforeWork = new ArrayList<>(), afterWork = new ArrayList<>();
            for (int trial = -6; trial < 30; trial++) {
                long pos = DhSectionPos.encode((byte) (6 + detail), detail == 12 ? -1 : -23, detail == 12 ? 2 : 47);
                try (var oldData = FullDataSourceV2.createEmpty(pos); var newData = FullDataSourceV2.createEmpty(pos)) {
                    double oldTime, newTime;
                    long oldNanos = baseline.metrics().workerNanos(), newNanos = optimized.metrics().workerNanos();
                    if ((trial & 1) == 0) {
                        oldTime = request(baseline, oldData, detail); newTime = request(optimized, newData, detail);
                    } else {
                        newTime = request(optimized, newData, detail); oldTime = request(baseline, oldData, detail);
                    }
                    if (trial == 0) {
                        for (int z = 0; z < 64; z++) {
                            for (int x = 0; x < 64; x++) {
                                var a = oldData.getApiDataPointColumn(x, z); var b = newData.getApiDataPointColumn(x, z);
                                require(a.size() == b.size(), "LOD run count changed");
                                for (int i = 0; i < a.size(); i++) {
                                    var first = a.get(i); var second = b.get(i);
                                    require(first.bottomYBlockPos == second.bottomYBlockPos && first.topYBlockPos == second.topYBlockPos
                                            && first.blockStateWrapper.equals(second.blockStateWrapper)
                                            && first.biomeWrapper.equals(second.biomeWrapper)
                                            && first.skyLightLevel == second.skyLightLevel, "Cached LOD changed actual data");
                                }
                            }
                        }
                    }
                    if (trial >= 0) {
                        before.add(oldTime); after.add(newTime);
                        beforeWork.add((baseline.metrics().workerNanos() - oldNanos) / 1e6);
                        afterWork.add((optimized.metrics().workerNanos() - newNanos) / 1e6);
                    }
                }
            }
            report.append("detail=").append(detail).append(" baseline ").append(summary(before))
                    .append(" optimized ").append(summary(after)).append('\n')
                    .append("worker elapsed baseline ").append(summary(beforeWork)).append(" optimized ")
                    .append(summary(afterWork)).append('\n');
        }
        dev.lexawhatt.astraengine.AstraEngine.LOGGER.info("ASTRA_VERIFY_LOD_TIMING {}", report);
        var atlas = terrain.terrain().rivers().orElseThrow();
        int riverX = 0, riverZ = 0; boolean foundRiver = false;
        for (int cell = 0; cell < atlas.cellCount(); cell++) {
            if (!atlas.river(cell) || atlas.waterMeters(cell) < 100 || atlas.waterMeters(cell) > 1000) { continue; }
            var normal = atlas.point(cell, .5);
            if (dev.lexawhatt.astraengine.surface.CubeFace.containing(normal) != terrain.chart().face()) { continue; }
            double scale = dev.lexawhatt.astraengine.surface.EarthChart.RADIUS_METERS / normal.dot(terrain.chart().face().outward());
            riverX = (int) Math.floor(normal.dot(terrain.chart().face().u()) * scale);
            riverZ = (int) Math.floor(normal.dot(terrain.chart().face().v()) * scale);
            foundRiver = true; break;
        }
        require(foundRiver, "Missing direct river LOD site");
        int tileX = Math.floorDiv(riverX, 64), tileZ = Math.floorDiv(riverZ, 64);
        try (var data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, tileX, tileZ))) {
            data.setRunApiSetterValidation(true);
            optimized.generateLod(tileX * 4, tileZ * 4, tileX, tileZ, (byte) 0, data,
                    EDhApiDistantGeneratorMode.INTERNAL_SERVER, ThreadPoolUtil.getWorldGenExecutor(), value -> {}).join();
            var column = data.getApiDataPointColumn(Math.floorMod(riverX, 64), Math.floorMod(riverZ, 64));
            var sample = terrain.terrain().sample(terrain.chart().normal(riverX + .5, riverZ + .5));
            var water = column.stream().filter(value -> net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                    .equals(value.blockStateWrapper.getWrappedMcObject())).findFirst().orElseThrow();
            require(water.topYBlockPos + terrain.getMinY() == (int) Math.floor(sample.waterMeters()),
                    "DH lost the river's raised water level");
            report.append("Validated river LOD at ").append(riverX).append(',').append(riverZ)
                    .append(" waterMeters=").append(sample.waterMeters()).append('\n');
        }
        report.append("optimized ").append(optimized.metrics()).append('\n');
        require(flight != null, "No flight DH wrapper");
        var empty = new EmptyFlightLodGenerator(flight);
        try (var data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 18, 0, 0))) {
            data.setRunApiSetterValidation(true);
            empty.generateLod(0, 0, 0, 0, (byte) 12, data, EDhApiDistantGeneratorMode.INTERNAL_SERVER,
                    ThreadPoolUtil.getWorldGenExecutor(), value -> require(value == data, "Replaced empty pooled data")).join();
            for (int z = 0; z < 64; z++) {
                for (int x = 0; x < 64; x++) {
                    var column = data.getApiDataPointColumn(x, z);
                    require(column.size() == 1 && column.getFirst().blockStateWrapper.isAir(), "Flight override generated terrain");
                }
            }
        }
        report.append("Empty flight validated at 4096-block spacing; no Minecraft chunks requested.\n");
        return report.toString();
    }

    private static double request(IDhApiWorldGenerator generator, FullDataSourceV2 data, byte detail) {
        long started = System.nanoTime();
        generator.generateLod((detail == 12 ? -1 : -23) * (4 << detail), (detail == 12 ? 2 : 47) * (4 << detail),
                detail == 12 ? -1 : -23, detail == 12 ? 2 : 47, detail, data,
                EDhApiDistantGeneratorMode.INTERNAL_SERVER, ThreadPoolUtil.getWorldGenExecutor(), value -> {
                    require(value == data, "Replaced pooled data");
                }).join();
        return (System.nanoTime() - started) / 1e6;
    }

    private static String summary(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return String.format(Locale.ROOT, "n=%d mean=%.3fms p50=%.3fms p95=%.3fms", sorted.size(),
                values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                sorted.get(sorted.size() / 2), sorted.get((int) ((sorted.size() - 1) * .95)));
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalStateException(message); } }
}
