package dev.lexawhatt.astraengine.client.ship;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.Event;

/**
 * Client render-thread event on NeoForge.EVENT_BUS, once at AFTER_BLOCK_ENTITIES before world
 * lighting, or AFTER_LEVEL after an active Iris pack. Never dispatched in a shadow pass.
 * Consumers submit current visual poses through collector(); never retain the event,
 * collector or level across frames. Register listeners only from a physical-client entry point.
 * Submission does not create entities, collisions, saved data or authoritative state.
 */
public final class CollectShipsEvent extends Event {
    private final ClientLevel level;
    private final SpaceVector camera;
    private final float partialTick;
    private final ShipCollector collector;

    /** Engine-owned frame context; all references must be non-null and partial tick finite. */
    public CollectShipsEvent(ClientLevel level, SpaceVector camera, float partialTick, ShipCollector collector) {
        if (level == null || camera == null || collector == null || !Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("Ship collection event requires a valid client frame");
        }
        this.level = level;
        this.camera = camera;
        this.partialTick = partialTick;
        this.collector = collector;
    }

    /** Current client level, borrowed for the duration of event dispatch only. */
    public ClientLevel level() { return level; }

    /** Immutable camera position in world blocks. */
    public SpaceVector camera() { return camera; }

    /** Host render interpolation fraction; consumers own their motion snapshots. */
    public float partialTick() { return partialTick; }

    /** Bounded collector, sealed immediately after event dispatch returns. */
    public ShipCollector collector() { return collector; }
}
