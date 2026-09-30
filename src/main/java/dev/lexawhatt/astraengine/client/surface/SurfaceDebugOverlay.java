package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import dev.lexawhatt.astraengine.surface.SurfacePatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** Client-only F3 geographic presentation; retains no players, worlds, packets or mutable connection state. */
public final class SurfaceDebugOverlay {
    private final Map<ResourceLocation, SurfacePatch> patches;

    /**
     * Creates a client-owned subscriber for the fixed Moon/Earth patches and explicitly supplied extra dimensions.
     * Additional bindings are copied, must use valid namespaced identities, and cannot replace either v1 patch.
     * Register {@link #debugText} once on the NeoForge game event bus; no HUD or server subscriber is installed.
     */
    public SurfaceDebugOverlay(Map<String, SurfacePatch> additionalBindings) {
        if (additionalBindings == null) {
            throw new IllegalArgumentException("Additional surface debug bindings must not be null");
        }
        Map<ResourceLocation, SurfacePatch> bindings = new HashMap<>();
        for (String body : List.of("moon", "earth")) {
            bindings.put(ResourceLocation.fromNamespaceAndPath("astraengine", "surface_" + body),
                    SurfaceDefinition.byBody(body).patch());
        }
        additionalBindings.forEach((id, patch) -> {
            ResourceLocation dimension = id == null ? null : ResourceLocation.tryParse(id);
            if (dimension == null || id.indexOf(':') < 1 || patch == null
                    || bindings.putIfAbsent(dimension, patch) != null) {
                throw new IllegalArgumentException("Invalid or repeated surface debug dimension: " + id);
            }
        });
        patches = Map.copyOf(bindings);
    }

    /** Changes only the current bound world's primary position rows, on the client render thread. */
    public void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.showOnlyReducedInfo()) { return; }
        SurfacePatch patch = patches.get(minecraft.level.dimension().location());
        if (patch == null) { return; }
        SurfaceDebugCoordinates coordinates = SurfaceDebugCoordinates.fromFeet(patch,
                new SpaceVector(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()));
        SurfaceDebugCoordinates.replacePrimaryRows(event.getLeft(), List.of(
                I18n.get("astraengine.debug.longitude", coordinates.longitudeText()),
                I18n.get("astraengine.debug.latitude", coordinates.latitudeText()),
                I18n.get("astraengine.debug.altitude", coordinates.altitudeText())), false);
    }
}
