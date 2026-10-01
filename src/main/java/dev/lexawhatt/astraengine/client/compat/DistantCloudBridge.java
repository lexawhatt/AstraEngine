package dev.lexawhatt.astraengine.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import net.minecraft.client.Minecraft;

/** Optional symbols stay isolated here; only the public DH generic-object event is used. */
final class DistantCloudBridge {
    private DistantCloudBridge() { }

    static DistantCloudCompatibility.Binding bind(DistantCloudCompatibility owner) {
        if (DhApi.Delayed.configs == null) { return null; }
        // Event level identity became public in API 5.1. Unknown older APIs retain their own clouds.
        if (DhApi.getApiMajorVersion() < 5
                || DhApi.getApiMajorVersion() == 5 && DhApi.getApiMinorVersion() < 1) {
            throw new IllegalArgumentException("Distant Horizons cloud ownership requires public API 5.1 or later");
        }
        CloudListener listener = new CloudListener(owner);
        DhApi.events.bind(DhApiBeforeGenericObjectRenderEvent.class, listener);
        return () -> {
            // Even a foreign event-system failure must leave a lingering callback inert after connection end.
            listener.closed = true;
            if (!DhApi.events.unbind(DhApiBeforeGenericObjectRenderEvent.class, CloudListener.class)) {
                throw new IllegalStateException("Distant Horizons did not remove Astra's cloud event listener");
            }
        };
    }

    private static final class CloudListener extends DhApiBeforeGenericObjectRenderEvent {
        private final DistantCloudCompatibility owner;
        private boolean closed;

        private CloudListener(DistantCloudCompatibility owner) { this.owner = owner; }

        @Override
        public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
            if (closed) { return; }
            try {
                var level = Minecraft.getInstance().level;
                var value = event.value;
                // DH 3.3.3 exports this case-sensitive identifier, not a Minecraft ResourceLocation.
                if (!"DistantHorizons".equals(value.resourceLocationNamespace)
                        || !"Clouds".equals(value.resourceLocationPath)
                        || level == null || value.clientLevelWrapper == null
                        || !level.dimension().location().toString().equals(value.clientLevelWrapper.getDimensionName())) {
                    return;
                }
                if (owner.cancelCurrentClouds()) { event.cancelEvent(); }
            } catch (LinkageError unavailableApi) {
                closed = true;
                owner.fail(unavailableApi);
            }
        }
    }
}
