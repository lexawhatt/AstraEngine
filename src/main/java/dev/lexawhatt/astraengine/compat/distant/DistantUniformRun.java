package dev.lexawhatt.astraengine.compat.distant;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_neoforge;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Exact DH 3.3.3 conversion aid for an already unchanged opaque run in a tall host chunk. The caller has
 * compared its current and sampled block/biome and rejected forced single-block entries. This method only
 * skips the remaining part of a real singleton section whose sampled DH light is uniformly zero.
 * It reads the same live chunk as DH and owns no persistent cache, world, snapshot, scheduling or persistence.
 */
public final class DistantUniformRun {
    private DistantUniformRun() { }

    /** Invocation-local bounds and conservative proofs; never retained outside one actual DH conversion. */
    public static final class Context {
        private final ChunkWrapper_neoforge wrapper;
        private final int minimumY;
        private final int maximumY;
        // Failure only: a mixed section cannot improve this invocation, even if it later becomes uniform.
        private final boolean[] failedMembership = new boolean[PlanetChart.HEIGHT / 16];
        // PalettedContainer.maybeHas consumes these predicates synchronously on this conversion thread.
        private final IdentityMismatch<BlockState> stateMismatch = new IdentityMismatch<>();
        private final IdentityMismatch<Holder<Biome>> biomeMismatch = new IdentityMismatch<>();

        private Context(ChunkWrapper_neoforge wrapper, int minimumY, int maximumY) {
            this.wrapper = wrapper;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
        }
    }

    private static final class IdentityMismatch<T> implements Predicate<T> {
        private T expected;

        @Override
        public boolean test(T value) {
            return value != expected;
        }
    }

    /** Captures an eligible wrapper's fixed height once per conversion; null selects the ordinary scan. */
    public static Context context(IChunkWrapper wrapped) {
        if (wrapped == null || wrapped.getClass() != ChunkWrapper_neoforge.class) { return null; }
        var wrapper = (ChunkWrapper_neoforge) wrapped;
        var chunk = wrapper.getChunk();
        if (chunk.getHeight() != PlanetChart.HEIGHT) { return null; }
        int minimumY = chunk.getMinBuildHeight();
        return new Context(wrapper, minimumY, minimumY + PlanetChart.HEIGHT);
    }

    /**
     * Returns the inclusive last Y that the current unchanged run can consume, or the original host Y.
     * Called only by the optional exact-version converter hook on its owning conversion thread. Foreign
     * wrappers, mixed/edited palettes, transparent materials, biome boundaries and lit cells fall back.
     */
    public static int bottom(IChunkWrapper wrapped, int x, int y, int z, int minimumY,
                             IBlockStateWrapper state, IBiomeWrapper biome, int blockLight, int skyLight) {
        return bottom(context(wrapped), x, y, z, minimumY, state, biome, blockLight, skyLight);
    }

    /** Same proof using the calling converter's already captured immutable bounds; null means no skip. */
    public static int bottom(Context context, int x, int y, int z, int minimumY,
                             IBlockStateWrapper state, IBiomeWrapper biome, int blockLight, int skyLight) {
        if (context == null || y < context.minimumY || y >= context.maximumY) { return y; }
        int sectionIndex = Math.floorDiv(y, 16) - Math.floorDiv(context.minimumY, 16);
        if (context.failedMembership[sectionIndex]) { return y; }
        int bottom = Math.max(minimumY, Math.floorDiv(y, 16) * 16);
        if (y - bottom < 2 || blockLight != 0 || skyLight != 0
                || !(state instanceof BlockStateWrapper_neoforge block)
                || !(biome instanceof BiomeWrapper_neoforge sampledBiome)
                || !state.isSolid() || state.isAir() || state.isLiquid()
                || state.getOpacity() != LodUtil.BLOCK_FULLY_OPAQUE) {
            return y;
        }
        var wrapper = context.wrapper;
        var section = wrapper.getChunk().getSection(sectionIndex);
        // Membership includes obsolete palette entries. A changed-then-restored section stays conservative.
        // No locks are added to DH's live read path: later block updates retain its ordinary capture scheduling.
        context.stateMismatch.expected = block.blockState;
        context.biomeMismatch.expected = sampledBiome.biome;
        if (section.getStates().maybeHas(context.stateMismatch)
                || section.getBiomes().maybeHas(context.biomeMismatch)) {
            context.failedMembership[sectionIndex] = true;
            return y;
        }
        for (int skippedY = y - 1; skippedY >= bottom; skippedY--) {
            if (wrapper.getDhBlockLight(x, skippedY + 1, z) != 0
                    || wrapper.getDhSkyLight(x, skippedY + 1, z) != 0) { return y; }
        }
        return bottom;
    }
}
