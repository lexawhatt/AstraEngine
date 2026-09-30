package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.server.SkyService;
import dev.lexawhatt.astraengine.server.SkyState;
import dev.lexawhatt.astraengine.sky.PlanetarySkyProfile;
import dev.lexawhatt.astraengine.sky.SkyEphemeris;
import dev.lexawhatt.astraengine.sky.SkySample;
import net.minecraft.server.MinecraftServer;

/**
 * Logical-server API for the saved Overworld sky profile. Calls require a non-null owning server and its
 * server thread; callers own permission policy. This does not change astronomical descriptors, world time,
 * authoritative block light, spawning rules, weather or the independent diagnostic stellar evolution.
 */
public final class AstraSky {
    private AstraSky() {
    }

    /** Returns immutable settings safe to retain after the owning server stops. */
    public static PlanetarySkyProfile profile(MinecraftServer server) {
        return SkyState.get(server).profile();
    }

    /**
     * Atomically replaces settings, marks normal SavedData dirty and sends the new revision to connected clients.
     * Equal settings return false; changed settings return true. Null input/off-thread access throws before mutation.
     * Normal save ownership applies: successful in-memory configuration does not guarantee an immediate disk commit.
     */
    public static boolean configure(MinecraftServer server, PlanetarySkyProfile profile) {
        boolean changed = SkyState.get(server).configure(profile);
        if (changed) { SkyService.broadcast(server); }
        return changed;
    }

    /** Samples the current Overworld host dayTime without mutating or advancing any clock. */
    public static SkySample snapshot(MinecraftServer server) {
        PlanetarySkyProfile profile = profile(server);
        return SkyEphemeris.sample(profile, server.overworld().getDayTime(), 0);
    }
}
