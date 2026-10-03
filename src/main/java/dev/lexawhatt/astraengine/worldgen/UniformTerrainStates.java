package dev.lexawhatt.astraengine.worldgen;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Host-owned section storage initially filled with one terrain material. Minecraft retains all mutation,
 * copying and codecs. Saving an unchanged uniform palette skips unpacking 4096 identical block indices;
 * any additional palette entry uses the ordinary host serializer. The persisted format is unchanged.
 * This optimization is local to sections created by Astra generation, without patching other mods' palettes.
 */
public final class UniformTerrainStates extends PalettedContainer<BlockState> {
    private final BlockState initial;
    private final List<BlockState> singleton;

    /** Creates an independently mutable section palette; null is invalid. No world or worker is retained. */
    public UniformTerrainStates(BlockState initial) {
        super(Block.BLOCK_STATE_REGISTRY, requireState(initial), PalettedContainer.Strategy.SECTION_STATES);
        this.initial = initial;
        singleton = List.of(initial);
    }

    /** Preserves the host threading guard and falls back for changed palettes or foreign serialization strategies. */
    @Override
    public PalettedContainerRO.PackedData<BlockState> pack(IdMap<BlockState> registry,
            PalettedContainer.Strategy strategy) {
        if (registry == Block.BLOCK_STATE_REGISTRY && strategy == PalettedContainer.Strategy.SECTION_STATES) {
            acquire();
            try {
                // Palette membership includes obsolete entries, so this can only reject an optimization;
                // it cannot erase a block edit even when the palette has grown and later become uniform again.
                if (!maybeHas(value -> value != initial)) {
                    return new PalettedContainerRO.PackedData<>(singleton, Optional.empty());
                }
            } finally {
                release();
            }
        }
        return super.pack(registry, strategy);
    }

    /**
     * Reads the generated material only while this palette has never gained another value. The callback
     * executes under the host read guard and must not acquire or mutate this container. Null means fallback.
     */
    public <T> T readUnchanged(Supplier<T> read) {
        if (read == null) { throw new IllegalArgumentException("An unchanged-palette reader is required"); }
        acquire();
        try { return maybeHas(value -> value != initial) ? null : read.get(); }
        finally { release(); }
    }

    /** Original immutable block state, without asserting that this mutable palette still contains only it. */
    public BlockState initialState() { return initial; }

    private static BlockState requireState(BlockState value) {
        if (value == null) { throw new IllegalArgumentException("Uniform terrain requires an initial block state"); }
        return value;
    }
}
