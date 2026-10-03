package dev.lexawhatt.astraengine.verification;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Lifecycle;
import dev.lexawhatt.astraengine.server.ExplorationCatalog;
import dev.lexawhatt.astraengine.server.PlanetSurfaceWorlds;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import dev.lexawhatt.astraengine.worldgen.UniformPaletteReads;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual host codecs and tall chunk reads verify bounded reuse without shared mutable storage or registries. */
@PrefixGameTestTemplate(false)
public final class UniformPaletteReadGameTests {
    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void canonicalSingletonReadsAreBoundedIndependentAndPreserveErrors(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var scope = new UniformPaletteReads(registry);
        var codec = blocks();
        var input = encode(codec, Blocks.STONE.defaultBlockState());
        var calls = new AtomicInteger();
        var first = scope.blocks(codec, input, () -> {
            calls.incrementAndGet(); return codec.parse(NbtOps.INSTANCE, input).setLifecycle(Lifecycle.deprecated(7));
        });
        var second = scope.blocks(codec, input.copy(), () -> {
            calls.incrementAndGet(); return codec.parse(NbtOps.INSTANCE, input);
        });
        helper.assertTrue(calls.get() == 1 && first.getOrThrow() != second.getOrThrow()
                        && first.lifecycle() == second.lifecycle(),
                "Repeated canonical singleton was not independent or lost its original success lifecycle");
        first.getOrThrow().set(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        helper.assertTrue(second.getOrThrow().get(0, 0, 0).is(Blocks.STONE), "A reused container shared block storage");

        var unknown = new CompoundTag(); var entry = new CompoundTag(); var palette = new ListTag();
        entry.putString("Name", "astraengine_verify:unknown_saved_block"); palette.add(entry); unknown.put("palette", palette);
        DataResult<PalettedContainer<BlockState>> originalError = codec.parse(NbtOps.INSTANCE, unknown);
        helper.assertTrue(originalError.error().isPresent(), "Invalid-block fixture did not exercise host partial/error handling");
        calls.set(0);
        for (int i = 0; i < 2; i++) {
            var actual = scope.blocks(codec, unknown, () -> { calls.incrementAndGet(); return originalError; });
            helper.assertTrue(actual == originalError, "A partial/error decode was transformed or cached");
        }
        helper.assertTrue(calls.get() == 2, "Repeated invalid input bypassed the host error path");

        var noncanonical = input.copy(); noncanonical.putInt("unknown_extra_field", 3); calls.set(0);
        for (int i = 0; i < 2; i++) {
            scope.blocks(codec, noncanonical, () -> { calls.incrementAndGet(); return codec.parse(NbtOps.INSTANCE, noncanonical); });
        }
        helper.assertTrue(calls.get() == 2, "Noncanonical singleton bypassed original parsing");

        var bounded = new UniformPaletteReads(registry); calls.set(0);
        var materials = List.of(Blocks.AIR, Blocks.STONE, Blocks.WATER, Blocks.PACKED_ICE, Blocks.SNOW_BLOCK,
                Blocks.SAND, Blocks.GRAVEL, Blocks.DIRT, Blocks.COBBLESTONE, Blocks.BEDROCK, Blocks.GLASS,
                Blocks.RED_CONCRETE, Blocks.WHITE_WOOL, Blocks.ANDESITE, Blocks.GRANITE, Blocks.DIORITE, Blocks.OAK_PLANKS);
        for (var material : materials) {
            var value = encode(codec, material.defaultBlockState());
            for (int i = 0; i < 2; i++) {
                bounded.blocks(codec, value, () -> { calls.incrementAndGet(); return codec.parse(NbtOps.INSTANCE, value); });
            }
        }
        helper.assertTrue(calls.get() == 18, "Read templates did not retain exactly the bounded first sixteen singleton inputs");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty")
    public static void biomeReadsNeverShareRegistryHoldersAcrossInvocations(GameTestHelper helper) {
        var actual = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var firstRegistry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var secondRegistry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        Registry.register(firstRegistry, Biomes.PLAINS, actual.getHolderOrThrow(Biomes.PLAINS).value());
        Registry.register(secondRegistry, Biomes.PLAINS, actual.getHolderOrThrow(Biomes.DESERT).value());
        firstRegistry.freeze(); secondRegistry.freeze();
        var firstCodec = biomes(firstRegistry); var secondCodec = biomes(secondRegistry);
        var input = firstCodec.encodeStart(NbtOps.INSTANCE, new PalettedContainer<>(firstRegistry.asHolderIdMap(),
                firstRegistry.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES)).getOrThrow();
        var first = new UniformPaletteReads(firstRegistry); var second = new UniformPaletteReads(secondRegistry);
        var calls = new AtomicInteger();
        first.biomes(firstCodec, input, () -> firstCodec.parse(NbtOps.INSTANCE, input));
        var result = second.biomes(secondCodec, input, () -> { calls.incrementAndGet(); return secondCodec.parse(NbtOps.INSTANCE, input); });
        var repeated = second.biomes(secondCodec, input.copy(), () -> { calls.incrementAndGet(); return secondCodec.parse(NbtOps.INSTANCE, input); });
        helper.assertTrue(calls.get() == 1 && result.getOrThrow() != repeated.getOrThrow()
                        && repeated.getOrThrow().get(0, 0, 0) == secondRegistry.getHolderOrThrow(Biomes.PLAINS)
                        && repeated.getOrThrow().get(0, 0, 0).value() == actual.getHolderOrThrow(Biomes.DESERT).value(),
                "Singleton biome decoding retained a holder from another registry/read");
        helper.succeed();
    }

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 400)
    public static void actualTallReadKeepsRepeatedSectionsAndLaterEditsIndependent(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var system = ExplorationCatalog.get(server).system("sol");
        var moon = system.bodies().stream().filter(body -> body.id().equals("moon")).findFirst().orElseThrow();
        var level = PlanetSurfaceWorlds.ensure(server,
                new PlanetChart(SolidPlanetProfile.create(system, moon).orElseThrow(), CubeFace.POSITIVE_X, 6));
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var plains = registry.getHolderOrThrow(Biomes.PLAINS); var desert = registry.getHolderOrThrow(Biomes.DESERT);
        var chunk = new ProtoChunk(new ChunkPos(20, -20), UpgradeData.EMPTY, level, registry, null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        for (int i = 5; i <= 6; i++) {
            chunk.getSections()[i] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    Blocks.STONE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    new PalettedContainer<>(registry.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
        }
        var saved = ChunkSerializer.write(level, chunk);
        var retained = saved.copy();
        var info = new RegionStorageInfo("astra-singleton-read-test", level.dimension(), "chunk");
        var restored = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), saved);
        helper.assertTrue(restored.getSection(5).getStates() != restored.getSection(6).getStates()
                        && restored.getSection(5).getBiomes() != restored.getSection(6).getBiomes(),
                "Actual deserialization shared mutable section containers");
        int y = level.getMinBuildHeight() + 5 * 16;
        var changed = new BlockPos(chunk.getPos().getMinBlockX(), y, chunk.getPos().getMinBlockZ());
        restored.setBlockState(changed, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        @SuppressWarnings("unchecked")
        var changedBiomes = (PalettedContainer<Holder<Biome>>) restored.getSection(5).getBiomes();
        changedBiomes.set(0, 0, 0, desert);
        helper.assertTrue(restored.getSection(6).getBlockState(0, 0, 0).is(Blocks.STONE)
                        && restored.getSection(6).getNoiseBiome(0, 0, 0) == plains && saved.equals(retained),
                "A decoded-section edit changed its neighbor or source NBT");
        var again = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(),
                ChunkSerializer.write(level, restored));
        helper.assertTrue(again.getBlockState(changed).is(Blocks.GOLD_BLOCK)
                        && again.getSection(5).getNoiseBiome(0, 0, 0) == desert
                        && again.getSection(6).getBlockState(0, 0, 0).is(Blocks.STONE)
                        && again.getSection(6).getNoiseBiome(0, 0, 0) == plains,
                "Later mixed block/biome edits were lost on host resave and second read");
        var originalAgain = ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), retained);
        helper.assertTrue(originalAgain.getBlockState(changed).is(Blocks.STONE)
                        && originalAgain.getSection(5).getNoiseBiome(0, 0, 0) == plains,
                "A subsequent read reused mutated containers from a previous invocation");
        helper.succeed();
    }

    private static Codec<PalettedContainer<BlockState>> blocks() {
        return PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    }

    private static CompoundTag encode(Codec<PalettedContainer<BlockState>> codec, BlockState state) {
        return (CompoundTag) codec.encodeStart(NbtOps.INSTANCE, new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                state, PalettedContainer.Strategy.SECTION_STATES)).getOrThrow();
    }

    private static Codec<PalettedContainerRO<Holder<Biome>>> biomes(Registry<Biome> registry) {
        return PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, registry.getHolderOrThrow(Biomes.PLAINS));
    }
}
