package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * At most sixteen successful singleton decodes owned by one synchronous chunk read. The owning read supplies
 * its actual registry and original NbtOps codecs. No template, registry or mutable container escapes that read.
 */
public final class UniformPaletteReads {
    private static final int MAX_TEMPLATES = 16;
    private static final int MAX_KEY_BYTES = 4096;
    private final Registry<Biome> registry;
    private final List<Template<BlockState>> blocks = new ArrayList<>();
    private final List<Template<Holder<Biome>>> biomes = new ArrayList<>();

    /** Captures the reading level's registry for this invocation only; null is invalid. */
    public UniformPaletteReads(Registry<Biome> registry) {
        if (registry == null) { throw new IllegalArgumentException("Palette reads require their level's biome registry"); }
        this.registry = registry;
    }

    /**
     * Decodes actual block NBT with the original host codec. Repeated canonical singleton inputs return distinct
     * ordinary host containers with the original success lifecycle; mixed, partial and error results are untouched.
     * The supplier must perform this codec's original parse with NbtOps; all arguments are required.
     */
    public DataResult<PalettedContainer<BlockState>> blocks(Codec<PalettedContainer<BlockState>> codec, Tag input,
            Supplier<DataResult<PalettedContainer<BlockState>>> original) {
        return decode(blocks, Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES,
                codec, input, original);
    }

    /** Same contract as block decoding, using only this read's biome registry and original biome codec. */
    public DataResult<PalettedContainerRO<Holder<Biome>>> biomes(Codec<PalettedContainerRO<Holder<Biome>>> codec,
            Tag input, Supplier<DataResult<PalettedContainerRO<Holder<Biome>>>> original) {
        return decode(biomes, registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES,
                codec, input, original);
    }

    @SuppressWarnings("unchecked")
    private <T, C extends PalettedContainerRO<T>> DataResult<C> decode(List<Template<T>> templates, IdMap<T> ids,
            PalettedContainer.Strategy strategy, Codec<C> codec, Tag input, Supplier<DataResult<C>> original) {
        if (codec == null || input == null || original == null) {
            throw new IllegalArgumentException("Palette reads require the original codec, NBT and parser");
        }
        if (!(input instanceof CompoundTag key) || key.size() != 1
                || !(key.get("palette") instanceof ListTag entries) || entries.size() != 1
                || key.sizeInBytes() > MAX_KEY_BYTES) { return original.get(); }
        for (var template : templates) {
            if (template.codec == codec && template.input.equals(key)) {
                return DataResult.success((C) new PalettedContainer<>(ids, template.value, strategy), template.lifecycle);
            }
        }
        DataResult<C> result = original.get();
        if (blocks.size() + biomes.size() >= MAX_TEMPLATES || result.error().isPresent()) { return result; }
        C decoded = result.result().orElse(null);
        if (decoded == null || decoded.getClass() != PalettedContainer.class) { return result; }
        var states = (PalettedContainer<T>) decoded;
        T value;
        states.acquire();
        try {
            value = states.get(0, 0, 0);
            if (states.maybeHas(candidate -> candidate != value)) { return result; }
        } finally { states.release(); }
        // Successful recovery from noncanonical properties must still visit the host codec each time.
        // Round-trip equality also avoids caching unknown extra fields or a nonstandard singleton encoding.
        var canonical = codec.encodeStart(NbtOps.INSTANCE, (C) new PalettedContainer<>(ids, value, strategy));
        if (canonical.error().isEmpty() && canonical.result().filter(key::equals).isPresent()) {
            templates.add(new Template<>(codec, key.copy(), value, result.lifecycle()));
        }
        return result;
    }

    private record Template<T>(Codec<?> codec, CompoundTag input, T value, Lifecycle lifecycle) { }
}
