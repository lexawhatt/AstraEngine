package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.EarthBoundarySection;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import java.util.ArrayList;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/** Bounded server-to-client read-only neighboring block observations. Never accepts a client-selected world. */
public record EarthBoundaryPayload(EarthBoundarySnapshot snapshot) implements CustomPacketPayload {
    public static final int MAX_BYTES = 560 * 1024;
    public static final Type<EarthBoundaryPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:earth_boundary"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EarthBoundaryPayload> CODEC = StreamCodec.ofMember(
            EarthBoundaryPayload::write, EarthBoundaryPayload::read);

    public EarthBoundaryPayload {
        if (snapshot == null) { throw new IllegalArgumentException("Earth boundary snapshot is required"); }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeLong(snapshot.revision()); writeChart(buffer, snapshot.source());
        buffer.writeDouble(snapshot.anchorFeet().x()); buffer.writeDouble(snapshot.anchorFeet().y());
        buffer.writeDouble(snapshot.anchorFeet().z()); buffer.writeBoolean(snapshot.complete());
        buffer.writeVarInt(snapshot.sections().size());
        for (EarthBoundarySection section : snapshot.sections()) {
            writeChart(buffer, section.chart()); buffer.writeLong(section.section().asLong());
            for (int i = 0; i < EarthBoundarySection.CELL_COUNT; i++) { buffer.writeVarInt(section.state(i)); }
            for (int i = 0; i < EarthBoundarySection.CELL_COUNT; i++) { buffer.writeByte(section.light(i)); }
            for (int i = 0; i < EarthBoundarySection.BIOME_COUNT; i++) { buffer.writeVarInt(section.biome(i)); }
            for (int i = 0; i < EarthBoundarySection.VISUAL_WORD_COUNT; i++) { buffer.writeLong(section.visualWord(i)); }
        }
        if (buffer.writerIndex() - start > MAX_BYTES) { throw new IllegalArgumentException("Earth boundary payload exceeds its wire budget"); }
    }

    private static EarthBoundaryPayload read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_BYTES) { throw new IllegalArgumentException("Earth boundary payload exceeds its wire budget"); }
        long revision = buffer.readLong(); CubeStorageChart source = readChart(buffer);
        var anchor = new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        boolean complete = buffer.readBoolean(); int count = buffer.readVarInt();
        if (count < 0 || count > EarthBoundarySnapshot.MAX_SECTIONS || revision < 1 || !source.contains(anchor)) {
            throw new IllegalArgumentException("Invalid Earth boundary header");
        }
        var biomes = buffer.registryAccess().registryOrThrow(Registries.BIOME);
        var sections = new ArrayList<EarthBoundarySection>(count);
        for (int sectionIndex = 0; sectionIndex < count; sectionIndex++) {
            CubeStorageChart chart = readChart(buffer); var position = SectionPos.of(buffer.readLong());
            int[] states = new int[EarthBoundarySection.CELL_COUNT]; byte[] light = new byte[states.length];
            int[] biomeIds = new int[EarthBoundarySection.BIOME_COUNT];
            for (int i = 0; i < states.length; i++) {
                states[i] = readId(buffer);
                if (Block.BLOCK_STATE_REGISTRY.byId(states[i]) == null) { throw new IllegalArgumentException("Unknown boundary block state"); }
            }
            buffer.readBytes(light);
            for (int i = 0; i < biomeIds.length; i++) {
                biomeIds[i] = readId(buffer);
                if (biomes.byId(biomeIds[i]) == null) { throw new IllegalArgumentException("Unknown boundary biome"); }
            }
            long[] visual = new long[EarthBoundarySection.VISUAL_WORD_COUNT];
            for (int i = 0; i < visual.length; i++) { visual[i] = buffer.readLong(); }
            sections.add(new EarthBoundarySection(chart, position, states, light, biomeIds, visual));
        }
        return new EarthBoundaryPayload(new EarthBoundarySnapshot(revision, source, anchor, complete, sections));
    }

    private static int readId(RegistryFriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        if (id < 0 || id > EarthBoundarySection.MAX_REGISTRY_ID) { throw new IllegalArgumentException("Invalid boundary registry ID"); }
        return id;
    }

    private static void writeChart(RegistryFriendlyByteBuf buffer, CubeStorageChart chart) {
        CubeStorageCharts.write(buffer, chart);
    }

    private static CubeStorageChart readChart(RegistryFriendlyByteBuf buffer) {
        return CubeStorageCharts.read(buffer);
    }

    @Override public Type<EarthBoundaryPayload> type() { return TYPE; }
}
