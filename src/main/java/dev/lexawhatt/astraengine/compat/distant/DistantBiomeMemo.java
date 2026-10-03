package dev.lexawhatt.astraengine.compat.distant;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** One immutable DH 3.3.3 lookup pair, owned exclusively by its existing chunk wrapper. */
public record DistantBiomeMemo(Holder<Biome> holder, BiomeWrapper_neoforge wrapper) { }
