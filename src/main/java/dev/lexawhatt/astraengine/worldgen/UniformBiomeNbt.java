package dev.lexawhatt.astraengine.worldgen;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * At most sixteen uniform biome templates owned by one ChunkSerializer.write invocation. The caller uses
 * that invocation's exact registry and biome codec; this object must never outlive the write or cross threads.
 * Source palettes remain independently mutable, and each result is an independent host-format NBT value.
 */
public final class UniformBiomeNbt {
    private static final int MAX_TEMPLATES = 16;
    private final Registry<Biome> registry;
    private final Map<Holder<Biome>, Tag> templates = new IdentityHashMap<>();

    /** Captures only the writing level's registry for this synchronous write. Null is invalid. */
    public UniformBiomeNbt(Registry<Biome> registry) {
        if (registry == null) { throw new IllegalArgumentException("Biome encoding requires the writing registry"); }
        this.registry = registry;
    }

    /**
     * Returns exact original-codec NBT or null for a mixed/foreign palette or a full template budget. The
     * encoder must be this write's original biome codec with NbtOps; encoding failures propagate unchanged.
     * Only actual singleton membership under the host guard permits reuse, including after block reload.
     */
    public Tag encode(PalettedContainerRO<Holder<Biome>> value,
            Function<PalettedContainerRO<Holder<Biome>>, Tag> hostEncoder) {
        if (value == null || hostEncoder == null) { throw new IllegalArgumentException("Biome palette and encoder are required"); }
        if (value.getClass() != PalettedContainer.class) { return null; }
        var states = (PalettedContainer<Holder<Biome>>) value;
        states.acquire();
        try {
            var biome = states.get(0, 0, 0);
            if (states.maybeHas(candidate -> candidate != biome)) { return null; }
            var encoded = templates.get(biome);
            if (encoded == null) {
                if (templates.size() >= MAX_TEMPLATES) { return null; }
                // Encode a separate palette so the original codec does not reacquire the source guard.
                encoded = hostEncoder.apply(new PalettedContainer<>(registry.asHolderIdMap(), biome,
                        PalettedContainer.Strategy.SECTION_BIOMES)).copy();
                templates.put(biome, encoded);
            }
            return encoded.copy();
        } finally { states.release(); }
    }
}
