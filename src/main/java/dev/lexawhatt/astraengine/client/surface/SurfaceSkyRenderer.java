package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.client.render.CosmosRenderer;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.FlightOrientation;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.SurfaceDefinition;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Planet-fixed view adapter. The existing cosmos renderer remains the sole shader/HDR resource owner. */
public final class SurfaceSkyRenderer {
    private SurfaceSkyRenderer() { }

    /** Converts actual camera block meters into the same rotating body frame used by virtual approach. */
    public static void render(RenderLevelStageEvent event, CosmosRenderer renderer, CosmosSystem system,
                              SurfaceDefinition definition, double clockTicks, double orbitalSeconds,
                              FlightOrientation earthOrientation, float exposure) {
        if (event.getCamera().getFluidInCamera() != FogType.NONE
                || event.getCamera().getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) { return; }
        var camera = event.getCamera().getPosition();
        var patch = definition.patch();
        var frame = earthOrientation == null ? definition.frame(system, orbitalSeconds, clockTicks)
                : definition.calendarFrame(system, orbitalSeconds, earthOrientation);
        SpaceVector observer = frame.toSystemPoint(patch.toBody(new SpaceVector(camera.x, camera.y, camera.z)));
        renderer.renderSurface(event, system, observer, clockTicks / 20, orbitalSeconds, earthOrientation, exposure,
                frame.toSystemOrientation(patch.orientationAt(camera.x, camera.z)));
    }
}
