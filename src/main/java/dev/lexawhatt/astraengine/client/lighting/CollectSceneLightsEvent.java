package dev.lexawhatt.astraengine.client.lighting;

import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.Event;

/**
 * Client render-thread event on NeoForge.EVENT_BUS, once per rendered Astra environment frame.
 * Submit current lights through collector(); never retain the collector, level or event across frames.
 * Only register listeners from a physical-client entry point. No authoritative state may be changed here.
 */
public final class CollectSceneLightsEvent extends Event {
    private final ClientLevel level;
    private final LightVector camera;
    private final LightCollector collector;
    private final float partialTick;

    public CollectSceneLightsEvent(ClientLevel level, LightVector camera, LightCollector collector, float partialTick) {
        this.level = level;
        this.camera = camera;
        this.collector = collector;
        this.partialTick = partialTick;
    }

    public ClientLevel level() { return level; }
    public LightVector camera() { return camera; }
    public LightCollector collector() { return collector; }
    public float partialTick() { return partialTick; }
}
