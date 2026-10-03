package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.client.sky.SkyIllumination;
import dev.lexawhatt.astraengine.client.solar.SolarStateClient;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Client-thread observations of synchronized solid-body frames; this service never advances a simulation clock. */
public final class PlanetSkyState {
    private final PlanetStateClient planets;
    private final SurfaceStateClient surface;
    private final SolarStateClient solar;
    private final Function<String, CosmosSystem> systems;

    public PlanetSkyState(PlanetStateClient planets, SurfaceStateClient surface, SolarStateClient solar,
            Function<String, CosmosSystem> systems) {
        if (planets == null || surface == null || solar == null || systems == null) {
            throw new IllegalArgumentException("Planet sky requires connection-owned snapshot services");
        }
        this.planets = planets; this.surface = surface; this.solar = solar; this.systems = systems;
    }

    /** Null for an unbound dimension. All vectors are host chart tangent directions and display light colors. */
    public Observation sample(ClientLevel level, Vec3 camera) {
        if (level == null || camera == null) { return null; }
        var chart = planets.chart(level.dimension().location().toString()).orElse(null);
        if (chart == null) { return null; }
        var profile = chart.profile();
        var system = systems.apply(profile.systemId());
        if (system == null) { return null; }
        double seconds = surface.orbitalSeconds();
        var frame = profile.frame(system, seconds);
        SpaceVector source = null;
        double largest = 0;
        for (var body : system.bodies()) {
            if (body.kind() != CelestialBody.Kind.STAR) { continue; }
            var direction = system.positionAt(body, seconds).subtract(frame.centerMeters());
            double angular = body.radiusMeters() / Math.max(1, direction.length());
            if (angular > largest) { largest = angular; source = direction.normalized(); }
        }
        double altitude = camera.y + chart.altitudeOriginMeters();
        if (profile.radiusMeters() + altitude <= 0) { return null; }
        var tangent = chart.tangentFrame(camera.x, camera.z, altitude);
        SpaceVector sun = source == null ? new SpaceVector(0, -1, 0)
                : tangent.toLocalDirection(frame.toBodyDirection(source));
        double incident = source == null ? 0 : system.id().equals("sol")
                ? Math.max(0, solar.visual().luminosity() + solar.visual().flash() * .6) : 1;
        double density = profile.atmosphereDensity(altitude);
        double direct = Math.clamp(sun.y() * 4 + .015, 0, 1) * incident;
        var airless = new SpaceVector(direct, direct, direct);
        var atmospheric = SkyIllumination.skyLight(sun.y(), 0, 0, (float) incident);
        var light = airless.multiply(1 - density).add(atmospheric.multiply(density));
        return new Observation(chart, sun, light, density, incident);
    }

    /** Immutable per-view observation; no host or descriptor-cache references escape. */
    public record Observation(PlanetChart chart, SpaceVector sunDirection, SpaceVector lightColor,
                              double atmosphereDensity, double incident) { }
}
