package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** Stateless client F3 presentation of the same fixed geographic references used by the logical server. */
public final class SurfaceDebugOverlay {
    private final EarthStateClient earth;

    /** Uses the existing connection owner; this overlay does not retain worlds or infer server bindings. */
    public SurfaceDebugOverlay(EarthStateClient earth) {
        if (earth == null) { throw new IllegalArgumentException("Geographic overlay requires connection context"); }
        this.earth = earth;
    }

    /** Changes primary coordinate rows only in a bound playable window; reduced debug reveals no location. */
    public void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.showOnlyReducedInfo()) { return; }
        var reference = earth.reference(minecraft.level.dimension().location().toString()).orElse(null);
        SpaceVector feet = new SpaceVector(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
        if (reference == null || !reference.contains(feet)
                || !minecraft.level.getWorldBorder().isWithinBounds(feet.x(), feet.z())) { return; }
        var geographic = reference.geographic(feet);
        var coordinates = SurfaceDebugCoordinates.fromGeographic(geographic);
        boolean replaced = SurfaceDebugCoordinates.replacePrimaryRows(event.getLeft(), List.of(
                I18n.get("astraengine.debug.longitude", coordinates.longitudeText()),
                I18n.get("astraengine.debug.latitude", coordinates.latitudeText()),
                I18n.get("astraengine.debug.altitude", coordinates.altitudeText())), false);
        if (replaced) {
            var tile = reference.topology().locate(geographic.normal());
            event.getLeft().add(I18n.get("astraengine.debug.geography", reference.geographyId()));
            event.getLeft().add(I18n.get("astraengine.debug.geographic_tile", tile.key()));
        }
    }
}
