package dev.lexawhatt.astraengine.verification;

import com.mojang.serialization.JsonOps;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.worldgen.UniformTerrainStates;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Verifies host-format equivalence after edits and measures only uniform palette packing, not world save time. */
@PrefixGameTestTemplate(false)
public final class TerrainStorageGameTests {
    private static volatile Object retained;

    @GameTest(templateNamespace = "astraengine_verify", template = "empty", timeoutTicks = 200)
    public static void uniformTerrainSerializationRetainsEveryEditAndHostFormat(GameTestHelper helper) {
        var strategy = PalettedContainer.Strategy.SECTION_STATES;
        var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, strategy,
                Blocks.AIR.defaultBlockState());
        for (var material : new BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.AIR.defaultBlockState()}) {
            var accelerated = new UniformTerrainStates(material);
            var host = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, material, strategy);
            for (int phase = 0; phase < 3; phase++) {
                if (phase > 0) {
                    for (int index = 0; index < 4096; index += 17) {
                        BlockState value = phase == 1 ? (index % 2 == 0 ? Blocks.DIAMOND_BLOCK.defaultBlockState()
                                : Blocks.CHEST.defaultBlockState()) : material;
                        int x = index & 15, y = index >> 8, z = index >> 4 & 15;
                        accelerated.set(x, y, z, value); host.set(x, y, z, value);
                    }
                }
                var actual = accelerated.pack(Block.BLOCK_STATE_REGISTRY, strategy);
                var expected = host.pack(Block.BLOCK_STATE_REGISTRY, strategy);
                helper.assertTrue(actual.paletteEntries().equals(expected.paletteEntries())
                                && Arrays.equals(actual.storage().map(stream -> stream.toArray()).orElseGet(() -> new long[0]),
                                        expected.storage().map(stream -> stream.toArray()).orElseGet(() -> new long[0])),
                        "Uniform optimization changed host palette packing at phase " + phase);
                var encoded = codec.encodeStart(JsonOps.INSTANCE, accelerated).getOrThrow();
                helper.assertTrue(encoded.equals(codec.encodeStart(JsonOps.INSTANCE, host).getOrThrow()),
                        "Uniform optimization changed the persisted codec format");
                var restored = codec.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                for (int index = 0; index < 4096; index++) {
                    int x = index & 15, y = index >> 8, z = index >> 4 & 15;
                    helper.assertTrue(restored.get(x, y, z) == host.get(x, y, z), "Serialization lost a terrain edit");
                }
            }
        }

        var material = Blocks.STONE.defaultBlockState();
        var host = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, material, strategy);
        var accelerated = new UniformTerrainStates(material);
        measure(host, 4000); measure(accelerated, 4000);
        long[] baseline = new long[31], optimized = new long[31];
        for (int sample = 0; sample < baseline.length; sample++) {
            if ((sample & 1) == 0) {
                baseline[sample] = measure(host, 1000); optimized[sample] = measure(accelerated, 1000);
            } else {
                optimized[sample] = measure(accelerated, 1000); baseline[sample] = measure(host, 1000);
            }
        }
        Arrays.sort(baseline); Arrays.sort(optimized);
        AstraEngine.LOGGER.info("ASTRA_UNIFORM_PACK samples=31 operationsPerSample=1000 warmup=4000 "
                        + "hostMedianNs={} hostP95Ns={} optimizedMedianNs={} optimizedP95Ns={}",
                baseline[15], baseline[29], optimized[15], optimized[29]);
        retained = null;
        helper.succeed();
    }

    private static long measure(PalettedContainer<BlockState> container, int count) {
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            retained = container.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES);
        }
        return System.nanoTime() - start;
    }
}
