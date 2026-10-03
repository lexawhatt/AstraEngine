package dev.lexawhatt.astraengine.mixin;

import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthBoundarySnapshot;
import dev.lexawhatt.astraengine.surface.PlanetaryLevelView;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Level-lifetime prediction context; no save data or reference survives host disposal. */
@Mixin(Level.class)
abstract class PlanetaryLevelMixin implements PlanetaryLevelView {
    @Unique private CubeStorageChart astra$chart;
    @Unique private EarthBoundarySnapshot astra$boundary;
    @Override public CubeStorageChart astra$chart() { return astra$chart; }
    @Override public EarthBoundarySnapshot astra$boundary() { return astra$boundary; }
    @Override public void astra$geography(CubeStorageChart chart, EarthBoundarySnapshot boundary) {
        astra$chart = chart; astra$boundary = boundary;
    }
}
