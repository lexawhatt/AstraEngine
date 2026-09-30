package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Permanent built-in surface identities, independent of loaded chunks and travel sessions. */
public final class SurfaceWorlds {
    private SurfaceWorlds() {}

    /** Returns the permanent world key for this versioned geographic patch. */
    public static ResourceKey<Level> dimension(SurfaceDefinition definition) {
        if (definition == null) { throw new IllegalArgumentException("Surface definition is required"); }
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("astraengine", "surface_" + definition.bodyId()));
    }

    /** Resolves only the fixed built-in worlds; ordinary Overworld is never rebound. */
    public static Optional<SurfaceDefinition> definition(ResourceKey<Level> dimension) {
        if (dimension == null) { return Optional.empty(); }
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            if (dimension(definition).equals(dimension)) { return Optional.of(definition); }
        }
        return Optional.empty();
    }

    /** Reasserts bounded borders after host Overworld border delegation. Requires the server thread. */
    public static void maintainBorders(MinecraftServer server) {
        PlanetaryTerrainWorld.maintainBorder(server);
        for (String body : new String[]{"moon", "earth"}) {
            SurfaceDefinition definition = SurfaceDefinition.byBody(body);
            ServerLevel level = server.getLevel(dimension(definition));
            if (level == null) { continue; }
            var border = level.getWorldBorder();
            double size = definition.patch().halfWidth() * 2 - 16;
            if (border.getCenterX() != 0 || border.getCenterZ() != 0) { border.setCenter(0, 0); }
            if (border.getSize() != size || border.getLerpTarget() != size) { border.setSize(size); }
        }
    }
}
