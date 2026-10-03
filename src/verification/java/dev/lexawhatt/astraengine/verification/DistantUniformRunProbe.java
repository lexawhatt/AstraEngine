package dev.lexawhatt.astraengine.verification;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import dev.lexawhatt.astraengine.compat.distant.DistantUniformRun;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainStates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Compares the actual pinned DH converter with its ordinary fallback, including every emitted API event. */
final class DistantUniformRunProbe {
    private static final ChunkPos POSITION = new ChunkPos(1000001, -1000001);

    private DistantUniformRunProbe() { }

    static String verify(ServerLevel level) {
        require(level.getServer().isSameThread(), "Uniform-run probe requires the server thread");
        require(Arrays.stream(LodDataBuilder.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("astra$consumeUniformSection")),
                "Exact-version uniform-run hook was not applied to the actual DH converter");
        var report = new StringBuilder("DH actual converter uniform-run equality:\n");
        var world = ServerLevelWrapper_neoforge.getWrapper(level);
        var listener = new Events(world);
        ApiEventInjector.INSTANCE.bind(DhApiChunkProcessingEvent.class, listener);
        try {
            for (int variant = 0; variant < 5; variant++) {
                var chunk = chunk(level, variant);
                // A foreign wrapper intentionally takes the unchanged upstream path. This subclass overrides
                // only a read counter; no production switch or replacement converter is used for the oracle.
                var original = new Original(chunk, world);
                var adapted = new ChunkWrapper_neoforge(chunk, world);
                lighting(original, variant); lighting(adapted, variant);
                var stone = BlockStateWrapper_neoforge.fromBlockState(Blocks.STONE.defaultBlockState(), world);
                var biome = adapted.getBiome(0, -1000, 0);
                require(DistantUniformRun.bottom(original, 0, -1000, 0, EarthChart.MIN_Y, stone, biome, 0, 0) == -1000,
                        "Foreign wrappers must retain the original converter scan");
                require(DistantUniformRun.bottom(adapted, 0, -1000, 0, EarthChart.MIN_Y, stone, biome, 0, 0) == -1008,
                        "Actual unchanged singleton section did not permit the bounded run");
                listener.override = variant == 3;
                listener.reset();
                long start = System.nanoTime();
                try (var expected = LodDataBuilder.createFromChunk(world, original)) {
                    long originalNanos = System.nanoTime() - start;
                    listener.check();
                    var expectedEvents = List.copyOf(listener.events);
                    int expectedOverrides = listener.overrides;
                    listener.reset(); start = System.nanoTime();
                    try (var actual = LodDataBuilder.createFromChunk(world, adapted)) {
                        long adaptedNanos = System.nanoTime() - start;
                        listener.check();
                        equal(expected, actual, "variant " + variant);
                        require(listener.events.equals(expectedEvents), "Changed API event positions/overrides in variant " + variant);
                        require(listener.overrides == expectedOverrides && (variant != 3 || expectedOverrides > 0),
                                "Actual material and biome override was not observed equally in both converters");
                        require(original.reads > 500000, "Oracle did not scan the actual deep host chunk");
                        if (variant == 2) {
                            require(DistantUniformRun.bottom(adapted, 0, -1194, 0, EarthChart.MIN_Y,
                                    stone, adapted.getBiome(0, -1194, 0), 0, 0) == -1194,
                                    "A lit skipped cell must retain the original scan");
                        }
                        if (variant == 4) {
                            require(DistantUniformRun.bottom(adapted, 0, -1100, 0, EarthChart.MIN_Y,
                                    stone, adapted.getBiome(0, -1100, 0), 0, 0) == -1100,
                                    "Changed-then-restored palette must retain the original scan");
                        }
                        report.append("variant=").append(variant).append(" columns=256 packedAndMappingEqual=true events=")
                                .append(expectedEvents.size()).append(" overrides=").append(expectedOverrides)
                                .append(" originalBlockReads=").append(original.reads)
                                .append(" originalMs=").append(originalNanos / 1e6).append(" adaptedMs=")
                                .append(adaptedNanos / 1e6).append("; diagnostic probe timing, not route acceptance\n");
                    }
                }
            }
        } finally {
            require(ApiEventInjector.INSTANCE.unbind(DhApiChunkProcessingEvent.class, Events.class),
                    "Fixture event listener must be removed");
        }
        negativeMembershipMemo(level);
        report.append("negativeMembershipMemoChangedAndReplacedSections=true; later invocations can prove a new singleton\n");
        return report.toString();
    }

