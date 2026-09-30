package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.PlanetaryTerrain;
import dev.lexawhatt.astraengine.surface.PlanetaryTile;
import dev.lexawhatt.astraengine.surface.PlanetaryTopology;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Overworld-save-owned identity manifest for the independent highlands geography.
 * Access requires the logical server thread; immutable topology values can be used by workers.
 * This pins addresses, not voxel storage: no dimensions, chunks or runtime tile slots are allocated.
 */
public final class PlanetaryGeographyState extends SavedData {
    public static final String GEOGRAPHY_ID = "astraengine:highlands";
    public static final String FILE_NAME = "astraengine_geography";
    private static final int FORMAT = 1;
    private static final PlanetaryTopology TOPOLOGY = new PlanetaryTopology(1, 12,
            PlanetaryTerrain.PATCH.radiusMeters());

    private PlanetaryGeographyState() {}

    /**
     * Loads or creates the pinned manifest. Existing unreadable data, including an orphaned backup,
     * rejects initialization instead of silently assigning new geographic ownership to an old save.
     */
    public static PlanetaryGeographyState get(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Planetary geography requires the owning server thread");
        }
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(() -> {
            var directory = server.getWorldPath(LevelResource.ROOT).resolve("data");
            if (Files.exists(directory.resolve(FILE_NAME + ".dat"))
                    || Files.exists(directory.resolve(FILE_NAME + ".dat_old"))) {
                throw new IllegalStateException("Existing planetary geography is unreadable; preserve it and restore a backup");
            }
            PlanetaryGeographyState state = new PlanetaryGeographyState();
            state.setDirty();
            return state;
        }, (tag, registries) -> decode(tag), null), FILE_NAME);
    }

    /** Immutable spherical address/frame contract; no host-world or GPU references are retained. */
    public PlanetaryTopology topology() { return TOPOLOGY; }

    /**
     * Canonical qualified address for future data ownership, independent of loaded worlds and tile neighbors.
     * The returned value is an identifier, not a current file path or a promise of generated block data.
     * Rejects a null tile or one belonging to a different topology version or resolution.
     */
    public String tileKey(PlanetaryTile tile) {
        if (tile == null || tile.version() != TOPOLOGY.version() || tile.level() != TOPOLOGY.level()) {
            throw new IllegalArgumentException("Tile does not belong to the pinned highlands topology");
        }
        return GEOGRAPHY_ID + "/" + tile.key();
    }

    /** Strict version-one metadata decoding; rejects wrong NBT types and changed identity before accepting state. */
    public static PlanetaryGeographyState decode(CompoundTag tag) {
        if (tag == null) { throw new IllegalArgumentException("Planetary geography data is required"); }
        require(tag, Tag.TAG_INT, "version", "topology_version", "level", "terrain_version");
        require(tag, Tag.TAG_LONG, "terrain_seed");
        require(tag, Tag.TAG_DOUBLE, "radius_meters");
        require(tag, Tag.TAG_STRING, "geography", "dimension");
        if (tag.getInt("version") != FORMAT || tag.getInt("topology_version") != TOPOLOGY.version()
                || tag.getInt("level") != TOPOLOGY.level() || tag.getDouble("radius_meters") != TOPOLOGY.radiusMeters()
                || tag.getInt("terrain_version") != PlanetaryTerrain.VERSION
                || tag.getLong("terrain_seed") != PlanetaryTerrain.SEED
                || !tag.getString("geography").equals(GEOGRAPHY_ID)
                || !tag.getString("dimension").equals(PlanetaryTerrain.DIMENSION_ID)) {
            throw new IllegalArgumentException("Saved planetary geography differs from its permanent definition");
        }
        return new PlanetaryGeographyState();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", FORMAT);
        tag.putString("geography", GEOGRAPHY_ID);
        tag.putString("dimension", PlanetaryTerrain.DIMENSION_ID);
        tag.putInt("topology_version", TOPOLOGY.version());
        tag.putInt("level", TOPOLOGY.level());
        tag.putDouble("radius_meters", TOPOLOGY.radiusMeters());
        tag.putInt("terrain_version", PlanetaryTerrain.VERSION);
        tag.putLong("terrain_seed", PlanetaryTerrain.SEED);
        return tag;
    }

    private static void require(CompoundTag tag, int type, String... keys) {
        for (String key : keys) {
            if (!tag.contains(key, type)) {
                throw new IllegalArgumentException("Missing or invalid planetary geography field: " + key);
            }
        }
    }
}
