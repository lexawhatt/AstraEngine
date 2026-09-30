package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.worldgen.SurfaceChunkGenerator;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Save-owned identity manifest. An incompatible definition fails closed instead of regenerating visited storage. */
public final class SurfaceBindings extends SavedData {
    private static final String FILE_NAME = "astraengine_surfaces";

    private SurfaceBindings() {}

    /** Loads or initializes immutable bindings on the owning logical-server thread. */
    public static SurfaceBindings get(MinecraftServer server) {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Surface bindings require the owning server thread");
        }
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(() -> {
            if (Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME + ".dat"))) {
                throw new IllegalStateException("Existing surface bindings are unreadable; restore a backup");
            }
            SurfaceBindings bindings = new SurfaceBindings();
            bindings.setDirty();
            return bindings;
        }, (tag, registries) -> decode(tag), null), FILE_NAME);
    }

    /** Rejects missing, changed, or unsupported persisted geographic identities before handoff. */
    public static SurfaceBindings decode(CompoundTag tag) {
        if (tag == null || !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1) {
            throw new IllegalArgumentException("Unsupported surface binding format");
        }
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            CompoundTag binding = tag.getCompound(body);
            if (!binding.contains("version", Tag.TAG_INT) || !binding.contains("seed", Tag.TAG_LONG)
                    || !binding.contains("dimension", Tag.TAG_STRING) || !binding.contains("system", Tag.TAG_STRING)
                    || binding.getInt("version") != definition.version() || binding.getLong("seed") != definition.seed()
                    || !binding.getString("system").equals(definition.systemId())
                    || !binding.getString("dimension").equals(SurfaceWorlds.dimension(definition).location().toString())) {
                throw new IllegalArgumentException("Saved surface binding differs from the pinned definition: " + body);
            }
        }
        return new SurfaceBindings();
    }

    /** Checks the loaded host generator, preventing a changed datapack from silently accepting an old binding. */
    public boolean matches(ServerLevel level, SurfaceDefinition definition) {
        return level != null && level.dimension().equals(SurfaceWorlds.dimension(definition))
                && level.getChunkSource().getGenerator() instanceof SurfaceChunkGenerator generator
                && generator.definition().equals(definition);
    }

    /** Rejects changed datapack bindings at server start, before this engine can admit surface observers. */
    public void validate(MinecraftServer server) {
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            ServerLevel level = server.getLevel(SurfaceWorlds.dimension(definition));
            // Hosts such as vanilla GameTestServer can deliberately omit datapack dimensions.
            // Missing worlds disable arrival; an existing world with a changed generator must fail closed.
            if (level != null && !matches(level, definition)) {
                throw new IllegalStateException("Loaded surface world differs from its permanent binding: " + body);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            CompoundTag binding = new CompoundTag();
            binding.putInt("version", definition.version()); binding.putLong("seed", definition.seed());
            binding.putString("dimension", SurfaceWorlds.dimension(definition).location().toString());
            binding.putString("system", definition.systemId()); tag.put(body, binding);
        }
        return tag;
    }
}
