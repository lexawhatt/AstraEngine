package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.ContinentalRegion;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** Geographic F3 coordinates for physical-altitude windows; owns no world or connection state. */
public final class ContinentalDebugOverlay {
    /** Replaces primary position rows on the render thread, preserving reduced-debug privacy. */
    public void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.showOnlyReducedInfo()) { return; }
        ContinentalRegion region = ContinentalRegion.forDimension(minecraft.level.dimension().location().toString())
                .orElse(null);
        if (region == null) { return; }
        SurfaceDebugCoordinates coordinates = SurfaceDebugCoordinates.fromFeet(region.patch(),
                new SpaceVector(minecraft.player.getX(),
                        minecraft.player.getY() + region.altitudeOriginMeters(), minecraft.player.getZ()));
        SurfaceDebugCoordinates.replacePrimaryRows(event.getLeft(), List.of(
                I18n.get("astraengine.debug.longitude", coordinates.longitudeText()),
                I18n.get("astraengine.debug.latitude", coordinates.latitudeText()),
                I18n.get("astraengine.debug.altitude", coordinates.altitudeText())), false);
    }
}
