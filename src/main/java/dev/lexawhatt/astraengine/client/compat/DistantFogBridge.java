package dev.lexawhatt.astraengine.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import java.awt.Color;

/** Optional API 7.2 symbols; public per-pass fog values never write third-party configuration. */
final class DistantFogBridge {
    private DistantFogBridge() { }

    static DistantFogCompatibility.Binding bind(DistantFogCompatibility owner) {
        if (DhApi.Delayed.configs == null) { return null; }
        if (DhApi.getApiMajorVersion() != 7 || DhApi.getApiMinorVersion() < 2) {
            throw new IllegalArgumentException("Geographic fog requires Distant Horizons API 7.2");
        }
        var listener = new FogListener(owner);
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, listener);
        return () -> {
            listener.closed = true;
            if (!DhApi.events.unbind(DhApiBeforeFogRenderEvent.class, FogListener.class)) {
                throw new IllegalStateException("Distant Horizons did not remove the geographic fog listener");
            }
        };
    }

    private static final class FogListener extends DhApiBeforeFogRenderEvent {
        private final DistantFogCompatibility owner;
        private boolean closed;

        private FogListener(DistantFogCompatibility owner) { this.owner = owner; }

        @Override public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
            if (closed) { return; }
            try {
                var frame = owner.current();
                var level = event.value.getRenderParam().clientLevelWrapper;
                if (frame == null || level == null || !frame.dimension().equals(level.getDimensionName())) { return; }
                // Pinned DH 3.3.3 scales fragment distance by (configured radius * 16), not its projection far plane.
                float radiusMeters = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() * 16.0f;
                if (!(radiusMeters > 0)) { return; }
                var fog = event.value.getFogRenderParam();
                fog.setFogColor(new Color(frame.red(), frame.green(), frame.blue()));
                fog.setFarFogFalloff(EDhApiFogFalloff.EXPONENTIAL);
                fog.setFarFogStartPercent(0);
                fog.setFarFogEndPercent(frame.lengthMeters() / radiusMeters);
                fog.setFarFogMinThickness(0);
                fog.setFarFogMaxThickness(1);
                fog.setFarFogDensity(1);
                fog.setHeightFogMixingMode(EDhApiHeightFogMixMode.SPHERICAL);
                fog.setHeightFogMinThickness(0);
                fog.setHeightFogMaxThickness(0);
                owner.adjusted();
            } catch (LinkageError unavailableApi) {
                closed = true;
                owner.fail(unavailableApi);
            }
        }
    }
}
