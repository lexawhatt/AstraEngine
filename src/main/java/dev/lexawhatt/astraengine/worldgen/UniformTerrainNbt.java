package dev.lexawhatt.astraengine.worldgen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Bounded immutable host-encoded palettes for unmodified generated sections. Only fixed vanilla default
 * states are keys; no registry, world, biome or mutable container is retained. Every returned NBT is a copy.
 */
public final class UniformTerrainNbt {
    private static final Set<BlockState> MATERIALS = Set.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState(),
            Blocks.SAND.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.DIRT.defaultBlockState());
    private static final ConcurrentHashMap<BlockState, Tag> PALETTES = new ConcurrentHashMap<>();

    private UniformTerrainNbt() { }

    /**
     * Returns an independent original-codec NBT snapshot, or null for the normal serializer path. The caller
     * must supply Minecraft's exact block-state codec and NbtOps. Edits and foreign materials always fall back.
     */
    public static Tag encode(UniformTerrainStates states, Function<PalettedContainer<BlockState>, Tag> hostEncoder) {
        if (states == null || hostEncoder == null) {
            throw new IllegalArgumentException("A generated palette and original host encoder are required");
        }
        return encode(states, states.initialState(), hostEncoder);
    }

    /**
     * Handles an exact deserialized vanilla palette in an owned tall chunk. The caller must establish that
     * ownership and use the original host codec/NbtOps. Foreign containers or mixed palettes return null.
     * This reads actual current palette membership under the host guard; an earlier save is not evidence.
     */
    public static Tag encodeHostPalette(PalettedContainer<BlockState> states,
            Function<PalettedContainer<BlockState>, Tag> hostEncoder) {
        if (states == null || hostEncoder == null) {
            throw new IllegalArgumentException("A host palette and original host encoder are required");
        }
        if (states.getClass() != PalettedContainer.class) { return null; }
        states.acquire();
        try { return encodeGuarded(states, states.get(0, 0, 0), hostEncoder); }
        finally { states.release(); }
    }

    private static Tag encode(PalettedContainer<BlockState> states, BlockState material,
            Function<PalettedContainer<BlockState>, Tag> hostEncoder) {
        states.acquire();
        try { return encodeGuarded(states, material, hostEncoder); }
        finally { states.release(); }
    }

    private static Tag encodeGuarded(PalettedContainer<BlockState> states, BlockState material,
            Function<PalettedContainer<BlockState>, Tag> hostEncoder) {
        if (!MATERIALS.contains(material) || states.maybeHas(value -> value != material)) { return null; }
        // Encode a separate constant palette: the host codec acquires its own container's guard.
        var encoded = PALETTES.computeIfAbsent(material, value -> hostEncoder.apply(new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, value, PalettedContainer.Strategy.SECTION_STATES)).copy());
        return encoded.copy();
    }
}
