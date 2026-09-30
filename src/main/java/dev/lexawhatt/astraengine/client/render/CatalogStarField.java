package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosGenerator;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.cosmos.UniverseGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Connection-owned neighborhood cache; extraction is presentation-only and grants no discoveries. */
final class CatalogStarField {
    static final int LIMIT = 24;
    record Star(String systemId, SpaceVector direction, SpaceVector color, float intensity) {
    }

    private record Cell(long seed, int galaxy, long x, long y, long z) {
    }

    private Cell cell;
    private List<CosmosSystem> systems = List.of();

    /** Reuses at most 27 immutable descriptors until the camera crosses a four-light-year sector boundary. */
    List<Star> extract(long seed, CosmosSystem current, SpaceVector observer, List<GalaxyDescriptor> galaxies) {
        // Catalog selection depends on absolute position, never the current navigation origin.
        double solDistance = observer.length();
        boolean legacy = solDistance < 64;
        double catalogGain = Math.clamp(Math.abs(solDistance - 64) / 4, 0, 1);
        catalogGain = catalogGain * catalogGain * (3 - 2 * catalogGain);
        GalaxyDescriptor host = galaxies.stream().min(Comparator.comparingDouble(galaxy ->
                galaxy.toLocalLightYears(observer).length() / galaxy.radiusLightYears())).orElseThrow();
        SpaceVector point = legacy ? observer : host.toLocalLightYears(observer);
        if (!legacy && UniverseGenerator.populationDensity(seed, host.index(), observer) <= 1e-7) {
            clear();
            return List.of();
        }
        Cell next = new Cell(seed, legacy ? -1 : host.index(), sector(point.x()), sector(point.y()), sector(point.z()));
        if (!next.equals(cell)) {
            systems = legacy ? CosmosGenerator.nearby(seed, observer, 1)
                    : UniverseGenerator.nearby(seed, host.index(), observer, 1);
            cell = next;
        }
        List<Star> result = new ArrayList<>();
        for (CosmosSystem system : systems) {
            if (system.id().equals(current.id())) { continue; }
            SpaceVector relative = system.galaxyPosition().subtract(observer);
            double distance = relative.length();
            if (distance <= 1e-9 || distance >= 4) { continue; }
            CelestialBody primary = system.bodies().stream().filter(body -> body.kind() == CelestialBody.Kind.STAR)
                    .findFirst().orElse(null);
            if (primary == null) { continue; }
            double fade = Math.clamp((4 - distance) / 1.5, 0, 1);
            fade = fade * fade * (3 - 2 * fade);
            double brightness = Math.clamp(primary.radiusMeters() / 696_340_000, 0.15, 5);
            float intensity = (float) Math.min(20, catalogGain * fade * brightness * 5 / Math.max(0.2, distance * distance));
            result.add(new Star(system.id(), relative.multiply(1 / distance), primary.color(), intensity));
        }
        result.sort(Comparator.comparingDouble(Star::intensity).reversed().thenComparing(Star::systemId));
        return List.copyOf(result.subList(0, Math.min(LIMIT, result.size())));
    }

    void clear() {
        cell = null;
        systems = List.of();
    }

    private static long sector(double position) {
        return (long) Math.floor(position / UniverseGenerator.SECTOR_LIGHT_YEARS + 0.5);
    }
}
