package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.cosmos.StellarEvolution;
import dev.lexawhatt.astraengine.cosmos.StellarEvolutionSnapshot;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Overworld-save-owned diagnostic Sol state. Access and mutation require the owning server thread. */
public final class SolarState extends SavedData {
    private static final String FILE_NAME = "astraengine_solar";
    private final StellarEvolution evolution;

    private SolarState(StellarEvolution evolution) { this.evolution = evolution; }

    /** Loads saved state, refusing silent replacement of existing unreadable or unsupported data. */
    public static SolarState get(MinecraftServer server) {
        if (!server.isSameThread()) { throw new IllegalStateException("Solar state requires the owning server thread"); }
        Factory<SolarState> factory = new Factory<>(() -> {
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME + ".dat"))) {
                throw new IllegalStateException("Existing solar state could not be read; preserve it and restore a backup");
            }
            SolarState state = new SolarState(new StellarEvolution());
            state.setDirty(); return state;
        }, (tag, registries) -> decode(tag), null);
        return server.overworld().getDataStorage().computeIfAbsent(factory, FILE_NAME);
    }

    /** Current immutable snapshot, shared by Overworld and Sol presentation. */
    public StellarEvolutionSnapshot snapshot() { return evolution.snapshot(); }
    /** Starts a new bounded 10..3600-second diagnostic drain; no usable energy is credited. */
    public void startDemo(int seconds) { evolution.startDemo(seconds); setDirty(); }
    /** Pauses every simulation clock, returning whether the state changed. */
    public boolean pause() { boolean changed = evolution.pause(); if (changed) { setDirty(); } return changed; }
    /** Resumes only an unfinished configured demo, returning whether the state changed. */
    public boolean resume() { boolean changed = evolution.resume(); if (changed) { setDirty(); } return changed; }
    /** Resets to full paused energy while preserving monotonic revision/cycle identities. */
    public void reset() { evolution.reset(); setDirty(); }
    /** Advances one occupied tick; no offline or unoccupied catch-up is performed. */
    public boolean tick(boolean occupied) {
        boolean changed = evolution.tick(occupied);
        if (changed) { setDirty(); }
        return changed;
    }

    /** Strict version-one decoding. Missing clocks and contradictory accounting are rejected. */
    public static SolarState decode(CompoundTag tag) {
        require(tag, Tag.TAG_INT, "version", "phase_ticks", "drain_ticks", "drain_elapsed");
        require(tag, Tag.TAG_LONG, "remaining", "extracted", "active_ticks", "revision", "cycle");
        require(tag, Tag.TAG_STRING, "phase"); require(tag, Tag.TAG_BYTE, "running");
        if (tag.getInt("version") != 1 || (tag.getByte("running") != 0 && tag.getByte("running") != 1)) {
            throw new IllegalArgumentException("Unsupported or invalid solar state format");
        }
        StellarEvolutionSnapshot snapshot = new StellarEvolutionSnapshot(tag.getLong("remaining"), tag.getLong("extracted"),
                tag.getLong("active_ticks"), StellarEvolutionSnapshot.Phase.valueOf(tag.getString("phase")),
                tag.getInt("phase_ticks"), tag.getInt("drain_ticks"), tag.getInt("drain_elapsed"),
                tag.getBoolean("running"), tag.getLong("revision"), tag.getLong("cycle"));
        return new SolarState(StellarEvolution.restore(snapshot));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        StellarEvolutionSnapshot snapshot = snapshot();
        tag.putInt("version", 1); tag.putLong("remaining", snapshot.remaining()); tag.putLong("extracted", snapshot.extracted());
        tag.putLong("active_ticks", snapshot.activeTicks()); tag.putString("phase", snapshot.phase().name());
        tag.putInt("phase_ticks", snapshot.phaseTicks()); tag.putInt("drain_ticks", snapshot.drainTicks());
        tag.putInt("drain_elapsed", snapshot.drainElapsed()); tag.putBoolean("running", snapshot.running());
        tag.putLong("revision", snapshot.revision()); tag.putLong("cycle", snapshot.cycle()); return tag;
    }

    private static void require(CompoundTag tag, int type, String... keys) {
        for (String key : keys) {
            if (!tag.contains(key, type)) { throw new IllegalArgumentException("Missing or invalid saved solar field: " + key); }
        }
    }
}
