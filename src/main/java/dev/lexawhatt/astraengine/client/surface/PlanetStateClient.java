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
    private Map<String, Map<String, SolidPlanetProfile>> profiles = Map.of();

    public void receive(PlanetContextReceivedEvent event) {
        var payload = event.payload();
        if (payload.revision() < revision) { return; }
        var replacement = new HashMap<String, PlanetChart>();
        for (var chart : payload.charts()) { replacement.put(chart.dimensionId(), chart); }
        if (payload.revision() == revision && !charts.equals(replacement)) {
            throw new IllegalArgumentException("Same planetary context revision changed its immutable chart definitions");
        }
        var nextProfiles = new HashMap<String, Map<String, SolidPlanetProfile>>();
        for (var chart : replacement.values()) {
            var profile = chart.profile();
            nextProfiles.computeIfAbsent(profile.systemId(), ignored -> new HashMap<>()).put(profile.bodyId(), profile);
        }
        nextProfiles.replaceAll((system, bodies) -> Map.copyOf(bodies));
        profiles = Map.copyOf(nextProfiles); charts = Map.copyOf(replacement); revision = payload.revision();
    }
    public Optional<PlanetChart> chart(String dimensionId) { return Optional.ofNullable(charts.get(dimensionId)); }
    public Optional<SolidPlanetProfile> profile(String systemId, String bodyId) {
        var bodies = profiles.get(systemId);
        return bodies == null ? Optional.empty() : Optional.ofNullable(bodies.get(bodyId));
    }
    public void clear() { charts = Map.of(); profiles = Map.of(); revision = -1; }
}
