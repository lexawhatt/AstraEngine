package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

/** Shared shader geometry: full part size scales the primitive; color is linear RGB in [0, 4]. */
public record RocketVisual(RocketGeometryKind geometry, double bottomRadiusRatio, double topRadiusRatio,
                           SpaceVector color, RocketMaterialStyle material) {
    public RocketVisual {
        if (geometry == null || color == null || material == null
                || !Double.isFinite(bottomRadiusRatio) || !Double.isFinite(topRadiusRatio)
                || bottomRadiusRatio <= 0 || bottomRadiusRatio > 1 || topRadiusRatio <= 0 || topRadiusRatio > 1
                || Math.max(bottomRadiusRatio, topRadiusRatio) != 1
                || color.x() < 0 || color.x() > 4 || color.y() < 0 || color.y() > 4
                || color.z() < 0 || color.z() > 4) {
            throw new IllegalArgumentException(
                    "Rocket visual requires valid geometry, radius ratios, color, and material");
        }
        if (geometry != RocketGeometryKind.FRUSTUM && (bottomRadiusRatio != 1 || topRadiusRatio != 1)) {
            throw new IllegalArgumentException("Only frustums can narrow their endpoint radius");
        }
    }
}
