package dev.lexawhatt.astraengine.client.environment;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.lexawhatt.astraengine.AstraEngine;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Validates profiles off-thread and atomically publishes them; bad packs preserve the last good profiles. */
public final class EnvironmentProfiles extends SimplePreparableReloadListener<Map<ResourceLocation, EnvironmentProfile>> {
    private Map<ResourceLocation, EnvironmentProfile> profiles = Map.of(id("space"), EnvironmentProfile.SPACE,
            id("planet"), EnvironmentProfile.PLANET);

    /** Bare names resolve in astraengine; other mods and packs may supply their own namespace. */
    public static ResourceLocation id(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : AstraEngine.MOD_ID + ":" + name);
        if (id == null) { throw new IllegalArgumentException("Invalid rendering profile identity: " + name); }
        return id;
    }

    /** Resolves a profile or returns null when the named resource does not exist. */
    public EnvironmentProfile get(String name) { return profiles.get(id(name)); }

    /** Current immutable IDs, for client command suggestions. */
    public Set<ResourceLocation> ids() { return profiles.keySet(); }

    /** Auto mode opts a dimension in only when it has an explicit matching profile, or is an Astra system. */
    public EnvironmentProfile resolve(String selection, ResourceLocation dimension, boolean hasSystemSnapshot) {
        if (selection.equals("off")) { return null; }
        if (!selection.equals("auto")) { return get(selection); }
        EnvironmentProfile mapped = profiles.get(dimension);
        return mapped != null ? mapped : hasSystemSnapshot ? get("space") : null;
    }

    @Override
    protected Map<ResourceLocation, EnvironmentProfile> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, EnvironmentProfile> prepared = new HashMap<>();
        prepared.put(id("space"), EnvironmentProfile.SPACE);
        prepared.put(id("planet"), EnvironmentProfile.PLANET);
        try {
            var resources = manager.listResources("environments", name -> name.getPath().endsWith(".json"));
            if (resources.size() > 256) { throw new IllegalArgumentException("Environment profile limit is 256"); }
            for (var entry : resources.entrySet()) {
                String path = entry.getKey().getPath();
                ResourceLocation name = ResourceLocation.fromNamespaceAndPath(entry.getKey().getNamespace(),
                        path.substring("environments/".length(), path.length() - ".json".length()));
                try (Reader reader = entry.getValue().openAsReader()) {
                    prepared.put(name, EnvironmentProfile.parse(JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
            return Map.copyOf(prepared);
        } catch (IOException | IllegalArgumentException | IllegalStateException | JsonParseException exception) {
            AstraEngine.LOGGER.error("Invalid AstraEngine environment resources; keeping previous profiles", exception);
            return Map.of();
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, EnvironmentProfile> prepared, ResourceManager manager, ProfilerFiller profiler) {
        if (!prepared.isEmpty()) { profiles = prepared; }
    }
}
