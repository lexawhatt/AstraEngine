package dev.lexawhatt.astraengine.compat.distant;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import net.minecraft.server.level.ServerLevel;

/** Optional DH symbols are isolated here. The process-lifetime listener retains no levels or generators. */
public final class DistantTerrainBridge {
    private DistantTerrainBridge() { }

    /** Called once during common setup, only when DH is installed; works on integrated and dedicated servers. */
    public static void register() {
        if (DhApi.getApiMajorVersion() != 7 || DhApi.getApiMinorVersion() < 2) {
            AstraEngine.LOGGER.warn("Direct Earth LOD generation requires Distant Horizons API 7.2; retaining DH generation");
            return;
        }
        DhApi.events.bind(DhApiLevelLoadEvent.class, new LevelListener());
    }

    private static final class LevelListener extends DhApiLevelLoadEvent {
        @Override
        public void onLevelLoad(DhApiEventParam<EventParam> event) {
            var wrapper = event.value.levelWrapper;
            if (!(wrapper.getWrappedMcObject() instanceof ServerLevel level)
                    || !(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earth)) { return; }
            var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, new EarthLodGenerator(wrapper, earth));
            if (result.success) {
                AstraEngine.LOGGER.info("Direct geographic DH LOD generator registered for {}", level.dimension().location());
            } else {
                AstraEngine.LOGGER.warn("DH retained another generator for {}: {}", level.dimension().location(), result.message);
            }
        }
    }
}
