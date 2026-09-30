package dev.lexawhatt.astraengine.client.render;

import dev.lexawhatt.astraengine.cosmos.CosmicRegion;
import dev.lexawhatt.astraengine.cosmos.GalaxyDescriptor;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable, bounded camera-relative atlas extraction. It never changes discovery, navigation or catalog content. */
record UniverseFrame(List<Galaxy> galaxies, List<Region> regions) {
    static final int MAX_GALAXIES = 9;
    static final int MAX_REGIONS = 24;

    UniverseFrame {
        galaxies = List.copyOf(galaxies);
        regions = List.copyOf(regions);
    }

    /** Stable association between an immutable region and the galaxy defining its local axes. */
    record RegionSource(GalaxyDescriptor galaxy, CosmicRegion descriptor) {
    }

    /** Unit axes remain in universe space; observer coordinates use this galaxy's physical radius. */
    record Galaxy(GalaxyDescriptor descriptor, SpaceVector observerRadii, float seed) {
    }

    /** Region coordinates use its physical radius and host galaxy axes; visibility fades before budget eviction. */
    record Region(CosmicRegion descriptor, SpaceVector observerRadii, int galaxySlot, float seed, float visibility) {
    }

    private record Candidate(RegionSource source, SpaceVector observerRadii, double importance) {
    }

    /** Subtracts double light-year positions before GPU narrowing; quality 0/1/2 selects 12/18/24 regions. */
    static UniverseFrame extract(List<GalaxyDescriptor> definitions, List<RegionSource> sources,
            SpaceVector observerLightYears, int quality) {
        if (definitions == null || sources == null || observerLightYears == null
                || definitions.size() > MAX_GALAXIES || quality < 0 || quality > 2) {
            throw new IllegalArgumentException("Invalid universe frame extraction inputs");
        }
        List<Galaxy> galaxies = new ArrayList<>(definitions.size());
        for (GalaxyDescriptor descriptor : definitions) {
            if (descriptor == null) {
                throw new IllegalArgumentException("Galaxy definitions must not contain null");
            }
            SpaceVector observer = descriptor.toLocalLightYears(observerLightYears)
                    .multiply(1.0 / descriptor.radiusLightYears());
            galaxies.add(new Galaxy(descriptor, observer, shaderSeed(descriptor.seed())));
        }
        galaxies.sort(Comparator.comparingDouble((Galaxy frame) -> frame.descriptor().centerLightYears()
                .distance(observerLightYears)).reversed().thenComparing(frame -> frame.descriptor().id()));
        Map<Integer, Integer> slots = new HashMap<>();
        for (int index = 0; index < galaxies.size(); index++) {
            if (slots.put(galaxies.get(index).descriptor().index(), index) != null) {
                throw new IllegalArgumentException("Duplicate galaxy identity in frame");
            }
        }
        List<Candidate> candidates = new ArrayList<>(sources.size());
        for (RegionSource source : sources) {
            if (source == null || source.galaxy() == null || source.descriptor() == null
                    || !slots.containsKey(source.galaxy().index())) {
                throw new IllegalArgumentException("Regions require a galaxy present in the frame");
            }
            CosmicRegion region = source.descriptor();
            if (region.strength() <= 0) {
                continue;
            }
            SpaceVector relative = observerLightYears.subtract(region.centerLightYears());
            SpaceVector local = new SpaceVector(relative.dot(source.galaxy().orientation().left()),
                    relative.dot(source.galaxy().orientation().up()), relative.dot(source.galaxy().orientation().forward()))
                    .multiply(1.0 / region.radiusLightYears());
            double importance = 1.0 / Math.max(0.2, local.length());
            if (region.kind() == CosmicRegion.Kind.QUASAR) {
                importance *= 4;
            }
            // Below this smooth optical footprint, individual named structures are unresolved.
            if (importance > 1e-7) {
                candidates.add(new Candidate(source, local, importance));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::importance).reversed()
                .thenComparing(candidate -> candidate.source().descriptor().systemId()));
        int limit = 12 + quality * 6;
        double cutoff = candidates.size() > limit ? candidates.get(limit).importance() : 0;
        List<Region> regions = new ArrayList<>(Math.min(limit, candidates.size()));
        for (int index = 0; index < Math.min(limit, candidates.size()); index++) {
            Candidate candidate = candidates.get(index);
            CosmicRegion descriptor = candidate.source().descriptor();
            double optical = smooth((candidate.importance() - 1e-7) / 9e-7);
            double budget = cutoff > 0 ? smooth((candidate.importance() - cutoff) / (cutoff * 0.35)) : 1;
            regions.add(new Region(descriptor, candidate.observerRadii(), slots.get(candidate.source().galaxy().index()),
                    shaderSeed(candidate.source().galaxy().seed() ^ descriptor.systemId().hashCode()), (float) (optical * budget)));
        }
        regions.sort(Comparator.comparingDouble((Region frame) -> frame.descriptor().centerLightYears()
                .distance(observerLightYears)).reversed().thenComparing(frame -> frame.descriptor().systemId()));
        return new UniverseFrame(galaxies, regions);
    }

    static float shaderSeed(long seed) {
        long mixed = seed ^ seed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return Math.floorMod(mixed, 4096);
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
