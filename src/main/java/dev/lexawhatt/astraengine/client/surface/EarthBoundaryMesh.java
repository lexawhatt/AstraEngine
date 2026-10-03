package dev.lexawhatt.astraengine.client.surface;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.RenderShape;
import net.neoforged.neoforge.client.model.data.ModelData;

/** One bounded worker's ordinary baked block and fluid geometry, with section-local floats only. */
final class EarthBoundaryMesh implements AutoCloseable {
    private static final int MAX_VERTICES = 1_048_576;
    private int vertexCount;
    final EarthBoundarySnapshot snapshot;
    final List<Part> parts = new ArrayList<>();
    private final List<ByteBufferBuilder> storage = new ArrayList<>();

    private EarthBoundaryMesh(EarthBoundarySnapshot snapshot) { this.snapshot = snapshot; }

    static EarthBoundaryMesh bake(EarthBoundarySnapshot snapshot, BlockRenderDispatcher models, BoundaryChestModels chests,
            Map<Integer, Biome> biomes, float[] shades, BooleanSupplier cancelled) {
        var result = new EarthBoundaryMesh(snapshot);
        try {
            for (EarthBoundarySection section : snapshot.sections()) {
                if (cancelled.getAsBoolean()) { throw new CancellationException("Retired Earth boundary mesh"); }
                var region = new EarthBoundaryRegion(section.chart(), snapshot, biomes, shades);
                var builders = new LinkedHashMap<RenderType, Layer>();
                var chestBuilders = new LinkedHashMap<RenderType, Layer>();
                var pose = new PoseStack(); var random = RandomSource.create(0); var block = new BlockPos.MutableBlockPos();
                var origin = section.section().origin();
                for (int y = 0; y < 16; y++) {
                    if (cancelled.getAsBoolean()) { throw new CancellationException("Retired Earth boundary mesh"); }
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            block.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                            if (!section.chart().contains(new SpaceVector(block.getX() + .5, block.getY() + .5, block.getZ() + .5))) { continue; }
                            var state = region.getBlockState(block);
                            if (!state.getFluidState().isEmpty()) {
                                var layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
                                models.renderLiquid(block, region, result.builder(builders, layer), state, state.getFluidState());
                            }
                            if (BoundaryChestModels.supported(state)) {
                                pose.pushPose(); pose.translate(x, y, z);
                                chests.render(state, section.containerOpen((y * 16 + z) * 16 + x), pose,
                                        result.builder(chestBuilders, RenderType.solid()),
                                        LevelRenderer.getLightColor(region, state, block));
                                pose.popPose();
                            }
                            if (state.getRenderShape() != RenderShape.MODEL) { continue; }
                            var model = models.getBlockModel(state);
                            var data = model.getModelData(region, block, state, ModelData.EMPTY);
                            random.setSeed(state.getSeed(block));
                            for (var layer : model.getRenderTypes(state, random, data)) {
                                pose.pushPose(); pose.translate(x, y, z);
                                models.renderBatched(state, block, region, pose, result.builder(builders, layer), true, random, data, layer);
                                pose.popPose();
                            }
                        }
                    }
                }
                for (var layer : builders.entrySet()) {
                    MeshData data = layer.getValue().builder.build();
                    if (data != null) {
                        result.parts.add(new Part(section, layer.getKey() == RenderType.translucent(), TextureAtlas.LOCATION_BLOCKS, data));
                    }
                }
                for (var layer : chestBuilders.values()) {
                    MeshData data = layer.builder.build();
                    if (data != null) { result.parts.add(new Part(section, false, Sheets.CHEST_SHEET, data)); }
                }
            }
            return result;
        } catch (RuntimeException | Error failure) { result.close(); throw failure; }
    }

    private VertexConsumer builder(Map<RenderType, Layer> builders, RenderType layer) {
        return builders.computeIfAbsent(layer, ignored -> {
            var bytes = new ByteBufferBuilder(65536); storage.add(bytes);
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            return new Layer(builder, new BoundedConsumer(builder));
        }).consumer;
    }

    /** Enforces the aggregate bound before a model can expand another vertex in native storage. */
    private final class BoundedConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private BoundedConsumer(VertexConsumer delegate) { this.delegate = delegate; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (++vertexCount > MAX_VERTICES) { throw new IllegalStateException("Earth boundary mesh exceeds its vertex budget"); }
            delegate.addVertex(x, y, z); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }
    private record Layer(BufferBuilder builder, VertexConsumer consumer) {}

    @Override public void close() {
        for (Part part : parts) { part.data.close(); }
        for (var bytes : storage) { bytes.close(); }
        parts.clear(); storage.clear();
    }
    record Part(EarthBoundarySection section, boolean translucent, ResourceLocation atlas, MeshData data) {}
}
