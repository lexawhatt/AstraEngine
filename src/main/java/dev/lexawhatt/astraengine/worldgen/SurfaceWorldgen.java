package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.MapCodec;
import dev.lexawhatt.astraengine.AstraEngine;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the saved generator codec before data-defined permanent surface dimensions load. */
public final class SurfaceWorldgen {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, AstraEngine.MOD_ID);

    static {
        GENERATORS.register("surface_patch", () -> SurfaceChunkGenerator.CODEC);
    }

    private SurfaceWorldgen() {}

    /** Registers once on the common mod event bus; no client types or runtime world allocation are involved. */
    public static void register(IEventBus modBus) {
        if (modBus == null) { throw new IllegalArgumentException("World generation registration requires a mod bus"); }
        GENERATORS.register(modBus);
    }
}
