package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.network.EarthContextReceivedEvent;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.GeographicReference;
import dev.lexawhatt.astraengine.surface.SurfaceReferences;
import java.util.Optional;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Client-main-thread connection owner. Resource reload retains geography; disconnect discards it. */
public final class EarthStateClient {
    private boolean active;

    /** Receives validated server context. Client presentation never infers Earth from a dimension name alone. */
    public void receive(EarthContextReceivedEvent event) { active = event.payload().version() == EarthChart.VERSION; }

    /** True only after this connection's server explicitly binds the new Earth preset. */
    public boolean active() { return active; }

    /** Immutable projection for the current connection, or absence for an unsupported world. */
    public Optional<GeographicReference> reference(String dimensionId) {
        if (active) {
            var chart = EarthChart.ALL.stream().filter(value -> value.dimensionId().equals(dimensionId)).findFirst();
            if (chart.isPresent()) { return chart.map(value -> value); }
        }
        return SurfaceReferences.forDimension(dimensionId).map(value -> value);
    }

    /** Drops server context, including when the next connection uses a vanilla or legacy Overworld. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) { active = false; }
}
