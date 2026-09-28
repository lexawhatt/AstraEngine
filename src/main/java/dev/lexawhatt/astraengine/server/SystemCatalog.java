package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.ExtractionResult;
import dev.lexawhatt.astraengine.api.SystemDescriptor;
import dev.lexawhatt.astraengine.api.SystemSnapshot;
import dev.lexawhatt.astraengine.systems.StellarSystem;
import dev.lexawhatt.astraengine.systems.SystemGenerator;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** World-save-scoped catalog in Overworld storage; access and mutation require the server thread. */
public final class SystemCatalog extends SavedData {
    private static final String FILE_NAME = "astraengine_systems";
    private final Map<String, StellarSystem> systems = new LinkedHashMap<>();
    private final Set<String> initializedLandings = new HashSet<>();

    private SystemCatalog() {}

    /** Loads the catalog, refusing to replace existing unreadable data with a pristine system. */
    public static SystemCatalog get(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("AstraEngine catalog requires the server thread");
        }
        Factory<SystemCatalog> factory = new Factory<>(() -> {
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME + ".dat"))) {
                throw new IllegalStateException("Existing AstraEngine catalog could not be read; preserve it and restore a backup");
            }
            SystemCatalog catalog = new SystemCatalog();
            long worldSeed = server.overworld().getSeed();
            catalog.systems.put("alpha", new StellarSystem(SystemGenerator.generate("alpha", worldSeed ^ 0x41A57A11L)));
            catalog.systems.put("beta", new StellarSystem(SystemGenerator.generate("beta", worldSeed ^ 0x6B37A229L)));
            catalog.setDirty();
            return catalog;
        }, (tag, registries) -> decode(tag), null);
        return server.overworld().getDataStorage().computeIfAbsent(factory, FILE_NAME);
    }

    /** Resolves one of the persistent demonstration systems or rejects an unknown identity. */
    public StellarSystem system(String id) {
        StellarSystem system = systems.get(id);
        if (system == null) {
            throw new IllegalArgumentException("Unknown system: " + id);
        }
        return system;
    }

    /** Whether this permanent world's landing has already been initialized. */
    public boolean landingInitialized(String id) { return initializedLandings.contains(id); }

    /** Records one-time initialization; future arrivals must preserve edited blocks. */
    public void markLandingInitialized(String id) {
        initializedLandings.add(id);
        setDirty();
    }

    /** Decodes version-one persistent state; no fallback generation is allowed on invalid data. */
    public static SystemCatalog decode(CompoundTag root) {
        if (root.getInt("version") != 1 || !root.contains("systems", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Unsupported AstraEngine catalog format");
        }
        SystemCatalog catalog = new SystemCatalog();
        for (String id : List.of("alpha", "beta", "transit")) {
            if (root.getBoolean("landing_" + id)) { catalog.initializedLandings.add(id); }
        }
        ListTag entries = root.getList("systems", Tag.TAG_COMPOUND);
        if (entries.size() != 2) {
            throw new IllegalArgumentException("Catalog must contain both persistent systems");
        }
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            requireFields(entry, Tag.TAG_STRING, "id");
            requireFields(entry, Tag.TAG_INT, "generator_version", "temperature", "planets", "burst_timer");
            requireFields(entry, Tag.TAG_LONG, "seed", "capacity", "remaining", "active_ticks", "bursts", "revision");
            requireFields(entry, Tag.TAG_LIST, "receipts");
            SystemDescriptor descriptor = new SystemDescriptor(entry.getString("id"), entry.getLong("seed"),
                    entry.getInt("generator_version"), entry.getInt("temperature"), entry.getInt("planets"),
                    entry.getLong("capacity"));
            SystemSnapshot state = new SystemSnapshot(descriptor, entry.getLong("remaining"),
                    entry.getLong("active_ticks"), entry.getInt("burst_timer"), entry.getLong("bursts"),
                    entry.getLong("revision"));
            ListTag encodedReceipts = entry.getList("receipts", Tag.TAG_COMPOUND);
            if (((ListTag) entry.get("receipts")).size() != encodedReceipts.size()) {
                throw new IllegalArgumentException("Invalid saved receipt list type");
            }
            if (encodedReceipts.size() > StellarSystem.MAX_RECEIPTS) {
                throw new IllegalArgumentException("Saved receipt ledger exceeds its limit");
            }
            List<ExtractionResult> receipts = new ArrayList<>();
            for (int j = 0; j < encodedReceipts.size(); j++) {
                CompoundTag receipt = encodedReceipts.getCompound(j);
                requireFields(receipt, Tag.TAG_LONG, "requested", "extracted", "revision");
                receipts.add(new ExtractionResult(receipt.getUUID("operation"), receipt.getLong("requested"),
                        receipt.getLong("extracted"), receipt.getLong("revision"), ExtractionResult.Status.APPLIED));
            }
            if (catalog.systems.putIfAbsent(descriptor.id(), StellarSystem.restore(state, receipts)) != null) {
                throw new IllegalArgumentException("Duplicate saved system identity");
            }
        }
        if (!catalog.systems.keySet().equals(Set.of("alpha", "beta"))) {
            throw new IllegalArgumentException("Unexpected saved system identities");
        }
        return catalog;
    }

    private static void requireFields(CompoundTag tag, int type, String... names) {
        for (String name : names) {
            if (!tag.contains(name, type)) {
                throw new IllegalArgumentException("Missing or invalid saved system field: " + name);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("version", 1);
        initializedLandings.forEach(id -> root.putBoolean("landing_" + id, true));
        ListTag entries = new ListTag();
        for (StellarSystem system : systems.values()) {
            SystemSnapshot state = system.snapshot();
            SystemDescriptor descriptor = state.descriptor();
            CompoundTag entry = new CompoundTag();
            entry.putString("id", descriptor.id());
            entry.putLong("seed", descriptor.seed());
            entry.putInt("generator_version", descriptor.generatorVersion());
            entry.putInt("temperature", descriptor.temperatureKelvin());
            entry.putInt("planets", descriptor.planetCount());
            entry.putLong("capacity", descriptor.resourceCapacity());
            entry.putLong("remaining", state.remainingResource());
            entry.putLong("active_ticks", state.activeTicks());
            entry.putInt("burst_timer", state.ticksUntilBurst());
            entry.putLong("bursts", state.burstCount());
            entry.putLong("revision", state.revision());
            ListTag receipts = new ListTag();
            for (ExtractionResult result : system.receipts()) {
                CompoundTag receipt = new CompoundTag();
                receipt.putUUID("operation", result.operationId());
                receipt.putLong("requested", result.requested());
                receipt.putLong("extracted", result.extracted());
                receipt.putLong("revision", result.revision());
                receipts.add(receipt);
            }
            entry.put("receipts", receipts);
            entries.add(entry);
        }
        root.put("systems", entries);
        return root;
    }
}
