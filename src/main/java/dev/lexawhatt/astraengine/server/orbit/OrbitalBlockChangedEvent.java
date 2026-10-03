package dev.lexawhatt.astraengine.server.orbit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.Event;

/** Synchronous logical-server notification after an actual canonical LevelChunk mutation, including commands/removals. */
public final class OrbitalBlockChangedEvent extends Event {
    private final ServerLevel level;
    private final ChunkPos chunk;
    public OrbitalBlockChangedEvent(ServerLevel level, ChunkPos chunk) { this.level = level; this.chunk = chunk; }
    public ServerLevel level() { return level; }
    public ChunkPos chunk() { return chunk; }
}