    private static void negativeMembershipMemo(ServerLevel level) {
        var chunk = chunk(level, 0);
        var world = ServerLevelWrapper_neoforge.getWrapper(level);
        var wrapper = new ChunkWrapper_neoforge(chunk, world);
        lighting(wrapper, 0);
        var context = DistantUniformRun.context(wrapper);
        var stone = BlockStateWrapper_neoforge.fromBlockState(Blocks.STONE.defaultBlockState(), world);
        var biome = wrapper.getBiome(0, -1100, 0);
        require(DistantUniformRun.bottom(context, 0, -1100, 0, EarthChart.MIN_Y, stone, biome, 0, 0) == -1104,
                "Fresh exact singleton must permit a run before any failed proof");
        put(chunk, 4, -1100, 4, Blocks.GOLD_BLOCK.defaultBlockState());
        require(DistantUniformRun.bottom(context, 0, -1100, 0, EarthChart.MIN_Y, stone, biome, 0, 0) == -1100,
                "A prior positive proof must not hide a later real section edit");
        int index = chunk.getSectionIndex(-1100);
        chunk.getSections()[index] = new LevelChunkSection(new UniformTerrainStates(Blocks.STONE.defaultBlockState()),
                chunk.getSection(index).getBiomes());
        require(DistantUniformRun.bottom(context, 0, -1100, 0, EarthChart.MIN_Y, stone, biome, 0, 0) == -1100,
                "A replaced section must retain the conservative failed-proof path for this invocation");
        require(DistantUniformRun.bottom(DistantUniformRun.context(wrapper), 0, -1100, 0, EarthChart.MIN_Y,
                stone, biome, 0, 0) == -1104, "The failed proof must not outlive one conversion");
        var gold = BlockStateWrapper_neoforge.fromBlockState(Blocks.GOLD_BLOCK.defaultBlockState(), world);
        chunk.getSections()[index] = new LevelChunkSection(new UniformTerrainStates(Blocks.GOLD_BLOCK.defaultBlockState()),
                chunk.getSection(index).getBiomes());
        require(DistantUniformRun.bottom(context, 0, -1100, 0, EarthChart.MIN_Y, gold, biome, 0, 0) == -1100,
                "A replacement material must not revive a failed proof in the old conversion");
    }

