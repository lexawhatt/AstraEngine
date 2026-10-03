package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChartTransform;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Immutable neighboring model input. Workers read copied cells and immutable biome values, never a live level. */
final class EarthBoundaryRegion implements BlockAndTintGetter {
    private final CubeStorageChart chart;
    private final Map<Key, EarthBoundarySection> sections;
    private final Map<Integer, Biome> biomes;
    private final float[] shades;
    private final LevelLightEngine light;

    EarthBoundaryRegion(CubeStorageChart chart, EarthBoundarySnapshot snapshot, Map<Integer, Biome> biomes, float[] shades) {
        this.chart = chart; this.biomes = Map.copyOf(biomes); this.shades = shades.clone();
        var values = new HashMap<Key, EarthBoundarySection>();
        for (var section : snapshot.sections()) { values.put(new Key(section.chart(), section.section().asLong()), section); }
        sections = Map.copyOf(values);
        light = new SnapshotLight();
    }

    private Cell cell(BlockPos position) {
        CubeStorageChart owner = chart;
        BlockPos point = position;
        var center = new SpaceVector(position.getX() + .5, position.getY() + .5, position.getZ() + .5);
        if (!chart.contains(center)) {
            int band = chart.band() + Math.floorDiv(position.getY() - chart.minY(), chart.height());
            owner = chart.chart(CubeFace.containing(chart.normal(center.x(), center.z())), band).orElse(null);
            if (owner == null) { return null; }
            var mapped = new EarthChartTransform(chart, owner).position(center);
            point = BlockPos.containing(mapped.x(), mapped.y(), mapped.z());
        }
        var section = sections.get(new Key(owner, SectionPos.asLong(point)));
        return section == null ? null : new Cell(section, point.getX() & 15, point.getY() & 15, point.getZ() & 15);
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        var cell = cell(pos);
        return cell == null ? Blocks.AIR.defaultBlockState() : Block.stateById(cell.section.state(cell.index()));
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return chart.height(); }
    @Override public int getMinBuildHeight() { return chart.minY(); }
    @Override public float getShade(Direction direction, boolean shade) { return shade ? shades[direction.ordinal()] : 1; }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        var cell = cell(pos);
        int light = cell == null ? 0 : cell.section.light(cell.index());
        return layer == LightLayer.SKY ? light >> 4 : light & 15;
    }
    @Override public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(getBrightness(LightLayer.BLOCK, pos), getBrightness(LightLayer.SKY, pos) - amount);
    }
    @Override public LevelLightEngine getLightEngine() {
        return light;
    }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        var cell = cell(pos);
        if (cell == null) { return 0xFFFFFF; }
        Biome biome = biomes.get(cell.section.biome(((cell.y >> 2) * 4 + (cell.z >> 2)) * 4 + (cell.x >> 2)));
        return biome == null ? 0xFFFFFF : resolver.getColor(biome, pos.getX(), pos.getZ());
    }

    /** Compatibility read surface for model code; no engines, propagation, live chunk access or mutable snapshot data. */
    private final class SnapshotLight extends LevelLightEngine {
        private final net.minecraft.world.level.lighting.LayerLightEventListener sky = new Layer(LightLayer.SKY);
        private final net.minecraft.world.level.lighting.LayerLightEventListener block = new Layer(LightLayer.BLOCK);
        SnapshotLight() {
            super(new net.minecraft.world.level.chunk.LightChunkGetter() {
                @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
                @Override public net.minecraft.world.level.BlockGetter getLevel() { return EarthBoundaryRegion.this; }
            }, false, false);
        }
        @Override public net.minecraft.world.level.lighting.LayerLightEventListener getLayerListener(LightLayer layer) {
            return layer == LightLayer.SKY ? sky : block;
        }
        @Override public int getRawBrightness(BlockPos point, int amount) { return EarthBoundaryRegion.this.getRawBrightness(point, amount); }
    }
    private final class Layer implements net.minecraft.world.level.lighting.LayerLightEventListener {
        private final LightLayer layer;
        Layer(LightLayer layer) { this.layer = layer; }
        @Override public int getLightValue(BlockPos point) { return getBrightness(layer, point); }
        @Override public net.minecraft.world.level.chunk.DataLayer getDataLayerData(SectionPos section) {
            if (!sections.containsKey(new Key(chart, section.asLong()))) { return null; }
            var result = new net.minecraft.world.level.chunk.DataLayer();
            var origin = section.origin();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) { result.set(x, y, z, getLightValue(origin.offset(x, y, z))); }
                }
            }
            return result;
        }
        @Override public void checkBlock(BlockPos point) { }
        @Override public boolean hasLightWork() { return false; }
        @Override public int runLightUpdates() { return 0; }
        @Override public void updateSectionStatus(SectionPos position, boolean empty) { }
        @Override public void setLightEnabled(net.minecraft.world.level.ChunkPos position, boolean enabled) { }
        @Override public void propagateLightSources(net.minecraft.world.level.ChunkPos position) { }
    }

    private record Key(CubeStorageChart chart, long section) {}
    private record Cell(EarthBoundarySection section, int x, int y, int z) {
        int index() { return (y * 16 + z) * 16 + x; }
    }
}
