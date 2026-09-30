package dev.lexawhatt.astraengine.api.rocket;

import dev.lexawhatt.astraengine.rocket.RocketBlueprint;
import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import dev.lexawhatt.astraengine.server.rocket.RocketBlueprintCodec;
import dev.lexawhatt.astraengine.server.rocket.RocketEditorSessions;
import dev.lexawhatt.astraengine.server.rocket.RocketWorkshop;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Public entry point shared by the diagnostic workshop and consumer-owned editor hosts. */
public final class AstraRocketEditor {
    private AstraRocketEditor() {}

    /**
     * Returns the immutable common part definitions after native common setup has finished.
     * Safe on either logical side; unavailable during registration. Contains no world or session state.
     * Calling before setup throws IllegalStateException. Consumers register through RegisterRocketPartsEvent.
     */
    public static RocketCatalog catalog() { return RocketWorkshop.catalog(); }

    /**
     * Encodes a catalog-valid immutable blueprint as a fresh, versioned compound owned by the caller.
     * Safe on either logical side after common setup freezes the catalog; performs no world mutation or I/O.
     * Null, unknown definitions and invalid schema values throw IllegalArgumentException. The caller may mutate
     * the returned tag without changing the blueprint or a previously encoded tag.
     */
    public static CompoundTag encodeBlueprint(RocketBlueprint blueprint) {
        return RocketBlueprintCodec.encode(blueprint);
    }

    /**
     * Decodes and validates versioned storage against the frozen catalog, returning an immutable blueprint.
     * Safe on either logical side after common setup. The caller retains ownership of the input tag, which
     * is neither modified nor retained and must not be mutated concurrently while this call reads it.
     * Null, malformed storage, unknown definitions and incompatible schemas throw IllegalArgumentException.
     * Hosts should assign the result only after this call succeeds, preserving their prior draft on failure.
     */
    public static RocketBlueprint decodeBlueprint(CompoundTag tag) {
        return RocketBlueprintCodec.decode(tag);
    }

    /**
     * Opens a token-bound editing session for a loaded RocketEditorHost in the player's current dimension.
     * Requires the owning server thread, a live player within eight blocks, build permission and host authorization.
     * Returns false with translated feedback for expected denial. Null inputs throw; no chunks are loaded.
     * Replaces any prior session. Tokens and unsaved drafts are not persisted or transferred on reconnect.
     */
    public static boolean open(ServerPlayer player, BlockPos position) { return RocketEditorSessions.open(player, position); }
}