    private static ProtoChunk chunk(ServerLevel level, int variant) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var biome = biomes.getHolderOrThrow(Biomes.PLAINS);
        var height = LevelHeightAccessor.create(EarthChart.MIN_Y, EarthChart.HEIGHT);
        var chunk = new ProtoChunk(POSITION, UpgradeData.EMPTY, height, biomes, null);
        for (int section = 0; section < chunk.getSectionsCount(); section++) {
            var state = chunk.getSectionYFromSectionIndex(section) * 16 < 64 ? Blocks.STONE : Blocks.AIR;
            chunk.getSections()[section] = new LevelChunkSection(new UniformTerrainStates(state.defaultBlockState()),
                    new PalettedContainer<>(biomes.asHolderIdMap(), biome, PalettedContainer.Strategy.SECTION_BIOMES));
        }
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        if (variant > 0) {
            for (int y = -512; y < -480; y++) {
                for (int x = 1; x < 5; x++) { put(chunk, x, y, 3, Blocks.CAVE_AIR.defaultBlockState()); }
            }
            put(chunk, 2, -480, 3, Blocks.WATER.defaultBlockState());
            put(chunk, 3, 64, 3, Blocks.SNOW.defaultBlockState());
            put(chunk, 4, 64, 4, Blocks.DANDELION.defaultBlockState());
            put(chunk, 5, 64, 5, Blocks.OAK_LEAVES.defaultBlockState());
            put(chunk, 6, -480, 6, Blocks.GLASS.defaultBlockState());
            put(chunk, 7, -481, 7, Blocks.GLOWSTONE.defaultBlockState());
            put(chunk, 8, -482, 8, Blocks.DIRT.defaultBlockState());
            var section = chunk.getSection(chunk.getSectionIndex(-1500));
            ((PalettedContainer<Holder<Biome>>) section.getBiomes()).getAndSet(0, 1, 0,
                    biomes.getHolderOrThrow(Biomes.DESERT));
        }
        if (variant == 4) {
            put(chunk, 0, -1100, 0, Blocks.GOLD_BLOCK.defaultBlockState());
            put(chunk, 0, -1100, 0, Blocks.STONE.defaultBlockState());
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING));
        return chunk;
    }

    private static void lighting(ChunkWrapper_neoforge wrapper, int variant) {
        wrapper.setIsDhBlockLightCorrect(true);
        wrapper.setIsDhSkyLightCorrect(true);
        if (variant >= 2) {
            wrapper.setDhBlockLight(0, -1197, 0, 7);
            wrapper.setDhSkyLight(1, -1198, 1, 4);
            wrapper.setDhBlockLight(7, -480, 7, 15);
        }
    }

    private static void put(ProtoChunk chunk, int x, int y, int z, BlockState state) {
        chunk.setBlockState(new BlockPos(POSITION.getMinBlockX() + x, y, POSITION.getMinBlockZ() + z), state, false);
    }

    private static void equal(FullDataSourceV2 expected, FullDataSourceV2 actual, String label) {
        require(expected != null && actual != null, "Converter rejected " + label);
        require(expected.mapping.size() == actual.mapping.size(), "Changed material mapping size " + label);
        for (int id = 0; id < expected.mapping.size(); id++) {
            require(expected.mapping.getBlockStateWrapper(id).equals(actual.mapping.getBlockStateWrapper(id))
                    && expected.mapping.getBiomeWrapper(id).equals(actual.mapping.getBiomeWrapper(id)),
                    "Changed material mapping " + label + " id=" + id);
        }
        for (int i = 0; i < expected.dataPoints.length; i++) {
            require(java.util.Objects.equals(expected.dataPoints[i], actual.dataPoints[i]),
                    "Changed packed height/light/material column " + label + " column=" + i);
        }
        require(expected.columnGenerationSteps.equals(actual.columnGenerationSteps)
                && expected.columnWorldCompressionMode.equals(actual.columnWorldCompressionMode)
                && expected.isEmpty == actual.isEmpty && expected.applyToParent.equals(actual.applyToParent),
                "Changed conversion metadata " + label);
    }

    private static final class Original extends ChunkWrapper_neoforge {
        long reads;
        Original(ProtoChunk chunk, ILevelWrapper level) { super(chunk, level); }
        @Override public IBlockStateWrapper getBlockState(int x, int y, int z, IMutableBlockPosWrapper position,
                                                        IBlockStateWrapper guess) {
            reads++; return super.getBlockState(x, y, z, position, guess);
        }
    }

    private static final class Events extends DhApiChunkProcessingEvent {
        final ILevelWrapper level;
        final List<String> events = new ArrayList<>();
        boolean override;
        int overrides;
        RuntimeException failure;
        Events(ILevelWrapper level) { this.level = level; }
        void reset() { events.clear(); overrides = 0; failure = null; }
        void check() {
            if (failure != null) { throw new IllegalStateException("DH caught a fixture callback failure", failure); }
        }
        @Override public void blockOrBiomeChangedDuringChunkProcessing(DhApiEventParam<EventParam> event) {
            try { observe(event.value); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        private void observe(EventParam value) {
            if (value.levelWrapper != level || value.chunkX != POSITION.x || value.chunkZ != POSITION.z) { return; }
            events.add(value.relativeBlockPosX + ":" + value.blockPosY + ":" + value.relativeBlockPosZ + ":"
                    + value.currentBlock.getSerialString() + ":" + value.currentBiome.getSerialString());
            if (override && value.currentBlock instanceof BlockStateWrapper_neoforge block
                    && block.blockState != null && block.blockState.is(Blocks.DIRT)) {
                value.setBlockOverride(BlockStateWrapper_neoforge.fromBlockState(Blocks.RED_CONCRETE.defaultBlockState(), level));
                value.setBiomeOverride(BiomeWrapper_neoforge.getBiomeWrapper(
                        ((ServerLevel) level.getWrappedMcObject()).registryAccess().registryOrThrow(Registries.BIOME)
                                .getHolderOrThrow(Biomes.FROZEN_OCEAN), level));
                overrides++;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
