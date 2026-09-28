package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.AstraEngine;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Permanent first-slice world identities. These are independent worlds, never recyclable slots. */
public final class SystemWorlds {
    public static final List<String> SYSTEM_IDS = List.of("alpha", "beta");
    public static final ResourceKey<Level> TRANSIT = key("transit");

    private SystemWorlds() {}

    /** Resolves a supported system identity to its permanent dimension key. */
    public static ResourceKey<Level> dimension(String id) {
        if (!SYSTEM_IDS.contains(id)) {
            throw new IllegalArgumentException("Unknown system: " + id);
        }
        return key(id);
    }

    /** Returns the system hosted by a dimension; transit and vanilla worlds have no system identity. */
    public static Optional<String> systemId(ResourceKey<Level> dimension) {
        return SYSTEM_IDS.stream().filter(id -> dimension(id).equals(dimension)).findFirst();
    }

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(AstraEngine.MOD_ID, path));
    }
}
