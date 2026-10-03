package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.worldgen.EarthChunkGenerator;
import dev.lexawhatt.astraengine.worldgen.PlanetChunkGenerator;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Save-owned permanent definitions for dynamically opened full planetary charts and Earth's upper-air bands.
 * Immutable generators contain registry holders, never levels. Allocation order does not affect identity;
 * exhaustion refuses a new chart and never evicts or reuses an existing saved definition.
 */
public final class PlanetSurfaceBindings extends SavedData {
    public static final int MAX_CHARTS = 96;
    private static final String FILE_NAME = "astraengine_planet_surfaces";
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private long revision;
    private long contextRevision;

    /** Expected refusal of a new permanent chart when the saved allocation budget is full. */
    public static final class CapacityExceededException extends IllegalStateException {
        private CapacityExceededException() { super("Persistent planetary chart capacity is exhausted"); }
    }

    public record Binding(CubeStorageChart chart, ChunkGenerator generator) {
        public Binding {
            if (chart == null || generator == null || !chart.equals(chartOf(generator))) {
                throw new IllegalArgumentException("Planet binding and generator identity disagree");
            }
        }
    }

    private PlanetSurfaceBindings() { }

    /** Logical-server-thread lookup; unreadable existing records fail closed without new defaults. */
    public static PlanetSurfaceBindings get(MinecraftServer server) {
        if (server == null || !server.isSameThread()) { throw new IllegalStateException("Planet bindings require the server thread"); }
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(() -> {
            var data = server.getWorldPath(LevelResource.ROOT).resolve("data");
            if (Files.exists(data.resolve(FILE_NAME + ".dat")) || Files.exists(data.resolve(FILE_NAME + ".dat_old"))) {
                throw new IllegalStateException("Existing planet bindings are unreadable; preserve the save and restore a backup");
            }
            return new PlanetSurfaceBindings();
        }, PlanetSurfaceBindings::decode, null), FILE_NAME);
    }

    /** Bounded immutable allocation snapshot. No world instance is exposed or retained. */
    public List<Binding> bindings() { return List.copyOf(bindings.values()); }
    public long revision() { return revision; }
    /** Server-lifetime wire ordering; visibility can change without altering the durable allocation manifest. */
    public long nextContextRevision() { contextRevision = Math.incrementExact(contextRevision); return contextRevision; }
    public Binding binding(String dimensionId) { return bindings.get(dimensionId); }

    /** Adds one exact definition, rejecting a conflicting identity or exhausted capacity before mutation. */
    public Binding bind(CubeStorageChart chart, ChunkGenerator generator) {
        Binding proposed = new Binding(chart, generator);
        var previous = bindings.get(chart.dimensionId());
        if (previous != null) {
            if (!previous.chart().equals(chart)) { throw new IllegalArgumentException("Saved planetary chart cannot be replaced"); }
            return previous;
        }
        if (chart instanceof PlanetChart planet) {
            for (var binding : bindings.values()) {
                if (binding.chart() instanceof PlanetChart other
                        && other.profile().systemId().equals(planet.profile().systemId())
                        && other.profile().bodyId().equals(planet.profile().bodyId())
                        && !other.profile().equals(planet.profile())) {
                    throw new IllegalArgumentException("Saved planet profile cannot be changed across charts");
                }
            }
        }
        if (bindings.size() >= MAX_CHARTS) { throw new CapacityExceededException(); }
        bindings.put(chart.dimensionId(), proposed);
        revision++;
        setDirty();
        return proposed;
    }

    /** Strict decoder used by the host and verification. Stored generator definitions are authoritative. */
    public static PlanetSurfaceBindings decode(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || registries == null || !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1
                || !tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 0
                || !tag.contains("charts", Tag.TAG_LIST)) { throw new IllegalArgumentException("Invalid planet binding manifest"); }
        var entries = tag.getList("charts", Tag.TAG_COMPOUND);
        if (entries.size() > MAX_CHARTS || entries.size() != ((ListTag) tag.get("charts")).size()) {
            throw new IllegalArgumentException("Invalid planetary chart count or element type");
        }
        var result = new PlanetSurfaceBindings();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (var element : entries) {
            var entry = (CompoundTag) element;
            if (!entry.contains("dimension", Tag.TAG_STRING) || !entry.contains("generator", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Planetary binding lacks dimension or generator");
            }
            var generator = ChunkGenerator.CODEC.parse(ops, entry.getCompound("generator")).getOrThrow();
            var chart = chartOf(generator);
            if (!chart.dimensionId().equals(entry.getString("dimension")) || result.bindings.containsKey(chart.dimensionId())) {
                throw new IllegalArgumentException("Duplicate or changed permanent planet dimension");
            }
            result.bind(chart, generator);
        }
        if (tag.getLong("revision") < entries.size()) { throw new IllegalArgumentException("Planet binding revision predates its entries"); }
        result.revision = tag.getLong("revision");
        result.setDirty(false);
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1); tag.putLong("revision", revision);
        var entries = new ListTag();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (var binding : bindings.values()) {
            var entry = new CompoundTag();
            entry.putString("dimension", binding.chart().dimensionId());
            entry.put("generator", ChunkGenerator.CODEC.encodeStart(ops, binding.generator()).getOrThrow());
            entries.add(entry);
        }
        tag.put("charts", entries);
        return tag;
    }

    private static CubeStorageChart chartOf(ChunkGenerator generator) {
        if (generator instanceof PlanetChunkGenerator planet) { return planet.chart(); }
        if (generator instanceof EarthChunkGenerator earth && earth.chart().band() > 3) { return earth.chart(); }
        throw new IllegalArgumentException("Planet manifest contains a non-planetary or legacy storage generator");
    }
}
