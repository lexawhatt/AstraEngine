package dev.lexawhatt.astraengine.verification;

import dev.lexawhatt.astraengine.client.flight.RocketController;
import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.client.render.FullscreenPass;
import dev.lexawhatt.astraengine.client.solar.SolarVisual;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.lang.reflect.Field;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

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

    /** Inspects the real cache allocation; this bridge adds no production API or alternate resource owner. */
    Object requireHeightCache(boolean allocated) {
        try {
            Field owner = CosmosRenderer.class.getDeclaredField("earthHeights");
            owner.setAccessible(true);
            Object cache = owner.get(renderer);
            Field textureField = cache.getClass().getDeclaredField("textures");
            textureField.setAccessible(true);
            int[] textures = (int[]) textureField.get(cache);
            Field gridField = cache.getClass().getDeclaredField("grid");
            gridField.setAccessible(true);
            Object grid = gridField.get(cache);
            if ((grid != null) != allocated) { throw new IllegalStateException("Unexpected Earth cache readiness"); }
            try (FullscreenPass state = new FullscreenPass()) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0);
                for (int texture : textures) {
                    if (!allocated) {
                        if (texture != 0) { throw new IllegalStateException("Distant Earth retained an owned height texture"); }
                        continue;
                    }
                    if (!GL11.glIsTexture(texture)) { throw new IllegalStateException("Height cache texture is not live"); }
                    RenderSystem.bindTexture(texture);
                    if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 513
                            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 513
                            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != GL30.GL_RGBA32F) {
                        throw new IllegalStateException("Height cache allocation has an unexpected layout");
                    }
                }
            }
            return grid;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not inspect production height cache", failure);
        }
    }
}
