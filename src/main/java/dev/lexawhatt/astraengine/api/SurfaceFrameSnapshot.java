package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.surface.PlanetaryFrame;
import dev.lexawhatt.astraengine.surface.PlanetaryPose;
import dev.lexawhatt.astraengine.surface.PlanetaryTile;
import dev.lexawhatt.astraengine.surface.PlanetaryTopology;
import dev.lexawhatt.astraengine.surface.GeographicPosition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Immutable instantaneous surface observation. Contains identifiers and double-meter values, not worlds or players.
 * Safe to retain or use on workers after sampling, but no longer describes later movement. It authorizes no edits,
 * discovery, teleport or storage migration. The body's coordinate axes and terrain definition belong to geographyId.
 */
public record SurfaceFrameSnapshot(ResourceLocation geographyId, ResourceKey<Level> dimension,
        PlanetaryTopology topology, PlanetaryPose pose) {
    public SurfaceFrameSnapshot {
        if (geographyId == null || dimension == null || topology == null || pose == null) {
            throw new IllegalArgumentException("A surface observation requires geography, world, topology and pose");
        }
    }

    /** Canonical logical tile containing the represented body-fixed feet position; not a host chunk address. */
    public PlanetaryTile tile() { return topology.locate(pose.bodyPositionMeters()); }

    /** Stable reference frame at the current tile center; changing this frame does not move the body-fixed pose. */
    public PlanetaryFrame frame() { return topology.frame(tile(), 0.5, 0.5); }

    /** Complete pose in the tile's tangent frame. These local values are not Minecraft block coordinates. */
    public PlanetaryPose.LocalPose localPose() { return pose.inFrame(frame()); }

    /** Feet latitude/longitude in radians and radial altitude in meters above this geography's reference sphere. */
    public GeographicPosition geographicPosition() {
        return GeographicPosition.fromBody(pose.bodyPositionMeters(), topology.radiusMeters());
    }
}
