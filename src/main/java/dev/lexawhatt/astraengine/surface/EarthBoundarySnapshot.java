package dev.lexawhatt.astraengine.surface;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.HashSet;
import java.util.List;

/**
 * Bounded server-authored observations around one canonical player anchor. Does not authorize edits or travel.
 * Immutable data may be consumed on mesh workers. The connection owns revisions and retirement; no persistence
 * or second simulation clock is implied. A complete snapshot means requested sections were actually loaded.
 */
public record EarthBoundarySnapshot(long revision, CubeStorageChart source, SpaceVector anchorFeet,
        boolean complete, List<EarthBoundarySection> sections) {
    public static final int MAX_SECTIONS = 32;

    public EarthBoundarySnapshot {
        if (revision < 1 || source == null || !source.contains(anchorFeet) || sections == null
                || sections.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("Invalid Earth boundary observation header or budget");
        }
        var keys = new HashSet<Key>();
        for (EarthBoundarySection section : sections) {
            if (section == null || !section.chart().geographyId().equals(source.geographyId())
                    || !keys.add(new Key(section.chart(), section.section().asLong()))) {
                throw new IllegalArgumentException("Earth boundary observations require unique matching section identities");
            }
            var center = section.section().center();
            SpaceVector represented = new EarthChartTransform(section.chart(), source)
                    .position(new SpaceVector(center.getX(), center.getY(), center.getZ()));
            if (represented.distance(anchorFeet) > EarthChartRebase.MAX_EXTENSION_METERS) {
                throw new IllegalArgumentException("Earth boundary observation is outside the anchor neighborhood");
            }
        }
        sections = List.copyOf(sections);
    }

    /**
     * Whether this observation remains local to a source/observed chart pose. Ordinary distant teleports,
     * including into the opposite hemisphere of a previously neighboring face, return false rather than
     * evaluating a singular projection. Null and foreign terrain contexts also return false.
     */
    public boolean visibleFrom(CubeStorageChart chart, SpaceVector feet) {
        if (chart == null || feet == null || !chart.geographyId().equals(source.geographyId())
                || !chart.equals(source) && sections.stream().noneMatch(section -> section.chart().equals(chart))
                || Math.abs(feet.y() + chart.altitudeOriginMeters() - anchorFeet.y() - source.altitudeOriginMeters())
                        > EarthChartRebase.MAX_EXTENSION_METERS
                || chart.normal(feet.x(), feet.z()).dot(source.face().outward()) <= 0) { return false; }
        var represented = new EarthChartTransform(chart, source).position(feet);
        return represented.distance(anchorFeet) <= EarthChartRebase.MAX_EXTENSION_METERS;
    }

    private record Key(CubeStorageChart chart, long section) {}
}
