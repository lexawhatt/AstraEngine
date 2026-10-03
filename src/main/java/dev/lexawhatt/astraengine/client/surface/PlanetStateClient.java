package dev.lexawhatt.astraengine.client.surface;

import dev.lexawhatt.astraengine.network.PlanetContextReceivedEvent;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Client-main-thread connection registry. Resource reload retains context; explicit logout clears it. */
public final class PlanetStateClient {
    private long revision = -1;
    private Map<String, PlanetChart> charts = Map.of();

    public void receive(PlanetContextReceivedEvent event) {
        var payload = event.payload();
        if (payload.revision() < revision) { return; }
        var replacement = new HashMap<String, PlanetChart>();
        for (var chart : payload.charts()) { replacement.put(chart.dimensionId(), chart); }
        if (payload.revision() == revision && !charts.equals(replacement)) {
            throw new IllegalArgumentException("Same planetary context revision changed its immutable chart definitions");
        }
        charts = Map.copyOf(replacement); revision = payload.revision();
    }
    public Optional<PlanetChart> chart(String dimensionId) { return Optional.ofNullable(charts.get(dimensionId)); }
    public Optional<SolidPlanetProfile> profile(String systemId, String bodyId) {
        return charts.values().stream().map(PlanetChart::profile)
                .filter(profile -> profile.systemId().equals(systemId) && profile.bodyId().equals(bodyId)).findFirst();
    }
    public void clear() { charts = Map.of(); revision = -1; }
}
