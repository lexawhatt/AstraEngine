package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.lang.reflect.Field;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Verification-only bridge to the existing renderer. The isolated verification module cannot share
 * production Java packages under JPMS; reflection here avoids a shipped test API or duplicate GPU owner.
 */
final class CelestialRendererProbe {
    private final CosmosRenderer renderer;

    CelestialRendererProbe(RocketController controller) {
        try {
            Field field = RocketController.class.getDeclaredField("renderer");
            field.setAccessible(true);
            Object value = field.get(controller);
            if (!(value instanceof CosmosRenderer cosmos)) {
                throw new IllegalStateException("The production Rocket renderer has an unexpected type");
            }
            renderer = cosmos;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Verification could not access the existing production CosmosRenderer", failure);
        }
    }

    /** Adds an explicitly controlled presentation draw through the actual production HDR/extraction path. */
    int draw(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters, double seconds, float exposure) {
        return draw(event, system, cameraMeters, seconds, exposure, SolarVisual.HEALTHY);
    }

    /** Uses a sampled authoritative solar presentation without adding a second simulation clock. */
    int draw(RenderLevelStageEvent event, CosmosSystem system, SpaceVector cameraMeters, double seconds, float exposure,
            SolarVisual solar) {
        renderer.setSolarVisual(solar);
        renderer.render(event, system, cameraMeters, seconds, 0, exposure);
        return renderer.bodyCount();
    }
}
